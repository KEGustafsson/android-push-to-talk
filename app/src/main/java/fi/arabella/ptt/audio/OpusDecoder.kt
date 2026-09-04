package fi.arabella.ptt.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One Opus decoder per remote sender, on top of the platform MediaCodec.
 *
 * All phones encode with the same parameters, so instead of shipping codec
 * config over the air the decoder synthesises the OpusHead itself. The AOSP
 * decoder always outputs 48 kHz, so decoded audio is decimated back to the
 * mixer's 16 kHz and re-framed into exact 20 ms frames.
 *
 * Not thread-safe: the engine serialises calls per decoder.
 */
class OpusDecoder {

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var outputRate = 0
    private var decimator: Decimator? = null

    // Decoded 16 kHz samples waiting to be cut into whole frames.
    private var pending = ShortArray(AudioConfig.FRAME_SAMPLES * 8)
    private var pendingLen = 0

    /** Last time a packet was fed in; the engine releases decoders that go quiet. */
    @Volatile var lastUsedNs: Long = System.nanoTime()
        private set

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioConfig.SAMPLE_RATE, 1)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(channels = 1, inputRate = AudioConfig.SAMPLE_RATE)))
        fmt.setByteBuffer("csd-1", ByteBuffer.wrap(ByteArray(8)))   // pre-skip, ns
        fmt.setByteBuffer("csd-2", ByteBuffer.wrap(ByteArray(8)))   // seek pre-roll, ns
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /** Decodes one packet; calls [onFrame] with each complete 640-byte 16 kHz PCM16 frame produced. */
    fun decode(packet: ByteArray, offset: Int, length: Int, onFrame: (ByteArray) -> Unit) {
        lastUsedNs = System.nanoTime()
        val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (idx >= 0) {
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(packet, offset, length)
            codec.queueInputBuffer(idx, 0, length, ptsUs, 0)
            ptsUs += AudioConfig.FRAME_MS * 1000L
        }
        drain(onFrame)
    }

    private fun drain(onFrame: (ByteArray) -> Unit) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                idx < 0 -> {}
                else -> {
                    val out = codec.getOutputBuffer(idx)
                    if (out != null && info.size > 0) {
                        if (outputRate == 0) onFormat(codec.getOutputFormat(idx))
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val samples = ShortArray(info.size / 2)
                        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        append(decimator?.process(samples) ?: samples)
                        emitFrames(onFrame)
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    private fun onFormat(fmt: MediaFormat) {
        val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (channels != 1 || rate % AudioConfig.SAMPLE_RATE != 0) {
            throw IllegalStateException("unsupported decoder output $rate Hz x$channels")
        }
        outputRate = rate
        val factor = rate / AudioConfig.SAMPLE_RATE
        decimator = if (factor > 1) Decimator(factor) else null
    }

    private fun append(samples: ShortArray) {
        if (pendingLen + samples.size > pending.size) pending = pending.copyOf((pendingLen + samples.size) * 2)
        System.arraycopy(samples, 0, pending, pendingLen, samples.size)
        pendingLen += samples.size
    }

    private fun emitFrames(onFrame: (ByteArray) -> Unit) {
        var start = 0
        while (pendingLen - start >= AudioConfig.FRAME_SAMPLES) {
            val frame = ByteArray(AudioConfig.FRAME_BYTES)
            ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                .put(pending, start, AudioConfig.FRAME_SAMPLES)
            onFrame(frame)
            start += AudioConfig.FRAME_SAMPLES
        }
        if (start > 0) {
            System.arraycopy(pending, start, pending, 0, pendingLen - start)
            pendingLen -= start
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 20_000L

        /** RFC 7845 identification header, mapping family 0, no pre-skip, no gain. */
        fun opusHead(channels: Int, inputRate: Int): ByteArray =
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray(Charsets.US_ASCII))
                .put(1)                       // version
                .put(channels.toByte())
                .putShort(0)                  // pre-skip (samples at 48 kHz)
                .putInt(inputRate)
                .putShort(0)                  // output gain, Q7.8 dB
                .put(0)                       // channel mapping family
                .array()
    }
}
