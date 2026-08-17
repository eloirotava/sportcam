package dev.cascam.remote

import java.io.InputStream
import java.io.OutputStream

/**
 * HTTP/1.1 no mínimo necessário para servir o site de configuração: linha de requisição,
 * cabeçalhos e corpo delimitado por `Content-Length`. Sem `chunked` na entrada, sem keep-alive,
 * sem negociação de conteúdo — o único cliente é o navegador que o operador abre, e cada
 * requisição fecha a conexão.
 *
 * Fica em funções puras sobre `InputStream`/`OutputStream` de propósito: é o que permite testar
 * o parsing em JUnit sem subir socket nenhum.
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val bodyText: String get() = String(body, Charsets.UTF_8)

    // data class com ByteArray: equals/hashCode gerados comparariam a referência do array.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        fun text(status: Int, message: String) =
            HttpResponse(status, "text/plain; charset=utf-8", message.toByteArray(Charsets.UTF_8))

        fun json(body: String) =
            HttpResponse(200, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

        fun jpeg(bytes: ByteArray) = HttpResponse(200, "image/jpeg", bytes)

        /** 401 com o desafio que faz o navegador abrir a caixa de usuário e senha. */
        fun unauthorized() = HttpResponse(
            401, "text/plain; charset=utf-8",
            "Autenticação necessária".toByteArray(Charsets.UTF_8),
            listOf("WWW-Authenticate" to "Basic realm=\"SportCam\", charset=\"UTF-8\""),
        )
    }
}

object HttpMessages {
    /** Teto do corpo aceito. O maior POST previsto é a configuração inteira, uns poucos kilobytes. */
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    private const val MAX_LINE_BYTES = 8 * 1024
    private const val MAX_HEADERS = 64

    /** Devolve `null` quando o cliente fechou a conexão antes de mandar a linha de requisição. */
    fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        require(parts.size == 3) { "Linha de requisição inválida: $requestLine" }
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            require(headers.size < MAX_HEADERS) { "Cabeçalhos demais" }
            val separator = line.indexOf(':')
            require(separator > 0) { "Cabeçalho inválido: $line" }
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..MAX_BODY_BYTES) { "Corpo de $length bytes acima do limite" }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            require(count > 0) { "Conexão encerrada no meio do corpo" }
            read += count
        }
        val separator = target.indexOf('?')
        val path = if (separator < 0) target else target.substring(0, separator)
        val query = if (separator < 0) emptyMap() else parseQuery(target.substring(separator + 1))
        return HttpRequest(method, decodePath(path), query, headers, body)
    }

    fun writeResponse(output: OutputStream, response: HttpResponse) {
        val header = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n")
            // O site é privado e muda a cada salvamento; cache aqui só serviria para mostrar
            // configuração velha depois de um ajuste feito no telefone.
            append("Cache-Control: no-store\r\n")
            response.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    fun parseQuery(query: String): Map<String, String> = query.split('&')
        .filter(String::isNotEmpty)
        .associate { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) decodePath(pair) to ""
            else decodePath(pair.substring(0, separator)) to decodePath(pair.substring(separator + 1))
        }

    private fun decodePath(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** Lê até CRLF sem bufferizar além da linha, para o corpo continuar intacto no stream. */
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (byte == '\n'.code) return buffer.removeSuffix("\r").toString()
            buffer.append(byte.toChar())
            require(buffer.length <= MAX_LINE_BYTES) { "Linha acima de $MAX_LINE_BYTES bytes" }
        }
    }

    private fun StringBuilder.removeSuffix(suffix: String): StringBuilder =
        if (endsWith(suffix)) also { setLength(length - suffix.length) } else this

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status $status"
    }
}
