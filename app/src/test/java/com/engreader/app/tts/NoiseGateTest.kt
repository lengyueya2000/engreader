package com.engreader.app.tts

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gain curve that removes the cloning voice's noise floor.
 *
 * The settings come from a measurement on existing audio (`verify/gate_experiment.py`):
 * a −45 dBFS threshold with up to −20 dB pulled the pause floor down 7–10 dB while the
 * speech lost 0.3–0.5 dB. Those are the two things this asserts, on a signal built to
 * have exactly that shape — a bed of hiss under speech-like tones — because "the gate
 * lowers the floor" and "the gate leaves the speech alone" are the whole contract, and
 * the failure that matters is a gate that has quietly become a level control.
 */
class NoiseGateTest {

    private val rate = 24_000

    /** A deterministic white-ish source: the test must not depend on a random seed. */
    private class Noise(seed: Long) {
        private var state = seed
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }
    }

    /** 220 Hz tone at [db] dBFS, [samples] long. */
    private fun tone(samples: Int, db: Double): FloatArray {
        val amplitude = 10.0.pow(db / 20.0)
        return FloatArray(samples) { (amplitude * sin(2 * PI * 220.0 * it / rate)).toFloat() }
    }

    /** Hiss at [db] dBFS, the shape the measured floor has. */
    private fun hiss(samples: Int, db: Double, seed: Long = 20261023L): FloatArray {
        val noise = Noise(seed)
        val amplitude = 10.0.pow(db / 20.0)
        return FloatArray(samples) { (amplitude * noise.next()).toFloat() }
    }

    @Test
    fun `the pause floor drops by the measured amount and the speech does not`() {
        // Half a second of speech at -16 dBFS, then half a second of hiss 38 dB under it —
        // the levels the device audio actually has.
        val speech = tone(rate / 2, -16.0)
        val pause = hiss(rate / 2, -54.0)
        val signal = speech + pause

        val gated = NoiseGate.apply(signal, rate)

        val pauseBefore = NoiseGate.levelDb(pause)
        val pauseAfter = NoiseGate.levelDb(gated.samples.copyOfRange(speech.size, signal.size))
        val speechAfter = NoiseGate.levelDb(gated.samples.copyOfRange(0, speech.size))

        assertTrue(
            "pause floor only moved ${pauseBefore - pauseAfter} dB",
            pauseAfter <= pauseBefore - 5.0,
        )
        assertEquals(
            "the speech must come through at the level it was synthesised at",
            NoiseGate.levelDb(speech),
            speechAfter,
            1.0,
        )
        assertTrue(gated.maxAttenuationDb < 0.0)
    }

    @Test
    fun `a clip with nothing quiet in it is returned untouched`() {
        val loud = tone(rate, -12.0)
        val result = NoiseGate.apply(loud, rate)
        // Identity, not equality: nothing below the threshold means nothing to pay for.
        assertSame(loud, result.samples)
        assertEquals(0.0, result.maxAttenuationDb, 0.0)
    }

    @Test
    fun `the deepest attenuation is bounded`() {
        // 80 dB under full scale: without the cap a slope of 1 would ask for -35 dB.
        val quiet = hiss(rate, -80.0)
        val result = NoiseGate.apply(quiet, rate)
        assertTrue(
            "attenuation ${result.maxAttenuationDb} dB went past the cap",
            result.maxAttenuationDb >= NoiseGate.MAX_ATTENUATION_DB - 1e-9,
        )
        assertTrue("the clip was erased rather than damped", result.samples.any { it != 0f })
    }

    @Test
    fun `the gate never amplifies`() {
        val signal = tone(rate / 2, -16.0) + hiss(rate / 2, -54.0)
        val gated = NoiseGate.apply(signal, rate)
        signal.indices.forEach { index ->
            assertTrue(
                "sample $index grew from ${signal[index]} to ${gated.samples[index]}",
                kotlin.math.abs(gated.samples[index]) <= kotlin.math.abs(signal[index]) + 1e-6f,
            )
        }
    }

    @Test
    fun `silence stays silence`() {
        val silent = FloatArray(rate)
        val result = NoiseGate.apply(silent, rate)
        assertTrue(result.samples.all { it == 0f })
    }

    @Test
    fun `a clip too short to hold one frame is left alone`() {
        val tiny = tone(4, -16.0)
        val result = NoiseGate.apply(tiny, rate)
        assertSame(tiny, result.samples)
    }

    @Test
    fun `an empty clip or a nonsense rate is left alone`() {
        val empty = FloatArray(0)
        assertSame(empty, NoiseGate.apply(empty, rate).samples)
        val some = tone(1024, -16.0)
        assertSame(some, NoiseGate.apply(some, 0).samples)
    }
}