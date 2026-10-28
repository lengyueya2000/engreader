package com.engreader.app.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The i+1 pick.
 *
 * The interesting cases are the edges: a library that is all too easy, one that is all
 * too hard, and the short piece whose rate is arithmetic rather than information.
 */
class AdaptiveReaderTest {

    private fun ready(id: Long, unknown: Int, total: Int = 200) = Readiness(id, unknown, total)

    @Test
    fun `picks the article nearest the comfort rate`() {
        val pick = AdaptiveReader.pick(
            listOf(
                ready(1, unknown = 40),  // 20%
                ready(2, unknown = 10),  // 5%
                ready(3, unknown = 2),   // 1%
            )
        )
        assertEquals(2L, pick?.id)
    }

    /** An article the reader already knows everything in teaches nothing. */
    @Test
    fun `prefers a little challenge over none`() {
        val pick = AdaptiveReader.pick(
            listOf(
                ready(1, unknown = 0),
                ready(2, unknown = 8), // 4%, closer to 5% than 0% is
            )
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `an article too short to profile is not a candidate`() {
        val pick = AdaptiveReader.pick(
            listOf(
                ready(1, unknown = 1, total = 10),
                ready(2, unknown = 30),
            )
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `a library that is entirely too hard yields the easiest of them`() {
        val pick = AdaptiveReader.pick(
            listOf(ready(1, unknown = 60), ready(2, unknown = 40), ready(3, unknown = 80))
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `ties go to the shorter unknown list`() {
        // 6/120 and 10/200 are both 5%; the one asking less of the reader wins.
        val pick = AdaptiveReader.pick(
            listOf(ready(1, unknown = 10, total = 200), ready(2, unknown = 6, total = 120))
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `nothing to choose from yields nothing`() {
        assertNull(AdaptiveReader.pick(emptyList()))
        assertNull(AdaptiveReader.pick(listOf(ready(1, unknown = 1, total = 10))))
    }

    /**
     * The badge's bands, which are now [VocabularyProfile.verdictFor]'s.
     *
     * **Behaviour intentionally changed.** The badge used to carry a 2–8% window of its
     * own, so a 6%-unknown article wore `i+1 · 刚好` while the reader's own sheet, built
     * from the same rate, said 生词偏多，建议先预习生词再读. The boundaries are the verdict's
     * 2% / 5% / 10%, so `fit(0.02)` is now Easy (was Comfort) and `fit(0.08)` is now
     * Hard (was Comfort) — see [AdaptiveReader.fit] for why the window narrowed.
     */
    @Test
    fun `the badge uses the reader's verdict bands`() {
        assertEquals(Fit.Easy, AdaptiveReader.fit(0.0))
        assertEquals(Fit.Easy, AdaptiveReader.fit(0.019))
        assertEquals(Fit.Easy, AdaptiveReader.fit(0.02))
        assertEquals(Fit.Comfort, AdaptiveReader.fit(0.021))
        assertEquals(Fit.Comfort, AdaptiveReader.fit(0.05))
        assertEquals(Fit.Hard, AdaptiveReader.fit(0.051))
        assertEquals(Fit.Hard, AdaptiveReader.fit(0.08))
        assertEquals(Fit.Hard, AdaptiveReader.fit(0.081))
        assertEquals(Fit.Hard, AdaptiveReader.fit(0.5))
    }

    /**
     * The invariant the two used to break: nothing the verdict calls a stretch or too
     * hard may wear an "i+1" chip, and everything it calls comfortable is the easy band.
     */
    @Test
    fun `no rate can be challenging to the verdict and comfortable to the badge`() {
        var rate = 0.0
        while (rate <= 0.25) {
            val verdict = VocabularyProfile.verdictFor(rate.toFloat())
            when (verdict) {
                VocabularyProfile.Verdict.Comfortable ->
                    assertEquals("$rate is $verdict", Fit.Easy, AdaptiveReader.fit(rate))
                VocabularyProfile.Verdict.Suitable ->
                    assertEquals("$rate is $verdict", Fit.Comfort, AdaptiveReader.fit(rate))
                VocabularyProfile.Verdict.Stretch,
                VocabularyProfile.Verdict.TooHard ->
                    assertEquals("$rate is $verdict", Fit.Hard, AdaptiveReader.fit(rate))
            }
            rate += 0.001
        }
    }

    @Test
    fun `the reason names the count and matches the band`() {
        val comfortable = AdaptiveReader.reason(ready(1, unknown = 10))
        assertTrue(comfortable, comfortable.contains("10"))
        assertTrue(comfortable, comfortable.contains("i+1"))
        // A share of the study-worthy lemmas, not of the running text.
        assertTrue(comfortable, comfortable.contains("必学词"))

        val easy = AdaptiveReader.reason(ready(1, unknown = 1))
        assertTrue(easy, easy.contains("轻松"))

        val hard = AdaptiveReader.reason(ready(1, unknown = 40))
        assertTrue(hard, hard.contains("40"))
        assertTrue(hard, hard.contains("最近"))
    }

    /**
     * A text the profiler skipped has no rate at all, and `fit(0.0)` is the easy band —
     * so the card used to read "生词只有 0 个…这篇读起来很轻松" about an unmeasured text.
     */
    @Test
    fun `a text too short to profile is reported as unmeasured`() {
        val short = AdaptiveReader.reason(Readiness(id = 7, unknown = 0, total = 0))
        assertFalse(short, short.contains("轻松"))
        assertFalse(short, short.contains("i+1"))

        val nearly = AdaptiveReader.reason(Readiness(id = 8, unknown = 1, total = 5))
        assertFalse(nearly, nearly.contains("轻松"))
    }

    /** The best on offer is not an i+1, and the chip must not say it is. */
    @Test
    fun `only the comfortable and easy bands wear the i plus one label`() {
        assertTrue(Fit.Comfort.chip.startsWith("i+1"))
        assertTrue(Fit.Easy.chip.startsWith("i+1"))
        assertTrue(Fit.Hard.chip, !Fit.Hard.chip.contains("i+1"))
        assertTrue(Fit.Hard.chip, Fit.Hard.chip.contains("最接近"))
    }

    @Test
    fun `a vocabulary estimate turns the frequent words into known ones`() {
        val lemmas = listOf("time", "work", "quaint", "obsequious")
        val ranks = mapOf("time" to 40, "work" to 300, "quaint" to 9000, "obsequious" to 18000)
        val known = AdaptiveReader.baselineKnown(lemmas, estimate = 5000) { ranks.getValue(it) }
        assertEquals(setOf("time", "work"), known)
    }

    /** No estimate means no claim: the wordbook is the only source of "known". */
    @Test
    fun `without an estimate nothing is assumed known`() {
        val lemmas = listOf("time", "work")
        assertTrue(AdaptiveReader.baselineKnown(lemmas, null) { 40 }.isEmpty())
        assertTrue(AdaptiveReader.baselineKnown(lemmas, 0) { 40 }.isEmpty())
    }

    /** A word the dictionary has no frequency for is never assumed known. */
    @Test
    fun `an unranked word is not assumed known`() {
        val known = AdaptiveReader.baselineKnown(listOf("zorblat"), estimate = 20000) { 0 }
        assertTrue(known.isEmpty())
    }

    @Test
    fun `the estimate is inclusive of the rank itself`() {
        val known = AdaptiveReader.baselineKnown(listOf("edge"), estimate = 1000) { 1000 }
        assertEquals(setOf("edge"), known)
    }
}
