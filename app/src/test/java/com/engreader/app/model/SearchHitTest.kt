package com.engreader.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snippet a search result shows: a window around the match, not the paragraph's head. */
class SearchHitTest {

    private fun hit(text: String) = SearchHit(
        articleId = 1,
        chapterIndex = 0,
        chapterTitle = "Chapter",
        paragraphIndex = 0,
        text = text,
    )

    @Test
    fun `a short paragraph is shown whole`() {
        assertEquals("The cat sat.", hit("The cat sat.").snippet("cat"))
    }

    @Test
    fun `a long paragraph is cut around the match`() {
        val text = "a".repeat(200) + " needle " + "b".repeat(200)
        val snippet = hit(text).snippet("needle", radius = 10)
        assertTrue(snippet.contains("needle"))
        // Ten characters either side of the six-character match, plus two ellipses.
        assertEquals(28, snippet.length)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `no ellipsis is added where the paragraph ends`() {
        val text = "needle at the very start" + "z".repeat(200)
        val snippet = hit(text).snippet("needle", radius = 10)
        assertTrue(snippet.startsWith("needle"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `matching is case-insensitive, like the search`() {
        val text = "x".repeat(100) + " Cambridge " + "y".repeat(100)
        assertTrue(hit(text).snippet("cambridge", radius = 5).contains("Cambridge"))
    }

    @Test
    fun `a query that is not in the text falls back to the opening`() {
        // The snippet is only ever asked for a hit, but a fallback beats an exception.
        val text = "Opening words here. " + "z".repeat(300)
        val snippet = hit(text).snippet("absent", radius = 40)
        assertTrue(snippet.startsWith("Opening words here."))
    }

    @Test
    fun `leading whitespace is not counted as context`() {
        val text = "\n\n   The answer is here."
        assertEquals("The answer is here.", hit(text).snippet("answer"))
    }
}
