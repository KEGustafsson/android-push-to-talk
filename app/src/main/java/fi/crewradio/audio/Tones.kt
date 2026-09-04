package fi.crewradio.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Short cue tones, as 20 ms PCM frames for the mixer: the radio-style confirmation you
 * hear in the ear when a talk key keys or un-keys the mic, so the state is known without
 * looking at the phone. Pure Kotlin, unit-tested.
 */
object Tones {
    private const val RAMP_MS = 5          // fade in/out so a tone never clicks
    private const val GAIN = 0.25f         // well under full scale; mixed on top of speech

    /** One rising note: the mic is live. */
    fun micOn(): List<ByteArray> = frames(beep(880.0, 80))

    /** Two short low notes: the mic is off. */
    fun micOff(): List<ByteArray> = frames(beep(587.0, 60) + silence(40) + beep(587.0, 60))

    /** A sine [hz] for [ms], ramped at both ends. */
    fun beep(hz: Double, ms: Int): ShortArray {
        val n = AudioConfig.SAMPLE_RATE * ms / 1000
        val ramp = AudioConfig.SAMPLE_RATE * RAMP_MS / 1000
        val out = ShortArray(n)
        for (i in 0 until n) {
            val env = min(1f, min(i, n - 1 - i).toFloat() / ramp)
            out[i] = (sin(2 * PI * hz * i / AudioConfig.SAMPLE_RATE) * env * GAIN * 32767).toInt().toShort()
        }
        return out
    }

    fun silence(ms: Int) = ShortArray(AudioConfig.SAMPLE_RATE * ms / 1000)

    /** Chops samples into whole frames, the last one padded with silence. */
    fun frames(samples: ShortArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < samples.size) {
            val f = ByteArray(AudioConfig.FRAME_BYTES)
            var j = 0
            while (j < AudioConfig.FRAME_SAMPLES && i + j < samples.size) {
                val s = samples[i + j].toInt()
                f[2 * j] = (s and 0xFF).toByte()
                f[2 * j + 1] = (s shr 8).toByte()
                j++
            }
            out.add(f)
            i += AudioConfig.FRAME_SAMPLES
        }
        return out
    }
}
