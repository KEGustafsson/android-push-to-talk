package fi.arabella.ptt

import java.nio.ByteBuffer

/**
 * Wire format, version 2. 13-byte header, big-endian integers:
 *
 *     'P' 'T' | version u8 = 2 | codec u8 | ttl u8 | senderId int32 | seq int32 | payload
 *
 * codec: 0 = one 20 ms frame of PCM16LE 16 kHz mono, 1 = one 20 ms Opus packet.
 * ttl:   hops this packet may still travel. A relay forwards only while ttl > 1
 *        and decrements it first, so a flood over a large mesh is bounded.
 */
object Packet {
    const val HEADER = 13
    const val VERSION = 2

    enum class Codec(val id: Int) {
        PCM(0), OPUS(1);

        companion object {
            fun fromId(id: Int): Codec? = entries.firstOrNull { it.id == id }
        }
    }

    class Header(val senderId: Int, val seq: Int, val codec: Codec, val ttl: Int)

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
        if (p.size <= HEADER || p[0] != 'P'.code.toByte() || p[1] != 'T'.code.toByte()) return null
        if (p[2].toInt() != VERSION) return null
        val codec = Codec.fromId(p[3].toInt() and 0xFF) ?: return null
        val ttl = p[4].toInt() and 0xFF
        val bb = ByteBuffer.wrap(p, 5, 8)
        return Header(bb.int, bb.int, codec, ttl)
    }

    /** Decrements the ttl byte in place (never below 0) and returns the new value. */
    fun decrementTtl(p: ByteArray): Int {
        val ttl = (p[4].toInt() and 0xFF).let { if (it > 0) it - 1 else 0 }
        p[4] = ttl.toByte()
        return ttl
    }
}
