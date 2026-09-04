package fi.arabella.ptt.audio

import android.media.MediaCodec
import android.media.MediaFormat

/**
 * Opus encoder on top of the platform MediaCodec (`audio/opus`, an AOSP
 * software codec since Android 10), so no native library is needed.
 *
 * Feed it 20 ms PCM16 frames from the capture thread; every finished Opus
 * packet comes back through [onPacket] on the same thread. The codec is run
 * synchronously with a short input timeout, so a stalled encoder drops a frame
 * instead of blocking the mic.
 */
class OpusEncoder(private val onPacket: (ByteArray) -> Unit) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioConfig.SAMPLE_RATE, 1)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, AudioConfig.OPUS_BITRATE)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /** Queues one 20 ms frame and emits whatever packets the encoder has finished. */
    fun encode(pcm: ByteArray) {
        val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (idx >= 0) {
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(pcm)
            codec.queueInputBuffer(idx, 0, pcm.size, ptsUs, 0)
            ptsUs += AudioConfig.FRAME_MS * 1000L
        }
        drain()
    }

    /** Hands every finished packet to [onPacket]; codec-config output is skipped, receivers synthesise their own. */
    private fun drain() {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (idx < 0) continue                       // format / buffers changed: nothing to read
            val out = codec.getOutputBuffer(idx)
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (out != null && info.size > 0 && !isConfig) {
                val bytes = ByteArray(info.size)
                out.position(info.offset)
                out.get(bytes)
                onPacket(bytes)
            }
            codec.releaseOutputBuffer(idx, false)
        }
    }

    /** Stops and frees the MediaCodec; the encoder cannot be reused afterwards. */
    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 20_000L
    }
}
