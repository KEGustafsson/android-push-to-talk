package fi.arabella.ptt.transport

/**
 * Retry delay that doubles from [firstMs] up to [maxMs]: 1 s, 2 s, 4 s, 8 s, 15 s, 15 s, …
 *
 * Every transport reconnects on its own schedule with one of these: a peer that has not
 * pressed Connect yet, a Bluetooth link that walked out of range, Wi-Fi that dropped.
 * Pure Kotlin so the schedule is unit-tested; the callers own the sleeping.
 */
internal class Backoff(private val firstMs: Long = 1_000, private val maxMs: Long = 15_000) {
    private var current = firstMs

    /** Delay to wait before the next attempt; each call grows the one after it. */
    @Synchronized fun next(): Long {
        val delay = current
        current = minOf(current * 2, maxMs)
        return delay
    }

    /** Back to the first delay, once an attempt has succeeded. */
    @Synchronized fun reset() {
        current = firstMs
    }
}
