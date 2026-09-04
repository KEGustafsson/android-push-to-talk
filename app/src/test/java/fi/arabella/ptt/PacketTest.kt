package fi.arabella.ptt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketTest {

    private val payload = ByteArray(640) { it.toByte() }

    @Test
    fun roundTripsHeaderAndPayload() {
        val p = Packet.encode(senderId = -12345, seq = 77, codec = Packet.Codec.OPUS, ttl = 4, payload = payload)
        assertEquals(Packet.HEADER + payload.size, p.size)
        val h = Packet.parse(p)!!
        assertEquals(-12345, h.senderId)
        assertEquals(77, h.seq)
        assertEquals(Packet.Codec.OPUS, h.codec)
        assertEquals(4, h.ttl)
        assertArrayEquals(payload, p.copyOfRange(Packet.HEADER, p.size))
    }

    @Test
    fun rejectsMalformedPackets() {
        val good = Packet.encode(1, 1, Packet.Codec.PCM, 4, payload)
        assertNull("empty payload", Packet.parse(good.copyOf(Packet.HEADER)))
        assertNull("bad magic", Packet.parse(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull("old version", Packet.parse(good.copyOf().also { it[2] = 1 }))
        assertNull("unknown codec", Packet.parse(good.copyOf().also { it[3] = 9 }))
    }

    @Test
    fun ttlDecrementsInPlaceAndStopsAtZero() {
        val p = Packet.encode(1, 1, Packet.Codec.PCM, 2, payload)
        assertEquals(1, Packet.decrementTtl(p))
        assertEquals(1, Packet.parse(p)!!.ttl)
        assertEquals(0, Packet.decrementTtl(p))
        assertEquals(0, Packet.decrementTtl(p))
        assertEquals(0, Packet.parse(p)!!.ttl)
    }

    @Test
    fun ttlIsClampedToOneByte() {
        assertEquals(255, Packet.parse(Packet.encode(1, 1, Packet.Codec.PCM, 999, payload))!!.ttl)
        assertEquals(0, Packet.parse(Packet.encode(1, 1, Packet.Codec.PCM, -3, payload))!!.ttl)
    }
}
