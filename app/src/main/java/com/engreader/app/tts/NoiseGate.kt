package com.engreader.app.tts

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Removes the cloning voice's noise floor, which is the one part of "it does not sound
 * like a recording" that is measurable and cheap to fix.
 *
 * Measured, same-text, twelve sentences, against the speaker's *own room floor*: the
 * clone's pauses sit 6.76 dB (p05) and 7.11 dB (mean) higher, and the excess is a
 * stationary wideband hiss — spectral centroid 2438 Hz against the room's 522 Hz —
 * about 38 dB below the speech. Stationary, broadband, and well below the signal is
 * exactly the shape a downward expander is for, and unlike a model change it costs no
 * bytes in the APK and no time in the engine.
 *
 * Two measurements decided the settings, both done on existing audio before any of this
 * was written into the app (see `verify/gate_experiment.py`):
 *
 * - threshold and depth: a −45 dBFS threshold with up to −20 dB pulls the pause floor
 *   down 7–10 dB while the speech loses 0.3–0.5 dB. A milder −48/−12 setting bought
 *   less; neither touched the speech enough to hear.
 * - release: the gain is interpolated between frames and then smoothed with a one-pole
 *   release. Stepping the gain at frame boundaries is what "pumping" is.
 *
 * It is deliberately *not* applied to the other voices. Their floors were never
 * measured, and the two Piper voices share neither the model nor the vocoder that the
 * cloning voice's floor comes from — gating audio nobody complained about would be a
 * change made on faith.
 *
 * Pure Kotlin with no Android imports, so the shape of the gain curve can be tested
 * against numbers instead of against a device.
 */
internal object NoiseGate {

    /** Below this frame level the gate starts pulling down. */
    const val THRESHOLD_DB = -45.0

    /** The most it may pull down, so a clip that is mostly quiet is damped, not erased. */
    const val MAX_ATTENUATION_DB = -20.0

    /** How hard it pulls: 1.0 means a frame 10 dB under the threshold loses 10 dB. */
    const val SLOPE = 1.0

    /** Analysis window. Short enough to open between words, long enough to be a level. */
    const val FRAME_MS = 10.0

    /** How fast the gain comes back up once a frame is loud enough. */
    const val RELEASE_MS = 80.0

    /**
     * How fast the gain opens when speech starts, which is not the release.
     *
     * The two used to be the same number, and the KDoc on this object said the attack was
     * instantaneous while the code applied the 80 ms one-pole to both directions. A gate
     * that opens in 80 ms is still mostly closed when the first consonant arrives: measured
     * on twenty real per-sentence clips, the first 60 ms of a sentence lost up to 5.67 dB
     * and kept as little as 49% of its peak. Consonant bursts are what makes speech sound
     * clear, so the gate was working against the thing it exists to help.
     *
     * 5 ms is open by the second sample of a burst and still slow enough not to click.
     * Measured against the same twenty clips it keeps the pause-floor win (10.80 dB
     * against 10.88) and moves the gain curve *less* (std 1.91 dB against 2.52), so it
     * costs nothing in pumping.
     */
    const val ATTACK_MS = 5.0

    /**
     * What one pass did: the samples to play, and the deepest attenuation it reached.
     *
     * A gate that attenuates nothing below [MAX_ATTENUATION_DB] on a clip with speech in
     * it is a gate that has been misconfigured into a level control, which is why the
     * depth is reported rather than assumed.
     */
    internal class Result(val samples: FloatArray, val maxAttenuationDb: Double)

    /**
     * Returns [samples] with everything quieter than [THRESHOLD_DB] pulled down.
     *
     * The input array is returned unchanged when no frame is below the threshold, so a
     * voice with nothing to fix pays nothing but the analysis.
     */
    fun apply(samples: FloatArray, sampleRate: Int): Result {
        if (samples.isEmpty() || sampleRate <= 0) return Result(samples, 0.0)
        val frame = ((sampleRate * FRAME_MS) / 1000.0).toInt().coerceAtLeast(16)
        val hop = (frame / 2).coerceAtLeast(1)
        val frames = (samples.size - frame) / hop + 1
        if (frames < 2) return Result(samples, 0.0)

        val gainDb = DoubleArray(frames)
        var deepest = 0.0
        for (index in 0 until frames) {
            val start = index * hop
            var sum = 0.0
            for (offset in 0 until frame) {
                val sample = samples[start + offset].toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / frame)
            // A frame of digital silence is -inf, not a small number; clipping the level
            // keeps the arithmetic below finite.
            val levelDb = if (rms <= 0.0) -200.0 else 20.0 * log10(rms)
            val gain = if (levelDb < THRESHOLD_DB) {
                max((levelDb - THRESHOLD_DB) * SLOPE, MAX_ATTENUATION_DB)
            } else {
                0.0
            }
            gainDb[index] = gain
            if (gain < deepest) deepest = gain
        }
        if (deepest == 0.0) return Result(samples, 0.0)

        val gated = FloatArray(samples.size)
        val release = exp(-1.0 / (sampleRate * RELEASE_MS / 1000.0))
        val attack = exp(-1.0 / (sampleRate * ATTACK_MS / 1000.0))
        val centre = frame / 2.0
        var smoothed = gainDb[0]
        for (index in samples.indices) {
            val position = (index - centre) / hop
            val gain = when {
                position <= 0.0 -> gainDb[0]
                position >= (frames - 1).toDouble() -> gainDb[frames - 1]
                else -> {
                    val lower = position.toInt()
                    val fraction = position - lower
                    gainDb[lower] * (1.0 - fraction) + gainDb[lower + 1] * fraction
                }
            }
            // Opening and closing use different constants. The gain rising means speech has
            // arrived and the gate has to be out of the way immediately; the gain falling
            // means a pause has started and the pull-down has to be slow enough to be
            // inaudible. One constant for both is what made every sentence start quiet.
            val coefficient = if (gain > smoothed) attack else release
            smoothed = smoothed * coefficient + gain * (1.0 - coefficient)
            gated[index] = (samples[index] * 10.0.pow(smoothed / 20.0)).toFloat()
        }
        return Result(gated, deepest)
    }

    /** The level of one signal in dBFS, for the tests and for the log line. */
    fun levelDb(samples: FloatArray): Double {
        if (samples.isEmpty()) return Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (sample in samples) {
            val value = abs(sample.toDouble())
            sum += value * value
        }
        val rms = sqrt(sum / samples.size)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
    }
}