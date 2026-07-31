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
import dev.cascam.config.DEFAULT_SCOREBOARD_DESTINATION
import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect

class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Mode { COMPOSITION, COURT, SCOREBOARD }

    private val cropPaint = strokePaint(Color.rgb(115, 226, 167))
    private val cropHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(115, 226, 167) }
    private val quadPaint = strokePaint(Color.rgb(255, 196, 77))
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 196, 77) }
    private val destinationPaint = strokePaint(Color.rgb(65, 170, 255))
    private val destinationHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(65, 170, 255) }
    private val corners = DEFAULT_SCOREBOARD_CORNERS.toMutableList()
    private var destination = DEFAULT_SCOREBOARD_DESTINATION
    private var mode = Mode.COMPOSITION
    private var activeCorner: Int? = null
    private var editingDestination = false
    private var activeCropCorner: Int? = null
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
    fun setScoreboardDestination(rect: NormalizedRect) { destination = rect; invalidate() }
    fun scoreboardDestination(): NormalizedRect = destination

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (mode == Mode.COURT) drawCrop(canvas)
        if (mode == Mode.SCOREBOARD) drawScoreboard(canvas)
    }

    private fun drawCrop(canvas: Canvas) {
        val crop = NormalizedRect.adjustable16x9(width, height, cropZoom, cropPanX, cropPanY)
        canvas.drawRect(crop.left * width, crop.top * height, crop.right * width, crop.bottom * height, cropPaint)
        canvas.drawCircle(crop.left * width, crop.top * height, 14f, cropHandlePaint)
        canvas.drawCircle(crop.right * width, crop.bottom * height, 14f, cropHandlePaint)
    }

    private fun drawScoreboard(canvas: Canvas) {
        val path = Path()
        corners.forEachIndexed { index, point ->
            val x = point.x * width; val y = point.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (index == 0 || index == 2) canvas.drawCircle(x, y, 14f, handlePaint)
        }
        path.close(); canvas.drawPath(path, quadPaint)
        canvas.drawRect(
            destination.left * width, destination.top * height,
            destination.right * width, destination.bottom * height, destinationPaint,
        )
        canvas.drawCircle(destination.left * width, destination.top * height, 14f, destinationHandlePaint)
        canvas.drawCircle(destination.right * width, destination.bottom * height, 14f, destinationHandlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (mode == Mode.COMPOSITION) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                if (mode == Mode.COURT) activeCropCorner = nearestCropCorner(event.x, event.y)
                if (mode == Mode.SCOREBOARD) {
                    val destinationCorner = nearestDestinationCorner(event.x, event.y)
                    editingDestination = destinationCorner != null || insideDestination(event.x, event.y)
                    activeCorner = if (editingDestination) {
                        destinationCorner ?: -1
                    } else {
                        nearestCorner(event.x, event.y) ?: if (insideScoreboard(event.x, event.y)) -1 else null
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.COURT && !scaleDetector.isInProgress) {
                    activeCropCorner?.let { resizeCrop(it, event.x, event.y) }
                        ?: moveCrop(event.x - lastX, event.y - lastY)
                }
                if (mode == Mode.SCOREBOARD) activeCorner?.let {
                    if (editingDestination) {
                        if (it == -1) moveDestination(event.x - lastX, event.y - lastY) else resizeDestination(it, event.x, event.y)
                    } else {
                        if (it == -1) moveScoreboard(event.x - lastX, event.y - lastY) else resizeScoreboard(it, event.x, event.y)
                    }
                }
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { activeCorner = null; activeCropCorner = null; editingDestination = false; performClick(); return true }
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
    private fun nearestCorner(x: Float, y: Float): Int? = listOf(0, 2).minByOrNull { i ->
        val dx = x - corners[i].x * width; val dy = y - corners[i].y * height; dx * dx + dy * dy
    }?.takeIf { i -> distanceSquared(x, y, corners[i].x * width, corners[i].y * height) <= 56f * 56f }

    private fun nearestCropCorner(x: Float, y: Float): Int? {
        val crop = NormalizedRect.adjustable16x9(width, height, cropZoom, cropPanX, cropPanY)
        val points = listOf(crop.left * width to crop.top * height, crop.right * width to crop.bottom * height)
        return points.indices.minByOrNull { distanceSquared(x, y, points[it].first, points[it].second) }
            ?.takeIf { distanceSquared(x, y, points[it].first, points[it].second) <= 56f * 56f }
    }

    private fun nearestDestinationCorner(x: Float, y: Float): Int? {
        val points = listOf(destination.left * width to destination.top * height, destination.right * width to destination.bottom * height)
        return points.indices.minByOrNull { distanceSquared(x, y, points[it].first, points[it].second) }
            ?.takeIf { distanceSquared(x, y, points[it].first, points[it].second) <= 56f * 56f }
    }

    private fun resizeCrop(corner: Int, x: Float, y: Float) {
        val old = NormalizedRect.adjustable16x9(width, height, cropZoom, cropPanX, cropPanY)
        val fixedX = if (corner == 0) old.right * width else old.left * width
        val fixedY = if (corner == 0) old.bottom * height else old.top * height
        val requestedWidth = kotlin.math.abs(x - fixedX).coerceAtLeast(width * .06f)
        val requestedHeight = kotlin.math.abs(y - fixedY).coerceAtLeast(height * .06f)
        val targetRatio = 16f / 9f
        val newWidth = minOf(requestedWidth, requestedHeight * targetRatio).coerceAtMost(width.toFloat())
        val newHeight = newWidth / targetRatio
        val left = if (corner == 0) fixedX - newWidth else fixedX
        val top = if (corner == 0) fixedY - newHeight else fixedY
        setCropFromPixels(left.coerceIn(0f, width - newWidth), top.coerceIn(0f, height - newHeight), newWidth, newHeight)
    }

    private fun setCropFromPixels(left: Float, top: Float, cropWidth: Float, cropHeight: Float) {
        val base = NormalizedRect.centered16x9(width, height)
        cropZoom = (base.width / (cropWidth / width)).coerceIn(1f, 8f)
        val actual = NormalizedRect.adjustable16x9(width, height, cropZoom, 0f, 0f)
        val centerX = (left + cropWidth / 2f) / width
        val centerY = (top + cropHeight / 2f) / height
        cropPanX = if (actual.width < 1f) ((centerX - .5f) * 2f / (1f - actual.width)).coerceIn(-1f, 1f) else 0f
        cropPanY = if (actual.height < 1f) ((centerY - .5f) * 2f / (1f - actual.height)).coerceIn(-1f, 1f) else 0f
        notifyCropChanged()
    }

    private fun resizeScoreboard(corner: Int, x: Float, y: Float) {
        val moving = normalized(x, y)
        val fixed = corners[if (corner == 0) 2 else 0]
        val left = minOf(moving.x, fixed.x); val right = maxOf(moving.x, fixed.x)
        val top = minOf(moving.y, fixed.y); val bottom = maxOf(moving.y, fixed.y)
        if (right - left < .02f || bottom - top < .02f) return
        corners[0] = NormalizedPoint(left, top)
        corners[1] = NormalizedPoint(right, top)
        corners[2] = NormalizedPoint(right, bottom)
        corners[3] = NormalizedPoint(left, bottom)
        invalidate()
    }

    private fun insideScoreboard(x: Float, y: Float): Boolean =
        x / width in corners[0].x..corners[2].x && y / height in corners[0].y..corners[2].y

    private fun moveScoreboard(dx: Float, dy: Float) {
        val normalizedDx = (dx / width).coerceIn(-corners[0].x, 1f - corners[2].x)
        val normalizedDy = (dy / height).coerceIn(-corners[0].y, 1f - corners[2].y)
        corners.indices.forEach { index ->
            corners[index] = NormalizedPoint(corners[index].x + normalizedDx, corners[index].y + normalizedDy)
        }
        invalidate()
    }

    private fun insideDestination(x: Float, y: Float): Boolean =
        x / width in destination.left..destination.right && y / height in destination.top..destination.bottom

    private fun resizeDestination(corner: Int, x: Float, y: Float) {
        val moving = normalized(x, y)
        val fixedX = if (corner == 0) destination.right else destination.left
        val fixedY = if (corner == 0) destination.bottom else destination.top
        val left = minOf(moving.x, fixedX); val right = maxOf(moving.x, fixedX)
        val top = minOf(moving.y, fixedY); val bottom = maxOf(moving.y, fixedY)
        if (right - left >= .02f && bottom - top >= .02f) {
            destination = NormalizedRect(left, top, right, bottom)
            invalidate()
        }
    }

    private fun moveDestination(dx: Float, dy: Float) {
        val normalizedDx = (dx / width).coerceIn(-destination.left, 1f - destination.right)
        val normalizedDy = (dy / height).coerceIn(-destination.top, 1f - destination.bottom)
        destination = NormalizedRect(
            destination.left + normalizedDx, destination.top + normalizedDy,
            destination.right + normalizedDx, destination.bottom + normalizedDy,
        )
        invalidate()
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return dx * dx + dy * dy
    }
    private fun normalized(x: Float, y: Float) = NormalizedPoint((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun strokePaint(colorValue: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue; style = Paint.Style.STROKE; strokeWidth = 4f
    }
}
