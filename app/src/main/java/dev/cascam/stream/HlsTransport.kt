package dev.cascam.stream

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import dev.cascam.config.VideoCodec

class HlsTransport(serverUrl: String, streamKey: String, private val videoCodec: VideoCodec, private val onStatus: (String) -> Unit) : MediaTransport {
    private val uploadPrefix = buildPrefix(serverUrl, streamKey)
    private val uploader = Executors.newSingleThreadExecutor()
    private val segment = ByteArrayOutputStream()
    private val playlist = ArrayDeque<Segment>()
    private val continuity = mutableMapOf<Int, Int>()
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
        if (keyFrame && segmentStartMs >= 0 && timestampMs - segmentStartMs >= SEGMENT_MS) finishSegment(timestampMs)
        if (segmentStartMs < 0) {
            if (!keyFrame) return
            startSegment(timestampMs)
        }
        val elementary = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1))
            write(if (videoCodec == VideoCodec.H265) byteArrayOf(0x46, 0x01, 0x50) else byteArrayOf(9, 0xf0.toByte()))
            if (keyFrame) {
                if (vps.isNotEmpty()) { write(byteArrayOf(0, 0, 0, 1)); write(vps) }
                if (sps.isNotEmpty()) { write(byteArrayOf(0, 0, 0, 1)); write(sps) }
                if (pps.isNotEmpty()) { write(byteArrayOf(0, 0, 0, 1)); write(pps) }
            }
            nals.forEach { write(byteArrayOf(0, 0, 0, 1)); write(it) }
        }.toByteArray()
        writePes(VIDEO_PID, 0xe0, elementary, timestampMs, timestampMs.toLong() * 90)
        lastTimestampMs = maxOf(lastTimestampMs, timestampMs)
    }

    @Synchronized override fun sendAudio(data: ByteArray, timestampMs: Int) {
        if (closed || segmentStartMs < 0) return
        val frameLength = data.size + 7
        val adts = byteArrayOf(
            0xff.toByte(), 0xf1.toByte(), 0x50, ((0x40) or (frameLength shr 11)).toByte(),
            (frameLength shr 3).toByte(), (((frameLength and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte(),
        )
        writePes(AUDIO_PID, 0xc0, adts + data, timestampMs, null)
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

    private fun writePes(pid: Int, streamId: Int, data: ByteArray, timestampMs: Int, pcr: Long?) {
        val pts = timestampMs.toLong() * 90
        val length = if (streamId == 0xe0) 0 else (data.size + 8).coerceAtMost(0xffff)
        val pes = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 1, streamId.toByte(), (length shr 8).toByte(), length.toByte(), 0x80.toByte(), 0x80.toByte(), 5))
            write(pts(pts)); write(data)
        }.toByteArray()
        writePackets(pid, pes, true, pcr)
    }

    private fun writeSection(pid: Int, section: ByteArray) = writePackets(pid, byteArrayOf(0) + section, true, null)
    private fun writePackets(pid: Int, payload: ByteArray, payloadStart: Boolean, pcr: Long?) {
        var offset = 0; var first = true
        while (offset < payload.size) {
            val includePcr = first && pcr != null
            val maximumPayload = if (includePcr) 176 else 184
            val count = minOf(maximumPayload, payload.size - offset)
            val needsAdaptation = includePcr || count < 184
            val packet = ByteArray(188) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = (((if (first && payloadStart) 0x40 else 0) or (pid shr 8)) and 0x5f).toByte()
            packet[2] = pid.toByte()
            val counter = continuity.getOrDefault(pid, 0); continuity[pid] = (counter + 1) and 15
            packet[3] = ((if (needsAdaptation) 0x30 else 0x10) or counter).toByte()
            var cursor = 4
            if (needsAdaptation) {
                val adaptationLength = 183 - count
                packet[cursor++] = adaptationLength.toByte()
                if (adaptationLength > 0) {
                    packet[cursor++] = if (includePcr) 0x10 else 0
                    if (includePcr) { pcrBytes(pcr!!).copyInto(packet, cursor); cursor += 6 }
                    cursor = 4 + 1 + adaptationLength
                }
            }
            payload.copyInto(packet, cursor, offset, offset + count)
            segment.write(packet); offset += count; first = false
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
    companion object {
        private const val VIDEO_PID = 0x100
        private const val AUDIO_PID = 0x101
        private const val PMT_PID = 0x1000
        private const val SEGMENT_MS = 2_000
        private const val WINDOW = 6

        private fun buildPrefix(server: String, key: String): String {
            var value = server.trim()
            if (value.contains("cid=&")) value = value.replace("cid=&", "cid=${URLEncoder.encode(key, "UTF-8")}&")
            if (!value.contains("file=")) value += if (value.contains('?')) "&file=" else "?file="
            return value
        }
        private fun pts(value: Long): ByteArray = byteArrayOf(
            ((0x20 or (((value shr 30).toInt() and 7) shl 1) or 1)).toByte(),
            (value shr 22).toByte(), ((((value shr 15).toInt() and 0x7f) shl 1) or 1).toByte(),
            (value shr 7).toByte(), (((value.toInt() and 0x7f) shl 1) or 1).toByte(),
        )
        private fun pcrBytes(base: Long): ByteArray = byteArrayOf(
            (base shr 25).toByte(), (base shr 17).toByte(), (base shr 9).toByte(), (base shr 1).toByte(),
            (((base and 1) shl 7) or 0x7e).toByte(), 0,
        )
        private fun pat(): ByteArray = withCrc(byteArrayOf(0, 0xb0.toByte(), 0x0d, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0))
        private fun pmt(codec: VideoCodec): ByteArray = withCrc(byteArrayOf(2, 0xb0.toByte(), 0x17, 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 0, 0xf0.toByte(), 0, (if (codec == VideoCodec.H265) 0x24 else 0x1b).toByte(), 0xe1.toByte(), 0, 0xf0.toByte(), 0, 0x0f, 0xe1.toByte(), 1, 0xf0.toByte(), 0))
        private fun withCrc(section: ByteArray): ByteArray {
            var crc = -1
            section.forEach { byte -> repeat(8) { crc = if (((crc ushr 31) xor ((byte.toInt() ushr (7 - it)) and 1)) != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 } }
            return section + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        }
    }
}
