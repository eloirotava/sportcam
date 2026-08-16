package dev.cascam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.OverlayLayer
import dev.cascam.geometry.NormalizedRect
import dev.cascam.geometry.LogoGeometry
import dev.cascam.geometry.StillFrameGeometry
import dev.cascam.stream.YoutubePublisher

class ComposedOutputView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var courtFrame: Bitmap? = null
    private var scoreboardFrame: Bitmap? = null
    private var clockFrame: Bitmap? = null
    private var logoBitmap: Bitmap? = null
    @Volatile private var configuration = BroadcastConfiguration()
    @Volatile private var exportIntervalNanos = 1_000_000_000L / YoutubePublisher.FPS
    var onComposedFrame: ((Bitmap) -> Unit)? = null
    private var lastExportNanos = 0L

    fun configure(value: BroadcastConfiguration) {
        configuration = value
        val fps = value.outputFps
        exportIntervalNanos = 1_000_000_000L / fps
        invalidate()
    }
    fun setLogo(bitmap: Bitmap?) {
        logoBitmap = bitmap
        invalidate()
    }
    fun submitCourt(bitmap: Bitmap) = post {
        val previous = courtFrame
        courtFrame = bitmap
        previous?.recycle()
        exportFrameIfNeeded()
        invalidate()
    }
    fun submitScoreboard(bitmap: Bitmap) = post {
        val previous = scoreboardFrame
        scoreboardFrame = bitmap
        previous?.recycle()
        exportFrameIfNeeded()
        invalidate()
    }
    fun submitClock(bitmap: Bitmap) = post {
        val previous = clockFrame
        clockFrame = bitmap
        previous?.recycle()
        exportFrameIfNeeded()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        render(canvas, width, height)
    }

    private fun exportFrameIfNeeded() {
        val now = System.nanoTime()
        if (onComposedFrame != null && now - lastExportNanos >= exportIntervalNanos) {
            lastExportNanos = now
            val resolution = configuration.outputResolution
            val output = Bitmap.createBitmap(resolution.width, resolution.height, Bitmap.Config.ARGB_8888)
            render(Canvas(output), output.width, output.height)
            onComposedFrame?.invoke(output)
        }
    }

    private fun render(canvas: Canvas, outputWidth: Int, outputHeight: Int) {
        canvas.drawColor(Color.BLACK)
        val config = configuration
        courtFrame?.let { drawCourt(canvas, it, config, outputWidth, outputHeight) }
        if (config.scoreboardEnabled) scoreboardFrame?.let {
            drawOverlay(
                canvas, it, cornersFor(config, OverlayLayer.SCOREBOARD, it),
                config.scoreboardDestination, outputWidth, outputHeight,
            )
        }
        if (config.clockEnabled) clockFrame?.let {
            drawOverlay(
                canvas, it, cornersFor(config, OverlayLayer.CLOCK, it),
                config.clockDestination, outputWidth, outputHeight,
            )
        }
        if (config.logoEnabled) logoBitmap?.let { logo ->
            val destination = LogoGeometry.destination(
                outputWidth, outputHeight, logo.width, logo.height,
                config.logoWidth, config.logoCenterX, config.logoCenterY,
            )
            canvas.drawBitmap(
                logo,
                null,
                RectF(
                    destination.left * outputWidth, destination.top * outputHeight,
                    destination.right * outputWidth, destination.bottom * outputHeight,
                ),
                paint,
            )
        }
    }

    /**
     * Cantos no espaço da imagem que chegou. Em vídeo o marcado já é o espaço certo; em foto o JPEG
     * é o quadro cheio do sensor, normalmente 4:3, e o que foi marcado veio do preview 16:9.
     */
    private fun cornersFor(
        config: BroadcastConfiguration,
        layer: OverlayLayer,
        frame: Bitmap,
    ): List<dev.cascam.geometry.NormalizedPoint> {
        val corners = if (layer == OverlayLayer.SCOREBOARD) config.scoreboardCorners else config.clockCorners
        return if (config.usesPhoto(layer)) {
            StillFrameGeometry.fromVideoPreview(corners, frame.width, frame.height)
        } else corners
    }

    /** Um recorte só: o retângulo 16:9 que o operador arrasta é a única fonte da verdade. */
    private fun drawCourt(canvas: Canvas, bitmap: Bitmap, config: BroadcastConfiguration, outputWidth: Int, outputHeight: Int) {
        val crop = NormalizedRect.adjustable16x9(bitmap.width, bitmap.height, config.cropZoom, config.cropPanX, config.cropPanY)
        val source = Rect(
            (crop.left * bitmap.width).toInt(), (crop.top * bitmap.height).toInt(),
            (crop.right * bitmap.width).toInt(), (crop.bottom * bitmap.height).toInt(),
        )
        canvas.drawBitmap(bitmap, source, Rect(0, 0, outputWidth, outputHeight), paint)
    }

    private fun drawOverlay(
        canvas: Canvas,
        bitmap: Bitmap,
        corners: List<dev.cascam.geometry.NormalizedPoint>,
        configured: NormalizedRect,
        outputWidth: Int,
        outputHeight: Int,
    ) {
        val destination = RectF(configured.left * outputWidth, configured.top * outputHeight, configured.right * outputWidth, configured.bottom * outputHeight)
        val sourcePoints = corners.flatMap { listOf(it.x * bitmap.width, it.y * bitmap.height) }.toFloatArray()
        val destinationPoints = floatArrayOf(
            destination.left, destination.top, destination.right, destination.top,
            destination.right, destination.bottom, destination.left, destination.bottom,
        )
        val homography = Matrix()
        if (!homography.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) return
        canvas.save()
        canvas.clipRect(destination)
        canvas.concat(homography)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.WHITE
        canvas.drawRect(destination, paint)
        paint.style = Paint.Style.FILL
    }
}
