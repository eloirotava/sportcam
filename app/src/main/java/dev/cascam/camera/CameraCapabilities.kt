package dev.cascam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

data class CameraInfo(val id: String, val minimumFocalLength: Float?)

data class CameraCapabilities(
    val rearCameras: List<CameraInfo>,
    val concurrentRearPairs: Set<Set<String>>,
) {
    val likelyUltraWide: CameraInfo? = rearCameras.minByOrNull { it.minimumFocalLength ?: Float.MAX_VALUE }
    val supportsConcurrentRearCameras: Boolean = concurrentRearPairs.isNotEmpty()
}

object CameraCapabilitiesReader {
    fun read(context: Context): CameraCapabilities {
        val manager = context.getSystemService(CameraManager::class.java)
        val rear = manager.cameraIdList.mapNotNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                null
            } else {
                CameraInfo(id, characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull())
            }
        }
        val rearIds = rear.mapTo(mutableSetOf()) { it.id }
        val pairs = manager.concurrentCameraIds
            .map { it.intersect(rearIds) }
            .filter { it.size >= 2 }
            .toSet()
        return CameraCapabilities(rear, pairs)
    }
}
