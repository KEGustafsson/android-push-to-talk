package fi.crewradio

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import fi.crewradio.audio.AudioCapture
import fi.crewradio.audio.AudioConfig
import fi.crewradio.audio.AudioRoute
import fi.crewradio.audio.Conceal
import fi.crewradio.audio.MicGate
import fi.crewradio.audio.Mixer
import fi.crewradio.audio.OpusDecoder
import fi.crewradio.audio.OpusEncoder
import fi.crewradio.transport.Transport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    val talking: Boolean,
    /** Milliseconds since we last heard anything from it, at the time the list was built. */
    val seenAgoMs: Long
) {
    val label: String get() = name ?: id.toUInt().toString(16)
}

/** Packet counters since Connect; for the Status screen. */
class Stats(
    val rxPackets: Long, val rxBytes: Long,
    val txPackets: Long, val txBytes: Long,
    val relayed: Long, val duplicates: Long, val hellos: Long,
    /** Packets dropped because a sender exceeded its rate budget or failed validation. */
    val rejected: Long,
    /** 20 ms slots the mixer filled with a faded repeat because the packet never came. */
    val concealed: Long
)

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
    private val context: Context,
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
    /** The Android device name, falling back to the model: what [displayName] is unless settings say otherwise. */
    val defaultName: String = deviceName(context)
    /** What the crew sees this phone as. Read at every heartbeat, so a change shows up within a second. */
    @Volatile var displayName: String = defaultName
    val isTalking: Boolean get() = talking
    val isConnected: Boolean get() = transports.isNotEmpty()
    /** The roster as last published; the UI reads this when it (re)binds. */
    val roster: List<Peer> get() = lastRoster
    /** A fresh roster with current ages, for a screen that polls. */
    val rosterNow: List<Peer> get() = buildRoster()
    /** Names of the transports running right now. */
    val activeTransports: List<String> get() = transports.map { it.name }

    private val route = AudioRoute(context, onStatus)

    /**
     * Voice-operated keying (setting `headset_vox`, and always on the earpiece route): the mic
     * is captured all the time and speech keys it, 1.5 s of quiet un-keys it ([MicGate]); the
     * last [PREROLL] frames before the gate opened go out first, so the first syllable is not
     * clipped. A muted headset is quiet, so mute means off air.
     */
    @Volatile var headsetVox = false
        set(value) { if (field != value) { field = value; syncMonitor() } }

    private var monitor: AudioCapture? = null
    private val gate = MicGate()
    private val preroll = ArrayDeque<ByteArray>()
    @Volatile private var gateTalking = false      // the gate keyed the mic, so the gate un-keys it

    private val monitorLock = Any()

    /**
     * On the phone's own mic the level cannot tell your voice from a shipmate's a metre away
     * (the voice-call path levels them out), so the earpiece route arms the voice gate only
     * while the proximity sensor says the phone is at your ear, as a phone call would.
     */
    @Volatile private var atEar = false
    @Volatile private var phoneMic = false
    /** True while the proximity sensor is registered, i.e. the ear is what arms the gate. */
    @Volatile private var earWatched = false
    /**
     * Setting `proximity_sensor`. Off: the sensor is never read, so the phone behaves as one without
     * it: the automatic route stays on the loudspeaker, the screen is not darkened, and the voice
     * gate on the phone's own mic runs only on the earpiece route, armed by level alone.
     */
    @Volatile var useProximity = true
        set(v) {
            if (field == v) return
            field = v
            synchronized(monitorLock) { if (monitor != null && phoneMic) watchProximity(true) }
            syncMonitor()
        }
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private val proximity = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val near = e.values[0] < (e.sensor.maximumRange.coerceAtMost(5f))
            if (near != atEar) {
                atEar = near
                route.atEar = near                                // AUTO: earpiece at the ear, loudspeaker away
                onStatus(if (near) "At the ear: voice keys the mic" else "Away from the ear")
            }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
    }
    /** Told when the engine starts or stops watching the ear; the service darkens the screen at the ear meanwhile. */
    @Volatile var onEarWatch: ((Boolean) -> Unit)? = null

    private fun watchProximity(on: Boolean) {
        val s = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)?.takeIf { useProximity }
        sensors.unregisterListener(proximity)                          // harmless when it is not registered
        val watch = on && s != null
        if (watch) {
            atEar = false                                              // the first reading decides
            sensors.registerListener(proximity, s, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            atEar = on                                                 // no sensor, or the setting off: the level alone arms the gate
        }
        route.atEar = false
        if (watch != earWatched) { earWatched = watch; onEarWatch?.invoke(watch) }
    }

    /** True while voice keying is armed: always with a headset, at the ear on the earpiece route. */
    val voiceArmed: Boolean get() = monitor != null && (!phoneMic || atEar)
    private val micPeak = java.util.concurrent.atomic.AtomicInteger(-1)

    /** Loudest frame (RMS, 16-bit scale) the voice gate has seen since last asked, -1 when the gate is not running; for the Status screen. */
    val micPeakNow: Int get() = if (monitor == null) -1 else micPeak.getAndSet(0)
    /** The level the voice gate opens at, for the Status screen. */
    val gateOpenRms: Int get() = gate.openRms.toInt()

    /**
     * Starts or stops the always-on headset capture as the setting, the route and the session
     * dictate. The capture's frames and this method serialise on [monitorLock], and a frame from
     * a capture that is no longer [monitor] is dropped, so a late frame after teardown cannot key
     * the mic; the capture itself is stopped outside the lock, since its worker may be waiting for it.
     */
    private fun syncMonitor() {
        while (true) {
            var toStop: AudioCapture? = null
            synchronized(monitorLock) {
                // On the phone itself the ear arms voice keying (earpiece, or auto at the ear); with a
                // Bluetooth headset it is the setting. A wired headset has a button that works, so neither.
                // Without the proximity sensor the automatic route has no ear to go by, so only the
                // earpiece route, chosen on purpose, runs the phone-mic monitor then.
                val phone = !route.headset && (route.policy == AudioRoute.Policy.EARPIECE || (route.policy == AudioRoute.Policy.AUTO && useProximity))
                val want = isConnected && !held && (phone || (headsetVox && route.bluetoothHeadset))
                val wantPhoneMic = !route.bluetoothHeadset
                // A capture tuned for the other mic is as wrong as one that should not run: a headset
                // that appears or goes away mid-session stops the monitor, and the next pass starts
                // a fresh one with the right gate once the old one has let go of the mic.
                if (monitor != null && (!want || wantPhoneMic != phoneMic)) {
                    toStop = monitor
                    monitor = null
                    if (gateTalking) { gateTalking = false; stopTalking() }
                    else if (talking) stopTalking()               // the mic that was feeding it is going away
                    gate.reset()
                    preroll.clear()
                    if (phoneMic) watchProximity(false)
                } else if (want && monitor == null) {
                    gate.reset()
                    phoneMic = wantPhoneMic
                    gate.tune(phoneMic)
                    if (phoneMic) watchProximity(true)
                    preroll.clear()
                    // A talk in progress on the engine's own capture (a setting or the route changed
                    // mid-press) hands over to the monitor, which feeds sendFrame while talking: two
                    // captures would send every frame twice and fight over the mic. Its callback takes
                    // no lock, so stopping it here is safe.
                    capture?.let { it.stop(); capture = null }
                    lateinit var m: AudioCapture
                    m = AudioCapture { pcm -> synchronized(monitorLock) { if (monitor === m) voiceFrame(pcm) } }
                    monitor = m                                   // published first: the worker may call back before start() returns
                    try {
                        m.start()
                        onStatus(if (phoneMic && earWatched) "Voice keys the mic at the ear" else "Voice keys the mic")
                    } catch (e: Exception) {
                        monitor = null
                        if (phoneMic) watchProximity(false)
                        m.stop()                                  // start() threw, so no worker exists; frees the record
                        onStatus("Mic error: ${e.message}")
                    }
                    return
                } else return
            }
            toStop?.stop()                                        // outside the lock: its worker may be waiting for it
        }
    }

    /** One frame from the headset capture: runs the voice gate, then sends or keeps it for the pre-roll. Holds [monitorLock]. */
    private fun voiceFrame(pcm: ByteArray) {
        val rms = MicGate.rms(pcm)
        micPeak.accumulateAndGet(rms.toInt()) { a, b -> maxOf(a, b) }
        if (phoneMic && !atEar) {                                  // away from the ear: the gate is disarmed, the button still works
            if (gateTalking) { gateTalking = false; stopTalking() }
            gate.reset()
            if (talking) sendFrame(pcm)
            else { preroll.addLast(pcm); while (preroll.size > PREROLL) preroll.removeFirst() }
            return
        }
        when (gate.feed(rms)) {
            MicGate.Change.OPEN -> if (!talking) {
                gateTalking = true
                startTalking()
                if (talking) for (f in preroll) sendFrame(f)      // the syllable that opened the gate
                else gateTalking = false                          // the mic did not open; the gate owns nothing
            }
            MicGate.Change.CLOSE -> if (gateTalking) { gateTalking = false; stopTalking() }
            null -> Unit
        }
        if (talking) sendFrame(pcm)
        else { preroll.addLast(pcm); while (preroll.size > PREROLL) preroll.removeFirst() }
    }

    /** A hardware talk key that reaches the engine through Telecom (a Bluetooth headset's button). Set by the service. */
    @Volatile var onTalkKey: (() -> Unit)? = null
    @Volatile private var held = false

    /**
     * Telecom's view of the session, only while a Bluetooth headset is the route: [AudioRoute]
     * asks for the call when such a headset appears and ends it when it goes; while the call
     * lasts Telecom routes the audio and the headset button arrives as [CallBridge.Listener.onHeadsetButton].
     */
    private val callListener = object : CallBridge.Listener {
        override fun onCallActive() { route.passive = true }
        override fun onCallEnded(reason: String?) {
            route.passive = false
            if (reason != null) onStatus(reason)
            if (isConnected) route.reapply()
        }
        override fun onHeadsetButton() { onTalkKey?.invoke() }
        override fun onHold(held: Boolean) {
            this@PttEngine.held = held
            if (held) stopTalking()
            mixer.muted = held
            onStatus(if (held) "On hold: phone call" else "Back on channel")
            syncMonitor()
        }
        override fun onAudioRoute(label: String) {
            if (label != route.current) { route.current = label; onStatus("Audio: $label") }
        }
    }

    /**
     * Register the session as a call while a Bluetooth headset is in use (setting `headset_call`).
     * Off by default: headsets differ in what their button sends, and a call makes Android drop
     * the media keys some of them send. On, for headsets that send a hang-up instead.
     */
    @Volatile var headsetAsCall = false
        set(value) {
            if (field == value) return
            field = value
            if (isConnected && route.bluetoothPresent) syncCall(true)
        }

    init {
        route.onBluetoothHeadset = { present -> syncCall(present) }
        route.onHeadsetChanged = { syncMonitor() }
    }

    private fun syncCall(bluetoothPresent: Boolean) {
        if (bluetoothPresent && headsetAsCall && isConnected) {
            if (!CallBridge.start(context)) {
                route.passive = false      // Telecom would not take it (a phone call, or no telecom); route ourselves
                route.reapply()
            }
        } else CallBridge.stop()
    }
    /** Headset when connected (default) or always the speaker; applies immediately. */
    var audioRoute: AudioRoute.Policy
        get() = route.policy
        set(value) {
            route.policy = value
            CallBridge.forcedRoute = when (value) {
                AudioRoute.Policy.SPEAKER -> android.telecom.CallAudioState.ROUTE_SPEAKER
                AudioRoute.Policy.EARPIECE -> android.telecom.CallAudioState.ROUTE_EARPIECE
                AudioRoute.Policy.AUTO -> null
            }
            syncMonitor()
        }
    /** Where the voice is going right now, for the Status screen. */
    val audioRouteNow: String get() = route.current
    private val mixer = Mixer()
    private val transports = CopyOnWriteArrayList<Transport>()
    private var capture: AudioCapture? = null
    @Volatile private var encoder: OpusEncoder? = null
    private val decoders = ConcurrentHashMap<Int, OpusDecoder>()
    private val undecodable = ConcurrentHashMap.newKeySet<Int>()   // senders whose decoder failed; reported once
    private val packetCount = AtomicInteger()
    private val audioSeq = AtomicInteger()                           // one per frame, so a gap in it is lost audio
    private val helloSeq = AtomicInteger()                           // hellos count separately; they are not frames
    /**
     * Packet counters for one session. connect() starts a fresh set; a callback from a
     * transport that was still winding down keeps incrementing the old set, which nothing
     * reads any more - so the counters can never be reset underneath a running callback.
     */
    private class Counters {
        val rxPackets = AtomicLong()
        val rxBytes = AtomicLong()
        val txPackets = AtomicLong()
        val txBytes = AtomicLong()
        val relayed = AtomicLong()
        val duplicates = AtomicLong()
        val hellos = AtomicLong()
        val rejected = AtomicLong()
        fun snapshot(concealed: Long) = Stats(rxPackets.get(), rxBytes.get(), txPackets.get(), txBytes.get(), relayed.get(), duplicates.get(), hellos.get(), rejected.get(), concealed)
    }
    @Volatile private var counters = Counters()
    @Volatile private var talking = false

    private val nodes = ConcurrentHashMap<Int, Node>()
    private val seqTracker = SeqTracker()                            // per sender audio sequence: gaps mean lost frames
    private val rateLimiter = RateLimiter()                          // a sender beyond its budget is dropped before it costs anything

    /** Seals and opens every packet; null until [channelKey] is set, and then nothing is sent or accepted either. */
    @Volatile private var crypto: ChannelCrypto? = null
    private var cryptoFor: String? = null

    /** The crew's channel key. Deriving the packet key takes a moment, so it is done once per value. */
    var channelKey: String = ""
        set(value) {
            if (value == field && cryptoFor == value) return
            field = value
            crypto = if (value.isEmpty()) null else ChannelCrypto.forChannelKey(value)
            cryptoFor = value
        }
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

    private class SeenCache(private val capacity: Int) : LinkedHashMap<Long, Boolean>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > capacity
        override fun clone(): Any = SeenCache(capacity).also { it.putAll(this) }   // HashMap is Cloneable; keep the bound
    }
    private val seen = SeenCache(4096)          // audio: ~80 s of one talker, plenty for a relay echo
    private val seenHellos = SeenCache(512)     // hellos: 1 Hz per node, their own sequence space

    /** Returns true if this (sender, seq) is new; hellos and audio number themselves independently. */
    private fun markSeen(senderId: Int, seq: Int, codec: Packet.Codec): Boolean {
        val key = (senderId.toLong() shl 32) or (seq.toLong() and 0xFFFF_FFFFL)
        val cache = if (codec == Packet.Codec.HELLO) seenHellos else seen
        synchronized(cache) { return cache.put(key, true) == null }
    }

    /** True if this (sender, seq) is already in the seen-cache; a look, nothing is recorded. */
    private fun isSeen(senderId: Int, seq: Int, codec: Packet.Codec): Boolean {
        val key = (senderId.toLong() shl 32) or (seq.toLong() and 0xFFFF_FFFFL)
        val cache = if (codec == Packet.Codec.HELLO) seenHellos else seen
        synchronized(cache) { return cache.containsKey(key) }
    }

    /** Starts playback and the given transports; any transport that fails to start is reported and dropped. */
    fun connect(list: List<Transport>) {
        disconnect()
        counters = Counters()
        CallBridge.listener = callListener
        route.start()
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
        syncMonitor()
    }

    /** Stops everything and releases codecs; safe to call when already idle. */
    fun disconnect() {
        val m = synchronized(monitorLock) { monitor.also { monitor = null; gateTalking = false; if (phoneMic) watchProximity(false) } }
        m?.stop()
        stopTalking()
        heartbeat?.shutdownNow()
        heartbeat = null
        for (t in transports) t.stop()
        transports.clear()
        mixer.stop()
        releaseDecoders()
        synchronized(seen) { seen.clear() }
        synchronized(seenHellos) { seenHellos.clear() }
        nodes.clear()
        seqTracker.clear()
        rateLimiter.clear()
        publishRoster()
        route.stop()
        CallBridge.stop()
        CallBridge.listener = null
        mixer.muted = false
        held = false
    }

    fun stats(): Stats = counters.snapshot(mixer.concealedFrames.get())

    /** Keys the mic: opens the encoder (if Opus) and the capture; on failure everything is released again. */
    fun startTalking() {
        if (talking || transports.isEmpty() || held) return
        talking = true
        encoder = if (codec == Packet.Codec.OPUS) {
            try {
                OpusEncoder { broadcast(Packet.Codec.OPUS, it) }
            } catch (e: Exception) {
                onStatus("Opus encoder unavailable, sending PCM")
                null
            }
        } else null
        if (monitor != null) {             // the always-on headset capture feeds sendFrame while talking
            onStatus(if (mode == Mode.FULL_DUPLEX) "Mic on" else "Transmitting")
            return
        }
        val cap = AudioCapture { pcm -> sendFrame(pcm) }
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

    /** One captured frame out: through the Opus encoder when there is one, else raw. */
    private fun sendFrame(pcm: ByteArray) {
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

    /** Un-keys the mic and releases the capture and encoder. */
    fun stopTalking() {
        if (!talking) return
        talking = false
        gateTalking = false
        capture?.stop()
        capture = null
        encoder?.release()
        encoder = null
        onStatus(if (mode == Mode.FULL_DUPLEX) "Mic off" else "Listening")
    }

    /** Full-duplex mic toggle. */
    fun toggleTalking() = if (talking) stopTalking() else startTalking()

    /** Plays a short cue (see [fi.crewradio.audio.Tones]) in the ear, or the speaker; nothing when not on channel. */
    fun cue(frames: List<ByteArray>) = mixer.cue(frames)

    /** Stamps and sends one of our own packets on every transport. */
    private fun broadcast(codec: Packet.Codec, payload: ByteArray) {
        val cr = crypto ?: return                                   // no key, nothing goes on the air
        val s = (if (codec == Packet.Codec.HELLO) helloSeq else audioSeq).getAndIncrement()
        markSeen(senderId, s, codec)
        val header = Packet.encode(senderId, s, codec, maxHops, ByteArray(0))
        val packet = header + cr.seal(Packet.aadOf(header), payload)
        val c = counters
        c.txPackets.incrementAndGet()
        c.txBytes.addAndGet(packet.size.toLong())
        for (t in transports) t.send(packet)
    }

    /** Receive path for every transport: dedupe, relay within the hop budget, then roster, then decode and play. */
    private fun onPacket(p: ByteArray, from: Transport, link: Any?) {
        if (transports.isEmpty()) return                  // a transport still winding down after disconnect
        val c = counters                                  // this session's set, whatever happens meanwhile
        val h = Packet.parse(p) ?: run { c.rejected.incrementAndGet(); return }
        if (h.senderId == senderId) return
        val now = SystemClock.elapsedRealtime()
        if (!rateLimiter.allowGlobal(now)) { c.rejected.incrementAndGet(); return }
        // Authenticate before anything else: a packet without the crew's key must not reach the
        // seen-cache (a forged sender+number would shadow the real packet), the relay, the roster,
        // nor the sender's own rate budget (a forged sender id would starve the real one).
        // The order after that is dedupe, then the sender's budget: see below.
        val cr = crypto ?: return
        val plain = cr.open(Packet.aadOf(p), p, Packet.HEADER, p.size - Packet.HEADER) ?: run { c.rejected.incrementAndGet(); return }
        // Drop duplicates before charging the sender: every frame arrives twice on WLAN (multicast
        // and broadcast) and again over every other link, so charging each copy would spend a
        // 75/s budget on 100+ copies/s and, once the burst is gone, refuse real frames too. Only
        // authenticated packets get here, so a forgery cannot occupy a (sender, seq) slot. The
        // cache is looked at first and written only for a packet within the budget, so a sender
        // over its budget cannot churn the shared cache either; a copy that slips in between on
        // another transport's thread is caught by the write and counted as the duplicate it is.
        if (isSeen(h.senderId, h.seq, h.codec)) { c.duplicates.incrementAndGet(); return }       // duplicate via another path
        if (!rateLimiter.allowSender(h.senderId, now)) { c.rejected.incrementAndGet(); return }
        if (!markSeen(h.senderId, h.seq, h.codec)) { c.duplicates.incrementAndGet(); return }   // lost the race to its twin
        c.rxPackets.incrementAndGet()
        c.rxBytes.addAndGet(p.size.toLong())

        // A peer's ttl is capped at our own budget and at the budget the sender signed into the
        // packet, so nobody can stamp 255, nor bump a captured packet's ttl, and ride further.
        val ttl = minOf(h.ttl, h.hops, maxHops)
        if (relay && ttl > 1) {
            Packet.setTtl(p, ttl - 1)
            var forwarded = false
            for (t in transports) {
                if (t === from) { if (t.relayWithin && t.send(p, except = link)) forwarded = true }
                else if (t.send(p)) forwarded = true
            }
            if (forwarded) c.relayed.incrementAndGet()
        }

        if (h.codec == Packet.Codec.HELLO) {
            c.hellos.incrementAndGet()
            Hello.decode(plain, 0, plain.size)?.let { heardHello(h.senderId, it, from, h.ttl) }
            return
        }
        heardAudio(h.senderId, from)

        if ((packetCount.incrementAndGet() and 0xFF) == 0) pruneDecoders()
        val playing = !(mode == Mode.HALF_DUPLEX && talking)   // radio semantics: not while we transmit

        // Audio frames number themselves consecutively, so a gap is lost audio and its slots are
        // reserved before this frame is queued. Admission and reservation stay atomic per sender:
        // the same sender's frames can arrive on several transport threads at once. A late frame
        // is dropped, its slot was concealed already.
        synchronized(seqTracker) {
            val gap = seqTracker.admit(h.senderId, h.seq)
            if (gap < 0) return
            if (playing && gap in 1..Conceal.MAX_FRAMES) mixer.conceal(h.senderId, gap)
        }
        if (!playing) return

        when (h.codec) {
            Packet.Codec.PCM -> mixer.push(h.senderId, plain, 0, plain.size)
            Packet.Codec.OPUS -> decodeOpus(h.senderId, plain)
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
        seqTracker.retain(nodes.keys)
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

    private fun buildRoster(): List<Peer> {
        val now = SystemClock.elapsedRealtime()
        return nodes.entries
            .map { (id, n) -> Peer(id, n.name, n.transports, n.via, n.hops, n.talking, now - n.lastSeen) }
            .sortedBy { it.label.lowercase() }
    }

    /** Rebuilds the list and hands it out only if it differs from the last one published (ages do not count). */
    @Synchronized
    private fun publishRoster() {
        val list = buildRoster()
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
                dec.decode(p, 0, p.size) { frame ->
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
        /** Frames kept from before the voice gate opened and sent first: 100 ms. */
        const val PREROLL = 5
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
