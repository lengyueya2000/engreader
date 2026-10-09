package com.engreader.app.nlp

import com.engreader.app.dict.Lexicon
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.dict.PartOfSpeechParser

/** Role a word group plays in its clause, used to colour the breakdown view. */
enum class ChunkRole(val label: String) {
    Subject("主语"),
    Predicate("谓语"),
    Object("宾语"),
    Complement("表语"),
    Adverbial("状语"),
    Attributive("定语"),
    Appositive("同位语"),
    Connector("连接词"),
    Other("其他"),
}

data class Chunk(val text: String, val role: ChunkRole, val start: Int, val end: Int)

/** Clause types a learner needs to tell apart. */
enum class ClauseKind(val label: String) {
    Main("主句"),
    Adverbial("状语从句"),
    Relative("定语从句"),
    Nominal("名词性从句"),
    NonFinite("非谓语结构"),
    Parenthetical("插入语"),
    Coordinated("并列分句"),
}

data class Clause(
    val kind: ClauseKind,
    /** The word that introduces the clause: `that`, `which`, `because`, `to`, ... */
    val marker: String,
    val text: String,
    val subject: String,
    val verb: String,
    val rest: String,
    val start: Int,
    val end: Int,
    val depth: Int,
)

data class GrammarNote(val label: String, val detail: String)

data class SentenceAnalysis(
    val sentence: String,
    val clauseCount: Int,
    val isLong: Boolean,
    val clauses: List<Clause>,
    /** The main clause reduced to subject + verb + object, i.e. what the sentence says. */
    val backbone: String,
    val chunks: List<Chunk>,
    val notes: List<GrammarNote>,
)

/**
 * Rule-based long-sentence analyser.
 *
 * This is deliberately not a statistical parser: it segments a sentence at
 * subordinators, relative pronouns and non-finite markers, then locates the finite
 * verb in each segment to pull out subject and predicate. For a learner reading
 * news prose that is enough to answer "where is the main clause, and what hangs
 * off it", which is the question the breakdown panel exists to answer.
 */
class GrammarAnalyzer(private val dictionary: Lexicon) {

    private val posCache = HashMap<String, PartOfSpeech>(4096)

    private val SUBORDINATORS = setOf(
        "because", "although", "though", "even", "if", "unless", "until", "till",
        "since", "as", "after", "before", "while", "whilst", "whereas", "whether",
        "once", "whenever", "wherever", "provided", "supposing", "given", "now",
        "so", "such", "than", "lest", "whereupon",
    )

    private val RELATIVE_PRONOUNS = setOf("who", "whom", "whose", "which", "that")

    private val AUXILIARIES = setOf(
        "am", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "having",
        "do", "does", "did",
        "will", "would", "shall", "should", "can", "could",
        "may", "might", "must", "ought", "need", "dare",
    )

    /** Multi-word subordinators, matched before single tokens. */
    private val PHRASAL_SUBORDINATORS = listOf(
        "as if", "as though", "as long as", "as soon as", "as far as", "so that",
        "in order that", "even though", "even if", "in case", "now that",
        "provided that", "given that", "the moment", "by the time", "no sooner",
    )

    /** Longest entry in [PHRASAL_SUBORDINATORS], i.e. the widest window to try. */
    private val MAX_PHRASAL_WORDS = PHRASAL_SUBORDINATORS.maxOf { it.split(' ').size }

    private val COORDINATORS = setOf("and", "but", "or", "nor", "yet", "so", "for")

    fun analyze(sentence: String): SentenceAnalysis {
        val tokens = Tokenizer.words(sentence)
        if (tokens.isEmpty()) {
            return SentenceAnalysis(sentence, 0, false, emptyList(), sentence, emptyList(), emptyList())
        }

        val markers = findClauseMarkers(sentence, tokens)
        val segments = segment(sentence, markers)
        val clauses = segments.mapIndexed { index, seg ->
            buildClause(seg, index, depth = if (seg.kind == ClauseKind.Main) 0 else 1)
        }

        val main = clauses.firstOrNull { it.kind == ClauseKind.Main } ?: clauses.firstOrNull()
        val backbone = main?.let { backboneOf(it) } ?: sentence
        val chunks = main?.let { chunk(it) } ?: emptyList()
        val notes = buildNotes(sentence, clauses, main)

        return SentenceAnalysis(
            sentence = sentence,
            clauseCount = clauses.size,
            isLong = tokens.size >= 32 || clauses.count { it.kind != ClauseKind.Main } >= 2,
            clauses = clauses,
            backbone = backbone,
            chunks = chunks,
            notes = notes,
        )
    }

    // ---------------------------------------------------------------- markers

    private data class Marker(
        val start: Int,
        val end: Int,
        val text: String,
        val kind: ClauseKind,
        /** True when the marker opens a clause that continues to the next marker. */
        val opensClause: Boolean,
    )

    private fun findClauseMarkers(sentence: String, tokens: List<String>): List<Marker> {
        val lower = tokens.map { it.lowercase() }
        val markers = mutableListOf<Marker>()
        val offsets = wordOffsets(sentence, tokens)

        var i = 0
        while (i < tokens.size) {
            val phrasal = matchPhrasal(lower, i)

            if (phrasal != null) {
                val words = phrasal.split(' ').size
                markers += Marker(
                    offsets[i],
                    offsets[i + words - 1] + tokens[i + words - 1].length,
                    phrasal,
                    ClauseKind.Adverbial,
                    true,
                )
                i += words
                continue
            }

            val w = lower[i]
            when {
                // Relative pronoun: `that`/`which`/`who` opening a clause, either
                // right after a noun or after a comma that closes a noun phrase.
                w in RELATIVE_PRONOUNS && i > 0 && isRelativePosition(sentence, tokens, offsets, i) -> {
                    markers += Marker(offsets[i], offsets[i] + tokens[i].length, tokens[i], ClauseKind.Relative, true)
                }

                // Subordinating conjunction starting a clause.
                w in SUBORDINATORS && isSubordinatorUse(lower, i) -> {
                    markers += Marker(offsets[i], offsets[i] + tokens[i].length, tokens[i], ClauseKind.Adverbial, true)
                }

                // Infinitive of purpose / as subject: `To determine ...`. A `to`
                // followed by a verb is never the preposition, so no further test.
                w == "to" && i + 1 < lower.size && isVerb(lower[i + 1]) && !isInfinitiveComplement(lower, i) -> {
                    markers += Marker(
                        offsets[i],
                        offsets[i] + tokens[i].length,
                        "to ${tokens[i + 1]}",
                        ClauseKind.NonFinite,
                        true,
                    )
                }

                // Participial phrase: `Faced with ...`, `Having said that ...`
                i > 0 && isParticiple(tokens[i]) && isNonFiniteStart(sentence, offsets[i], i, lower) -> {
                    markers += Marker(offsets[i], offsets[i] + tokens[i].length, tokens[i], ClauseKind.NonFinite, true)
                }

                // Coordinating conjunction between two finite clauses.
                w in COORDINATORS && i > 0 && isClauseBoundary(lower, i) -> {
                    markers += Marker(
                        offsets[i],
                        offsets[i] + tokens[i].length,
                        tokens[i],
                        ClauseKind.Coordinated,
                        true,
                    )
                }
            }
            i++
        }

        // A colon or semicolon followed by its own subject and verb also joins two
        // independent clauses; news prose uses this constantly to add an
        // explanation ("... gentlemanlike: he had a pleasant countenance").
        markers += punctuationBoundaries(sentence, tokens, offsets)
        return markers.sortedBy { it.start }
    }

    private fun punctuationBoundaries(
        sentence: String,
        tokens: List<String>,
        offsets: List<Int>,
    ): List<Marker> {
        val out = mutableListOf<Marker>()
        for (tokenIndex in 1 until tokens.size) {
            val at = offsets[tokenIndex]
            var i = at - 1
            while (i >= 0 && sentence[i] == ' ') i--
            if (i < 0 || (sentence[i] != ':' && sentence[i] != ';')) continue
            // Only when the punctuation is immediately followed by a clause of its
            // own, i.e. a finite verb appears within the next few words.
            val window = tokenIndex..minOf(tokenIndex + 5, tokens.lastIndex)
            val lower = tokens.map { it.lowercase() }
            if (window.any { isFiniteVerb(lower, it) }) {
                out += Marker(at, at + tokens[tokenIndex].length, sentence[i].toString(), ClauseKind.Coordinated, true)
            }
        }
        return out
    }

    /**
     * True when the token sits where a relative pronoun can open a clause.
     *
     * `which`/`who`/`whom`/`whose` are relative pronouns whenever they are not
     * sentence-initial (where they would be interrogative). `that` is the ambiguous
     * one — it is a demonstrative, a conjunction and a relative pronoun — so it
     * needs a noun phrase in front of it.
     */
    private fun isRelativePosition(
        sentence: String,
        tokens: List<String>,
        offsets: List<Int>,
        index: Int,
    ): Boolean {
        val word = tokens[index].lowercase()
        if (word != "that") return true
        if (commaBefore(sentence, offsets[index])) return false
        return isLikelyNoun(tokens[index - 1])
    }

    /** True when the only thing between the previous word and [offset] is a comma. */
    private fun commaBefore(sentence: String, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && sentence[i] == ' ') i--
        return i >= 0 && sentence[i] == ','
    }

    /**
     * `to` + verb is a full infinitive complement of the previous verb ("decided to
     * leave") rather than a clause of its own; those should not open a segment.
     */
    private fun isInfinitiveComplement(lower: List<String>, index: Int): Boolean {
        val prev = lower.getOrNull(index - 1) ?: return false
        // Any verb immediately before `to` takes the infinitive as its complement
        // ("decided to leave"), which is not a clause in its own right.
        return posOf(prev) == PartOfSpeech.Verb || prev in AUXILIARIES
    }

    /**
     * `for`, `so`, `as` and `since` are only subordinators in some readings; require
     * a plausible subject after them so `as a result` is not treated as a clause.
     */
    private fun isSubordinatorUse(lower: List<String>, index: Int): Boolean {
        val next = lower.getOrNull(index + 1) ?: return false
        val w = lower[index]
        return when (w) {
            "as" -> next !in setOf("a", "an", "the", "well", "such", "much", "many", "if", "though", "opposed")
            "so" -> next !in setOf("far", "much", "many", "long", "on", "that")
            "for" -> next !in setOf("a", "an", "the", "example", "instance", "now", "some", "this", "that")
            "since" -> true
            "now" -> next == "that"
            "such" -> next == "that"
            "even" -> next in setOf("though", "if", "when", "as")
            "that" -> false
            else -> true
        }
    }

    private fun isClauseBoundary(lower: List<String>, index: Int): Boolean {
        // `and` between two adjectives or two nouns coordinates a phrase, not a
        // clause: "good-looking and gentlemanlike" is one complement, and treating
        // it as a second clause hides where the sentence really breaks.
        val before = lower.getOrNull(index - 1).orEmpty()
        val after = lower.getOrNull(index + 1).orEmpty()
        if (posOf(before) == PartOfSpeech.Adjective && posOf(after) == PartOfSpeech.Adjective) {
            return false
        }
        if (posOf(before) == PartOfSpeech.Noun && posOf(after) == PartOfSpeech.Noun) {
            val hasVerbSoon = (index + 1..minOf(index + 4, lower.lastIndex))
                .any { isFiniteVerb(lower, it) }
            if (!hasVerbSoon) return false
        }

        // A coordinator joins clauses only when a finite verb follows before the
        // next coordinator.
        var i = index + 1
        var depth = 0
        while (i < lower.size && i < index + 12) {
            val w = lower[i]
            if (w in COORDINATORS || w in SUBORDINATORS) depth++
            if (isFiniteVerb(lower, i) && depth == 0) return true
            i++
        }
        return false
    }

    /**
     * Longest phrasal subordinator starting at [index], or null.
     *
     * Matched longest-first: `PHRASAL_SUBORDINATORS` contains three-word entries
     * ("as soon as", "by the time"), and comparing a fixed two-token window made
     * every one of them dead — the phrase then fell through to the single-word
     * `as`/`that` branch and was segmented as two separate markers.
     */
    private fun matchPhrasal(lower: List<String>, index: Int): String? {
        for (length in MAX_PHRASAL_WORDS downTo 2) {
            if (index + length > lower.size) continue
            val candidate = lower.subList(index, index + length).joinToString(" ")
            PHRASAL_SUBORDINATORS.firstOrNull { it == candidate }?.let { return it }
        }
        return null
    }

    /**
     * True when a participial phrase starts here: at the beginning of the sentence,
     * or right after a comma or coordinator.
     *
     * The punctuation has to be read from the raw sentence, not from the token list:
     * `Tokenizer` only yields letters, apostrophes and hyphens, so a preceding comma
     * is a character before [offset], never a token that "ends with a comma".
     */
    private fun isNonFiniteStart(sentence: String, offset: Int, index: Int, lower: List<String>): Boolean {
        if (index == 0) return true
        if (lower[index - 1] in COORDINATORS) return true
        var i = offset - 1
        while (i >= 0 && sentence[i].isWhitespace()) i--
        return i >= 0 && (sentence[i] == ',' || sentence[i] == ';' || sentence[i] == ':')
    }

    private fun isParticiple(word: String): Boolean {
        val w = word.lowercase()
        return (w.endsWith("ing") || w.endsWith("ed") || w.endsWith("en")) && isVerb(w)
    }

    private fun isLikelyNoun(word: String): Boolean = when (posOf(word)) {
        PartOfSpeech.Noun, PartOfSpeech.Pronoun, PartOfSpeech.Unknown -> true
        else -> false
    }

    // -------------------------------------------------------------- segments

    private data class Segment(
        val text: String,
        val start: Int,
        val end: Int,
        val kind: ClauseKind,
        val marker: String,
    )

    private fun segment(sentence: String, markers: List<Marker>): List<Segment> {
        val out = mutableListOf<Segment>()
        if (markers.isEmpty()) {
            val trimmed = sentence.trim()
            return listOf(
                Segment(
                    trimmed, sentence.indexOf(trimmed).coerceAtLeast(0),
                    sentence.indexOf(trimmed).coerceAtLeast(0) + trimmed.length,
                    ClauseKind.Main, "",
                )
            )
        }

        val first = markers.first()
        if (first.start > 0) {
            val head = sentence.substring(0, first.start).trim().trimEnd(',', ';', ':')
            if (head.isNotEmpty()) {
                val at = sentence.indexOf(head)
                out += Segment(head, at, at + head.length, ClauseKind.Main, "")
            }
        }

        markers.forEachIndexed { index, marker ->
            val nextStart = markers.getOrNull(index + 1)?.start ?: sentence.length
            val raw = sentence.substring(marker.start, nextStart)

            // A subordinate clause at the front of the sentence is closed by the
            // comma that separates it from the main clause; everything after that
            // comma is the main clause, not part of the subordinate one.
            val splitAt = if (out.isEmpty() || out.last().kind != ClauseKind.Main) {
                frontedSplit(raw, marker)
            } else -1

            if (splitAt > 0) {
                val clauseText = raw.substring(0, splitAt).trim().trimEnd(',', ';', ':').trim()
                val restText = raw.substring(splitAt).trim().trimStart(',', ';', ':').trim()
                addSegment(out, sentence, clauseText, marker.start, marker.kind, marker.text)
                addSegment(out, sentence, restText, marker.start + splitAt, ClauseKind.Main, "")
                return@forEachIndexed
            }

            val text = raw.trim().trimEnd(',', ';', ':').trim()
            val kind = if (index == 0 && marker.start == 0 && marker.kind == ClauseKind.Coordinated) {
                ClauseKind.Main
            } else marker.kind
            addSegment(out, sentence, text, marker.start, kind, marker.text)
        }
        return out
    }

    /**
     * Offset where a fronted subordinate clause ends and the main clause begins, or
     * -1 when the clause is not fronted or no main clause can be located.
     */
    private fun frontedSplit(raw: String, marker: Marker): Int {
        if (marker.kind != ClauseKind.Adverbial && marker.kind != ClauseKind.NonFinite) return -1
        val comma = raw.indexOf(',')
        if (comma > 0) {
            val rest = raw.substring(comma + 1).trim()
            if (rest.isNotEmpty()) {
                // Only split when a finite verb follows, i.e. there really is a main clause.
                val restWords = Tokenizer.words(rest).map { it.lowercase() }
                if (restWords.indices.any { isFiniteVerb(restWords, it) }) return comma + 1
            }
        }
        return frontedSplitWithoutComma(raw)
    }

    /**
     * Locates the main clause of a fronted subordinate clause written without a comma.
     *
     * "When he arrived she left." has no punctuation to split on, so the boundary has
     * to come from the verbs: the subordinate clause ends after its own verb and the
     * main clause begins at the subject of the next finite verb. Without this the
     * whole sentence was labelled subordinate and no main clause existed at all, so
     * the panel showed the subordinate clause as the sentence's backbone.
     */
    private fun frontedSplitWithoutComma(raw: String): Int {
        val tokens = Tokenizer.words(raw)
        if (tokens.isEmpty()) return -1
        val lower = tokens.map { it.lowercase() }
        val offsets = wordOffsets(raw, tokens)
        val firstVerb = lower.indices.firstOrNull { isFiniteVerb(lower, it) } ?: return -1
        val secondVerb = (firstVerb + 1 until lower.size).firstOrNull { isFiniteVerb(lower, it) }
            ?: return -1
        // Walk back from the second verb over its subject phrase, stopping at a word
        // that cannot be part of a subject.
        var start = secondVerb
        var steps = 0
        while (start > firstVerb && steps < 5) {
            val prev = lower[start - 1]
            if (prev in COORDINATORS || prev in SUBORDINATORS) break
            if (posOf(prev) == PartOfSpeech.Preposition) break
            if (isFiniteVerb(lower, start - 1)) break
            start--
            steps++
        }
        // The main clause needs at least one word before its verb.
        return if (start in (firstVerb + 1) until secondVerb) offsets[start] else -1
    }

    private fun addSegment(
        out: MutableList<Segment>,
        sentence: String,
        text: String,
        from: Int,
        kind: ClauseKind,
        marker: String,
    ) {
        if (text.isEmpty()) return
        val at = sentence.indexOf(text, from.coerceIn(0, sentence.length))
        val start = if (at >= 0) at else from.coerceIn(0, sentence.length)
        out += Segment(text, start, start + text.length, kind, marker)
    }

    private fun buildClause(segment: Segment, index: Int, depth: Int): Clause {
        val tokens = Tokenizer.words(segment.text)
        val lower = tokens.map { it.lowercase() }
        val offsets = wordOffsets(segment.text, tokens)

        var verbIndex = -1
        for (i in lower.indices) {
            if (isFiniteVerb(lower, i)) {
                verbIndex = i
                break
            }
        }

        // Include leading auxiliaries in the verb phrase.
        var verbStart = verbIndex
        while (verbStart > 0 && lower[verbStart - 1] in AUXILIARIES) verbStart--

        val subject: String
        val verb: String
        val rest: String
        when {
            verbIndex < 0 -> {
                subject = ""
                verb = ""
                rest = segment.text
            }
            else -> {
                val verbEnd = verbPhraseEnd(segment.text, tokens, offsets, verbStart)
                val subjectEnd = offsets.getOrElse(verbStart) { 0 }
                val rawSubject = stripLeadingMarker(
                    trimToLastBoundary(segment.text.substring(0, subjectEnd)),
                    segment.marker,
                )
                val afterVerb = segment.text.substring(verbEnd).trim().trimStart(',', ';', ':').trim()
                // "We're expecting…" opens with a contraction that carries its own
                // subject, so there is no text before the verb to read it from; the
                // subject has to come out of the word, and the verb phrase is the
                // expansion ("are expecting") rather than the contraction alone.
                val hidden = PartOfSpeechParser.contraction(tokens.getOrNull(verbStart).orEmpty())
                if (hidden?.subject != null && rawSubject.isBlank()) {
                    val tail = segment.text.substring(offsets[verbStart] + tokens[verbStart].length, verbEnd)
                    // A contraction opening the sentence capitalises its subject.
                    subject = if (verbStart == 0) hidden.subject.replaceFirstChar { it.uppercase() }
                    else hidden.subject
                    verb = (hidden.expansion + tail).trim()
                    rest = afterVerb
                } else {
                    subject = rawSubject
                    verb = segment.text.substring(offsets[verbStart], verbEnd).trim()
                    rest = afterVerb
                }
            }
        }

        return Clause(
            kind = segment.kind,
            marker = segment.marker,
            text = segment.text,
            subject = subject.trim(),
            verb = verb.trim(),
            rest = rest,
            start = segment.start,
            end = segment.end,
            depth = depth,
        )
    }

    /** Walks forward from the first verb over auxiliaries, adverbs and particles. */
    private fun verbPhraseEnd(
        text: String,
        tokens: List<String>,
        offsets: List<Int>,
        verbStart: Int,
    ): Int {
        var i = verbStart
        var end = offsets[i] + tokens[i].length
        var i2 = i + 1
        while (i2 < tokens.size) {
            val w = tokens[i2].lowercase()
            // A contraction that carries its own subject opens a new clause — in
            // "the minister said it's a problem", `it's` is the subject and verb of
            // what was said, not a tail of `said`.
            if (PartOfSpeechParser.contraction(w)?.subject != null) break
            val isVerbLike = w in AUXILIARIES || posOf(w) == PartOfSpeech.Verb
            val isNegation = w == "not" || w == "n't" || w == "never"
            val isAdverb = posOf(w) == PartOfSpeech.Adverb && i2 == i + 1
            if (isVerbLike || isNegation || isAdverb) {
                end = offsets[i2] + tokens[i2].length
                i2++
                i = i2 - 1
            } else break
        }
        return end
    }

    private fun stripLeadingMarker(text: String, marker: String): String {
        var out = text.trim()
        if (marker.isNotEmpty()) {
            val m = marker.substringBefore(' ')
            if (out.lowercase().startsWith(m.lowercase())) {
                out = out.substring(m.length).trimStart(',', ' ', ':')
            }
        }
        return out
    }

    /**
     * Keeps only what follows the last clause-level punctuation, so a colon or
     * semicolon inside the segment does not leak into the subject: in
     * "he was good-looking: he had a pleasant countenance" the subject is `he`,
     * not `he was good-looking: he`.
     */
    private fun trimToLastBoundary(text: String): String {
        val cut = text.lastIndexOfAny(charArrayOf(':', ';', '\u2014'))
        return if (cut >= 0) text.substring(cut + 1).trim() else text.trim()
    }

    // ------------------------------------------------------------- rendering

    private fun backboneOf(clause: Clause): String = buildString {
        if (clause.subject.isNotBlank()) append(clause.subject)
        if (clause.verb.isNotBlank()) {
            if (isNotEmpty()) append(' ')
            append(clause.verb)
        }
        val obj = objectOf(clause)
        if (obj.isNotBlank()) {
            if (isNotEmpty()) append(' ')
            append(obj)
        }
    }.trim().ifBlank { clause.text }

    /** The object or complement: the head noun phrase right after the verb. */
    private fun objectOf(clause: Clause): String {
        val rest = clause.rest
        if (rest.isBlank()) return ""
        val cut = rest.indexOfFirst { it == ',' || it == ';' || it == ':' }
        val head = if (cut > 0) rest.substring(0, cut) else rest
        // The backbone is a fragment, so the sentence's own terminator is not part
        // of it: "We are expecting to hear from the minister", not "…minister."
        return head.trim().split(' ').take(8).joinToString(" ").trimEnd('.', '!', '?', '\u2026')
    }

    /**
     * Splits the main clause into teachable chunks. Boundaries are placed before
     * prepositions, subordinators and relative pronouns, which is where a reader
     * naturally pauses.
     */
    private fun chunk(clause: Clause): List<Chunk> {
        val tokens = Tokenizer.words(clause.text)
        if (tokens.isEmpty()) return emptyList()
        val lower = tokens.map { it.lowercase() }
        val offsets = wordOffsets(clause.text, tokens)

        val boundaries = mutableListOf(0)
        // A contraction at the very start hides its own subject ("We're"), so the
        // first token is a chunk of its own. Without the split the whole clause came
        // back as one predicate chunk and the colour line contradicted the 主语/谓语
        // fields printed above it.
        val leadingContraction = offsets.firstOrNull() == 0 &&
            PartOfSpeechParser.contraction(tokens.first())?.subject != null
        if (leadingContraction) boundaries += 1
        for (i in 1 until tokens.size) {
            val w = lower[i]
            // A comma cannot be a token, so a phrase boundary after one has to be
            // read from the raw clause text.
            var before = offsets[i] - 1
            while (before >= 0 && clause.text[before].isWhitespace()) before--
            val afterComma = before >= 0 && clause.text[before] == ','
            val startsPhrase = w in RELATIVE_PRONOUNS ||
                posOf(w) == PartOfSpeech.Preposition ||
                afterComma
            if (startsPhrase) boundaries += i
        }
        boundaries += tokens.size

        val verbIndex = lower.indices.firstOrNull { isFiniteVerb(lower, it) } ?: -1
        // The verb is where the predicate begins, so it is always a chunk edge.
        if (verbIndex > 0) boundaries += verbIndex
        // With the contraction split off, the predicate is what follows it.
        val predicateStart = if (leadingContraction) 1 else verbIndex
        val sorted = boundaries.distinct().sorted()

        return sorted.zipWithNext().mapNotNull { (from, to) ->
            if (from >= to) return@mapNotNull null
            val start = offsets[from]
            val end = offsets[to - 1] + tokens[to - 1].length
            val text = clause.text.substring(start, end).trim()
            if (text.isEmpty()) return@mapNotNull null
            val role = when {
                leadingContraction && from == 0 -> ChunkRole.Subject
                predicateStart in from until to -> ChunkRole.Predicate
                from == 0 -> ChunkRole.Subject
                lower[from] in RELATIVE_PRONOUNS -> ChunkRole.Attributive
                // A prepositional phrase is adverbial even when it follows the verb:
                // in "expecting to hear from the minister" the object is "to hear"
                // and "from the minister" tells you where, not what. An infinitive
                // marker is not a preposition, though — `to hear` is the object.
                isPrepositionalPhrase(lower, from) -> ChunkRole.Adverbial
                verbIndex >= 0 && from > verbIndex -> ChunkRole.Object
                else -> ChunkRole.Other
            }
            Chunk(text, role, start, end)
        }
    }

    /** True when the chunk opening at [from] is a prepositional phrase, not an infinitive. */
    private fun isPrepositionalPhrase(lower: List<String>, from: Int): Boolean {
        val w = lower.getOrNull(from) ?: return false
        if (posOf(w) != PartOfSpeech.Preposition) return false
        // `to` before a verb is the infinitive marker: "to hear" is a complement of
        // the verb before it, not an adverbial.
        return !(w == "to" && isVerb(lower.getOrNull(from + 1).orEmpty()))
    }

    private fun buildNotes(
        sentence: String,
        clauses: List<Clause>,
        main: Clause?,
    ): List<GrammarNote> {
        val notes = mutableListOf<GrammarNote>()
        if (main != null && main.subject.isNotBlank()) {
            notes += GrammarNote(
                "句子主干",
                "主语「${main.subject}」 + 谓语「${main.verb}」，先抓住这一层意思，其余成分都是它的修饰或补充。",
            )
        }
        clauses.filter { it.kind != ClauseKind.Main }.forEach { clause ->
            val detail = when (clause.kind) {
                ClauseKind.Relative -> {
                    val ref = referenceBefore(sentence, clause.start)
                    "${clause.marker} 引导定语从句，修饰前面的「$ref」，在从句中充当${roleInClause(clause)}。"
                }
                ClauseKind.Adverbial -> {
                    "${clause.marker} 引导状语从句，说明主句的${adverbialSense(clause.marker)}；从句自带主语「${clause.subject.ifBlank { "（省略）" }}」。"
                }
                ClauseKind.Nominal -> {
                    "${clause.marker} 引导名词性从句，整体在主句中作成分，不能单独成句。"
                }
                ClauseKind.NonFinite -> {
                    "「${clause.marker}」为非谓语结构，没有自己的主语，逻辑主语与主句一致，在这里作${nonFiniteSense(clause.marker)}。"
                }
                ClauseKind.Coordinated -> when (clause.marker) {
                    ":" -> "冒号后面是一个独立分句，用来说明或展开前半句，读的时候可以当成两句话处理。"
                    ";" -> "分号连接两个地位相同的独立分句，前后各自成句。"
                    else -> "${clause.marker} 连接并列分句，与前面的句子地位相同，可单独成句理解。"
                }
                ClauseKind.Parenthetical -> "插入语，补充说明，阅读时可以先跳过去。"
                ClauseKind.Main -> ""
            }
            if (detail.isNotEmpty()) {
                notes += GrammarNote(clause.kind.label, detail)
            }
        }
        if (clauses.size >= 3) {
            notes += GrammarNote(
                "拆句顺序",
                "全句共 ${clauses.size} 个分句。先读主句，再回到每个从句，按「主干 → 修饰 → 补充」的顺序还原。",
            )
        }
        return notes
    }

    private fun roleInClause(clause: Clause): String = when {
        clause.verb.isBlank() -> "成分"
        clause.rest.isBlank() -> "主语"
        else -> "主语或宾语"
    }

    private fun adverbialSense(marker: String): String = when (marker.lowercase()) {
        "because", "since", "as", "for" -> "原因"
        "although", "though", "even though", "even if", "while", "whereas" -> "让步或对比"
        "if", "unless", "provided", "provided that", "in case", "supposing" -> "条件"
        "when", "while", "as", "after", "before", "until", "till", "once",
        "as soon as", "the moment", "by the time", "whenever" -> "时间"
        "where", "wherever" -> "地点"
        "so that", "in order that", "lest" -> "目的"
        "so", "such", "that", "than" -> "结果或比较"
        else -> "背景"
    }

    private fun nonFiniteSense(marker: String): String = when {
        marker.startsWith("to") -> "目的或结果状语"
        marker.endsWith("ing") -> "伴随、原因或定语"
        else -> "状语或定语"
    }

    /** The noun phrase immediately before a relative clause, i.e. its antecedent. */
    private fun referenceBefore(sentence: String, clauseStart: Int): String {
        val head = sentence.substring(0, clauseStart.coerceAtLeast(0))
        val words = Tokenizer.words(head)
        if (words.isEmpty()) return "前面提到的内容"
        return words.takeLast(3).joinToString(" ")
    }

    // ---------------------------------------------------------------- lexicon

    private fun isFiniteVerb(lower: List<String>, index: Int): Boolean {
        val w = lower.getOrNull(index) ?: return false
        if (w in AUXILIARIES) return true
        // "We're", "it's", "won't": a verb written with an apostrophe, which no
        // dictionary entry or suffix rule reaches.
        if (PartOfSpeechParser.contractionPos(w) == PartOfSpeech.Verb) return true
        if (index > 0 && lower[index - 1] == "to") return false
        if (w.endsWith("ing")) return false
        return posOf(w) == PartOfSpeech.Verb
    }

    private fun isVerb(word: String): Boolean =
        word in AUXILIARIES ||
            PartOfSpeechParser.contractionPos(word) == PartOfSpeech.Verb ||
            posOf(word) == PartOfSpeech.Verb

    /**
     * Part of speech for a word: contractions, then the dictionary, then suffix
     * heuristics. Results are cached because a long article re-asks about the same
     * words.
     */
    private fun posOf(word: String): PartOfSpeech {
        val key = Tokenizer.normalize(word)
        if (key.isEmpty()) return PartOfSpeech.Unknown
        posCache[key]?.let { return it }
        // Before the dictionary: `we're` normalises to `we're` and misses every
        // entry, and the stem (`we`) is a pronoun rather than the verb in play.
        val pos = PartOfSpeechParser.contractionPos(key) ?: run {
            val entry = dictionary.lookup(key)
            if (entry != null && entry.translation.isNotBlank()) {
                val gloss = entry.translation.split("\\n", "\n").firstOrNull { it.isNotBlank() }.orEmpty()
                PartOfSpeechParser.parse(gloss).takeIf { it != PartOfSpeech.Unknown }
                    ?: PartOfSpeechParser.guess(key)
            } else {
                PartOfSpeechParser.guess(key)
            }
        }
        posCache[key] = pos
        return pos
    }

    /** Character offset of each token in the original string, in token order. */
    private fun wordOffsets(text: String, tokens: List<String>): List<Int> {
        val offsets = ArrayList<Int>(tokens.size)
        var cursor = 0
        for (token in tokens) {
            val at = text.indexOf(token, cursor)
            val resolved = if (at >= 0) at else cursor
            offsets += resolved
            cursor = resolved + token.length
        }
        return offsets
    }
}
