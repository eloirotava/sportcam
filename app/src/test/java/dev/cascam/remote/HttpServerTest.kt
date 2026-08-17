package dev.cascam.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket

/**
 * Sobe o servidor num socket de verdade e conversa com ele. É o único ponto em que o laço de
 * accept, o parsing e a escrita da resposta são exercitados juntos — e nada aqui depende do
 * Android, então roda no JUnit do módulo como qualquer outro teste.
 */
class HttpServerTest {
    private fun conversar(pedido: String, handler: (HttpRequest) -> HttpResponse): String {
        // Porta zero: o sistema escolhe uma livre, e dois testes em paralelo não brigam.
        val server = HttpServer(0, handler)
        server.use {
            it.start()
            Socket("127.0.0.1", it.boundPort).use { cliente ->
                cliente.soTimeout = 5_000
                val saida = BufferedOutputStream(cliente.getOutputStream())
                saida.write(pedido.toByteArray(Charsets.UTF_8))
                saida.flush()
                return BufferedInputStream(cliente.getInputStream()).readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    @Test
    fun `entrega a resposta do roteador`() {
        val resposta = conversar("GET /api/config HTTP/1.1\r\nHost: x\r\n\r\n") { request ->
            assertEquals("/api/config", request.path)
            HttpResponse.json("""{"outputFps":30}""")
        }
        assertTrue(resposta.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(resposta.contains("Content-Type: application/json; charset=utf-8"))
        assertTrue(resposta.endsWith("""{"outputFps":30}"""))
    }

    @Test
    fun `o corpo do post chega inteiro no roteador`() {
        val corpo = """{"cropZoom":2.5,"liveTitle":"Liceu"}"""
        var recebido = ""
        conversar("POST /api/config HTTP/1.1\r\nContent-Length: ${corpo.toByteArray().size}\r\n\r\n$corpo") { request ->
            recebido = request.bodyText
            HttpResponse.text(200, "ok")
        }
        assertEquals(corpo, recebido)
    }

    @Test
    fun `roteador que estoura vira 500 em vez de derrubar o servidor`() {
        val resposta = conversar("GET /api/preview/court.jpg HTTP/1.1\r\n\r\n") { error("câmera não iniciou") }
        assertTrue(resposta.startsWith("HTTP/1.1 500 Internal Server Error\r\n"))
        assertTrue(resposta.contains("câmera não iniciou"))
    }

    @Test
    fun `requisicao malformada vira 400`() {
        val resposta = conversar("SOCORRO\r\n\r\n") { HttpResponse.text(200, "não deveria chegar aqui") }
        assertTrue(resposta.startsWith("HTTP/1.1 400 Bad Request\r\n"))
    }

    @Test
    fun `atende varias conexoes seguidas`() {
        val server = HttpServer(0, { HttpResponse.text(200, "pronto") })
        server.use {
            it.start()
            repeat(3) { volta ->
                Socket("127.0.0.1", it.boundPort).use { cliente ->
                    cliente.soTimeout = 5_000
                    cliente.getOutputStream().write("GET /$volta HTTP/1.1\r\n\r\n".toByteArray())
                    cliente.getOutputStream().flush()
                    val resposta = cliente.getInputStream().readBytes().toString(Charsets.UTF_8)
                    assertTrue("Conexão $volta falhou", resposta.endsWith("pronto"))
                }
            }
        }
    }
}
