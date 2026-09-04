package fi.arabella.ptt.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Bluetooth Classic RFCOMM. Every phone listens as a server; optionally also
 * connects to one chosen paired peer. With the engine's relay enabled, a phone
 * holding several links forwards between them, so a chain A-B-C works.
 *
 * Throughput: 16 kHz PCM16 = 32 kB/s, comfortably inside RFCOMM's practical limit.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    context: Context,
    private val peer: BluetoothDevice?
) : Transport {

    override val name = "BT"
    override val relayWithin = true

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

        server = adapter.listenUsingRfcommWithServiceRecord("PTT", SERVICE_UUID)
        thread(name = "ptt-bt-accept") {
            while (running) {
                try {
                    val s = server?.accept() ?: break
                    addLink(s, "accepted ${s.remoteDevice.name}")
                } catch (e: IOException) {
                    if (running) onStatus("BT accept error: ${e.message}")
                    break
                }
            }
        }

        peer?.let { dev ->
            thread(name = "ptt-bt-connect") {
                adapter.cancelDiscovery()
                var attempt = 0
                while (running && attempt < 5) {
                    attempt++
                    try {
                        val s = dev.createRfcommSocketToServiceRecord(SERVICE_UUID)
                        s.connect()
                        addLink(s, "connected to ${dev.name}")
                        return@thread
                    } catch (e: IOException) {
                        onStatus("BT connect ${dev.name} failed ($attempt/5)")
                        Thread.sleep(1500)
                    }
                }
            }
        }
        onStatus("BT: listening" + (peer?.let { ", connecting to ${it.name}" } ?: ""))
    }

    private fun addLink(socket: BluetoothSocket, why: String) {
        val link = StreamLink(socket.remoteDevice.name ?: socket.remoteDevice.address,
            socket.inputStream, socket.outputStream) { socket.close() }
        links.add(link)
        onStatus("BT: $why (${links.size} link${if (links.size == 1) "" else "s"})")
        thread(name = "ptt-bt-rx-${socket.remoteDevice.address}") {
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
        try { server?.close() } catch (_: IOException) {}
        server = null
        for (link in links) link.close()
        links.clear()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("9d3f1a52-6c0e-4b7a-9f0c-7a2c1e4d5b61")
    }
}
