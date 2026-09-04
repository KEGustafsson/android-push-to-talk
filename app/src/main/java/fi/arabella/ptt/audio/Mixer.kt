package fi.arabella.ptt.audio

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Per-sender jitter queues summed into one PCM stream. Needed for full duplex,
 * where several peers can be talking at the same time; in half duplex it just
 * passes a single stream through with a little jitter protection.
 *
 * Pacing comes from AudioTrack.write() blocking: one 20 ms frame per loop,
 * silence if nobody is sending.
 */
class Mixer(private val playback: AudioPlayback = AudioPlayback()) {

    private class Stream {
        val frames = ArrayDeque<ByteArray>()
        var primed = false
        var lastSeen = System.nanoTime()
    }

    private val streams = ConcurrentHashMap<Int, Stream>()
    @Volatile private var running = false
    private var worker: Thread? = null

    private val prefillFrames = 2     // 40 ms before a new stream starts draining
    private val maxQueuedFrames = 10  // 200 ms cap; drop oldest beyond this
    private val idleTimeoutNs = 1_000_000_000L

    fun start() {
        if (running) return
        running = true
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
                    val frame = synchronized(st) {
                        if (!st.primed && st.frames.size >= prefillFrames) st.primed = true
                        if (st.primed) st.frames.pollFirst() else null
                    }
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

    fun push(senderId: Int, data: ByteArray, offset: Int, length: Int) {
        if (length != AudioConfig.FRAME_BYTES) return
        val st = streams.getOrPut(senderId) { Stream() }
        synchronized(st) {
            st.lastSeen = System.nanoTime()
            st.frames.addLast(data.copyOfRange(offset, offset + length))
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
}
