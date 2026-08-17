package dev.cascam.tunnel

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente WebSocket (RFC 6455) sobre TLS, escrito à mão como o [dev.cascam.stream.RtmpClient]:
 * `SSLSocket` do JDK, streams bufferizados e escrita sincronizada. O cf-p não usa subprotocolo
 * nem cabeçalho extra — a URL configurada é usada como está.
 *
 * O keepalive é responsabilidade nossa: o cf-p não tem ping de aplicação e não desconecta sessão
 * ociosa, mas o 4G e o proxy no meio desconectam. Em vez de uma thread só para isso, o tempo
 * limite de leitura serve de relógio: quando a leitura estoura sem nada ter chegado, sai um ping.
 */
class WebSocketClient(private val url: String) : AutoCloseable {
    private lateinit var socket: SSLSocket
    private lateinit var input: BufferedInputStream
    private lateinit var output: BufferedOutputStream
    private val random = SecureRandom()

    fun connect() {
        val uri = URI(url)
        require(uri.scheme == "wss") { "O endereço do cf-p precisa começar com wss://" }
        val host = requireNotNull(uri.host) { "Endereço do cf-p sem host: $url" }
        val port = if (uri.port > 0) uri.port else 443
        val path = uri.rawPath.orEmpty().ifBlank { "/" } +
            uri.rawQuery?.let { "?$it" }.orEmpty()
        socket = (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply {
            soTimeout = PING_INTERVAL_MILLIS
            tcpNoDelay = true
            startHandshake()
        }
        input = BufferedInputStream(socket.getInputStream())
        output = BufferedOutputStream(socket.getOutputStream())
        handshake(host, port, path)
    }

    private fun handshake(host: String, port: Int, path: String) {
        val key = ByteArray(16).also(random::nextBytes).let { Base64.getEncoder().encodeToString(it) }
        val hostHeader = if (port == 443) host else "$host:$port"
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        output.write(request.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val statusLine = readHeaderLine()
        require(statusLine.startsWith("HTTP/1.1 101") || statusLine.startsWith("HTTP/1.0 101")) {
            "O servidor recusou o upgrade: $statusLine"
        }
        var accept: String? = null
        while (true) {
            val line = readHeaderLine()
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0 && line.substring(0, separator).equals("Sec-WebSocket-Accept", true)) {
                accept = line.substring(separator + 1).trim()
            }
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1)),
        )
        require(accept == expected) { "Sec-WebSocket-Accept não confere; o outro lado não é um WebSocket" }
    }

    private fun readHeaderLine(): String {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Conexão encerrada durante o handshake WebSocket")
            if (byte == '\n'.code) return buffer.toString().removeSuffix("\r")
            buffer.append(byte.toChar())
            require(buffer.length <= MAX_HEADER_LINE) { "Cabeçalho do handshake longo demais" }
        }
    }

    /**
     * Próxima mensagem binária, já remontada. Texto é descartado — o cf-p só fala binário — e
     * ping/pong é resolvido aqui dentro, sem subir para o chamador.
     */
    fun readBinaryMessage(): ByteArray {
        var assembled: ByteArray? = null
        var messageOpcode = 0
        while (true) {
            val frame = try {
                readFrame()
            } catch (timeout: SocketTimeoutException) {
                // Nada chegou dentro do intervalo: aproveita para provar que a sessão está viva.
                sendControl(OPCODE_PING, ByteArray(0))
                continue
            }
            when (frame.opcode) {
                OPCODE_PING -> { sendControl(OPCODE_PONG, frame.payload); continue }
                OPCODE_PONG -> continue
                OPCODE_CLOSE -> throw EOFException("O servidor fechou o WebSocket")
                OPCODE_CONTINUATION -> {
                    val current = assembled ?: throw IllegalStateException("Continuação sem quadro inicial")
                    assembled = current + frame.payload
                }
                else -> {
                    messageOpcode = frame.opcode
                    assembled = frame.payload
                }
            }
            if (!frame.fin) continue
            val message = assembled ?: ByteArray(0)
            assembled = null
            if (messageOpcode == OPCODE_BINARY) return message
            // Texto ou opcode desconhecido: o cf-p ignora, e ignorar é mais seguro que abortar.
        }
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /**
     * O primeiro byte pode estourar o tempo limite sem problema: é o silêncio entre quadros que
     * vira ping. Do segundo em diante estamos no meio de um quadro, e parar ali não é ociosidade —
     * é conexão quebrada. Sem essa separação, um ping enviado no meio de um quadro deixaria a
     * leitura fora de sincronia com o que o servidor está mandando.
     */
    private fun readFrame(): Frame {
        val first = readByte()
        socket.soTimeout = MID_FRAME_TIMEOUT_MILLIS
        try {
            return readFrameBody(first)
        } catch (timeout: SocketTimeoutException) {
            throw EOFException("Quadro WebSocket interrompido no meio")
        } finally {
            socket.soTimeout = PING_INTERVAL_MILLIS
        }
    }

    private fun readFrameBody(first: Int): Frame {
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val second = readByte()
        require(second and 0x80 == 0) { "O servidor não pode mascarar os quadros" }
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
            length = (readByte().toLong() shl 8) or readByte().toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte().toLong() }
        }
        require(length in 0..MAX_MESSAGE_BYTES) { "Quadro de $length bytes acima do limite" }
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < payload.size) {
            val count = input.read(payload, read, payload.size - read)
            if (count <= 0) throw EOFException("Conexão encerrada no meio de um quadro WebSocket")
            read += count
        }
        return Frame(fin, opcode, payload)
    }

    private fun readByte(): Int {
        val byte = input.read()
        if (byte < 0) throw EOFException("Conexão WebSocket encerrada")
        return byte
    }

    fun sendBinary(payload: ByteArray) = sendControl(OPCODE_BINARY, payload)

    /**
     * O cliente é obrigado a mascarar todo quadro que envia; o servidor recusa a conexão sem isso.
     * Sincronizado porque várias threads de stream escrevem na mesma sessão.
     */
    @Synchronized
    private fun sendControl(opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        val mask = ByteArray(4).also(random::nextBytes)
        when {
            payload.size < 126 -> output.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                output.write(0x80 or 126)
                output.write(payload.size ushr 8 and 0xFF)
                output.write(payload.size and 0xFF)
            }
            else -> {
                output.write(0x80 or 127)
                repeat(8) { index -> output.write((payload.size.toLong() ushr (56 - index * 8)).toInt() and 0xFF) }
            }
        }
        output.write(mask)
        val masked = ByteArray(payload.size)
        for (index in payload.indices) masked[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        output.write(masked)
        output.flush()
    }

    override fun close() {
        runCatching { sendControl(OPCODE_CLOSE, ByteArray(0)) }
        runCatching { socket.close() }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
        const val MAX_HEADER_LINE = 8 * 1024
        const val MAX_MESSAGE_BYTES = 16L * 1024 * 1024
        const val PING_INTERVAL_MILLIS = 25_000
        const val MID_FRAME_TIMEOUT_MILLIS = 60_000
    }
}
