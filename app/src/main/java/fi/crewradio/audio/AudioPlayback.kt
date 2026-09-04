package fi.crewradio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Streaming PCM16 playback. write() is blocking; call it from the receive thread. */
class AudioPlayback {

    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConfig.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // ~100 ms of buffering absorbs LAN/BT jitter without noticeable delay
            .setBufferSizeInBytes(maxOf(minBuf, AudioConfig.FRAME_BYTES * 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        track?.write(data, offset, length)
    }

    fun stop() {
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }
}
