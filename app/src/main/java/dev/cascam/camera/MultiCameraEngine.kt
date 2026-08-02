package dev.cascam.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
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
    )
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

    @SuppressLint("MissingPermission")
    fun start(plan: Plan, rotations: Map<String, Int>, gpuSurfaces: Map<String, Surface>? = null) {
        stop()
        this.plan = plan
        this.rotations = rotations
        running = true
        handler.post {
            surfaces = gpuSurfaces ?: plan.sources.associate { source ->
                val reader = ImageReader.newInstance(source.size.width, source.size.height, ImageFormat.YUV_420_888, 3)
                readers[source.id] = reader
                reader.setOnImageAvailableListener({ available ->
                    val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (running) runCatching {
                            onFrame(source.id, YuvFrameConverter.convert(image, source.size.width, rotations[source.id] ?: 0))
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
                    val physicalIds = sources.mapNotNull { it.physicalId }.toSet()
                    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalIds.isNotEmpty()) {
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, physicalIds)
                    } else camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    val request = builder.apply {
                        sources.forEach { addTarget(surfaces.getValue(it.id)) }
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        val requestedFps = sources.maxOfOrNull { it.fps } ?: 0
                        fpsRange(camera.id, requestedFps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        applyZooms(camera.id, sources, this)
                    }.build()
                    session.setRepeatingRequest(request, null, handler)
                    if (sessions.size == plan.sources.map { it.logicalId }.distinct().size) {
                        val profiles = plan.sources.joinToString { "${it.id}=${it.size.width}×${it.size.height}@${it.fps.takeIf { fps -> fps > 0 } ?: "auto"} · %.1f×".format(it.zoom) }
                        onStatus("${plan.sources.size} fonte(s) Camera2 ativas · $profiles")
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

    private fun applyZooms(logicalId: String, sources: List<Source>, builder: CaptureRequest.Builder) {
        val logical = manager.getCameraCharacteristics(logicalId)
        val physicalKeys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { logical.availablePhysicalCameraRequestKeys }.getOrNull().orEmpty()
        } else emptyList()
        sources.filter { it.zoom > 1.001f }.forEach { source ->
            val physicalId = source.physicalId
            if (physicalId == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val range = logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, source.zoom.coerceIn(range?.lower ?: 1f, range?.upper ?: source.zoom))
                }
                return@forEach
            }
            val physical = manager.getCameraCharacteristics(physicalId)
            when {
                // O S22 anuncia CONTROL_ZOOM_RATIO por sensor e aceita o request, mas ignora a
                // mudança em alguns sensores físicos durante multistream. SCALER_CROP_REGION é
                // igualmente anunciado e descreve diretamente a área do sensor que deve gerar o
                // buffer 1080p, portanto é a via determinística para a composição.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalKeys.contains(CaptureRequest.SCALER_CROP_REGION) -> {
                    val active = physical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@forEach
                    val maximum = physical.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: source.zoom
                    builder.setPhysicalCameraKey(
                        CaptureRequest.SCALER_CROP_REGION,
                        centeredCrop(active, source.zoom.coerceAtMost(maximum)),
                        physicalId,
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && physicalKeys.contains(CaptureRequest.CONTROL_ZOOM_RATIO) -> {
                    val range = physical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        ?: logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    val value = source.zoom.coerceIn(range?.lower ?: 1f, range?.upper ?: source.zoom)
                    builder.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, value, physicalId)
                }
                else -> onStatus("Zoom independente indisponível para ${source.id}; mantendo 1,0×")
            }
        }
    }

    private fun centeredCrop(active: Rect, zoom: Float): Rect {
        val width = ((active.width() / zoom).toInt().coerceAtLeast(2)) and -2
        val height = ((active.height() / zoom).toInt().coerceAtLeast(2)) and -2
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun fail(message: String) { running = false; onStatus("Captura múltipla indisponível: $message") }
}
