package fi.crewradio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Everything the main screen deliberately leaves out, in the main screen's own language:
 * the same header, cards with a teal label, label-value rows, the crew as dot/name/meta
 * rows, packet counters as tiles, and the status log last. Rebuilt every two seconds
 * while open; nothing here is tappable.
 */
class StatusActivity : AppCompatActivity() {

    private var service: PttService? = null
    private lateinit var prefs: Prefs
    private lateinit var inflater: LayoutInflater
    private lateinit var crewName: TextView
    private lateinit var statePill: TextView
    private lateinit var cards: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2_000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PttService.LocalBinder).service
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        inflater = LayoutInflater.from(this)
        crewName = findViewById(R.id.crewName)
        statePill = findViewById(R.id.statePill)
        cards = findViewById(R.id.cards)
        crewName.text = prefs.crewName.uppercase()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        unbindService(connection)
        service = null
        super.onStop()
    }

    // ---- rendering ----------------------------------------------------------------

    private fun render() {
        val s = service
        val e = s?.engine
        val on = e?.isConnected == true

        statePill.text = getString(if (on) R.string.channel_on else R.string.state_off)
        statePill.setTextColor(color(if (on) R.color.primary else R.color.text_dim))

        cards.removeAllViews()

        // Crew
        val peers = e?.rosterNow ?: emptyList()
        card("CREW", getString(R.string.aboard, peers.size)).let { rows ->
            if (peers.isEmpty()) {
                rows.addView(note(if (on) getString(R.string.roster_alone) else getString(R.string.status_off_hint)))
            } else for (p in peers) rows.addView(peerRow(p))
        }

        // This phone
        card("THIS PHONE", e?.senderId?.let { hex(it) } ?: "").let { rows ->
            rows.addView(kv("MY NAME", prefs.name ?: e?.defaultName ?: "-"))
            rows.addView(kv("MODE", if (prefs.fullDuplex) "Full duplex" else "Half duplex"))
            rows.addView(kv("RELAY", if (prefs.relay) "On" else "Off"))
            rows.addView(kv("CODEC", if (prefs.opus) "Opus" else "PCM"))
            rows.addView(kv("HOP LIMIT", prefs.hops.toString()))
        }

        // Network
        card("NETWORK", e?.activeTransports?.joinToString(" + ")?.uppercase().orEmpty()).let { rows ->
            for ((nic, addr) in interfaces()) rows.addView(kv(nic.uppercase(), addr))
            rows.addView(kv("MULTICAST", "${prefs.group}:${prefs.port}"))
            rows.addView(kv("AWARE", fi.crewradio.transport.WifiAwareTransport.SERVICE_NAME + " · passphrase set"))
            rows.addView(kv("BLUETOOTH", bluetoothName()))
        }

        // Packets
        val st = e?.stats()
        card("PACKETS", if (on) "SINCE JOIN · " + kb((st?.rxBytes ?: 0) + (st?.txBytes ?: 0)) else "").let { rows ->
            rows.addView(tiles(
                "RECEIVED" to (st?.rxPackets ?: 0).toString(),
                "SENT" to (st?.txPackets ?: 0).toString(),
                "RELAYED" to (st?.relayed ?: 0).toString()
            ))
            rows.addView(tiles(
                "DUPLICATES" to (st?.duplicates ?: 0).toString(),
                "CONCEALED" to (st?.concealed ?: 0).toString(),
                "HELLOS" to (st?.hellos ?: 0).toString()
            ))
        }

        // Log
        card("LOG", "").let { rows ->
            val lines = s?.statusLog.orEmpty().asReversed()
            if (lines.isEmpty()) rows.addView(note("-"))
            else for (line in lines) rows.addView(logLine(line))
        }
    }

    /** Adds a card with a title (and an optional right-hand aside) and returns its row container. */
    private fun card(title: String, aside: String): LinearLayout {
        val v = inflater.inflate(R.layout.card_status, cards, false)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.aside).text = aside
        cards.addView(v)
        return v.findViewById(R.id.rows)
    }

    private fun kv(key: String, value: String): View {
        val v = inflater.inflate(R.layout.row_kv, cards, false)
        v.findViewById<TextView>(R.id.key).text = key
        v.findViewById<TextView>(R.id.value).text = value
        return v
    }

    private fun peerRow(p: Peer): View {
        val v = inflater.inflate(R.layout.row_status_peer, cards, false)
        v.findViewById<TextView>(R.id.name).text = p.label
        val meta = v.findViewById<TextView>(R.id.meta)
        val dot = v.findViewById<View>(R.id.dot)
        if (p.talking) {
            meta.text = getString(R.string.meta_talking)
            meta.setTextColor(color(R.color.talking))
            dot.backgroundTintList = ColorStateList.valueOf(color(R.color.talking))
        } else {
            meta.text = p.via.uppercase() + if (p.hops > 0) " · ${p.hops} HOP" + (if (p.hops == 1) "" else "S") else ""
            meta.setTextColor(color(R.color.text_dim))
            dot.backgroundTintList = null
        }
        val on = Hello.describe(p.transports).ifEmpty { "-" }
        v.findViewById<TextView>(R.id.detail).text = "on $on · id ${hex(p.id)} · heard ${ago(p.seenAgoMs)}"
        return v
    }

    private fun tiles(vararg pairs: Pair<String, String>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        pairs.forEachIndexed { i, (label, value) ->
            val t = inflater.inflate(R.layout.tile_stat, row, false)
            t.findViewById<TextView>(R.id.value).text = value
            t.findViewById<TextView>(R.id.label).text = label
            (t.layoutParams as LinearLayout.LayoutParams).marginStart = if (i == 0) 0 else dp(8)
            row.addView(t)
        }
        return row
    }

    private fun logLine(line: String): View = TextView(this).apply {
        text = line
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 12f
        setTextColor(color(R.color.text_dim))
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun note(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(color(R.color.text_dim))
        setPadding(0, dp(8), 0, dp(6))
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hex(id: Int) = id.toUInt().toString(16)
    private fun kb(bytes: Long) = if (bytes < 10_000) "$bytes B" else "${bytes / 1024} kB"
    private fun ago(ms: Long) = if (ms < 1_000) "just now" else "${ms / 1000} s ago"

    /** Every interface that is up with an address: wlan0, the Aware data interface, a hotspot. */
    private fun interfaces(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address
                    when {
                        a is Inet4Address -> nic.name to "${a.hostAddress}/${ia.networkPrefixLength}"
                        a is Inet6Address && nic.name.startsWith("aware") -> nic.name to (a.hostAddress?.substringBefore('%') ?: "")
                        else -> null
                    }
                }
            }
            .ifEmpty { listOf("WI-FI" to "no interface up") }
    } catch (e: Exception) {
        listOf("WI-FI" to "unknown")
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothName(): String {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return "None"
        if (!adapter.isEnabled) return "Off"
        return try { adapter.name } catch (_: SecurityException) { null } ?: "On"
    }
}
