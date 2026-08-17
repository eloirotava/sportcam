package dev.cascam.config

import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect
import org.json.JSONArray
import org.json.JSONObject

/**
 * A configuração inteira em JSON, para o site editar exatamente o que as oito telas editam.
 *
 * [fromJson] parte de uma configuração base e só substitui o que o JSON trouxer. Isso resolve dois
 * problemas de uma vez: o site pode mandar só a tela que o operador mexeu, e a chave do YouTube —
 * que sai mascarada em [toJson] — permanece a que já estava salva quando o campo volta em branco.
 *
 * Sem dependência de Android: `org.json` vem no aparelho e existe também no JUnit do módulo, então
 * a ida e volta é testável sem Robolectric.
 */
object ConfigurationJson {
    /** O que o site recebe no lugar da chave quando ela já está guardada no aparelho. */
    const val SECRET_PLACEHOLDER = "__guardada__"

    fun toJson(configuration: BroadcastConfiguration, maskSecrets: Boolean = true): JSONObject {
        fun secret(value: String) = if (maskSecrets && value.isNotBlank()) SECRET_PLACEHOLDER else value
        return JSONObject().apply {
            put("courtCameraId", configuration.courtCameraId)
            put("scoreboardCameraId", configuration.scoreboardCameraId)
            put("scoreboardEnabled", configuration.scoreboardEnabled)
            put("scoreboardSource", configuration.scoreboardSource.name)
            put("scoreboardPhotoIntervalMillis", configuration.scoreboardPhotoIntervalMillis)
            put("cropZoom", configuration.cropZoom.toDouble())
            put("cropPanX", configuration.cropPanX.toDouble())
            put("cropPanY", configuration.cropPanY.toDouble())
            put("scoreboardCorners", cornersToJson(configuration.scoreboardCorners))
            put("scoreboardDestination", rectToJson(configuration.scoreboardDestination))
            put("clockCameraId", configuration.clockCameraId)
            put("clockEnabled", configuration.clockEnabled)
            put("clockSource", configuration.clockSource.name)
            put("clockPhotoIntervalMillis", configuration.clockPhotoIntervalMillis)
            put("clockCorners", cornersToJson(configuration.clockCorners))
            put("clockDestination", rectToJson(configuration.clockDestination))
            put("captureWidth", configuration.captureWidth)
            put("captureHeight", configuration.captureHeight)
            put("captureFps", configuration.captureFps)
            put("scoreboardCaptureWidth", configuration.scoreboardCaptureWidth)
            put("scoreboardCaptureHeight", configuration.scoreboardCaptureHeight)
            put("scoreboardCaptureFps", configuration.scoreboardCaptureFps)
            put("clockCaptureWidth", configuration.clockCaptureWidth)
            put("clockCaptureHeight", configuration.clockCaptureHeight)
            put("clockCaptureFps", configuration.clockCaptureFps)
            put("logoUri", configuration.logoUri)
            put("logoEnabled", configuration.logoEnabled)
            put("logoWhiteTransparent", configuration.logoWhiteTransparent)
            put("logoWidth", configuration.logoWidth.toDouble())
            put("logoCenterX", configuration.logoCenterX.toDouble())
            put("logoCenterY", configuration.logoCenterY.toDouble())
            put("protocol", configuration.protocol.name)
            put("videoCodec", configuration.videoCodec.name)
            put("outputResolution", configuration.outputResolution.name)
            put("outputFps", configuration.outputFps)
            put("bitratePreset", configuration.bitratePreset.name)
            put("youtubeServerUrl", configuration.youtubeServerUrl)
            put("youtubeStreamKey", secret(configuration.youtubeStreamKey))
            put("youtubeOAuthClientId", configuration.youtubeOAuthClientId)
            put("youtubeOAuthClientSecret", secret(configuration.youtubeOAuthClientSecret))
            put("liveTitle", configuration.liveTitle)
            put("livePrivacy", configuration.livePrivacy.name)
            put("liveLatency", configuration.liveLatency.name)
            put("compositionEngine", configuration.compositionEngine.name)
            put("frameRotation", configuration.frameRotation.name)
            put("remoteEnabled", configuration.remoteEnabled)
            put("remotePort", configuration.remotePort)
            put("remoteUser", configuration.remoteUser)
            put("remotePassword", secret(configuration.remotePassword))
            put("tunnelUrl", configuration.tunnelUrl)
            put("tunnelToken", secret(configuration.tunnelToken))
        }
    }

    fun fromJson(json: JSONObject, base: BroadcastConfiguration): BroadcastConfiguration {
        fun secret(key: String, current: String): String {
            val value = json.optString(key, SECRET_PLACEHOLDER)
            return if (value == SECRET_PLACEHOLDER) current else value
        }
        return base.copy(
            courtCameraId = json.optString("courtCameraId", base.courtCameraId),
            scoreboardCameraId = json.optString("scoreboardCameraId", base.scoreboardCameraId),
            scoreboardEnabled = json.optBoolean("scoreboardEnabled", base.scoreboardEnabled),
            scoreboardSource = enum(json, "scoreboardSource", base.scoreboardSource),
            scoreboardPhotoIntervalMillis = json.optLong("scoreboardPhotoIntervalMillis", base.scoreboardPhotoIntervalMillis)
                .takeIf { it in PHOTO_INTERVAL_OPTIONS_MILLIS } ?: base.scoreboardPhotoIntervalMillis,
            cropZoom = json.optDouble("cropZoom", base.cropZoom.toDouble()).toFloat().coerceIn(1f, 8f),
            cropPanX = json.optDouble("cropPanX", base.cropPanX.toDouble()).toFloat().coerceIn(-1f, 1f),
            cropPanY = json.optDouble("cropPanY", base.cropPanY.toDouble()).toFloat().coerceIn(-1f, 1f),
            scoreboardCorners = cornersFromJson(json.optJSONArray("scoreboardCorners"), base.scoreboardCorners),
            scoreboardDestination = rectFromJson(json.optJSONObject("scoreboardDestination"), base.scoreboardDestination),
            clockCameraId = json.optString("clockCameraId", base.clockCameraId),
            clockEnabled = json.optBoolean("clockEnabled", base.clockEnabled),
            clockSource = enum(json, "clockSource", base.clockSource),
            clockPhotoIntervalMillis = json.optLong("clockPhotoIntervalMillis", base.clockPhotoIntervalMillis)
                .takeIf { it in PHOTO_INTERVAL_OPTIONS_MILLIS } ?: base.clockPhotoIntervalMillis,
            clockCorners = cornersFromJson(json.optJSONArray("clockCorners"), base.clockCorners),
            clockDestination = rectFromJson(json.optJSONObject("clockDestination"), base.clockDestination),
            captureWidth = json.optInt("captureWidth", base.captureWidth).coerceAtLeast(0),
            captureHeight = json.optInt("captureHeight", base.captureHeight).coerceAtLeast(0),
            captureFps = json.optInt("captureFps", base.captureFps).coerceAtLeast(0),
            scoreboardCaptureWidth = json.optInt("scoreboardCaptureWidth", base.scoreboardCaptureWidth).coerceAtLeast(0),
            scoreboardCaptureHeight = json.optInt("scoreboardCaptureHeight", base.scoreboardCaptureHeight).coerceAtLeast(0),
            scoreboardCaptureFps = json.optInt("scoreboardCaptureFps", base.scoreboardCaptureFps).coerceAtLeast(0),
            clockCaptureWidth = json.optInt("clockCaptureWidth", base.clockCaptureWidth).coerceAtLeast(0),
            clockCaptureHeight = json.optInt("clockCaptureHeight", base.clockCaptureHeight).coerceAtLeast(0),
            clockCaptureFps = json.optInt("clockCaptureFps", base.clockCaptureFps).coerceAtLeast(0),
            // A imagem do ícone é escolhida pelo seletor de arquivos do Android; o site não inventa URI.
            logoEnabled = json.optBoolean("logoEnabled", base.logoEnabled),
            logoWhiteTransparent = json.optBoolean("logoWhiteTransparent", base.logoWhiteTransparent),
            logoWidth = json.optDouble("logoWidth", base.logoWidth.toDouble()).toFloat().coerceIn(.05f, .5f),
            logoCenterX = json.optDouble("logoCenterX", base.logoCenterX.toDouble()).toFloat().coerceIn(0f, 1f),
            logoCenterY = json.optDouble("logoCenterY", base.logoCenterY.toDouble()).toFloat().coerceIn(0f, 1f),
            protocol = enum(json, "protocol", base.protocol),
            videoCodec = enum(json, "videoCodec", base.videoCodec),
            outputResolution = enum(json, "outputResolution", base.outputResolution),
            outputFps = json.optInt("outputFps", base.outputFps).takeIf { it in OUTPUT_FPS_OPTIONS } ?: base.outputFps,
            bitratePreset = enum(json, "bitratePreset", base.bitratePreset),
            youtubeServerUrl = json.optString("youtubeServerUrl", base.youtubeServerUrl).trim().removeSuffix("/"),
            youtubeStreamKey = secret("youtubeStreamKey", base.youtubeStreamKey).trim(),
            youtubeOAuthClientId = json.optString("youtubeOAuthClientId", base.youtubeOAuthClientId).trim(),
            youtubeOAuthClientSecret = secret("youtubeOAuthClientSecret", base.youtubeOAuthClientSecret).trim(),
            liveTitle = json.optString("liveTitle", base.liveTitle).trim().ifBlank { "SportCam ao vivo" },
            livePrivacy = enum(json, "livePrivacy", base.livePrivacy),
            liveLatency = enum(json, "liveLatency", base.liveLatency),
            compositionEngine = enum(json, "compositionEngine", base.compositionEngine),
            frameRotation = enum(json, "frameRotation", base.frameRotation),
            remoteEnabled = json.optBoolean("remoteEnabled", base.remoteEnabled),
            remotePort = json.optInt("remotePort", base.remotePort).takeIf { it in 1024..65535 } ?: base.remotePort,
            remoteUser = json.optString("remoteUser", base.remoteUser).trim().ifBlank { base.remoteUser },
            remotePassword = secret("remotePassword", base.remotePassword),
            tunnelUrl = json.optString("tunnelUrl", base.tunnelUrl).trim(),
            tunnelToken = secret("tunnelToken", base.tunnelToken).trim(),
        )
    }

    private inline fun <reified T : Enum<T>> enum(json: JSONObject, key: String, fallback: T): T =
        runCatching { enumValueOf<T>(json.getString(key)) }.getOrDefault(fallback)

    private fun cornersToJson(corners: List<NormalizedPoint>) = JSONArray().apply {
        corners.forEach { put(JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())) }
    }

    /**
     * Quatro pontos ou nada. [NormalizedPoint] recusa coordenada fora de 0..1 no construtor, e um
     * quadrilátero pela metade não tem correção possível — o certo é manter o que já estava.
     */
    private fun cornersFromJson(array: JSONArray?, fallback: List<NormalizedPoint>): List<NormalizedPoint> {
        if (array == null) return fallback
        return runCatching {
            require(array.length() == 4)
            (0 until 4).map { index ->
                val point = array.getJSONObject(index)
                NormalizedPoint(
                    point.getDouble("x").toFloat().coerceIn(0f, 1f),
                    point.getDouble("y").toFloat().coerceIn(0f, 1f),
                )
            }
        }.getOrDefault(fallback)
    }

    private fun rectToJson(rect: NormalizedRect) = JSONObject()
        .put("left", rect.left.toDouble())
        .put("top", rect.top.toDouble())
        .put("right", rect.right.toDouble())
        .put("bottom", rect.bottom.toDouble())

    private fun rectFromJson(json: JSONObject?, fallback: NormalizedRect): NormalizedRect {
        if (json == null) return fallback
        return runCatching {
            NormalizedRect(
                json.getDouble("left").toFloat().coerceIn(0f, 1f),
                json.getDouble("top").toFloat().coerceIn(0f, 1f),
                json.getDouble("right").toFloat().coerceIn(0f, 1f),
                json.getDouble("bottom").toFloat().coerceIn(0f, 1f),
            )
        }.getOrDefault(fallback)
    }

    private val OUTPUT_FPS_OPTIONS = setOf(15, 20, 24, 30, 60)
}
