package fi.crewradio.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Where the voice goes: a Bluetooth headset over SCO when one is connected, a wired or USB
 * headset when plugged in, otherwise the speakerphone. Follows headsets as they come and
 * go while the session lasts, and reports each change on the status line.
 *
 * VOICE_COMMUNICATION capture and playback follow the "communication device", so both the
 * mic and the ear end up on the headset. API 31+ has [AudioManager.setCommunicationDevice]
 * for that; older phones get the same through speakerphone and SCO switches.
 *
 * While a Bluetooth headset is present the engine hands routing to Telecom (see
 * `CallService`), which owns the route for as long as that call lasts; this class then
 * goes [passive] and only carries the label. [onBluetoothHeadset] tells the engine when to
 * start and end that call.
 *
 * A headset's SCO link is narrow: 8 or 16 kHz mono, so it carries this app's 16 kHz voice as it is.
 */
class AudioRoute(private val context: Context, private val onStatus: (String) -> Unit) {

    /**
     * [AUTO] prefers a headset, else the earpiece while the phone is at the ear and the
     * loudspeaker otherwise; [SPEAKER] always the loudspeaker; [EARPIECE] always the earpiece.
     * Headsets are ignored by the last two.
     */
    enum class Policy { AUTO, SPEAKER, EARPIECE }

    @Volatile var policy = Policy.AUTO
        set(value) { field = value; if (active) apply(announce = true) }

    /** Short description of the route in use, for the Status screen. */
    @Volatile var current: String = "Speaker"

    /** True while Telecom routes the call; then this class touches no device. */
    @Volatile var passive = false

    /** Called (on the main thread) when a Bluetooth headset becomes, or stops being, the wanted route. */
    var onBluetoothHeadset: ((Boolean) -> Unit)? = null

    /**
     * Called (on the main thread) whenever [headset] or [bluetoothHeadset] changed: the route in
     * use, not the wanted one, so a consumer that tunes itself to the mic (the voice monitor)
     * follows what the mic really is, also while a headset is still being retried.
     */
    var onHeadsetChanged: (() -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var scoDevice: AudioDeviceInfo? = null
    private var bluetoothWanted = false

    /** True while a Bluetooth headset is present and preferred, whether or not the switch to it has succeeded yet. */
    val bluetoothPresent: Boolean get() = bluetoothWanted

    /** True while a Bluetooth headset is the route in use (or Telecom's, while [passive]). */
    @Volatile var bluetoothHeadset = false
        private set

    /** True while any headset (Bluetooth, wired, USB) is the route in use. */
    @Volatile var headset = false
        private set

    /** The phone is at the ear (proximity sensor): in [Policy.AUTO] that means the earpiece, as in a call. Set by the engine. */
    @Volatile var atEar = false
        set(value) { if (field != value) { field = value; if (active) apply(announce = true) } }

    /**
     * A hands-free headset that thinks it is in a call answers its own button with a hang-up,
     * and with no call to hang up the phone drops the SCO link instead. Nothing re-opens it
     * unless we ask again, so the loss of the link is watched and the route re-applied.
     */
    private val commDeviceListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        AudioManager.OnCommunicationDeviceChangedListener { dev ->
            if (active && !passive && bluetoothWanted && dev?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) heal()
        } else null

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            if (active && !passive && bluetoothWanted && st == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) heal()
        }
    }

    private val healRunnable = Runnable { if (active && !passive && bluetoothWanted) { scoDevice = null; apply(announce = false) } }

    private fun heal() {
        handler.removeCallbacks(healRunnable)
        handler.postDelayed(healRunnable, 700)     // let the stack finish tearing the link down first
    }

    /**
     * A headset that has just connected shows up among the outputs a moment before the platform
     * lets it be the communication device, and [AudioManager.setCommunicationDevice] can refuse
     * it meanwhile. Rather than sit on the speaker until the next device event, try again a few
     * times; the count starts over whenever a route applies.
     */
    private var retries = 0
    private val retryRunnable = Runnable { if (active && !passive) apply(announce = true) }

    private fun retryLater() {
        if (retries >= MAX_RETRIES) return
        retries++
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, RETRY_MS)
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { if (active) apply(announce = true) }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { if (active) apply(announce = true) }
    }

    fun start() {
        if (active) return
        active = true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commDeviceListener?.let { audioManager.addOnCommunicationDeviceChangedListener({ r -> handler.post(r) }, it) }
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        }
        apply(announce = false)
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(healRunnable)
        handler.removeCallbacks(retryRunnable)
        retries = 0
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) commDeviceListener?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
        else try { context.unregisterReceiver(scoReceiver) } catch (e: Exception) { onStatus("Audio route: ${e.message}") }
        if (bluetoothWanted) { bluetoothWanted = false; onBluetoothHeadset?.invoke(false) }
        headset = false
        bluetoothHeadset = false
        passive = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (scoDevice != null) { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
        } catch (e: Exception) {
            onStatus("Audio route reset failed: ${e.message}")     // the old route may linger; teardown goes on
        }
        scoDevice = null
        audioManager.mode = AudioManager.MODE_NORMAL
        current = "Speaker"
    }

    /** Re-evaluates the route, e.g. when Telecom hands it back. */
    fun reapply() { if (active) apply(announce = true) }

    /** Picks the best available device under [policy] and switches to it if it is not the one in use. */
    @Synchronized
    private fun apply(announce: Boolean) {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headset = if (policy == Policy.AUTO) pickHeadset(outputs) else null
        val bluetooth = headset?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        if (bluetooth != bluetoothWanted) {
            bluetoothWanted = bluetooth
            handler.post { onBluetoothHeadset?.invoke(bluetooth) }
        }
        var applied = headset != null                         // false once the switch is refused: the phone's mic and ear until the retry
        if (!passive) {                                       // Telecom is routing; it reports the label itself
            val before = current
            val earpiece = headset == null && (policy == Policy.EARPIECE || (policy == Policy.AUTO && atEar))
            current = when {
                earpiece -> "Earpiece"
                headset == null -> "Speaker"
                bluetooth -> "Headset · " + headset.productName.toString().trim().ifEmpty { "Bluetooth" }
                else -> "Wired headset"
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (headset != null) {
                        // The communication device list may lag the output list by a moment, and the
                        // switch itself can be refused while the link comes up: speaker for now, then retry.
                        val dev = audioManager.availableCommunicationDevices.firstOrNull { it.id == headset.id }
                            ?: audioManager.availableCommunicationDevices.firstOrNull { it.type == headset.type }
                        if (dev != null && audioManager.setCommunicationDevice(dev)) {
                            retries = 0
                        } else {
                            audioManager.clearCommunicationDevice()
                            current = "Speaker"
                            applied = false
                            retryLater()
                        }
                    } else {
                        retries = 0
                        audioManager.clearCommunicationDevice()
                        val type = if (earpiece) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
                            ?.let { audioManager.setCommunicationDevice(it) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    when {
                        headset == null -> {   // speakerphone on, or off for the earpiece
                            if (scoDevice != null) { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false; scoDevice = null }
                            audioManager.isSpeakerphoneOn = !earpiece
                        }
                        bluetooth -> {
                            audioManager.isSpeakerphoneOn = false
                            if (scoDevice?.id != headset.id) {
                                audioManager.startBluetoothSco()
                                audioManager.isBluetoothScoOn = true
                                scoDevice = headset
                            }
                        }
                        else -> {   // wired: the platform routes to it once the speakerphone is off
                            if (scoDevice != null) { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false; scoDevice = null }
                            audioManager.isSpeakerphoneOn = false
                        }
                    }
                }
            } catch (e: Exception) {
                onStatus("Audio route failed: ${e.message}")
                applied = false
                current = before
            }
            if (announce && current != before) onStatus("Audio: $current")
        }
        routed(applied, applied && bluetooth)
    }

    /** Records the route in use and tells the engine when it changed. */
    private fun routed(headset: Boolean, bluetooth: Boolean) {
        if (headset == this.headset && bluetooth == bluetoothHeadset) return
        this.headset = headset
        bluetoothHeadset = bluetooth
        handler.post { onHeadsetChanged?.invoke() }
    }

    /** A Bluetooth headset first (it is the one you wear on deck), then anything plugged in. */
    private fun pickHeadset(outputs: Array<AudioDeviceInfo>): AudioDeviceInfo? =
        outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: outputs.firstOrNull { it.type in WIRED }

    private companion object {
        const val MAX_RETRIES = 6
        const val RETRY_MS = 700L
        val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE
        )
    }
}
