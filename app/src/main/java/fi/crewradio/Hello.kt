package fi.crewradio

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Payload of a [Packet.Codec.HELLO] packet: who this node is and how it is connected.
 *
 *     ver u8 = 1 | transports u8 (bit flags) | ttl u8 | nameLen u8 | name UTF-8, at most 32 bytes
 *
 * `ttl` repeats the hop budget the sender stamped on the header, so a receiver counts the
 * hops a packet travelled as `ttl - header.ttl` without assuming everyone uses the default.
 * Every node sends one every second and relays the others like audio; that is the whole
 * roster protocol. Builds without it drop the packet as an unknown codec and are listed
 * from their audio alone, by id.
 */
class Hello(val name: String, val transports: Int, val ttl: Int) {

    fun encode(): ByteArray {
        val bytes = utf8Prefix(name, MAX_NAME_BYTES)
        val head = byteArrayOf(
            VERSION.toByte(), (transports and 0xFF).toByte(), ttl.coerceIn(0, 255).toByte(), bytes.size.toByte()
        )
        return head + bytes
    }

    companion object {
        const val VERSION = 1
        const val MAX_NAME_BYTES = 32

        const val LAN = 1
        const val BT = 2
        const val AWARE = 4

        /**
         * Parses [length] bytes at [offset]; null for anything off the wire contract — an unknown
         * version, a name over [MAX_NAME_BYTES], trailing bytes, or invalid UTF-8. These packets come
         * from whoever is on the same network, so nothing about them is taken on trust. Control
         * characters are stripped from the name so it stays one line on screen.
         */
        fun decode(p: ByteArray, offset: Int, length: Int): Hello? {
            if (length < 4 || p[offset].toInt() != VERSION) return null
            val nameLen = p[offset + 3].toInt() and 0xFF
            if (nameLen > MAX_NAME_BYTES || length != 4 + nameLen) return null
            val name = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(p, offset + 4, nameLen))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            return Hello(name.filterNot { it.isISOControl() }, p[offset + 1].toInt() and 0xFF, p[offset + 2].toInt() and 0xFF)
        }

        /** The flag for a transport, by the name it reports; unknown transports carry no flag. */
        fun bitFor(transportName: String): Int = when (transportName) {
            "LAN" -> LAN
            "BT" -> BT
            "Aware" -> AWARE
            else -> 0
        }

        /** Flags as text, e.g. `LAN+BT`; empty when none are set. */
        fun describe(flags: Int): String = buildList {
            if (flags and LAN != 0) add("LAN")
            if (flags and BT != 0) add("BT")
            if (flags and AWARE != 0) add("Aware")
        }.joinToString("+")

        /** The longest prefix of [s] whose UTF-8 form fits in [max] bytes, never cutting a code point. */
        private fun utf8Prefix(s: String, max: Int): ByteArray {
            var end = 0
            var bytes = 0
            while (end < s.length) {
                val cp = s.codePointAt(end)
                val size = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > max) break
                bytes += size
                end += Character.charCount(cp)
            }
            return s.substring(0, end).toByteArray(StandardCharsets.UTF_8)
        }
    }
}
