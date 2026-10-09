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

    private val WORD = Regex("[A-Za-z][A-Za-z'\\u2019-]*")

    fun split(body: String): List<Paragraph> =
        body.split(Regex("\n\\s*\n|\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { text ->
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
