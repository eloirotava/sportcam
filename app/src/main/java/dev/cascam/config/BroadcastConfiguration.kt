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
    val youtubeOAuthClientId: String = "",
    val youtubeOAuthClientSecret: String = "",
    val liveTitle: String = "CasCam ao vivo",
    val livePrivacy: LivePrivacy = LivePrivacy.UNLISTED,
    val liveLatency: LiveLatency = LiveLatency.LOW,
    val compositionEngine: CompositionEngine = CompositionEngine.CPU,
)

enum class BroadcastProtocol(val label: String) { RTMPS("RTMPS"), HLS("HLS") }

/**
 * Onde a quadra e o placar são juntados. A CPU converte cada quadro para bitmap e desenha em
 * Canvas; a GPU deixa a câmera escrever em textura e compõe em OpenGL, sem nenhum pixel passando
 * pelo processador. A escolha fica na tela porque as duas têm defeito: a CPU esquenta o aparelho,
 * e a GPU depende de o driver aceitar a combinação de superfícies.
 */
enum class CompositionEngine(val label: String) {
    CPU("CPU · Canvas"), GPU("GPU · OpenGL"),
}
enum class VideoCodec(val label: String) { H264("H.264 / AVC"), H265("H.265 / HEVC") }
enum class BitratePreset(val label: String, val bitsPerSecond: Int?) {
    AUTO("Automático (Wi-Fi alto / dados baixo)", null),
    ULTRA_LOW("Ultra baixo · 250 kbps", 250_000),
    LOW("Baixo · 500 kbps", 500_000),
    MEDIUM("Médio · 1,5 Mbps", 1_500_000),
    HIGH("Alto · 3 Mbps", 3_000_000),
}
enum class LivePrivacy(val label: String, val apiValue: String) {
    UNLISTED("Não listado", "unlisted"), PRIVATE("Privado", "private"), PUBLIC("Público", "public"),
}

/**
 * Mesmas opções do YouTube Studio, sem o app filtrar nada: quem aceita ou recusa a combinação é o
 * YouTube, e a recusa aparece na tela. A ultrabaixa abre mão do DVR, então quem assiste não
 * consegue voltar a fita durante o jogo.
 *
 * Em HLS o atraso não vem só desta escolha: ingestão por segmentos carrega o tamanho do segmento
 * embutido na latência, e é por isso que [segmentMillis] encolhe junto. O YouTube aceita de 1 a 4
 * segundos por segmento, e quanto menor, menor o atraso.
 */
enum class LiveLatency(val label: String, val apiValue: String, val allowsDvr: Boolean, val segmentMillis: Int) {
    NORMAL("Normal · mais estável", "normal", true, 4_000),
    LOW("Baixa", "low", true, 2_000),
    ULTRA_LOW("Ultrabaixa · sem DVR", "ultraLow", false, 1_000),
}

val DEFAULT_SCOREBOARD_DESTINATION = NormalizedRect(.04f, .05f, .36f, .24f)

val DEFAULT_SCOREBOARD_CORNERS = listOf(
    NormalizedPoint(.68f, .08f),
    NormalizedPoint(.93f, .12f),
    NormalizedPoint(.91f, .30f),
    NormalizedPoint(.66f, .27f),
)
