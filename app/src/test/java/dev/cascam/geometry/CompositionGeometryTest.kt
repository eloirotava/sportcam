package dev.cascam.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionGeometryTest {
    @Test fun `landscape container produces centered horizontal crop`() {
        val crop = NormalizedRect.centered16x9(2000, 1000)
        assertEquals(0f, crop.top, .0001f)
        assertEquals(1f, crop.bottom, .0001f)
        assertEquals(16f / 9f, crop.width * 2f, .0001f)
    }

    @Test fun `portrait container produces centered vertical crop`() {
        val crop = NormalizedRect.centered16x9(1000, 2000)
        assertEquals(0f, crop.left, .0001f)
        assertEquals(1f, crop.right, .0001f)
        assertEquals(9f / 32f, crop.height, .0001f)
    }

    @Test fun `accepts clockwise scoreboard quadrilateral`() {
        ScoreboardQuad(listOf(
            NormalizedPoint(.1f, .1f), NormalizedPoint(.9f, .1f),
            NormalizedPoint(.9f, .9f), NormalizedPoint(.1f, .9f),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects degenerate scoreboard quadrilateral`() {
        ScoreboardQuad(List(4) { NormalizedPoint(.5f, .5f) })
    }
}
