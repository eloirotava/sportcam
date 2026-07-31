package dev.cascam.stream

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

class RtmpClient(serverUrl: String, private val streamKey: String) : AutoCloseable {
    private val uri = URI(serverUrl)
    private lateinit var input: BufferedInputStream
    private lateinit var output: BufferedOutputStream
    private var outputChunkSize = 128
    private var inputChunkSize = 128
    private var publishedStreamId = 1

    fun connect() {
        require(uri.scheme == "rtmps") { "A publicação exige uma URL rtmps://" }
        val socket = SSLSocketFactory.getDefault().createSocket(uri.host, if (uri.port > 0) uri.port else 443)
        input = BufferedInputStream(socket.getInputStream())
        output = BufferedOutputStream(socket.getOutputStream())
        handshake()
        command(3, 0, "connect", 1.0, mapOf(
            "app" to uri.path.trim('/'), "type" to "nonprivate", "tcUrl" to uri.toString(),
            "flashVer" to "FMLE/3.0 (compatible; CasCam/0.1)", "fpad" to false,
            "capabilities" to 15.0, "audioCodecs" to 0.0, "videoCodecs" to 252.0,
            "videoFunction" to 1.0, "objectEncoding" to 0.0,
        ))
        waitForResult(1.0)
        sendMessage(2, 1, 0, intBytes(4096))
        outputChunkSize = 4096
        command(3, 0, "createStream", 2.0, null)
        publishedStreamId = waitForResult(2.0).toInt()
        command(8, publishedStreamId, "publish", 0.0, null, streamKey, "live")
        waitForPublishStart()
    }

    fun sendAvcSequenceHeader(sps: ByteArray, pps: ByteArray) {
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x17, 0, 0, 0, 0))
            write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte()))
            write(shortBytes(sps.size)); write(sps)
            write(1); write(shortBytes(pps.size)); write(pps)
        }.toByteArray()
        sendMessage(6, 9, publishedStreamId, body)
    }

    fun sendMetadata(width: Int, height: Int, frameRate: Int, videoBitrate: Int) {
        val payload = Amf0.encode(listOf("@setDataFrame", "onMetaData", mapOf(
            "width" to width.toDouble(), "height" to height.toDouble(),
            "framerate" to frameRate.toDouble(), "videodatarate" to videoBitrate / 1_000.0,
            "videocodecid" to 7.0, "audiocodecid" to 10.0,
            "audiosamplerate" to 44_100.0, "audiosamplesize" to 16.0, "stereo" to false,
            "encoder" to "CasCam Android",
        )))
        sendMessage(5, 18, publishedStreamId, payload)
    }

    fun sendAacSequenceHeader(config: ByteArray) {
        sendMessage(4, 8, publishedStreamId, byteArrayOf(0xae.toByte(), 0) + config)
    }

    fun sendAudio(data: ByteArray, timestampMs: Int) {
        sendMessage(4, 8, publishedStreamId, byteArrayOf(0xae.toByte(), 1) + data, timestampMs)
    }

    fun sendVideo(nals: List<ByteArray>, timestampMs: Int, keyFrame: Boolean) {
        val body = ByteArrayOutputStream().apply {
            write(if (keyFrame) 0x17 else 0x27); write(byteArrayOf(1, 0, 0, 0))
            nals.forEach { write(intBytes(it.size)); write(it) }
        }.toByteArray()
        sendMessage(6, 9, publishedStreamId, body, timestampMs)
    }

    private fun handshake() {
        val c1 = ByteArray(1536).also { SecureRandom().nextBytes(it) }
        c1.fill(0, 0, 8)
        output.write(3); output.write(c1); output.flush()
        require(input.read() == 3) { "Servidor RTMP respondeu com versão inválida" }
        val s1 = readBytes(1536)
        readBytes(1536)
        output.write(s1); output.flush()
    }

    private fun command(chunkStreamId: Int, messageStreamId: Int, name: String, transaction: Double, commandObject: Any?, vararg arguments: Any?) {
        val payload = Amf0.encode(listOf(name, transaction, commandObject, *arguments))
        sendMessage(chunkStreamId, 20, messageStreamId, payload)
    }

    private fun waitForResult(transaction: Double): Double {
        while (true) {
            val message = readMessage()
            if (message.type == 1 && message.payload.size >= 4) inputChunkSize = readInt(message.payload, 0)
            if (message.type != 20) continue
            val values = Amf0.decode(message.payload)
            if (values.firstOrNull() == "_error") error("Servidor RTMP rejeitou a conexão: $values")
            if (values.firstOrNull() == "_result" && values.getOrNull(1) == transaction) {
                return (values.lastOrNull { it is Double } as? Double) ?: transaction
            }
        }
    }

    private fun waitForPublishStart() {
        while (true) {
            val message = readMessage()
            if (message.type == 1 && message.payload.size >= 4) { inputChunkSize = readInt(message.payload, 0); continue }
            if (message.type != 20) continue
            val values = Amf0.decode(message.payload)
            val status = values.filterIsInstance<Map<*, *>>().lastOrNull()
            val code = status?.get("code")?.toString().orEmpty()
            if (code == "NetStream.Publish.Start") return
            if (code.contains("Failed") || code.contains("BadName") || code.contains("Denied")) {
                error("YouTube recusou a publicação: $code")
            }
        }
    }

    @Synchronized private fun sendMessage(csid: Int, type: Int, streamId: Int, payload: ByteArray, timestamp: Int = 0) {
        var offset = 0
        while (offset < payload.size) {
            output.write(if (offset == 0) csid else 0xc0 or csid)
            if (offset == 0) {
                writeMedium(timestamp.coerceAtMost(0xffffff)); writeMedium(payload.size); output.write(type)
                output.write(byteArrayOf((streamId and 255).toByte(), (streamId shr 8 and 255).toByte(), (streamId shr 16 and 255).toByte(), (streamId shr 24 and 255).toByte()))
                if (timestamp >= 0xffffff) output.write(intBytes(timestamp))
            } else if (timestamp >= 0xffffff) output.write(intBytes(timestamp))
            val count = minOf(outputChunkSize, payload.size - offset)
            output.write(payload, offset, count); offset += count
        }
        output.flush()
    }

    private data class Incoming(val type: Int, val payload: ByteArray)
    private data class Header(var timestamp: Int = 0, var length: Int = 0, var type: Int = 0, var streamId: Int = 0)
    private val headers = mutableMapOf<Int, Header>()
    private fun readMessage(): Incoming {
        while (true) {
            val basic = input.read(); require(basic >= 0)
            val format = basic shr 6; val csid = basic and 0x3f
            require(csid >= 2) { "Chunk stream RTMP estendido não suportado" }
            val header = headers.getOrPut(csid) { Header() }
            if (format <= 2) header.timestamp = readMedium()
            if (format <= 1) { header.length = readMedium(); header.type = input.read() }
            if (format == 0) header.streamId = readLittleInt()
            if (header.timestamp == 0xffffff) header.timestamp = readBigInt()
            val payload = ByteArrayOutputStream(header.length)
            var remaining = header.length
            var first = true
            while (remaining > 0) {
                if (!first) {
                    val continuation = input.read(); require(continuation and 0x3f == csid)
                    if (header.timestamp >= 0xffffff) readBytes(4)
                }
                val part = readBytes(minOf(inputChunkSize, remaining))
                payload.write(part); remaining -= part.size; first = false
            }
            return Incoming(header.type, payload.toByteArray())
        }
    }

    private fun writeMedium(value: Int) { output.write(value shr 16 and 255); output.write(value shr 8 and 255); output.write(value and 255) }
    private fun readMedium() = (input.read() shl 16) or (input.read() shl 8) or input.read()
    private fun readLittleInt() = input.read() or (input.read() shl 8) or (input.read() shl 16) or (input.read() shl 24)
    private fun readBigInt() = readInt(readBytes(4), 0)
    private fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            require(read > 0) { "Conexão RTMP encerrada inesperadamente" }
            offset += read
        }
        return bytes
    }
    override fun close() { runCatching { output.close() }; runCatching { input.close() } }

    private object Amf0 {
        fun encode(values: List<Any?>): ByteArray = ByteArrayOutputStream().also { output -> values.forEach { write(output, it) } }.toByteArray()
        private fun write(out: ByteArrayOutputStream, value: Any?): Unit {
            when (value) {
                null -> out.write(5)
                is String -> { out.write(2); out.write(shortBytes(value.toByteArray().size)); out.write(value.toByteArray()) }
                is Number -> { out.write(0); DataOutputStream(out).writeDouble(value.toDouble()) }
                is Boolean -> { out.write(1); out.write(if (value) 1 else 0) }
                is Map<*, *> -> {
                    out.write(3)
                    value.forEach { (key, item) ->
                        val encodedKey = key.toString().toByteArray()
                        out.write(shortBytes(encodedKey.size))
                        out.write(encodedKey)
                        write(out, item)
                    }
                    out.write(byteArrayOf(0, 0, 9))
                }
                else -> error("Tipo AMF não suportado")
            }
        }
        fun decode(bytes: ByteArray): List<Any?> {
            var offset = 0
            fun value(): Any? {
                return when (bytes[offset++].toInt() and 255) {
                    0 -> java.lang.Double.longBitsToDouble(java.nio.ByteBuffer.wrap(bytes, offset, 8).long).also { offset += 8 }
                    1 -> bytes[offset++].toInt() != 0
                    2 -> { val length = ((bytes[offset++].toInt() and 255) shl 8) or (bytes[offset++].toInt() and 255); String(bytes, offset, length).also { offset += length } }
                    3, 8 -> {
                        if (bytes[offset - 1].toInt() and 255 == 8) offset += 4
                        val result = mutableMapOf<String, Any?>()
                        while (!(bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() && bytes[offset + 2] == 9.toByte())) {
                            val length = ((bytes[offset++].toInt() and 255) shl 8) or (bytes[offset++].toInt() and 255)
                            val key = String(bytes, offset, length); offset += length
                            result[key] = value()
                        }
                        offset += 3; result
                    }
                    5, 6 -> null
                    else -> null
                }
            }
            val result = mutableListOf<Any?>(); while (offset < bytes.size) result += value(); return result
        }
    }

    companion object {
        private fun shortBytes(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
        private fun intBytes(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
        private fun readInt(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
    }
}
