package fi.arabella.ptt

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import fi.arabella.ptt.audio.AudioCapture
import fi.arabella.ptt.audio.AudioConfig
import fi.arabella.ptt.audio.Mixer
import fi.arabella.ptt.audio.OpusDecoder
import fi.arabella.ptt.audio.OpusEncoder
import fi.arabella.ptt.transport.Transport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * One crew member as the roster sees it. [name] stays null for a phone on a build that
 * predates hello packets; it is then listed from its audio, by id.
 *
 * [via] is the transport this phone heard it on last; [hops] how many relays that took;
 * [transports] the flags from its hello ([Hello.describe]), i.e. what it is connected to.
 */
class Peer(
    val id: Int,
    val name: String?,
    val transports: Int,
    val via: String,
    val hops: Int,
    val talking: Boolean
) {
    val label: String get() = name ?: id.toUInt().toString(16)
}

/**
 * Glue between mic, codec, transports, relay, roster and mixer.
 *
 * HALF_DUPLEX: mic runs only while the talk button is held; incoming audio is
 *              not played while transmitting (radio behaviour) but is still relayed.
 * FULL_DUPLEX: mic on/off is a toggle; incoming audio always plays and
 *              simultaneous talkers are mixed.
 *
 * Codec: outgoing frames go out as Opus ([Packet.Codec.OPUS]) when [codec] says
 * so and the platform encoder starts, otherwise as raw PCM. Incoming packets are
 * decoded by what their header says, one [OpusDecoder] per remote sender, so a
 * mixed crew of Opus and PCM phones just works.
 *
 * Relay: every packet carries (senderId, seq) and a ttl. A packet not seen before
 * is played and, if relay is on and ttl allows, forwarded on every transport
 * (excluding the link it came from) with ttl decremented. Duplicates are dropped
 * by the seen-cache, so a flood across a multi-hop Aware/BT topology terminates.
 * Running several transports at once makes this phone a bridge (e.g. boat Wi-Fi <-> Aware).
 *
 * Roster: while connected a heartbeat thread sends a [Hello] every second, and every
 * hello or audio packet heard refreshes that sender's entry. A sender silent for
 * [PEER_TIMEOUT_MS] is dropped; at most [MAX_NODES] are tracked. Timing uses the
 * monotonic clock, so a wall-clock change never ages or revives anyone. [onRoster]
 * fires only when the list actually changes.
 */
class PttEngine(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onRoster: (List<Peer>) -> Unit = {}
) {

    enum class Mode { HALF_DUPLEX, FULL_DUPLEX }

    @Volatile var mode: Mode = Mode.HALF_DUPLEX
        set(value) {
            if (field != value) {
                field = value
                stopTalking()
                onStatus("Mode: ${value.name.lowercase().replace('_', ' ')}")
            }
        }

    @Volatile var relay: Boolean = true

    /** Outgoing audio codec, PCM or OPUS. Takes effect the next time the mic is keyed. */
    @Volatile var codec: Packet.Codec = Packet.Codec.OPUS

    /** Hop budget stamped on packets this phone originates. */
    @Volatile var maxHops: Int = AudioConfig.DEFAULT_TTL

    val senderId: Int = Random.nextInt()
    /** What the crew sees this phone as: the Android device name, falling back to the model. */
    val displayName: String = deviceName(context)
    val isTalking: Boolean get() = talking
    val isConnected: Boolean get() = transports.isNotEmpty()
    /** The roster as last published; the UI reads this when it (re)binds. */
    val roster: List<Peer> get() = lastRoster

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mixer = Mixer()
    private val transports = CopyOnWriteArrayList<Transport>()
    private var capture: AudioCapture? = null
    @Volatile private var encoder: OpusEncoder? = null
    private val decoders = ConcurrentHashMap<Int, OpusDecoder>()
    private val undecodable = ConcurrentHashMap.newKeySet<Int>()   // senders whose decoder failed; reported once
    private val packetCount = AtomicInteger()
    private val seq = AtomicInteger()                                // shared by the audio thread and the heartbeat
    @Volatile private var talking = false

    private val nodes = ConcurrentHashMap<Int, Node>()
    private var heartbeat: ScheduledExecutorService? = null
    @Volatile private var lastRoster: List<Peer> = emptyList()
    private var lastRosterKey = ""

    /** Everything we know about one sender; refreshed by its hellos and its audio. */
    private class Node {
        @Volatile var name: String? = null
        @Volatile var transports = 0
        @Volatile var via = "?"
        @Volatile var hops = 0
        @Volatile var lastSeen = 0L
        @Volatile var lastAudio = 0L
        @Volatile var talking = false
    }

    private val seenCapacity = 4096
    private val seen = object : LinkedHashMap<Long, Boolean>(seenCapacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > seenCapacity
    }

    /** Returns true if this (sender, seq) is new. */
    private fun markSeen(senderId: Int, seq: Int): Boolean {
        val key = (senderId.toLong() shl 32) or (seq.toLong() and 0xFFFF_FFFFL)
        synchronized(seen) { return seen.put(key, true) == null }
    }

    /** Starts playback and the given transports; any transport that fails to start is reported and dropped. */
    fun connect(list: List<Transport>) {
        disconnect()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        mixer.start()
        for (t in list) {
            transports.add(t)
            try {
                t.start(::onPacket, onStatus)
            } catch (e: Exception) {
                // A transport that never started would silently swallow every frame we hand it.
                onStatus("${t.name} failed: ${e.message}")
                transports.remove(t)
                try { t.stop() } catch (_: Exception) {}
            }
        }
        heartbeat = Executors.newSingleThreadScheduledExecutor { Thread(it, "ptt-heartbeat") }.also {
            it.scheduleAtFixedRate({ tick() }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Stops everything and releases codecs; safe to call when already idle. */
    fun disconnect() {
        stopTalking()
        heartbeat?.shutdownNow()
        heartbeat = null
        for (t in transports) t.stop()
        transports.clear()
        mixer.stop()
        releaseDecoders()
        synchronized(seen) { seen.clear() }
        nodes.clear()
        publishRoster()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /** Keys the mic: opens the encoder (if Opus) and the capture; on failure everything is released again. */
    fun startTalking() {
        if (talking || transports.isEmpty()) return
        talking = true
        encoder = if (codec == Packet.Codec.OPUS) {
            try {
                OpusEncoder { broadcast(Packet.Codec.OPUS, it) }
            } catch (e: Exception) {
                onStatus("Opus encoder unavailable, sending PCM")
                null
            }
        } else null
        val cap = AudioCapture { pcm ->
            val e = encoder
            if (e == null) {
                broadcast(Packet.Codec.PCM, pcm)
            } else {
                try {
                    e.encode(pcm)
                } catch (ex: Exception) {
                    encoder = null
                    e.release()
                    onStatus("Opus failed (${ex.message}), sending PCM")
                    broadcast(Packet.Codec.PCM, pcm)
                }
            }
        }
        try {
            cap.start()
        } catch (e: Exception) {
            cap.stop()                     // frees an AudioRecord that was created but never started
            encoder?.release()
            encoder = null
            talking = false
            onStatus("Mic error: ${e.message}")
            return
        }
        capture = cap
        onStatus(if (mode == Mode.FULL_DUPLEX) "Mic on" else "Transmitting")
    }

    /** Un-keys the mic and releases the capture and encoder. */
    fun stopTalking() {
        if (!talking) return
        talking = false
        capture?.stop()
        capture = null
        encoder?.release()
        encoder = null
        onStatus(if (mode == Mode.FULL_DUPLEX) "Mic off" else "Listening")
    }

    /** Full-duplex mic toggle. */
    fun toggleTalking() = if (talking) stopTalking() else startTalking()

    /** Stamps and sends one of our own packets on every transport. */
    private fun broadcast(codec: Packet.Codec, payload: ByteArray) {
        val s = seq.getAndIncrement()
        markSeen(senderId, s)
        val packet = Packet.encode(senderId, s, codec, maxHops, payload)
        for (t in transports) t.send(packet)
    }

    /** Receive path for every transport: dedupe, relay within the hop budget, then roster, then decode and play. */
    private fun onPacket(p: ByteArray, from: Transport, link: Any?) {
        val h = Packet.parse(p) ?: return
        if (h.senderId == senderId) return
        if (!markSeen(h.senderId, h.seq)) return          // duplicate via another path

        // A peer's ttl is capped at our own budget, so nobody can stamp 255 and ride further than we allow.
        val ttl = minOf(h.ttl, maxHops)
        if (relay && ttl > 1) {
            Packet.setTtl(p, ttl - 1)
            for (t in transports) {
                if (t === from) { if (t.relayWithin) t.send(p, except = link) }
                else t.send(p)
            }
        }

        if (h.codec == Packet.Codec.HELLO) {
            Hello.decode(p, Packet.HEADER, p.size - Packet.HEADER)?.let { heardHello(h.senderId, it, from, h.ttl) }
            return
        }
        heardAudio(h.senderId, from)

        if (packetCount.incrementAndGet() and 0xFF == 0) pruneDecoders()
        if (mode == Mode.HALF_DUPLEX && talking) return   // radio semantics

        when (h.codec) {
            Packet.Codec.PCM -> mixer.push(h.senderId, p, Packet.HEADER, p.size - Packet.HEADER)
            Packet.Codec.OPUS -> decodeOpus(h.senderId, p)
            Packet.Codec.HELLO -> Unit                        // handled above
        }
    }

    // ---- roster -------------------------------------------------------------------

    /** Heartbeat thread: announce ourselves, drop the silent, clear stale talking marks, publish if anything moved. */
    private fun tick() {
        sendHello()
        val now = SystemClock.elapsedRealtime()
        var changed = false
        for ((id, n) in nodes) {
            if (now - n.lastSeen > PEER_TIMEOUT_MS) {
                if (nodes.remove(id, n)) changed = true
            } else if (n.talking && now - n.lastAudio > TALK_HOLD_MS) {
                n.talking = false
                changed = true
            }
        }
        if (changed) publishRoster()
    }

    private fun sendHello() {
        var flags = 0
        for (t in transports) flags = flags or Hello.bitFor(t.name)
        broadcast(Packet.Codec.HELLO, Hello(displayName, flags, maxHops).encode())
    }

    private fun heardHello(id: Int, hello: Hello, from: Transport, ttlLeft: Int) {
        val n = nodeFor(id) ?: return
        n.name = hello.name
        n.transports = hello.transports
        n.via = from.name
        n.hops = (hello.ttl - ttlLeft).coerceAtLeast(0)
        n.lastSeen = SystemClock.elapsedRealtime()
        publishRoster()
    }

    /** Audio is proof of life too, and lights the talking mark; the roster is only republished when that flips. */
    private fun heardAudio(id: Int, from: Transport) {
        val n = nodeFor(id) ?: return
        val now = SystemClock.elapsedRealtime()
        n.lastSeen = now
        n.lastAudio = now
        n.via = from.name
        if (!n.talking) {
            n.talking = true
            publishRoster()
        }
    }

    /**
     * The entry for a sender, created on first sight — unless the roster is already full, in
     * which case an unknown sender is ignored. Sender ids are unauthenticated, so without a cap
     * anyone in radio range could grow the list without bound by cycling ids faster than the
     * 4 s expiry. The check and the insert are not one atomic step; a few over the cap is fine.
     */
    private fun nodeFor(id: Int): Node? =
        nodes[id] ?: if (nodes.size >= MAX_NODES) null else nodes.computeIfAbsent(id) { Node() }

    /** Rebuilds the list and hands it out only if it differs from the last one published. */
    @Synchronized
    private fun publishRoster() {
        val list = nodes.entries
            .map { (id, n) -> Peer(id, n.name, n.transports, n.via, n.hops, n.talking) }
            .sortedBy { it.label.lowercase() }
        val key = list.joinToString("|") { "${it.id}/${it.name}/${it.transports}/${it.via}/${it.hops}/${it.talking}" }
        if (key == lastRosterKey) return
        lastRosterKey = key
        lastRoster = list
        onRoster(list)
    }

    // ---- codecs -------------------------------------------------------------------

    /** Feeds one Opus packet to the sender's decoder and plays whatever frames come out. */
    private fun decodeOpus(sender: Int, p: ByteArray) {
        if (undecodable.contains(sender)) return
        val dec = try {
            decoderFor(sender) ?: return                     // over capacity and everyone is talking: drop
        } catch (e: Exception) {
            if (undecodable.size >= MAX_UNDECODABLE) undecodable.clear()
            undecodable.add(sender)
            onStatus("Opus decoder unavailable, can't play ${sender.toUInt().toString(16)}")
            return
        }
        synchronized(dec) {
            try {
                dec.decode(p, Packet.HEADER, p.size - Packet.HEADER) { frame ->
                    mixer.push(sender, frame, 0, frame.size)
                }
            } catch (e: Exception) {
                decoders.remove(sender)
                dec.release()
                onStatus("Opus decode error: ${e.message}")
            }
        }
    }

    /**
     * The sender's decoder, created on demand. Decoders are a bounded resource (each is a
     * MediaCodec), so at most [MAX_DECODERS] exist; a new sender beyond that evicts the
     * quietest one if it has paused, and is dropped if every slot is actively talking.
     */
    private fun decoderFor(sender: Int): OpusDecoder? {
        decoders[sender]?.let { return it }
        if (decoders.size >= MAX_DECODERS && !evictQuietest()) return null
        return decoders.computeIfAbsent(sender) { OpusDecoder() }   // atomic: never two codecs for one sender
    }

    /** Releases the decoder that has been quiet longest, if it has paused at all. */
    private fun evictQuietest(): Boolean {
        val (sender, dec) = decoders.entries.minByOrNull { it.value.lastUsedNs } ?: return false
        if (System.nanoTime() - dec.lastUsedNs < EVICT_IDLE_NS) return false
        if (decoders.remove(sender, dec)) synchronized(dec) { dec.release() }
        return true
    }

    /** Releases decoders of senders that have been silent for a while; they are recreated on demand. */
    private fun pruneDecoders() {
        val now = System.nanoTime()
        for ((sender, dec) in decoders) {
            if (now - dec.lastUsedNs > DECODER_IDLE_NS && decoders.remove(sender, dec)) {
                synchronized(dec) { dec.release() }
            }
        }
    }

    /** Drops every decoder and the failed-sender list, on disconnect. */
    private fun releaseDecoders() {
        for (dec in decoders.values) synchronized(dec) { dec.release() }
        decoders.clear()
        undecodable.clear()
    }

    private companion object {
        const val DECODER_IDLE_NS = 30_000_000_000L
        const val EVICT_IDLE_NS = 2_000_000_000L
        const val MAX_DECODERS = 8            // a crew, not a crowd; each one is a MediaCodec instance
        const val MAX_UNDECODABLE = 64

        const val TICK_MS = 1_000L            // hello cadence; also how often talking marks and timeouts are checked
        const val PEER_TIMEOUT_MS = 4_000L    // three missed hellos and a bit
        const val TALK_HOLD_MS = 400L         // how long after the last frame a peer still shows as talking
        const val MAX_NODES = 64              // far more than a crew; a ceiling, not a target

        /** The name Android shows in Settings > About, which the user can change; the model otherwise. */
        fun deviceName(context: Context): String =
            try { Settings.Global.getString(context.contentResolver, "device_name") } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
