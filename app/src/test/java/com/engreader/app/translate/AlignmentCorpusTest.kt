package com.engreader.app.translate

import com.engreader.app.nlp.Sentences
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the aligner over seventy paragraphs of a real novel with the translations the
 * live engine actually returned.
 *
 * The corpus is the point: a hand-written pair can be made to pass by tuning to it,
 * whereas this is the prose and the machine output the feature meets in the field.
 * It was captured from Project Gutenberg's *Pride and Prejudice* — plain paragraphs
 * and dialogue, quotes and dashes, abbreviations and semicolons — with the same
 * endpoint the app calls, so a change in how the aligner behaves shows up here as a
 * drop in the share of paragraphs that can be shown sentence by sentence.
 */
class AlignmentCorpusTest {

    private fun corpus(): List<Pair<List<String>, String>> {
        val stream = javaClass.getResourceAsStream("/translate/alignment_corpus.tsv")
            ?: error("alignment corpus is missing from test resources")
        return stream.bufferedReader().readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val english = Sentences.split(line.substring(0, tab)).map { it.text }
            if (english.size < 2) return@mapNotNull null
            english to line.substring(tab + 1).trim()
        }
    }

    @Test
    fun `almost every paragraph of a real novel can be shown sentence by sentence`() {
        val cases = corpus()
        assertTrue("corpus should hold dozens of paragraphs", cases.size >= 60)
        val aligned = cases.count { SentenceAlignment.align(it.first, it.second) != null }
        val rate = 100 * aligned / cases.size
        // The exact-count split alone manages about three in five on this corpus; the
        // proportional fit is what takes it near the whole of it.
        assertTrue(
            "aligned only $aligned of ${cases.size} paragraphs ($rate%)",
            rate >= 90,
        )
        // Guard against the test passing because the corpus happens to line up on its
        // own: a good share of these paragraphs must need the proportional fit, which
        // is the code this test exists to protect.
        val mismatched = cases.count { (english, chinese) ->
            chinese.count { it in "\u3002\uFF01\uFF1F\u2026!?" } != english.size
        }
        assertTrue(
            "only $mismatched of ${cases.size} paragraphs exercise the proportional fit",
            mismatched >= 15,
        )
    }

    @Test
    fun `a paragraph that aligns is cut at the sentence boundaries`() {
        val cases = corpus()
        // Spot-check the shape of the result rather than the exact wording: each piece
        // must be non-empty, and together they must account for the translation.
        val checked = cases.count { (english, chinese) ->
            val pieces = SentenceAlignment.align(english, chinese) ?: return@count false
            if (pieces.size != english.size) return@count false
            if (pieces.any { it.isBlank() }) return@count false
            val joined = pieces.joinToString("").filterNot { it.isWhitespace() }
            val source = chinese.filterNot { it.isWhitespace() }
            // A cut at a comma drops the mark, so the pieces are a subsequence of the
            // translation rather than equal to it.
            joined.length >= source.length * 9 / 10
        }
        assertTrue(
            "only $checked of ${cases.size} aligned paragraphs produced usable pieces",
            checked >= cases.size * 9 / 10,
        )
    }

    @Test
    fun `the fit does not cut a chinese sentence up to save a hair of deviation`() {
        // Every piece ending on anything but a terminator means a cut landed inside a
        // Chinese sentence, overriding the boundary the translator chose. Some of those
        // are needed — one Chinese sentence really can carry two English ones — but the
        // cost of a cut is what keeps the fit from doing it for a hundredth of a share
        // deviation, which showed up as `两周的熟识肯定是很少的。两周结束后` sitting
        // under one English sentence. Lowering SPLIT_PENALTY took this from 7 to 23.
        val terminators = "\u3002\uFF01\uFF1F\u2026!?"
        val closers = "\u300D\u300F\u201D\u2019\uFF09)\u3011\u300B]"
        val cuts = corpus().sumOf { (english, chinese) ->
            SentenceAlignment.align(english, chinese)?.count { piece ->
                var end = piece.length
                while (end > 0 && closers.indexOf(piece[end - 1]) >= 0) end--
                end > 0 && terminators.indexOf(piece[end - 1]) < 0
            } ?: 0
        }
        assertTrue("the fit cut inside a Chinese sentence $cuts times", cuts <= 12)
    }
}
