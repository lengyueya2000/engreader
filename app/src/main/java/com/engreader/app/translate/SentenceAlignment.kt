package com.engreader.app.translate

import kotlin.math.abs

/**
 * Lines a paragraph's Chinese translation up with its English sentences.
 *
 * The translation is fetched a paragraph at a time on purpose: one request per
 * paragraph keeps the request count down, and handing the engine the whole paragraph
 * as context reads better than sending sentences one by one — asked for one sentence
 * at a time, the engine turns `Mr.` into a sentence of its own and drops the
 * cohesion between clauses. The reader then wants the result per sentence, so the
 * Chinese can sit directly under the English it belongs to, and the two have to line
 * up: a piece shown under the wrong sentence is worse than no pairing at all.
 *
 * Machine translation does not preserve sentence count. Asked for a paragraph it
 * re-breaks it — it splits an English sentence around a quotation mark, or joins two
 * short ones — so counting Chinese full stops and demanding the same number as the
 * English only works about three paragraphs in five on a real novel. The rest used to
 * fall back to a whole-paragraph translation, which is what a reader sees as "the
 * sentence translation is not working".
 *
 * So there are two attempts. An exact count match is taken as-is, because when the
 * two agree there is nothing to decide. When they disagree, the Chinese is cut at its
 * clause separators and distributed over the English sentences in proportion to how
 * much of the paragraph each one carries, which is a strong signal about which
 * Chinese clause belongs to which English sentence. The distribution is rejected
 * outright when any one sentence ends up with a share of the Chinese far from its
 * share of the English: that is the shape of a paragraph the translator rewrote
 * rather than re-broke, and a guess there is worse than the fallback.
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

    /**
     * Marks that separate clauses within a sentence.
     *
     * Only used when the sentence counts disagree. Cutting here is what lets one
     * Chinese sentence supply two English ones, or two supply one; the cuts are held
     * apart from [TERMINATORS] so that a cut at a full stop is preferred to a cut at a
     * comma, all else being equal.
     */
    private const val SEPARATORS = "\uFF0C\uFF1B\uFF1A\u3001,;:"

    /** Marks that close the sentence they follow rather than opening the next one. */
    private const val CLOSERS = "\u300D\u300F\u201D\u2019\uFF09)\u3011\u300B]"

    /**
     * How far one sentence's share of the Chinese may stray from its share of the
     * English before the whole distribution is dropped, as a fraction of the paragraph.
     *
     * Measured over seventy multi-sentence paragraphs of a real novel: the groupings a
     * reader would call correct sit at a median deviation of 0.04 and a ninetieth
     * percentile of 0.08. A relative measure was tried first and rejected: a two-word
     * sentence owns a tiny share of the paragraph, so the same absolute error reads as
     * a huge relative one and the noise alone pushed good groupings over the line.
     */
    private const val TOLERANCE = 0.12

    /**
     * One Chinese piece per entry in [englishSentences], or null when no split of the
     * translation can be trusted to line up with them.
     */
    fun align(englishSentences: List<String>, chinese: String): List<String>? {
        if (englishSentences.isEmpty()) return null
        val text = chinese.trim()
        if (text.isEmpty()) return null
        // A one-sentence paragraph has nothing to line up with, and a translation that
        // dropped its final full stop would otherwise be rejected for no reason.
        if (englishSentences.size == 1) return listOf(text)
        // When the translator kept the sentence count there is nothing to decide, and
        // taking this path first means the proportional fit below can never change an
        // answer that was already right.
        val exact = splitSentences(text)
        if (exact.size == englishSentences.size) return exact
        return proportional(englishSentences, text)
    }

    /** Splits at sentence terminators only. */
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

    /** A Chinese clause, and whether it ends a sentence rather than a phrase. */
    private class Atom(val start: Int, val end: Int, val endsSentence: Boolean)

    /** Cuts the text at every terminator and separator, keeping the mark on its clause. */
    private fun atoms(text: String): List<Atom> {
        val out = mutableListOf<Atom>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || TERMINATORS.indexOf(c) >= 0 || SEPARATORS.indexOf(c) >= 0) {
                var end = i + 1
                while (end < text.length && TERMINATORS.indexOf(text[end]) >= 0) end++
                while (end < text.length && CLOSERS.indexOf(text[end]) >= 0) end++
                var from = start
                while (from < end && text[from].isWhitespace()) from++
                var to = end
                while (to > from && text[to - 1].isWhitespace()) to--
                if (to > from) out += Atom(from, to, TERMINATORS.indexOf(c) >= 0 || c == '\n')
                start = end
                i = end
                continue
            }
            i++
        }
        var from = start
        while (from < text.length && text[from].isWhitespace()) from++
        var to = text.length
        while (to > from && text[to - 1].isWhitespace()) to--
        if (to > from) out += Atom(from, to, true)
        return out
    }

    /**
     * Gives each English sentence a run of consecutive Chinese clauses.
     *
     * The cost of a distribution is how far each sentence's share of the Chinese text
     * strays from its share of the English, plus a charge for every cut that lands
     * inside a Chinese sentence rather than on its full stop. Shares rather than
     * sentence counts, because the two languages disagree about how long a sentence
     * is: a Chinese translation of an English sentence runs to about two thirds the
     * character count, and that ratio is stable enough across a paragraph to say where
     * one sentence's text ends and the next one's begins.
     *
     * The run has to be contiguous and non-empty, which is what makes this a fit to
     * the shape of a translation rather than a bag-of-clauses guess: a group can only
     * be wrong by taking a clause from its neighbour.
     */
    private fun proportional(english: List<String>, text: String): List<String>? {
        val units = atoms(text)
        val n = english.size
        val m = units.size
        // Fewer Chinese clauses than English sentences: something has to be left empty,
        // so there is nothing to show under at least one sentence.
        if (m < n) return null
        val enTotal = english.sumOf { it.length.toDouble() }
        val zhTotal = units.sumOf { (it.end - it.start).toDouble() }
        if (enTotal <= 0.0 || zhTotal <= 0.0) return null

        val enPrefix = DoubleArray(n + 1)
        english.forEachIndexed { i, s -> enPrefix[i + 1] = enPrefix[i] + s.length }
        val zhPrefix = DoubleArray(m + 1)
        units.forEachIndexed { j, a -> zhPrefix[j + 1] = zhPrefix[j] + (a.end - a.start) }

        // best[i][j]: the cheapest way to cover the first i sentences with the first j
        // clauses. A cut before clause k is free when k is 0 or clause k-1 ended a
        // sentence, and costs a fraction otherwise.
        val unreachable = Double.MAX_VALUE / 4
        val best = Array(n + 1) { DoubleArray(m + 1) { unreachable } }
        val from = Array(n + 1) { IntArray(m + 1) }
        best[0][0] = 0.0
        for (i in 1..n) {
            for (j in i..m) {
                var cost = unreachable
                var cut = 0
                for (k in (i - 1) until j) {
                    if (best[i - 1][k] >= unreachable) continue
                    val zhShare = (zhPrefix[j] - zhPrefix[k]) / zhTotal
                    val enShare = (enPrefix[i] - enPrefix[i - 1]) / enTotal
                    val splitCost = if (k == 0 || units[k - 1].endsSentence) 0.0 else SPLIT_PENALTY
                    val candidate = best[i - 1][k] + abs(zhShare - enShare) + splitCost
                    if (candidate < cost) {
                        cost = candidate
                        cut = k
                    }
                }
                best[i][j] = cost
                from[i][j] = cut
            }
        }
        if (best[n][m] >= unreachable) return null

        val cuts = IntArray(n + 1)
        var j = m
        for (i in n downTo 1) {
            cuts[i] = j
            j = from[i][j]
        }
        cuts[0] = 0

        val groups = (0 until n).map { i ->
            // A cut inside a Chinese sentence leaves that sentence's own comma at the
            // end of the piece before it, where it separates nothing; the clause it
            // introduced continues in the next piece, so the mark moves with it.
            var last = units[cuts[i + 1] - 1].end
            if (cuts[i + 1] < m) {
                while (last > units[cuts[i]].start && SEPARATORS.indexOf(text[last - 1]) >= 0) last--
            }
            text.substring(units[cuts[i]].start, last)
        }

        val worst = groups.indices.maxOf { i ->
            abs(groups[i].length / zhTotal - english[i].length / enTotal)
        }
        return groups.takeIf { worst <= TOLERANCE }
    }

    /**
     * What a cut inside a Chinese sentence costs, in the same units as the share
     * deviation it competes with.
     *
     * A cut at a full stop is what the translator chose, so it is preferred; a cut at a
     * comma is only made when the shares say the boundary has to be there. Set too low
     * the fit happily breaks a Chinese sentence in two to shave a hundredth off a
     * deviation — on the corpus that is fifteen spurious cuts instead of seven, and
     * sentences like `两周的熟识肯定是很少的。两周结束后` shown under one English
     * sentence. Set at 0.2 or above the fit refuses cuts it needs and one paragraph in
     * the corpus drops out. Measured over the corpus, 0.1 is where the two meet.
     */
    private const val SPLIT_PENALTY = 0.1
}
