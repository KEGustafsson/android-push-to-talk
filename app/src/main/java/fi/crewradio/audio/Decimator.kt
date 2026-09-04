package fi.crewradio.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Integer-factor sample-rate reducer: windowed-sinc low-pass, then keep every
 * [factor]-th sample. FIR history and phase carry across calls, so consecutive
 * frames join without clicks. Used to bring the platform Opus decoder's fixed
 * 48 kHz output down to the 16 kHz the mixer runs at.
 */
class Decimator(val factor: Int) {

    private val taps: FloatArray = design(factor)
    private val history = ShortArray(taps.size - 1)
    private var phase = 0

    /** Feeds PCM16 samples, returns roughly 1/[factor] as many. */
    fun process(input: ShortArray): ShortArray {
        val buf = ShortArray(history.size + input.size)
        System.arraycopy(history, 0, buf, 0, history.size)
        System.arraycopy(input, 0, buf, history.size, input.size)
        val out = ShortArray(input.size / factor + 1)
        var count = 0
        for (i in input.indices) {
            if (phase == 0) {
                val idx = history.size + i
                var acc = 0f
                for (k in taps.indices) acc += taps[k] * buf[idx - k]
                out[count++] = acc.roundToInt().coerceIn(-32768, 32767).toShort()
            }
            phase = (phase + 1) % factor
        }
        System.arraycopy(buf, buf.size - history.size, history, 0, history.size)
        return if (count == out.size) out else out.copyOf(count)
    }

    companion object {
        /** Hamming-windowed sinc, cutoff at 0.9x the new Nyquist, 8*factor+1 taps, unity DC gain. */
        fun design(factor: Int): FloatArray {
            val n = 8 * factor + 1
            val fc = 0.45 / factor
            val mid = (n - 1) / 2.0
            val h = DoubleArray(n) { k ->
                val x = k - mid
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                sinc * (0.54 - 0.46 * cos(2 * PI * k / (n - 1)))
            }
            val sum = h.sum()
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }
    }
}
