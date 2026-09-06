package fi.crewradio.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * The phone's own call volume, which is what the session plays on: the [AudioPlayback] track has
 * `USAGE_VOICE_COMMUNICATION`, so it sits on the voice-call stream, the one the volume keys move
 * during a phone call and a Bluetooth headset's buttons move over HFP. The main screen's slider
 * sets it directly, because on channel the volume keys are a talk key and cannot.
 *
 * The stream has a floor (1 on every phone seen) and cannot be muted by an app, so the true mute
 * stays in the [Mixer]. Until Android 13 a Bluetooth headset's SCO link has a stream of its own
 * (`STREAM_BLUETOOTH_SCO`, hidden); Android 14 folded it into the voice-call stream.
 */
class CallVolume(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The stream in use: the voice-call stream, or the SCO stream while a Bluetooth headset carries the call on Android 13 and below. */
    fun stream(bluetoothHeadset: Boolean): Int =
        if (bluetoothHeadset && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) STREAM_BLUETOOTH_SCO else AudioManager.STREAM_VOICE_CALL

    fun min(stream: Int): Int = try { am.getStreamMinVolume(stream) } catch (_: Exception) { 0 }
    fun max(stream: Int): Int = try { am.getStreamMaxVolume(stream) } catch (_: Exception) { 1 }
    fun get(stream: Int): Int = try { am.getStreamVolume(stream) } catch (_: Exception) { 0 }

    /** Sets the level silently (no system volume panel); clamped to the stream's range. */
    fun set(stream: Int, index: Int) {
        try { am.setStreamVolume(stream, index.coerceIn(min(stream), max(stream)), 0) } catch (_: Exception) {}
    }

    companion object {
        /** `AudioManager.STREAM_BLUETOOTH_SCO`, hidden in the SDK; the value has never changed. */
        const val STREAM_BLUETOOTH_SCO = 6
        /** Sent by the system when any stream's level changes (a headset button, the phone's own panel). Not in the SDK; used by every volume widget. */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
