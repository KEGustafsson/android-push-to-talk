package fi.arabella.ptt.transport

/**
 * A bidirectional link carrier. Implementations must be safe to call send()
 * from any thread and must deliver onPacket() from their own receive thread.
 *
 * `link` is an opaque token identifying the specific peer connection a packet
 * arrived on, so the relay can avoid echoing it straight back. Broadcast
 * transports (LAN multicast) pass null.
 */
interface Transport {
    val name: String

    /** True if forwarding a packet to *other* links of this same transport makes sense (BT, Aware). False for multicast. */
    val relayWithin: Boolean

    fun start(onPacket: (packet: ByteArray, transport: Transport, link: Any?) -> Unit, onStatus: (String) -> Unit)
    fun send(packet: ByteArray, except: Any? = null)
    fun stop()
}
