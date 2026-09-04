package fi.crewradio.transport

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Length-prefixed (uint16 BE) packet framing over a stream socket. Shared by BT and Wi-Fi Aware. */
class StreamLink(
    val label: String,
    input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit
) {
    private val input = DataInputStream(input)

    fun send(packet: ByteArray) {
        synchronized(output) {
            output.write(packet.size ushr 8)
            output.write(packet.size and 0xFF)
            output.write(packet)
            output.flush()
        }
    }

    /** Blocks until the link breaks. */
    fun readLoop(onPacket: (ByteArray) -> Unit) {
        while (true) {
            val len = input.readUnsignedShort()
            if (len <= 0 || len > 4096) throw IOException("bad frame length $len")
            val buf = ByteArray(len)
            input.readFully(buf)
            onPacket(buf)
        }
    }

    fun close() = try { closer() } catch (_: Exception) {}
}
