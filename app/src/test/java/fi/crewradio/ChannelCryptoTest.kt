package fi.crewradio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelCryptoTest {

    private val crypto = ChannelCrypto.forChannelKey("north-star-2026")
    private val aad = Packet.encode(7, 42, Packet.Codec.OPUS, 0, ByteArray(1))
    private val plain = ByteArray(60) { (it * 7).toByte() }

    @Test
    fun roundTrips() {
        val sealed = crypto.seal(aad, plain)
        assertEquals(plain.size + ChannelCrypto.OVERHEAD, sealed.size)
        assertArrayEquals(plain, crypto.open(aad, sealed))
    }

    @Test
    fun opensFromAnOffsetInsideALargerArray() {
        val sealed = crypto.seal(aad, plain)
        val packet = aad + sealed
        assertArrayEquals(plain, crypto.open(aad, packet, aad.size, sealed.size))
    }

    @Test
    fun everyPacketGetsItsOwnNonceAndCiphertext() {
        val a = crypto.seal(aad, plain)
        val b = crypto.seal(aad, plain)
        assertFalse(a.contentEquals(b))
        assertFalse(a.copyOf(ChannelCrypto.NONCE_BYTES).contentEquals(b.copyOf(ChannelCrypto.NONCE_BYTES)))
    }

    @Test
    fun aFlippedBitAnywhereFails() {
        val sealed = crypto.seal(aad, plain)
        for (i in listOf(0, ChannelCrypto.NONCE_BYTES, sealed.size / 2, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", crypto.open(aad, bad))
        }
    }

    @Test
    fun theHeaderIsAuthenticatedToo() {
        val sealed = crypto.seal(aad, plain)
        val otherSender = aad.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertNull(crypto.open(otherSender, sealed))
    }

    @Test
    fun aDifferentChannelKeyCannotOpenIt() {
        val sealed = crypto.seal(aad, plain)
        assertNull(ChannelCrypto.forChannelKey("north-star-2027").open(aad, sealed))
        assertNotNull(ChannelCrypto.forChannelKey("north-star-2026").open(aad, sealed))
    }

    @Test
    fun truncatedInputIsRejectedNotThrown() {
        val sealed = crypto.seal(aad, plain)
        assertNull(crypto.open(aad, sealed.copyOf(ChannelCrypto.OVERHEAD)))
        assertNull(crypto.open(aad, ByteArray(0)))
    }

    @Test
    fun keyDerivationIsDeterministicAndKeyed() {
        assertArrayEquals(ChannelCrypto.derive("abcd-efgh-jkmn").encoded, ChannelCrypto.derive("abcd-efgh-jkmn").encoded)
        assertFalse(ChannelCrypto.derive("abcd-efgh-jkmn").encoded.contentEquals(ChannelCrypto.derive("abcd-efgh-jkmp").encoded))
        assertEquals(32, ChannelCrypto.derive("x").encoded.size)
    }
}
