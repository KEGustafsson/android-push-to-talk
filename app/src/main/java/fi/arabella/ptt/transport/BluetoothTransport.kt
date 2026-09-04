package fi.arabella.ptt.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bluetooth Classic RFCOMM. Every phone listens as a server; optionally also
 * connects to one chosen paired peer. With the engine's relay enabled, a phone
 * holding several links forwards between them, so a chain A-B-C works.
 *
 * Reconnect: the dialled link is re-dialled with [Backoff] whenever it drops, for as long
 * as the session runs, and the server socket is re-created if the adapter is toggled.
 * An accepted link is the other side's job to restore — it dialled us, it dials again.
 *
 * Throughput: 16 kHz PCM16 = 32 kB/s, comfortably inside RFCOMM's practical limit.
 *
 * Every call into the Bluetooth stack is treated as able to throw: on Android 12+ the
 * adapter throws [SecurityException] for a missing runtime permission, and vendor stacks
 * throw their own things. Failures become status lines; they never reach the thread's
 * default handler, which would kill the app.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    context: Context,
    private val peer: BluetoothDevice?
) : Transport {

    override val name = "BT"
    override val relayWithin = true

    private val appContext = context.applicationContext
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    /** Resolved now, while the adapter is on: with it off, `name` is null and retries would show the MAC. */
    private val peerLabel: String? = peer?.let { label(it) }
    private val links = CopyOnWriteArrayList<StreamLink>()
    private val lifecycle = Any()                       // orders "add a link" against "stop and close them all"
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var dialThread: Thread? = null
    @Volatile private var dialing: BluetoothSocket? = null   // mid-connect(); interrupt() does not abort that, close() does
    @Volatile private var running = false
    private lateinit var onPacket: (ByteArray, Transport, Any?) -> Unit
    private lateinit var onStatus: (String) -> Unit

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onPacket = onPacket
        this.onStatus = onStatus
        if (adapter == null || !adapter.isEnabled) {
            onStatus("BT: adapter off")
            return
        }
        running = true
        transportThread("ptt-bt-listen", { onStatus("BT listener stopped: ${it.message}") }) { listenLoop() }
        peer?.let { redial(it) }
        onStatus("BT: listening" + (peerLabel?.let { ", connecting to $it" } ?: ""))
    }

    /**
     * Serves incoming connections for the whole session. The server socket dies when the
     * adapter is toggled; it is then re-created with backoff rather than given up on.
     */
    private fun listenLoop() {
        val backoff = Backoff()
        while (running) {
            val srv = try {
                adapter.listenUsingRfcommWithServiceRecord("PTT", SERVICE_UUID)
            } catch (e: Exception) {
                if (!running) return
                val wait = backoff.next()
                onStatus("BT: can't listen (${e.message}), retry in ${wait / 1000}s")
                if (!sleepQuietly(wait)) return
                continue
            }
            server = srv
            if (!running) { srv.close(); return }        // stop() raced us; leave no listener behind
            backoff.reset()
            while (running) {
                val s = try { srv.accept() } catch (_: Exception) { break }
                try {
                    addLink(s, "accepted ${label(s.remoteDevice)}", isDialed = false)
                } catch (e: Exception) {                // the peer hung up before we got its streams; keep serving
                    try { s.close() } catch (_: Exception) {}
                    onStatus("BT: accept failed (${e.message})")
                }
            }
            try { srv.close() } catch (_: Exception) {}
            server = null
            if (!running) return
            onStatus("BT: listener dropped, restarting")
            if (!sleepQuietly(backoff.next())) return
        }
    }

    /** Starts a dial thread for [dev]: at start, and again whenever our link to it drops. */
    private fun redial(dev: BluetoothDevice) {
        dialThread = transportThread("ptt-bt-connect", { onStatus("BT connect stopped: ${it.message}") }) {
            dialLoop(dev)
        }
    }

    /**
     * Dials [dev] until it answers, with backoff: the other phone may not have pressed
     * Connect yet, may be out of range, or (Samsung) may just fail the first attempt.
     * A socket that failed to connect is closed — a leaked one keeps the RFCOMM channel
     * busy and makes every later attempt fail too. Returns once the link is up; the
     * link's reader calls [redial] when it drops.
     */
    private fun dialLoop(dev: BluetoothDevice) {
        cancelDiscoveryQuietly()
        val backoff = Backoff()
        while (running) {
            var socket: BluetoothSocket? = null
            try {
                socket = dev.createRfcommSocketToServiceRecord(SERVICE_UUID)
                dialing = socket
                socket.connect()
                dialing = null
                addLink(socket, "connected to ${peerLabel ?: label(dev)}", isDialed = true)
                return
            } catch (e: Exception) {
                dialing = null
                try { socket?.close() } catch (_: Exception) {}
                if (!running) return
                val wait = backoff.next()
                onStatus("BT: ${peerLabel ?: label(dev)} not answering, retry in ${wait / 1000}s")
                if (!sleepQuietly(wait)) return
            }
        }
    }

    /**
     * Best effort: an ongoing system scan slows RFCOMM down, but cancelling it needs
     * BLUETOOTH_SCAN on Android 12+, which is optional here. Without it we simply skip —
     * calling anyway throws [SecurityException] and used to take the app down with it.
     */
    private fun cancelDiscoveryQuietly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
    }

    /** Human name of a device, or its address when the name needs a permission we lack. */
    private fun label(dev: BluetoothDevice): String =
        (try { dev.name } catch (_: SecurityException) { null }) ?: dev.address

    /** Registers a connected socket as a link, unless [stop] already ran — then it is closed instead. */
    private fun addLink(socket: BluetoothSocket, why: String, isDialed: Boolean) {
        val dev = socket.remoteDevice
        val link = StreamLink(label(dev), socket.inputStream, socket.outputStream) { socket.close() }
        synchronized(lifecycle) {
            if (!running) { link.close(); return }
            links.add(link)
        }
        onStatus("BT: $why (${links.size} link${if (links.size == 1) "" else "s"})")
        transportThread("ptt-bt-rx-${dev.address}", { onStatus("BT rx stopped: ${it.message}") }) {
            try {
                link.readLoop { onPacket(it, this, link) }
            } catch (e: IOException) {
                if (running) onStatus("BT: ${link.label} dropped" + if (isDialed) ", redialling" else "")
            } finally {
                links.remove(link)
                link.close()
                if (isDialed && running) peer?.let { redial(it) }
            }
        }
    }

    override fun send(packet: ByteArray, except: Any?) {
        for (link in links) {
            if (link === except) continue
            try { link.send(packet) } catch (_: IOException) { /* reader thread tears it down */ }
        }
    }

    override fun stop() {
        synchronized(lifecycle) {
            running = false
            for (link in links) link.close()
            links.clear()
        }
        dialThread?.interrupt()                         // ends a backoff sleep early
        try { dialing?.close() } catch (_: Exception) {} // aborts a connect() in flight
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("9d3f1a52-6c0e-4b7a-9f0c-7a2c1e4d5b61")
    }
}
