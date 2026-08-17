package dev.cascam.remote

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor HTTP do site de configuração. `ServerSocket` cru e threads nomeadas, como o resto do
 * app: o projeto não tem nenhuma dependência de rede de terceiros, e o que se pede aqui — GET,
 * POST e alguns kilobytes por resposta — não justifica trazer a primeira.
 *
 * Uma conexão por thread, cada requisição fechando a conexão. O navegador abre poucas conexões
 * simultâneas para carregar a página, e a pool elástica evita que um cliente parado segure o
 * atendimento dos outros.
 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
    private val onStatus: (String) -> Unit = {},
) : AutoCloseable {
    private var serverSocket: ServerSocket? = null
    private val counter = AtomicInteger()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cascam-http-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
    @Volatile private var running = false

    /** Porta realmente aberta. Difere da pedida quando ela é zero, que manda o sistema escolher. */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        if (running) return
        val address = if (LOOPBACK_ONLY) InetAddress.getLoopbackAddress() else null
        val socket = ServerSocket(port, BACKLOG, address)
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, "cascam-http").apply { isDaemon = true }.start()
        onStatus(
            if (LOOPBACK_ONLY) "Site no ar em 127.0.0.1:$port, alcançável só pelo túnel."
            else "Site no ar na porta $port, incluindo a rede local.",
        )
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (error: SocketException) {
                // close() fecha o ServerSocket justamente para acordar este accept.
                if (running) onStatus("Servidor do site parou: ${error.message}")
                return
            } catch (error: Exception) {
                onStatus("Falha ao aceitar conexão: ${error.message}")
                continue
            }
            workers.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            runCatching {
                connection.soTimeout = SOCKET_TIMEOUT_MILLIS
                connection.tcpNoDelay = true
                val input = BufferedInputStream(connection.getInputStream())
                val output = BufferedOutputStream(connection.getOutputStream())
                val request = HttpMessages.readRequest(input) ?: return@runCatching
                val response = runCatching { handler(request) }.getOrElse { error ->
                    HttpResponse.text(500, "Falha ao atender ${request.path}: ${error.message}")
                }
                HttpMessages.writeResponse(output, response)
            }.onFailure { error ->
                // Requisição malformada ou conexão cortada no meio: derruba só esta conexão.
                runCatching {
                    val output = BufferedOutputStream(connection.getOutputStream())
                    HttpMessages.writeResponse(output, HttpResponse.text(400, "Requisição inválida: ${error.message}"))
                }
            }
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    private companion object {
        const val BACKLOG = 16
        const val SOCKET_TIMEOUT_MILLIS = 15_000

        /**
         * O destino é escutar só em `127.0.0.1`, com o túnel cf-p como único caminho até o site —
         * assim ninguém na Wi-Fi do ginásio alcança a chave do YouTube nem o botão de transmitir.
         * Fica em `false` enquanto o site está sendo testado pela rede local, com `adb forward`
         * ainda não sendo o caminho do dia a dia. Vira `true` quando o túnel estiver validado.
         */
        const val LOOPBACK_ONLY = false
    }
}
