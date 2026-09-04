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
     * [AUTO] prefers a headset, else the loudspeaker; [SPEAKER] always the loudspeaker;
     * [EARPIECE] always the earpiece, the phone held to the ear like a call. Headsets are
     * ignored by the last two.
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

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var scoDevice: AudioDeviceInfo? = null
    private var bluetoothWanted = false

    /** True while a Bluetooth headset is the wanted route. */
    val bluetoothHeadset: Boolean get() = bluetoothWanted

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
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) commDeviceListener?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
        else try { context.unregisterReceiver(scoReceiver) } catch (e: Exception) { onStatus("Audio route: ${e.message}") }
        if (bluetoothWanted) { bluetoothWanted = false; onBluetoothHeadset?.invoke(false) }
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
        if (passive) return                                   // Telecom is routing; it reports the label itself
        val earpiece = headset == null && policy == Policy.EARPIECE
        val label = when {
            earpiece -> "Earpiece"
            headset == null -> "Speaker"
            bluetooth -> "Headset · " + headset.productName.toString().trim().ifEmpty { "Bluetooth" }
            else -> "Wired headset"
        }
        val changed = label != current
        current = label
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (headset != null) {
                    // The device list for communication may lag the output list by a moment; fall back to speaker if so.
                    val dev = audioManager.availableCommunicationDevices.firstOrNull { it.id == headset.id }
                        ?: audioManager.availableCommunicationDevices.firstOrNull { it.type == headset.type }
                    if (dev != null) audioManager.setCommunicationDevice(dev)
                    else { audioManager.clearCommunicationDevice(); current = "Speaker" }
                } else {
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
            return
        }
        if (announce && changed) onStatus("Audio: $current")
    }

    /** A Bluetooth headset first (it is the one you wear on deck), then anything plugged in. */
    private fun pickHeadset(outputs: Array<AudioDeviceInfo>): AudioDeviceInfo? =
        outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: outputs.firstOrNull { it.type in WIRED }

    private companion object {
        val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE
        )
    }
}
