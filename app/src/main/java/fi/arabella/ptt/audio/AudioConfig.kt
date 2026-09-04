package fi.arabella.ptt.audio

/** Shared audio parameters. 16 kHz mono PCM16, 20 ms frames = 320 samples = 640 bytes. */
object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    /** Opus target bitrate. 24 kbit/s is transparent for speech and ~10x smaller than PCM. */
    const val OPUS_BITRATE = 24_000

    /** Hops a packet may travel through the relay mesh before it is dropped. */
    const val DEFAULT_TTL = 4
}
