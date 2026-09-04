package fi.crewradio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ConcealTest {

    private fun frame(vararg samples: Short): ByteArray {
        val f = ByteArray(AudioConfig.FRAME_BYTES)
        val sb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until AudioConfig.FRAME_SAMPLES) sb.put(samples[i % samples.size])
        return f
    }

    private fun sample(f: ByteArray, i: Int): Int =
        ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(i).toInt()

    @Test
    fun repeatsTheLastFrameWithDecayingGain() {
        val last = frame(10000, -10000)
        val first = Conceal.frame(last, 1)!!
        val second = Conceal.frame(last, 2)!!
        val third = Conceal.frame(last, 3)!!
        assertEquals(6000, sample(first, 0))
        assertEquals(-6000, sample(first, 1))
        assertEquals(3600, sample(second, 0))
        assertEquals(2160, sample(third, 0))
    }

    @Test
    fun givesUpAfterMaxFrames() {
        val last = frame(1000)
        assertNotNull(Conceal.frame(last, Conceal.MAX_FRAMES))
        assertNull(Conceal.frame(last, Conceal.MAX_FRAMES + 1))
        assertNull(Conceal.frame(last, 0))
    }

    @Test
    fun nothingToRepeatMeansNothing() {
        assertNull(Conceal.frame(null, 1))
        assertNull(Conceal.frame(ByteArray(10), 1))      // not a whole frame
    }

    @Test
    fun clampsAtFullScale() {
        val last = frame(32767, -32768)
        val f = Conceal.frame(last, 1)!!
        assertEquals((32767 * 0.6f).toInt(), sample(f, 0))
        assertEquals((-32768 * 0.6f).toInt(), sample(f, 1))
    }
}
