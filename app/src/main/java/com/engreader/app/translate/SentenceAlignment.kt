package com.engreader.app.translate

/**
 * Splits a paragraph's Chinese translation into one piece per English sentence.
 *
 * The translation is fetched a paragraph at a time on purpose: one request per
 * paragraph keeps the request count down, and handing the engine the whole paragraph
 * as context reads better than sending sentences one by one. The reader then wants
 * the result per sentence, so the Chinese can sit directly under the English it
 * belongs to — and the two have to line up exactly, because a piece shown under the
 * wrong sentence is worse than no pairing at all.
 *
 * The split is therefore only accepted when it yields exactly as many Chinese
 * sentences as the English has. Anything else returns null and the caller shows the
 * paragraph and its translation whole, as it did before.
 */
object SentenceAlignment {

    /**
     * Marks that end a sentence in the translated text.
     *
     * `，` and `；` are deliberately absent: they separate clauses inside one Chinese
     * sentence, so splitting on them would produce more pieces than the English has.
     * The ASCII `!` and `?` are kept because a translation occasionally carries them
     * over from the source; `.` is not, because it also appears in decimals and
     * abbreviations.
     */
    private const val TERMINATORS = "\u3002\uFF01\uFF1F\u2026!?"

    /** Marks that close the sentence they follow rather than opening the next one. */
    private const val CLOSERS = "\u300D\u300F\u201D\u2019\uFF09)\u3011\u300B]"

    /**
     * One Chinese piece per entry in [englishSentences], or null when the translation
     * does not split into that many sentences.
     */
    fun align(englishSentences: List<String>, chinese: String): List<String>? {
        if (englishSentences.isEmpty()) return null
        val text = chinese.trim()
        if (text.isEmpty()) return null
        // A one-sentence paragraph has nothing to line up with, and a translation that
        // dropped its final full stop would otherwise be rejected for no reason.
        if (englishSentences.size == 1) return listOf(text)
        return splitSentences(text).takeIf { it.size == englishSentences.size }
    }

    private fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || TERMINATORS.indexOf(c) >= 0) {
                var end = i + 1
                // A run like "……" or "？！" closes one sentence, not two.
                while (end < text.length && TERMINATORS.indexOf(text[end]) >= 0) end++
                while (end < text.length && CLOSERS.indexOf(text[end]) >= 0) end++
                push(out, text, start, end)
                start = end
                i = end
                continue
            }
            i++
        }
        push(out, text, start, text.length)
        return out
    }

    private fun push(out: MutableList<String>, source: String, start: Int, end: Int) {
        val piece = source.substring(start, end).trim()
        if (piece.isNotEmpty()) out += piece
    }
}
