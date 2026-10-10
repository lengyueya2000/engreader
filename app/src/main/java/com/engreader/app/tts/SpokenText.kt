package com.engreader.app.tts

/**
 * Rewrites written prose into the words a native speaker would actually say.
 *
 * The bundled voice phonemises whatever it is handed, and espeak-ng's own number and
 * symbol rules are those of a general-purpose reader rather than of a person reading a
 * novel aloud. Measured on the bundled British voice, four shapes come out wrong in a
 * way a listener notices at once:
 *
 * - `1837` is spoken as the cardinal "one thousand eight hundred and thirty-seven".
 *   In a carrier sentence the written form runs 6.85 s of audio against 5.22 s for the
 *   spoken form. Every four-digit year in a book is read out as a quantity, which is
 *   the single clearest tell that a machine is talking.
 * - `CHAPTER VI.` comes back as "chapter vee eye". espeak treats a lone `V` as the
 *   letter, and where it does recognise a numeral it inserts the literal word "Roman" —
 *   `II` is spoken as "Roman two", which is worse than the letter.
 * - `8:35 P. M.` is spoken as "eight thirty-five p, M": the initial glues itself to the
 *   number and the second letter is read as a word.
 * - Markup is read out. `it was **very** important and _quite_ true` is 5.17 s of audio
 *   against 2.69 s without the markers — the voice says "asterisk" for each one. A
 *   Project Gutenberg `[Illustration: ...]` note is worse: inside a sentence it adds
 *   6.28 s, and on a line of its own it is a 7.2 s sentence of the book.
 *
 * What is deliberately *not* here is the connected speech itself. Linking and weak forms
 * already come out right and rewriting them would be fixing something that is not
 * broken: in a matched carrier `to` is reduced rather than spelled out (8 runs, 1.101 s
 * against 1.260 s for `two`, standard deviation 0.03 either way), and a phrase carries no
 * measurable pause at a word boundary where there is no punctuation — per-word timings
 * over "It is a truth universally acknowledged, that a single man" show a 0.000 s gap
 * between every pair of words inside a phrase and the only pause in the sentence at the
 * comma. Contractions, `Mr.`/`Mrs.`/`Dr.`/`St.`/`No.` and `8:35` are likewise already
 * correct and are left alone.
 *
 * Every number above, and every number below, comes from `tools/voice/say.py`, which
 * synthesises with this same bundled voice and transcribes the audio back.
 *
 * The output of [of] goes to the voice only. The reader still shows the original text,
 * so nothing here can move a word out from under a tap.
 */
object SpokenText {

    /**
     * The spoken form of [text].
     *
     * Order matters. Bracketed notes go first so that a year inside one — the copyright
     * line of a Gutenberg edition is `[Copyright 1894 by George Allen.]` — is judged on
     * the sentence that is left. Decades go before years because `1890s` would otherwise
     * be seen as the number 1890 followed by a letter.
     *
     * The result may be empty, and an empty result is the answer for a sentence that is
     * nothing but apparatus: a Gutenberg edition puts `[Illustration: ...]` on a line of
     * its own, and the sentence splitter hands it over as its own sentence. The voice
     * treats blank text as a sentence that finishes immediately, so the reader skips it
     * and moves on. Falling back to the original here would read the whole note aloud,
     * which is the defect this rewrite exists to remove — measured on the bundled voice,
     * the note is 7.2 s of audio that no listener wants.
     */
    fun of(text: String): String {
        if (text.isBlank()) return text
        return tidySpacing(
            spellMeridiem(
                spellRomanNumerals(
                    spellDecades(
                        spellYears(
                            dropEmphasisMarks(dropEditorialNotes(text))
                        )
                    )
                )
            )
        )
    }

    // ------------------------------------------------------------ editorial notes

    /**
     * Labels that mark apparatus rather than prose.
     *
     * Only labels that are not themselves ordinary words are listed. `[Table]` and
     * `[Figure]` are things a writer puts in a sentence, and dropping them would leave a
     * hole where a word was; the apparatus forms of those always carry a number or a
     * colon — `[Figure 2]`, `[Table: ...]` — and are caught by the punctuation rule in
     * [isEditorialNote] instead.
     */
    private val EDITORIAL = setOf(
        "illustration", "footnote", "sidenote", "caption", "transcriber", "copyright",
        "frontispiece", "endnote", "marginalia", "advertisement",
    )

    /**
     * Drops `[Illustration: ...]` and `[12]`.
     *
     * Bracket depth is counted rather than matched with a regular expression: a real
     * Gutenberg note nests a copyright line inside the illustration note, and a pattern
     * that stops at the first `]` leaves a stray bracket behind, which the voice then
     * reads as a word. A bracketed *number* is a citation marker and is dropped for the
     * same reason — "She waited [1] and then left" should not say "one".
     *
     * A label has to be the whole bracket or be followed by a colon for the bracket to
     * count as apparatus. `[sic]` and `[table]` are words a reader would say, and
     * dropping them leaves a sentence missing a word where it stood.
     */
    private fun dropEditorialNotes(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] != '[') {
                out.append(text[i])
                i++
                continue
            }
            val close = matchingBracket(text, i)
            if (close < 0) {
                out.append(text[i])
                i++
                continue
            }
            if (isEditorialNote(text.substring(i + 1, close))) {
                i = close + 1
                continue
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun isEditorialNote(inner: String): Boolean {
        val trimmed = inner.trim()
        if (trimmed.toIntOrNull() != null) return true
        val head = trimmed.takeWhile { it.isLetter() }.lowercase()
        if (head.isEmpty()) return false
        val rest = trimmed.drop(head.length)
        // A bare label, or a label introducing content: `[Illustration]`, `[Figure 2]`,
        // `[Footnote: ...]`. A bracket that is a phrase on its own — `[sic]`, or a
        // bracketed aside in a quotation — is read out, because it is part of the prose.
        return head in EDITORIAL && (rest.isBlank() || rest.first() == ':' || rest.first().isDigit())
    }

    /** Index of the `]` closing the `[` at [open], or -1 when it is never closed. */
    private fun matchingBracket(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    // ------------------------------------------------------------------ markup

    /**
     * Removes emphasis markers, keeping the words they mark.
     *
     * `_did_` is already spoken correctly by espeak, but `*did*` is not, and normalising
     * both means the two spellings of the same emphasis sound the same. Only a marker
     * that wraps a word is removed, so an underscore inside a word survives.
     */
    private fun dropEmphasisMarks(text: String): String {
        if (!text.contains('*') && !text.contains('_')) return text
        return text
            .replace(Regex("\\*{1,3}"), "")
            .replace(Regex("(?<![\\p{L}\\p{N}])_(?=\\S)|(?<=\\S)_(?![\\p{L}\\p{N}])"), "")
    }

    // ------------------------------------------------------------------- years

    /** A four-digit run, not part of a longer number and not followed by a letter. */
    private val FOUR_DIGITS = Regex("(?<![\\d.,])\\d{4}(?![\\d\\p{L}])")

    /**
     * Words that put a following four-digit number in a year frame.
     *
     * `1234 people` and `1837` are the same shape, and only the context tells them
     * apart. Reading every four-digit number as a year would turn quantities into dates;
     * reading none of them leaves every date as a cardinal, which is what the voice does
     * now. The list is the prepositions and nouns a date actually follows in prose, and
     * `copyright` is there for the Gutenberg line that survives [dropEditorialNotes].
     */
    private val YEAR_MARKERS = setOf(
        "in", "since", "until", "till", "by", "of", "from", "year", "years", "circa",
        "ca", "ad", "ce", "around", "about", "after", "before", "during", "throughout",
        "dated", "copyright", "pub", "published", "born", "died",
    )

    private fun spellYears(text: String): String {
        val matches = FOUR_DIGITS.findAll(text).toList()
        if (matches.isEmpty()) return text
        val out = StringBuilder(text.length + 32)
        var copied = 0
        matches.forEach { match ->
            val year = match.value.toInt()
            if (year !in 1000..2099 || !isYearContext(text, match.range)) return@forEach
            out.append(text, copied, match.range.first)
            out.append(yearWords(year))
            copied = match.range.last + 1
        }
        out.append(text, copied, text.length)
        return out.toString()
    }

    /**
     * True when a word that frames a date stands immediately before the number.
     *
     * A span like `1837-1901` is deliberately not treated as a date, even though the
     * first end follows `from`. The voice already reads a dash between two numbers as
     * "to" — `1837-1901` transcribes identically to "eighteen thirty-seven to nineteen
     * oh one" — so spelling one end and not the other would only make the two halves of
     * the span disagree.
     */
    private fun isYearContext(text: String, range: IntRange): Boolean {
        val before = text.substring(0, range.first).trimEnd()
        if (before.endsWith("\u00A9")) return true
        if (isSpanAdjacent(before, text.substring(range.last + 1))) return false
        val word = before.takeLastWhile { it.isLetter() || it == '.' }.trim('.').lowercase()
        return word in YEAR_MARKERS
    }

    /** True when the number sits at one end of `1837-1901` rather than standing alone. */
    private fun isSpanAdjacent(before: String, after: String): Boolean {
        val lead = before.lastOrNull()
        if (lead == '-' || lead == '\u2013' || lead == '\u2014') {
            val head = before.dropLast(1).trimEnd()
            if (head.length >= 4 && head.takeLast(4).all { it.isDigit() }) return true
        }
        val trimmed = after.trimStart()
        val dash = trimmed.firstOrNull() ?: return false
        if (dash != '-' && dash != '\u2013' && dash != '\u2014') return false
        val rest = trimmed.drop(1).trimStart()
        return rest.length >= 4 && rest.take(4).all { it.isDigit() }
    }

    /**
     * A year in the form a speaker uses, which depends on the century.
     *
     * `1066` is "ten sixty-six", `1837` "eighteen thirty-seven", `1805` "eighteen oh
     * five" and `1800` "eighteen hundred". From 2000 the pattern changes: 2000–2009 are
     * "two thousand (and five)" and 2010 onwards go back to pairs, "twenty ten".
     */
    private fun yearWords(year: Int): String {
        val high = year / 100
        val low = year % 100
        return when {
            year == 1000 -> "one thousand"
            year < 1100 -> "${
                cardinal(high)
            } ${if (low == 0) "hundred" else pairWords(low)}"
            year < 2000 -> if (low == 0) "${cardinal(high)} hundred" else "${cardinal(high)} ${pairWords(low)}"
            year < 2010 -> if (low == 0) "two thousand" else "two thousand and ${cardinal(low)}"
            else -> "${cardinal(high)} ${cardinal(low)}"
        }
    }

    /** The last two digits of a year: `oh five`, `thirty-seven`. */
    private fun pairWords(low: Int): String = when {
        low < 10 -> "oh ${cardinal(low)}"
        else -> cardinal(low)
    }

    // ----------------------------------------------------------------- decades

    /** `1890s`, which the voice reads as the number 1890 followed by the letter s. */
    private val DECADE = Regex("(?<![\\d.,])(\\d{3})0s\\b")

    private fun spellDecades(text: String): String {
        if (!text.contains("0s")) return text
        return DECADE.replace(text) { match ->
            val century = match.groupValues[1].toInt()
            val high = century / 10
            val tens = century % 10
            if (high !in 1..20 || tens == 0) {
                match.value
            } else {
                "${cardinal(high)} ${pluralTens(tens * 10)}"
            }
        }
    }

    private fun pluralTens(tens: Int): String = when (tens) {
        20 -> "twenties"
        30 -> "thirties"
        40 -> "forties"
        50 -> "fifties"
        60 -> "sixties"
        70 -> "seventies"
        80 -> "eighties"
        90 -> "nineties"
        else -> cardinal(tens)
    }

    // --------------------------------------------------------- roman numerals

    private val ROMAN_DIGITS = mapOf(
        'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000,
    )

    /** Divisions of a work, where the numeral is spoken as a cardinal. */
    private val DIVISIONS = setOf(
        "chapter", "chap", "part", "book", "act", "scene", "volume", "vol", "section",
        "appendix", "article", "canto", "stave", "letter", "no", "number", "figure",
        "fig", "plate", "stage", "table", "war", "series", "season", "episode",
    )

    /**
     * Names borne by rulers and popes, where the numeral is spoken as an ordinal.
     *
     * "Henry the Fifth" and "Chapter Five" are the same numeral in two frames, and a
     * reader who says "Henry Five" is heard as a foreigner. The distinction is the one
     * thing here that cannot be derived from the text's shape, so it is a table.
     */
    private val REGNAL = setOf(
        "henry", "edward", "george", "william", "charles", "louis", "philip", "frederick",
        "elizabeth", "mary", "anne", "james", "richard", "john", "stephen", "pope", "pius",
        "leo", "gregory", "innocent", "benedict", "urban", "clement", "alexander",
        "nicholas", "paul", "peter", "adrian", "charlemagne", "napoleon",
    )

    private val ROMAN = Regex("(?<![\\p{L}])([IVXLCDM]{1,9})(?![\\p{L}])")

    private fun spellRomanNumerals(text: String): String {
        if (!text.contains(Regex("[IVXLCDM]"))) return text
        return ROMAN.replace(text) { match ->
            val token = match.value
            val value = romanValue(token) ?: return@replace token
            val after = text.substring(match.range.last + 1)
            // The pronoun `I` is followed by the verb it governs — "the part I liked
            // best" — so a lowercase word after a lone `I` means the pronoun, not the
            // numeral. No other single numeral letter is an English word, so the guard
            // is restricted to `I`; applying it to `V` or `X` would swallow a heading's
            // numeral whenever the heading is set in mixed case.
            if (token == "I" && after.trimStart().firstOrNull()?.isLowerCase() == true) {
                return@replace token
            }
            // Only a numeral that follows a numbering word is a numeral. `MIX` and `DIV`
            // are made of numeral letters and parse, and both sit in sentences where the
            // preceding word is an ordinary one.
            val before = text.substring(0, match.range.first).trimEnd()
            val frame = before.takeLastWhile { it.isLetter() }
            // A heading is set in capitals — `CHAPTER VI.` — and the word replacing the
            // numeral has to match it, or the voice's prosody shifts mid-heading. Roman
            // numerals are always capitals, so the case has to be read off the word in
            // front of them, not off the numeral.
            val shout = frame.length > 1 && frame.all { it.isUpperCase() }
            fun say(word: String) = if (shout) word.uppercase() else word
            when (frame.lowercase()) {
                in DIVISIONS -> say(cardinal(value))
                in REGNAL -> "the ${ordinal(value)}"
                else -> token
            }
        }
    }

    /**
     * The value of a strict roman numeral, or null.
     *
     * Strict means the token must round-trip through [toRoman], which is what keeps an
     * ordinary word out: `CIVIL` and `MILD` are made only of numeral letters and both
     * parse, but neither is how the value is written back.
     */
    private fun romanValue(token: String): Int? {
        var total = 0
        var previous = 0
        for (i in token.indices.reversed()) {
            val value = ROMAN_DIGITS[token[i]] ?: return null
            if (value < previous) total -= value else {
                total += value
                previous = value
            }
        }
        if (total !in 1..3999) return null
        return total.takeIf { toRoman(it) == token }
    }

    private fun toRoman(value: Int): String {
        val table = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )
        var left = value
        val out = StringBuilder()
        for ((amount, symbol) in table) {
            while (left >= amount) {
                out.append(symbol)
                left -= amount
            }
        }
        return out.toString()
    }

    // --------------------------------------------------------------- meridiem

    /**
     * `P. M.` to `p m`.
     *
     * A dotted initial pair with a space between the letters is the one meridiem shape
     * the voice gets wrong: measured on the bundled voice, `5 P. M.` comes back as
     * "five p, M" — the first letter glued to the number and the second read as a word
     * of its own. Every other spelling is already correct and is left alone: `5 p.m.`,
     * `5 P.M.`, `5 pm` and `5 PM` all transcribe as "five pm", and the undotted forms
     * also include the verb `am`, which must not be touched.
     */
    private fun spellMeridiem(text: String): String {
        if (!text.contains('.')) return text
        return text.replace(Regex("\\b([apAP])\\.\\s+([mM])\\.?")) { match ->
            "${match.groupValues[1].lowercase()} ${match.groupValues[2].lowercase()}"
        }
    }

    // ------------------------------------------------------------------ spacing

    private fun tidySpacing(text: String): String = text
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\s+([,.;:!?])"), "$1")
        .replace(Regex("([\\[(])\\s+"), "$1")
        .replace(Regex("\\s+([\\])])"), "$1")
        .trim()

    // ------------------------------------------------------------------ numbers

    private val ONES = arrayOf(
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )

    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    /** 1–99 as words, hyphenated the way a year is written: `thirty-seven`. */
    private fun cardinal(value: Int): String = when {
        value < 20 -> ONES[value]
        value % 10 == 0 -> TENS[value / 10]
        else -> "${TENS[value / 10]}-${ONES[value % 10]}"
    }

    private val ORDINAL_ONES = arrayOf(
        "", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
        "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
        "sixteenth", "seventeenth", "eighteenth", "nineteenth",
    )

    private val ORDINAL_TENS = arrayOf(
        "", "", "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth",
        "seventieth", "eightieth", "ninetieth",
    )

    private fun ordinal(value: Int): String = when {
        value < 20 -> ORDINAL_ONES[value]
        value % 10 == 0 -> ORDINAL_TENS[value / 10]
        else -> "${TENS[value / 10]}-${ORDINAL_ONES[value % 10]}"
    }
}
