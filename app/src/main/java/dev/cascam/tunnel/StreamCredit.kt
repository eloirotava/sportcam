package dev.cascam.tunnel

/**
 * Os dois contadores de crédito de um stream cf-p, um por sentido.
 *
 * Está separado do [CfpTunnel] porque é a parte do protocolo em que errar não dá erro: mandar
 * além do crédito derruba a sessão WebSocket inteira, e deixar de devolver crédito congela o
 * stream em silêncio depois de 1 MiB. Um teste de mesa cobre os dois casos; um `curl` pequeno não
 * cobriria nenhum.
 */
class StreamCredit(
    private val window: Int = CfpFrame.WINDOW,
    private val threshold: Int = CfpFrame.WINDOW_UPDATE_THRESHOLD,
) {
    private val lock = Object()
    private var available = window.toLong()
    private var consumed = 0L

    @Volatile
    var closed = false
        private set

    /**
     * Espera ter [count] bytes de crédito e os reserva. Devolve `false` quando o stream fechou —
     * quem envia precisa desistir em vez de dormir para sempre.
     */
    fun reserve(count: Int): Boolean {
        synchronized(lock) {
            while (available < count && !closed) {
                try {
                    lock.wait(WAIT_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            if (closed) return false
            available -= count
            return true
        }
    }

    /** Crédito devolvido pelo outro lado num WINDOW_UPDATE. */
    fun release(value: Long) = synchronized(lock) {
        available += value
        lock.notifyAll()
    }

    /**
     * Registra bytes já escritos no socket local e devolve quanto anunciar agora, ou zero.
     *
     * A contagem é depois da escrita de propósito: o crédito mede o que o destino realmente
     * engoliu, não o que chegou na fila.
     */
    fun consume(count: Int): Long = synchronized(lock) {
        consumed += count
        if (consumed < threshold) 0L else consumed.also { consumed = 0 }
    }

    /** Fecha e acorda quem esperava crédito. */
    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    private companion object {
        const val WAIT_MILLIS = 1_000L
    }
}
