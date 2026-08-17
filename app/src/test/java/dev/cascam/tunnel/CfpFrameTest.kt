package dev.cascam.tunnel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CfpFrameTest {
    @Test
    fun `o cabecalho tem seis bytes com stream id big-endian`() {
        val bytes = CfpFrame(CfpFrame.DATA, 0x01020304L, byteArrayOf(9, 9)).encode()
        assertArrayEquals(
            byteArrayOf(2, CfpFrame.DATA.toByte(), 0x01, 0x02, 0x03, 0x04, 9, 9),
            bytes,
        )
    }

    @Test
    fun `o auth leva o token em ascii puro no stream zero`() {
        val token = "a".repeat(64)
        val bytes = CfpFrame.text(CfpFrame.AUTH, 0, token).encode()
        assertEquals(CfpFrame.HEADER_BYTES + 64, bytes.size)
        assertArrayEquals(byteArrayOf(2, 1, 0, 0, 0, 0), bytes.copyOfRange(0, 6))
        assertEquals(token, String(bytes.copyOfRange(6, bytes.size), Charsets.UTF_8))
    }

    @Test
    fun `ida e volta preserva kind stream e payload`() {
        val original = CfpFrame(CfpFrame.OPEN, 1_000_000L, "127.0.0.1:8080".toByteArray())
        assertEquals(original, CfpFrame.decode(original.encode()))
    }

    @Test
    fun `payload vazio e valido`() {
        val decodificado = CfpFrame.decode(CfpFrame.of(CfpFrame.OPEN_OK, 7).encode())
        assertEquals(CfpFrame.OPEN_OK, decodificado.kind)
        assertEquals(7L, decodificado.streamId)
        assertEquals(0, decodificado.payload.size)
    }

    @Test
    fun `stream id acima de dois bilhoes nao vira negativo`() {
        // O id é u32 e o servidor numera rotas HTTP a partir de 1.000.000; um Int com sinal
        // estouraria e o quadro voltaria para o stream errado.
        val alto = 4_294_967_295L
        assertEquals(alto, CfpFrame.decode(CfpFrame.of(CfpFrame.DATA, alto).encode()).streamId)
    }

    @Test
    fun `quadro curto e recusado`() {
        val erro = runCatching { CfpFrame.decode(byteArrayOf(2, 1, 0, 0, 0)) }.exceptionOrNull()
        assertTrue(erro is IllegalArgumentException)
    }

    @Test
    fun `versao um e recusada com a versao no texto`() {
        val erro = runCatching { CfpFrame.decode(byteArrayOf(1, 1, 0, 0, 0, 0)) }.exceptionOrNull()
        assertTrue(erro is IllegalArgumentException)
        assertTrue(erro!!.message!!.contains("1"))
        assertTrue(erro.message!!.contains("2"))
    }

    @Test
    fun `window update carrega os creditos em u32`() {
        val quadro = CfpFrame.windowUpdate(3, 524_288)
        assertEquals(4, quadro.payload.size)
        assertEquals(524_288L, CfpFrame.decode(quadro.encode()).credit)
    }

    @Test
    fun `window update com payload curto e ignorado em vez de derrubar`() {
        assertNull(CfpFrame(CfpFrame.WINDOW_UPDATE, 3, byteArrayOf(0, 1)).credit)
    }

    @Test
    fun `as constantes da janela sao as do servidor`() {
        assertEquals(1024 * 1024, CfpFrame.WINDOW)
        assertEquals(512 * 1024, CfpFrame.WINDOW_UPDATE_THRESHOLD)
        assertEquals(16 * 1024, CfpFrame.CHUNK)
        assertEquals(2, CfpFrame.VERSION)
    }

    @Test
    fun `so o proprio site e destino aceito`() {
        assertTrue(CfpTunnel.allowsTarget("127.0.0.1:8080", 8080))
        assertTrue(CfpTunnel.allowsTarget("localhost:8080", 8080))
        // Outra porta no mesmo aparelho, um serviço da rede local, e o formato sem porta.
        assertFalse(CfpTunnel.allowsTarget("127.0.0.1:22", 8080))
        assertFalse(CfpTunnel.allowsTarget("192.168.0.50:8080", 8080))
        assertFalse(CfpTunnel.allowsTarget("roteador.local:8080", 8080))
        assertFalse(CfpTunnel.allowsTarget("127.0.0.1", 8080))
        assertFalse(CfpTunnel.allowsTarget("", 8080))
    }
}
