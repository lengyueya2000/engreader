package com.engreader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewrite against the app's own eight passages.
 *
 * The unit tests above pin individual shapes. This one is the guard against a rewrite
 * that is merely wrong somewhere new: it runs every paragraph of the shipped corpus
 * through [SpokenText.of] and checks the two things a bad rule breaks first — a word of
 * the book goes missing, or a paragraph that needs no rewriting comes out changed.
 *
 * A corpus rather than more hand-written cases because the shapes that matter here are
 * the ones that only occur in real prose: a Gutenberg copyright line nested inside an
 * illustration note, a year inside a parenthesis, a `Mr.` at the end of a line.
 */
class SpokenCorpusTest {

    private val paragraphs: List<String> = javaClass.getResourceAsStream("/tts/spoken_corpus.txt")
        ?.bufferedReader()
        ?.readLines()
        ?.filter { it.isNotBlank() }
        ?: error("spoken_corpus.txt is missing from the test resources")

    @Test
    fun `the corpus is the whole shipped collection`() {
        assertEquals(58, paragraphs.size)
    }

    @Test
    fun `no paragraph loses a word of the book`() {
        // Only apparatus may be dropped, and apparatus is bracketed. Every other word of
        // four or more letters has to survive into what the voice is handed, or the
        // rewrite is deleting prose.
        val lost = mutableListOf<String>()
        for (paragraph in paragraphs) {
            val spoken = SpokenText.of(paragraph)
            val kept = spoken.lowercase()
            for (word in CONTENT_WORD.findAll(stripBrackets(paragraph)).map { it.value.lowercase() }) {
                if (!kept.contains(word)) lost += "$word  <-  ${paragraph.take(70)}"
            }
        }
        assertTrue("words dropped by the rewrite:\n${lost.joinToString("\n")}", lost.isEmpty())
    }

    @Test
    fun `only a minority of paragraphs are rewritten at all`() {
        // The rules are narrow on purpose: a year after a date word, a heading numeral,
        // a bracket that is apparatus. A change that suddenly rewrites most of the
        // corpus is a rule that has grown teeth it should not have.
        val changed = paragraphs.count { SpokenText.of(it) != it }
        assertTrue("$changed of ${paragraphs.size} paragraphs rewritten", changed <= 6)
    }

    @Test
    fun `the paragraphs that change are the expected ones`() {
        // Five, and every one of them for a reason that can be named: the Gutenberg
        // illustration note, the dotted `P. M.` of the journal entry, and three
        // nineteenth-century years. A sixth would mean a rule has started firing on
        // prose that does not need it.
        val changed = paragraphs.filter { SpokenText.of(it) != it }
        assertEquals(
            changed.joinToString("\n") { "  ${it.take(60)}" },
            5,
            changed.size,
        )
    }

    @Test
    fun `the illustration note of the Gutenberg edition is spoken as nothing`() {
        val paragraph = paragraphs.first { it.contains("Illustration") }
        val spoken = SpokenText.of(paragraph)
        assertTrue(spoken, !spoken.contains("Illustration"))
        assertTrue(spoken, !spoken.contains("Copyright"))
        assertTrue(spoken, !spoken.contains("["))
        assertTrue(spoken, spoken.endsWith("no objection to hearing it.\u201D"))
    }

    @Test
    fun `the journal entry loses its dotted meridiem but keeps its clock time`() {
        val paragraph = paragraphs.first { it.contains("8:35") }
        val spoken = SpokenText.of(paragraph)
        assertTrue(spoken, spoken.contains("8:35"))
        assertTrue(spoken, !spoken.contains("P. M."))
        assertTrue(spoken, spoken.contains("p m"))
    }

    @Test
    fun `a paragraph of plain prose comes out byte for byte`() {
        val plain = paragraphs.first { it.startsWith("It is a truth") }
        assertEquals(plain, SpokenText.of(plain))
    }

    private fun stripBrackets(text: String): String {
        val out = StringBuilder(text.length)
        var depth = 0
        for (c in text) {
            when (c) {
                '[' -> depth++
                ']' -> if (depth > 0) depth--
                else -> if (depth == 0) out.append(c)
            }
        }
        return out.toString()
    }

    private companion object {
        /** Long enough that a hit is prose rather than a function word. */
        val CONTENT_WORD = Regex("[A-Za-z]{4,}")
    }
}
