package fi.crewradio

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
import android.text.style.RelativeSizeSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import fi.crewradio.transport.BluetoothTransport
import fi.crewradio.transport.LanTransport
import fi.crewradio.transport.Transport
import fi.crewradio.transport.WifiAwareTransport

/**
 * Thin UI over [PttService]. The activity binds while visible and mirrors the
 * service's engine state, so rotating, backgrounding or reopening the app never
 * interrupts a running session.
 *
 * The "Radio" layout, top to bottom: channel header (crew name, how many aboard, menu),
 * three transport tiles, a strip with the Bluetooth peer and Connect, a card with the
 * status line and the crew, and the talk disc taking every pixel that is left. Settings
 * is behind the menu. Everything the user touches is at least 44 dp; the disc is
 * about 90% of the screen width.
 */
class MainActivity : AppCompatActivity() {

    private var service: PttService? = null
    private val engine: PttEngine? get() = service?.engine

    private lateinit var prefs: Prefs
    private lateinit var crewName: TextView
    private lateinit var channelLabel: TextView
    private lateinit var peersIcon: ImageView
    private lateinit var peerCount: TextView
    private lateinit var pttButton: MaterialButton
    private lateinit var channelRow: View
    private lateinit var channelState: TextView
    private lateinit var channelSwitch: MaterialSwitch
    private var syncingSwitch = false                  // true while syncUi() moves the switch itself
    private lateinit var peerButton: TextView
    private lateinit var menuButton: ImageButton
    private lateinit var tiles: List<Tile>
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var btPeerIndex = 0                        // 0 = listen only, else pairedDevices[index - 1]

    /** One transport tile: a view, its icon and label, and whether it is on. */
    private inner class Tile(val key: String, val root: LinearLayout, val icon: ImageView, val label: TextView) {
        var on = false
            set(value) {
                field = value
                root.background = ContextCompat.getDrawable(this@MainActivity, if (value) R.drawable.bg_tile_on else R.drawable.bg_tile_off)
                val tint = ContextCompat.getColor(this@MainActivity, if (value) R.color.primary else R.color.text_dim)
                icon.imageTintList = ColorStateList.valueOf(tint)
                label.setTextColor(tint)
            }
    }

    private val connection = object : ServiceConnection {
        /** Adopts the service's engine and pushes the stored settings into it; they are the source of truth. */
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PttService.LocalBinder).service
            service = s
            s.statusListener = { _ -> runOnUiThread { syncUi() } }        // the text itself lives on the Status screen
            s.rosterListener = { peers -> runOnUiThread { renderRoster(peers) } }
            renderRoster(s.lastRoster)
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        crewName = findViewById(R.id.crewName)
        channelLabel = findViewById(R.id.channelLabel)
        peersIcon = findViewById(R.id.peersIcon)
        peerCount = findViewById(R.id.peerCount)
        pttButton = findViewById(R.id.pttButton)
        channelRow = findViewById(R.id.channelRow)
        channelState = findViewById(R.id.channelState)
        channelSwitch = findViewById(R.id.channelSwitch)
        peerButton = findViewById(R.id.peerButton)
        menuButton = findViewById(R.id.menuButton)
        tiles = listOf(
            Tile(Prefs.KEY_USE_LAN, findViewById(R.id.tileLan), findViewById(R.id.tileLanIcon), findViewById(R.id.tileLanLabel)),
            Tile(Prefs.KEY_USE_BT, findViewById(R.id.tileBt), findViewById(R.id.tileBtIcon), findViewById(R.id.tileBtLabel)),
            Tile(Prefs.KEY_USE_AWARE, findViewById(R.id.tileAware), findViewById(R.id.tileAwareIcon), findViewById(R.id.tileAwareLabel))
        )

        // The disc is a circle as big as its area allows: the smaller of width and height.
        findViewById<View>(R.id.talkArea).addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val size = minOf(r - l, b - t)
            val lp = pttButton.layoutParams
            if (size > 0 && (lp.width != size || lp.height != size)) {
                lp.width = size
                lp.height = size
                pttButton.layoutParams = lp
            }
        }

        menuButton.setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menuInflater.inflate(R.menu.main, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_status -> startActivity(Intent(this@MainActivity, StatusActivity::class.java))
                        R.id.action_settings -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    true
                }
            }.show()
        }

        // Every choice on this screen is remembered, so on the boat it is open the app, press Connect.
        for (tile in tiles) {
            tile.on = prefs.bool(tile.key, tile.key == Prefs.KEY_USE_LAN)
            tile.root.setOnClickListener {
                if (engine?.isConnected == true) return@setOnClickListener   // takes effect on the next Connect anyway
                tile.on = !tile.on
                prefs.put(tile.key, tile.on)
                if (tile.on && !hasPermissions()) requestPermissions()       // ask for what this transport needs, now
                if (tile.key == Prefs.KEY_USE_BT) refreshPeer()
            }
        }
        peerButton.setOnClickListener { v -> if (engine?.isConnected != true) showPeerMenu(v) }
        refreshPeer()

        channelRow.setOnClickListener { channelSwitch.toggle() }
        channelSwitch.setOnCheckedChangeListener { _, on ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            val s = service
            when {
                s == null -> syncUi()                                  // not bound yet; snap back
                on && !s.engine.isConnected -> if (hasPermissions()) connect(s) else { requestPermissions(); syncUi() }
                !on && s.engine.isConnected -> { s.disconnect(); syncUi() }
            }
        }

        pttButton.setOnTouchListener { v, ev ->
            val e = engine ?: return@setOnTouchListener false
            if (e.mode == PttEngine.Mode.HALF_DUPLEX) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        fingerDown = true
                        e.startTalking()
                        refreshPttLabel()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        fingerDown = false
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

    /** Binds while visible; BIND_AUTO_CREATE means the service (and its senderId) exists whenever the UI is up. */
    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** Coming back from the settings screen: push the live-applicable settings into the engine. */
    override fun onResume() {
        super.onResume()
        crewName.text = prefs.crewName.uppercase()
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

    private fun tileOn(key: String) = tiles.first { it.key == key }.on

    /** Builds the selected transports and hands them to the service. */
    private fun connect(s: PttService) {
        val ctx = applicationContext
        val list = mutableListOf<Transport>()
        if (tileOn(Prefs.KEY_USE_LAN)) list += LanTransport(ctx, prefs.group, prefs.port)
        if (tileOn(Prefs.KEY_USE_BT)) list += BluetoothTransport(ctx, pairedDevices.getOrNull(btPeerIndex - 1))
        if (tileOn(Prefs.KEY_USE_AWARE)) list += WifiAwareTransport(ctx, s.engine.senderId, prefs.channelKey)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.pick_transport, Toast.LENGTH_SHORT).show()
            syncUi()
            return
        }
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
        e.audioRoute = when (prefs.audioRoute) {
            Prefs.ROUTE_SPEAKER -> fi.crewradio.audio.AudioRoute.Policy.SPEAKER
            Prefs.ROUTE_EARPIECE -> fi.crewradio.audio.AudioRoute.Policy.EARPIECE
            else -> fi.crewradio.audio.AudioRoute.Policy.AUTO
        }
        e.headsetAsCall = prefs.headsetAsCall
        e.headsetVox = prefs.headsetVox
        e.useProximity = prefs.proximitySensor
        e.channelKey = prefs.channelKey
        service?.cueTones = prefs.cueTones
        service?.refreshHardwareButtons()
    }

    /**
     * Pulls the connect state from the engine into the widgets: the channel switch and its
     * one-line state, like the power switch on a radio. The switch is moved under
     * [syncingSwitch] so its listener does not mistake that for the user.
     */
    private fun syncUi() {
        val connected = engine?.isConnected == true
        syncingSwitch = true
        channelSwitch.isChecked = connected
        syncingSwitch = false
        channelState.text = getString(if (connected) R.string.channel_on else R.string.channel_off)
        channelState.setTextColor(ContextCompat.getColor(this, if (connected) R.color.primary else R.color.text_dim))
        for (tile in tiles) tile.root.alpha = if (connected) 0.55f else 1f
        peerButton.alpha = if (connected) 0.55f else 1f
        // The screen stays on only while on channel, and only if the user wants it to.
        if (connected && prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshPttLabel()
    }

    private var fingerDown = false

    /**
     * Disc caption and colours: teal and TALK while idle, red and ON AIR while the mic is live.
     * ON AIR without a finger on the disc means a talk key latched it, so the hint says how to stop.
     */
    private fun refreshPttLabel() {
        val e = engine
        val live = e?.isTalking == true
        val (big, small) = when (e?.mode ?: PttEngine.Mode.HALF_DUPLEX) {
            PttEngine.Mode.HALF_DUPLEX ->
                if (!live) R.string.ptt_talk to R.string.ptt_talk_hint
                else if (fingerDown) R.string.ptt_on_air to R.string.ptt_on_air_hint
                else R.string.ptt_on_air to R.string.ptt_on_air_latched_hint
            PttEngine.Mode.FULL_DUPLEX -> if (live) R.string.ptt_mic_on to R.string.ptt_mic_on_hint else R.string.ptt_mic_off to R.string.ptt_mic_off_hint
        }
        val hintColor = ContextCompat.getColor(this, if (live) R.color.error else R.color.primary_container)
        pttButton.text = SpannableStringBuilder()
            .append(getString(big))
            .append("\n")
            .append(getString(small), RelativeSizeSpan(0.3f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .also { sb ->
                val start = sb.length - getString(small).length
                sb.setSpan(ForegroundColorSpan(hintColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        pttButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (live) R.color.on_air else R.color.primary))
        pttButton.setTextColor(ContextCompat.getColor(this, if (live) R.color.on_air_text else R.color.on_primary))
        pttButton.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, if (live) R.color.error else R.color.outline))
    }

    /**
     * The header count, and whoever is talking shown in green on the label line above the
     * channel name. Nothing changes size or position: a layout shift under the talk disc is
     * the last thing a thumb about to press it needs. The full list lives on the Status screen.
     */
    private fun renderRoster(peers: List<Peer>) {
        peerCount.text = peers.size.toString()
        val talking = peers.filter { it.talking }
        val green = ContextCompat.getColor(this, R.color.talking)
        if (talking.isEmpty()) {
            channelLabel.text = getString(R.string.crew_channel)
            channelLabel.setTextColor(ContextCompat.getColor(this, R.color.text_teal_dim))
            peersIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            peerCount.setTextColor(ContextCompat.getColor(this, R.color.text))
        } else {
            channelLabel.text = "\u25CF " + getString(R.string.talking_line, talking.joinToString(", ") { it.label.uppercase() })
            channelLabel.setTextColor(green)
            peersIcon.imageTintList = ColorStateList.valueOf(green)
            peerCount.setTextColor(green)
        }
    }

    // ---- Bluetooth peer -----------------------------------------------------------

    /** The peer strip: which peer Bluetooth will dial; hidden altogether while Bluetooth is off. */
    private fun refreshPeer() {
        if (!tileOn(Prefs.KEY_USE_BT)) {
            peerButton.visibility = View.GONE
            return
        }
        peerButton.visibility = View.VISIBLE
        loadPairedDevices()
        val dev = pairedDevices.getOrNull(btPeerIndex - 1)
        peerButton.text = if (dev == null) getString(R.string.peer_listen_only)
        else getString(R.string.peer_prefix) + (dev.name ?: dev.address).uppercase()
    }

    /** Popup with "listen only" and every bonded device; the choice is remembered. */
    private fun showPeerMenu(anchor: View) {
        loadPairedDevices()
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, getString(R.string.bt_listen_only))
            pairedDevices.forEachIndexed { i, d -> menu.add(0, i + 1, i + 1, d.name ?: d.address) }
            setOnMenuItemClickListener { item ->
                btPeerIndex = item.itemId
                prefs.put(Prefs.KEY_BT_PEER, pairedDevices.getOrNull(btPeerIndex - 1)?.address ?: "")
                refreshPeer()
                true
            }
        }.show()
    }

    /** Reads the bonded devices and re-finds the remembered one. */
    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val missing = bluetoothPermissions().any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing) { requestPermissions(); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        pairedDevices = adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
        val remembered = pairedDevices.indexOfFirst { it.address == prefs.string(Prefs.KEY_BT_PEER) }
        btPeerIndex = if (remembered >= 0) remembered + 1 else 0
    }

    // ---- permissions --------------------------------------------------------------

    /**
     * Permissions without which Connect cannot work, for the transports that are switched
     * on: the mic always, Bluetooth only with the Bluetooth tile, Aware discovery only with
     * the Aware tile. A LAN-only crew member never has to grant Bluetooth anything.
     */
    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (tileOn(Prefs.KEY_USE_BT)) list += bluetoothPermissions()
        if (tileOn(Prefs.KEY_USE_AWARE)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.NEARBY_WIFI_DEVICES
            else list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.toTypedArray()
    }

    /** What listing bonded devices and dialling one need; nothing before Android 12. */
    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

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
