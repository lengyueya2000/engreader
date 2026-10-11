package com.engreader.app.nlp

/** A single article paragraph, pre-split so the reader never re-tokenizes on scroll. */
data class Paragraph(
    val text: String,
    /** Word ranges as `[start, end)` offsets into [text], used for tap-to-look-up. */
    val tokens: List<WordSpan>,
)

data class WordSpan(val start: Int, val end: Int, val text: String)

/**
 * Splits prose into paragraphs and words. Kept deliberately simple: the reader
 * only needs stable offsets into the raw string so a tap can be mapped back to a
 * word, and the offsets must survive being passed through Compose's text layout.
 */
object Paragraphs {

    /**
     * The same word class the tokenizer uses.
     *
     * A private ASCII-only copy lived here, so the reader split `café` into `caf` and
     * a tap on the accented character landed outside every span. Sharing one pattern
     * is what keeps the offsets a tap is matched against identical to the ones the
     * word count is computed from.
     */
    private val WORD = Tokenizer.WORD

    /**
     * Where one paragraph ends and the next begins.
     *
     * Compiled once: the search path runs this over every chapter of a book on each
     * query, and building the pattern per call was pure overhead.
     */
    private val BREAK = Regex("\n\\s*\n|\\n")

    /**
     * The paragraph texts of [body], without tokenizing them.
     *
     * Searching a book only needs each paragraph's text, and building the word spans
     * for a whole book costs several times what the match itself does. [split] is
     * defined in terms of this, so the two can never disagree about where a paragraph
     * starts — which is what keeps the paragraph index a search hit reports pointing
     * at the same paragraph the reader and the translation are indexed by.
     */
    fun texts(body: String): List<String> =
        body.split(BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun split(body: String): List<Paragraph> =
        texts(body).map { text ->
            Paragraph(
                text = text,
                tokens = WORD.findAll(text).map {
                    WordSpan(it.range.first, it.range.last + 1, it.value)
                }.toList(),
            )
        }

    /**
     * Maps a character offset from a click into the word covering it. Returns null
     * when the tap landed on whitespace or punctuation, so taps between words do
     * nothing instead of guessing.
     */
    fun wordAt(paragraph: Paragraph, offset: Int): String? =
        paragraph.tokens.firstOrNull { offset in it.start until it.end }?.text
}
