package dev.cascam.stream

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private var audioWorker: Thread? = null

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
            val connectedRtmp = RtmpClient(serverUrl, streamKey).also { activeClient = it; it.connect() }
            rtmp = connectedRtmp
            connectedRtmp.sendMetadata(WIDTH, HEIGHT, FPS, VIDEO_BITRATE)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val supportedColors = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.toSet()
            val colorFormat = when {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in supportedColors ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in supportedColors ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val startedAt = System.nanoTime()
            audioWorker = Thread({ publishAudio(connectedRtmp, startedAt) }, "cascam-audio").also { it.start() }
            onStatus("Publicação aceita pelo YouTube; aguardando o primeiro quadro H.264…")
            val info = MediaCodec.BufferInfo()
            var sentConfig = false
            var sentFrames = 0
            while (running.get()) {
                frames.poll()?.let { bitmap ->
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        codec.getInputBuffer(index)?.let { argbToYuv420(bitmap, it, colorFormat) }
                        val pts = (System.nanoTime() - startedAt) / 1_000
                        codec.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2, pts, 0)
                    }
                    bitmap.recycle()
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec.outputFormat
                        val units = listOfNotNull(output.getByteBuffer("csd-0"), output.getByteBuffer("csd-1"))
                            .flatMap { splitNals(it.toByteArray()) }
                            .map(::stripStartCode)
                        val sps = units.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 }
                            ?: error("Encoder H.264 não forneceu SPS")
                        val pps = units.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 }
                            ?: error("Encoder H.264 não forneceu PPS")
                        connectedRtmp.sendAvcSequenceHeader(sps, pps); sentConfig = true
                    } else if (index >= 0) {
                        if (sentConfig && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = ByteArray(info.size)
                            codec.getOutputBuffer(index)!!.apply { position(info.offset); limit(info.offset + info.size); get(data) }
                            connectedRtmp.sendVideo(splitNals(data), (info.presentationTimeUs / 1_000).toInt(), info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            sentFrames++
                            if (sentFrames == 1 || sentFrames % 150 == 0) {
                                onStatus("● ENVIANDO · 1280×720 · ${FPS} fps · AAC · $sentFrames quadros")
                            }
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
            audioWorker?.join(1_000); audioWorker = null
            runCatching { codec?.stop() }; runCatching { codec?.release() }; rtmp?.close(); activeClient = null
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        activeClient?.close()
        worker?.join(2_000)
        onStatus("Transmissão encerrada")
    }

    private fun argbToYuv420(bitmap: Bitmap, output: ByteBuffer, colorFormat: Int) {
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        output.clear()
        for (color in pixels) {
            val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
            val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)
            output.put(y.toByte())
        }
        val u = ByteArray(WIDTH * HEIGHT / 4)
        val v = ByteArray(WIDTH * HEIGHT / 4)
        var chromaIndex = 0
        for (y in 0 until HEIGHT step 2) for (x in 0 until WIDTH step 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val color = pixels[(y + dy) * WIDTH + x + dx]
                r += color shr 16 and 255; g += color shr 8 and 255; b += color and 255
            }
            val averageR = r / 4; val averageG = g / 4; val averageB = b / 4
            u[chromaIndex] = (((-38 * averageR - 74 * averageG + 112 * averageB + 128) shr 8) + 128).coerceIn(16, 240).toByte()
            v[chromaIndex] = (((112 * averageR - 94 * averageG - 18 * averageB + 128) shr 8) + 128).coerceIn(16, 240).toByte()
            chromaIndex++
        }
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            for (index in u.indices) { output.put(u[index]); output.put(v[index]) }
        } else {
            output.put(u); output.put(v)
        }
        output.flip()
    }

    private fun publishAudio(rtmp: RtmpClient, startedAt: Long) {
        var codec: MediaCodec? = null
        var recorder: AudioRecord? = null
        try {
            val minimum = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8_192),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microfone não pôde ser inicializado" }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8_192)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); recorder.startRecording()
            val info = MediaCodec.BufferInfo()
            val pcm = ByteArray(8_192)
            var sentConfig = false
            while (running.get()) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val count = recorder.read(pcm, 0, pcm.size)
                    if (count > 0) {
                        codec.getInputBuffer(inputIndex)!!.apply { clear(); put(pcm, 0, count) }
                        codec.queueInputBuffer(inputIndex, 0, count, (System.nanoTime() - startedAt) / 1_000, 0)
                    }
                }
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val config = codec.outputFormat.getByteBuffer("csd-0")?.toByteArray() ?: byteArrayOf(0x12, 0x08)
                        rtmp.sendAacSequenceHeader(config); sentConfig = true
                    } else if (outputIndex >= 0) {
                        if (sentConfig && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = ByteArray(info.size)
                            codec.getOutputBuffer(outputIndex)!!.apply { position(info.offset); limit(info.offset + info.size); get(data) }
                            rtmp.sendAudio(data, (info.presentationTimeUs / 1_000).toInt())
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    } else break
                }
            }
        } catch (error: Exception) {
            if (running.getAndSet(false)) onStatus("Falha no áudio: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { recorder?.stop() }; recorder?.release()
            runCatching { codec?.stop() }; codec?.release()
        }
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

    companion object {
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val FPS = 15
        private const val VIDEO_BITRATE = 3_000_000
        private const val AUDIO_RATE = 44_100
    }
}
