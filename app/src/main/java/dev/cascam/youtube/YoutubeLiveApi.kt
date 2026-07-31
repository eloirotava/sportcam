package dev.cascam.youtube

import android.content.Context
import dev.cascam.config.BroadcastProtocol
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class YoutubeLiveApi(context: Context) {
    data class DeviceAuthorization(
        val deviceCode: String, val userCode: String, val verificationUrl: String,
        val verificationUrlComplete: String, val intervalSeconds: Int, val expiresInSeconds: Int,
    ) {
        /** URL que já leva o código embutido quando o Google devolve essa variante. */
        val bestVerificationUrl: String get() = verificationUrlComplete.ifBlank { verificationUrl }
    }

    data class Ingestion(val serverUrl: String, val streamKey: String)

    private val preferences = context.getSharedPreferences("youtube_oauth", Context.MODE_PRIVATE)

    fun beginDeviceAuthorization(clientId: String): DeviceAuthorization {
        val response = postForm("https://oauth2.googleapis.com/device/code", mapOf(
            "client_id" to clientId,
            "scope" to "https://www.googleapis.com/auth/youtube",
        ))
        return DeviceAuthorization(
            response.getString("device_code"), response.getString("user_code"),
            response.optString("verification_url", response.optString("verification_uri")),
            response.optString("verification_url_complete", response.optString("verification_uri_complete")),
            response.optInt("interval", 5), response.getInt("expires_in"),
        )
    }

    fun finishDeviceAuthorization(clientId: String, clientSecret: String, authorization: DeviceAuthorization, onWaiting: () -> Unit): String {
        val deadline = System.currentTimeMillis() + authorization.expiresInSeconds * 1_000L
        var interval = authorization.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1_000L)
            val values = mutableMapOf(
                "client_id" to clientId, "device_code" to authorization.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ).also { if (clientSecret.isNotBlank()) it["client_secret"] = clientSecret }
            val response = postForm("https://oauth2.googleapis.com/token", values, acceptErrors = true)
            when (response.optString("error")) {
                "authorization_pending" -> onWaiting()
                "slow_down" -> interval += 5
                "" -> return saveTokens(response)
                else -> error("OAuth recusado: ${response.optString("error_description", response.optString("error"))}")
            }
        }
        error("O código de autorização do YouTube expirou")
    }

    fun hasRefreshToken(): Boolean = preferences.getString("refresh_token", null) != null

    fun createAndBindBroadcast(clientId: String, clientSecret: String, title: String, privacy: String, protocol: BroadcastProtocol): Ingestion {
        val token = accessToken(clientId, clientSecret)
        val broadcast = requestJson(
            "https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet,status,contentDetails",
            token,
            JSONObject().put("snippet", JSONObject().put("title", title).put("scheduledStartTime", Instant.now().plusSeconds(15).toString()))
                .put("status", JSONObject().put("privacyStatus", privacy).put("selfDeclaredMadeForKids", false))
                .put("contentDetails", JSONObject().put("enableAutoStart", true).put("enableAutoStop", true)
                    .put("monitorStream", JSONObject().put("enableMonitorStream", false))),
        )
        val stream = requestJson(
            "https://www.googleapis.com/youtube/v3/liveStreams?part=snippet,cdn",
            token,
            JSONObject().put("snippet", JSONObject().put("title", "$title · CasCam"))
                .put("cdn", JSONObject().put("ingestionType", if (protocol == BroadcastProtocol.RTMPS) "rtmp" else "hls")
                    .put("frameRate", "variable").put("resolution", "variable")),
        )
        val broadcastId = broadcast.getString("id")
        val streamId = stream.getString("id")
        requestJson(
            "https://www.googleapis.com/youtube/v3/liveBroadcasts/bind?part=id,contentDetails&id=${encode(broadcastId)}&streamId=${encode(streamId)}",
            token, JSONObject(),
        )
        val info = stream.getJSONObject("cdn").getJSONObject("ingestionInfo")
        return Ingestion(info.getString("ingestionAddress"), info.getString("streamName"))
    }

    private fun accessToken(clientId: String, clientSecret: String): String {
        val current = preferences.getString("access_token", null)
        if (current != null && preferences.getLong("expires_at", 0) > System.currentTimeMillis() + 60_000) return current
        val refresh = preferences.getString("refresh_token", null) ?: error("Autorize a conta do YouTube primeiro")
        val values = mutableMapOf(
            "client_id" to clientId, "refresh_token" to refresh, "grant_type" to "refresh_token",
        ).also { if (clientSecret.isNotBlank()) it["client_secret"] = clientSecret }
        return saveTokens(postForm("https://oauth2.googleapis.com/token", values))
    }

    private fun saveTokens(response: JSONObject): String {
        val access = response.getString("access_token")
        preferences.edit().putString("access_token", access)
            .putLong("expires_at", System.currentTimeMillis() + response.optLong("expires_in", 3_600) * 1_000L)
            .also { editor -> response.optString("refresh_token").takeIf(String::isNotBlank)?.let { editor.putString("refresh_token", it) } }
            .apply()
        return access
    }

    private fun requestJson(url: String, token: String, body: JSONObject): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.connectTimeout = 20_000; connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        return response(connection)
    }

    private fun postForm(url: String, values: Map<String, String>, acceptErrors: Boolean = false): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.connectTimeout = 20_000; connection.readTimeout = 20_000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }.toByteArray()
        connection.outputStream.use { it.write(body) }
        return response(connection, acceptErrors)
    }

    private fun response(connection: HttpURLConnection, acceptErrors: Boolean = false): JSONObject {
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val json = JSONObject(text.ifBlank { "{}" })
        if (!acceptErrors && code !in 200..299) error("YouTube API HTTP $code: ${json.optJSONObject("error")?.optString("message") ?: text}")
        return json
    }

    companion object { private fun encode(value: String) = URLEncoder.encode(value, "UTF-8") }
}
