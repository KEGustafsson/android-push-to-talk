package fi.crewradio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The same bytes the Signal K plugin's test suite checks (sk-plugin/test/vector.json), so the
 * two implementations of the wire format and the channel crypto are held to each other:
 * same PBKDF2 parameters, same AAD rule, same AES-GCM layout, same hello encoding. The packet
 * was sealed by the plugin with a fixed nonce; this test opens it and re-encodes the hello.
 */
class CrossLanguageVectorTest {
    private val channelKey = "north-star-2026"
    private val derivedKeyHex = "c387a6bb78fb90ffdb2842ebbfa5057048545562ba2c97274ff6d0f1e442e1cc"
    private val packetHex = "5054030204041234567800000007000102030405060708090a0bbcee1ff455805ed4bacd8cdbdaf74c4a6d7841ae99bc52b5a213e2e1"
    private val plainHex = "0101040841726162656c6c61"

    @Test
    fun theKeyDerivesToTheSameBytes() {
        assertEquals(derivedKeyHex, ChannelCrypto.derive(channelKey).encoded.toHex())
    }

    @Test
    fun aPacketSealedByThePluginOpensHere() {
        val packet = packetHex.hexToBytes()
        val h = Packet.parse(packet)
        assertNotNull(h)
        assertEquals(0x12345678, h!!.senderId)
        assertEquals(7, h.seq)
        assertEquals(Packet.Codec.HELLO, h.codec)
        assertEquals(4, h.ttl)
        assertEquals(4, h.hops)
        val plain = ChannelCrypto.forChannelKey(channelKey).open(Packet.aadOf(packet), packet, Packet.HEADER, packet.size - Packet.HEADER)
        assertNotNull(plain)
        assertEquals(plainHex, plain!!.toHex())
        val hello = Hello.decode(plain, 0, plain.size)
        assertNotNull(hello)
        assertEquals("Arabella", hello!!.name)
        assertEquals(Hello.LAN, hello.transports)
        assertEquals(4, hello.ttl)
    }

    @Test
    fun theHelloEncodesToTheSameBytes() {
        assertArrayEquals(plainHex.hexToBytes(), Hello("Arabella", Hello.LAN, 4).encode())
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
