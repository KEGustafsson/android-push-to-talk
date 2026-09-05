package fi.crewradio

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Confidentiality and authenticity of every packet on the wire, from one shared channel key.
 *
 * AES-256-GCM (hardware-accelerated on every phone this app runs on) with a fresh random
 * 96-bit nonce per packet and the packet header as associated data, so a packet from a
 * phone without the key, or one altered in flight, fails the tag and is dropped before it
 * reaches the relay, the roster or a decoder. Replays of an authentic packet are caught by
 * the seen-cache and the sequence tracker afterwards.
 *
 * The key comes from the crew's channel key (a passphrase) through PBKDF2-HMAC-SHA256 with a
 * fixed application salt: the same passphrase gives the same key on every phone, and deriving
 * it once per session costs well under a second. Pure JVM crypto, unit-tested.
 */
class ChannelCrypto(private val key: SecretKeySpec) {

    /** [aad] is authenticated but sent in the clear; returns nonce || ciphertext || tag. */
    fun seal(aad: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return nonce + ct
    }

    /** The plaintext, or null if the packet was not sealed with this key and this [aad] exactly. */
    fun open(aad: ByteArray, sealed: ByteArray, offset: Int = 0, length: Int = sealed.size - offset): ByteArray? {
        if (length < OVERHEAD + 1) return null
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, offset, NONCE_BYTES))
            c.updateAAD(aad)
            c.doFinal(sealed, offset + NONCE_BYTES, length - NONCE_BYTES)
        } catch (_: Exception) {
            null                                   // bad tag, wrong key, truncated: all the same to us
        }
    }

    companion object {
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        /** Bytes a sealed payload is longer than its plaintext. */
        const val OVERHEAD = NONCE_BYTES + TAG_BYTES
        private const val TAG_BITS = TAG_BYTES * 8
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val ITERATIONS = 64_000
        private val SALT = "CrewRadio channel key v3".toByteArray()
        private val random = SecureRandom()

        /** The AES-256 key for a channel key; deterministic, so every phone with the same key agrees. */
        fun derive(channelKey: String): SecretKeySpec {
            val spec = PBEKeySpec(channelKey.toCharArray(), SALT, ITERATIONS, 256)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            return SecretKeySpec(bytes, "AES")
        }

        fun forChannelKey(channelKey: String) = ChannelCrypto(derive(channelKey))
    }
}
