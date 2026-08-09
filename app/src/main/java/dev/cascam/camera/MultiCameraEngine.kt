package dev.cascam.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.os.Build
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
    data class Source(
        val id: String,
        val logicalId: String,
        val physicalId: String?,
        val size: Size,
        val fps: Int,
        val zoom: Float = 1f,
        val stillIntervalMillis: Long = 0L,
    ) {
        val isStill: Boolean get() = stillIntervalMillis > 0L
    }
    data class Plan(val sources: List<Source>)

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
    @Volatile private var generation = 0

    @SuppressLint("MissingPermission")
    fun start(plan: Plan, rotations: Map<String, Int>, gpuSurfaces: Map<String, Surface>? = null) {
        stop()
        val activeGeneration = generation
        this.plan = plan
        this.rotations = rotations
        running = true
        handler.post {
            surfaces = plan.sources.associate { source ->
                val gpuSurface = gpuSurfaces?.get(source.id)
                if (!source.isStill && gpuSurface != null) return@associate source.id to gpuSurface
                val format = if (source.isStill) ImageFormat.JPEG else ImageFormat.YUV_420_888
                val reader = ImageReader.newInstance(source.size.width, source.size.height, format, if (source.isStill) 2 else 3)
                readers[source.id] = reader
                reader.setOnImageAvailableListener({ available ->
                    val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (running && generation == activeGeneration) runCatching {
                            val bitmap = if (source.isStill) {
                                val buffer = image.planes[0].buffer
                                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                                // JPEG é entregue na orientação correta pelo HAL. A correção
                                // manual da GPU pertence exclusivamente aos SurfaceTextures de vídeo.
                                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            } else YuvFrameConverter.convert(image, source.size.width, rotations[source.id] ?: 0)
                            onFrame(source.id, bitmap)
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
        generation++
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
                    val videoSources = sources.filterNot { it.isStill }
                    // O S22 aprovou exatamente esta forma: o repeating declara somente o sensor
                    // de vídeo; a tele entra apenas no request JPEG avulso.
                    val physicalIds = videoSources.mapNotNull { it.physicalId }.toSet()
                    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalIds.isNotEmpty()) {
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, physicalIds)
                    } else camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    if (videoSources.isNotEmpty()) builder.apply {
                        videoSources.forEach { addTarget(surfaces.getValue(it.id)) }
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        val requestedFps = videoSources.maxOfOrNull { it.fps } ?: 0
                        fpsRange(camera.id, requestedFps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        // O zoom é aplicado de forma determinística na composição. Alguns HALs
                        // Samsung aceitam a chave física sem realmente alterar o buffer.
                    }.build().also { session.setRepeatingRequest(it, null, handler) }
                    sources.filter { it.isStill }.forEach { scheduleStill(camera, session, it, generation, 250L) }
                    if (sessions.size == plan.sources.map { it.logicalId }.distinct().size) {
                        val profiles = plan.sources.joinToString { source ->
                            val cadence = if (source.isStill) "foto/${source.stillIntervalMillis}ms" else "${source.fps.takeIf { it > 0 } ?: "auto"} fps"
                            "${source.id}=${source.size.width}×${source.size.height}@$cadence · %.1f×".format(source.zoom)
                        }
                        onStatus("${plan.sources.size} fonte(s) Camera2 ativas · $profiles")
                    }
                }.onFailure { fail("não consegui iniciar ${camera.id}: ${it.message}") }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) = fail("sessão ${camera.id} recusada")
        }
        runCatching { camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)) }
            .onFailure { fail("não consegui configurar ${camera.id}: ${it.message}") }
    }

    private fun scheduleStill(
        camera: CameraDevice,
        session: CameraCaptureSession,
        source: Source,
        activeGeneration: Int,
        delayMillis: Long,
    ) {
        handler.postDelayed({
            if (!running || generation != activeGeneration || sessions[camera.id] !== session) return@postDelayed
            runCatching {
                val physicalIds = source.physicalId?.let(::setOf).orEmpty()
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalIds.isNotEmpty()) {
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, physicalIds)
                } else camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.apply {
                    addTarget(surfaces.getValue(source.id))
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult,
                    ) = scheduleStill(camera, session, source, activeGeneration, source.stillIntervalMillis)

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure,
                    ) {
                        onStatus("Foto do placar falhou (${failure.reason}); tentando novamente")
                        scheduleStill(camera, session, source, activeGeneration, source.stillIntervalMillis)
                    }
                }, handler)
            }.onFailure {
                onStatus("Foto do placar indisponível: ${it.message}")
                scheduleStill(camera, session, source, activeGeneration, source.stillIntervalMillis)
            }
        }, delayMillis)
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
