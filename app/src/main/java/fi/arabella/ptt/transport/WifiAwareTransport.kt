package fi.arabella.ptt.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

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
 * Reconnect, at three levels:
 * - A link to a peer we dialled is one [Dial]. When it drops, times out or its data path
 *   is lost, the dial ends once and the next one is scheduled with [Backoff], for as long
 *   as discovery still sees that peer. `onServiceLost` forgets the peer; rediscovery dials again.
 * - Accepted links are the other side's to restore: it dialled us, it dials again.
 * - The whole Aware session dies when Wi-Fi is turned off. The state broadcast and a
 *   backoff re-attach bring it back, republishing and resubscribing from scratch.
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
    private val idBytes: ByteArray = ByteBuffer.allocate(4).putInt(localId).array()

    @Volatile private var running = false
    @Volatile private var session: WifiAwareSession? = null
    @Volatile private var publish: PublishDiscoverySession? = null
    @Volatile private var subscribe: SubscribeDiscoverySession? = null
    @Volatile private var server: ServerSocket? = null
    private val links = CopyOnWriteArrayList<StreamLink>()
    private val responderCallbacks = CopyOnWriteArrayList<ConnectivityManager.NetworkCallback>()
    private val peers = ConcurrentHashMap<Int, PeerHandle>()      // every publisher discovery currently sees
    private val dials = ConcurrentHashMap<Int, Dial>()            // peers we are dialling or linked to
    private val backoffs = ConcurrentHashMap<Int, Backoff>()
    private val attachBackoff = Backoff()
    private lateinit var onPacket: (ByteArray, Transport, Any?) -> Unit
    private lateinit var onStatus: (String) -> Unit

    /** Aware comes and goes with Wi-Fi; re-attach when it is back, drop everything when it is gone. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!running) return
            if (manager?.isAvailable == true) {
                if (session == null) attach()
            } else if (session != null) {
                dropSession()
                onStatus("Aware: unavailable (Wi-Fi off?), waiting")
            }
        }
    }

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onPacket = onPacket
        this.onStatus = onStatus
        if (manager == null) {
            onStatus("Aware: not supported on this phone")
            return
        }
        running = true
        transportThread("ptt-aware-accept", { onStatus("Aware accept stopped: ${it.message}") }) { acceptLoop() }
        ContextCompat.registerReceiver(
            appContext, stateReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        attach()
    }

    // ---- session ------------------------------------------------------------------

    /** Attaches, or re-attaches after the session died; waits with backoff while Aware is unavailable. */
    private fun attach() {
        if (!running || session != null) return
        val m = manager ?: return
        if (!m.isAvailable) {
            retryAttach("Aware: unavailable (Wi-Fi off?), waiting")
            return
        }
        reporting(onStatus, "Aware attach") {
            m.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession) {
                    if (!running) { s.close(); return }
                    session = s
                    attachBackoff.reset()
                    startPublish(s)
                    startSubscribe(s)
                    onStatus("Aware: attached, discovering…")
                }
                override fun onAttachFailed() = retryAttach("Aware: attach failed, retrying")
                override fun onAwareSessionTerminated() {
                    dropSession()
                    retryAttach("Aware: session ended, re-attaching")
                }
            }, handler)
        }
    }

    private fun retryAttach(why: String) {
        if (!running) return
        onStatus(why)
        handler.postDelayed({ attach() }, attachBackoff.next())
    }

    /** Forgets peers, dials, discovery sessions and links: all of it hangs off the session. */
    private fun dropSession() {
        for (cb in responderCallbacks) unregister(cb)
        responderCallbacks.clear()
        for (d in dials.values) d.abandon()
        dials.clear()
        peers.clear()
        publish?.close(); publish = null
        subscribe?.close(); subscribe = null
        session?.close(); session = null
        for (link in links) link.close()            // their NAN interface is gone anyway
    }

    private fun startPublish(s: WifiAwareSession) {
        val cfg = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(idBytes)
            .build()
        reporting(onStatus, "Aware publish") {
            s.publish(cfg, object : DiscoverySessionCallback() {
                override fun onPublishStarted(ps: PublishDiscoverySession) {
                    publish = ps
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Responder accepting any initiator: one request covers all peers, and it
                        // survives individual data paths coming and going.
                        reporting(onStatus, "Aware responder") {
                            val spec = WifiAwareNetworkSpecifier.Builder(ps)
                                .setPskPassphrase(passphrase).setPort(port).build()
                            requestResponder(spec)
                        }
                    }
                }
                override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        reporting(onStatus, "Aware responder") {
                            val ps = publish ?: return@reporting
                            val spec = WifiAwareNetworkSpecifier.Builder(ps, peer)
                                .setPskPassphrase(passphrase).setPort(port).build()
                            requestResponder(spec)
                        }
                    }
                }
                override fun onSessionTerminated() {
                    publish = null
                    if (running && session != null) {
                        onStatus("Aware: publish ended, restarting")
                        handler.postDelayed({ session?.let { startPublish(it) } }, RESTART_MS)
                    }
                }
            }, handler)
        }
    }

    private fun startSubscribe(s: WifiAwareSession) {
        val cfg = SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build()
        reporting(onStatus, "Aware subscribe") {
            s.subscribe(cfg, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(ss: SubscribeDiscoverySession) { subscribe = ss }

                override fun onServiceDiscovered(peer: PeerHandle, ssi: ByteArray?, filters: List<ByteArray>?) {
                    if (ssi == null || ssi.size < 4) return
                    val peerId = ByteBuffer.wrap(ssi).int
                    if (peerId == localId) return
                    peers[peerId] = peer                      // a fresh handle each time it (re)appears
                    // Tie-break: lower id initiates, so each pair gets exactly one link.
                    if (localId < peerId) dial(peerId)
                }

                /** Android 11+: the peer went out of range. Forget it; rediscovery starts a new dial. */
                override fun onServiceLost(peer: PeerHandle, reason: Int) {
                    val id = peers.entries.firstOrNull { it.value == peer }?.key ?: return
                    peers.remove(id)
                    dials.remove(id)?.abandon()
                    if (running) onStatus("Aware: lost ${hex(id)}")
                }

                override fun onSessionTerminated() {
                    subscribe = null
                    if (running && session != null) {
                        onStatus("Aware: subscribe ended, restarting")
                        handler.postDelayed({ session?.let { startSubscribe(it) } }, RESTART_MS)
                    }
                }
            }, handler)
        }
    }

    // ---- data paths ---------------------------------------------------------------

    private fun request(spec: WifiAwareNetworkSpecifier): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

    /** Responder side: keep the request registered; peers dial our [server] when their path is up. */
    private fun requestResponder(spec: WifiAwareNetworkSpecifier) {
        val cb = object : ConnectivityManager.NetworkCallback() {}
        responderCallbacks.add(cb)
        connectivity.requestNetwork(request(spec), cb)
    }

    /** Initiator side: one [Dial] per peer at a time; the dial schedules its own successor when it ends. */
    private fun dial(peerId: Int) {
        if (!running) return
        val peer = peers[peerId] ?: return
        val ss = subscribe ?: return
        val d = Dial(peerId)
        if (dials.putIfAbsent(peerId, d) != null) return
        onStatus("Aware: connecting to ${hex(peerId)}")
        try {
            ss.sendMessage(peer, 0, idBytes)              // wakes pre-Android-12 publishers
            val spec = WifiAwareNetworkSpecifier.Builder(ss, peer).setPskPassphrase(passphrase).build()
            connectivity.requestNetwork(request(spec), d, DIAL_TIMEOUT_MS)
        } catch (e: Exception) {
            d.fail("Aware: request for ${hex(peerId)} failed (${e.message})")
        }
    }

    private fun backoffFor(peerId: Int) = backoffs.computeIfAbsent(peerId) { Backoff() }

    private fun unregister(cb: ConnectivityManager.NetworkCallback) {
        try { connectivity.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    /**
     * One attempt to hold a link to [peerId]: request the data path, dial the peer's port
     * when it appears, then keep both until something ends it. Ends exactly once, whichever
     * of the link reader, the network callback or the timeout gets there first.
     */
    private inner class Dial(val peerId: Int) : ConnectivityManager.NetworkCallback() {
        private val dialing = AtomicBoolean()
        private val finished = AtomicBoolean()
        @Volatile var link: StreamLink? = null

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
            val addr = info.peerIpv6Addr ?: return
            if (finished.get() || !dialing.compareAndSet(false, true)) return
            transportThread("ptt-aware-dial", { fail("Aware: dial died (${it.message})") }) {
                try {
                    val sock = network.socketFactory.createSocket()
                    sock.connect(InetSocketAddress(addr, info.port), DIAL_TIMEOUT_MS)
                    if (finished.get()) { sock.close(); return@transportThread }
                    backoffFor(peerId).reset()
                    addLink(sock, "connected to ${hex(peerId)}", this)
                } catch (e: IOException) {
                    fail("Aware: dial ${hex(peerId)} failed (${e.message})")
                }
            }
        }
        override fun onLost(network: Network) = fail("Aware: path to ${hex(peerId)} lost")
        override fun onUnavailable() = fail("Aware: ${hex(peerId)} did not answer")

        /** Ends this attempt and, while discovery still sees the peer, schedules the next one. */
        fun fail(why: String) {
            if (!finish()) return
            if (!running) return
            onStatus(why)
            if (peers.containsKey(peerId)) handler.postDelayed({ dial(peerId) }, backoffFor(peerId).next())
        }

        /** Ends this attempt silently, for teardown. */
        fun abandon() { finish() }

        private fun finish(): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            unregister(this)
            dials.remove(peerId, this)
            link?.close()
            return true
        }
    }

    // ---- links --------------------------------------------------------------------

    /** Serves incoming data-path connections for the whole session; re-listens if the socket dies. */
    private fun acceptLoop() {
        val backoff = Backoff()
        while (running) {
            val srv = try {
                ServerSocket(port)
            } catch (e: IOException) {
                val wait = backoff.next()
                onStatus("Aware: can't listen on $port (${e.message}), retry in ${wait / 1000}s")
                if (!sleepQuietly(wait)) return
                continue
            }
            server = srv
            if (!running) { srv.close(); return }
            backoff.reset()
            while (running) {
                val s = try { srv.accept() } catch (_: IOException) { break }
                addLink(s, "accepted ${s.inetAddress.hostAddress}", null)
            }
            try { srv.close() } catch (_: IOException) {}
            server = null
            if (running && !sleepQuietly(backoff.next())) return
        }
    }

    private fun addLink(socket: Socket, why: String, dial: Dial?) {
        socket.tcpNoDelay = true
        val link = StreamLink(socket.inetAddress.hostAddress ?: "?", socket.getInputStream(), socket.getOutputStream()) { socket.close() }
        dial?.link = link
        links.add(link)
        onStatus("Aware: $why (${links.size} link${if (links.size == 1) "" else "s"})")
        transportThread("ptt-aware-rx", { onStatus("Aware rx stopped: ${it.message}") }) {
            try {
                link.readLoop { onPacket(it, this, link) }
            } catch (e: IOException) {
                if (running && dial == null) onStatus("Aware: ${link.label} dropped")
            } finally {
                links.remove(link)
                link.close()
                dial?.fail("Aware: ${hex(dial.peerId)} dropped, reconnecting")
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
        handler.removeCallbacksAndMessages(null)          // pending re-attach and re-dial timers
        try { appContext.unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        try { server?.close() } catch (_: IOException) {}
        server = null
        dropSession()
        links.clear()
        backoffs.clear()
    }

    companion object {
        const val SERVICE_NAME = "arabella_ptt"
        private const val DIAL_TIMEOUT_MS = 20_000
        private const val RESTART_MS = 2_000L
    }
}
