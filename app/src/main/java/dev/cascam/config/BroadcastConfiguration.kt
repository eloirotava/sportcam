package dev.cascam.config

import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect

data class BroadcastConfiguration(
    val courtCameraId: String = "",
    val scoreboardCameraId: String = "",
    val scoreboardEnabled: Boolean = true,
    val scoreboardSource: OverlaySource = OverlaySource.VIDEO,
    /** Intervalo base entre fotos; a proteção térmica pode aumentá-lo temporariamente. */
    val scoreboardPhotoIntervalMillis: Long = 1_000L,
    val cropZoom: Float = 1f,
    val cropPanX: Float = 0f,
    val cropPanY: Float = 0f,
    val scoreboardCorners: List<NormalizedPoint> = DEFAULT_SCOREBOARD_CORNERS,
    val scoreboardDestination: NormalizedRect = DEFAULT_SCOREBOARD_DESTINATION,
    val clockCameraId: String = "",
    val clockEnabled: Boolean = false,
    val clockSource: OverlaySource = OverlaySource.VIDEO,
    val clockPhotoIntervalMillis: Long = 1_000L,
    val clockCorners: List<NormalizedPoint> = DEFAULT_CLOCK_CORNERS,
    val clockDestination: NormalizedRect = DEFAULT_CLOCK_DESTINATION,
    /** Zero mantém a escolha automática até o diagnóstico fornecer perfis aprovados. */
    val captureWidth: Int = 0,
    val captureHeight: Int = 0,
    val captureFps: Int = 0,
    val scoreboardCaptureWidth: Int = 0,
    val scoreboardCaptureHeight: Int = 0,
    val scoreboardCaptureFps: Int = 0,
    val clockCaptureWidth: Int = 0,
    val clockCaptureHeight: Int = 0,
    val clockCaptureFps: Int = 0,
    val logoUri: String = "",
    val logoEnabled: Boolean = false,
    val logoWhiteTransparent: Boolean = false,
    /** Largura relativa à saída; a altura preserva a proporção original da imagem. */
    val logoWidth: Float = .18f,
    val logoCenterX: Float = .88f,
    val logoCenterY: Float = .85f,
    val protocol: BroadcastProtocol = BroadcastProtocol.RTMPS,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val outputResolution: OutputResolution = OutputResolution.HD,
    val outputFps: Int = 20,
    val bitratePreset: BitratePreset = BitratePreset.AUTO,
    val youtubeServerUrl: String = "rtmps://a.rtmps.youtube.com/live2",
    val youtubeStreamKey: String = "",
    val youtubeOAuthClientId: String = "",
    val youtubeOAuthClientSecret: String = "",
    val liveTitle: String = "SportCam ao vivo",
    val livePrivacy: LivePrivacy = LivePrivacy.UNLISTED,
    val liveLatency: LiveLatency = LiveLatency.LOW,
    val compositionEngine: CompositionEngine = CompositionEngine.CPU,
    val frameRotation: FrameRotation = FrameRotation.AUTO,
    /**
     * Configuração remota. O site é servido pelo próprio aparelho e alcançado pelo túnel cf-p, que
     * é o que resolve o celular no 4G do ginásio: atrás de CGNAT ele não tem endereço para receber
     * conexão, e sem túnel reverso não existe caminho até ele. A transmissão não passa por aqui —
     * o RTMPS continua saindo direto para o YouTube.
     */
    val remoteEnabled: Boolean = false,
    val remotePort: Int = 8080,
    val remoteUser: String = "sportcam",
    val remotePassword: String = "",
    val tunnelUrl: String = "",
    val tunnelToken: String = "",
) {
    /** Só vale ligar o remoto com senha: o site expõe a chave do YouTube e o botão de transmitir. */
    val remoteReady: Boolean get() = remoteEnabled && remotePassword.isNotBlank() && remotePort in 1024..65535

    val tunnelReady: Boolean get() = remoteReady && tunnelUrl.startsWith("wss://") && tunnelToken.isNotBlank()

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

    fun enabled(layer: OverlayLayer): Boolean = when (layer) {
        OverlayLayer.SCOREBOARD -> scoreboardEnabled
        OverlayLayer.CLOCK -> clockEnabled
    }

    fun sourceFor(layer: OverlayLayer): OverlaySource = when (layer) {
        OverlayLayer.SCOREBOARD -> scoreboardSource
        OverlayLayer.CLOCK -> clockSource
    }

    fun photoIntervalFor(layer: OverlayLayer): Long = when (layer) {
        OverlayLayer.SCOREBOARD -> scoreboardPhotoIntervalMillis
        OverlayLayer.CLOCK -> clockPhotoIntervalMillis
    }

    /**
     * Foto exige câmera exclusiva porque a fonte inteira troca de vídeo para JPEG: quem
     * compartilhasse a câmera ficaria sem quadros de vídeo. Vale para as duas sobreposições.
     */
    fun canUsePhoto(layer: OverlayLayer): Boolean {
        if (!enabled(layer)) return false
        val camera = cameraIdFor(layer)
        if (camera.isBlank() || camera == courtCameraId) return false
        val other = OverlayLayer.entries.first { it != layer }
        return !enabled(other) || cameraIdFor(other) != camera
    }

    fun usesPhoto(layer: OverlayLayer): Boolean =
        sourceFor(layer) == OverlaySource.PHOTO_EVERY_SECOND && canUsePhoto(layer)

    /** Camada cuja foto sai desta câmera, quando existe; é ela que dá a geometria do JPEG. */
    fun photoLayerFor(cameraId: String): OverlayLayer? =
        OverlayLayer.entries.firstOrNull { usesPhoto(it) && cameraIdFor(it) == cameraId }

    fun stillIntervalFor(cameraId: String): Long = photoLayerFor(cameraId)
        ?.let { photoIntervalFor(it).coerceIn(1_000L, 30_000L) } ?: 0L

    val requestedCaptureProfile: CaptureProfile?
        get() = if (captureWidth > 0 && captureHeight > 0 && captureFps > 0) {
            CaptureProfile(captureWidth, captureHeight, captureFps)
        } else null

    fun captureSettingsFor(layer: OverlayLayer?): CaptureSettings = when (layer) {
        // A fonte principal dirige o ritmo do compositor. Em "Seguir saída", pede à câmera o
        // mesmo FPS configurado para o encoder em vez de deixar o HAL escolher silenciosamente.
        null -> CaptureSettings(captureWidth, captureHeight, captureFps.takeIf { it > 0 } ?: outputFps)
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

    /**
     * Camadas que dividem a câmera com [layer], sem contar ela mesma. Quando a lista não está vazia,
     * a resolução e o FPS escolhidos na tela dessa camada são apenas um pedido: [resolvedCaptureSettings]
     * atende a exigência maior entre todas, e a tela precisa dizer isso em vez de fingir independência.
     */
    fun layersSharingCameraWith(layer: OverlayLayer?): List<String> {
        val camera = if (layer == null) courtCameraId else cameraIdFor(layer)
        if (camera.isBlank()) return emptyList()
        return buildList {
            if (layer != null && courtCameraId == camera) add("quadra")
            OverlayLayer.entries.forEach { other ->
                if (other != layer && enabled(other) && cameraIdFor(other) == camera) add(other.label.lowercase())
            }
        }
    }
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

/**
 * De onde sai a imagem de uma sobreposição. Vídeo acompanha movimento e custa um stream contínuo;
 * foto usa o JPEG cheio do sensor, muito mais nítido para dígitos pequenos, ao preço de atualizar
 * só a cada intervalo. Placar quase não se move e ganha com foto; cronômetro corre e costuma pedir
 * vídeo — mas quem decide é quem está no ginásio, então a opção vale para as duas.
 */
enum class OverlaySource(val label: String) {
    VIDEO("Vídeo · acompanha movimento"),
    PHOTO_EVERY_SECOND("Foto alta · intervalo configurável"),
}

val PHOTO_INTERVAL_OPTIONS_MILLIS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

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
enum class OutputResolution(val label: String, val width: Int, val height: Int) {
    P360("360p · 640×360", 640, 360),
    P540("540p · 960×540", 960, 540),
    HD("720p · 1280×720", 1280, 720),
    FULL_HD("1080p · 1920×1080", 1920, 1080);

    fun recommendedBitrate(fps: Int): Int = when (this) {
        P360 -> if (fps > 30) 1_500_000 else 800_000
        P540 -> if (fps > 30) 3_000_000 else 1_500_000
        HD -> if (fps > 30) 4_500_000 else 3_000_000
        FULL_HD -> if (fps > 30) 9_000_000 else 6_000_000
    }
}
enum class BitratePreset(val label: String, val bitsPerSecond: Int?) {
    AUTO("Automático para resolução/FPS", null),
    ULTRA_LOW("Ultra baixo · 250 kbps", 250_000),
    LOW("Baixo · 500 kbps", 500_000),
    MEDIUM("Médio · 1,5 Mbps", 1_500_000),
    HIGH("Alto · 3 Mbps", 3_000_000),
    VERY_HIGH("Muito alto · 4,5 Mbps", 4_500_000),
    FULL_HD("Full HD · 6 Mbps", 6_000_000),
    FULL_HD_60("Full HD 60 · 9 Mbps", 9_000_000),
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
