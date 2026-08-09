package dev.cascam.stream

import dev.cascam.config.VideoCodec
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HlsTransportPacketizationTest {
    @Test fun `reused packet buffer preserves transport stream and elementary video bytes`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        val pps = byteArrayOf(0x68, 0x01, 0x02)
        val nal = byteArrayOf(0x65, 0x11, 0x22, 0x33)
        val transport = HlsTransport("https://example.invalid/upload?file=", "key", VideoCodec.H264) {}
        transport.sendVideoConfig(VideoCodec.H264, null, sps, pps)
        transport.sendVideo(listOf(nal), timestampMs = 0, keyFrame = true)

        val segmentField = HlsTransport::class.java.getDeclaredField("segment").apply { isAccessible = true }
        val bytes = (segmentField.get(transport) as ByteArrayOutputStream).toByteArray()
        assertEquals(0, bytes.size % 188)

        val videoPayload = ByteArrayOutputStream()
        var expectedContinuity = 0
        bytes.asList().chunked(188).forEach { boxed ->
            val packet = boxed.toByteArray()
            assertEquals(0x47, packet[0].toInt() and 0xff)
            val pid = ((packet[1].toInt() and 0x1f) shl 8) or (packet[2].toInt() and 0xff)
            if (pid != 0x100) return@forEach
            assertEquals(expectedContinuity++ and 0x0f, packet[3].toInt() and 0x0f)
            val adaptationControl = packet[3].toInt() ushr 4 and 0x03
            var payloadOffset = 4
            if (adaptationControl == 2 || adaptationControl == 3) {
                payloadOffset += 1 + (packet[4].toInt() and 0xff)
            }
            if (adaptationControl == 1 || adaptationControl == 3) {
                videoPayload.write(packet, payloadOffset, 188 - payloadOffset)
            }
        }

        val pes = videoPayload.toByteArray()
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xe0.toByte()), pes.copyOfRange(0, 4))
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 1, 9, 0xf0.toByte(),
                0, 0, 0, 1, *sps,
                0, 0, 0, 1, *pps,
                0, 0, 0, 1, *nal,
            ),
            pes.copyOfRange(14, pes.size),
        )
    }
}
