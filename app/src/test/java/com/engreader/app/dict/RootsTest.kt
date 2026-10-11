package com.engreader.app.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prefix/root/suffix cutter.
 *
 * Every case here is a word a reader of news prose meets early. The last few are the
 * guards: a cut is refused when the remainder is not a word, and inflectional endings
 * are deliberately not treated as suffixes.
 */
class RootsTest {

    /**
     * Stands in for the dictionary. The real caller passes `dictionary::lookup`, so the
     * cutter can tell `un` + `happy` from `un` + `derstand`; a fixture keeps the test
     * off SQLite.
     */
    private val known = setOf(
        "happy", "quick", "teach", "stand", "care", "view", "print", "port", "agree",
    )

    private fun explain(word: String) = Roots.explain(word) { it in known }

    private fun texts(word: String) = explain(word)!!.segments.map { it.text }

    @Test
    fun `unhappy is un plus happy`() {
        val breakdown = explain("unhappy")!!
        assertEquals(listOf("un", "happy"), breakdown.segments.map { it.text })
        val prefix = breakdown.affixes.single()
        assertEquals(AffixKind.Prefix, prefix.kind)
        assertEquals("不、相反", prefix.meaning)
        // The base is kept but not labelled: the table has nothing to say about `happy`.
        assertNull(breakdown.segments[1].affix)
    }

    @Test
    fun `quickly is quick plus ly`() {
        val breakdown = explain("quickly")!!
        assertEquals(listOf("quick", "ly"), breakdown.segments.map { it.text })
        assertEquals(AffixKind.Suffix, breakdown.affixes.single().kind)
    }

    /** The longest prefix wins: `under`, not `un` plus a mystery. */
    @Test
    fun `understand is under plus stand`() {
        val breakdown = explain("understand")!!
        assertEquals(listOf("under", "stand"), breakdown.segments.map { it.text })
        assertEquals("under", breakdown.affixes.single().text)
    }

    @Test
    fun `national cuts a root and two suffixes`() {
        val breakdown = explain("national")!!
        assertEquals(listOf("nat", "ion", "al"), breakdown.segments.map { it.text })
        val root = breakdown.affixes.first()
        assertEquals(AffixKind.Root, root.kind)
        assertEquals("出生", root.meaning)
        assertEquals(listOf(AffixKind.Root, AffixKind.Suffix, AffixKind.Suffix), breakdown.affixes.map { it.kind })
    }

    @Test
    fun `reaction is re plus act plus ion`() {
        val breakdown = explain("reaction")!!
        assertEquals(listOf("re", "act", "ion"), breakdown.segments.map { it.text })
        assertTrue(breakdown.affixes.any { it.kind == AffixKind.Root && it.text == "act" })
    }

    /** `spect` and `spec` both match; the longer root is the one worth showing. */
    @Test
    fun `inspect keeps the whole root`() {
        val breakdown = explain("inspect")!!
        assertEquals(listOf("in", "spect"), breakdown.segments.map { it.text })
        assertEquals("看", breakdown.affixes.last().meaning)
    }

    /** Suffixes were peeled off the end, so they have to be put back front-first. */
    @Test
    fun `carefully puts its suffixes back in reading order`() {
        assertEquals(listOf("care", "ful", "ly"), texts("carefully"))
    }

    @Test
    fun `reconstruct puts its prefixes in reading order`() {
        assertEquals(listOf("re", "con", "struct"), texts("reconstruct"))
    }

    /** A root inside the middle keeps the letters on either side, unlabelled. */
    @Test
    fun `include keeps the silent e it could not account for`() {
        val breakdown = explain("include")!!
        assertEquals(listOf("in", "clud", "e"), breakdown.segments.map { it.text })
        assertEquals("关闭", breakdown.affixes.last().meaning)
        assertNull(breakdown.segments.last().affix)
    }

    @Test
    fun `a word below the length floor is left alone`() {
        assertNull(Roots.explain("metal") { true })
    }

    /**
     * The guard the dictionary predicate exists for: `re-` looks like a prefix on
     * `reprint`, but the cut is only offered once `print` is known to stand on its own.
     */
    @Test
    fun `a lone prefix is refused when the remainder is not a word`() {
        assertNull(Roots.explain("reprint") { false })
        assertEquals(listOf("re", "print"), Roots.explain("reprint") { it == "print" }!!.segments.map { it.text })
    }

    /**
     * `-ed` and `-ing` are not in the suffix table. Inflection is the 同族词 panel's
     * job, and treating the ending as derivational would cut `sacred` in two.
     */
    @Test
    fun `an inflectional ending is not treated as a suffix`() {
        assertNull(Roots.explain("sacred") { true })
    }

    @Test
    fun `a word with nothing recognisable in it yields nothing`() {
        assertNull(explain("theatre"))
    }

    /**
     * A word the dictionary knows and the table does not must not come back as a single
     * unlabelled chip: there is no breakdown to show.
     */
    @Test
    fun `a plain word with no affix is not reported`() {
        assertNull(Roots.explain("capture") { it == "capture" })
    }

    @Test
    fun `case and punctuation do not matter`() {
        assertEquals(listOf("un", "happy"), texts("Unhappy,"))
    }
}
