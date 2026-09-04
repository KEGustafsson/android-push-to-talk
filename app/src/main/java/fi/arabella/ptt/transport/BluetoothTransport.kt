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
    private val links = CopyOnWriteArrayList<StreamLink>()
    private var server: BluetoothServerSocket? = null
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

        // A failed listen must not stop us from dialling out, so the two halves are independent.
        try {
            val srv = adapter.listenUsingRfcommWithServiceRecord("PTT", SERVICE_UUID)
            server = srv
            transportThread("ptt-bt-accept", { onStatus("BT accept stopped: ${it.message}") }) {
                acceptLoop(srv)
            }
        } catch (e: Exception) {
            onStatus("BT: can't listen (${e.message})")
        }

        peer?.let { dev ->
            transportThread("ptt-bt-connect", { onStatus("BT connect stopped: ${it.message}") }) {
                connectLoop(dev)
            }
        }
        onStatus("BT: listening" + (peer?.let { ", connecting to ${label(it)}" } ?: ""))
    }

    private fun acceptLoop(srv: BluetoothServerSocket) {
        while (running) {
            val s = try {
                srv.accept()
            } catch (e: Exception) {
                if (running) onStatus("BT accept error: ${e.message}")
                return
            }
            addLink(s, "accepted ${label(s.remoteDevice)}")
        }
    }

    /**
     * Dials the chosen peer, retrying a few times: the other phone may not have pressed
     * Connect yet, and Samsung stacks routinely fail the first attempt after pairing.
     * A socket that failed to connect is closed — a leaked one keeps the RFCOMM channel
     * busy and makes every later attempt fail too.
     */
    private fun connectLoop(dev: BluetoothDevice) {
        cancelDiscoveryQuietly()
        var attempt = 0
        while (running && attempt < CONNECT_ATTEMPTS) {
            attempt++
            var socket: BluetoothSocket? = null
            try {
                socket = dev.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                addLink(socket, "connected to ${label(dev)}")
                return
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                if (!running) return
                onStatus("BT connect ${label(dev)} failed $attempt/$CONNECT_ATTEMPTS (${e.message})")
                try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { return }
            }
        }
        if (running) onStatus("BT: gave up on ${label(dev)} — press Connect on it first")
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

    private fun addLink(socket: BluetoothSocket, why: String) {
        val link = StreamLink(label(socket.remoteDevice), socket.inputStream, socket.outputStream) { socket.close() }
        links.add(link)
        onStatus("BT: $why (${links.size} link${if (links.size == 1) "" else "s"})")
        transportThread("ptt-bt-rx-${socket.remoteDevice.address}", { onStatus("BT rx stopped: ${it.message}") }) {
            try {
                link.readLoop { onPacket(it, this, link) }
            } catch (e: IOException) {
                if (running) onStatus("BT: ${link.label} dropped")
            } finally {
                links.remove(link)
                link.close()
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
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        for (link in links) link.close()
        links.clear()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("9d3f1a52-6c0e-4b7a-9f0c-7a2c1e4d5b61")
        private const val CONNECT_ATTEMPTS = 5
        private const val RETRY_MS = 1500L
    }
}
