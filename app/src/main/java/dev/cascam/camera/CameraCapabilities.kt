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
) {
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
            val logical = CameraInfo(
                id, id, null,
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull(),
                facing, horizontalFieldOfView(characteristics), maximumYuvSize(characteristics), level, multiCamera,
            )
            val physical = characteristics.physicalCameraIds.map { physicalId ->
                val physicalCharacteristics = manager.getCameraCharacteristics(physicalId)
                CameraInfo(
                    "$id/$physicalId", id, physicalId,
                    physicalCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull(),
                    facing, horizontalFieldOfView(physicalCharacteristics), maximumYuvSize(physicalCharacteristics),
                    hardwareLevel(physicalCharacteristics), false,
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
