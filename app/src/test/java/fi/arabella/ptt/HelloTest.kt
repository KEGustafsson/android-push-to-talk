package fi.arabella.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelloTest {

    private fun roundTrip(h: Hello): Hello {
        val bytes = h.encode()
        return Hello.decode(bytes, 0, bytes.size)!!
    }

    @Test
    fun roundTripsNameTransportsAndTtl() {
        val h = roundTrip(Hello("Arabella S25", Hello.LAN or Hello.AWARE, 4))
        assertEquals("Arabella S25", h.name)
        assertEquals(Hello.LAN or Hello.AWARE, h.transports)
        assertEquals(4, h.ttl)
    }

    @Test
    fun decodesAtAnOffsetInsideALargerBuffer() {
        val body = Hello("x", Hello.BT, 2).encode()
        val buf = ByteArray(5) + body + ByteArray(3)
        val h = Hello.decode(buf, 5, body.size)!!
        assertEquals("x", h.name)
        assertEquals(Hello.BT, h.transports)
    }

    @Test
    fun truncatesLongNamesOnACodePointBoundary() {
        // 20 x "ä" is 40 bytes of UTF-8; only 16 (32 bytes) fit, and never half of one.
        val h = roundTrip(Hello("ä".repeat(20), 0, 4))
        assertEquals("ä".repeat(16), h.name)
        assertEquals(Hello.MAX_NAME_BYTES, h.encode().size - 4)

        val emoji = roundTrip(Hello("🚤".repeat(9), 0, 4))   // 9 boats x 4 bytes = 36
        assertEquals("🚤".repeat(8), emoji.name)
    }

    @Test
    fun rejectsShortOrForeignPayloads() {
        assertNull(Hello.decode(byteArrayOf(1, 0, 4), 0, 3))                  // too short for a header
        assertNull(Hello.decode(byteArrayOf(2, 0, 4, 0), 0, 4))               // unknown version
        assertNull(Hello.decode(byteArrayOf(1, 0, 4, 5, 65, 66), 0, 6))       // claims 5 name bytes, has 2
    }

    @Test
    fun describesTransportFlags() {
        assertEquals("", Hello.describe(0))
        assertEquals("LAN+BT+Aware", Hello.describe(Hello.LAN or Hello.BT or Hello.AWARE))
        assertEquals(Hello.AWARE, Hello.bitFor("Aware"))
        assertEquals(0, Hello.bitFor("Carrier pigeon"))
    }
}
