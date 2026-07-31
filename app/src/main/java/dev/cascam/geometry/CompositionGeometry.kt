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
