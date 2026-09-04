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
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import fi.arabella.ptt.transport.BluetoothTransport
import fi.arabella.ptt.transport.LanTransport
import fi.arabella.ptt.transport.Transport
import fi.arabella.ptt.transport.WifiAwareTransport

/**
 * Thin UI over [PttService]. The activity binds while visible and mirrors the
 * service's engine state, so rotating, backgrounding or reopening the app never
 * interrupts a running session.
 *
 * Layout, top to bottom: app bar (Settings lives in its overflow menu), transport chips,
 * the Bluetooth peer dropdown (only while Bluetooth is on), Connect, a card with the
 * status line and the crew, and the talk pad filling whatever is left. Everything the
 * user touches is large; everything they read is in one card.
 */
class MainActivity : AppCompatActivity() {

    private var service: PttService? = null
    private val engine: PttEngine? get() = service?.engine

    private lateinit var prefs: Prefs
    private lateinit var toolbar: MaterialToolbar
    private lateinit var status: TextView
    private lateinit var rosterView: TextView
    private lateinit var pttButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var chipLan: Chip
    private lateinit var chipBt: Chip
    private lateinit var chipAware: Chip
    private lateinit var btPeerLayout: TextInputLayout
    private lateinit var btPeer: MaterialAutoCompleteTextView
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var btPeerIndex = 0                        // 0 = listen only, else pairedDevices[index - 1]

    private val connection = object : ServiceConnection {
        /** Adopts the service's engine and pushes the stored settings into it; they are the source of truth. */
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PttService.LocalBinder).service
            service = s
            s.statusListener = { msg -> runOnUiThread { status.text = msg; syncUi() } }
            s.rosterListener = { peers -> runOnUiThread { rosterView.text = renderRoster(peers) } }
            status.text = s.lastStatus
            rosterView.text = renderRoster(s.lastRoster)
            applySettings(s.engine)
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
        DynamicColors.applyToActivityIfAvailable(this)   // the phone's own palette on Android 12+
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        toolbar = findViewById(R.id.toolbar)
        status = findViewById(R.id.status)
        rosterView = findViewById(R.id.roster)
        pttButton = findViewById(R.id.pttButton)
        connectButton = findViewById(R.id.connectButton)
        chipLan = findViewById(R.id.chipLan)
        chipBt = findViewById(R.id.chipBt)
        chipAware = findViewById(R.id.chipAware)
        btPeerLayout = findViewById(R.id.btPeerLayout)
        btPeer = findViewById(R.id.btPeer)
        setSupportActionBar(toolbar)

        // Every choice on this screen is remembered, so on the boat it is open the app, press Connect.
        chipLan.setOnCheckedChangeListener { _, on -> prefs.put(Prefs.KEY_USE_LAN, on) }
        chipAware.setOnCheckedChangeListener { _, on -> prefs.put(Prefs.KEY_USE_AWARE, on) }
        chipBt.setOnCheckedChangeListener { _, on ->
            prefs.put(Prefs.KEY_USE_BT, on)
            btPeerLayout.visibility = if (on) View.VISIBLE else View.GONE
            if (on) loadPairedDevices()
        }
        btPeer.setOnItemClickListener { _, _, position, _ ->
            btPeerIndex = position
            prefs.put(Prefs.KEY_BT_PEER, pairedDevices.getOrNull(position - 1)?.address ?: "")
        }
        chipLan.isChecked = prefs.bool(Prefs.KEY_USE_LAN, true)
        chipAware.isChecked = prefs.bool(Prefs.KEY_USE_AWARE, false)
        chipBt.isChecked = prefs.bool(Prefs.KEY_USE_BT, false)      // after its listener, so the dropdown fills

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

        pttButton.setOnTouchListener { v, ev ->
            val e = engine ?: return@setOnTouchListener false
            if (e.mode == PttEngine.Mode.HALF_DUPLEX) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        e.startTalking()
                        refreshPttLabel()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        e.stopTalking()
                        refreshPttLabel()
                    }
                }
                true
            } else false
        }
        pttButton.setOnClickListener {
            val e = engine ?: return@setOnClickListener
            if (e.mode == PttEngine.Mode.FULL_DUPLEX) {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                e.toggleTalking()
                refreshPttLabel()
            }
        }

        requestPermissions()
        refreshPttLabel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Binds while visible; BIND_AUTO_CREATE means the service (and its senderId) exists whenever the UI is up. */
    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** Coming back from the settings screen: push the live-applicable settings into the engine. */
    override fun onResume() {
        super.onResume()
        engine?.let { applySettings(it) }
        refreshPttLabel()
    }

    /** Unbinds without touching the session: a connected service keeps running as a started foreground service. */
    override fun onStop() {
        service?.statusListener = null
        service?.rosterListener = null
        service = null
        unbindService(connection)   // the service keeps running while connected; see PttService
        super.onStop()
    }

    /** Builds the selected transports and hands them to the service. */
    private fun connect(s: PttService) {
        val ctx = applicationContext
        val list = mutableListOf<Transport>()
        if (chipLan.isChecked) list += LanTransport(ctx, prefs.group, prefs.port)
        if (chipBt.isChecked) list += BluetoothTransport(ctx, pairedDevices.getOrNull(btPeerIndex - 1))
        if (chipAware.isChecked) list += WifiAwareTransport(ctx, s.engine.senderId, prefs.passphrase)
        if (list.isEmpty()) { status.text = "Pick at least one transport"; return }
        applySettings(s.engine)
        s.connect(list)
        syncUi()
    }

    /**
     * The settings that apply without a reconnect: duplex mode, relay, codec, hop limit
     * and the announced name. Changing the mode un-keys the mic, which is what you want.
     */
    private fun applySettings(e: PttEngine) {
        e.mode = if (prefs.fullDuplex) PttEngine.Mode.FULL_DUPLEX else PttEngine.Mode.HALF_DUPLEX
        e.relay = prefs.relay
        e.codec = if (prefs.opus) Packet.Codec.OPUS else Packet.Codec.PCM
        e.maxHops = prefs.hops
        e.displayName = prefs.name ?: e.defaultName
    }

    /** Pulls the connect state from the engine into the widgets: Connect is loud, Disconnect is calm. */
    private fun syncUi() {
        val connected = engine?.isConnected == true
        connectButton.text = getString(if (connected) R.string.disconnect else R.string.connect)
        connectButton.backgroundTintList = ColorStateList.valueOf(
            color(if (connected) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorPrimary)
        )
        connectButton.setTextColor(
            color(if (connected) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnPrimary)
        )
        for (chip in listOf(chipLan, chipBt, chipAware)) chip.isEnabled = !connected
        btPeerLayout.isEnabled = !connected
        refreshPttLabel()
    }

    /** Talk-pad caption and colour for the current mode and mic state: red means the mic is live. */
    private fun refreshPttLabel() {
        val e = engine
        val live = e?.isTalking == true
        pttButton.text = getString(
            when (e?.mode ?: PttEngine.Mode.HALF_DUPLEX) {
                PttEngine.Mode.HALF_DUPLEX -> if (live) R.string.ptt_talking else R.string.ptt_hold
                PttEngine.Mode.FULL_DUPLEX -> if (live) R.string.ptt_mic_on else R.string.ptt_mic_off
            }
        )
        pttButton.backgroundTintList = ColorStateList.valueOf(
            color(if (live) com.google.android.material.R.attr.colorError else com.google.android.material.R.attr.colorPrimary)
        )
        pttButton.setTextColor(
            color(if (live) com.google.android.material.R.attr.colorOnError else com.google.android.material.R.attr.colorOnPrimary)
        )
    }

    /**
     * The crew, one line each: a dot (green while talking), the name, and how we hear them.
     * Empty when not connected.
     */
    private fun renderRoster(peers: List<Peer>): CharSequence {
        if (peers.isEmpty()) return if (engine?.isConnected == true) getString(R.string.roster_alone) else ""
        val muted = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val green = ContextCompat.getColor(this, R.color.talking)
        val sb = SpannableStringBuilder()
        sb.append("Crew · ${peers.size} online", StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (p in peers) {
            sb.append("\n")
            sb.append("● ", ForegroundColorSpan(if (p.talking) green else muted), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(p.label)
            val details = buildList {
                add("via ${p.via}" + if (p.hops > 0) " (${p.hops} hop${if (p.hops == 1) "" else "s"})" else "")
                val on = Hello.describe(p.transports)
                if (on.isNotEmpty() && on != p.via) add("on $on")
            }
            sb.append("  ·  " + details.joinToString("  ·  "), ForegroundColorSpan(muted), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (p.talking) sb.append("  ·  talking", ForegroundColorSpan(green), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(pttButton, attr)

    /** Fills the Bluetooth dropdown with bonded devices; entry 0 is "listen only". */
    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        if (!hasPermissions()) { requestPermissions(); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        pairedDevices = adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
        val labels = listOf(getString(R.string.bt_listen_only)) + pairedDevices.map { it.name ?: it.address }
        btPeer.setSimpleItems(labels.toTypedArray())
        val remembered = pairedDevices.indexOfFirst { it.address == prefs.string(Prefs.KEY_BT_PEER) }
        btPeerIndex = if (remembered >= 0) remembered + 1 else 0
        btPeer.setText(labels[btPeerIndex], false)
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

    /**
     * Asked for, but not required to Connect:
     * - POST_NOTIFICATIONS: without it the foreground notification is hidden on Android 13+,
     *   but the service still runs.
     * - BLUETOOTH_SCAN: only used to cancel an in-progress system scan before dialling a peer,
     *   which makes RFCOMM connect faster; [BluetoothTransport] skips that step without it.
     */
    private fun optionalPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list += Manifest.permission.BLUETOOTH_SCAN
        return list.toTypedArray()
    }

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
