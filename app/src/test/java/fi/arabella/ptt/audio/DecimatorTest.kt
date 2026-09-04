package fi.arabella.ptt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DecimatorTest {

    private val frame48k = 960   // one 20 ms frame at 48 kHz

    @Test
    fun produces320SamplesPerFrameEveryFrame() {
        val d = Decimator(3)
        repeat(5) { assertEquals(320, d.process(ShortArray(frame48k)).size) }
    }

    @Test
    fun keepsPhaseAcrossOddSizedChunks() {
        val d = Decimator(3)
        val total = d.process(ShortArray(7)).size + d.process(ShortArray(500)).size + d.process(ShortArray(453)).size
        assertEquals(320, total)
    }

    @Test
    fun passesDcAtUnityGain() {
        val d = Decimator(3)
        val out = d.process(ShortArray(frame48k) { 1000 })
        for (i in 40 until out.size) assertTrue("sample $i = ${out[i]}", abs(out[i] - 1000) <= 2)
    }

    @Test
    fun attenuatesAliasingContentAboveNewNyquist() {
        val d = Decimator(3)
        // Alternating +/-8000 is a tone at 24 kHz; after decimation it must be gone, not folded to 8 kHz.
        val out = d.process(ShortArray(frame48k) { if (it % 2 == 0) 8000 else -8000 })
        for (i in 40 until out.size) assertTrue("sample $i = ${out[i]}", abs(out[i].toInt()) < 200)
    }

    @Test
    fun unityDcGainForAnyFactor() {
        for (f in 1..6) assertEquals(1.0, Decimator.design(f).sum().toDouble(), 1e-5)
    }
}
