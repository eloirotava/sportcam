package dev.cascam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.cascam.config.DEFAULT_SCOREBOARD_CORNERS
import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect

class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Mode { COMPOSITION, COURT, SCOREBOARD }

    private val cropPaint = strokePaint(Color.rgb(115, 226, 167))
    private val quadPaint = strokePaint(Color.rgb(255, 196, 77))
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 196, 77) }
    private val corners = DEFAULT_SCOREBOARD_CORNERS.toMutableList()
    private var mode = Mode.COMPOSITION
    private var activeCorner: Int? = null
    private var cropZoom = 1f
    private var cropPanX = 0f
    private var cropPanY = 0f
    private var lastX = 0f
    private var lastY = 0f
    var onCropChanged: ((zoom: Float, panX: Float, panY: Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (mode != Mode.COURT) return false
            cropZoom = (cropZoom * detector.scaleFactor).coerceIn(1f, 8f)
            notifyCropChanged()
            return true
        }
    })

    fun setMode(value: Mode) { mode = value; invalidate() }
    fun setCrop(zoom: Float, panX: Float, panY: Float) {
        cropZoom = zoom.coerceIn(1f, 8f); cropPanX = panX.coerceIn(-1f, 1f); cropPanY = panY.coerceIn(-1f, 1f)
        invalidate()
    }
    fun crop(): Triple<Float, Float, Float> = Triple(cropZoom, cropPanX, cropPanY)
    fun changeCropZoom(delta: Float) {
        cropZoom = (cropZoom + delta).coerceIn(1f, 8f)
        notifyCropChanged()
    }
    fun setScoreboardCorners(points: List<NormalizedPoint>) { require(points.size == 4); corners.clear(); corners.addAll(points); invalidate() }
    fun scoreboardCorners(): List<NormalizedPoint> = corners.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (mode == Mode.COURT) drawCrop(canvas)
        if (mode == Mode.SCOREBOARD) drawScoreboard(canvas)
    }

    private fun drawCrop(canvas: Canvas) {
        val crop = NormalizedRect.adjustable16x9(width, height, cropZoom, cropPanX, cropPanY)
        canvas.drawRect(crop.left * width, crop.top * height, crop.right * width, crop.bottom * height, cropPaint)
    }

    private fun drawScoreboard(canvas: Canvas) {
        val path = Path()
        corners.forEachIndexed { index, point ->
            val x = point.x * width; val y = point.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, 12f, handlePaint)
        }
        path.close(); canvas.drawPath(path, quadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (mode == Mode.COMPOSITION) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                if (mode == Mode.SCOREBOARD) activeCorner = nearestCorner(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.COURT && !scaleDetector.isInProgress) moveCrop(event.x - lastX, event.y - lastY)
                if (mode == Mode.SCOREBOARD) activeCorner?.let { corners[it] = normalized(event.x, event.y); invalidate() }
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { activeCorner = null; performClick(); return true }
        }
        return true
    }

    private fun moveCrop(dx: Float, dy: Float) {
        val crop = NormalizedRect.adjustable16x9(width, height, cropZoom, cropPanX, cropPanY)
        if (crop.width < 1f) cropPanX = (cropPanX + 2f * dx / width / (1f - crop.width)).coerceIn(-1f, 1f)
        if (crop.height < 1f) cropPanY = (cropPanY + 2f * dy / height / (1f - crop.height)).coerceIn(-1f, 1f)
        notifyCropChanged()
    }

    private fun notifyCropChanged() { invalidate(); onCropChanged?.invoke(cropZoom, cropPanX, cropPanY) }
    private fun nearestCorner(x: Float, y: Float) = corners.indices.minByOrNull { i ->
        val dx = x - corners[i].x * width; val dy = y - corners[i].y * height; dx * dx + dy * dy
    }
    private fun normalized(x: Float, y: Float) = NormalizedPoint((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun strokePaint(colorValue: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue; style = Paint.Style.STROKE; strokeWidth = 4f
    }
}
