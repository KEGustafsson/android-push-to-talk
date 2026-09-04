package fi.crewradio.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
 *
 * Reconnect: the receive thread owns the socket and re-opens it with [Backoff] whenever
 * it breaks or there is no Wi-Fi yet. A Wi-Fi interface change (dropped and came back,
 * new address, hotspot came up) closes the socket on purpose so it is re-opened and the
 * group re-joined on the new interface — the kernel forgets memberships when a link goes down.
 */
class LanTransport(
    context: Context,
    private val group: String = "239.255.42.1",
    private val port: Int = 47474
) : Transport {

    override val name = "LAN"
    override val relayWithin = false

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val groupAddr: InetAddress by lazy { InetAddress.getByName(group) }
    private val backoff = Backoff()
    private val wake = Semaphore(0)                      // released to cut a backoff wait short
    private val lifecycle = Any()                        // orders "publish a socket" against "stop and close it"

    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var openedOn: String? = null       // "wlan0/192.168.0.35" while a socket is up
    @Volatile private var ownAddr: InetAddress? = null
    @Volatile private var broadcastAddr: InetAddress? = null
    @Volatile private var heard = false
    @Volatile private var running = false
    private var rxThread: Thread? = null
    private var lock: WifiManager.MulticastLock? = null
    private lateinit var onPacket: (ByteArray, Transport, Any?) -> Unit
    private lateinit var onStatus: (String) -> Unit

    /**
     * Re-opens the socket around Wi-Fi changes. Losing Wi-Fi closes the socket outright,
     * even though a wildcard-bound UDP socket would happily stay open: the kernel drops the
     * multicast membership with the link, and Wi-Fi usually comes back on the same interface
     * with the same DHCP address, so a "did it change?" check alone would never re-join.
     */
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val nic = lp.interfaceName ?: return
            val addr = lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: return
            if (running && "$nic/$addr" != openedOn) rejoin()
        }
        override fun onLost(network: Network) {
            if (!running) return
            onStatus("LAN: Wi-Fi lost, waiting for it")
            rejoin()                                       // rx loop then waits in "no Wi-Fi" until it is back
        }
    }

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onPacket = onPacket
        this.onStatus = onStatus
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("ptt-multicast").apply {
            setReferenceCounted(false)
            acquire()
        }
        running = true
        rxThread = transportThread("ptt-lan-rx", { onStatus("LAN rx stopped: ${it.message}") }) { rxLoop() }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback
        )
    }

    /** Opens the socket, receives until it breaks, waits, repeats — for as long as the session runs. */
    private fun rxLoop() {
        while (running) {
            val nic = try {
                pickInterface()
            } catch (e: Exception) {                   // enumerating interfaces can itself fail mid-change
                val wait = backoff.next()
                onStatus("LAN: can't list interfaces (${e.message}), retry in ${wait / 1000}s")
                pause(wait)
                continue
            }
            if (nic == null) {
                onStatus("LAN: no Wi-Fi, waiting")
                pause(backoff.next())
                continue
            }
            val s = try {
                openSocket(nic)
            } catch (e: Exception) {
                val wait = backoff.next()
                onStatus("LAN: can't open (${e.message}), retry in ${wait / 1000}s")
                pause(wait)
                continue
            }
            val own = ipv4Of(nic)
            synchronized(lifecycle) {
                if (!running) { s.close(); return }   // stop() ran while we were opening
                socket = s
            }
            ownAddr = own
            openedOn = "${nic.name}/${own?.hostAddress}"
            broadcastAddr = broadcastAddressOf(nic)
            heard = false
            val bc = broadcastAddr?.hostAddress?.let { " + $it" } ?: ""
            onStatus("LAN: $group:$port$bc via ${nic.name}")
            receiveUntilClosed(s)
            socket = null
            openedOn = null
            if (running) pause(backoff.next())
        }
    }

    /**
     * Bound with SO_REUSEADDR set *before* the bind, so a second listener on the port is possible.
     *
     * Deliberately not an `apply` block: inside one, `port` resolves to
     * [java.net.DatagramSocket.getPort] — the *remote* port, -1 on an unconnected socket — instead
     * of this transport's port, and joinGroup then dies with "port out of range:-1".
     */
    private fun openSocket(nic: NetworkInterface): MulticastSocket {
        val s = MulticastSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            s.timeToLive = 1
            s.broadcast = true
            s.networkInterface = nic
            s.joinGroup(InetSocketAddress(groupAddr, port), nic)
        } catch (e: Exception) {
            s.close()                                  // a bound-but-unjoined socket would hold the port
            throw e
        }
        return s
    }

    private fun receiveUntilClosed(s: MulticastSocket) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (!heard && p.address != ownAddr) {          // our own frames loop back; they don't count
                    heard = true
                    backoff.reset()                            // a working network: the next reopen starts fast again
                    onStatus("LAN: hearing ${p.address.hostAddress}")
                }
                onPacket(buf.copyOf(p.length), this, null)
            } catch (e: IOException) {
                if (running && !s.isClosed) onStatus("LAN: socket error (${e.message}), reopening")
                return
            }
        }
    }

    /** Closes the current socket so [rxLoop] re-opens on whatever Wi-Fi now offers, without the usual wait. */
    private fun rejoin() {
        backoff.reset()
        socket?.close()
        wake.release()
    }

    /** Waits up to [ms], or less if [rejoin] or [stop] has something new. */
    private fun pause(ms: Long) {
        wake.drainPermits()
        if (running) wake.tryAcquire(ms, TimeUnit.MILLISECONDS)
    }

    /** Sends to the group and, when we know one, to the subnet broadcast address as well. */
    override fun send(packet: ByteArray, except: Any?): Boolean {
        val s = socket ?: return false
        sendTo(s, packet, groupAddr)
        broadcastAddr?.let { sendTo(s, packet, it) }
        return true
    }

    private fun sendTo(s: MulticastSocket, packet: ByteArray, to: InetAddress) {
        try {
            s.send(DatagramPacket(packet, packet.size, to, port))
        } catch (_: IOException) { /* transient, drop the frame */ }
    }

    override fun stop() {
        synchronized(lifecycle) {
            running = false
            socket?.let {
                try { it.leaveGroup(groupAddr) } catch (_: Exception) {}
                it.close()
            }
        }
        try { connectivity.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
        wake.release()
        rxThread?.join(500)
        rxThread = null
        socket = null
        heard = false
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    /** Prefer wlan0; otherwise first up, non-loopback, multicast-capable interface with an IPv4 address. */
    private fun pickInterface(): NetworkInterface? {
        val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        val usable = all.filter { nic ->
            nic.isUp && !nic.isLoopback && nic.supportsMulticast() && ipv4Of(nic) != null
        }
        return usable.firstOrNull { it.name.startsWith("wlan") } ?: usable.firstOrNull()
    }

    private fun ipv4Of(nic: NetworkInterface): InetAddress? =
        nic.inetAddresses.toList().firstOrNull { it is Inet4Address }

    /** The interface's IPv4 broadcast address, e.g. 192.168.1.255; null on IPv6-only interfaces. */
    private fun broadcastAddressOf(nic: NetworkInterface): InetAddress? =
        nic.interfaceAddresses.firstNotNullOfOrNull { it.broadcast }
}
