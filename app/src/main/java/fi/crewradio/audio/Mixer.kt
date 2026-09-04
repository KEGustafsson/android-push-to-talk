package fi.crewradio.audio

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Per-sender jitter queues summed into one PCM stream. Needed for full duplex,
 * where several peers can be talking at the same time; in half duplex it just
 * passes a single stream through with a little jitter protection.
 *
 * Pacing comes from AudioTrack.write() blocking: one 20 ms frame per loop,
 * silence if nobody is sending.
 *
 * Loss: a slot the engine knows is missing (a gap in the sender's sequence, see
 * [conceal]) and a queue that runs dry while its sender is still talking are both
 * filled with [Conceal.frame] - the last good frame, fading - instead of a click of
 * silence. After [Conceal.MAX_FRAMES] of that the stream goes quiet until real audio returns.
 */
class Mixer(private val playback: AudioPlayback = AudioPlayback()) {

    private class Stream {
        val frames = ArrayDeque<ByteArray>()
        var primed = false
        var lastSeen = System.nanoTime()
        var last: ByteArray? = null      // last real frame, what concealment repeats
        var concealed = 0                // consecutive concealed slots so far
    }

    private val streams = ConcurrentHashMap<Int, Stream>()
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Slots filled by concealment since [start]; shown on the Status screen. */
    val concealedFrames = AtomicLong()

    private val prefillFrames = 2     // 40 ms before a new stream starts draining
    private val maxQueuedFrames = 10  // 200 ms cap; drop oldest beyond this
    private val idleTimeoutNs = 1_000_000_000L
    private val stillTalkingNs = 150_000_000L   // an empty queue this soon after a frame is loss, not the end

    fun start() {
        if (running) return
        running = true
        concealedFrames.set(0)
        playback.start()
        worker = thread(name = "ptt-mixer") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val acc = IntArray(AudioConfig.FRAME_SAMPLES)
            val out = ByteArray(AudioConfig.FRAME_BYTES)
            while (running) {
                acc.fill(0)
                var active = 0
                val now = System.nanoTime()
                for ((id, st) in streams) {
                    val frame = synchronized(st) { nextFrame(st, now) }
                    if (frame != null) {
                        active++
                        var i = 0
                        while (i < AudioConfig.FRAME_SAMPLES) {
                            val lo = frame[2 * i].toInt() and 0xFF
                            val hi = frame[2 * i + 1].toInt()
                            acc[i] += (hi shl 8) or lo
                            i++
                        }
                    } else if (now - st.lastSeen > idleTimeoutNs) {
                        streams.remove(id)
                    }
                }
                if (active == 0) {
                    out.fill(0)
                } else {
                    for (i in 0 until AudioConfig.FRAME_SAMPLES) {
                        val v = acc[i].coerceIn(-32768, 32767)
                        out[2 * i] = (v and 0xFF).toByte()
                        out[2 * i + 1] = (v shr 8).toByte()
                    }
                }
                playback.write(out, 0, out.size)
            }
        }
    }

    /** The frame a stream contributes to this slot: real audio, a concealed repeat, or nothing. Call holding the stream lock. */
    private fun nextFrame(st: Stream, now: Long): ByteArray? {
        if (!st.primed && st.frames.size >= prefillFrames) st.primed = true
        if (!st.primed) return null
        val f = st.frames.pollFirst()
        if (f != null && f !== HOLE) {
            st.last = f
            st.concealed = 0
            return f
        }
        // A known hole, or a queue that ran dry while the sender is still talking.
        if (f == null && now - st.lastSeen > stillTalkingNs) return null
        val fill = Conceal.frame(st.last, st.concealed + 1) ?: return null
        st.concealed++
        concealedFrames.incrementAndGet()
        return fill
    }

    fun push(senderId: Int, data: ByteArray, offset: Int, length: Int) {
        if (length != AudioConfig.FRAME_BYTES) return
        val st = streams.getOrPut(senderId) { Stream() }
        synchronized(st) {
            st.lastSeen = System.nanoTime()
            st.frames.addLast(data.copyOfRange(offset, offset + length))
            while (st.frames.size > maxQueuedFrames) st.frames.pollFirst()
        }
    }

    /** Reserves [count] slots for frames the engine knows were lost, so timing holds and each is concealed in turn. */
    fun conceal(senderId: Int, count: Int) {
        val st = streams[senderId] ?: return          // nothing heard from them yet: nothing to repeat either
        synchronized(st) {
            repeat(count.coerceAtMost(Conceal.MAX_FRAMES)) { st.frames.addLast(HOLE) }
            while (st.frames.size > maxQueuedFrames) st.frames.pollFirst()
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        streams.clear()
        playback.stop()
    }

    private companion object {
        /** Queue marker for a slot whose packet never came. */
        val HOLE = ByteArray(0)
    }
}
