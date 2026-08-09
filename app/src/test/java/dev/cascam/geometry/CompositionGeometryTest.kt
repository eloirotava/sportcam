package dev.cascam.geometry

import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.CaptureProfile
import dev.cascam.config.CaptureSettings
import dev.cascam.config.OverlayLayer
import dev.cascam.config.OutputResolution
import dev.cascam.config.ScoreboardSource
import dev.cascam.camera.CameraCapabilities
import dev.cascam.camera.CameraInfo
import dev.cascam.camera.LensFacing
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

    @Test fun `logo keeps image ratio and stays inside output`() {
        val destination = LogoGeometry.destination(1920, 1080, 800, 400, .2f, 1f, 1f)

        assertEquals(.2f, destination.width, .0001f)
        assertEquals(.2f * 1920f / 1080f * .5f, destination.height, .0001f)
        assertEquals(1f, destination.right, .0001f)
        assertEquals(1f, destination.bottom, .0001f)
    }

    @Test fun `white key removes white and preserves saturated colors`() {
        assertEquals(0, WhiteTransparency.applyToColor(0xffffffff.toInt()) ushr 24)
        assertEquals(255, WhiteTransparency.applyToColor(0xffff0000.toInt()) ushr 24)
        val feathered = WhiteTransparency.applyToColor(0xfff0f0f0.toInt()) ushr 24
        assertEquals(true, feathered in 1..254)
    }

    @Test fun `video corners map into centered crop of four by three photo`() {
        val mapped = StillFrameGeometry.fromVideoPreview(
            listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f), NormalizedPoint(1f, 1f), NormalizedPoint(0f, 1f)),
            3648, 2736,
        )

        assertEquals(.125f, mapped.first().y, .0001f)
        assertEquals(.875f, mapped[2].y, .0001f)
        assertEquals(0f, mapped.first().x, .0001f)
        assertEquals(1f, mapped[2].x, .0001f)
    }

    @Test fun `photo source requires a camera dedicated to scoreboard`() {
        val dedicated = BroadcastConfiguration(
            courtCameraId = "wide", scoreboardCameraId = "tele",
            scoreboardSource = ScoreboardSource.PHOTO_EVERY_SECOND,
        )
        val shared = dedicated.copy(scoreboardCameraId = "wide")

        assertEquals(1_000L, dedicated.stillIntervalFor("tele"))
        assertEquals(0L, shared.stillIntervalFor("wide"))
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

    @Test fun `three distinct enabled layers request three cameras`() {
        val configuration = BroadcastConfiguration(
            courtCameraId = "wide",
            scoreboardCameraId = "tele",
            scoreboardEnabled = true,
            clockCameraId = "front",
            clockEnabled = true,
        )

        assertEquals(linkedSetOf("wide", "tele", "front"), configuration.requiredCameraIds())
    }

    @Test fun `capture profile has a readable label`() {
        assertEquals("1920×1080 · 30 fps", CaptureProfile(1920, 1080, 30).label)
    }

    @Test fun `output resolutions are explicit and independent from bitrate`() {
        assertEquals(640 to 360, OutputResolution.P360.width to OutputResolution.P360.height)
        assertEquals(960 to 540, OutputResolution.P540.width to OutputResolution.P540.height)
        assertEquals(1280 to 720, OutputResolution.HD.width to OutputResolution.HD.height)
        assertEquals(1920 to 1080, OutputResolution.FULL_HD.width to OutputResolution.FULL_HD.height)
    }

    @Test fun `automatic court capture follows output fps`() {
        val configuration = BroadcastConfiguration(courtCameraId = "wide", captureFps = 0, outputFps = 30)

        assertEquals(30, configuration.resolvedCaptureSettings("wide").fps)
    }

    @Test fun `full hd sixty recommends higher bitrate`() {
        assertEquals(6_000_000, OutputResolution.FULL_HD.recommendedBitrate(30))
        assertEquals(9_000_000, OutputResolution.FULL_HD.recommendedBitrate(60))
    }

    @Test fun `shared camera resolves the most demanding layer profile`() {
        val configuration = BroadcastConfiguration(
            courtCameraId = "wide",
            captureWidth = 1280,
            captureHeight = 720,
            captureFps = 20,
            scoreboardCameraId = "wide",
            scoreboardCaptureWidth = 1920,
            scoreboardCaptureHeight = 1080,
            scoreboardCaptureFps = 10,
            clockCameraId = "wide",
            clockEnabled = true,
            clockCaptureWidth = 640,
            clockCaptureHeight = 360,
            clockCaptureFps = 30,
        )

        assertEquals(CaptureSettings(1920, 1080, 30), configuration.resolvedCaptureSettings("wide"))
    }

    @Test fun `shared camera resolves the largest requested capture zoom`() {
        val configuration = BroadcastConfiguration(
            courtCameraId = "shared",
            scoreboardCameraId = "shared",
            clockCameraId = "shared",
            scoreboardEnabled = true,
            clockEnabled = true,
            captureZoom = 1.4f,
            scoreboardCaptureZoom = 5f,
            clockCaptureZoom = 2f,
        )

        assertEquals(5f, configuration.resolvedCaptureZoom("shared"))
    }

    @Test fun `simultaneous support follows logical camera groups`() {
        val cameras = listOf(
            CameraInfo("0/wide", "0", "wide", 1f, LensFacing.BACK),
            CameraInfo("0/tele", "0", "tele", 3f, LensFacing.BACK),
            CameraInfo("1", "1", null, 2f, LensFacing.FRONT),
            CameraInfo("2", "2", null, 2f, LensFacing.EXTERNAL),
        )
        val capabilities = CameraCapabilities(cameras, setOf(setOf("0", "1")))

        assertEquals(true, capabilities.supportsSimultaneous(setOf("0/wide", "0/tele")))
        assertEquals(true, capabilities.supportsSimultaneous(setOf("0/wide", "1")))
        assertEquals(false, capabilities.supportsSimultaneous(setOf("0/wide", "2")))

        val triple = CameraCapabilities(cameras, setOf(setOf("0", "1", "2")))
        assertEquals(true, triple.supportsSimultaneous(setOf("0/wide", "1", "2")))
    }
}
