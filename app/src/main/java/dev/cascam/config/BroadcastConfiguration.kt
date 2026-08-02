package dev.cascam.config

import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect

data class BroadcastConfiguration(
    val courtCameraId: String = "",
    val scoreboardCameraId: String = "",
    val scoreboardEnabled: Boolean = true,
    val cropZoom: Float = 1f,
    val cropPanX: Float = 0f,
    val cropPanY: Float = 0f,
    val scoreboardCorners: List<NormalizedPoint> = DEFAULT_SCOREBOARD_CORNERS,
    val scoreboardDestination: NormalizedRect = DEFAULT_SCOREBOARD_DESTINATION,
    val clockCameraId: String = "",
    val clockEnabled: Boolean = false,
    val clockCorners: List<NormalizedPoint> = DEFAULT_CLOCK_CORNERS,
    val clockDestination: NormalizedRect = DEFAULT_CLOCK_DESTINATION,
    /** Zero mantém a escolha automática até o diagnóstico fornecer perfis aprovados. */
    val captureWidth: Int = 0,
    val captureHeight: Int = 0,
    val captureFps: Int = 0,
    val captureZoom: Float = 1f,
    val scoreboardCaptureWidth: Int = 0,
    val scoreboardCaptureHeight: Int = 0,
    val scoreboardCaptureFps: Int = 0,
    val scoreboardCaptureZoom: Float = 1f,
    val clockCaptureWidth: Int = 0,
    val clockCaptureHeight: Int = 0,
    val clockCaptureFps: Int = 0,
    val clockCaptureZoom: Float = 1f,
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
    val frameRotation: FrameRotation = FrameRotation.AUTO,
) {
    /** Câmeras distintas necessárias; camadas podem compartilhar a mesma fonte. */
    fun requiredCameraIds(): Set<String> = buildSet {
        courtCameraId.takeIf(String::isNotBlank)?.let(::add)
        if (scoreboardEnabled) add(scoreboardCameraId.ifBlank { courtCameraId })
        if (clockEnabled) add(clockCameraId.ifBlank { courtCameraId })
    }.filterTo(linkedSetOf()) { it.isNotBlank() }

    fun cameraIdFor(layer: OverlayLayer): String = when (layer) {
        OverlayLayer.SCOREBOARD -> scoreboardCameraId
        OverlayLayer.CLOCK -> clockCameraId
    }.ifBlank { courtCameraId }

    val requestedCaptureProfile: CaptureProfile?
        get() = if (captureWidth > 0 && captureHeight > 0 && captureFps > 0) {
            CaptureProfile(captureWidth, captureHeight, captureFps)
        } else null

    fun captureSettingsFor(layer: OverlayLayer?): CaptureSettings = when (layer) {
        null -> CaptureSettings(captureWidth, captureHeight, captureFps)
        OverlayLayer.SCOREBOARD -> CaptureSettings(scoreboardCaptureWidth, scoreboardCaptureHeight, scoreboardCaptureFps)
        OverlayLayer.CLOCK -> CaptureSettings(clockCaptureWidth, clockCaptureHeight, clockCaptureFps)
    }

    /** Quando camadas compartilham a câmera, um único stream atende a configuração mais exigente. */
    fun resolvedCaptureSettings(cameraId: String): CaptureSettings {
        val requested = buildList {
            if (courtCameraId == cameraId) add(captureSettingsFor(null))
            if (scoreboardEnabled && cameraIdFor(OverlayLayer.SCOREBOARD) == cameraId) add(captureSettingsFor(OverlayLayer.SCOREBOARD))
            if (clockEnabled && cameraIdFor(OverlayLayer.CLOCK) == cameraId) add(captureSettingsFor(OverlayLayer.CLOCK))
        }
        val largest = requested.filter { it.hasSize }.maxByOrNull { it.width.toLong() * it.height }
        return CaptureSettings(largest?.width ?: 0, largest?.height ?: 0, requested.maxOfOrNull { it.fps } ?: 0)
    }

    /** Uma câmera compartilhada só produz um stream; nesse caso prevalece o maior zoom pedido. */
    fun resolvedCaptureZoom(cameraId: String): Float = buildList {
        if (courtCameraId == cameraId) add(captureZoom)
        if (scoreboardEnabled && cameraIdFor(OverlayLayer.SCOREBOARD) == cameraId) add(scoreboardCaptureZoom)
        if (clockEnabled && cameraIdFor(OverlayLayer.CLOCK) == cameraId) add(clockCaptureZoom)
    }.maxOrNull()?.coerceIn(1f, 8f) ?: 1f
}

data class CaptureSettings(val width: Int = 0, val height: Int = 0, val fps: Int = 0) {
    val hasSize: Boolean get() = width > 0 && height > 0
}

data class CaptureProfile(val width: Int, val height: Int, val fps: Int) {
    init {
        require(width > 0 && height > 0 && fps > 0)
    }

    val label: String get() = "${width}×$height · $fps fps"
}

enum class OverlayLayer(val label: String) {
    SCOREBOARD("Placar"), CLOCK("Cronômetro"),
}

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

/**
 * Giro aplicado à imagem da câmera antes de compor. O automático deriva da orientação do sensor e
 * acerta na maioria dos casos, mas cada HAL entrega o buffer do seu jeito e não há como testar todos
 * — por isso os valores fixos ficam à mão, para corrigir na tela em vez de esperar outra versão.
 */
enum class FrameRotation(val label: String, val degrees: Int?) {
    AUTO("Automática", null),
    NONE("0°", 0),
    QUARTER("90° horário", 90),
    HALF("180°", 180),
    THREE_QUARTERS("90° anti-horário", 270),
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
val DEFAULT_CLOCK_DESTINATION = NormalizedRect(.72f, .05f, .96f, .18f)

val DEFAULT_SCOREBOARD_CORNERS = listOf(
    NormalizedPoint(.68f, .08f),
    NormalizedPoint(.93f, .12f),
    NormalizedPoint(.91f, .30f),
    NormalizedPoint(.66f, .27f),
)

val DEFAULT_CLOCK_CORNERS = listOf(
    NormalizedPoint(.36f, .08f),
    NormalizedPoint(.64f, .08f),
    NormalizedPoint(.64f, .24f),
    NormalizedPoint(.36f, .24f),
)
