package fi.arabella.ptt.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun doublesUpToTheCapAndStaysThere() {
        val b = Backoff(firstMs = 1_000, maxMs = 15_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), List(6) { b.next() })
    }

    @Test
    fun resetStartsTheScheduleOver() {
        val b = Backoff(firstMs = 1_000, maxMs = 15_000)
        repeat(4) { b.next() }
        b.reset()
        assertEquals(1_000L, b.next())
        assertEquals(2_000L, b.next())
    }

    @Test
    fun capBelowFirstDelayIsRespected() {
        val b = Backoff(firstMs = 500, maxMs = 500)
        assertEquals(listOf(500L, 500L, 500L), List(3) { b.next() })
    }
}
