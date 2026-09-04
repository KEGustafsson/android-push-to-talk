package fi.crewradio

/**
 * Per-sender audio sequence admission, the pure part of loss detection: given the sequence
 * number of a packet that just arrived, how many frames went missing before it.
 *
 * Wrap-aware: distances are the signed 32-bit difference, so a sender's counter rolling
 * over from Int.MAX_VALUE is a distance of one, not a jump backwards. One lock for the
 * read-compare-write, because the same sender's packets arrive on several transport
 * threads; the caller keeps its follow-up (reserving the slots) inside [synchronized] on
 * this object if that has to be atomic with the admission.
 */
class SeqTracker {
    private val last = HashMap<Int, Int>()

    /**
     * Admits [seq] from [senderId]: the count of missing sequence numbers before it (0 when
     * it is the next expected one, or the first we hear), or -1 when it is late - a number
     * we have already moved past - and should be dropped.
     */
    @Synchronized
    fun admit(senderId: Int, seq: Int): Int {
        val prev = last.put(senderId, seq)
        if (prev == null) return 0
        val distance = seq - prev              // wraps with the counter
        if (distance <= 0) { last[senderId] = prev; return -1 }
        return distance - 1
    }

    /** Forgets senders no longer in [alive]. */
    @Synchronized
    fun retain(alive: Collection<Int>) { last.keys.retainAll(alive.toSet()) }

    @Synchronized
    fun clear() = last.clear()
}
