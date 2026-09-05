package fi.crewradio

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi

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
        if (!CallBridge.attached(c)) {
            // The engine gave up on this placement (headset gone, session ended) before Telecom answered.
            c.setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
            c.destroy()
            return c
        }
        c.setActive()
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

        /** Telecom's route below API 34; from 34 the endpoint callbacks below carry the same and this is left alone. */
        @Deprecated("Telecom reports CallEndpoints from API 34")
        @Suppress("DEPRECATION")
        override fun onCallAudioStateChanged(state: CallAudioState) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
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

        // API 34+: the same in terms of CallEndpoint. Telecom tells us what it can route to and
        // where it is; the policy asks for a change when it wants another of the available ones.
        @Volatile private var endpoints: List<CallEndpoint> = emptyList()
        private val mainHandler = Handler(Looper.getMainLooper())

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onAvailableCallEndpointsChanged(available: List<CallEndpoint>) {
            endpoints = available
            steer(currentCallEndpoint)
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onCallEndpointChanged(endpoint: CallEndpoint) {
            CallBridge.listener?.onAudioRoute(label(endpoint))
            steer(endpoint)
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun steer(current: CallEndpoint?) {
            val wanted = CallBridge.wantedEndpoint(endpoints) ?: return
            if (current?.endpointType == wanted.endpointType) return
            requestCallEndpointChange(wanted, { r -> mainHandler.post(r) }, object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) = Unit
                override fun onError(error: CallEndpointException) = Unit   // Telecom keeps its route and reports it above
            })
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun label(e: CallEndpoint): String = when (e.endpointType) {
            CallEndpoint.TYPE_BLUETOOTH -> "Headset · " + e.endpointName.toString().trim().ifEmpty { "Bluetooth" }
            CallEndpoint.TYPE_WIRED_HEADSET -> "Wired headset"
            CallEndpoint.TYPE_SPEAKER -> "Speaker"
            CallEndpoint.TYPE_EARPIECE -> "Earpiece"
            else -> "Streaming"
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
    /** The route policy while Telecom is routing: null = headset first, else the given CallAudioState route. */
    @Volatile var forcedRoute: Int? = null
    @Volatile private var connection: CallService.ChannelConnection? = null
    @Volatile private var placing = false

    val active: Boolean get() = connection != null

    /** The route the policy wants (below API 34), or null to leave Telecom's choice alone. */
    @Suppress("DEPRECATION")
    fun wantedRoute(state: CallAudioState): Int? {
        val mask = state.supportedRouteMask
        forcedRoute?.let { return if ((mask and it) != 0) it else null }
        return when {
            (mask and CallAudioState.ROUTE_BLUETOOTH) != 0 -> CallAudioState.ROUTE_BLUETOOTH
            (mask and CallAudioState.ROUTE_WIRED_HEADSET) != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
            (mask and CallAudioState.ROUTE_SPEAKER) != 0 -> CallAudioState.ROUTE_SPEAKER   // never the earpiece
            else -> null
        }
    }

    /** The endpoint the policy wants among [available] (API 34+), or null to leave Telecom's choice alone. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun wantedEndpoint(available: List<CallEndpoint>): CallEndpoint? {
        fun of(type: Int) = available.firstOrNull { it.endpointType == type }
        forcedRoute?.let { return of(endpointType(it)) }
        return of(CallEndpoint.TYPE_BLUETOOTH) ?: of(CallEndpoint.TYPE_WIRED_HEADSET) ?: of(CallEndpoint.TYPE_SPEAKER)   // never the earpiece
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun endpointType(route: Int): Int = when (route) {
        CallAudioState.ROUTE_SPEAKER -> CallEndpoint.TYPE_SPEAKER
        CallAudioState.ROUTE_EARPIECE -> CallEndpoint.TYPE_EARPIECE
        CallAudioState.ROUTE_WIRED_HEADSET -> CallEndpoint.TYPE_WIRED_HEADSET
        else -> CallEndpoint.TYPE_BLUETOOTH
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

    /** Ends the call, or cancels a placement Telecom has not answered yet: its connection is refused in [attached]. */
    fun stop() {
        placing = false
        connection?.end(DisconnectCause.LOCAL)
    }

    /** The connection Telecom created for the current placement; false if that placement was cancelled meanwhile. */
    internal fun attached(c: CallService.ChannelConnection): Boolean {
        if (!placing) return false
        placing = false
        connection?.takeIf { it !== c }?.end(DisconnectCause.LOCAL)
        connection = c
        listener?.onCallActive()
        return true
    }

    internal fun detached(c: CallService.ChannelConnection) {
        if (connection === c) { connection = null; listener?.onCallEnded(null) }
    }

    internal fun failed(reason: String) {
        placing = false
        listener?.onCallEnded(reason)
    }
}
