package com.engreader.app.nlp

/** A sentence plus its character range inside the owning paragraph. */
data class Sentence(
    val text: String,
    val start: Int,
    val end: Int,
    val wordCount: Int,
)

/**
 * Splits a paragraph into sentences.
 *
 * A plain "split on period" breaks on abbreviations, decimals and initials, so a
 * terminator only ends a sentence when it is followed by whitespace and the next
 * word does not continue an abbreviation. Quotation marks are carried with the
 * sentence they close, and a lowercase word after the terminator — with or without a
 * closing quote in between — continues the sentence rather than starting a new one.
 */
object Sentences {

    /** Never end a sentence: titles, months, and forms that only exist dotted. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc",
        "eg", "ie", "fig", "inc", "ltd", "gov", "sen", "rep", "gen", "col",
        "capt", "lt", "sgt", "rev", "hon", "pres", "supt", "dept", "approx",
        "avg", "vol", "pp", "eds", "op", "ed", "al",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct",
        "nov", "dec", "mon", "tue", "tues", "wed", "thu", "thur", "thurs",
        "fri", "sat", "sun", "ad", "bc", "ce", "bce",
    )

    /**
     * Words that are ordinary English *and* an abbreviation.
     *
     * `no`, `us`, `am`, `pm`, `min`, `max`, `est`, `co`, `uk` and friends really do
     * end sentences ("He said no."), so they cannot sit in [ABBREVIATIONS]: that
     * silently merged whole paragraphs into one "sentence". They only suppress the
     * boundary when the next word is lowercase or a number, which is the shape of a
     * continued phrase ("the US government", "no. 5") rather than of a new sentence.
     */
    private val AMBIGUOUS = setOf(
        "no", "us", "usa", "uk", "eu", "un", "am", "pm", "min", "max", "est",
        "co", "who", "bbc", "cnn", "nato",
    )

    /**
     * Abbreviations that are also ordinary words a sentence can end on.
     *
     * `etc.` and `Inc.` are in [ABBREVIATIONS], which suppresses the boundary
     * unconditionally, so "Bring pens, etc. She had none." came back as one sentence.
     * They only suppress it when the next word continues the phrase.
     */
    private val SENTENCE_FINAL = setOf("etc", "inc", "ltd", "co", "vs", "al")

    fun split(paragraph: String): List<Sentence> {
        if (paragraph.isBlank()) return emptyList()
        val out = mutableListOf<Sentence>()
        var start = 0
        var i = 0
        while (i < paragraph.length) {
            val c = paragraph[i]
            if (c == '.' || c == '!' || c == '?' || c == '\u2026' ||
                c == '\u3002' || c == '\uFF01' || c == '\uFF1F'
            ) {
                // Consume runs like "?!" or "..." as one terminator.
                var j = i
                while (j + 1 < paragraph.length && isTerminator(paragraph[j + 1])) j++
                val after = j + 1
                if (isBoundary(paragraph, i, after)) {
                    // Pull trailing closing quotes/brackets into this sentence.
                    var end = after
                    while (end < paragraph.length && isCloser(paragraph[end])) end++
                    push(out, paragraph, start, end)
                    start = end
                    i = end
                    continue
                }
                i = after
                continue
            }
            i++
        }
        if (start < paragraph.length) push(out, paragraph, start, paragraph.length)
        return out
    }

    private fun push(out: MutableList<Sentence>, source: String, start: Int, end: Int) {
        val raw = source.substring(start, end)
        val lead = raw.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return
        val text = raw.trim()
        if (text.isEmpty()) return
        val realStart = start + lead
        out += Sentence(
            text = text,
            start = realStart,
            end = realStart + text.length,
            wordCount = Tokenizer.countWords(text),
        )
    }

    private fun isTerminator(c: Char) = c == '.' || c == '!' || c == '?' || c == '\u2026'

    private fun isCloser(c: Char) = c == '"' || c == '\'' || c == '\u201D' || c == '\u2019' ||
        c == ')' || c == ']' || c == '\u300D' || c == '\u300F'

    private fun isBoundary(s: String, terminator: Int, after: Int): Boolean {
        // Must be followed by whitespace, or be the end of the paragraph.
        if (after >= s.length) return true
        if (!s[after].isWhitespace()) {
            // Allow `."` / `.)` before the whitespace.
            if (!isCloser(s[after])) return false
            val next = after + 1
            if (next >= s.length) return true
            if (!s[next].isWhitespace()) return false
            // A closing quote ends the sentence only when a new one follows it.
            // `?" cried his wife` and `!" she said` are dialogue tags: breaking there
            // hands the tag to the speaker as a sentence of its own, so the listener
            // hears a full stop in the middle of what is one utterance.
            return !continuesPhrase(s, next)
        }
        if (s[terminator] != '.') {
            // `!`, `?` and `…` end a sentence far more often than not, but a lowercase
            // word after them still continues the same one — `"How so? how can it
            // affect them?"` is one question, not a question and a fragment.
            return !continuesPhrase(s, after)
        }

        val word = wordBefore(s, terminator)
        // `A.` is an initial in "J. Smith" but a one-letter word in "The grade is A."
        // Suppressing the boundary for any single capital merged the second sentence
        // into the first. What separates them is what comes before: an initial follows
        // the start of the sentence or another capitalised word (a first name, a
        // title), never a lowercase word.
        if (word.length == 1 && word[0].isUpperCase()) return !followsCapital(s, terminator)
        // Dotted abbreviations ("p.m.", "U.S.") only match once the dots are removed.
        val bare = word.replace(".", "").lowercase()
        // `etc.` and `Inc.` also end sentences ("pens, etc. She left."), so they are
        // tested before the unconditional list: they only suppress the boundary when
        // what follows continues the same sentence.
        if (bare in SENTENCE_FINAL) return !continuesPhrase(s, after)
        if (bare in ABBREVIATIONS) return false
        if (bare in AMBIGUOUS && continuesPhrase(s, after)) return false
        // No decimal test here: `3.5` never reaches this point. A terminator only gets
        // this far when what follows it is whitespace (or a closer and then whitespace),
        // and the digit of a decimal is neither, so the check that used to sit here could
        // not be true. The doc above still holds, by way of that whitespace requirement.
        return true
    }

    /**
     * True when the word after the period reads as a continuation rather than as the
     * start of a new sentence: lowercase ("no. 5", "the US government") or a number.
     */
    private fun continuesPhrase(s: String, after: Int): Boolean {
        var i = after
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return false
        val c = s[i]
        if (c.isDigit()) return true
        // A leading quote or bracket belongs to the next word, not to the sentence.
        if (isCloser(c) || c == '(' || c == '\u201C' || c == '\u2018') {
            var j = i + 1
            while (j < s.length && (s[j].isWhitespace() || isCloser(s[j]))) j++
            return j < s.length && (s[j].isLowerCase() || s[j].isDigit())
        }
        return c.isLowerCase()
    }

    private fun wordBefore(s: String, index: Int): String {
        var i = index - 1
        while (i >= 0 && (s[i].isLetter() || s[i] == '.')) i--
        return s.substring(i + 1, index)
    }

    /**
     * True when the one-letter word ending at [terminator] opens its sentence, which
     * is what makes it an initial or a list label rather than a sentence's last word.
     *
     * `J. Smith` and `I. Introduction` open their sentences, and `A.`/`B.` after
     * another sentence's full stop is a label too, so in all of those the dot does not
     * end anything. The `A.` of `The grade is A. She was pleased.` has an ordinary word
     * in front of it, so its dot really does end the sentence.
     *
     * The previous version only recognised the paragraph-initial case (`i < 0`), so the
     * same `B.` split off as a one-word sentence of its own whenever a sentence happened
     * to end before it: `A. That is it. B. This too.` came back as three sentences, with
     * `B.` alone and `A.` still joined to its text.
     */
    private fun followsCapital(s: String, terminator: Int): Boolean {
        var i = terminator - 2
        while (i >= 0 && s[i].isWhitespace()) i--
        if (i < 0) return true
        // A terminator immediately before the letter — including the dot of a previous
        // initial (`Dr. J. Smith`) — means the letter opens what follows.
        if (s[i] == '.' || s[i] == '!' || s[i] == '?' || s[i] == '\u2026' ||
            s[i] == ';' || s[i] == ':'
        ) return true
        val end = i + 1
        while (i >= 0 && (s[i].isLetter() || s[i] == '\'' || s[i] == '\u2019' || s[i] == '-')) i--
        val before = s.substring(i + 1, end)
        return before.isEmpty() || before.first().isUpperCase()
    }
}
