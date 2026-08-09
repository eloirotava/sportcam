package dev.cascam.geometry

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "Coordinates must be normalized" }
    }
}

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }

    companion object {
        fun centered16x9(containerWidth: Int, containerHeight: Int): NormalizedRect {
            require(containerWidth > 0 && containerHeight > 0)
            val target = 16f / 9f
            val containerRatio = containerWidth.toFloat() / containerHeight
            return if (containerRatio > target) {
                val normalizedWidth = target / containerRatio
                NormalizedRect((1f - normalizedWidth) / 2f, 0f, (1f + normalizedWidth) / 2f, 1f)
            } else {
                val normalizedHeight = containerRatio / target
                NormalizedRect(0f, (1f - normalizedHeight) / 2f, 1f, (1f + normalizedHeight) / 2f)
            }
        }

        fun adjustable16x9(
            containerWidth: Int,
            containerHeight: Int,
            zoom: Float,
            panX: Float,
            panY: Float,
        ): NormalizedRect {
            require(zoom in 1f..8f)
            require(panX in -1f..1f && panY in -1f..1f)
            val base = centered16x9(containerWidth, containerHeight)
            val width = base.width / zoom
            val height = base.height / zoom
            val centerX = .5f + panX * (1f - width) / 2f
            val centerY = .5f + panY * (1f - height) / 2f
            return NormalizedRect(
                centerX - width / 2f,
                centerY - height / 2f,
                centerX + width / 2f,
                centerY + height / 2f,
            )
        }
    }
}

data class ScoreboardQuad(val corners: List<NormalizedPoint>) {
    init {
        require(corners.size == 4) { "A scoreboard requires exactly four corners" }
        require(signedArea() > 0.0001f) { "Corners must be clockwise and non-degenerate" }
    }

    private fun signedArea(): Float = corners.indices.sumOf { index ->
        val current = corners[index]
        val next = corners[(index + 1) % corners.size]
        ((current.x * next.y - next.x * current.y) / 2f).toDouble()
    }.toFloat()
}

object LogoGeometry {
    /** Preserva a proporção do arquivo e impede que o ícone saia da área transmitida. */
    fun destination(
        outputWidth: Int,
        outputHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        requestedWidth: Float,
        requestedCenterX: Float,
        requestedCenterY: Float,
    ): NormalizedRect {
        require(outputWidth > 0 && outputHeight > 0 && imageWidth > 0 && imageHeight > 0)
        var width = requestedWidth.coerceIn(.05f, .5f)
        var height = width * outputWidth / outputHeight.toFloat() * imageHeight / imageWidth.toFloat()
        if (height > .9f) {
            width *= .9f / height
            height = .9f
        }
        val centerX = requestedCenterX.coerceIn(width / 2f, 1f - width / 2f)
        val centerY = requestedCenterY.coerceIn(height / 2f, 1f - height / 2f)
        return NormalizedRect(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
    }
}

object WhiteTransparency {
    /**
     * Remove branco puro e suaviza os 30 níveis próximos dele. Usar o menor canal mantém cores
     * saturadas opacas: amarelo claro, por exemplo, não some só porque dois canais são altos.
     */
    fun applyToColor(color: Int): Int {
        val originalAlpha = color ushr 24 and 0xff
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        val distanceFromWhite = 255 - minOf(red, green, blue)
        val keyedAlpha = (distanceFromWhite * 255 / FEATHER).coerceIn(0, 255)
        return (originalAlpha * keyedAlpha / 255 shl 24) or (color and 0x00ffffff)
    }

    private const val FEATHER = 30
}

object StillFrameGeometry {
    data class DecodeRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val fullWidth: Int,
        val fullHeight: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        init {
            require(fullWidth > 0 && fullHeight > 0)
            require(left in 0 until right && top in 0 until bottom)
            require(right <= fullWidth && bottom <= fullHeight)
        }
    }

    /** Converte pontos marcados no preview 16:9 para o JPEG cheio, normalmente 4:3. */
    fun fromVideoPreview(
        corners: List<NormalizedPoint>,
        stillWidth: Int,
        stillHeight: Int,
        previewZoom: Float = 1f,
    ): List<NormalizedPoint> {
        val videoCrop = NormalizedRect.centered16x9(stillWidth, stillHeight)
        return corners.map { point ->
            val unzoomed = CaptureZoomGeometry.fromZoomedPreview(point, previewZoom)
            NormalizedPoint(
                videoCrop.left + unzoomed.x * videoCrop.width,
                videoCrop.top + unzoomed.y * videoCrop.height,
            )
        }
    }

    /** Retângulo mínimo do JPEG que contém o quadrilátero, com margem para filtragem nas bordas. */
    fun decodeRegion(
        corners: List<NormalizedPoint>,
        stillWidth: Int,
        stillHeight: Int,
        previewZoom: Float = 1f,
        paddingFraction: Float = .08f,
    ): DecodeRegion {
        require(corners.size == 4)
        require(paddingFraction in 0f..1f)
        val mapped = fromVideoPreview(corners, stillWidth, stillHeight, previewZoom)
        val minimumX = mapped.minOf { it.x } * stillWidth
        val maximumX = mapped.maxOf { it.x } * stillWidth
        val minimumY = mapped.minOf { it.y } * stillHeight
        val maximumY = mapped.maxOf { it.y } * stillHeight
        val paddingX = maxOf(8f, (maximumX - minimumX) * paddingFraction)
        val paddingY = maxOf(8f, (maximumY - minimumY) * paddingFraction)
        val left = kotlin.math.floor(minimumX - paddingX).toInt().coerceIn(0, stillWidth - 1)
        val top = kotlin.math.floor(minimumY - paddingY).toInt().coerceIn(0, stillHeight - 1)
        val right = kotlin.math.ceil(maximumX + paddingX).toInt().coerceIn(left + 1, stillWidth)
        val bottom = kotlin.math.ceil(maximumY + paddingY).toInt().coerceIn(top + 1, stillHeight)
        return DecodeRegion(left, top, right, bottom, stillWidth, stillHeight)
    }

    /** Renormaliza os cantos do JPEG cheio para a textura produzida pelo decoder regional. */
    fun fromVideoPreviewToRegion(
        corners: List<NormalizedPoint>,
        region: DecodeRegion,
        previewZoom: Float = 1f,
    ): List<NormalizedPoint> = fromVideoPreview(
        corners, region.fullWidth, region.fullHeight, previewZoom,
    ).map { point ->
        NormalizedPoint(
            ((point.x * region.fullWidth - region.left) / region.width).coerceIn(0f, 1f),
            ((point.y * region.fullHeight - region.top) / region.height).coerceIn(0f, 1f),
        )
    }
}

/** Recorte central previsível, independente de o HAL respeitar zoom por sensor físico. */
object CaptureZoomGeometry {
    fun fromZoomedPreview(point: NormalizedPoint, zoom: Float): NormalizedPoint {
        val safeZoom = zoom.coerceIn(1f, 8f)
        return NormalizedPoint(
            .5f + (point.x - .5f) / safeZoom,
            .5f + (point.y - .5f) / safeZoom,
        )
    }

    fun fromZoomedPreview(rect: NormalizedRect, zoom: Float): NormalizedRect {
        val topLeft = fromZoomedPreview(NormalizedPoint(rect.left, rect.top), zoom)
        val bottomRight = fromZoomedPreview(NormalizedPoint(rect.right, rect.bottom), zoom)
        return NormalizedRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    }
}
