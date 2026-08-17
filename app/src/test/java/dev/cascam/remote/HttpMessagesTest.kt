package dev.cascam.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpMessagesTest {
    private fun parse(raw: String) = HttpMessages.readRequest(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    @Test
    fun `le metodo caminho e cabecalhos`() {
        val request = parse("GET /api/config HTTP/1.1\r\nHost: sportcam\r\nAuthorization: Basic eA==\r\n\r\n")!!
        assertEquals("GET", request.method)
        assertEquals("/api/config", request.path)
        assertEquals("Basic eA==", request.header("authorization"))
        // O nome do cabeçalho vem do navegador com a capitalização que ele quiser.
        assertEquals("Basic eA==", request.header("AUTHORIZATION"))
        assertEquals(0, request.body.size)
    }

    @Test
    fun `le o corpo pelo content-length`() {
        val corpo = """{"outputFps":30}"""
        val request = parse("POST /api/config HTTP/1.1\r\nContent-Length: ${corpo.length}\r\n\r\n$corpo")!!
        assertEquals("POST", request.method)
        assertEquals(corpo, request.bodyText)
    }

    @Test
    fun `separa a query do caminho`() {
        val request = parse("GET /api/preview/court.jpg?t=12&vazio HTTP/1.1\r\n\r\n")!!
        assertEquals("/api/preview/court.jpg", request.path)
        assertEquals("12", request.query["t"])
        assertEquals("", request.query["vazio"])
    }

    @Test
    fun `caminho com escape volta decodificado`() {
        assertEquals("/api/preview/quadra imagem.jpg", parse("GET /api/preview/quadra%20imagem.jpg HTTP/1.1\r\n\r\n")!!.path)
    }

    @Test
    fun `conexao fechada sem requisicao devolve nulo`() {
        assertNull(parse(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `linha de requisicao malformada e recusada`() {
        parse("SOCORRO\r\n\r\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `corpo maior que o teto e recusado`() {
        parse("POST /api/config HTTP/1.1\r\nContent-Length: ${HttpMessages.MAX_BODY_BYTES + 1}\r\n\r\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `corpo cortado no meio e recusado`() {
        parse("POST /api/config HTTP/1.1\r\nContent-Length: 40\r\n\r\ncurto")
    }

    @Test
    fun `a resposta traz tamanho tipo e o desafio quando e 401`() {
        val saida = ByteArrayOutputStream()
        HttpMessages.writeResponse(saida, HttpResponse.unauthorized())
        val texto = saida.toString("UTF-8")
        assertTrue(texto.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertTrue(texto.contains("WWW-Authenticate: Basic realm=\"SportCam\""))
        assertTrue(texto.contains("Content-Length: ${"Autenticação necessária".toByteArray().size}"))
        assertTrue(texto.contains("Cache-Control: no-store"))
    }

    @Test
    fun `corpo binario sai intacto depois do cabecalho`() {
        val saida = ByteArrayOutputStream()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x0A, 0xFF.toByte(), 0xD9.toByte())
        HttpMessages.writeResponse(saida, HttpResponse.jpeg(jpeg))
        val bytes = saida.toByteArray()
        assertTrue(jpeg.contentEquals(bytes.copyOfRange(bytes.size - jpeg.size, bytes.size)))
    }
}
