package com.engreader.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceAlignmentTest {

    // ------------------------------------------------------------ exact count match

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

    @Test
    fun `returns null for a blank translation`() {
        assertNull(SentenceAlignment.align(listOf("One.", "Two."), "   "))
    }

    @Test
    fun `returns null when there are no english sentences`() {
        assertNull(SentenceAlignment.align(emptyList(), "随便什么。"))
    }

    // -------------------------------------------------------- mismatched counts

    @Test
    fun `one chinese sentence supplies two english ones`() {
        // The translator joined the English sentences; the Chinese still carries the
        // comma that separates them, so the boundary can be found.
        val out = SentenceAlignment.align(
            listOf("The sample was young.", "The authors warned against conclusions."),
            "样本很年轻，作者警告不要下结论。",
        )
        assertEquals(listOf("样本很年轻", "作者警告不要下结论。"), out)
    }

    @Test
    fun `one chinese sentence supplies three english ones`() {
        val out = SentenceAlignment.align(
            listOf("One.", "Two.", "Three."),
            "第一，第二，第三。",
        )
        assertEquals(listOf("第一", "第二", "第三。"), out)
    }

    @Test
    fun `two chinese sentences supply three english ones`() {
        val out = SentenceAlignment.align(
            listOf(
                "The study was small.",
                "The sample was young.",
                "The authors warned against conclusions.",
            ),
            "这项研究规模很小。样本很年轻，作者警告不要下结论。",
        )
        assertEquals(
            listOf("这项研究规模很小。", "样本很年轻", "作者警告不要下结论。"),
            out,
        )
    }

    @Test
    fun `two chinese sentences supply one english one`() {
        // The translator broke one English sentence in two. Both pieces belong under
        // that sentence, so they are joined rather than shown apart.
        val out = SentenceAlignment.align(
            listOf("He said the study was small and left.", "She stayed behind."),
            "他说这项研究规模很小。然后他离开了。她留在了后面。",
        )
        assertEquals(listOf("他说这项研究规模很小。然后他离开了。", "她留在了后面。"), out)
    }

    @Test
    fun `the comma left by a cut inside a sentence is dropped`() {
        // `样本很年轻，` cut before its continuation would end a line with a comma that
        // separates nothing; the mark belongs to the sentence, not to the piece.
        val out = SentenceAlignment.align(
            listOf("The sample was young.", "The authors warned against conclusions."),
            "样本很年轻，作者警告不要下结论。",
        )
        assertEquals("样本很年轻", out?.first())
    }

    @Test
    fun `a cut inside a sentence is preferred at a full stop when one is available`() {
        // Two sentences' worth of Chinese, and the boundary between them is a full
        // stop: the fit must use it rather than splitting at the comma inside the
        // first sentence.
        val out = SentenceAlignment.align(
            listOf("Alpha beta.", "Gamma."),
            "阿尔法，贝塔。伽马。",
        )
        assertEquals(listOf("阿尔法，贝塔。", "伽马。"), out)
    }

    // ------------------------------------------------------------- what is refused

    @Test
    fun `returns null when the translation carries no boundary to cut at`() {
        // Three English sentences, one unpunctuated Chinese clause: there is nowhere to
        // put a boundary, so no pairing can be shown.
        assertNull(
            SentenceAlignment.align(
                listOf("First.", "Second.", "Third."),
                "第一第二第三。",
            )
        )
    }

    @Test
    fun `returns null when the shares do not match the english at all`() {
        // One English sentence carries the paragraph, but the Chinese spreads evenly
        // over three clauses: the fit is nonsense, so the paragraph is shown whole.
        assertNull(
            SentenceAlignment.align(
                listOf(
                    "Short.",
                    "Also short.",
                    "This third English sentence is by far the longest of the three and " +
                        "carries nearly all of the content of the whole paragraph.",
                ),
                "短，也短，第三个句子。",
            )
        )
    }

    @Test
    fun `a translation with fewer clauses than sentences is refused`() {
        assertNull(SentenceAlignment.align(listOf("One.", "Two.", "Three."), "第一第二第三。"))
    }

    // --------------------------------------------------------------- real novel prose

    @Test
    fun `a paragraph whose translator moved the attribution still lines up`() {
        // Straight from a Project Gutenberg conversion: the English is three sentences
        // and the Chinese is two, because the translator folded the second and third
        // together. The fit has to give the second English sentence two clauses and the
        // third the rest.
        val out = SentenceAlignment.align(
            listOf(
                "“I do not believe Mrs. Long will do any such thing.",
                "She has two nieces of her own.",
                "She is a selfish, hypocritical woman, and I have no opinion of her.”",
            ),
            "“我不相信龙太太会做出这样的事。她自己有两个侄女，她是一个自私、虚伪的女人，我对她没有意见。”",
        )
        assertNotNull(out)
        assertEquals(3, out?.size)
        assertTrue(out!![0].startsWith("“我不相信"))
        assertTrue(out[1].startsWith("她自己有两个侄女"))
        assertTrue(out[2].contains("自私"))
    }

    @Test
    fun `a sentence folded into its neighbour keeps its own piece`() {
        val out = SentenceAlignment.align(
            listOf(
                "Mrs. Bennet was quite disconcerted.",
                "She could not imagine what business he could have in town so soon after " +
                    "his arrival in Hertfordshire.",
                "Lady Lucas quieted her fears a little by starting the idea of his being " +
                    "gone to London only to get a large party for the ball.",
            ),
            "班纳特夫人感到非常不安。她无法想象他刚抵达赫特福德郡这么快就到城里来做什么；" +
                "她开始担心他可能总是从一个地方飞到另一个地方。卢卡斯夫人开始了他的想法，稍稍平息了她的恐惧。",
        )
        assertNotNull(out)
        assertEquals("班纳特夫人感到非常不安。", out?.first())
        assertTrue(out!!.last().startsWith("卢卡斯夫人"))
    }
}
