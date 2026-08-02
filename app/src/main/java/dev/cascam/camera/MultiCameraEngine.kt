package dev.cascam.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Camera2 direto para um conjunto arbitrário de fontes. Fontes físicas da mesma lógica dividem
 * uma sessão; câmeras lógicas anunciadas como concorrentes são abertas ao mesmo tempo. Uma fonte
 * é criada apenas uma vez, ainda que quadra, placar e cronômetro a reutilizem.
 */
class MultiCameraEngine(
    private val manager: CameraManager,
    private val onFrame: (cameraId: String, Bitmap) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    data class Source(val id: String, val logicalId: String, val physicalId: String?)
    data class Plan(val sources: List<Source>, val size: Size, val fps: Int)

    private val thread = HandlerThread("cascam-multi-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val devices = mutableMapOf<String, CameraDevice>()
    private val sessions = mutableMapOf<String, CameraCaptureSession>()
    private val readers = mutableMapOf<String, ImageReader>()
    private var surfaces = emptyMap<String, Surface>()
    private var plan: Plan? = null
    private var rotations = emptyMap<String, Int>()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(plan: Plan, rotations: Map<String, Int>, gpuSurfaces: Map<String, Surface>? = null) {
        stop()
        this.plan = plan
        this.rotations = rotations
        running = true
        handler.post {
            surfaces = gpuSurfaces ?: plan.sources.associate { source ->
                val reader = ImageReader.newInstance(plan.size.width, plan.size.height, ImageFormat.YUV_420_888, 3)
                readers[source.id] = reader
                reader.setOnImageAvailableListener({ available ->
                    val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (running) runCatching {
                            onFrame(source.id, YuvFrameConverter.convert(image, plan.size.width, rotations[source.id] ?: 0))
                        }.onFailure { onStatus("Falha ao converter ${source.id}: ${it.message}") }
                    } finally { image.close() }
                }, handler)
                source.id to reader.surface
            }
            plan.sources.groupBy { it.logicalId }.keys.forEach { logical ->
                runCatching { manager.openCamera(logical, callback(logical), handler) }
                    .onFailure { fail("não consegui abrir $logical: ${it.message}") }
            }
        }
    }

    fun stop() {
        running = false
        handler.post {
            sessions.values.forEach { runCatching { it.stopRepeating() }; it.close() }
            devices.values.forEach { it.close() }
            readers.values.forEach { it.close() }
            sessions.clear(); devices.clear(); readers.clear(); surfaces = emptyMap()
        }
    }

    fun release() { stop(); thread.quitSafely() }

    private fun callback(logicalId: String) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) { devices[logicalId] = camera; configure(camera) }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); fail("câmera $logicalId desconectada") }
        override fun onError(camera: CameraDevice, error: Int) { camera.close(); fail("câmera $logicalId falhou ($error)") }
    }

    private fun configure(camera: CameraDevice) {
        val plan = plan ?: return
        val sources = plan.sources.filter { it.logicalId == camera.id }
        val outputs = sources.map { source ->
            OutputConfiguration(surfaces.getValue(source.id)).apply {
                source.physicalId?.let { physicalId -> setPhysicalCameraId(physicalId) }
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                sessions[camera.id] = session
                runCatching {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        sources.forEach { addTarget(surfaces.getValue(it.id)) }
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        fpsRange(camera.id, plan.fps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    session.setRepeatingRequest(request, null, handler)
                    if (sessions.size == plan.sources.map { it.logicalId }.distinct().size) {
                        onStatus("${plan.sources.size} fonte(s) Camera2 ativas em ${plan.size.width}×${plan.size.height}")
                    }
                }.onFailure { fail("não consegui iniciar ${camera.id}: ${it.message}") }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) = fail("sessão ${camera.id} recusada")
        }
        runCatching { camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)) }
            .onFailure { fail("não consegui configurar ${camera.id}: ${it.message}") }
    }

    private fun fpsRange(logicalId: String, fps: Int): Range<Int>? {
        if (fps <= 0) return null
        return manager.getCameraCharacteristics(logicalId)
            .get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == fps }
            ?.minByOrNull { it.upper - it.lower }
    }

    private fun fail(message: String) { running = false; onStatus("Captura múltipla indisponível: $message") }
}
