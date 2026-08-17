package dev.cascam.tunnel

import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

/**
 * Cliente do túnel cf-p. Mantém uma sessão WSS com o servidor e, quando ele manda um OPEN, abre a
 * conexão local pedida e faz a ponte. O cliente nunca origina stream: quem numera e quem decide o
 * destino é o servidor.
 *
 * O controle de fluxo da wire v2 não é opcional. São dois contadores por stream, um em cada
 * sentido: sem esperar crédito para enviar, o servidor derruba a sessão WebSocket inteira; sem
 * devolver crédito ao consumir, o stream congela em silêncio depois de 1 MiB. Funciona num teste
 * pequeno e quebra na primeira prévia em JPEG, que é o motivo de estar aqui por inteiro.
 */
class CfpTunnel(
    private val url: String,
    private val token: String,
    private val allowedPort: Int,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val streams = ConcurrentHashMap<Long, Stream>()
    @Volatile private var session: WebSocketClient? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread(::reconnectLoop, "cascam-tunnel").apply { isDaemon = true; start() }
    }

    private fun reconnectLoop() {
        var delaySeconds = 1L
        while (running) {
            val startedAt = System.currentTimeMillis()
            runCatching { runSession() }.onFailure { error ->
                if (running) onStatus("Túnel caiu: ${error.message}")
            }
            closeAllStreams()
            if (!running) return
            // Sessão que se sustentou por um minuto conta como saudável; a próxima queda recomeça
            // do começo em vez de herdar a espera longa de uma sequência antiga de falhas.
            if (System.currentTimeMillis() - startedAt >= HEALTHY_SESSION_MILLIS) delaySeconds = 1
            val jitter = Random.nextLong(0, 1_000)
            runCatching { Thread.sleep(delaySeconds * 1_000 + jitter) }.onFailure { return }
            delaySeconds = (delaySeconds * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
        }
    }

    private fun runSession() {
        onStatus("Conectando o túnel em $url…")
        WebSocketClient(url).use { client ->
            client.connect()
            client.sendBinary(CfpFrame.text(CfpFrame.AUTH, 0, token).encode())
            val answer = CfpFrame.decode(client.readBinaryMessage())
            when (answer.kind) {
                CfpFrame.ERROR -> {
                    // Token recusado não melhora com tempo. O binário de referência reconecta para
                    // sempre; num telefone isso é bateria gasta contra uma parede.
                    running = false
                    onStatus("Túnel recusado: ${answer.text}")
                    return
                }
                CfpFrame.AUTH_OK -> onStatus("Túnel autenticado. O site já responde pelo endereço público.")
                else -> throw IllegalStateException("Resposta inesperada ao AUTH: kind ${answer.kind}")
            }
            session = client
            try {
                while (running) dispatch(client, CfpFrame.decode(client.readBinaryMessage()))
            } finally {
                session = null
            }
        }
    }

    private fun dispatch(client: WebSocketClient, frame: CfpFrame) {
        when (frame.kind) {
            CfpFrame.OPEN -> openStream(frame.streamId, frame.text)
            CfpFrame.DATA -> streams[frame.streamId]?.deliver(frame.payload)
            CfpFrame.WINDOW_UPDATE -> frame.credit?.let { streams[frame.streamId]?.addCredit(it) }
            CfpFrame.CLOSE, CfpFrame.OPEN_ERROR -> streams.remove(frame.streamId)?.shutdown()
            CfpFrame.ERROR -> onStatus("Erro do servidor cf-p: ${frame.text}")
            else -> Unit
        }
    }

    private fun openStream(id: Long, target: String) {
        if (!allowsTarget(target, allowedPort)) {
            send(CfpFrame.text(CfpFrame.OPEN_ERROR, id, "Destino $target não autorizado por este aparelho"))
            onStatus("Recusei um pedido de conexão para $target; só o site local é permitido.")
            return
        }
        val stream = Stream(id)
        streams[id] = stream
        Thread({ serveStream(stream, target) }, "cascam-tunnel-$id").apply { isDaemon = true }.start()
    }

    private fun serveStream(stream: Stream, target: String) {
        val socket = Socket()
        val connected = runCatching {
            socket.connect(
                InetSocketAddress(target.substringBeforeLast(':'), target.substringAfterLast(':').toInt()),
                CONNECT_TIMEOUT_MILLIS,
            )
            socket.tcpNoDelay = true
        }
        if (connected.isFailure) {
            streams.remove(stream.id)
            send(CfpFrame.text(CfpFrame.OPEN_ERROR, stream.id, connected.exceptionOrNull()?.message.orEmpty()))
            runCatching { socket.close() }
            return
        }
        stream.attach(socket)
        // OPEN_OK antes de qualquer DATA nosso: a fila de saída preserva ordem, e é assim que o
        // servidor sabe que a ponte existe.
        send(CfpFrame.of(CfpFrame.OPEN_OK, stream.id))
        pumpLocalToTunnel(stream, socket)
    }

    private fun pumpLocalToTunnel(stream: Stream, socket: Socket) {
        val buffer = ByteArray(CfpFrame.CHUNK)
        runCatching {
            val input = socket.getInputStream()
            while (!stream.closed) {
                val count = input.read(buffer)
                if (count <= 0) break
                // Crédito primeiro, envio depois: é o que segura o excedente no socket de origem
                // em vez de acumular na sessão.
                if (!stream.awaitCredit(count)) break
                send(CfpFrame(CfpFrame.DATA, stream.id, buffer.copyOf(count)))
            }
        }
        if (streams.remove(stream.id) != null) send(CfpFrame.of(CfpFrame.CLOSE, stream.id))
        stream.shutdown()
    }

    private fun send(frame: CfpFrame) {
        val client = session ?: return
        runCatching { client.sendBinary(frame.encode()) }.onFailure {
            // Sessão morrendo: o laço de reconexão já vai perceber pela leitura.
        }
    }

    private fun closeAllStreams() {
        streams.values.toList().forEach { it.shutdown() }
        streams.clear()
    }

    override fun close() {
        running = false
        closeAllStreams()
        runCatching { session?.close() }
        session = null
        worker?.interrupt()
        worker = null
    }

    /**
     * Um stream e seus dois contadores de crédito. A entrega para o socket local passa por uma
     * fila com thread própria: escrever direto na thread de leitura da sessão deixaria um socket
     * local lento travando todos os outros streams junto.
     */
    private inner class Stream(val id: Long) {
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private val credit = StreamCredit()
        private var socket: Socket? = null
        val closed: Boolean get() = credit.closed

        fun attach(connected: Socket) {
            socket = connected
            Thread({ writeLoop(connected) }, "cascam-tunnel-$id-in").apply { isDaemon = true }.start()
        }

        fun deliver(payload: ByteArray) {
            if (!closed) inbound.put(payload)
        }

        private fun writeLoop(connected: Socket) {
            runCatching {
                val output = connected.getOutputStream()
                while (!closed) {
                    val chunk = inbound.take()
                    if (chunk === POISON) break
                    output.write(chunk)
                    output.flush()
                    // Só agora o byte foi realmente consumido; o crédito devolvido mede o que o
                    // socket local engoliu, não o que chegou na fila.
                    accountConsumed(chunk.size)
                }
            }
        }

        private fun accountConsumed(count: Int) {
            val devolver = credit.consume(count)
            if (devolver > 0) send(CfpFrame.windowUpdate(id, devolver))
        }

        fun awaitCredit(count: Int): Boolean = credit.reserve(count)

        fun addCredit(value: Long) = credit.release(value)

        /** Fecha e acorda quem estiver esperando crédito, senão a thread de envio dorme para sempre. */
        fun shutdown() {
            if (closed) return
            credit.close()
            inbound.put(POISON)
            runCatching { socket?.close() }
            socket = null
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val HEALTHY_SESSION_MILLIS = 60_000L
        private const val MAX_BACKOFF_SECONDS = 30L
        private val POISON = ByteArray(0)

        /**
         * O OPEN traz um destino em texto e o protocolo não restringe nada: quem manda o OPEN
         * escolhe para onde este aparelho abre socket. Numa VM isso passa; num telefone dentro da
         * Wi-Fi de um ginásio, seria uma porta de entrada na rede local com as credenciais de rede
         * do aparelho. Só o próprio site, no loopback e na porta configurada, é destino válido.
         */
        fun allowsTarget(target: String, allowedPort: Int): Boolean {
            val host = target.substringBeforeLast(':', "")
            val port = target.substringAfterLast(':', "").toIntOrNull()
            return port == allowedPort &&
                (host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]")
        }
    }
}
