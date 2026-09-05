package fi.crewradio

/**
 * Per-sender token buckets: a sender may pass [burst] packets at once and [perSecond] on
 * average; beyond that its packets are dropped before they cost anything (relay, decoder,
 * mixer). A healthy sender needs 50 audio packets a second plus one hello, so the defaults
 * leave headroom for jitter and none for a flood. Senders idle for [forgetMs] are forgotten.
 * Pure Kotlin, unit-tested; the caller supplies the clock.
 */
class RateLimiter(
    private val perSecond: Double = 75.0,
    private val burst: Double = 150.0,
    private val forgetMs: Long = 10_000
) {
    private class Bucket(var tokens: Double, var lastMs: Long)

    private val buckets = HashMap<Int, Bucket>()
    private var lastSweepMs = 0L

    /** True if the packet from [senderId] at [nowMs] is within the budget. */
    @Synchronized
    fun allow(senderId: Int, nowMs: Long): Boolean {
        if (nowMs - lastSweepMs > forgetMs) {
            buckets.values.removeIf { nowMs - it.lastMs > forgetMs }
            lastSweepMs = nowMs
        }
        val b = buckets.getOrPut(senderId) { Bucket(burst, nowMs) }
        val elapsed = (nowMs - b.lastMs).coerceAtLeast(0)
        b.tokens = (b.tokens + elapsed * perSecond / 1000.0).coerceAtMost(burst)
        b.lastMs = nowMs
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    @Synchronized
    fun clear() = buckets.clear()
}
