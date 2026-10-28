package com.engreader.app.nlp

/**
 * One connected-speech pattern, as it is written and as it sounds.
 *
 * [start] and [end] are offsets into the sentence, so the reader can underline the
 * words being talked about rather than describing them in the abstract.
 */
data class SpeechFeature(
    val kind: Kind,
    /** The words as written, e.g. `want to`. */
    val written: String,
    /** What they come out as, e.g. `wanna`. */
    val sounds: String,
    val start: Int,
    val end: Int,
    /** One line on why it happens, in the reader's language. */
    val tip: String,
)

/**
 * The patterns a learner cannot see in the spelling.
 *
 * Ordered as the sheet lists them: the ones that change a word's shape first, then
 * the ones that only run words together.
 */
enum class Kind(val label: String) {
    Reduction("缩读"),
    Assimilation("同化"),
    Flap("浊化"),
    Elision("失去爆破"),
    Linking("连读"),
    WeakForm("弱读"),
}

/**
 * Explains why a sentence does not sound like its spelling.
 *
 * This is a **rule table, not an alignment**. It reads the written words and reports
 * the patterns English applies at their boundaries; it does not know which syllable
 * the speaker stressed, so a pattern is offered as "this is the rule that applies
 * here", not as "this recording did this".
 *
 * Deliberately separate from [com.engreader.app.tts.SpokenText], which rewrites text
 * *for the voice* and measured that the bundled model already links and reduces
 * correctly — so it leaves connected speech alone. That module answers "how should
 * this be read"; this one answers "why did that sound like one word". Rewriting here
 * would break the first; explaining there would be off-topic. They share nothing but
 * the sentence.
 *
 * Every rule is a heuristic with a known false-positive class, noted at each one.
 */
object ConnectedSpeech {

    /** The most common reductions, as the pairs a reader will actually meet. */
    private val REDUCTIONS: List<Triple<String, String, String>> = listOf(
        Triple("want to", "wanna", "两个词连成一个，`t` 被吞掉"),
        Triple("going to", "gonna", "表示将来时几乎总是缩读"),
        Triple("got to", "gotta", "`have got to` 也一样"),
        Triple("have to", "hafta", "`v` 清化成 `f`"),
        Triple("has to", "hasta", "`have to` 的第三人称形式"),
        Triple("kind of", "kinda", "`of` 弱读成 `a`"),
        Triple("sort of", "sorta", "`of` 弱读成 `a`"),
        Triple("out of", "outta", "`of` 弱读成 `a`"),
        Triple("a lot of", "a lotta", "`of` 弱读成 `a`"),
        Triple("let me", "lemme", "`t` 被吞掉"),
        Triple("give me", "gimme", "`ve` 被吞掉"),
        Triple("don't know", "dunno", "`t` 被吞掉"),
        Triple("would have", "would've", "`have` 只剩 `ve`"),
        Triple("could have", "could've", "`have` 只剩 `ve`"),
        Triple("should have", "should've", "`have` 只剩 `ve`"),
        Triple("must have", "must've", "`have` 只剩 `ve`"),
        Triple("might have", "might've", "`have` 只剩 `ve`"),
        Triple("them", "'em", "句中的 `them` 常常只剩 `em`"),
        Triple("because", "'cause", "口语里常缩成 `cause`"),
    )

    /** Words that reduce to a schwa when they are not being stressed. */
    private val WEAK_FORMS: Map<String, String> = mapOf(
        "to" to "tə", "for" to "fə", "and" to "ən", "of" to "əv", "a" to "ə",
        "an" to "ən", "the" to "ðə", "at" to "ət", "from" to "frəm", "as" to "əz",
        "than" to "ðən", "that" to "ðət", "but" to "bət", "or" to "ə", "can" to "kən",
        "was" to "wəz", "were" to "wə", "have" to "əv", "has" to "əz", "had" to "əd",
        "do" to "də", "does" to "dəz", "her" to "ə", "us" to "əs", "them" to "əm",
        "some" to "səm", "your" to "jə", "you" to "jə", "there" to "ðə",
        "him" to "ɪm", "his" to "ɪz", "am" to "əm", "are" to "ə",
    )

    /** Words whose written final letter is not the sound they end with. */
    private val SILENT_FINALS = listOf("w", "gh", "mb", "mn")

    private val VOWELS = "aeiou"
    private val STOPS = "tdpbkg"

    /**
     * The patterns in [sentence], left to right.
     *
     * [tokens] must be the sentence's own word spans, as [Paragraphs.split] produces
     * them: the offsets come from there, and re-tokenising here would drift from the
     * spans the reader highlights.
     */
    fun explain(sentence: String, tokens: List<WordSpan>): List<SpeechFeature> {
        // Not `< 2`: the rules that need a boundary skip themselves on a short list, and
        // the two that live inside one word — the flap and the weak form — do not. A
        // one-word paragraph is a heading or a shouted line, and `Water.` is exactly the
        // case the flap rule is written for.
        if (tokens.isEmpty()) return emptyList()
        val out = mutableListOf<SpeechFeature>()
        val lower = tokens.map { Tokenizer.normalize(it.text) }

        reductions(lower, tokens, out)
        assimilations(lower, tokens, out)
        flapped(lower, tokens, out)
        unreleased(lower, tokens, out)
        linked(lower, tokens, out)
        weak(lower, tokens, out)

        // One pattern per boundary, the strongest first: `want to` is a reduction, and
        // reporting the weak form of `to` inside it as well would bury the thing worth
        // knowing. A feature that overlaps one already kept is dropped.
        val accepted = mutableListOf<SpeechFeature>()
        for (feature in out.sortedWith(compareBy({ it.kind.ordinal }, { it.start }))) {
            if (accepted.any { feature.start < it.end && it.start < feature.end }) continue
            accepted += feature
        }
        return accepted
    }

    /** `want to` → `wanna`, and the other set phrases. */
    private fun reductions(lower: List<String>, tokens: List<WordSpan>, out: MutableList<SpeechFeature>) {
        var i = 0
        while (i < tokens.size) {
            val pair = if (i + 1 < tokens.size) "${lower[i]} ${lower[i + 1]}" else null
            val triple = if (i + 2 < tokens.size) "${lower[i]} ${lower[i + 1]} ${lower[i + 2]}" else null
            // Longest first: `a lot of` must not be taken as `of` on its own.
            val hit = REDUCTIONS.firstOrNull { it.first == triple }
                ?: REDUCTIONS.firstOrNull { it.first == pair }
            if (hit != null) {
                val span = hit.first.count { it == ' ' } + 1
                out += SpeechFeature(
                    kind = Kind.Reduction,
                    written = sentence(tokens, i, span),
                    sounds = hit.second,
                    start = tokens[i].start,
                    end = tokens[i + span - 1].end,
                    tip = hit.third,
                )
                i += span
            } else {
                i++
            }
        }
    }

    /** `did you` → `didja`, `this year` → `thish year`. */
    private fun assimilations(lower: List<String>, tokens: List<WordSpan>, out: MutableList<SpeechFeature>) {
        for (i in 0 until tokens.size - 1) {
            val next = lower[i + 1]
            if (next != "you" && next != "your" && next != "year") continue
            val word = lower[i]
            val last = word.lastOrNull() ?: continue
            // The written form merges for the stops (`did you` is said as one word) but
            // stays two words for the fricatives (`thish year`), so the two cases spell
            // out differently.
            val sounds = when (last) {
                'd' -> word.dropLast(1) + "dja"
                't' -> word.dropLast(1) + "cha"
                's' -> word.trimEnd('s') + "sh " + next
                'z' -> word.trimEnd('z') + "zh " + next
                else -> continue
            }
            out += SpeechFeature(
                kind = Kind.Assimilation,
                written = sentence(tokens, i, 2),
                sounds = sounds,
                start = tokens[i].start,
                end = tokens[i + 1].end,
                tip = "`$last` 遇到 /j/ 合成一个音",
            )
        }
    }

    /** `water` → `wader`: /t/ between vowels with the stress in front of it. */
    private fun flapped(lower: List<String>, tokens: List<WordSpan>, out: MutableList<SpeechFeature>) {
        for (i in tokens.indices) {
            val word = lower[i]
            var at = 1
            while (at < word.length) {
                if (word[at] != 't') {
                    at++
                    continue
                }
                // A doubled `tt` is one sound, so both letters are consumed together.
                val doubled = at + 1 < word.length && word[at + 1] == 't'
                val after = word.substring(if (doubled) at + 2 else at + 1)
                val before = word[at - 1]
                // Only the endings that are themselves unstressed: `-er`, `-y`, `-ing`,
                // `-le`. `return`, `hotel` and `total` carry the stress on that syllable
                // and do not flap, which is why `u`, `a` and `o` are not in this list.
                val flaps = (before in VOWELS || before == 'r') &&
                    (after.startsWith("er") || after.startsWith("y") ||
                        after.startsWith("i") || after.startsWith("le"))
                if (flaps) {
                    out += SpeechFeature(
                        kind = Kind.Flap,
                        written = tokens[i].text,
                        sounds = word.substring(0, at) + "d" + after,
                        start = tokens[i].start,
                        end = tokens[i].end,
                        tip = "美音里两个元音之间的 `t` 变浊，听起来接近 `d`",
                    )
                    // One feature per word — the written form and the span are the same
                    // whichever /t/ was found, and a second entry for the same span would
                    // only be dropped by `explain`'s overlap pass. This was a
                    // `return@flapped`, which left the whole function: the first word in a
                    // sentence that flapped ended the scan, so `water bottle` reported
                    // `water` and nothing else, and every later word went unexamined.
                    break
                }
                at += if (doubled) 2 else 1
            }
        }
    }

    /** `next day`: the stop is held rather than released. */
    private fun unreleased(lower: List<String>, tokens: List<WordSpan>, out: MutableList<SpeechFeature>) {
        for (i in 0 until tokens.size - 1) {
            val word = lower[i]
            val last = word.lastOrNull() ?: continue
            if (last !in STOPS) continue
            // `wanted to`, `needed to`: the `-ed` here is its own syllable and is not
            // the silent-stop case.
            if ((last == 't' || last == 'd') && word.endsWith("ed") &&
                word.length >= 4 && word[word.length - 3] in "td"
            ) continue
            val nextFirst = lower[i + 1].firstOrNull() ?: continue
            if (nextFirst in VOWELS) continue
            out += SpeechFeature(
                kind = Kind.Elision,
                written = sentence(tokens, i, 2),
                sounds = "${word.dropLast(1)}(${last})…",
                start = tokens[i].start,
                end = tokens[i + 1].end,
                tip = "`$last` 后面紧跟辅音，只做口型不爆破",
            )
        }
    }

    /** `an apple`: a final consonant runs into the next word's first vowel. */
    private fun linked(lower: List<String>, tokens: List<WordSpan>, out: MutableList<SpeechFeature>) {
        for (i in 0 until tokens.size - 1) {
            val word = lower[i]
            val last = word.lastOrNull() ?: continue
            // A word spelled `…re` ends in a silent `e`: the sound that can run into the
            // next word is the `r` in front of it — `more apples`, `there is` — which is
            // the linking-r case this rule exists for. Reading the last *character*
            // treated that `e` as the coda and rejected the word before `r` was ever
            // considered, so the r tip below was only ever produced for a spelling that
            // ends in a bare `r` (`far away`), and the `word.endsWith("re")` half of the
            // test was unreachable.
            val coda = if (word.endsWith("re")) 'r' else last
            if (coda in VOWELS || coda !in 'a'..'z') continue
            if (SILENT_FINALS.any { word.endsWith(it) }) continue
            val nextFirst = lower[i + 1].firstOrNull() ?: continue
            if (nextFirst !in VOWELS) continue
            val isR = coda == 'r'
            out += SpeechFeature(
                kind = Kind.Linking,
                written = sentence(tokens, i, 2),
                sounds = "${word}‿${lower[i + 1]}",
                start = tokens[i].start,
                end = tokens[i + 1].end,
                tip = if (isR) "英音里词尾的 `r` 遇到元音才发音" else "辅音直接拼到下一个词的开头元音上",
            )
        }
    }

    /** `to`, `for`, `and`: the vowel collapses unless the word is being stressed. */
    private fun weak(lower: List<String>, tokens: List<WordSpan>, out: MutableList<SpeechFeature>) {
        for (i in tokens.indices) {
            val sound = WEAK_FORMS[lower[i]] ?: continue
            // A one-word answer is stressed, not reduced: `Where are you going?` — `To.`
            if (i == tokens.lastIndex) continue
            out += SpeechFeature(
                kind = Kind.WeakForm,
                written = tokens[i].text,
                sounds = sound,
                start = tokens[i].start,
                end = tokens[i].end,
                tip = "功能词不重读时元音弱化成 /ə/",
            )
        }
    }

    private fun sentence(tokens: List<WordSpan>, from: Int, count: Int): String =
        tokens.subList(from, from + count).joinToString(" ") { it.text }
}
