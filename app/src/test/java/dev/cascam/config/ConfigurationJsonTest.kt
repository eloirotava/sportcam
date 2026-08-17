package dev.cascam.config

import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConfigurationJsonTest {
    private val configured = BroadcastConfiguration(
        courtCameraId = "0",
        scoreboardCameraId = "2",
        scoreboardEnabled = true,
        scoreboardSource = OverlaySource.PHOTO_EVERY_SECOND,
        scoreboardPhotoIntervalMillis = 5_000L,
        cropZoom = 2.5f,
        cropPanX = -.4f,
        cropPanY = .3f,
        scoreboardCorners = listOf(
            NormalizedPoint(.1f, .2f), NormalizedPoint(.8f, .25f),
            NormalizedPoint(.75f, .6f), NormalizedPoint(.05f, .55f),
        ),
        scoreboardDestination = NormalizedRect(.02f, .03f, .3f, .2f),
        clockEnabled = true,
        captureWidth = 3840,
        captureHeight = 2160,
        captureFps = 30,
        outputResolution = OutputResolution.FULL_HD,
        outputFps = 30,
        youtubeStreamKey = "abcd-efgh-ijkl-mnop",
        youtubeOAuthClientSecret = "segredo-oauth",
        liveTitle = "Liceu Santista",
        livePrivacy = LivePrivacy.PUBLIC,
        remoteEnabled = true,
        remotePort = 8181,
        remoteUser = "eloi",
        remotePassword = "senha-do-site",
        tunnelUrl = "wss://a.rotava.com",
        tunnelToken = "a".repeat(64),
    )

    @Test
    fun `a ida e volta preserva a configuracao inteira`() {
        val json = ConfigurationJson.toJson(configured, maskSecrets = false)
        assertEquals(configured, ConfigurationJson.fromJson(json, BroadcastConfiguration()))
    }

    @Test
    fun `segredos saem mascarados e voltam intactos`() {
        val json = ConfigurationJson.toJson(configured)
        assertEquals(ConfigurationJson.SECRET_PLACEHOLDER, json.getString("youtubeStreamKey"))
        assertEquals(ConfigurationJson.SECRET_PLACEHOLDER, json.getString("remotePassword"))
        assertEquals(ConfigurationJson.SECRET_PLACEHOLDER, json.getString("tunnelToken"))
        assertNotEquals(configured.youtubeStreamKey, json.getString("youtubeStreamKey"))

        // O site devolve o marcador porque nunca viu o valor; o aparelho mantém o que já tinha.
        val voltou = ConfigurationJson.fromJson(json, configured)
        assertEquals(configured.youtubeStreamKey, voltou.youtubeStreamKey)
        assertEquals(configured.remotePassword, voltou.remotePassword)
        assertEquals(configured.tunnelToken, voltou.tunnelToken)
    }

    @Test
    fun `segredo novo substitui o guardado`() {
        val json = ConfigurationJson.toJson(configured).put("youtubeStreamKey", "wxyz-0000-1111-2222")
        assertEquals("wxyz-0000-1111-2222", ConfigurationJson.fromJson(json, configured).youtubeStreamKey)
    }

    @Test
    fun `json parcial so muda o que trouxe`() {
        val json = JSONObject().put("outputFps", 60).put("scoreboardEnabled", false)
        val resultado = ConfigurationJson.fromJson(json, configured)
        assertEquals(60, resultado.outputFps)
        assertEquals(false, resultado.scoreboardEnabled)
        assertEquals(configured.courtCameraId, resultado.courtCameraId)
        assertEquals(configured.scoreboardCorners, resultado.scoreboardCorners)
        assertEquals(configured.youtubeStreamKey, resultado.youtubeStreamKey)
    }

    @Test
    fun `valor invalido cai no que ja estava`() {
        val json = JSONObject()
            .put("outputFps", 17)
            .put("livePrivacy", "INEXISTENTE")
            .put("remotePort", 80)
            .put("scoreboardPhotoIntervalMillis", 1_234L)
        val resultado = ConfigurationJson.fromJson(json, configured)
        assertEquals(configured.outputFps, resultado.outputFps)
        assertEquals(configured.livePrivacy, resultado.livePrivacy)
        assertEquals(configured.remotePort, resultado.remotePort)
        assertEquals(configured.scoreboardPhotoIntervalMillis, resultado.scoreboardPhotoIntervalMillis)
    }

    @Test
    fun `quadrilatero pela metade nao substitui o que estava`() {
        val json = ConfigurationJson.toJson(configured, maskSecrets = false)
        json.put("scoreboardCorners", org.json.JSONArray().put(JSONObject().put("x", .1).put("y", .1)))
        assertEquals(configured.scoreboardCorners, ConfigurationJson.fromJson(json, configured).scoreboardCorners)
    }
}
