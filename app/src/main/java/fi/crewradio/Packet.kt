package fi.crewradio

import java.nio.ByteBuffer

/**
 * Wire format, version 3. 14-byte header, big-endian integers, followed by the sealed payload:
 *
 *     'P' 'T' | version u8 = 3 | codec u8 | ttl u8 | hops u8 | senderId int32 | seq int32
 *     then: nonce (12) | ciphertext | tag (16)
 *
 * The payload is AES-256-GCM under the crew's channel key ([ChannelCrypto]) with the header as
 * associated data, except the ttl byte, which relays rewrite in place ([aadOf]). `hops` is the
 * sender's original hop budget and is authenticated: a relay never lets ttl exceed it, so a
 * captured packet with its ttl bumped travels no further than the sender allowed. Without the
 * key a packet cannot be read or forged; no other header field can be altered in flight.
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
    const val HEADER = 14
    const val VERSION = 3
    /** Largest packet a peer may send: a sealed PCM frame with header is 682 bytes, Opus far less. Anything bigger is dropped unread. */
    const val MAX_SIZE = 1024

    /** What the payload is. PCM and OPUS carry audio; HELLO is the roster heartbeat. */
    enum class Codec(val id: Int) {
        PCM(0), OPUS(1), HELLO(2);

        companion object {
            /** Codec for a wire id, or null for an id this build does not know. */
            fun fromId(id: Int): Codec? = entries.firstOrNull { it.id == id }
        }
    }

    /** Parsed header fields; the payload starts at [HEADER] in the same array. [hops] is the authenticated origin budget. */
    class Header(val senderId: Int, val seq: Int, val codec: Codec, val ttl: Int, val hops: Int)

    /** Builds a complete packet; ttl and hops are clamped to the byte range, hops defaults to the ttl the sender stamps. */
    fun encode(senderId: Int, seq: Int, codec: Codec, ttl: Int, payload: ByteArray, hops: Int = ttl): ByteArray {
        val bb = ByteBuffer.allocate(HEADER + payload.size)
        bb.put('P'.code.toByte()).put('T'.code.toByte())
        bb.put(VERSION.toByte()).put(codec.id.toByte()).put(ttl.coerceIn(0, 255).toByte()).put(hops.coerceIn(0, 255).toByte())
        bb.putInt(senderId).putInt(seq)
        bb.put(payload)
        return bb.array()
    }

    /** Returns null for anything that is not a well-formed v3 packet with a non-empty payload. */
    fun parse(p: ByteArray): Header? {
        if (p.size <= HEADER || p.size > MAX_SIZE || p[0] != 'P'.code.toByte() || p[1] != 'T'.code.toByte()) return null
        if (p[2].toInt() != VERSION) return null
        val codec = Codec.fromId(p[3].toInt() and 0xFF) ?: return null
        val ttl = p[4].toInt() and 0xFF
        val hops = p[5].toInt() and 0xFF
        val bb = ByteBuffer.wrap(p, 6, 8)
        return Header(bb.int, bb.int, codec, ttl, hops)
    }

    /** The header as authenticated: a copy of the first [HEADER] bytes with the ttl zeroed, since relays change it. */
    fun aadOf(p: ByteArray): ByteArray = p.copyOf(HEADER).also { it[4] = 0 }

    /** Rewrites the ttl byte of an already encoded packet in place, clamped to the byte range. */
    fun setTtl(p: ByteArray, ttl: Int) {
        p[4] = ttl.coerceIn(0, 255).toByte()
    }
}
