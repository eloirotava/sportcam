package dev.cascam.geometry

import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.CaptureProfile
import dev.cascam.config.OverlayLayer
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

    @Test fun `adjustable crop preserves 16 by 9 pixel ratio`() {
        val crop = NormalizedRect.adjustable16x9(2400, 1080, 2f, 1f, -1f)
        assertEquals(16f / 9f, crop.width * 2400f / (crop.height * 1080f), .0001f)
        assertEquals(1f, crop.right, .0001f)
        assertEquals(0f, crop.top, .0001f)
    }

    @Test fun `shared overlay sources open a camera only once`() {
        val configuration = BroadcastConfiguration(
            courtCameraId = "wide",
            scoreboardCameraId = "tele",
            scoreboardEnabled = true,
            clockCameraId = "tele",
            clockEnabled = true,
        )

        assertEquals(linkedSetOf("wide", "tele"), configuration.requiredCameraIds())
    }

    @Test fun `disabled overlays do not request their cameras`() {
        val configuration = BroadcastConfiguration(
            courtCameraId = "wide",
            scoreboardCameraId = "tele",
            scoreboardEnabled = false,
            clockCameraId = "front",
            clockEnabled = false,
        )

        assertEquals(setOf("wide"), configuration.requiredCameraIds())
    }

    @Test fun `blank overlay source reuses court camera`() {
        val configuration = BroadcastConfiguration(courtCameraId = "wide", clockEnabled = true)

        assertEquals("wide", configuration.cameraIdFor(OverlayLayer.CLOCK))
        assertEquals(setOf("wide"), configuration.requiredCameraIds())
    }

    @Test fun `capture profile stays automatic until all values are selected`() {
        assertEquals(null, BroadcastConfiguration(captureWidth = 1280, captureHeight = 720).requestedCaptureProfile)
        assertEquals(
            CaptureProfile(1280, 720, 30),
            BroadcastConfiguration(captureWidth = 1280, captureHeight = 720, captureFps = 30).requestedCaptureProfile,
        )
    }
}
