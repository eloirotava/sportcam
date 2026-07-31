package dev.cascam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import kotlin.math.atan
import kotlin.math.max

enum class LensFacing(val label: String) {
    BACK("traseira"), FRONT("frontal"), EXTERNAL("externa"), UNKNOWN("desconhecida"),
}

/** Como pedir zoom de um sensor físico sem afetar o outro da mesma câmera lógica. */
enum class PerPhysicalZoom(val label: String) {
    ZOOM_RATIO("por CONTROL_ZOOM_RATIO"),
    CROP_REGION("por SCALER_CROP_REGION"),
    NONE("não suportado"),
}

data class CameraInfo(
    val id: String,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val minimumFocalLength: Float?,
    val lensFacing: LensFacing,
    /** Ângulo horizontal aproximado em graus; quanto maior, mais quadra cabe no quadro. */
    val horizontalFieldOfView: Float? = null,
    val maximumYuvSize: Size? = null,
    val hardwareLevel: String = "?",
    val logicalMultiCamera: Boolean = false,
    /**
     * Se o HAL aceita ajustar zoom por sensor físico dentro de uma sessão só. Sem isso o zoom
     * vale para a câmera lógica inteira e mexer no placar mexeria também na quadra.
     */
    val perPhysicalZoom: PerPhysicalZoom = PerPhysicalZoom.NONE,
    /** Chaves que o request aceita sobrescrever por sensor, sem o prefixo `android.`. */
    val physicalRequestKeys: List<String> = emptyList(),
    val zoomRatioRange: String? = null,
) {
    /**
     * Exposição por sensor. Placar de LED estoura quando a medição é feita pela quadra escura, e
     * placar de parede clara faz o contrário; sem esta chave os dois dividem a mesma exposição.
     */
    val independentExposure: Boolean get() =
        (physicalRequestKeys.contains("sensor.exposureTime") && physicalRequestKeys.contains("sensor.sensitivity")) ||
            physicalRequestKeys.contains("control.aeExposureCompensation") ||
            physicalRequestKeys.contains("control.aeRegions")

    val independentFocus: Boolean get() = physicalRequestKeys.any {
        it == "lens.focusDistance" || it == "control.afMode" || it == "control.afRegions"
    }

    /** Quanto dá para recortar no sensor antes de começar a esticar pixel, saindo em 1080p. */
    val losslessCropAt1080p: Float? get() = maximumYuvSize?.let { it.width / 1920f }

    val isPhysical: Boolean get() = physicalCameraId != null

    val lensLabel: String get() = when {
        horizontalFieldOfView == null -> "abertura ?"
        horizontalFieldOfView >= 90f -> "ultra-wide ${horizontalFieldOfView.toInt()}°"
        horizontalFieldOfView >= 60f -> "wide ${horizontalFieldOfView.toInt()}°"
        else -> "tele ${horizontalFieldOfView.toInt()}°"
    }

    val describe: String get() = buildString {
        append(id)
        append(" · ").append(lensFacing.label)
        append(" · ").append(lensLabel)
        minimumFocalLength?.let { append(" · ").append("%.1f mm".format(it)) }
        maximumYuvSize?.let { append(" · YUV máx ").append("${it.width}x${it.height}") }
        if (isPhysical) losslessCropAt1080p?.takeIf { it > 1.05f }?.let {
            append(" · crop limpo até ").append("%.1f×".format(it)).append(" em 1080p")
        }
        zoomRatioRange?.let { append(" · zoom ").append(it) }
    }
}

data class CameraCapabilities(
    val cameras: List<CameraInfo>,
    val concurrentPairs: Set<Set<String>>,
) {
    val likelyUltraWide: CameraInfo? = cameras
        .filter { it.lensFacing == LensFacing.BACK }
        .minByOrNull { it.minimumFocalLength ?: Float.MAX_VALUE }
    val supportsConcurrentCameras: Boolean = concurrentPairs.isNotEmpty()

    fun camera(id: String): CameraInfo? = cameras.firstOrNull { it.id == id }

    /** Sensores físicos agrupados pela câmera lógica que os expõe. */
    fun physicalSensorsByLogical(): Map<String, List<CameraInfo>> = cameras
        .filter { it.isPhysical }
        .groupBy { it.logicalCameraId }
}

object CameraCapabilitiesReader {
    fun read(context: Context): CameraCapabilities {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameras = manager.cameraIdList.flatMap { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
                else -> LensFacing.UNKNOWN
            }
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            val multiCamera = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val level = hardwareLevel(characteristics)
            val physicalKeys = physicalRequestKeys(characteristics)
            val logical = CameraInfo(
                id, id, null,
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull(),
                facing, horizontalFieldOfView(characteristics), maximumYuvSize(characteristics), level, multiCamera,
                perPhysicalZoom(physicalKeys), physicalKeys, zoomRatioRange(characteristics),
            )
            val physical = characteristics.physicalCameraIds.map { physicalId ->
                val physicalCharacteristics = manager.getCameraCharacteristics(physicalId)
                CameraInfo(
                    "$id/$physicalId", id, physicalId,
                    physicalCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull(),
                    facing, horizontalFieldOfView(physicalCharacteristics), maximumYuvSize(physicalCharacteristics),
                    hardwareLevel(physicalCharacteristics), false,
                    PerPhysicalZoom.NONE, emptyList(), zoomRatioRange(physicalCharacteristics),
                )
            }
            listOf(logical) + physical
        }
        val cameraIds = manager.cameraIdList.toSet()
        val pairs = manager.concurrentCameraIds
            .map { it.intersect(cameraIds) }
            .filter { it.size >= 2 }
            .toSet()
        return CameraCapabilities(cameras, pairs)
    }

    /**
     * Chaves que o HAL aceita sobrescrever por sensor físico dentro do mesmo request. É o que
     * decide se dá para dar zoom só no placar sem arrastar o enquadramento da quadra junto.
     */
    private fun physicalRequestKeys(characteristics: CameraCharacteristics): List<String> =
        runCatching { characteristics.availablePhysicalCameraRequestKeys }.getOrNull().orEmpty()
            .map { it.name.removePrefix("android.") }
            .sorted()

    private fun perPhysicalZoom(keys: List<String>): PerPhysicalZoom = when {
        keys.contains("control.zoomRatio") -> PerPhysicalZoom.ZOOM_RATIO
        keys.contains("scaler.cropRegion") -> PerPhysicalZoom.CROP_REGION
        else -> PerPhysicalZoom.NONE
    }

    private fun zoomRatioRange(characteristics: CameraCharacteristics): String? = characteristics
        .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        ?.let { "%.1f×–%.1f×".format(it.lower, it.upper) }

    private fun hardwareLevel(characteristics: CameraCharacteristics) =
        when (characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }

    private fun maximumYuvSize(characteristics: CameraCharacteristics): Size? = characteristics
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(ImageFormat.YUV_420_888)
        ?.maxByOrNull { it.width.toLong() * it.height }

    private fun horizontalFieldOfView(characteristics: CameraCharacteristics): Float? {
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (focal <= 0f) return null
        val longestEdge = max(sensor.width, sensor.height)
        return Math.toDegrees(2.0 * atan((longestEdge / (2f * focal)).toDouble())).toFloat()
    }
}
