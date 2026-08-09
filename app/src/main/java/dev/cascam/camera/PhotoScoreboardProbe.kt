package dev.cascam.camera

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import dev.cascam.gl.EglCore
import java.util.concurrent.Executor

/**
 * Prova isoladamente a arquitetura pretendida: vídeo contínuo da quadra numa SurfaceTexture real
 * de OpenGL e fotos JPEG avulsas do sensor do placar. Não basta configurar a sessão; só aprova se
 * três fotos chegam no tamanho pedido enquanto a textura da quadra continua sendo consumida.
 */
class PhotoScoreboardProbe(
    private val manager: CameraManager,
    private val onProgress: (String) -> Unit,
    private val onReport: (String) -> Unit,
) {
    private data class AttemptResult(
        val stillSize: Size,
        val approved: Boolean,
        val configured: Boolean,
        val stillCount: Int,
        val decodedSize: Size?,
        val videoFps: Float,
        val maximumGapMs: Long,
        val detail: String,
    )

    private val thread = HandlerThread("sportcam-photo-probe").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor(handler::post)
    private val attempts = mutableListOf<AttemptResult>()
    private var current: Attempt? = null
    private var stillCandidates = emptyList<Size>()
    private var candidateIndex = 0
    private var court: CameraInfo? = null
    private var scoreboard: CameraInfo? = null
    private var videoSize: Size? = null

    @Volatile var isRunning = false
        private set

    fun start(court: CameraInfo, scoreboard: CameraInfo) {
        if (isRunning) return
        isRunning = true
        attempts.clear()
        candidateIndex = 0
        this.court = court
        this.scoreboard = scoreboard
        handler.post {
            val error = validatePair(court, scoreboard)
            if (error != null) {
                finish("REPROVADO ANTES DE ABRIR A CÂMERA: $error")
                return@post
            }
            val selectedVideoSize = chooseVideoSize(court)
            val candidates = chooseStillSizes(scoreboard, selectedVideoSize)
            if (selectedVideoSize == null || candidates.isEmpty()) {
                finish("REPROVADO ANTES DE ABRIR A CÂMERA: o HAL não anunciou tamanhos úteis para vídeo + foto.")
                return@post
            }
            videoSize = selectedVideoSize
            stillCandidates = candidates
            onProgress(
                "Teste GPU real: quadra ${selectedVideoSize.width}×${selectedVideoSize.height}; " +
                    "${candidates.size} resolução(ões) de foto. Não mexa no aparelho.",
            )
            runNext()
        }
    }

    fun cancel() {
        if (!isRunning) return
        handler.post {
            current?.cancel()
            current = null
            finish("TESTE INTERROMPIDO")
        }
    }

    fun shutdown() {
        if (isRunning) cancel()
        thread.quitSafely()
    }

    private fun runNext() {
        if (!isRunning) return
        if (candidateIndex >= stillCandidates.size) {
            finish(null)
            return
        }
        val court = court ?: return
        val scoreboard = scoreboard ?: return
        val video = videoSize ?: return
        val still = stillCandidates[candidateIndex]
        onProgress(
            "Tentativa ${candidateIndex + 1}/${stillCandidates.size}: GPU ${video.width}×${video.height} + " +
                "foto ${still.width}×${still.height}…",
        )
        current = Attempt(court, scoreboard, video, still) { result ->
            attempts += result
            current = null
            if (result.approved) finish(null) else {
                candidateIndex++
                handler.postDelayed(::runNext, BETWEEN_ATTEMPTS_MS)
            }
        }.also(Attempt::start)
    }

    private fun finish(earlyConclusion: String?) {
        if (!isRunning) return
        isRunning = false
        current?.cancel()
        current = null
        val report = buildReport(earlyConclusion)
        onProgress(report.lineSequence().firstOrNull { it.startsWith("CONCLUSÃO:") } ?: "Teste encerrado.")
        onReport(report)
        thread.quitSafely()
    }

    private fun validatePair(court: CameraInfo, scoreboard: CameraInfo): String? = when {
        court.physicalCameraId == null || scoreboard.physicalCameraId == null ->
            "selecione dois sensores FÍSICOS nas abas Quadra e Placar"
        court.logicalCameraId != scoreboard.logicalCameraId ->
            "este teste exige dois sensores físicos da mesma câmera lógica"
        court.physicalCameraId == scoreboard.physicalCameraId ->
            "quadra e placar estão no mesmo sensor"
        else -> null
    }

    private fun chooseVideoSize(camera: CameraInfo): Size? {
        val id = camera.physicalCameraId ?: camera.logicalCameraId
        val sizes = outputSizes(id, SurfaceTexture::class.java).ifEmpty {
            outputSizes(camera.logicalCameraId, SurfaceTexture::class.java)
        }
        return sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun chooseStillSizes(camera: CameraInfo, video: Size?): List<Size> {
        val id = camera.physicalCameraId ?: camera.logicalCameraId
        val all = jpegSizes(id).ifEmpty { jpegSizes(camera.logicalCameraId) }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height }
        if (all.isEmpty()) return emptyList()
        val limits = listOf(Int.MAX_VALUE, 3840, 2560, 1920)
        val selected = limits.mapNotNull { limit -> all.firstOrNull { it.width <= limit } }.distinct()
        val useful = video?.let { baseline -> selected.filter { it.width > baseline.width || it.height > baseline.height } }.orEmpty()
        return (useful.ifEmpty { selected }).take(MAXIMUM_ATTEMPTS)
    }

    private fun jpegSizes(cameraId: String): List<Size> = runCatching {
        manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private fun outputSizes(cameraId: String, klass: Class<*>): List<Size> = runCatching {
        manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(klass)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private fun buildReport(earlyConclusion: String?): String {
        val court = court
        val scoreboard = scoreboard
        val video = videoSize
        val winner = attempts.firstOrNull { it.approved }
        return buildString {
            appendLine("SPORTCAM · TESTE GPU + FOTO")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Quadra: ${court?.id} · física ${court?.physicalCameraId} · ${court?.lensLabel}")
            appendLine("Placar: ${scoreboard?.id} · física ${scoreboard?.physicalCameraId} · ${scoreboard?.lensLabel}")
            video?.let { appendLine("Vídeo testado: SurfaceTexture OpenGL ${it.width}×${it.height}") }
            appendLine("Critério: 3/3 fotos, tamanho cheio, vídeo ≥ ${MINIMUM_VIDEO_FPS.toInt()} fps e pausa máxima ≤ ${MAXIMUM_GAP_MS} ms")
            appendLine()
            attempts.forEachIndexed { index, attempt ->
                appendLine("TENTATIVA ${index + 1}: foto pedida ${attempt.stillSize.width}×${attempt.stillSize.height}")
                appendLine("  ${if (attempt.approved) "APROVADA" else "REPROVADA"} · ${attempt.detail}")
                appendLine("  sessão=${if (attempt.configured) "aceita" else "recusada"}; fotos=${attempt.stillCount}/$STILL_COUNT; recebida=${attempt.decodedSize?.let { "${it.width}×${it.height}" } ?: "nenhuma"}; vídeo=%.1f fps; maior pausa=${attempt.maximumGapMs} ms".format(attempt.videoFps))
            }
            appendLine()
            when {
                earlyConclusion != null -> appendLine("CONCLUSÃO: $earlyConclusion")
                winner != null -> {
                    val gain = video?.let { winner.decodedSize?.width?.toFloat()?.div(it.width) }
                    append("CONCLUSÃO: APROVADO PARA IMPLEMENTAR. GPU + foto funcionaram juntas")
                    gain?.let { append("; a foto entregou %.1f× mais pixels na largura".format(it)) }
                    appendLine(".")
                    appendLine("Perfil recomendado: vídeo ${video?.width}×${video?.height} + foto ${winner.stillSize.width}×${winner.stillSize.height} a cada 1 s.")
                }
                else -> appendLine("CONCLUSÃO: REPROVADO. Nenhuma resolução manteve a quadra em GPU enquanto entregava três fotos completas.")
            }
        }.trim()
    }

    private inner class Attempt(
        private val court: CameraInfo,
        private val scoreboard: CameraInfo,
        private val videoSize: Size,
        private val stillSize: Size,
        private val onDone: (AttemptResult) -> Unit,
    ) {
        private var egl: EglCore? = null
        private var anchor: EGLSurface? = null
        private var textureId = 0
        private var stream: SurfaceTexture? = null
        private var videoSurface: Surface? = null
        private var stillReader: ImageReader? = null
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var finished = false
        private var configured = false
        private var measurementStartedAt = 0L
        private var videoFrames = 0
        private var lastVideoAt = 0L
        private var maximumGapMs = 0L
        private var stillCount = 0
        private var decodedSize: Size? = null
        private var captureFailure: String? = null

        @SuppressLint("MissingPermission")
        fun start() {
            handler.postDelayed({ fail("tempo limite de ${ATTEMPT_TIMEOUT_MS / 1_000}s") }, ATTEMPT_TIMEOUT_MS)
            runCatching {
                setUpGl()
                setUpStillReader()
                manager.openCamera(court.logicalCameraId, cameraCallback, handler)
            }.onFailure { fail("falha ao montar: ${it.message}") }
        }

        fun cancel() {
            if (finished) return
            finished = true
            cleanUp()
        }

        private fun setUpGl() {
            val core = EglCore().also(EglCore::setUp)
            val surface = core.createOffscreenSurface().also(core::makeCurrent)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val texture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(videoSize.width, videoSize.height)
                setOnFrameAvailableListener({ available -> consumeVideoFrame(available) }, handler)
            }
            egl = core
            anchor = surface
            stream = texture
            videoSurface = Surface(texture)
        }

        private fun consumeVideoFrame(available: SurfaceTexture) {
            if (finished) return
            runCatching {
                val core = egl ?: return@runCatching
                val surface = anchor ?: return@runCatching
                core.makeCurrent(surface)
                available.updateTexImage()
                if (measurementStartedAt > 0L) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastVideoAt > 0L) maximumGapMs = maxOf(maximumGapMs, now - lastVideoAt)
                    lastVideoAt = now
                    videoFrames++
                }
            }.onFailure { fail("SurfaceTexture GPU parou: ${it.message}") }
        }

        private fun setUpStillReader() {
            stillReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            decodedSize = Size(bounds.outWidth, bounds.outHeight)
                            stillCount++
                        }
                    } finally { image.close() }
                }, handler)
            }
        }

        private val cameraCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                configure(camera)
            }
            override fun onDisconnected(camera: CameraDevice) = fail("câmera desconectada")
            override fun onError(camera: CameraDevice, error: Int) = fail("erro ao abrir câmera ($error)")
        }

        private fun configure(camera: CameraDevice) {
            val video = videoSurface ?: return fail("SurfaceTexture não criada")
            val still = stillReader?.surface ?: return fail("ImageReader JPEG não criado")
            val outputs = listOf(
                OutputConfiguration(video).apply { setPhysicalCameraId(court.physicalCameraId!!) },
                OutputConfiguration(still).apply { setPhysicalCameraId(scoreboard.physicalCameraId!!) },
            )
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    session = value
                    configured = true
                    beginVideo(camera, value)
                }
                override fun onConfigureFailed(value: CameraCaptureSession) = fail("HAL recusou SurfaceTexture + JPEG")
            }
            runCatching {
                camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback))
            }.onFailure { fail("createCaptureSession: ${it.message}") }
        }

        private fun beginVideo(camera: CameraDevice, configured: CameraCaptureSession) {
            val video = videoSurface ?: return fail("SurfaceTexture desapareceu")
            runCatching {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, setOf(court.physicalCameraId!!)).apply {
                    addTarget(video)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }.build()
                configured.setRepeatingRequest(request, null, handler)
                handler.postDelayed({ beginMeasurement(camera, configured) }, WARMUP_MS)
            }.onFailure { fail("não iniciou vídeo: ${it.message}") }
        }

        private fun beginMeasurement(camera: CameraDevice, configured: CameraCaptureSession) {
            if (finished) return
            measurementStartedAt = SystemClock.elapsedRealtime()
            videoFrames = 0
            lastVideoAt = 0L
            maximumGapMs = 0L
            stillCount = 0
            decodedSize = null
            val still = stillReader?.surface ?: return fail("JPEG desapareceu")
            val request = runCatching {
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, setOf(scoreboard.physicalCameraId!!)).apply {
                    addTarget(still)
                    set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }.build()
            }.getOrElse { return fail("não criou request de foto: ${it.message}") }
            repeat(STILL_COUNT) { index ->
                handler.postDelayed({
                    if (!finished) runCatching { configured.capture(request, null, handler) }
                        .onFailure { captureFailure = it.message ?: it.javaClass.simpleName }
                }, index * STILL_INTERVAL_MS)
            }
            handler.postDelayed(::evaluate, MEASUREMENT_MS)
        }

        private fun evaluate() {
            if (finished) return
            val now = SystemClock.elapsedRealtime()
            if (lastVideoAt > 0L) maximumGapMs = maxOf(maximumGapMs, now - lastVideoAt)
            val elapsed = (now - measurementStartedAt).coerceAtLeast(1L)
            val fps = videoFrames * 1_000f / elapsed
            val actual = decodedSize
            val fullSize = actual != null && actual.width.toLong() * actual.height >= stillSize.width.toLong() * stillSize.height * .9
            val approved = configured && stillCount == STILL_COUNT && fullSize && fps >= MINIMUM_VIDEO_FPS && maximumGapMs <= MAXIMUM_GAP_MS
            complete(
                AttemptResult(
                    stillSize, approved, configured, stillCount, actual, fps, maximumGapMs,
                    buildString {
                        append(if (approved) "vídeo e fotos coexistiram" else "critério não atingido")
                        if (!fullSize && actual != null) append("; JPEG veio menor que o pedido")
                        captureFailure?.let { append("; capture falhou: $it") }
                    },
                ),
            )
        }

        private fun fail(reason: String) = complete(
            AttemptResult(stillSize, false, configured, stillCount, decodedSize, 0f, maximumGapMs, reason),
        )

        private fun complete(result: AttemptResult) {
            if (finished) return
            finished = true
            cleanUp()
            onDone(result)
        }

        private fun cleanUp() {
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { stillReader?.close() }
            runCatching { videoSurface?.release() }
            runCatching { stream?.release() }
            val core = egl
            val surface = anchor
            if (core != null && surface != null) runCatching {
                core.makeCurrent(surface)
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                core.releaseSurface(surface)
            }
            runCatching { core?.release() }
            session = null; device = null; stillReader = null
            videoSurface = null; stream = null; anchor = null; egl = null
        }
    }

    private companion object {
        const val MAXIMUM_ATTEMPTS = 4
        const val STILL_COUNT = 3
        const val WARMUP_MS = 2_000L
        const val STILL_INTERVAL_MS = 1_000L
        const val MEASUREMENT_MS = 4_500L
        const val ATTEMPT_TIMEOUT_MS = 9_000L
        const val BETWEEN_ATTEMPTS_MS = 600L
        const val MINIMUM_VIDEO_FPS = 8f
        const val MAXIMUM_GAP_MS = 1_000L
    }
}
