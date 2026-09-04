package fi.arabella.ptt

import java.nio.ByteBuffer

/** Wire format: 'P' 'T' | senderId int32 | seq int32 | PCM16LE payload. 10-byte header. */
object Packet {
    const val HEADER = 10

    class Header(val senderId: Int, val seq: Int)

    fun encode(senderId: Int, seq: Int, pcm: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(HEADER + pcm.size)
        bb.put('P'.code.toByte()).put('T'.code.toByte())
        bb.putInt(senderId).putInt(seq)
        bb.put(pcm)
        return bb.array()
    }

    fun parse(p: ByteArray): Header? {
        if (p.size <= HEADER || p[0] != 'P'.code.toByte() || p[1] != 'T'.code.toByte()) return null
        val bb = ByteBuffer.wrap(p, 2, 8)
        return Header(bb.int, bb.int)
    }
}
