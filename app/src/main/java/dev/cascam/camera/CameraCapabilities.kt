package dev.cascam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

enum class LensFacing(val label: String) {
    BACK("traseira"), FRONT("frontal"), EXTERNAL("externa"), UNKNOWN("desconhecida"),
}

data class CameraInfo(
    val id: String,
    val minimumFocalLength: Float?,
    val lensFacing: LensFacing,
)

data class CameraCapabilities(
    val cameras: List<CameraInfo>,
    val concurrentPairs: Set<Set<String>>,
) {
    val likelyUltraWide: CameraInfo? = cameras
        .filter { it.lensFacing == LensFacing.BACK }
        .minByOrNull { it.minimumFocalLength ?: Float.MAX_VALUE }
    val supportsConcurrentCameras: Boolean = concurrentPairs.isNotEmpty()
}

object CameraCapabilitiesReader {
    fun read(context: Context): CameraCapabilities {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameras = manager.cameraIdList.map { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
                else -> LensFacing.UNKNOWN
            }
            CameraInfo(
                id,
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull(),
                facing,
            )
        }
        val cameraIds = cameras.mapTo(mutableSetOf()) { it.id }
        val pairs = manager.concurrentCameraIds
            .map { it.intersect(cameraIds) }
            .filter { it.size >= 2 }
            .toSet()
        return CameraCapabilities(cameras, pairs)
    }
}
