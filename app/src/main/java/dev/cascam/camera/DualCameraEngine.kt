package dev.cascam.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Captura simultânea de dois sensores físicos da mesma câmera lógica.
 *
 * É a via garantida pelo framework, e a única que o teste aprovou neste aparelho: uma sessão só,
 * dois streams YUV de mesmo tamanho, cada um preso a um sensor por
 * [OutputConfiguration.setPhysicalCameraId]. O CameraX não serve aqui porque pode ignorar o pedido
 * de sensor físico e entregar o mesmo stream da lógica nos dois destinos.
 *
 * O zoom do placar é aplicado só no sensor do placar, via chave por câmera física no request. Sem
 * esse suporte no HAL o zoom valeria para a lógica inteira e arrastaria o enquadramento da quadra.
 */
class DualCameraEngine(
    private val manager: CameraManager,
    private val onFrame: (Role, Bitmap) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    enum class Role { COURT, SCOREBOARD }

    data class Plan(
        val logicalId: String,
        val courtPhysicalId: String,
        val scoreboardPhysicalId: String,
        val size: Size,
        val perPhysicalZoom: PerPhysicalZoom,
    )

    private val thread = HandlerThread("cascam-dual-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command -> handler.post(command) }

    private var plan: Plan? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var courtReader: ImageReader? = null
    private var scoreboardReader: ImageReader? = null
    private var rotationDegrees = 0
    private var scoreboardZoom = 1f
    private var courtTargetWidth = COURT_TARGET_WIDTH
    private var scoreboardTargetWidth = SCOREBOARD_TARGET_WIDTH

    @Volatile private var running = false
    private val frameCounts = mutableMapOf<Role, Int>()
    private var lastReportAt = 0L

    val isRunning: Boolean get() = running

    /**
     * Monta o plano de captura para o par escolhido, ou devolve null quando os dois não são
     * sensores físicos da mesma câmera lógica — aí quem chama segue pelo caminho antigo.
     */
    fun planFor(capabilities: CameraCapabilities, court: CameraInfo, scoreboard: CameraInfo, ceiling: Size): Plan? {
        val courtPhysical = court.physicalCameraId ?: return null
        val scoreboardPhysical = scoreboard.physicalCameraId ?: return null
        if (court.logicalCameraId != scoreboard.logicalCameraId) return null
        if (courtPhysical == scoreboardPhysical) return null
        val size = commonSize(courtPhysical, scoreboardPhysical, ceiling) ?: return null
        val logical = capabilities.camera(court.logicalCameraId)
        return Plan(court.logicalCameraId, courtPhysical, scoreboardPhysical, size, logical?.perPhysicalZoom ?: PerPhysicalZoom.NONE)
    }

    @SuppressLint("MissingPermission")
    fun start(plan: Plan, displayRotationDegrees: Int, scoreboardZoomRatio: Float) {
        stop()
        this.plan = plan
        this.scoreboardZoom = scoreboardZoomRatio
        rotationDegrees = rotationFor(plan.logicalId, displayRotationDegrees)
        running = true
        frameCounts.clear()
        handler.post {
            val court = ImageReader.newInstance(plan.size.width, plan.size.height, ImageFormat.YUV_420_888, 3)
            val scoreboard = ImageReader.newInstance(plan.size.width, plan.size.height, ImageFormat.YUV_420_888, 3)
            courtReader = court
            scoreboardReader = scoreboard
            court.setOnImageAvailableListener({ reader -> deliver(reader, Role.COURT, courtTargetWidth) }, handler)
            scoreboard.setOnImageAvailableListener({ reader -> deliver(reader, Role.SCOREBOARD, scoreboardTargetWidth) }, handler)
            try {
                manager.openCamera(plan.logicalId, deviceCallback, handler)
            } catch (error: CameraAccessException) {
                fail("não consegui abrir a câmera ${plan.logicalId}: ${error.message}")
            } catch (error: SecurityException) {
                fail("permissão de câmera negada")
            }
        }
    }

    fun setScoreboardZoom(ratio: Float) {
        scoreboardZoom = ratio
        handler.post { applyRequest() }
    }

    fun stop() {
        running = false
        handler.post {
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { courtReader?.close() }
            runCatching { scoreboardReader?.close() }
            session = null
            device = null
            courtReader = null
            scoreboardReader = null
        }
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- sessão

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            configure(camera)
        }

        override fun onDisconnected(camera: CameraDevice) = fail("a câmera foi tomada por outro app")

        override fun onError(camera: CameraDevice, error: Int) = fail("erro ao abrir a câmera lógica (código $error)")
    }

    private fun configure(camera: CameraDevice) {
        val plan = plan ?: return
        val court = courtReader ?: return
        val scoreboard = scoreboardReader ?: return
        val outputs = listOf(
            OutputConfiguration(court.surface).apply { setPhysicalCameraId(plan.courtPhysicalId) },
            OutputConfiguration(scoreboard.surface).apply { setPhysicalCameraId(plan.scoreboardPhysicalId) },
        )
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                applyRequest()
                onStatus("Dois sensores ativos: quadra ${plan.courtPhysicalId}, placar ${plan.scoreboardPhysicalId} a ${plan.size.width}x${plan.size.height}.")
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) =
                fail("o HAL recusou a sessão com os dois sensores em ${plan.size.width}x${plan.size.height}")
        }
        try {
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback))
        } catch (error: CameraAccessException) {
            fail("createCaptureSession falhou: ${error.message}")
        } catch (error: IllegalArgumentException) {
            fail("combinação de streams rejeitada: ${error.message}")
        }
    }

    private fun applyRequest() {
        val plan = plan ?: return
        val camera = device ?: return
        val configured = session ?: return
        val court = courtReader ?: return
        val scoreboard = scoreboardReader ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(court.surface)
                addTarget(scoreboard.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
            applyScoreboardZoom(builder, plan)
            configured.setRepeatingRequest(builder.build(), null, handler)
        } catch (error: CameraAccessException) {
            fail("setRepeatingRequest falhou: ${error.message}")
        } catch (error: IllegalStateException) {
            fail("sessão encerrada antes de capturar: ${error.message}")
        }
    }

    private fun applyScoreboardZoom(builder: CaptureRequest.Builder, plan: Plan) {
        if (scoreboardZoom <= 1.01f) return
        when (plan.perPhysicalZoom) {
            PerPhysicalZoom.ZOOM_RATIO ->
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, scoreboardZoom, plan.scoreboardPhysicalId)
            PerPhysicalZoom.CROP_REGION -> cropRegionFor(plan.scoreboardPhysicalId, scoreboardZoom)?.let {
                builder.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, it, plan.scoreboardPhysicalId)
            }
            // Sem chave por sensor, aplicar zoom aqui mexeria também na quadra: o recorte do placar
            // fica por conta dos quatro cantos da composição, que já cortam a imagem entregue.
            PerPhysicalZoom.NONE -> Unit
        }
    }

    private fun cropRegionFor(physicalId: String, zoom: Float): Rect? {
        val active = runCatching {
            manager.getCameraCharacteristics(physicalId).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        }.getOrNull() ?: return null
        val width = (active.width() / zoom).roundToInt().coerceAtLeast(64)
        val height = (active.height() / zoom).roundToInt().coerceAtLeast(64)
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    // ---------------------------------------------------------------- quadros

    private fun deliver(reader: ImageReader, role: Role, targetWidth: Int) {
        val image: Image = reader.acquireLatestImage() ?: return
        try {
            if (!running) return
            val bitmap = YuvFrameConverter.convert(image, targetWidth, rotationDegrees)
            frameCounts[role] = (frameCounts[role] ?: 0) + 1
            onFrame(role, bitmap)
            reportRate()
        } catch (error: RuntimeException) {
            onStatus("Falha ao converter quadro: ${error.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    /** Taxa real na tela: com composição em CPU ela costuma ficar abaixo da taxa da câmera. */
    private fun reportRate() {
        val now = System.currentTimeMillis()
        if (lastReportAt == 0L) { lastReportAt = now; return }
        if (now - lastReportAt < RATE_REPORT_MS) return
        val seconds = (now - lastReportAt) / 1_000f
        val court = (frameCounts[Role.COURT] ?: 0) / seconds
        val scoreboard = (frameCounts[Role.SCOREBOARD] ?: 0) / seconds
        frameCounts.clear()
        lastReportAt = now
        onStatus("Quadra %.0f fps · placar %.0f fps".format(court, scoreboard))
    }

    private fun commonSize(physicalA: String, physicalB: String, ceiling: Size): Size? {
        val sizesOf = { id: String ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888)?.toSet().orEmpty()
            }.getOrNull().orEmpty()
        }
        return (sizesOf(physicalA) intersect sizesOf(physicalB))
            .filter { it.width <= ceiling.width && it.height <= ceiling.height }
            .maxByOrNull { it.width.toLong() * it.height }
    }

    private fun rotationFor(logicalId: String, displayRotationDegrees: Int): Int {
        val sensor = runCatching {
            manager.getCameraCharacteristics(logicalId).get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: 0
        return ((sensor - displayRotationDegrees) % 360 + 360) % 360
    }

    private fun fail(reason: String) {
        running = false
        onStatus("Captura dupla indisponível: $reason")
    }

    companion object {
        /** Tamanhos de conversão, não de captura: o sensor entrega 1080p e só o desenho encolhe. */
        const val COURT_TARGET_WIDTH = 1280
        const val SCOREBOARD_TARGET_WIDTH = 640
        private const val RATE_REPORT_MS = 2_000L
    }
}
