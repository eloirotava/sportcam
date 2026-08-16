package dev.cascam.config

import android.content.Context
import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect

class BroadcastConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("broadcast_configuration", Context.MODE_PRIVATE)

    fun load(): BroadcastConfiguration = BroadcastConfiguration(
        courtCameraId = preferences.getString("court_camera", "").orEmpty(),
        scoreboardCameraId = preferences.getString("scoreboard_camera", "").orEmpty(),
        scoreboardEnabled = preferences.getBoolean("scoreboard_enabled", true),
        scoreboardSource = runCatching {
            OverlaySource.valueOf(preferences.getString("scoreboard_source", null).orEmpty())
        }.getOrDefault(OverlaySource.VIDEO),
        scoreboardPhotoIntervalMillis = preferences.getLong("scoreboard_photo_interval_ms", 1_000L)
            .takeIf { it in PHOTO_INTERVAL_OPTIONS_MILLIS } ?: 1_000L,
        cropZoom = preferences.getFloat("crop_zoom", 1f),
        cropPanX = preferences.getFloat("crop_pan_x", 0f),
        cropPanY = preferences.getFloat("crop_pan_y", 0f),
        scoreboardCorners = decodeCorners(preferences.getString("scoreboard_corners", null)),
        scoreboardDestination = decodeRect(preferences.getString("scoreboard_destination", null)),
        clockCameraId = preferences.getString("clock_camera", "").orEmpty(),
        clockEnabled = preferences.getBoolean("clock_enabled", false),
        clockSource = runCatching {
            OverlaySource.valueOf(preferences.getString("clock_source", null).orEmpty())
        }.getOrDefault(OverlaySource.VIDEO),
        clockPhotoIntervalMillis = preferences.getLong("clock_photo_interval_ms", 1_000L)
            .takeIf { it in PHOTO_INTERVAL_OPTIONS_MILLIS } ?: 1_000L,
        clockCorners = decodeCorners(preferences.getString("clock_corners", null), DEFAULT_CLOCK_CORNERS),
        clockDestination = decodeRect(preferences.getString("clock_destination", null), DEFAULT_CLOCK_DESTINATION),
        captureWidth = preferences.getInt("capture_width", 0).coerceAtLeast(0),
        captureHeight = preferences.getInt("capture_height", 0).coerceAtLeast(0),
        captureFps = preferences.getInt("capture_fps", 0).coerceAtLeast(0),
        scoreboardCaptureWidth = preferences.getInt("scoreboard_capture_width", 0).coerceAtLeast(0),
        scoreboardCaptureHeight = preferences.getInt("scoreboard_capture_height", 0).coerceAtLeast(0),
        scoreboardCaptureFps = preferences.getInt("scoreboard_capture_fps", 0).coerceAtLeast(0),
        clockCaptureWidth = preferences.getInt("clock_capture_width", 0).coerceAtLeast(0),
        clockCaptureHeight = preferences.getInt("clock_capture_height", 0).coerceAtLeast(0),
        clockCaptureFps = preferences.getInt("clock_capture_fps", 0).coerceAtLeast(0),
        logoUri = preferences.getString("logo_uri", "").orEmpty(),
        logoEnabled = preferences.getBoolean("logo_enabled", false),
        logoWhiteTransparent = preferences.getBoolean("logo_white_transparent", false),
        logoWidth = preferences.getFloat("logo_width", .18f).coerceIn(.05f, .5f),
        logoCenterX = preferences.getFloat("logo_center_x", .88f).coerceIn(0f, 1f),
        logoCenterY = preferences.getFloat("logo_center_y", .85f).coerceIn(0f, 1f),
        protocol = runCatching {
            BroadcastProtocol.valueOf(preferences.getString("broadcast_protocol", null).orEmpty())
        }.getOrDefault(BroadcastProtocol.RTMPS),
        videoCodec = runCatching {
            VideoCodec.valueOf(preferences.getString("video_codec", null).orEmpty())
        }.getOrDefault(VideoCodec.H264),
        outputResolution = runCatching {
            OutputResolution.valueOf(preferences.getString("output_resolution", null).orEmpty())
        }.getOrDefault(OutputResolution.HD),
        outputFps = preferences.getInt("output_fps", 20).takeIf { it in OUTPUT_FPS_OPTIONS } ?: 20,
        bitratePreset = runCatching {
            BitratePreset.valueOf(preferences.getString("bitrate_preset", null).orEmpty())
        }.getOrDefault(BitratePreset.AUTO),
        youtubeServerUrl = preferences.getString("youtube_server", null)
            ?: "rtmps://a.rtmps.youtube.com/live2",
        youtubeStreamKey = preferences.getString("youtube_key", "").orEmpty(),
        youtubeOAuthClientId = preferences.getString("youtube_oauth_client_id", "").orEmpty(),
        youtubeOAuthClientSecret = preferences.getString("youtube_oauth_client_secret", "").orEmpty(),
        liveTitle = preferences.getString("live_title", "SportCam ao vivo").orEmpty(),
        livePrivacy = runCatching {
            LivePrivacy.valueOf(preferences.getString("live_privacy", null).orEmpty())
        }.getOrDefault(LivePrivacy.UNLISTED),
        liveLatency = runCatching {
            LiveLatency.valueOf(preferences.getString("live_latency", null).orEmpty())
        }.getOrDefault(LiveLatency.LOW),
        compositionEngine = runCatching {
            CompositionEngine.valueOf(preferences.getString("composition_engine", null).orEmpty())
        }.getOrDefault(CompositionEngine.CPU),
        frameRotation = runCatching {
            FrameRotation.valueOf(preferences.getString("frame_rotation", null).orEmpty())
        }.getOrDefault(FrameRotation.AUTO),
    )

    fun save(configuration: BroadcastConfiguration) {
        preferences.edit()
            .putString("court_camera", configuration.courtCameraId)
            .putString("scoreboard_camera", configuration.scoreboardCameraId)
            .putBoolean("scoreboard_enabled", configuration.scoreboardEnabled)
            .putString("scoreboard_source", configuration.scoreboardSource.name)
            .putString("clock_source", configuration.clockSource.name)
            .putLong("clock_photo_interval_ms", configuration.clockPhotoIntervalMillis)
            .putLong("scoreboard_photo_interval_ms", configuration.scoreboardPhotoIntervalMillis)
            .putFloat("crop_zoom", configuration.cropZoom)
            .putFloat("crop_pan_x", configuration.cropPanX)
            .putFloat("crop_pan_y", configuration.cropPanY)
            .putString("scoreboard_corners", configuration.scoreboardCorners.joinToString(";") { "${it.x},${it.y}" })
            .putString("scoreboard_destination", with(configuration.scoreboardDestination) { "$left,$top,$right,$bottom" })
            .putString("clock_camera", configuration.clockCameraId)
            .putBoolean("clock_enabled", configuration.clockEnabled)
            .putString("clock_corners", configuration.clockCorners.joinToString(";") { "${it.x},${it.y}" })
            .putString("clock_destination", with(configuration.clockDestination) { "$left,$top,$right,$bottom" })
            .putInt("capture_width", configuration.captureWidth)
            .putInt("capture_height", configuration.captureHeight)
            .putInt("capture_fps", configuration.captureFps)
            .putInt("scoreboard_capture_width", configuration.scoreboardCaptureWidth)
            .putInt("scoreboard_capture_height", configuration.scoreboardCaptureHeight)
            .putInt("scoreboard_capture_fps", configuration.scoreboardCaptureFps)
            .putInt("clock_capture_width", configuration.clockCaptureWidth)
            .putInt("clock_capture_height", configuration.clockCaptureHeight)
            .putInt("clock_capture_fps", configuration.clockCaptureFps)
            .putString("logo_uri", configuration.logoUri)
            .putBoolean("logo_enabled", configuration.logoEnabled)
            .putBoolean("logo_white_transparent", configuration.logoWhiteTransparent)
            .putFloat("logo_width", configuration.logoWidth)
            .putFloat("logo_center_x", configuration.logoCenterX)
            .putFloat("logo_center_y", configuration.logoCenterY)
            .putString("broadcast_protocol", configuration.protocol.name)
            .putString("video_codec", configuration.videoCodec.name)
            .putString("output_resolution", configuration.outputResolution.name)
            .putInt("output_fps", configuration.outputFps)
            .putString("bitrate_preset", configuration.bitratePreset.name)
            .putString("youtube_server", configuration.youtubeServerUrl)
            .putString("youtube_key", configuration.youtubeStreamKey)
            .putString("youtube_oauth_client_id", configuration.youtubeOAuthClientId)
            .putString("youtube_oauth_client_secret", configuration.youtubeOAuthClientSecret)
            .putString("live_title", configuration.liveTitle)
            .putString("live_privacy", configuration.livePrivacy.name)
            .putString("live_latency", configuration.liveLatency.name)
            .putString("composition_engine", configuration.compositionEngine.name)
            .putString("frame_rotation", configuration.frameRotation.name)
            .apply()
    }

    private fun decodeCorners(
        value: String?,
        fallback: List<NormalizedPoint> = DEFAULT_SCOREBOARD_CORNERS,
    ): List<NormalizedPoint> = runCatching {
        value.orEmpty().split(';').map { encoded ->
            val coordinates = encoded.split(',')
            NormalizedPoint(coordinates[0].toFloat(), coordinates[1].toFloat())
        }.also { require(it.size == 4) }
    }.getOrDefault(fallback)

    private fun decodeRect(
        value: String?,
        fallback: NormalizedRect = DEFAULT_SCOREBOARD_DESTINATION,
    ): NormalizedRect = runCatching {
        val coordinates = value.orEmpty().split(',').map(String::toFloat)
        require(coordinates.size == 4)
        NormalizedRect(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
    }.getOrDefault(fallback)

    private companion object {
        val OUTPUT_FPS_OPTIONS = setOf(15, 20, 24, 30, 60)
    }
}
