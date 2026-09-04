package fi.crewradio

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * The channel as a self-managed call, for the sake of Bluetooth headsets.
 *
 * With its SCO link up, a hands-free headset believes the phone is in a call, so its button
 * sends a hang-up (HFP AT+CHUP), never a media key; with no call to hang up, Android drops
 * the SCO link instead, and the next press arrives as a media key. Erratic. Registering the
 * session as a self-managed call makes it a real call to HFP: SCO stays up, and the hang-up
 * request reaches [ChannelConnection.onDisconnect], which keys the mic instead of hanging up.
 *
 * Telecom then also owns the audio route (Bluetooth, wired, speaker) for as long as the call
 * lasts; [ChannelConnection.onCallAudioStateChanged] reports it and applies the speaker
 * policy. The engine places the call only while a Bluetooth headset is the route, so
 * without one nothing changes: no call, and the phone's volume keys keep keying the mic.
 */
class CallService : ConnectionService() {

    override fun onCreateOutgoingConnection(from: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val c = ChannelConnection()
        c.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        c.connectionCapabilities = Connection.CAPABILITY_HOLD or Connection.CAPABILITY_SUPPORT_HOLD or Connection.CAPABILITY_MUTE
        c.audioModeIsVoip = true
        c.setAddress(request?.address ?: CallBridge.ADDRESS, TelecomManager.PRESENTATION_ALLOWED)
        c.setCallerDisplayName("Crew Radio", TelecomManager.PRESENTATION_ALLOWED)
        c.setActive()
        CallBridge.attached(c)
        return c
    }

    override fun onCreateOutgoingConnectionFailed(from: PhoneAccountHandle?, request: ConnectionRequest?) {
        CallBridge.failed("Headset call refused by the phone")
    }

    /** One channel session as Telecom sees it. Callbacks arrive on the main thread. */
    class ChannelConnection : Connection() {

        /** The headset button (HFP hang-up): a talk key, not the end of the session. */
        override fun onDisconnect() { CallBridge.listener?.onHeadsetButton() }

        /** The system insists (an emergency call, say): end for real. */
        override fun onAbort() { end(DisconnectCause.LOCAL) }

        /** A phone call was answered: the channel waits, muted both ways, until it ends. */
        override fun onHold() { setOnHold(); CallBridge.listener?.onHold(true) }
        override fun onUnhold() { setActive(); CallBridge.listener?.onHold(false) }

        override fun onCallAudioStateChanged(state: CallAudioState) {
            val label = when (state.route) {
                CallAudioState.ROUTE_BLUETOOTH -> "Headset · " + bluetoothName(state)
                CallAudioState.ROUTE_WIRED_HEADSET -> "Wired headset"
                CallAudioState.ROUTE_SPEAKER -> "Speaker"
                else -> "Earpiece"
            }
            val wanted = CallBridge.wantedRoute(state)
            if (wanted != null && wanted != state.route) setAudioRoute(wanted)
            CallBridge.listener?.onAudioRoute(label)
        }

        private fun bluetoothName(state: CallAudioState): String =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) state.activeBluetoothDevice?.let { dev ->
                try { dev.name } catch (_: SecurityException) { null }
            } else null)?.takeIf { it.isNotBlank() } ?: "Bluetooth"

        fun end(cause: Int) {
            setDisconnected(DisconnectCause(cause))
            destroy()
            CallBridge.detached(this)
        }
    }
}

/**
 * Glue between the engine and the system-created [CallService.ChannelConnection]: places
 * and ends the call, and forwards its events to whoever is listening (the engine).
 */
object CallBridge {
    interface Listener {
        fun onCallActive()
        fun onCallEnded(reason: String?)
        fun onHeadsetButton()
        fun onHold(held: Boolean)
        fun onAudioRoute(label: String)
    }

    val ADDRESS: Uri = Uri.fromParts("crewradio", "channel", null)
    private const val ACCOUNT_ID = "crewradio"

    @Volatile var listener: Listener? = null
    @Volatile var speakerOnly = false
    @Volatile private var connection: CallService.ChannelConnection? = null
    @Volatile private var placing = false

    val active: Boolean get() = connection != null

    /** The route the policy wants, or null to leave Telecom's choice alone. */
    fun wantedRoute(state: CallAudioState): Int? {
        val mask = state.supportedRouteMask
        if (speakerOnly) return if (mask and CallAudioState.ROUTE_SPEAKER != 0) CallAudioState.ROUTE_SPEAKER else null
        return when {
            mask and CallAudioState.ROUTE_BLUETOOTH != 0 -> CallAudioState.ROUTE_BLUETOOTH
            mask and CallAudioState.ROUTE_WIRED_HEADSET != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
            mask and CallAudioState.ROUTE_SPEAKER != 0 -> CallAudioState.ROUTE_SPEAKER   // never the earpiece
            else -> null
        }
    }

    /** Places the call; [Listener.onCallActive] or [Listener.onCallEnded] follows on the main thread. */
    fun start(context: Context): Boolean {
        if (connection != null || placing) return true
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)) return false
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = PhoneAccountHandle(ComponentName(context, CallService::class.java), ACCOUNT_ID)
        return try {
            tm.registerPhoneAccount(
                PhoneAccount.builder(handle, "Crew Radio")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(ADDRESS.scheme)
                    .build()
            )
            if (!tm.isOutgoingCallPermitted(handle)) return false      // a phone call is in progress
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) }
            placing = true
            tm.placeCall(ADDRESS, extras)
            true
        } catch (e: Exception) {
            placing = false
            listener?.onCallEnded("Headset call failed: ${e.message}")
            false
        }
    }

    fun stop() {
        placing = false
        connection?.end(DisconnectCause.LOCAL)
    }

    internal fun attached(c: CallService.ChannelConnection) {
        placing = false
        connection?.takeIf { it !== c }?.end(DisconnectCause.LOCAL)
        connection = c
        listener?.onCallActive()
    }

    internal fun detached(c: CallService.ChannelConnection) {
        if (connection === c) { connection = null; listener?.onCallEnded(null) }
    }

    internal fun failed(reason: String) {
        placing = false
        listener?.onCallEnded(reason)
    }
}
