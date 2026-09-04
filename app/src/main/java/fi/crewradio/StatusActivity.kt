package fi.crewradio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Everything the main screen deliberately leaves out: the crew in detail, this phone's
 * addresses, the network settings in use, packet counters and the last status lines.
 * Plain monospace blocks, refreshed once a second while open. Nothing here is tappable.
 */
class StatusActivity : AppCompatActivity() {

    private var service: PttService? = null
    private lateinit var prefs: Prefs
    private lateinit var sections: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
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
        sections = findViewById(R.id.sections)
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
        sections.removeAllViews()
        val s = service
        val e = s?.engine
        section("CHANNEL", buildString {
            line("name", prefs.crewName)
            line("state", if (e?.isConnected == true) "on channel" else "off")
            line("via", e?.activeTransports?.joinToString(" + ")?.ifEmpty { "-" } ?: "-")
            line("my name", prefs.name ?: e?.defaultName ?: "-")
            line("my id", e?.senderId?.let { hex(it) } ?: "-")
            line("mode", if (prefs.fullDuplex) "full duplex" else "half duplex")
            line("relay", if (prefs.relay) "on" else "off")
            line("codec", if (prefs.opus) "Opus" else "PCM")
        })
        section("CREW", crew(e))
        section("NETWORK", buildString {
            for (nic in interfaces()) append(nic).append('\n')
            line("multicast", "${prefs.group}:${prefs.port}")
            line("aware", "${WifiAwareServiceName} · passphrase ${"*".repeat(prefs.passphrase.length)}")
            line("bluetooth", bluetoothName())
            line("hop limit", prefs.hops.toString())
        })
        section("PACKETS", e?.stats()?.let { st ->
            buildString {
                line("received", "${st.rxPackets}  (${kb(st.rxBytes)})")
                line("sent", "${st.txPackets}  (${kb(st.txBytes)})")
                line("relayed", st.relayed.toString())
                line("duplicates", st.duplicates.toString())
                line("hellos", st.hellos.toString())
            }
        } ?: "-")
        section("LOG", s?.statusLog?.takeIf { it.isNotEmpty() }?.asReversed()?.joinToString("\n") ?: "-")
    }

    private fun crew(e: PttEngine?): String {
        val peers = e?.rosterNow ?: emptyList()
        if (peers.isEmpty()) return if (e?.isConnected == true) "nobody else aboard" else "-"
        return buildString {
            for ((i, p) in peers.withIndex()) {
                if (i > 0) append('\n')
                append(if (p.talking) "● " else "○ ").append(p.label).append('\n')
                line("  id", hex(p.id))
                line("  via", p.via + if (p.hops > 0) " · ${p.hops} hop${if (p.hops == 1) "" else "s"}" else "")
                line("  on", Hello.describe(p.transports).ifEmpty { "-" })
                line("  heard", "${p.seenAgoMs / 1000}.${p.seenAgoMs % 1000 / 100} s ago")
            }
        }
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key.padEnd(12)).append(value).append('\n')
    }

    private fun section(title: String, body: String) {
        sections.addView(TextView(this).apply {
            text = title
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.16f
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StatusActivity, R.color.text_teal_dim))
            setPadding(0, dp(14), 0, dp(6))
        })
        sections.addView(TextView(this).apply {
            text = body.trimEnd()
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StatusActivity, R.color.text))
            setLineSpacing(0f, 1.15f)
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hex(id: Int) = id.toUInt().toString(16)
    private fun kb(bytes: Long) = if (bytes < 10_000) "$bytes B" else "${bytes / 1024} kB"

    /** Every interface that is up with an address: wlan0, the Aware data interface, a hotspot. */
    private fun interfaces(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address
                    when {
                        a is Inet4Address -> "${nic.name.padEnd(12)}${a.hostAddress}/${ia.networkPrefixLength}"
                        a is Inet6Address && nic.name.startsWith("aware") -> "${nic.name.padEnd(12)}${a.hostAddress?.substringBefore('%')}"
                        else -> null
                    }
                }
            }
            .ifEmpty { listOf("no network interface up") }
    } catch (e: Exception) {
        listOf("interfaces: ${e.message}")
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothName(): String {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return "none"
        if (!adapter.isEnabled) return "off"
        val name = try { adapter.name } catch (_: SecurityException) { null }
        return name ?: "on"
    }

    private companion object {
        val WifiAwareServiceName = fi.crewradio.transport.WifiAwareTransport.SERVICE_NAME
    }
}
