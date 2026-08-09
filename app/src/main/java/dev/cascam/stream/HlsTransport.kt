package dev.cascam.stream

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import dev.cascam.config.VideoCodec

class HlsTransport(
    serverUrl: String,
    streamKey: String,
    private val videoCodec: VideoCodec,
    /** O YouTube aceita de 1 a 4 s por segmento, e o segmento entra inteiro na latência. */
    private val segmentMillis: Int = 2_000,
    private val onStatus: (String) -> Unit,
) : MediaTransport {
    private val uploadPrefix = buildPrefix(serverUrl, streamKey)
    private val uploader = Executors.newSingleThreadExecutor()
    private val segment = ByteArrayOutputStream()
    private val playlist = ArrayDeque<Segment>()
    private val continuity = mutableMapOf<Int, Int>()
    private val packetScratch = ByteArray(TS_PACKET_SIZE)
    private var sps = ByteArray(0)
    private var pps = ByteArray(0)
    private var vps = ByteArray(0)
    private var sequence = 0
    private var segmentStartMs = -1
    private var lastTimestampMs = 0
    private var closed = false

    override fun connect() = Unit
    override fun sendMetadata(width: Int, height: Int, frameRate: Int, videoBitrate: Int) = Unit
    override fun sendVideoConfig(codec: VideoCodec, vps: ByteArray?, sps: ByteArray, pps: ByteArray) {
        require(codec == videoCodec)
        this.vps = vps ?: ByteArray(0); this.sps = sps; this.pps = pps
    }
    override fun sendAacSequenceHeader(config: ByteArray) = Unit

    @Synchronized override fun sendVideo(nals: List<ByteArray>, timestampMs: Int, keyFrame: Boolean) {
        if (closed) return
        if (keyFrame && segmentStartMs >= 0 && timestampMs - segmentStartMs >= segmentMillis) finishSegment(timestampMs)
        if (segmentStartMs < 0) {
            if (!keyFrame) return
            startSegment(timestampMs)
        }
        val elementary = ArrayList<ByteArray>(nals.size * 2 + 8).apply {
            add(if (videoCodec == VideoCodec.H265) H265_ACCESS_UNIT else H264_ACCESS_UNIT)
            if (keyFrame) {
                if (vps.isNotEmpty()) { add(START_CODE); add(vps) }
                if (sps.isNotEmpty()) { add(START_CODE); add(sps) }
                if (pps.isNotEmpty()) { add(START_CODE); add(pps) }
            }
            nals.forEach { add(START_CODE); add(it) }
        }
        writePes(VIDEO_PID, 0xe0, elementary, elementary.sumOf { it.size }, timestampMs, timestampMs.toLong() * 90)
        lastTimestampMs = maxOf(lastTimestampMs, timestampMs)
    }

    @Synchronized override fun sendAudio(data: ByteArray, timestampMs: Int) {
        if (closed || segmentStartMs < 0) return
        val frameLength = data.size + 7
        val adts = byteArrayOf(
            0xff.toByte(), 0xf1.toByte(), 0x50, ((0x40) or (frameLength shr 11)).toByte(),
            (frameLength shr 3).toByte(), (((frameLength and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte(),
        )
        writePes(AUDIO_PID, 0xc0, listOf(adts, data), adts.size + data.size, timestampMs, null)
        lastTimestampMs = maxOf(lastTimestampMs, timestampMs)
    }

    private fun startSegment(timestampMs: Int) {
        segment.reset(); continuity.clear(); segmentStartMs = timestampMs
        writeSection(0, pat()); writeSection(PMT_PID, pmt(videoCodec))
    }

    private fun finishSegment(nextStartMs: Int) {
        if (segment.size() == 0) return
        val currentSequence = sequence++
        val item = Segment(currentSequence, "segment-${currentSequence.toString().padStart(6, '0')}.ts", (nextStartMs - segmentStartMs) / 1_000.0, segment.toByteArray())
        playlist.addLast(item); while (playlist.size > WINDOW) playlist.removeFirst()
        val snapshot = playlist.toList()
        uploader.execute {
            try {
                upload(item.name, item.bytes, "video/mp2t")
                upload("stream.m3u8", playlist(snapshot), "application/vnd.apple.mpegurl")
                onStatus("● HLS · segmento ${item.sequence} enviado · ${"%.2f".format(java.util.Locale.US, item.duration)} s")
            } catch (error: Exception) {
                onStatus("Falha no upload HLS: ${error.message ?: error.javaClass.simpleName}")
            }
        }
        segmentStartMs = -1
    }

    private fun writePes(
        pid: Int,
        streamId: Int,
        data: List<ByteArray>,
        dataSize: Int,
        timestampMs: Int,
        pcr: Long?,
    ) {
        val pts = timestampMs.toLong() * 90
        val length = if (streamId == 0xe0) 0 else (dataSize + 8).coerceAtMost(0xffff)
        val header = ByteArray(14)
        header[2] = 1
        header[3] = streamId.toByte()
        header[4] = (length shr 8).toByte()
        header[5] = length.toByte()
        header[6] = 0x80.toByte()
        header[7] = 0x80.toByte()
        header[8] = 5
        writePts(header, 9, pts)
        writePackets(pid, header, data, dataSize, true, pcr)
    }

    private fun writeSection(pid: Int, section: ByteArray) =
        writePackets(pid, POINTER_FIELD, listOf(section), section.size, true, null)

    private fun writePackets(
        pid: Int,
        prefix: ByteArray,
        payloadParts: List<ByteArray>,
        payloadSize: Int,
        payloadStart: Boolean,
        pcr: Long?,
    ) {
        val payloadCursor = PayloadCursor(prefix, payloadParts)
        var remaining = prefix.size + payloadSize
        var first = true
        while (remaining > 0) {
            val includePcr = first && pcr != null
            val maximumPayload = if (includePcr) 176 else 184
            val count = minOf(maximumPayload, remaining)
            val needsAdaptation = includePcr || count < 184
            packetScratch.fill(0xff.toByte())
            packetScratch[0] = 0x47
            packetScratch[1] = (((if (first && payloadStart) 0x40 else 0) or (pid shr 8)) and 0x5f).toByte()
            packetScratch[2] = pid.toByte()
            val counter = continuity.getOrDefault(pid, 0); continuity[pid] = (counter + 1) and 15
            packetScratch[3] = ((if (needsAdaptation) 0x30 else 0x10) or counter).toByte()
            var packetCursor = 4
            if (needsAdaptation) {
                val adaptationLength = 183 - count
                packetScratch[packetCursor++] = adaptationLength.toByte()
                if (adaptationLength > 0) {
                    packetScratch[packetCursor++] = if (includePcr) 0x10 else 0
                    if (includePcr) { writePcr(packetScratch, packetCursor, pcr!!); packetCursor += 6 }
                    packetCursor = 4 + 1 + adaptationLength
                }
            }
            payloadCursor.copyInto(packetScratch, packetCursor, count)
            segment.write(packetScratch, 0, TS_PACKET_SIZE)
            remaining -= count
            first = false
        }
    }

    private fun playlist(items: List<Segment>): ByteArray = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        append("#EXT-X-TARGETDURATION:${maxOf(2, ceil(items.maxOf { it.duration }).toInt())}\n")
        append("#EXT-X-MEDIA-SEQUENCE:${items.first().sequence}\n")
        items.forEach { append("#EXTINF:${"%.3f".format(java.util.Locale.US, it.duration)},\n${it.name}\n") }
    }.toByteArray()

    private fun upload(name: String, bytes: ByteArray, contentType: String) {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                val connection = URL(uploadPrefix + URLEncoder.encode(name, "UTF-8")).openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"; connection.doOutput = true
                connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val response = connection.responseCode
                if (response !in 200..299) error("Upload HLS $name retornou HTTP $response")
                connection.disconnect(); return
            } catch (error: Exception) {
                failure = error
                if (attempt < 2) Thread.sleep(500L shl attempt)
            }
        }
        throw failure ?: IllegalStateException("Falha no upload HLS")
    }

    @Synchronized override fun close() {
        if (closed) return
        if (segmentStartMs >= 0 && segment.size() > 0) finishSegment(maxOf(lastTimestampMs, segmentStartMs + 1))
        closed = true; uploader.shutdown(); uploader.awaitTermination(10, TimeUnit.SECONDS)
    }

    private data class Segment(val sequence: Int, val name: String, val duration: Double, val bytes: ByteArray)

    private class PayloadCursor(
        private val prefix: ByteArray,
        private val parts: List<ByteArray>,
    ) {
        private var inPrefix = true
        private var partIndex = 0
        private var partOffset = 0

        fun copyInto(destination: ByteArray, destinationOffset: Int, byteCount: Int) {
            var target = destinationOffset
            var remaining = byteCount
            while (remaining > 0) {
                val source = if (inPrefix) prefix else parts[partIndex]
                val available = source.size - partOffset
                val copied = minOf(available, remaining)
                source.copyInto(destination, target, partOffset, partOffset + copied)
                target += copied
                remaining -= copied
                partOffset += copied
                if (partOffset == source.size) {
                    partOffset = 0
                    if (inPrefix) inPrefix = false else partIndex++
                }
            }
        }
    }

    companion object {
        private const val VIDEO_PID = 0x100
        private const val AUDIO_PID = 0x101
        private const val PMT_PID = 0x1000
        private const val WINDOW = 6
        private const val TS_PACKET_SIZE = 188
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val H264_ACCESS_UNIT = byteArrayOf(0, 0, 0, 1, 9, 0xf0.toByte())
        private val H265_ACCESS_UNIT = byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50)
        private val POINTER_FIELD = byteArrayOf(0)

        private fun buildPrefix(server: String, key: String): String {
            var value = server.trim()
            if (value.contains("cid=&")) value = value.replace("cid=&", "cid=${URLEncoder.encode(key, "UTF-8")}&")
            if (!value.contains("file=")) value += if (value.contains('?')) "&file=" else "?file="
            return value
        }
        private fun writePts(destination: ByteArray, offset: Int, value: Long) {
            destination[offset] = (0x20 or (((value shr 30).toInt() and 7) shl 1) or 1).toByte()
            destination[offset + 1] = (value shr 22).toByte()
            destination[offset + 2] = ((((value shr 15).toInt() and 0x7f) shl 1) or 1).toByte()
            destination[offset + 3] = (value shr 7).toByte()
            destination[offset + 4] = (((value.toInt() and 0x7f) shl 1) or 1).toByte()
        }

        private fun writePcr(destination: ByteArray, offset: Int, base: Long) {
            destination[offset] = (base shr 25).toByte()
            destination[offset + 1] = (base shr 17).toByte()
            destination[offset + 2] = (base shr 9).toByte()
            destination[offset + 3] = (base shr 1).toByte()
            destination[offset + 4] = (((base and 1) shl 7) or 0x7e).toByte()
            destination[offset + 5] = 0
        }
        private fun pat(): ByteArray = withCrc(byteArrayOf(0, 0xb0.toByte(), 0x0d, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0))
        private fun pmt(codec: VideoCodec): ByteArray = withCrc(byteArrayOf(2, 0xb0.toByte(), 0x17, 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 0, 0xf0.toByte(), 0, (if (codec == VideoCodec.H265) 0x24 else 0x1b).toByte(), 0xe1.toByte(), 0, 0xf0.toByte(), 0, 0x0f, 0xe1.toByte(), 1, 0xf0.toByte(), 0))
        private fun withCrc(section: ByteArray): ByteArray {
            var crc = -1
            section.forEach { byte -> repeat(8) { crc = if (((crc ushr 31) xor ((byte.toInt() ushr (7 - it)) and 1)) != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 } }
            return section + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        }
    }
}
