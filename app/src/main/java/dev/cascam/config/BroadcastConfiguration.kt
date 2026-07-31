package dev.cascam.config

import dev.cascam.geometry.NormalizedPoint

data class BroadcastConfiguration(
    val courtCameraId: String = "",
    val scoreboardCameraId: String = "",
    val cropZoom: Float = 1f,
    val cropPanX: Float = 0f,
    val cropPanY: Float = 0f,
    val scoreboardCorners: List<NormalizedPoint> = DEFAULT_SCOREBOARD_CORNERS,
    val scoreboardPlacement: ScoreboardPlacement = ScoreboardPlacement.TOP_RIGHT,
    val youtubeServerUrl: String = "rtmps://a.rtmps.youtube.com/live2",
    val youtubeStreamKey: String = "",
)

val DEFAULT_SCOREBOARD_CORNERS = listOf(
    NormalizedPoint(.68f, .08f),
    NormalizedPoint(.93f, .12f),
    NormalizedPoint(.91f, .30f),
    NormalizedPoint(.66f, .27f),
)

enum class ScoreboardPlacement(val label: String) {
    TOP_LEFT("Superior esquerdo"),
    TOP_RIGHT("Superior direito"),
    BOTTOM_LEFT("Inferior esquerdo"),
    BOTTOM_RIGHT("Inferior direito"),
}
