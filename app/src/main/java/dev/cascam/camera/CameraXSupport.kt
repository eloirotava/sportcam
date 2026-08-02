package dev.cascam.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Size
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.util.concurrent.Executor

/** Ponte entre o inventário de câmeras do CasCam e os seletores/use cases do CameraX. */
object CameraXSupport {
    val DEFAULT_ANALYSIS_SIZE: Size = Size(1280, 720)
    private val directExecutor = Executor { command -> command.run() }

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
    fun imageAnalysis(camera: CameraInfo, size: Size = DEFAULT_ANALYSIS_SIZE, fps: Int = 0): ImageAnalysis {
        val resolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        ).build()
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        targetFpsRange(camera, fps)?.let { range -> Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range,
        ) }
        camera.physicalCameraId?.let { Camera2Interop.Extender(builder).setPhysicalCameraId(it) }
        return builder.build()
    }

    @ExperimentalCamera2Interop
    fun preview(camera: CameraInfo, surface: android.view.Surface, size: Size, fps: Int = 0): Preview {
        val resolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        ).build()
        val builder = Preview.Builder().setResolutionSelector(resolutionSelector)
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        targetFpsRange(camera, fps)?.let { extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        camera.physicalCameraId?.let(extender::setPhysicalCameraId)
        return builder.build().apply {
            setSurfaceProvider { request -> request.provideSurface(surface, directExecutor) {} }
        }
    }

    private fun targetFpsRange(camera: CameraInfo, fps: Int): Range<Int>? = if (fps <= 0) null else {
        camera.fpsRanges.filter { it.upper == fps }.minByOrNull { it.upper - it.lower }
    }
}
