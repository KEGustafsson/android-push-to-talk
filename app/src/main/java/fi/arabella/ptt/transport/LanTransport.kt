package fi.arabella.ptt.transport

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * UDP multicast on the local network. Every phone joins the same group; whoever
 * is talking sends, everyone else plays. No server, no discovery needed.
 *
 * Note: multicast is often blocked on guest/isolated Wi-Fi. On a boat router
 * or a phone hotspot it works fine.
 */
class LanTransport(
    context: Context,
    private val group: String = "239.255.42.1",
    private val port: Int = 47474
) : Transport {

    override val name = "LAN"
    override val relayWithin = false

    private val appContext = context.applicationContext
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var rxThread: Thread? = null
    @Volatile private var running = false
    private val groupAddr: InetAddress by lazy { InetAddress.getByName(group) }

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("ptt-multicast").apply {
            setReferenceCounted(false)
            acquire()
        }

        val nic = pickInterface()
        val s = MulticastSocket(port).apply {
            reuseAddress = true
            timeToLive = 1
            if (nic != null) {
                networkInterface = nic
                joinGroup(InetSocketAddress(groupAddr, port), nic)
            } else {
                joinGroup(groupAddr)
            }
        }
        socket = s
        running = true

        rxThread = thread(name = "ptt-lan-rx") {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    onPacket(buf.copyOf(p.length), this, null)
                } catch (e: IOException) {
                    if (running) onStatus("LAN rx error: ${e.message}")
                }
            }
        }
        onStatus("LAN: $group:$port via ${nic?.name ?: "default"}")
    }

    override fun send(packet: ByteArray, except: Any?) {
        try {
            socket?.send(DatagramPacket(packet, packet.size, groupAddr, port))
        } catch (_: IOException) { /* transient, drop the frame */ }
    }

    override fun stop() {
        running = false
        socket?.let {
            try { it.leaveGroup(groupAddr) } catch (_: Exception) {}
            it.close()
        }
        socket = null
        rxThread?.join(500)
        rxThread = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    /** Prefer wlan0; otherwise first up, non-loopback, multicast-capable interface with an IPv4 address. */
    private fun pickInterface(): NetworkInterface? {
        val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        val usable = all.filter { nic ->
            nic.isUp && !nic.isLoopback && nic.supportsMulticast() &&
                nic.inetAddresses.toList().any { it.address.size == 4 }
        }
        return usable.firstOrNull { it.name.startsWith("wlan") } ?: usable.firstOrNull()
    }
}
