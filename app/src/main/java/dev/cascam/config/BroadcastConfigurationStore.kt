package dev.cascam.config

import android.content.Context
import dev.cascam.geometry.NormalizedPoint

class BroadcastConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("broadcast_configuration", Context.MODE_PRIVATE)

    fun load(): BroadcastConfiguration = BroadcastConfiguration(
        courtCameraId = preferences.getString("court_camera", "").orEmpty(),
        scoreboardCameraId = preferences.getString("scoreboard_camera", "").orEmpty(),
        cropZoom = preferences.getFloat("crop_zoom", 1f),
        cropPanX = preferences.getFloat("crop_pan_x", 0f),
        cropPanY = preferences.getFloat("crop_pan_y", 0f),
        scoreboardCorners = decodeCorners(preferences.getString("scoreboard_corners", null)),
        scoreboardZoom = preferences.getFloat("scoreboard_zoom", 1f),
        scoreboardPlacement = runCatching {
            ScoreboardPlacement.valueOf(preferences.getString("scoreboard_placement", null).orEmpty())
        }.getOrDefault(ScoreboardPlacement.TOP_RIGHT),
        youtubeServerUrl = preferences.getString("youtube_server", null)
            ?: "rtmps://a.rtmps.youtube.com/live2",
        youtubeStreamKey = preferences.getString("youtube_key", "").orEmpty(),
    )

    fun save(configuration: BroadcastConfiguration) {
        preferences.edit()
            .putString("court_camera", configuration.courtCameraId)
            .putString("scoreboard_camera", configuration.scoreboardCameraId)
            .putFloat("crop_zoom", configuration.cropZoom)
            .putFloat("crop_pan_x", configuration.cropPanX)
            .putFloat("crop_pan_y", configuration.cropPanY)
            .putString("scoreboard_corners", configuration.scoreboardCorners.joinToString(";") { "${it.x},${it.y}" })
            .putFloat("scoreboard_zoom", configuration.scoreboardZoom)
            .putString("scoreboard_placement", configuration.scoreboardPlacement.name)
            .putString("youtube_server", configuration.youtubeServerUrl)
            .putString("youtube_key", configuration.youtubeStreamKey)
            .apply()
    }

    private fun decodeCorners(value: String?): List<NormalizedPoint> = runCatching {
        value.orEmpty().split(';').map { encoded ->
            val coordinates = encoded.split(',')
            NormalizedPoint(coordinates[0].toFloat(), coordinates[1].toFloat())
        }.also { require(it.size == 4) }
    }.getOrDefault(DEFAULT_SCOREBOARD_CORNERS)
}
