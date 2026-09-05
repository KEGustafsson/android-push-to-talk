package fi.crewradio

/**
 * Ingress budget for received packets, checked before anything else is spent on them.
 *
 * Two layers. A global token bucket ([globalPerSecond], [globalBurst]) bounds what this phone
 * will look at in total, whatever sender ids the packets claim: sender ids are chosen by the
 * sender, so a flood that rotates them cannot buy itself more. Under that, one bucket per
 * sender ([perSecond], [burst]) so a single noisy or broken peer cannot starve the others. A
 * healthy sender needs 50 audio packets a second plus one hello; the global budget covers
 * several talking at once. The sender table is bounded by [maxSenders]: when it is full an
 * unknown sender is refused rather than allocated, and senders idle for [forgetMs] are swept.
 * Pure Kotlin, unit-tested; the caller supplies the clock.
 */
class RateLimiter(
    private val perSecond: Double = 75.0,
    private val burst: Double = 150.0,
    private val globalPerSecond: Double = 400.0,
    private val globalBurst: Double = 800.0,
    private val maxSenders: Int = 128,
    private val forgetMs: Long = 10_000
) {
    private class Bucket(var tokens: Double, var lastMs: Long)

    private val global = Bucket(globalBurst, 0)
    private val buckets = HashMap<Int, Bucket>()
    private var lastSweepMs = 0L

    /** True if the packet from [senderId] at [nowMs] is within both budgets. */
    @Synchronized
    fun allow(senderId: Int, nowMs: Long): Boolean {
        if (!take(global, nowMs, globalPerSecond, globalBurst)) return false
        if (nowMs - lastSweepMs > forgetMs) {
            buckets.values.removeIf { nowMs - it.lastMs > forgetMs }
            lastSweepMs = nowMs
        }
        val b = buckets[senderId] ?: run {
            if (buckets.size >= maxSenders) return false      // table full: no state for a newcomer
            Bucket(burst, nowMs).also { buckets[senderId] = it }
        }
        return take(b, nowMs, perSecond, burst)
    }

    private fun take(b: Bucket, nowMs: Long, rate: Double, cap: Double): Boolean {
        val elapsed = (nowMs - b.lastMs).coerceAtLeast(0)
        b.tokens = (b.tokens + elapsed * rate / 1000.0).coerceAtMost(cap)
        b.lastMs = nowMs
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    @Synchronized
    fun clear() { buckets.clear(); global.tokens = globalBurst; global.lastMs = 0 }
}
