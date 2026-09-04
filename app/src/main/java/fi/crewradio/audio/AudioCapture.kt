package fi.crewradio.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread

/**
 * Pulls 20 ms PCM16 frames from the mic and hands them to [onFrame].
 * VOICE_COMMUNICATION source enables the platform AEC/NS where available.
 */
class AudioCapture(private val onFrame: (ByteArray) -> Unit) {

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, AudioConfig.FRAME_BYTES * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord init failed")
        }
        record = rec
        // Hardware/platform echo cancellation is what makes full duplex on speakerphone usable.
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        running = true
        rec.startRecording()
        worker = thread(name = "ptt-capture") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val buf = ByteArray(AudioConfig.FRAME_BYTES)
                var got = 0
                while (running && got < buf.size) {
                    val n = rec.read(buf, got, buf.size - got)
                    if (n <= 0) break
                    got += n
                }
                if (got == buf.size) onFrame(buf)
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
    }
}
