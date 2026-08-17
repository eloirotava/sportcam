package dev.cascam.remote

import android.content.res.AssetManager
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.ConfigurationJson
import org.json.JSONObject

/**
 * O que o app entrega ao site. Tudo o que precisa da Activity — widgets, câmeras, quadros — passa
 * por aqui, porque o servidor HTTP roda nas suas próprias threads e não pode tocar em view.
 */
interface RemoteBridge {
    /** A configuração salva, que é o que o site enxerga. */
    fun configuration(): BroadcastConfiguration

    /** Aplica e persiste o que veio do site, e atualiza a tela do aparelho. */
    fun applyConfiguration(configuration: BroadcastConfiguration)

    /** Câmeras, tamanhos de captura, faixas de FPS e as opções dos enums, para montar os campos. */
    fun capabilitiesJson(): JSONObject

    /** Estado da transmissão, da conta do YouTube e do túnel. */
    fun statusJson(): JSONObject

    /** Um quadro da fonte pedida, já em JPEG. `null` quando ainda não chegou imagem nenhuma. */
    fun previewJpeg(source: String): ByteArray?

    /** Ações das telas Ao vivo, YouTube e Teste. Devolve a mensagem que o site mostra. */
    fun command(name: String): String

    /** Imagem do ícone enviada pelo site. Devolve a mensagem de resultado. */
    fun replaceLogo(bytes: ByteArray): String
}

class RemoteRoutes(
    private val assets: AssetManager,
    private val bridge: RemoteBridge,
) {
    fun handle(request: HttpRequest): HttpResponse {
        val configuration = bridge.configuration()
        val auth = BasicAuth(configuration.remoteUser, configuration.remotePassword)
        if (!auth.accepts(request.header("authorization"))) return HttpResponse.unauthorized()
        return when {
            request.method == "GET" && request.path == "/api/config" ->
                HttpResponse.json(ConfigurationJson.toJson(configuration).toString())

            request.method == "POST" && request.path == "/api/config" -> {
                val json = runCatching { JSONObject(request.bodyText) }.getOrElse {
                    return HttpResponse.text(400, "JSON inválido: ${it.message}")
                }
                val updated = ConfigurationJson.fromJson(json, configuration)
                bridge.applyConfiguration(updated)
                HttpResponse.json(ConfigurationJson.toJson(bridge.configuration()).toString())
            }

            request.method == "GET" && request.path == "/api/capabilities" ->
                HttpResponse.json(bridge.capabilitiesJson().toString())

            request.method == "GET" && request.path == "/api/status" ->
                HttpResponse.json(bridge.statusJson().toString())

            request.method == "GET" && request.path.startsWith("/api/preview/") -> {
                val source = request.path.removePrefix("/api/preview/").removeSuffix(".jpg")
                val jpeg = bridge.previewJpeg(source)
                    ?: return HttpResponse.text(503, "Ainda não há imagem desta fonte. Abra a tela correspondente no aparelho ou aguarde a câmera iniciar.")
                HttpResponse.jpeg(jpeg)
            }

            request.method == "POST" && request.path.startsWith("/api/command/") ->
                HttpResponse.json(message(bridge.command(request.path.removePrefix("/api/command/"))))

            request.method == "POST" && request.path == "/api/logo" -> {
                if (request.body.isEmpty()) return HttpResponse.text(400, "Nenhuma imagem recebida")
                HttpResponse.json(message(bridge.replaceLogo(request.body)))
            }

            request.method == "GET" -> asset(request.path)

            else -> HttpResponse.text(405, "Método ${request.method} não atendido em ${request.path}")
        }
    }

    private fun message(text: String) = JSONObject().put("mensagem", text).toString()

    private fun asset(path: String): HttpResponse {
        val name = if (path == "/") "index.html" else path.removePrefix("/")
        // O caminho vem do navegador; sem esta checagem um "../" alcançaria outros assets do APK.
        if (name.contains("..") || name.startsWith("/")) return HttpResponse.text(400, "Caminho inválido")
        val bytes = runCatching { assets.open("web/$name").use { it.readBytes() } }.getOrNull()
            ?: return HttpResponse.text(404, "Não existe $path")
        return HttpResponse(200, contentType(name), bytes)
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
}
