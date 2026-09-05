package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun aNormalTalkerIsNeverThrottled() {
        val r = RateLimiter()
        var dropped = 0
        var t = 0L
        repeat(60 * 51) {            // 51 packets a second (50 audio + 1 hello) for a minute
            if (!r.allow(1, t)) dropped++
            t += 1000 / 51
        }
        assertEquals(0, dropped)
    }

    @Test
    fun aFloodIsCutToTheBudget() {
        val r = RateLimiter(perSecond = 75.0, burst = 150.0)
        var passed = 0
        repeat(2000) { if (r.allow(1, 0L)) passed++ }          // 2000 packets in the same millisecond
        assertEquals(150, passed)                              // the burst, then nothing
        var later = 0
        repeat(2000) { if (r.allow(1, 1000L)) later++ }        // a second later: 75 more
        assertEquals(75, later)
    }

    @Test
    fun sendersAreIndependent() {
        val r = RateLimiter(perSecond = 10.0, burst = 10.0)
        repeat(10) { assertTrue(r.allow(1, 0L)) }
        assertFalse(r.allow(1, 0L))
        assertTrue(r.allow(2, 0L))
    }

    @Test
    fun rotatingSenderIdsCannotExceedTheGlobalBudget() {
        val r = RateLimiter(perSecond = 75.0, burst = 150.0, globalPerSecond = 400.0, globalBurst = 800.0, maxSenders = 100_000)
        var passed = 0
        repeat(100_000) { i -> if (r.allow(i, 0L)) passed++ }   // a fresh sender id on every packet
        assertEquals(800, passed)                                 // the global burst, whatever the ids
        var later = 0
        repeat(100_000) { i -> if (r.allow(-i, 1000L)) later++ }
        assertEquals(400, later)
    }

    @Test
    fun theSenderTableIsBounded() {
        val r = RateLimiter(perSecond = 10.0, burst = 10.0, globalPerSecond = 1e9, globalBurst = 1e9, maxSenders = 4)
        for (id in 1..4) assertTrue(r.allow(id, 0L))
        assertFalse(r.allow(5, 0L))                               // no room: refused, not allocated
        assertTrue(r.allow(1, 0L))                                // the known ones still work
        assertTrue(r.allow(5, 20_000L))                           // after the sweep there is room again
    }

    @Test
    fun unauthenticatedPacketsNeverChargeASender() {
        val r = RateLimiter(perSecond = 10.0, burst = 10.0, globalPerSecond = 1e6, globalBurst = 1e6)
        repeat(100_000) { assertTrue(r.allowGlobal(0L)) }        // a flood claiming to be sender 1, before the tag check
        repeat(10) { assertTrue(r.allowSender(1, 0L)) }           // sender 1 still has its whole burst
        assertFalse(r.allowSender(1, 0L))
    }

    @Test
    fun anIdleSenderIsForgottenAndStartsFresh() {
        val r = RateLimiter(perSecond = 10.0, burst = 10.0, forgetMs = 1000)
        repeat(10) { r.allow(1, 0L) }
        assertFalse(r.allow(1, 0L))
        assertTrue(r.allow(1, 5000L))          // refilled anyway, and the bucket had been swept
    }
}
