package dev.cascam.stream

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class YoutubePublisher(
    private val serverUrl: String,
    private val streamKey: String,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean()
    private val frames = ArrayBlockingQueue<Bitmap>(1)
    private var worker: Thread? = null
    @Volatile private var activeClient: RtmpClient? = null

    fun start() {
        check(running.compareAndSet(false, true))
        worker = Thread(::publishLoop, "cascam-youtube").also { it.start() }
    }

    fun offer(bitmap: Bitmap) {
        if (!running.get()) { bitmap.recycle(); return }
        frames.poll()?.recycle()
        if (!frames.offer(bitmap)) bitmap.recycle()
    }

    private fun publishLoop() {
        var codec: MediaCodec? = null
        var rtmp: RtmpClient? = null
        try {
            onStatus("Conectando ao YouTube por RTMPS…")
            rtmp = RtmpClient(serverUrl, streamKey).also { activeClient = it; it.connect() }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            onStatus("● AO VIVO · 1280×720 · ${FPS} fps · 3 Mbps")
            val startedAt = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            var sentConfig = false
            while (running.get()) {
                frames.poll()?.let { bitmap ->
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        codec.getInputBuffer(index)?.let { argbToI420(bitmap, it) }
                        val pts = (System.nanoTime() - startedAt) / 1_000
                        codec.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2, pts, 0)
                    }
                    bitmap.recycle()
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec.outputFormat
                        val sps = stripStartCode(output.getByteBuffer("csd-0")!!.toByteArray())
                        val pps = stripStartCode(output.getByteBuffer("csd-1")!!.toByteArray())
                        rtmp.sendAvcSequenceHeader(sps, pps); sentConfig = true
                    } else if (index >= 0) {
                        if (sentConfig && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = ByteArray(info.size)
                            codec.getOutputBuffer(index)!!.apply { position(info.offset); limit(info.offset + info.size); get(data) }
                            rtmp.sendVideo(splitNals(data), (info.presentationTimeUs / 1_000).toInt(), info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                        codec.releaseOutputBuffer(index, false)
                    } else break
                }
                if (frames.isEmpty()) Thread.sleep(5)
            }
        } catch (error: Exception) {
            if (running.getAndSet(false)) onStatus("Falha na transmissão: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            frames.forEach(Bitmap::recycle); frames.clear()
            runCatching { codec?.stop() }; runCatching { codec?.release() }; rtmp?.close(); activeClient = null
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        activeClient?.close()
        worker?.join(2_000)
        onStatus("Transmissão encerrada")
    }

    private fun argbToI420(bitmap: Bitmap, output: ByteBuffer) {
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        output.clear()
        for (color in pixels) {
            val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
            output.put(((77 * r + 150 * g + 29 * b shr 8).coerceIn(0, 255)).toByte())
        }
        for (y in 0 until HEIGHT step 2) for (x in 0 until WIDTH step 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val color = pixels[(y + dy) * WIDTH + x + dx]
                r += color shr 16 and 255; g += color shr 8 and 255; b += color and 255
            }
            output.put(((-43 * (r / 4) - 85 * (g / 4) + 128 * (b / 4) shr 8) + 128).coerceIn(0, 255).toByte())
        }
        for (y in 0 until HEIGHT step 2) for (x in 0 until WIDTH step 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val color = pixels[(y + dy) * WIDTH + x + dx]
                r += color shr 16 and 255; g += color shr 8 and 255; b += color and 255
            }
            output.put(((128 * (r / 4) - 107 * (g / 4) - 21 * (b / 4) shr 8) + 128).coerceIn(0, 255).toByte())
        }
        output.flip()
    }

    private fun ByteBuffer.toByteArray() = duplicate().let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
    private fun stripStartCode(data: ByteArray): ByteArray = when {
        data.size >= 4 && data.sliceArray(0..3).contentEquals(byteArrayOf(0, 0, 0, 1)) -> data.copyOfRange(4, data.size)
        data.size >= 3 && data.sliceArray(0..2).contentEquals(byteArrayOf(0, 0, 1)) -> data.copyOfRange(3, data.size)
        else -> data
    }
    private fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index < data.size - 3) {
            val length = when {
                data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte() -> 3
                index < data.size - 4 && data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (length > 0) { starts += index to length; index += length } else index++
        }
        if (starts.isEmpty()) {
            val result = mutableListOf<ByteArray>()
            var offset = 0
            while (offset + 4 <= data.size) {
                val size = ((data[offset].toInt() and 255) shl 24) or ((data[offset + 1].toInt() and 255) shl 16) or
                    ((data[offset + 2].toInt() and 255) shl 8) or (data[offset + 3].toInt() and 255)
                if (size <= 0 || offset + 4 + size > data.size) return listOf(data)
                result += data.copyOfRange(offset + 4, offset + 4 + size)
                offset += 4 + size
            }
            return result.takeIf { offset == data.size && it.isNotEmpty() } ?: listOf(data)
        }
        return starts.mapIndexed { i, (start, prefix) -> data.copyOfRange(start + prefix, starts.getOrNull(i + 1)?.first ?: data.size) }
    }

    companion object { private const val WIDTH = 1280; private const val HEIGHT = 720; private const val FPS = 15 }
}
