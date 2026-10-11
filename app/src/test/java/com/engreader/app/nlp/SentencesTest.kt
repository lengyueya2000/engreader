package com.engreader.app.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {

    @Test
    fun `splits on terminal punctuation followed by whitespace`() {
        val out = Sentences.split("The train was late. We arrived at dusk. Nobody spoke!")
        assertEquals(3, out.size)
        assertEquals("The train was late.", out[0].text)
        assertEquals("Nobody spoke!", out[2].text)
    }

    @Test
    fun `keeps abbreviations inside one sentence`() {
        val out = Sentences.split("Mr. Smith met Dr. Jones at 5 p.m. on Tuesday.")
        assertEquals(1, out.size)
    }

    @Test
    fun `does not split a decimal number`() {
        val out = Sentences.split("The rate rose to 3.5 per cent last month.")
        assertEquals(1, out.size)
    }

    @Test
    fun `does not split on a single-letter initial`() {
        val out = Sentences.split("J. R. R. Tolkien wrote the books.")
        assertEquals(1, out.size)
    }

    @Test
    fun `carries a closing quote into the sentence it ends`() {
        val out = Sentences.split("\"I will not go.\" She left the room.")
        assertEquals(2, out.size)
        assertEquals("\"I will not go.\"", out[0].text)
    }

    @Test
    fun `treats a run of terminators as one boundary`() {
        val out = Sentences.split("Why would he do that?! Nobody knows.")
        assertEquals(2, out.size)
        assertEquals("Why would he do that?!", out[0].text)
    }

    @Test
    fun `offsets point back into the original paragraph`() {
        val paragraph = "First one here. Second one there."
        val out = Sentences.split(paragraph)
        out.forEach { sentence ->
            assertEquals(sentence.text, paragraph.substring(sentence.start, sentence.end))
        }
    }

    @Test
    fun `counts words per sentence`() {
        val out = Sentences.split("One two three. Four five.")
        assertEquals(3, out[0].wordCount)
        assertEquals(2, out[1].wordCount)
    }

    @Test
    fun `blank input yields nothing`() {
        assertTrue(Sentences.split("   ").isEmpty())
    }
}

class ParagraphsTest {

    @Test
    fun `splits on blank lines and trims`() {
        val out = Paragraphs.split("One line.\n\nAnother para.\n\n  Third.  ")
        assertEquals(3, out.size)
        assertEquals("Third.", out[2].text)
    }

    @Test
    fun `token offsets map back to the source text`() {
        val paragraph = Paragraphs.split("Kenya reported its first-ever Ebola death.").single()
        paragraph.tokens.forEach { token ->
            assertEquals(token.text, paragraph.text.substring(token.start, token.end))
        }
    }

    @Test
    fun `wordAt returns the word under an offset and null between words`() {
        val paragraph = Paragraphs.split("Hello world").single()
        assertEquals("Hello", Paragraphs.wordAt(paragraph, 2))
        assertEquals("world", Paragraphs.wordAt(paragraph, 7))
        assertNull(Paragraphs.wordAt(paragraph, 5))
    }

    @Test
    fun `hyphenated and apostrophe words stay one token`() {
        val paragraph = Paragraphs.split("A first-ever decision didn't help.").single()
        val texts = paragraph.tokens.map { it.text }
        assertTrue(texts.contains("first-ever"))
        assertTrue(texts.contains("didn't"))
    }

    /**
     * The text-only path exists so a book search does not build word spans it never
     * reads. A search hit reports a paragraph *index*, and the reader and the
     * translation are indexed the same way, so the two splits have to agree paragraph
     * for paragraph — not merely in length.
     */
    @Test
    fun `the text-only split agrees with the tokenizing one`() {
        val body = "One line.\n\nAnother para.\n\n  Third.  \n\n\n\nFourth, with\n" +
            "a hard wrap inside it.\n\n   \n\nFifth."
        assertEquals(
            Paragraphs.split(body).map { it.text },
            Paragraphs.texts(body),
        )
    }

    @Test
    fun `the text-only split trims, drops blanks and breaks on a single newline`() {
        // A lone newline is a boundary, the same as a blank line. Book text never
        // relies on that: BookText flattens source hard wraps before storing, so a
        // paragraph that was wrapped at 72 columns arrives here as one line.
        val texts = Paragraphs.texts("  First.  \n\n\n\nSecond,\nwrapped.\n\n   \n\nThird.")
        assertEquals(listOf("First.", "Second,", "wrapped.", "Third."), texts)
    }

    @Test
    fun `the text-only split of a blank body is empty`() {
        assertTrue(Paragraphs.texts("").isEmpty())
        assertTrue(Paragraphs.texts("\n\n  \n\n").isEmpty())
    }
}

class TokenizerTest {

    @Test
    fun `normalize strips punctuation and possessive endings`() {
        assertEquals("government", Tokenizer.normalize("government,"))
        assertEquals("minister", Tokenizer.normalize("\"Minister's"))
        assertEquals("don't", Tokenizer.normalize("Don't"))
    }

    @Test
    fun `syllables handles silent e and short words`() {
        assertEquals(1, Tokenizer.syllables("cat"))
        assertEquals(2, Tokenizer.syllables("table"))
        assertEquals(3, Tokenizer.syllables("family"))
        assertEquals(1, Tokenizer.syllables("make"))
    }
}
