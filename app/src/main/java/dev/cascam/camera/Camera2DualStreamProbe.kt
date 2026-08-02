package dev.cascam.camera

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.util.concurrent.Executor

/**
 * Testa dois sensores físicos pela via que o Android realmente garante.
 *
 * O CameraX não expõe stream por sensor físico de forma confiável: o pedido de
 * `setPhysicalCameraId` num ImageAnalysis pode ser silenciosamente ignorado, e aí os dois
 * use cases recebem o mesmo stream da câmera lógica — dois fluxos idênticos que passam por
 * "funcionou". A garantia documentada do framework é outra: abrir a câmera **lógica**, criar
 * **uma** sessão e trocar um stream YUV dela por dois streams de mesmo formato e mesmo tamanho,
 * cada um amarrado a um sensor físico via [OutputConfiguration.setPhysicalCameraId]. Mesmo
 * tamanho nos dois não é detalhe: é condição da garantia.
 *
 * Os callbacks saem na thread interna do probe; quem chama é responsável por voltar à main.
 */
class Camera2DualStreamProbe(private val manager: CameraManager) {
    data class Result(
        val approved: Boolean,
        val detail: String,
        val distance: Int? = null,
        val comparisons: Int = 0,
        val physicalZoom: PhysicalZoomResult? = null,
    )

    data class PhysicalZoomResult(
        val supported: Boolean,
        val applied: Boolean,
        val factor: Float? = null,
        val method: PerPhysicalZoom = PerPhysicalZoom.NONE,
        val detail: String,
    )

    private val thread = HandlerThread("cascam-camera2-probe").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command -> handler.post(command) }

    fun close() {
        thread.quitSafely()
    }

    /** Tamanhos que os dois sensores aceitam em YUV, do maior para o menor, dentro do teto pedido. */
    fun commonSizes(physicalA: String, physicalB: String, ceilings: List<Size>): List<Size> {
        val available = { id: String ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.toSet().orEmpty()
        }
        val shared = available(physicalA) intersect available(physicalB)
        return ceilings.mapNotNull { ceiling ->
            shared.filter { it.width <= ceiling.width && it.height <= ceiling.height }
                .maxByOrNull { it.width.toLong() * it.height }
        }.distinct()
    }

    fun run(
        logicalId: String,
        physicalA: String,
        physicalB: String,
        size: Size,
        observationMs: Long,
        onDone: (Result) -> Unit,
    ) {
        Attempt(logicalId, physicalA, physicalB, size, observationMs, onDone).start()
    }

    private inner class Attempt(
        private val logicalId: String,
        private val physicalA: String,
        private val physicalB: String,
        private val size: Size,
        private val observationMs: Long,
        private val onDone: (Result) -> Unit,
    ) {
        private val readerA = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        private val readerB = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        private val collectorA = Collector()
        private val collectorB = Collector()
        private val distances = mutableListOf<Int>()
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var startedAt = 0L
        private var settled = false
        private var finished = false
        private var baselineResult: Result? = null
        private var baselineA: LumaSignature? = null
        private var baselineB: LumaSignature? = null

        @SuppressLint("MissingPermission")
        fun start() {
            readerA.setOnImageAvailableListener({ reader -> consume(reader, collectorA, compare = false) }, handler)
            readerB.setOnImageAvailableListener({ reader -> consume(reader, collectorB, compare = true) }, handler)
            handler.postDelayed({ finish(Result(false, "o aparelho não respondeu ao pedido de abertura em ${OPEN_TIMEOUT_MS / 1000}s")) }, OPEN_TIMEOUT_MS)
            try {
                manager.openCamera(logicalId, deviceCallback, handler)
            } catch (error: CameraAccessException) {
                finish(Result(false, "não consegui abrir a câmera $logicalId: ${error.message}"))
            } catch (error: SecurityException) {
                finish(Result(false, "permissão de câmera negada"))
            }
        }

        private val deviceCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                configure(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                finish(Result(false, "a câmera foi tomada por outro app durante o teste"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                finish(Result(false, "erro ao abrir a câmera lógica: ${deviceError(error)}"))
            }
        }

        private fun configure(camera: CameraDevice) {
            val outputs = listOf(
                OutputConfiguration(readerA.surface).apply { setPhysicalCameraId(physicalA) },
                OutputConfiguration(readerB.surface).apply { setPhysicalCameraId(physicalB) },
            )
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    request(camera, configured)
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    finish(Result(false, "o HAL recusou a sessão com os dois sensores em ${size.width}x${size.height}"))
                }
            }
            try {
                camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback))
            } catch (error: CameraAccessException) {
                finish(Result(false, "createCaptureSession falhou: ${error.message}"))
            } catch (error: IllegalArgumentException) {
                finish(Result(false, "combinação de streams rejeitada: ${error.message}"))
            }
        }

        private fun request(camera: CameraDevice, configured: CameraCaptureSession) {
            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(readerA.surface)
                    addTarget(readerB.surface)
                    // Mesmo ajuste da captura: medir com estabilização ligada mediria outro campo.
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }.build()
                configured.setRepeatingRequest(request, null, handler)
                startedAt = System.currentTimeMillis()
                handler.postDelayed({ settled = true }, WARMUP_MS)
                handler.postDelayed({ evaluateStreams(camera, configured) }, WARMUP_MS + observationMs)
            } catch (error: CameraAccessException) {
                finish(Result(false, "setRepeatingRequest falhou: ${error.message}"))
            } catch (error: IllegalStateException) {
                finish(Result(false, "sessão encerrada antes de capturar: ${error.message}"))
            }
        }

        private fun consume(reader: ImageReader, collector: Collector, compare: Boolean) {
            val image: Image = reader.acquireLatestImage() ?: return
            try {
                // O aquecimento é descartado de propósito: os primeiros quadros saem pretos enquanto
                // a exposição converge, e quadro preto não distingue câmera nenhuma.
                if (!settled) return
                val plane = image.planes[0]
                val signature = LumaSignature.of(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
                collector.record(signature, image.width, image.height)
                if (compare) {
                    val other = collectorA.last
                    if (other != null && other.hasContrast && signature.hasContrast) {
                        distances += other.distanceTo(signature)
                    }
                }
            } finally {
                image.close()
            }
        }

        private fun evaluateStreams(camera: CameraDevice, configured: CameraCaptureSession) {
            val detail = "${collectorA.describe(physicalA, startedAt)}; ${collectorB.describe(physicalB, startedAt)}"
            val result = when {
                collectorA.frames == 0 || collectorB.frames == 0 ->
                    Result(false, "$detail — um dos sensores não entregou quadro nenhum")
                distances.isEmpty() ->
                    Result(false, "$detail — só chegaram quadros sem contraste; aponte as câmeras para cenas iluminadas e diferentes e repita", comparisons = 0)
                else -> {
                    val median = distances.sorted()[distances.size / 2]
                    val approved = median > DISTINCT_DISTANCE
                    val verdict = if (approved) "imagens distintas" else "mesma imagem nos dois fluxos"
                    Result(approved, "$detail — $verdict (distância mediana $median em ${distances.size} comparações)", median, distances.size)
                }
            }
            if (!result.approved) {
                finish(result)
                return
            }
            baselineResult = result
            baselineA = collectorA.last
            baselineB = collectorB.last
            runCatching { startPhysicalZoomTest(camera, configured) }
                .onFailure {
                    finishWithZoom(PhysicalZoomResult(true, false, detail = "falha ao preparar teste físico: ${it.message}"))
                }
        }

        /**
         * Mantém A (a lente mais aberta/quadra) sem alteração e pede o zoom máximo somente em B
         * (a lente mais fechada, candidata a placar/cronômetro). Além de a requisição ser aceita,
         * confirma que os dois streams continuam entregando quadros após a mudança.
         */
        private fun startPhysicalZoomTest(camera: CameraDevice, configured: CameraCaptureSession) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                finishWithZoom(PhysicalZoomResult(false, false, detail = "Android anterior à API 28"))
                return
            }
            val logical = manager.getCameraCharacteristics(logicalId)
            val keys = runCatching { logical.availablePhysicalCameraRequestKeys }.getOrNull().orEmpty()
            val physical = manager.getCameraCharacteristics(physicalB)
            // A sobrecarga simples cria o request sem blocos de configuração física. Nesse caso
            // setPhysicalCameraKey() rejeita até IDs presentes em physicalCameraIds com
            // "Physical camera id is not valid". Os IDs precisam entrar já na criação do builder.
            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW,
                setOf(physicalA, physicalB),
            ).apply {
                addTarget(readerA.surface)
                addTarget(readerB.surface)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            val zoom = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && keys.contains(CaptureRequest.CONTROL_ZOOM_RATIO) -> {
                    val range = physical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        ?: logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    val factor = range?.upper
                    if (factor == null || factor <= 1f) null else {
                        builder.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, factor, physicalB)
                        PhysicalZoomResult(true, false, factor, PerPhysicalZoom.ZOOM_RATIO, "requisição preparada")
                    }
                }
                keys.contains(CaptureRequest.SCALER_CROP_REGION) -> {
                    val active = physical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val factor = physical.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        ?: logical.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    if (active == null || factor == null || factor <= 1f) null else {
                        builder.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, centeredCrop(active, factor), physicalB)
                        PhysicalZoomResult(true, false, factor, PerPhysicalZoom.CROP_REGION, "requisição preparada")
                    }
                }
                else -> null
            }
            if (zoom == null) {
                finishWithZoom(PhysicalZoomResult(false, false, detail = "o HAL não declarou zoom/crop por sensor físico"))
                return
            }
            collectorA.reset(); collectorB.reset(); distances.clear()
            settled = false
            runCatching { configured.setRepeatingRequest(builder.build(), null, handler) }
                .onFailure {
                    finishWithZoom(zoom.copy(detail = "o HAL recusou ${zoom.method.label}: ${it.message}"))
                    return
                }
            startedAt = System.currentTimeMillis()
            handler.postDelayed({ settled = true }, ZOOM_WARMUP_MS)
            handler.postDelayed({ evaluatePhysicalZoom(zoom) }, ZOOM_WARMUP_MS + ZOOM_OBSERVATION_MS)
        }

        private fun evaluatePhysicalZoom(requested: PhysicalZoomResult) {
            val a = collectorA.last
            val b = collectorB.last
            if (collectorA.frames == 0 || collectorB.frames == 0 || a == null || b == null) {
                finishWithZoom(requested.copy(detail = "requisição aceita, mas um stream parou de entregar quadros"))
                return
            }
            val courtChange = baselineA?.distanceTo(a)
            val auxiliaryChange = baselineB?.distanceTo(b)
            val visuallyConfirmed = courtChange != null && auxiliaryChange != null &&
                auxiliaryChange >= ZOOM_CHANGE_DISTANCE && auxiliaryChange > courtChange
            val evidence = if (courtChange != null && auxiliaryChange != null) {
                "quadra mudou $courtChange; auxiliar mudou $auxiliaryChange (a cena deve ficar parada para esta comparação)"
            } else "os dois streams continuaram ativos"
            val verdict = if (visuallyConfirmed) "aceito e mudança visual confirmada" else "aceito, mas mudança visual não confirmada"
            finishWithZoom(requested.copy(applied = visuallyConfirmed, detail = "$verdict; $evidence"))
        }

        private fun finishWithZoom(zoom: PhysicalZoomResult) {
            val base = baselineResult ?: Result(false, "teste-base ausente")
            finish(base.copy(physicalZoom = zoom))
        }

        private fun finish(result: Result) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { readerA.close() }
            runCatching { readerB.close() }
            session = null
            device = null
            onDone(result)
        }
    }

    private class Collector {
        var frames = 0
        var last: LumaSignature? = null
        private var width = 0
        private var height = 0

        fun record(signature: LumaSignature, imageWidth: Int, imageHeight: Int) {
            last = signature
            width = imageWidth
            height = imageHeight
            frames++
        }

        fun reset() { frames = 0; last = null; width = 0; height = 0 }

        fun describe(physicalId: String, startedAt: Long): String {
            if (frames == 0) return "$physicalId sem quadros"
            val seconds = (System.currentTimeMillis() - startedAt).coerceAtLeast(1) / 1_000f
            return "$physicalId ${width}x$height @ %.0f fps".format(frames / seconds)
        }
    }

    companion object {
        // Inclui abertura, teste-base e a segunda fase com zoom físico.
        private const val OPEN_TIMEOUT_MS = 12_000L
        private const val WARMUP_MS = 1_200L
        private const val ZOOM_WARMUP_MS = 800L
        private const val ZOOM_OBSERVATION_MS = 1_200L
        private const val DISTINCT_DISTANCE = 6
        private const val ZOOM_CHANGE_DISTANCE = 6

        private fun centeredCrop(active: Rect, zoom: Float): Rect {
            val width = (active.width() / zoom).toInt().coerceAtLeast(2) and -2
            val height = (active.height() / zoom).toInt().coerceAtLeast(2) and -2
            val left = active.left + (active.width() - width) / 2
            val top = active.top + (active.height() - height) / 2
            return Rect(left, top, left + width, top + height)
        }

        private fun deviceError(code: Int) = when (code) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "câmera já em uso"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "limite de câmeras abertas atingido"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "câmera desativada por política do aparelho"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "falha fatal do dispositivo"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "falha do serviço de câmera"
            else -> "código $code"
        }
    }
}
