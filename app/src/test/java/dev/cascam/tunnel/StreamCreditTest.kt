package dev.cascam.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCreditTest {
    @Test
    fun `a janela inicial cobre um mebibyte e nao mais que isso`() {
        val credito = StreamCredit()
        assertTrue(credito.reserve(CfpFrame.WINDOW))
        // Passar da janela é o que faz o servidor derrubar a sessão WebSocket inteira. O certo
        // aqui não é recusar nem cortar o bloco: é esperar crédito.
        val alem = Thread { credito.reserve(1) }
        alem.start()
        Thread.sleep(50)
        assertTrue("Sem crédito o envio tem que esperar, não seguir em frente", alem.isAlive)
        credito.close()
        alem.join(2_000)
    }

    @Test
    fun `credito devolvido libera quem esperava`() {
        val credito = StreamCredit()
        assertTrue(credito.reserve(CfpFrame.WINDOW))
        credito.release(4_096)
        assertTrue(credito.reserve(4_096))
    }

    @Test
    fun `so anuncia ao cruzar a metade da janela`() {
        val credito = StreamCredit()
        assertEquals(0L, credito.consume(CfpFrame.WINDOW_UPDATE_THRESHOLD - 1))
        assertEquals(CfpFrame.WINDOW_UPDATE_THRESHOLD.toLong(), credito.consume(1))
    }

    @Test
    fun `o anuncio leva o acumulado exato e zera o contador`() {
        val credito = StreamCredit()
        val blocos = CfpFrame.WINDOW_UPDATE_THRESHOLD / CfpFrame.CHUNK
        repeat(blocos - 1) { assertEquals(0L, credito.consume(CfpFrame.CHUNK)) }
        // O bloco que cruza o limiar devolve tudo o que foi consumido desde o último anúncio.
        assertEquals(CfpFrame.WINDOW_UPDATE_THRESHOLD.toLong(), credito.consume(CfpFrame.CHUNK))
        assertEquals(0L, credito.consume(1))
    }

    @Test
    fun `fechar acorda quem espera credito`() {
        val credito = StreamCredit()
        assertTrue(credito.reserve(CfpFrame.WINDOW))
        val esperando = Thread { credito.reserve(CfpFrame.CHUNK) }
        esperando.start()
        Thread.sleep(50)
        assertTrue(esperando.isAlive)
        credito.close()
        esperando.join(2_000)
        assertFalse("A thread de envio ficaria presa para sempre", esperando.isAlive)
    }
}
