package fi.arabella.ptt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import fi.arabella.ptt.transport.BluetoothTransport
import fi.arabella.ptt.transport.LanTransport
import fi.arabella.ptt.transport.Transport
import fi.arabella.ptt.transport.WifiAwareTransport

/**
 * Thin UI over [PttService]. The activity binds while visible and mirrors the
 * service's engine state, so rotating, backgrounding or reopening the app never
 * interrupts a running session.
 */
class MainActivity : AppCompatActivity() {

    private var service: PttService? = null
    private val engine: PttEngine? get() = service?.engine

    private lateinit var status: TextView
    private lateinit var pttButton: Button
    private lateinit var connectButton: Button
    private lateinit var btSpinner: Spinner
    private lateinit var checkLan: CheckBox
    private lateinit var checkBt: CheckBox
    private lateinit var checkAware: CheckBox
    private lateinit var duplexSwitch: SwitchMaterial
    private lateinit var relaySwitch: SwitchMaterial
    private lateinit var opusSwitch: SwitchMaterial
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private val connection = object : ServiceConnection {
        /**
         * Adopts the service's engine. A running session is the source of truth for the
         * switches; an idle engine only has defaults, so anything the user flipped before
         * the bind completed is pushed into it instead of being overwritten.
         */
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PttService.LocalBinder).service
            service = s
            s.statusListener = { msg -> runOnUiThread { status.text = msg; syncUi() } }
            status.text = s.lastStatus
            if (!s.engine.isConnected) applyUiToEngine(s.engine)
            syncUi()
        }

        /** Only reached if the service process dies; controls become no-ops until rebound. */
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    /** Wires the widgets; everything that needs the engine goes through [service], which arrives on bind. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = findViewById(R.id.status)
        pttButton = findViewById(R.id.pttButton)
        connectButton = findViewById(R.id.connectButton)
        btSpinner = findViewById(R.id.btDevices)
        checkLan = findViewById(R.id.checkLan)
        checkBt = findViewById(R.id.checkBt)
        checkAware = findViewById(R.id.checkAware)
        duplexSwitch = findViewById(R.id.duplexSwitch)
        relaySwitch = findViewById(R.id.relaySwitch)
        opusSwitch = findViewById(R.id.opusSwitch)

        checkBt.setOnCheckedChangeListener { _, on ->
            btSpinner.visibility = if (on) View.VISIBLE else View.GONE
            if (on) loadPairedDevices()
        }
        duplexSwitch.setOnCheckedChangeListener { _, on ->
            engine?.mode = if (on) PttEngine.Mode.FULL_DUPLEX else PttEngine.Mode.HALF_DUPLEX
            refreshPttLabel()
        }
        relaySwitch.isChecked = true
        relaySwitch.setOnCheckedChangeListener { _, on -> engine?.relay = on }
        opusSwitch.isChecked = true
        opusSwitch.setOnCheckedChangeListener { _, on ->
            engine?.codec = if (on) Packet.Codec.OPUS else Packet.Codec.PCM
        }

        connectButton.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.engine.isConnected) {
                s.disconnect()
                syncUi()
            } else if (hasPermissions()) {
                connect(s)
            } else {
                requestPermissions()
            }
        }

        pttButton.setOnTouchListener { _, ev ->
            val e = engine ?: return@setOnTouchListener false
            if (e.mode == PttEngine.Mode.HALF_DUPLEX) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> e.startTalking()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> e.stopTalking()
                }
                true
            } else false
        }
        pttButton.setOnClickListener {
            val e = engine ?: return@setOnClickListener
            if (e.mode == PttEngine.Mode.FULL_DUPLEX) e.toggleTalking()
        }

        requestPermissions()
        refreshPttLabel()
    }

    /** Binds while visible; BIND_AUTO_CREATE means the service (and its senderId) exists whenever the UI is up. */
    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** Unbinds without touching the session: a connected service keeps running as a started foreground service. */
    override fun onStop() {
        service?.statusListener = null
        service = null
        unbindService(connection)   // the service keeps running while connected; see PttService
        super.onStop()
    }

    /** Builds the selected transports and hands them to the service. */
    private fun connect(s: PttService) {
        val ctx = applicationContext
        val list = mutableListOf<Transport>()
        if (checkLan.isChecked) list += LanTransport(ctx)
        if (checkBt.isChecked) {
            val peer = pairedDevices.getOrNull(btSpinner.selectedItemPosition - 1) // 0 = listen only
            list += BluetoothTransport(ctx, peer)
        }
        if (checkAware.isChecked) list += WifiAwareTransport(ctx, s.engine.senderId)
        if (list.isEmpty()) { status.text = "Pick at least one transport"; return }
        s.connect(list)
        syncUi()
    }

    /** Pushes the switch positions into an idle engine. */
    private fun applyUiToEngine(e: PttEngine) {
        e.mode = if (duplexSwitch.isChecked) PttEngine.Mode.FULL_DUPLEX else PttEngine.Mode.HALF_DUPLEX
        e.relay = relaySwitch.isChecked
        e.codec = if (opusSwitch.isChecked) Packet.Codec.OPUS else Packet.Codec.PCM
    }

    /** Pulls connect state, mode, relay and codec from the engine into the widgets. */
    private fun syncUi() {
        val e = engine
        val connected = e?.isConnected == true
        connectButton.text = if (connected) "Disconnect" else "Connect"
        if (e != null) {
            duplexSwitch.isChecked = e.mode == PttEngine.Mode.FULL_DUPLEX
            relaySwitch.isChecked = e.relay
            opusSwitch.isChecked = e.codec == Packet.Codec.OPUS
        }
        refreshPttLabel()
    }

    /** Big-button caption for the current mode and mic state. */
    private fun refreshPttLabel() {
        val e = engine
        pttButton.text = when (e?.mode ?: PttEngine.Mode.HALF_DUPLEX) {
            PttEngine.Mode.HALF_DUPLEX -> "HOLD TO TALK"
            PttEngine.Mode.FULL_DUPLEX -> if (e?.isTalking == true) "MIC ON — TAP TO MUTE" else "MIC OFF — TAP TO TALK"
        }
    }

    /** Fills the Bluetooth spinner with bonded devices; index 0 is "listen only". */
    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        if (!hasPermissions()) { requestPermissions(); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        pairedDevices = adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
        val labels = listOf("Listen only (act as server)") + pairedDevices.map { it.name ?: it.address }
        btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    /** Permissions without which Connect cannot work. */
    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list += Manifest.permission.BLUETOOTH_CONNECT
        // Wi-Fi Aware discovery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.NEARBY_WIFI_DEVICES
        else list += Manifest.permission.ACCESS_FINE_LOCATION
        return list.toTypedArray()
    }

    /** Nice to have: without it the foreground notification is hidden on Android 13+, but the service still runs. */
    private fun optionalPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    /** True when every required (not optional) permission is granted. */
    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Asks for whatever is still missing, required and optional in one dialog run. */
    private fun requestPermissions() {
        val missing = (requiredPermissions() + optionalPermissions()).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
