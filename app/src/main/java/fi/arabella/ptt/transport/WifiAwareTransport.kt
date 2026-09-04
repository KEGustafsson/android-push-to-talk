package fi.arabella.ptt.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Wi-Fi Aware (NAN): infrastructure-free, no access point, no pairing.
 *
 * Every phone publishes AND subscribes to the same service. Each side carries
 * its node id in the service-specific info. When a subscriber discovers a peer
 * it initiates a data path only if its own id is lower than the peer's, so
 * each pair gets exactly one link. Data paths are TCP over the NAN IPv6 link,
 * framed by [StreamLink]. Combined with the engine's relay this forms an
 * app-level flooding mesh: A-B-C works even if A and C can't see each other.
 *
 * Requires Android 10+ (API 29) for WifiAwareNetworkInfo; the publisher uses
 * accept-any on Android 12+, otherwise it waits for a wake-up message and
 * requests the path per peer.
 */
@SuppressLint("MissingPermission")
class WifiAwareTransport(
    context: Context,
    private val localId: Int,
    private val passphrase: String = "arabella-ptt",
    private val port: Int = 47475
) : Transport {

    override val name = "Aware"
    override val relayWithin = true

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())

    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private var server: ServerSocket? = null
    private val links = CopyOnWriteArrayList<StreamLink>()
    private val callbacks = CopyOnWriteArrayList<ConnectivityManager.NetworkCallback>()
    private val pendingPeers = ConcurrentHashMap<Int, Boolean>()   // peerId -> initiating
    @Volatile private var running = false
    private lateinit var onPacket: (ByteArray, Transport, Any?) -> Unit
    private lateinit var onStatus: (String) -> Unit

    private val idBytes: ByteArray = ByteBuffer.allocate(4).putInt(localId).array()

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onPacket = onPacket
        this.onStatus = onStatus
        if (manager == null || !manager.isAvailable) {
            onStatus("Aware: not available on this phone (or Wi-Fi off)")
            return
        }
        running = true

        server = ServerSocket(port)
        thread(name = "ptt-aware-accept") {
            while (running) {
                try {
                    val s = server?.accept() ?: break
                    addLink(s, "accepted ${s.inetAddress.hostAddress}")
                } catch (e: IOException) {
                    if (running) onStatus("Aware accept error: ${e.message}")
                    break
                }
            }
        }

        manager.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                session = s
                startPublish(s)
                startSubscribe(s)
                onStatus("Aware: attached, discovering…")
            }
            override fun onAttachFailed() = onStatus("Aware: attach failed")
        }, handler)
    }

    private fun startPublish(s: WifiAwareSession) {
        val cfg = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(idBytes)
            .build()
        s.publish(cfg, object : DiscoverySessionCallback() {
            override fun onPublishStarted(ps: PublishDiscoverySession) {
                publish = ps
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Responder accepting any initiator: one request covers all peers.
                    val spec = WifiAwareNetworkSpecifier.Builder(ps)
                        .setPskPassphrase(passphrase).setPort(port).build()
                    requestNetwork(spec, null)
                }
            }
            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    val spec = WifiAwareNetworkSpecifier.Builder(publish ?: return, peer)
                        .setPskPassphrase(passphrase).setPort(port).build()
                    requestNetwork(spec, null)
                }
            }
            override fun onSessionTerminated() = onStatus("Aware: publish ended")
        }, handler)
    }

    private fun startSubscribe(s: WifiAwareSession) {
        val cfg = SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build()
        s.subscribe(cfg, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(ss: SubscribeDiscoverySession) { subscribe = ss }

            override fun onServiceDiscovered(peer: PeerHandle, ssi: ByteArray?, filters: List<ByteArray>?) {
                if (ssi == null || ssi.size < 4) return
                val peerId = ByteBuffer.wrap(ssi).int
                if (peerId == localId) return
                // Tie-break: lower id initiates, so each pair gets exactly one link.
                if (localId >= peerId) return
                if (pendingPeers.putIfAbsent(peerId, true) != null) return
                onStatus("Aware: found peer ${peerId.toUInt().toString(16)}")
                val ss = subscribe ?: return
                ss.sendMessage(peer, 0, idBytes) // wakes pre-Android-12 publishers
                val spec = WifiAwareNetworkSpecifier.Builder(ss, peer)
                    .setPskPassphrase(passphrase).build()
                requestNetwork(spec, peerId)
            }
            override fun onSessionTerminated() = onStatus("Aware: subscribe ended")
        }, handler)
    }

    private fun requestNetwork(spec: WifiAwareNetworkSpecifier, peerId: Int?) {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var dialed = false
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (peerId == null || dialed) return          // responder side: peer dials us
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val addr = info.peerIpv6Addr ?: return
                dialed = true
                thread(name = "ptt-aware-dial") {
                    try {
                        val sock = network.socketFactory.createSocket()
                        sock.connect(InetSocketAddress(addr, info.port), 5000)
                        addLink(sock, "connected to ${peerId.toUInt().toString(16)}")
                    } catch (e: IOException) {
                        onStatus("Aware: dial failed (${e.message})")
                        pendingPeers.remove(peerId)
                        dialed = false
                    }
                }
            }
            override fun onLost(network: Network) { peerId?.let { pendingPeers.remove(it) } }
            override fun onUnavailable() { peerId?.let { pendingPeers.remove(it) } }
        }
        callbacks.add(cb)
        connectivity.requestNetwork(req, cb)
    }

    private fun addLink(socket: Socket, why: String) {
        socket.tcpNoDelay = true
        val link = StreamLink(socket.inetAddress.hostAddress ?: "?", socket.getInputStream(), socket.getOutputStream()) { socket.close() }
        links.add(link)
        onStatus("Aware: $why (${links.size} link${if (links.size == 1) "" else "s"})")
        thread(name = "ptt-aware-rx") {
            try {
                link.readLoop { onPacket(it, this, link) }
            } catch (e: IOException) {
                if (running) onStatus("Aware: ${link.label} dropped")
            } finally {
                links.remove(link)
                link.close()
            }
        }
    }

    override fun send(packet: ByteArray, except: Any?) {
        for (link in links) {
            if (link === except) continue
            try { link.send(packet) } catch (_: IOException) {}
        }
    }

    override fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        server = null
        for (link in links) link.close()
        links.clear()
        for (cb in callbacks) try { connectivity.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        callbacks.clear()
        publish?.close(); publish = null
        subscribe?.close(); subscribe = null
        session?.close(); session = null
        pendingPeers.clear()
    }

    companion object {
        const val SERVICE_NAME = "arabella_ptt"
    }
}
