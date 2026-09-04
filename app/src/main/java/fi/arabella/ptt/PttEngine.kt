package fi.arabella.ptt

import android.content.Context
import android.media.AudioManager
import fi.arabella.ptt.audio.AudioCapture
import fi.arabella.ptt.audio.Mixer
import fi.arabella.ptt.transport.Transport
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Glue between mic, transports, relay and mixer.
 *
 * HALF_DUPLEX: mic runs only while the talk button is held; incoming audio is
 *              not played while transmitting (radio behaviour) but is still relayed.
 * FULL_DUPLEX: mic on/off is a toggle; incoming audio always plays and
 *              simultaneous talkers are mixed.
 *
 * Relay: every packet carries (senderId, seq). A packet not seen before is
 * played and, if relay is on, forwarded on every transport (excluding the
 * link it came from). Duplicates are dropped by the seen-cache, so a flood
 * across a multi-hop Aware/BT topology terminates. Running several transports
 * at once makes this phone a bridge (e.g. boat Wi-Fi <-> Aware).
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

    val senderId: Int = Random.nextInt()
    val isTalking: Boolean get() = talking
    val isConnected: Boolean get() = transports.isNotEmpty()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mixer = Mixer()
    private val transports = CopyOnWriteArrayList<Transport>()
    private var capture: AudioCapture? = null
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

    fun disconnect() {
        stopTalking()
        for (t in transports) t.stop()
        transports.clear()
        mixer.stop()
        synchronized(seen) { seen.clear() }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun startTalking() {
        if (talking || transports.isEmpty()) return
        talking = true
        capture = AudioCapture { pcm ->
            val s = seq++
            markSeen(senderId, s)
            val packet = Packet.encode(senderId, s, pcm)
            for (t in transports) t.send(packet)
        }.also {
            try { it.start() } catch (e: Exception) { talking = false; onStatus("Mic error: ${e.message}") }
        }
        if (talking) onStatus(if (mode == Mode.FULL_DUPLEX) "Mic on" else "Transmitting")
    }

    fun stopTalking() {
        if (!talking) return
        talking = false
        capture?.stop()
        capture = null
        onStatus(if (mode == Mode.FULL_DUPLEX) "Mic off" else "Listening")
    }

    fun toggleTalking() = if (talking) stopTalking() else startTalking()

    private fun onPacket(p: ByteArray, from: Transport, link: Any?) {
        val h = Packet.parse(p) ?: return
        if (h.senderId == senderId) return
        if (!markSeen(h.senderId, h.seq)) return          // duplicate via another path

        if (relay) {
            for (t in transports) {
                if (t === from) { if (t.relayWithin) t.send(p, except = link) }
                else t.send(p)
            }
        }

        if (mode == Mode.HALF_DUPLEX && talking) return   // radio semantics
        mixer.push(h.senderId, p, Packet.HEADER, p.size - Packet.HEADER)
    }
}
