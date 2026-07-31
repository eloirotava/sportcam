package dev.cascam.stream

import dev.cascam.config.VideoCodec

interface MediaTransport : AutoCloseable {
    fun connect()
    fun sendMetadata(width: Int, height: Int, frameRate: Int, videoBitrate: Int)
    fun sendVideoConfig(codec: VideoCodec, vps: ByteArray?, sps: ByteArray, pps: ByteArray)
    fun sendVideo(nals: List<ByteArray>, timestampMs: Int, keyFrame: Boolean)
    fun sendAacSequenceHeader(config: ByteArray)
    fun sendAudio(data: ByteArray, timestampMs: Int)
}

class RtmpTransport(private val client: RtmpClient) : MediaTransport {
    override fun connect() = client.connect()
    override fun sendMetadata(width: Int, height: Int, frameRate: Int, videoBitrate: Int) = client.sendMetadata(width, height, frameRate, videoBitrate)
    override fun sendVideoConfig(codec: VideoCodec, vps: ByteArray?, sps: ByteArray, pps: ByteArray) {
        require(codec == VideoCodec.H264) { "RTMPS suporta somente H.264 neste aplicativo" }
        client.sendAvcSequenceHeader(sps, pps)
    }
    override fun sendVideo(nals: List<ByteArray>, timestampMs: Int, keyFrame: Boolean) = client.sendVideo(nals, timestampMs, keyFrame)
    override fun sendAacSequenceHeader(config: ByteArray) = client.sendAacSequenceHeader(config)
    override fun sendAudio(data: ByteArray, timestampMs: Int) = client.sendAudio(data, timestampMs)
    override fun close() = client.close()
}
