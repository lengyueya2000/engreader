package com.engreader.app.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frequency-band sampler and its estimate.
 *
 * The exact interval is pinned in one test so a change to the formula is a visible
 * decision rather than a drifting number; the rest check the shape of the answer.
 */
class VocabularySizeTest {

    private val firstBand = VocabularySize.BANDS.first()
    private val tailBand = VocabularySize.BANDS.last()

    @Test
    fun `the band widths add up to the population the estimate covers`() {
        assertEquals(50_000, VocabularySize.BANDS.sumOf { it.width })
    }

    @Test
    fun `the same seed asks the same words`() {
        val once = VocabularySize.offsets(seed = 42L, band = firstBand, available = 1000)
        val twice = VocabularySize.offsets(seed = 42L, band = firstBand, available = 1000)
        assertEquals(once, twice)
    }

    @Test
    fun `a different seed asks different words`() {
        val a = VocabularySize.offsets(seed = 42L, band = firstBand, available = 1000)
        val b = VocabularySize.offsets(seed = 43L, band = firstBand, available = 1000)
        assertNotEquals(a, b)
    }

    @Test
    fun `offsets stay inside the band and stay distinct`() {
        val offsets = VocabularySize.offsets(seed = 7L, band = tailBand, available = 24_418)
        assertEquals(VocabularySize.PER_BAND, offsets.size)
        assertEquals(offsets.size, offsets.toSet().size)
        assertTrue(offsets.all { it in 0 until 24_418 })
        assertEquals(offsets, offsets.sorted())
    }

    /** Eight words in a row would behave like one question asked eight times. */
    @Test
    fun `offsets are spread across the whole band`() {
        val offsets = VocabularySize.offsets(seed = 7L, band = firstBand, available = 1000)
        assertTrue("first offset ${offsets.first()} is not near the top", offsets.first() < 125)
        assertTrue("last offset ${offsets.last()} is not near the bottom", offsets.last() >= 875)
    }

    @Test
    fun `a band narrower than the sample yields only what it has`() {
        val offsets = VocabularySize.offsets(seed = 7L, band = firstBand, available = 3)
        assertEquals(listOf(0, 1, 2), offsets)
    }

    @Test
    fun `an empty band yields nothing`() {
        assertTrue(VocabularySize.offsets(seed = 7L, band = firstBand, available = 0).isEmpty())
    }

    /**
     * Nine words for eight probes: `available / take` is 1, so there is no room for a
     * jitter inside a window and every seed produced offsets 0..7 — the ninth word could
     * never be asked, and two tests of that band asked exactly the same words.
     */
    @Test
    fun `a band one word wider than the sample can still ask its last word`() {
        val scheduled = (0L..40L).map {
            VocabularySize.offsets(seed = it, band = firstBand, available = 9)
        }
        scheduled.forEach { offsets ->
            assertEquals(VocabularySize.PER_BAND, offsets.size)
            assertEquals(offsets.size, offsets.toSet().size)
            assertTrue("offset out of range: $offsets", offsets.all { it in 0 until 9 })
            assertEquals(offsets, offsets.sorted())
        }
        assertTrue(
            "the ninth word was never asked: ${scheduled.map { it.last() }.distinct()}",
            scheduled.any { 8 in it },
        )
        assertTrue("offsets did not vary with the seed", scheduled.toSet().size > 1)
    }

    @Test
    fun `everything known estimates the whole population`() {
        val n = VocabularySize.BANDS.map { VocabularySize.PER_BAND }
        val all = VocabularySize.estimate(n, n)
        assertEquals(50_000, all.estimate)
        assertEquals(50_000, all.high)
        assertEquals(64, all.sampled)
        assertEquals(64, all.known)
        // Unanimous answers are still only eight samples a band, so the interval has
        // real width rather than collapsing to a point.
        assertEquals(43_345, all.low)
    }

    @Test
    fun `nothing known estimates zero`() {
        val n = VocabularySize.BANDS.map { VocabularySize.PER_BAND }
        val none = VocabularySize.estimate(List(n.size) { 0 }, n)
        assertEquals(0, none.estimate)
        assertEquals(0, none.low)
        // Eight "no"s per band is not proof of zero: the interval has to leave room for
        // the words the reader would have recognised had they been asked.
        assertEquals(6_655, none.high)
    }

    /**
     * Half of the first band is 500 words. The interval is the Agresti–Coull standard
     * error scaled to the band: 1.96 · √(1000² · 0.25 / 12) ≈ 283.
     */
    @Test
    fun `half of one band scales to half its width with a binomial interval`() {
        val result = VocabularySize.estimate(knownPerBand = listOf(4), sampledPerBand = listOf(8))
        assertEquals(500, result.estimate)
        assertEquals(217, result.low)
        assertEquals(783, result.high)
    }

    /**
     * A reader who aced a band knows at least most of it, so the lower bound cannot be
     * dragged to zero by the uncertainty of the bands they failed.
     */
    @Test
    fun `a band that was aced sets a floor under the interval`() {
        val known = MutableList(VocabularySize.BANDS.size) { 0 }
        val sampled = MutableList(VocabularySize.BANDS.size) { 0 }
        known[0] = 8
        sampled[0] = 8
        val result = VocabularySize.estimate(known, sampled)
        assertEquals(1000, result.estimate)
        // Eight of eight is a 95% lower bound of about 0.68 on the band's share.
        assertTrue("interval ${result.low}..${result.high} starts too low", result.low >= 800)
        assertTrue("interval ${result.low}..${result.high} is inverted", result.low <= result.estimate)
    }

    @Test
    fun `a band nobody answered is skipped rather than counted as zero`() {
        val result = VocabularySize.estimate(
            knownPerBand = listOf(8, 0, 0, 0, 0, 0, 0, 0),
            sampledPerBand = listOf(8, 0, 0, 0, 0, 0, 0, 0),
        )
        assertEquals(1000, result.estimate)
        assertEquals(8, result.sampled)
    }

    /** The tail band is 30,000 words wide, so one hit in eight is worth 3,750 on its own. */
    @Test
    fun `the wide tail band widens the interval`() {
        val known = MutableList(VocabularySize.BANDS.size) { 0 }
        val sampled = MutableList(VocabularySize.BANDS.size) { 0 }
        known[tailBand.index] = 1
        sampled[tailBand.index] = 8
        val result = VocabularySize.estimate(known, sampled)
        assertEquals(3750, result.estimate)
        assertTrue("interval ${result.low}..${result.high} is too tight", result.high - result.low > 6000)
    }

    @Test
    fun `answers are counted into their own bands`() {
        val answers = listOf(
            VocabProbe("the", 0) to true,
            VocabProbe("of", 0) to true,
            VocabProbe("abacus", 7) to false,
            VocabProbe("abate", 7) to true,
        )
        val result = VocabularySize.estimate(answers)
        assertEquals(4, result.sampled)
        assertEquals(3, result.known)
    }

    @Test
    fun `a probe from a band that does not exist is ignored`() {
        val result = VocabularySize.estimate(listOf(VocabProbe("x", 99) to true))
        assertEquals(0, result.sampled)
        assertEquals(0, result.estimate)
    }
}
