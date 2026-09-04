package fi.arabella.ptt.transport

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/**
 * UDP on the local network. Every phone joins the same multicast group; whoever is
 * talking sends, everyone else plays. No server, no discovery needed.
 *
 * Multicast alone is unreliable on consumer gear: access points rate-limit or drop
 * traffic to groups nobody has an IGMP querier for, and some routers filter it outright.
 * So every frame also goes to the interface's IPv4 broadcast address, which survives far
 * more networks. The socket is bound to the wildcard address, so it picks up both copies;
 * the engine's seen-cache drops whichever arrives second, and the extra traffic is one
 * more 60-byte Opus datagram per 20 ms. Client isolation ("AP isolation", most guest
 * Wi-Fi) blocks both, and then nothing but Bluetooth or Wi-Fi Aware will do.
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
    @Volatile private var broadcastAddr: InetAddress? = null
    @Volatile private var heard = false
    private val groupAddr: InetAddress by lazy { InetAddress.getByName(group) }

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("ptt-multicast").apply {
            setReferenceCounted(false)
            acquire()
        }

        val nic = pickInterface()
        broadcastAddr = nic?.let { broadcastAddressOf(it) }
        val s = try {
            openSocket(nic)
        } catch (e: Exception) {
            releaseLock()                     // no socket, so nothing would ever release it
            throw e                           // reported by the engine
        }
        socket = s
        running = true

        rxThread = transportThread("ptt-lan-rx", { onStatus("LAN rx stopped: ${it.message}") }) {
            receiveLoop(s, onPacket, onStatus)
        }
        val via = nic?.name ?: "default"
        val bc = broadcastAddr?.hostAddress?.let { " + $it" } ?: ""
        onStatus("LAN: $group:$port$bc via $via")
    }

    /**
     * Bound with SO_REUSEADDR set *before* the bind, so a second listener on the port is possible.
     *
     * Deliberately not an `apply` block: inside one, `port` resolves to
     * [java.net.DatagramSocket.getPort] — the *remote* port, -1 on an unconnected socket — instead
     * of this transport's port, and joinGroup then dies with "port out of range:-1".
     */
    private fun openSocket(nic: NetworkInterface?): MulticastSocket {
        val s = MulticastSocket(null as SocketAddress?)
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        s.timeToLive = 1
        s.broadcast = true
        if (nic != null) {
            s.networkInterface = nic
            s.joinGroup(InetSocketAddress(groupAddr, port), nic)
        } else {
            s.joinGroup(groupAddr)
        }
        return s
    }

    private fun receiveLoop(
        s: MulticastSocket,
        onPacket: (ByteArray, Transport, Any?) -> Unit,
        onStatus: (String) -> Unit
    ) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (!heard) {
                    heard = true
                    onStatus("LAN: hearing ${p.address.hostAddress}")   // once, so "is anything arriving?" is answerable
                }
                onPacket(buf.copyOf(p.length), this, null)
            } catch (e: IOException) {
                if (!running || s.isClosed) return                     // normal teardown
                onStatus("LAN rx error: ${e.message}")
                return                                                 // a broken socket never heals; don't spin on it
            }
        }
    }

    /** Sends to the group and, when we know one, to the subnet broadcast address as well. */
    override fun send(packet: ByteArray, except: Any?) {
        val s = socket ?: return
        sendTo(s, packet, groupAddr)
        broadcastAddr?.let { sendTo(s, packet, it) }
    }

    private fun sendTo(s: MulticastSocket, packet: ByteArray, to: InetAddress) {
        try {
            s.send(DatagramPacket(packet, packet.size, to, port))
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
        heard = false
        releaseLock()
    }

    private fun releaseLock() {
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

    /** The interface's IPv4 broadcast address, e.g. 192.168.1.255; null on IPv6-only interfaces. */
    private fun broadcastAddressOf(nic: NetworkInterface): InetAddress? =
        nic.interfaceAddresses.firstNotNullOfOrNull { it.broadcast }
}
