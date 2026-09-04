package fi.crewradio.audio

/**
 * Packet-loss concealment for one 20 ms slot: the sender's last good frame, repeated with
 * decaying gain. Speech is stationary enough over 20-60 ms that a faded repeat is far
 * less audible than the click of a silent slot; past [MAX_FRAMES] a repeat starts to
 * sound like a stutter, so it gives way to silence.
 *
 * Codec-agnostic on purpose: the platform Opus decoder cannot be asked for its own
 * concealment through MediaCodec (an empty input buffer yields empty output), and this
 * covers PCM senders too. Pure Kotlin, unit-tested.
 */
object Conceal {
    const val MAX_FRAMES = 3
    const val DECAY = 0.6f

    /**
     * The frame to play in place of the [n]th consecutive missing one (1-based), from the
     * last frame that did arrive; null once the gap is too long to paper over, or when
     * there is nothing to repeat yet.
     */
    fun frame(last: ByteArray?, n: Int): ByteArray? {
        if (last == null || n < 1 || n > MAX_FRAMES || last.size != AudioConfig.FRAME_BYTES) return null
        var gain = 1f
        repeat(n) { gain *= DECAY }
        val out = ByteArray(last.size)
        var i = 0
        while (i < last.size) {
            val lo = last[i].toInt() and 0xFF
            val hi = last[i + 1].toInt()
            val v = ((hi shl 8) or lo) * gain
            val s = v.toInt().coerceIn(-32768, 32767)
            out[i] = (s and 0xFF).toByte()
            out[i + 1] = (s shr 8).toByte()
            i += 2
        }
        return out
    }
}
