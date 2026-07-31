package dev.cascam.config

import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect

data class BroadcastConfiguration(
    val courtCameraId: String = "",
    val scoreboardCameraId: String = "",
    val cropZoom: Float = 1f,
    val cropPanX: Float = 0f,
    val cropPanY: Float = 0f,
    val scoreboardCorners: List<NormalizedPoint> = DEFAULT_SCOREBOARD_CORNERS,
    val scoreboardDestination: NormalizedRect = DEFAULT_SCOREBOARD_DESTINATION,
    val scoreboardZoom: Float = 1f,
    val protocol: BroadcastProtocol = BroadcastProtocol.RTMPS,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val bitratePreset: BitratePreset = BitratePreset.AUTO,
    val youtubeServerUrl: String = "rtmps://a.rtmps.youtube.com/live2",
    val youtubeStreamKey: String = "",
)

enum class BroadcastProtocol(val label: String) { RTMPS("RTMPS"), HLS("HLS") }
enum class VideoCodec(val label: String) { H264("H.264 / AVC"), H265("H.265 / HEVC") }
enum class BitratePreset(val label: String, val bitsPerSecond: Int?) {
    AUTO("Automático (Wi-Fi alto / dados baixo)", null),
    ULTRA_LOW("Ultra baixo · 250 kbps", 250_000),
    LOW("Baixo · 500 kbps", 500_000),
    MEDIUM("Médio · 1,5 Mbps", 1_500_000),
    HIGH("Alto · 3 Mbps", 3_000_000),
}

val DEFAULT_SCOREBOARD_DESTINATION = NormalizedRect(.04f, .05f, .36f, .24f)

val DEFAULT_SCOREBOARD_CORNERS = listOf(
    NormalizedPoint(.68f, .08f),
    NormalizedPoint(.93f, .12f),
    NormalizedPoint(.91f, .30f),
    NormalizedPoint(.66f, .27f),
)
