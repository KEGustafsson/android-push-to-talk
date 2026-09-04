package fi.crewradio.audio

import kotlin.math.sqrt

/**
 * Voice-operated keying (VOX) for a headset: speech opens the gate, a stretch of quiet
 * closes it. Pure Kotlin, unit-tested.
 *
 * Measured on a Jabra Evolve2 65 over SCO: the headset's own noise suppression leaves about
 * 2 RMS (16-bit scale) when the wearer is quiet, whether muted or not, with spikes to ~20
 * about once a second; speech runs 200-1900. So the level cannot tell muted from quiet, and
 * mute is not the key; speech is. [openRms] sits well above the spikes and needs [ATTACK_FRAMES]
 * in a row, [HANG_FRAMES] of quiet close the gate so a pause between words stays on air.
 */
class MicGate {
    enum class Change { OPEN, CLOSE }

    /** RMS a frame must exceed, [ATTACK_FRAMES] in a row, to open. */
    var openRms = HEADSET_OPEN
    /** RMS below which a frame counts as quiet while open. */
    var closeRms = HEADSET_CLOSE

    /** Thresholds for the mic in use: a noise-suppressed headset boom, or the phone's own mic held to the mouth. */
    fun tune(phoneMic: Boolean) {
        openRms = if (phoneMic) PHONE_OPEN else HEADSET_OPEN
        closeRms = if (phoneMic) PHONE_CLOSE else HEADSET_CLOSE
    }

    var open = false
        private set
    private var loudFrames = 0
    private var quietFrames = 0

    /** Feeds one 20 ms frame and reports a transition, if this frame caused one. */
    fun feed(frame: ByteArray): Change? = feed(rms(frame))

    fun feed(rms: Double): Change? {
        if (open) {
            if (rms < closeRms) {
                if (++quietFrames >= HANG_FRAMES) { open = false; quietFrames = 0; loudFrames = 0; return Change.CLOSE }
            } else quietFrames = 0
        } else {
            if (rms > openRms) {
                if (++loudFrames >= ATTACK_FRAMES) { open = true; loudFrames = 0; quietFrames = 0; return Change.OPEN }
            } else loudFrames = 0
        }
        return null
    }

    fun reset() { open = false; loudFrames = 0; quietFrames = 0 }

    companion object {
        /** 40 ms of speech opens: a spike does not. */
        const val ATTACK_FRAMES = 2
        /** 1.5 s of quiet closes: a pause between words does not. */
        const val HANG_FRAMES = 75
        /** A headset boom with its own noise suppression: ~2 RMS quiet, ~20 spikes, speech 200-1900. */
        const val HEADSET_OPEN = 80.0
        const val HEADSET_CLOSE = 40.0
        /**
         * The phone's own mic through the voice-call path (gain levelled): close talk peaks
         * 400-1150 per half second, a quiet table 0-190. Armed only at the ear, see the engine.
         */
        const val PHONE_OPEN = 300.0
        const val PHONE_CLOSE = 120.0

        fun rms(frame: ByteArray): Double {
            var acc = 0.0
            var i = 0
            var n = 0
            while (i + 1 < frame.size) {
                val s = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toDouble()
                acc += s * s
                i += 2
                n++
            }
            return if (n == 0) 0.0 else sqrt(acc / n)
        }
    }
}
