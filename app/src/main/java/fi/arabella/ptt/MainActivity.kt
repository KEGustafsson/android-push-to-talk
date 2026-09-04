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
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PttService.LocalBinder).service
            service = s
            s.statusListener = { msg -> runOnUiThread { status.text = msg; syncUi() } }
            status.text = s.lastStatus
            syncUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

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

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.statusListener = null
        service = null
        unbindService(connection)   // the service keeps running while connected; see PttService
        super.onStop()
    }

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

    /** Pulls connect state, mode and relay from the engine into the widgets. */
    private fun syncUi() {
        val e = engine
        val connected = e?.isConnected == true
        connectButton.text = if (connected) "Disconnect" else "Connect"
        if (e != null) {
            duplexSwitch.isChecked = e.mode == PttEngine.Mode.FULL_DUPLEX
            relaySwitch.isChecked = e.relay
        }
        refreshPttLabel()
    }

    private fun refreshPttLabel() {
        val e = engine
        pttButton.text = when (e?.mode ?: PttEngine.Mode.HALF_DUPLEX) {
            PttEngine.Mode.HALF_DUPLEX -> "HOLD TO TALK"
            PttEngine.Mode.FULL_DUPLEX -> if (e?.isTalking == true) "MIC ON — TAP TO MUTE" else "MIC OFF — TAP TO TALK"
        }
    }

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

    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val missing = (requiredPermissions() + optionalPermissions()).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
