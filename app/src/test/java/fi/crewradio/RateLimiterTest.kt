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
    fun anIdleSenderIsForgottenAndStartsFresh() {
        val r = RateLimiter(perSecond = 10.0, burst = 10.0, forgetMs = 1000)
        repeat(10) { r.allow(1, 0L) }
        assertFalse(r.allow(1, 0L))
        assertTrue(r.allow(1, 5000L))          // refilled anyway, and the bucket had been swept
    }
}
