package dev.cascam.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
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
                }.build()
                configured.setRepeatingRequest(request, null, handler)
                startedAt = System.currentTimeMillis()
                handler.postDelayed({ settled = true }, WARMUP_MS)
                handler.postDelayed({ evaluate() }, WARMUP_MS + observationMs)
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

        private fun evaluate() {
            val detail = "${collectorA.describe(physicalA, startedAt)}; ${collectorB.describe(physicalB, startedAt)}"
            when {
                collectorA.frames == 0 || collectorB.frames == 0 ->
                    finish(Result(false, "$detail — um dos sensores não entregou quadro nenhum"))
                distances.isEmpty() ->
                    finish(Result(false, "$detail — só chegaram quadros sem contraste; aponte as câmeras para cenas iluminadas e diferentes e repita", comparisons = 0))
                else -> {
                    val median = distances.sorted()[distances.size / 2]
                    val approved = median > DISTINCT_DISTANCE
                    val verdict = if (approved) "imagens distintas" else "mesma imagem nos dois fluxos"
                    finish(Result(approved, "$detail — $verdict (distância mediana $median em ${distances.size} comparações)", median, distances.size))
                }
            }
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

        fun describe(physicalId: String, startedAt: Long): String {
            if (frames == 0) return "$physicalId sem quadros"
            val seconds = (System.currentTimeMillis() - startedAt).coerceAtLeast(1) / 1_000f
            return "$physicalId ${width}x$height @ %.0f fps".format(frames / seconds)
        }
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 8_000L
        private const val WARMUP_MS = 1_200L
        private const val DISTINCT_DISTANCE = 6

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
