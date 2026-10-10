package com.engreader.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceAlignmentTest {

    @Test
    fun `splits a two-sentence translation on the full stop`() {
        val out = SentenceAlignment.align(
            listOf("The study was small.", "The researchers warned against conclusions."),
            "这项研究规模很小。研究人员警告不要下结论。",
        )
        assertEquals(listOf("这项研究规模很小。", "研究人员警告不要下结论。"), out)
    }

    @Test
    fun `keeps a closing quote with the sentence it closes`() {
        val out = SentenceAlignment.align(
            listOf("He said yes.", "She left."),
            "他说“好。”她走了。",
        )
        assertEquals(listOf("他说“好。”", "她走了。"), out)
    }

    @Test
    fun `does not split on a comma inside one sentence`() {
        val out = SentenceAlignment.align(
            listOf("A single sentence."),
            "这是一个句子，里面有逗号。",
        )
        assertEquals(listOf("这是一个句子，里面有逗号。"), out)
    }

    @Test
    fun `does not split on a semicolon inside one sentence`() {
        val out = SentenceAlignment.align(
            listOf("One.", "Two."),
            "第一句；还有后半截。第二句。",
        )
        assertEquals(listOf("第一句；还有后半截。", "第二句。"), out)
    }

    @Test
    fun `treats a run of ellipsis marks as one terminator`() {
        val out = SentenceAlignment.align(
            listOf("Wait.", "Then he spoke."),
            "等等……然后他开口了。",
        )
        assertEquals(listOf("等等……", "然后他开口了。"), out)
    }

    @Test
    fun `a single english sentence is never rejected for a missing full stop`() {
        val out = SentenceAlignment.align(listOf("One sentence only."), "只有一句话")
        assertEquals(listOf("只有一句话"), out)
    }

    @Test
    fun `returns null when the translation has more sentences than the english`() {
        // The translator split one of the two English sentences in two around the
        // quote, so the Chinese no longer lines up one-for-one; showing a piece under
        // the wrong English sentence would be a guess, so the caller falls back to the
        // whole paragraph.
        val out = SentenceAlignment.align(
            listOf("He said “yes” and left.", "She stayed."),
            "他说“好。”然后离开了。她留下了。",
        )
        assertNull(out)
    }

    @Test
    fun `a lone english sentence takes the whole translation however it splits`() {
        // Nothing to line up against: with one English sentence every Chinese sentence
        // in the paragraph belongs to it, so the split is not a mismatch.
        val out = SentenceAlignment.align(
            listOf("He said “yes” and left."),
            "他说“好。”然后离开了。",
        )
        assertEquals(listOf("他说“好。”然后离开了。"), out)
    }

    @Test
    fun `returns null when the translation has fewer sentences than the english`() {
        val out = SentenceAlignment.align(
            listOf("First.", "Second.", "Third."),
            "第一。第二。",
        )
        assertNull(out)
    }

    @Test
    fun `returns null for a blank translation`() {
        assertNull(SentenceAlignment.align(listOf("One.", "Two."), "   "))
    }

    @Test
    fun `returns null when there are no english sentences`() {
        assertNull(SentenceAlignment.align(emptyList(), "随便什么。"))
    }

    @Test
    fun `a newline also ends a sentence`() {
        val out = SentenceAlignment.align(
            listOf("One.", "Two."),
            "第一句\n第二句",
        )
        assertEquals(listOf("第一句", "第二句"), out)
    }

    @Test
    fun `a translation with a trailing space still aligns`() {
        val out = SentenceAlignment.align(
            listOf("One.", "Two."),
            " 第一句。第二句。 ",
        )
        assertEquals(listOf("第一句。", "第二句。"), out)
    }
}
