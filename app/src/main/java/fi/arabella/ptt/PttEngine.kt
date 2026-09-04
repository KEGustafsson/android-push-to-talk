package fi.arabella.ptt

import android.content.Context
import android.media.AudioManager
import fi.arabella.ptt.audio.AudioCapture
import fi.arabella.ptt.audio.AudioConfig
import fi.arabella.ptt.audio.Mixer
import fi.arabella.ptt.audio.OpusDecoder
import fi.arabella.ptt.audio.OpusEncoder
import fi.arabella.ptt.transport.Transport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Glue between mic, codec, transports, relay and mixer.
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
 */
class PttEngine(context: Context, private val onStatus: (String) -> Unit) {

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

    /** Outgoing codec. Takes effect the next time the mic is keyed. */
    @Volatile var codec: Packet.Codec = Packet.Codec.OPUS

    /** Hop budget stamped on packets this phone originates. */
    @Volatile var maxHops: Int = AudioConfig.DEFAULT_TTL

    val senderId: Int = Random.nextInt()
    val isTalking: Boolean get() = talking
    val isConnected: Boolean get() = transports.isNotEmpty()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mixer = Mixer()
    private val transports = CopyOnWriteArrayList<Transport>()
    private var capture: AudioCapture? = null
    @Volatile private var encoder: OpusEncoder? = null
    private val decoders = ConcurrentHashMap<Int, OpusDecoder>()
    private val undecodable = ConcurrentHashMap.newKeySet<Int>()   // senders whose decoder failed; reported once
    private val packetCount = AtomicInteger()
    private var seq = 0
    @Volatile private var talking = false

    private val seenCapacity = 4096
    private val seen = object : LinkedHashMap<Long, Boolean>(seenCapacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > seenCapacity
    }

    /** Returns true if this (sender, seq) is new. */
    private fun markSeen(senderId: Int, seq: Int): Boolean {
        val key = (senderId.toLong() shl 32) or (seq.toLong() and 0xFFFF_FFFFL)
        synchronized(seen) { return seen.put(key, true) == null }
    }

    /** Starts playback and the given transports; any transport that fails to start is reported and skipped. */
    fun connect(list: List<Transport>) {
        disconnect()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        mixer.start()
        for (t in list) {
            transports.add(t)
            try { t.start(::onPacket, onStatus) } catch (e: Exception) { onStatus("${t.name}: ${e.message}") }
        }
    }

    /** Stops everything and releases codecs; safe to call when already idle. */
    fun disconnect() {
        stopTalking()
        for (t in transports) t.stop()
        transports.clear()
        mixer.stop()
        releaseDecoders()
        synchronized(seen) { seen.clear() }
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

    /** Stamps and sends one of our own frames on every transport. */
    private fun broadcast(codec: Packet.Codec, payload: ByteArray) {
        val s = seq++
        markSeen(senderId, s)
        val packet = Packet.encode(senderId, s, codec, maxHops, payload)
        for (t in transports) t.send(packet)
    }

    /** Receive path for every transport: dedupe, relay within the hop budget, then decode and play. */
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

        if (packetCount.incrementAndGet() and 0xFF == 0) pruneDecoders()
        if (mode == Mode.HALF_DUPLEX && talking) return   // radio semantics

        when (h.codec) {
            Packet.Codec.PCM -> mixer.push(h.senderId, p, Packet.HEADER, p.size - Packet.HEADER)
            Packet.Codec.OPUS -> decodeOpus(h.senderId, p)
        }
    }

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
    }
}
