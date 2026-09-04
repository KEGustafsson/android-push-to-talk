package fi.arabella.ptt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var engine: PttEngine
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

        engine = PttEngine(this) { msg -> runOnUiThread { status.text = msg; refreshPttLabel() } }

        checkBt.setOnCheckedChangeListener { _, on ->
            btSpinner.visibility = if (on) View.VISIBLE else View.GONE
            if (on) loadPairedDevices()
        }
        duplexSwitch.setOnCheckedChangeListener { _, on ->
            engine.mode = if (on) PttEngine.Mode.FULL_DUPLEX else PttEngine.Mode.HALF_DUPLEX
            refreshPttLabel()
        }
        relaySwitch.isChecked = engine.relay
        relaySwitch.setOnCheckedChangeListener { _, on -> engine.relay = on }

        connectButton.setOnClickListener {
            if (engine.isConnected) {
                engine.disconnect()
                connectButton.text = "Connect"
                status.text = "Disconnected"
            } else if (hasPermissions()) {
                connect()
            } else {
                requestPermissions()
            }
        }

        pttButton.setOnTouchListener { _, ev ->
            if (engine.mode == PttEngine.Mode.HALF_DUPLEX) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> engine.startTalking()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> engine.stopTalking()
                }
                true
            } else false
        }
        pttButton.setOnClickListener {
            if (engine.mode == PttEngine.Mode.FULL_DUPLEX) engine.toggleTalking()
        }

        requestPermissions()
        refreshPttLabel()
    }

    private fun connect() {
        val list = mutableListOf<Transport>()
        if (checkLan.isChecked) list += LanTransport(this)
        if (checkBt.isChecked) {
            val peer = pairedDevices.getOrNull(btSpinner.selectedItemPosition - 1) // 0 = listen only
            list += BluetoothTransport(this, peer)
        }
        if (checkAware.isChecked) list += WifiAwareTransport(this, engine.senderId)
        if (list.isEmpty()) { status.text = "Pick at least one transport"; return }
        engine.connect(list)
        connectButton.text = "Disconnect"
    }

    private fun refreshPttLabel() {
        pttButton.text = when (engine.mode) {
            PttEngine.Mode.HALF_DUPLEX -> "HOLD TO TALK"
            PttEngine.Mode.FULL_DUPLEX -> if (engine.isTalking) "MIC ON — TAP TO MUTE" else "MIC OFF — TAP TO TALK"
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

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list += Manifest.permission.BLUETOOTH_CONNECT
        // Wi-Fi Aware discovery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.NEARBY_WIFI_DEVICES
        else list += Manifest.permission.ACCESS_FINE_LOCATION
        return list.toTypedArray()
    }

    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        if (!hasPermissions()) ActivityCompat.requestPermissions(this, requiredPermissions(), 1)
    }

    override fun onDestroy() {
        engine.disconnect()
        super.onDestroy()
    }
}
