package fi.crewradio

import java.nio.ByteBuffer

/**
 * Wire format, version 2. 13-byte header, big-endian integers:
 *
 *     'P' 'T' | version u8 = 2 | codec u8 | ttl u8 | senderId int32 | seq int32 | payload
 *
 * codec: 0 = one 20 ms frame of PCM16LE 16 kHz mono, 1 = one 20 ms Opus packet,
 *        2 = a [Hello] roster heartbeat (no audio). A build that predates hello drops
 *        the packet as an unknown codec, which is why adding it needed no version bump.
 * ttl:   hops this packet may still travel. A relay clamps it to its own hop budget,
 *        forwards only while it is > 1 and decrements it first, so a flood over a
 *        large mesh is bounded and no peer can buy itself extra hops.
 *
 * Compatibility: there is no legacy decoding. Builds before version 2 used a
 * 10-byte header with no version byte; their packets fail [parse] and are dropped,
 * so a crew must run the same app version on every phone.
 */
object Packet {
    const val HEADER = 13
    const val VERSION = 2
    /** Largest packet a peer may send: a PCM frame with header is 653 bytes, Opus far less. Anything bigger is dropped unread. */
    const val MAX_SIZE = 1024

    /** What the payload is. PCM and OPUS carry audio; HELLO is the roster heartbeat. */
    enum class Codec(val id: Int) {
        PCM(0), OPUS(1), HELLO(2);

        companion object {
            /** Codec for a wire id, or null for an id this build does not know. */
            fun fromId(id: Int): Codec? = entries.firstOrNull { it.id == id }
        }
    }

    /** Parsed header fields; the payload starts at [HEADER] in the same array. */
    class Header(val senderId: Int, val seq: Int, val codec: Codec, val ttl: Int)

    /** Builds a complete packet; ttl is clamped to the byte range. */
    fun encode(senderId: Int, seq: Int, codec: Codec, ttl: Int, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(HEADER + payload.size)
        bb.put('P'.code.toByte()).put('T'.code.toByte())
        bb.put(VERSION.toByte()).put(codec.id.toByte()).put(ttl.coerceIn(0, 255).toByte())
        bb.putInt(senderId).putInt(seq)
        bb.put(payload)
        return bb.array()
    }

    /** Returns null for anything that is not a well-formed v2 packet with a non-empty payload. */
    fun parse(p: ByteArray): Header? {
        if (p.size <= HEADER || p.size > MAX_SIZE || p[0] != 'P'.code.toByte() || p[1] != 'T'.code.toByte()) return null
        if (p[2].toInt() != VERSION) return null
        val codec = Codec.fromId(p[3].toInt() and 0xFF) ?: return null
        val ttl = p[4].toInt() and 0xFF
        val bb = ByteBuffer.wrap(p, 5, 8)
        return Header(bb.int, bb.int, codec, ttl)
    }

    /** Rewrites the ttl byte of an already encoded packet in place, clamped to the byte range. */
    fun setTtl(p: ByteArray, ttl: Int) {
        p[4] = ttl.coerceIn(0, 255).toByte()
    }
}
