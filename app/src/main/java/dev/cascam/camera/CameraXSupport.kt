package dev.cascam.camera

import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/** Ponte entre o inventário de câmeras do CasCam e os seletores/use cases do CameraX. */
object CameraXSupport {
    val DEFAULT_ANALYSIS_SIZE: Size = Size(1280, 720)

    @ExperimentalCamera2Interop
    fun selectorFor(cameraId: String): CameraSelector = CameraSelector.Builder().addCameraFilter { cameras ->
        cameras.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
    }.build()

    /**
     * ImageAnalysis já preso ao sensor físico pedido. Quando [camera] é um sensor físico de uma
     * câmera lógica, o fluxo sai daquele sensor mesmo — é assim que dois enquadramentos diferentes
     * saem de uma única câmera lógica.
     */
    @ExperimentalCamera2Interop
    fun imageAnalysis(camera: CameraInfo, size: Size = DEFAULT_ANALYSIS_SIZE): ImageAnalysis {
        val resolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        ).build()
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        camera.physicalCameraId?.let { Camera2Interop.Extender(builder).setPhysicalCameraId(it) }
        return builder.build()
    }
}
