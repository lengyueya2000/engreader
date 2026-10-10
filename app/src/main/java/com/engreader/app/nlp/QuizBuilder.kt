package com.engreader.app.nlp

import com.engreader.app.dict.Lexicon
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.dict.PartOfSpeechParser
import com.engreader.app.dict.WordEntry
import com.engreader.app.model.QuizQuestion

/**
 * Builds reading-comprehension questions from an article's own text.
 *
 * Two types are generated, both of which a rule can answer correctly rather than
 * guess at:
 *
 *  * **cloze** — a study-worthy word is blanked out and the reader picks it from
 *    four options of the same part of speech. The answer is the word the article
 *    actually used, so it is verifiable from the text. This deliberately replaces
 *    a "what does X mean" question: an offline dictionary cannot tell that
 *    `barrage` in "a barrage of accusations" is the figurative 一连串 rather than
 *    the first-listed 弹幕, and a question whose answer contradicts the passage is
 *    worse than no question.
 *  * **reference resolution** — for a relative clause, what the pronoun refers
 *    back to, with the antecedent taken from the text.
 *
 * Both are real reading skills and neither requires understanding the article as
 * a whole, which is what keeps the answers trustworthy.
 */
class QuizBuilder(private val dictionary: Lexicon) {

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for",
        "with", "by", "from", "as", "is", "are", "was", "were", "be", "been", "being",
        "has", "have", "had", "do", "does", "did", "will", "would", "can", "could",
        "may", "might", "must", "shall", "should", "this", "that", "these", "those",
        "it", "its", "he", "she", "they", "them", "his", "her", "their", "we", "our",
        "you", "your", "i", "my", "me", "us", "not", "no", "so", "than", "then",
        "there", "here", "which", "who", "whom", "whose", "what", "when", "where",
        "why", "how", "if", "while", "also", "more", "most", "such", "other", "one",
        "two", "new", "said", "says", "like", "just", "now", "over", "after",
    )

    fun build(articleText: String, maxQuestions: Int = 5): List<QuizQuestion> {
        val sentences = Sentences.split(articleText.replace("\n", " "))
        if (sentences.isEmpty()) return emptyList()

        // Words the article uses more than once make the best cloze targets: the
        // blank is then recoverable from the reader's memory of the passage, and a
        // distractor drawn from the same article fits the register.
        val articleWords = articleText.let { text ->
            Tokenizer.words(text).map { it.lowercase() }.groupingBy { it }.eachCount()
        }

        val questions = mutableListOf<QuizQuestion>()
        questions += clozeQuestions(sentences, articleWords, maxQuestions)
        if (questions.size < maxQuestions) {
            questions += referenceQuestions(sentences, maxQuestions - questions.size)
        }
        return questions.take(maxQuestions)
    }

    // ------------------------------------------------------------------ cloze

    private fun clozeQuestions(
        sentences: List<Sentence>,
        articleWords: Map<String, Int>,
        limit: Int,
    ): List<QuizQuestion> {
        data class Candidate(
            val sentence: Sentence,
            val entry: WordEntry,
            val surface: String,
            val pos: PartOfSpeech,
            /** Exact `[start, end)` of the surface form inside `sentence.text`. */
            val start: Int,
            val end: Int,
        )

        val candidates = mutableListOf<Candidate>()
        val seen = HashSet<String>()
        for (sentence in sentences) {
            // Short sentences make a poor question: too little context to reason from.
            if (sentence.wordCount < 8) continue
            for (match in Tokenizer.matches(sentence.text)) {
                val raw = match.value
                val lower = raw.lowercase()
                if (lower in STOP || lower.length < 5) continue
                if (!seen.add(lower)) continue
                val entry = dictionary.lookup(raw) ?: continue
                // Test the word as the article wrote it, and only when that spelling
                // is the dictionary headword — an inflected form is a different
                // question from the one the reader would ask.
                if (!entry.lemma.equals(raw, ignoreCase = true)) continue
                if (!entry.isStudyWorthy) continue
                // A capitalised token away from the sentence start is a name.
                if (isProperNounUse(raw, sentence)) continue
                // Must also appear in lowercase somewhere, i.e. the text really is
                // using the common word.
                if (!appearsLowercase(raw, sentences)) continue
                val pos = posOf(entry)
                if (pos == PartOfSpeech.Unknown) continue
                candidates += Candidate(
                    sentence = sentence,
                    entry = entry,
                    surface = raw,
                    pos = pos,
                    start = match.range.first,
                    end = match.range.last + 1,
                )
                break
            }
        }

        val out = mutableListOf<QuizQuestion>()
        for (candidate in candidates) {
            if (out.size >= limit) break
            val options = clozeOptions(candidate.surface, candidate.pos, articleWords) ?: continue
            val answer = options.indexOfFirst { it.equals(candidate.surface, ignoreCase = true) }
            if (answer < 0) continue
            val blanked = blankOut(candidate.sentence.text, candidate.start, candidate.end)
            if (blanked == candidate.sentence.text) continue
            // The same word often appears twice in a sentence ("the barrage … the
            // barrage"), and blanking one occurrence left the other on screen, so the
            // question answered itself.
            if (stillVisible(blanked, candidate.surface)) continue

            val gloss = dictionary.firstGloss(candidate.entry.translation)
            out += QuizQuestion(
                question = "选一个词填入空格，使句子与原文一致：\n\n“$blanked”",
                options = options,
                answerIndex = answer,
                explanation = "原文用的是 ${candidate.surface}。" +
                    if (gloss.isNotBlank()) {
                        "它在词典里的释义是「$gloss」，" +
                            "在这句话里的具体含义要结合上下文确定。"
                    } else {
                        ""
                    },
            )
        }
        return out
    }

    /**
     * Four English options: the word the article used plus three same-part-of-speech
     * words drawn from the article itself where possible, so the wrong answers are
     * plausible in register rather than obviously foreign.
     */
    private fun clozeOptions(
        answer: String,
        pos: PartOfSpeech,
        articleWords: Map<String, Int>,
    ): List<String>? {
        val fromArticle = articleWords.keys
            .filter { it != answer.lowercase() && it.length >= 5 && it !in STOP }
            .filter { word -> posOfWord(word) == pos }
            .sorted()
            .take(12)

        val pool = fromArticle.toMutableList()
        if (pool.size < 3) {
            val entry = dictionary.lookup(answer) ?: return null
            // English words, not glosses: the blank sits in an English sentence, so a
            // Chinese option is not a wrong answer but a giveaway.
            pool += dictionary.englishDistractors(entry, pos, count = 3 - pool.size)
        }
        if (pool.size < 3) return null

        // Deterministic pick and rotation: the same article always yields the same
        // quiz, but the answer is not always in the same slot.
        val seed = answer.hashCode()
        val picked = mutableListOf<String>()
        var cursor = Math.floorMod(seed, pool.size)
        while (picked.size < 3 && picked.size < pool.size) {
            val candidate = pool[cursor % pool.size]
            if (candidate !in picked) picked += candidate
            cursor++
        }
        if (picked.size < 3) return null

        val options = (picked + answer).toMutableList()
        val shift = Math.floorMod(seed, 4)
        repeat(shift) { options.add(options.removeAt(0)) }
        return options
    }

    /**
     * Replaces the answer with a blank, keeping any trailing punctuation and the
     * space after the word so the blank reads as a gap rather than running into the
     * next word.
     *
     * Takes the token's offsets rather than searching for its text: `indexOf` finds
     * the first occurrence anywhere, so blanking `reform` in "The reforms stalled…"
     * used to cut the `reforms` in the middle and leave a dangling `s`.
     */
    private fun blankOut(sentence: String, start: Int, end: Int): String =
        Cloze.blankAt(sentence, start, end)

    /** True when the answer is still readable somewhere else in the blanked sentence. */
    private fun stillVisible(blanked: String, answer: String): Boolean =
        Regex(
            "(?<![A-Za-z'\\u2019-])" + Regex.escape(answer) + "(?![A-Za-z'\\u2019-])",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(blanked)

    // -------------------------------------------------------------- reference

    private fun referenceQuestions(sentences: List<Sentence>, limit: Int): List<QuizQuestion> {
        val out = mutableListOf<QuizQuestion>()
        for (sentence in sentences) {
            if (out.size >= limit) break
            val words = Tokenizer.words(sentence.text)
            val lower = words.map { it.lowercase() }
            val markerIndex = lower.indexOfFirst { it in setOf("which", "who", "whom", "whose") }
            if (markerIndex <= 1) continue

            val antecedent = words.take(markerIndex)
                .takeLastWhile { it.lowercase() !in STOP || it.first().isUpperCase() }
                .joinToString(" ")
                .ifBlank { words[markerIndex - 1] }
            if (antecedent.length < 3) continue

            val otherNouns = words
                .filterIndexed { i, w -> i < markerIndex - 1 && w.length > 4 && w.lowercase() !in STOP }
                .map { it.trim(',', '.', '"', '\'') }
                .filter { it.length > 4 && !it.equals(antecedent, ignoreCase = true) }
                .distinct()
                .take(3)
            if (otherNouns.size < 3) continue

            val options = (otherNouns + antecedent).toMutableList()
            val shift = Math.floorMod(sentence.start, 4)
            repeat(shift) { options.add(options.removeAt(0)) }
            val answer = options.indexOf(antecedent)
            if (answer < 0) continue

            out += QuizQuestion(
                question = "下面的句子中，${words[markerIndex]} 指代的是：\n\n“${sentence.text.take(220)}”",
                options = options,
                answerIndex = answer,
                explanation = "定语从句由 ${words[markerIndex]} 引导，它紧跟在先行词「$antecedent」之后，" +
                    "因此指代的就是这个成分。",
            )
        }
        return out
    }

    // ---------------------------------------------------------------- lexicon

    private fun posOf(entry: WordEntry): PartOfSpeech =
        PartOfSpeechParser.parse(
            entry.translation.split("\\n", "\n").firstOrNull { it.isNotBlank() }.orEmpty(),
        ).takeIf { it != PartOfSpeech.Unknown }
            ?: PartOfSpeechParser.guess(entry.lemma)

    private fun posOfWord(word: String): PartOfSpeech {
        val entry = dictionary.lookup(word)
        return if (entry != null) posOf(entry) else PartOfSpeechParser.guess(word)
    }

    /**
     * True when the token reads as a name rather than as the dictionary word.
     *
     * A capitalised form of a lowercase headword is almost always a proper noun in
     * news prose — `Matt`, `Rose`, `Baker` — unless it opens the sentence, where
     * capitalisation is just orthography. Opening quotes are skipped so a quoted
     * first word is still recognised as sentence-initial.
     */
    private fun isProperNounUse(raw: String, sentence: Sentence): Boolean {
        if (raw.isEmpty() || !raw.first().isUpperCase()) return false
        val lead = sentence.text.trimStart(' ', '\u201C', '"', '\u2018', '\'', '(', '\u2014', '-')
        return !lead.startsWith(raw)
    }

    /** True when the word occurs in lowercase somewhere in the passage. */
    private fun appearsLowercase(word: String, sentences: List<Sentence>): Boolean {
        val lower = word.lowercase()
        for (sentence in sentences) {
            var from = 0
            while (from <= sentence.text.length - lower.length) {
                val at = sentence.text.indexOf(lower, from, ignoreCase = true)
                if (at < 0) break
                if (sentence.text.regionMatches(at, lower, 0, lower.length)) return true
                from = at + 1
            }
        }
        return false
    }
}
