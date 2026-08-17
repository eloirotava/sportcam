package dev.cascam.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BasicAuthTest {
    private val auth = BasicAuth("sportcam", "senha-do-site")

    private fun cabecalho(usuario: String, senha: String) =
        "Basic " + Base64.getEncoder().encodeToString("$usuario:$senha".toByteArray(Charsets.UTF_8))

    @Test
    fun `usuario e senha certos entram`() {
        assertTrue(auth.accepts(cabecalho("sportcam", "senha-do-site")))
    }

    @Test
    fun `senha errada nao entra`() {
        assertFalse(auth.accepts(cabecalho("sportcam", "senha-do-sit")))
    }

    @Test
    fun `usuario errado nao entra`() {
        assertFalse(auth.accepts(cabecalho("admin", "senha-do-site")))
    }

    @Test
    fun `sem cabecalho nao entra`() {
        assertFalse(auth.accepts(null))
        assertFalse(auth.accepts(""))
    }

    @Test
    fun `cabecalho de outro esquema nao entra`() {
        assertFalse(auth.accepts("Bearer senha-do-site"))
    }

    @Test
    fun `base64 quebrado nao derruba nem entra`() {
        assertFalse(auth.accepts("Basic ????"))
        assertFalse(auth.accepts("Basic " + Base64.getEncoder().encodeToString("semdoispontos".toByteArray())))
    }

    @Test
    fun `senha com dois pontos vale inteira`() {
        val comDoisPontos = BasicAuth("sportcam", "a:b:c")
        assertTrue(comDoisPontos.accepts(cabecalho("sportcam", "a:b:c")))
    }

    @Test
    fun `sem senha configurada nada entra`() {
        val vazio = BasicAuth("sportcam", "")
        assertFalse(vazio.configured)
        assertFalse(vazio.accepts(cabecalho("sportcam", "")))
    }
}
