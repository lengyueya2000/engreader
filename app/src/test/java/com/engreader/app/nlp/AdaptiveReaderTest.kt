package com.engreader.app.nlp

import org.junit.Assert.assertEquals
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

    @Test
    fun `the comfort window is two to eight per cent`() {
        assertEquals(Fit.Easy, AdaptiveReader.fit(0.0))
        assertEquals(Fit.Easy, AdaptiveReader.fit(0.019))
        assertEquals(Fit.Comfort, AdaptiveReader.fit(0.02))
        assertEquals(Fit.Comfort, AdaptiveReader.fit(0.05))
        assertEquals(Fit.Comfort, AdaptiveReader.fit(0.08))
        assertEquals(Fit.Hard, AdaptiveReader.fit(0.081))
    }

    @Test
    fun `the reason names the count and matches the band`() {
        val comfortable = AdaptiveReader.reason(ready(1, unknown = 10))
        assertTrue(comfortable, comfortable.contains("10"))
        assertTrue(comfortable, comfortable.contains("i+1"))

        val easy = AdaptiveReader.reason(ready(1, unknown = 1))
        assertTrue(easy, easy.contains("轻松"))

        val hard = AdaptiveReader.reason(ready(1, unknown = 40))
        assertTrue(hard, hard.contains("40"))
        assertTrue(hard, hard.contains("最近"))
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
