package com.engreader.app.nlp

import kotlin.math.abs
import kotlin.math.roundToInt

/** How well one article fits the reader's vocabulary as it stands. */
data class Readiness(val id: Long, val unknown: Int, val total: Int) {
    /**
     * Share of the article's study-worthy lemmas the reader does not know yet.
     *
     * Over study-worthy lemmas rather than over every word: function words and proper
     * nouns are known to anyone who can read the sentence at all, and counting them
     * would make every text look easy.
     */
    val rate: Double get() = if (total <= 0) 0.0 else unknown.toDouble() / total
}

/** Which side of the comfort zone a text falls on. */
enum class Fit(val label: String, val chip: String) {
    Easy("偏易", "i+1 · 偏易"),
    Comfort("刚好", "i+1 · 刚好"),

    /**
     * The best on offer, not an i+1: when every article is past the comfort zone the
     * pick is the closest approach to it, and labelling that "i+1" would contradict
     * the sentence underneath it.
     */
    Hard("偏难", "偏难 · 最接近"),
}

/**
 * Picks the article nearest the reader's "i + 1".
 *
 * Krashen's input hypothesis in one line: a text teaches when the reader already knows
 * almost all of it. Too easy and there is nothing to acquire; too hard and the reader
 * is decoding rather than reading. This turns that into a choice over the articles on
 * the shelf, using the unknown rate [VocabularyProfile] already computes for each one.
 *
 * Pure logic over counts: the profiling itself needs the dictionary, so it stays in the
 * repository and this only decides between the results.
 */
object AdaptiveReader {

    /**
     * The unknown rate to aim for — roughly 95% of the study-worthy lemmas known.
     *
     * The window either side is wide because the rate is computed over lemmas, not
     * running words, and a reader's tolerance varies with how tired they are.
     */
    const val COMFORT_RATE = 0.05

    /** Under this there is almost nothing left to learn from the text. */
    const val EASY_BELOW = 0.02

    /** Over this the reader spends more time looking words up than reading. */
    const val HARD_ABOVE = 0.08

    /**
     * Below this many study-worthy lemmas a rate is noise.
     *
     * A twenty-word caption with one unknown word reads as 5% — apparently perfect —
     * but says nothing about the reader. Matches [VocabularyProfile.MIN_PROFILED_LEMMAS]
     * so the badge and the recommendation cannot disagree about what is profiled.
     */
    const val MIN_LEMMAS = VocabularyProfile.MIN_PROFILED_LEMMAS

    fun fit(rate: Double): Fit = when {
        rate < EASY_BELOW -> Fit.Easy
        rate <= HARD_ABOVE -> Fit.Comfort
        else -> Fit.Hard
    }

    /**
     * The article closest to i+1, or null when nothing is long enough to judge.
     *
     * Closest by distance to [COMFORT_RATE] rather than "the easiest": a text the
     * reader already knows everything in teaches nothing, so the next one up is the
     * better suggestion. Ties go to the shorter unknown list and then to the lower id,
     * so the same library always yields the same pick.
     */
    fun pick(candidates: List<Readiness>): Readiness? =
        candidates.filter { it.total >= MIN_LEMMAS }
            .minWithOrNull(
                compareBy({ abs(it.rate - COMFORT_RATE) }, { it.unknown }, { it.id }),
            )

    /**
     * The lemmas to treat as known from the reader's estimated vocabulary size alone.
     *
     * Without this a fresh install has no wordbook at all, so every article reads as
     * 100% unknown and "closest to i+1" collapses into "shortest". The estimate from
     * the sampling test is a corpus rank — "this reader knows about the top 5,000
     * words" — so the words at or above that rank are the ones such a reader is
     * expected to know. It is a baseline, not evidence: the wordbook's own known set
     * is unioned on top of it, so a word the reader has actually demonstrated still
     * counts even when it sits below the rank.
     *
     * A lemma with no frequency data ranks 0 and is never assumed known, because the
     * dictionary not knowing how common a word is says nothing about the reader.
     */
    fun baselineKnown(lemmas: List<String>, estimate: Int?, rankOf: (String) -> Int): Set<String> {
        if (estimate == null || estimate <= 0) return emptySet()
        return lemmas.filter { rankOf(it) in 1..estimate }.toSet()
    }

    /**
     * One sentence saying why this article, in the reader's language.
     *
     * Written for the article [pick] returned: the "closest in the list" wording in the
     * hard case is a claim about that choice, and would be wrong on any other article.
     */
    fun reason(ready: Readiness): String {
        val percent = (ready.rate * 100).roundToInt()
        return when (fit(ready.rate)) {
            Fit.Easy ->
                "生词只有 ${ready.unknown} 个（约 $percent%），这篇读起来很轻松，适合先热身。"
            Fit.Comfort ->
                "生词 ${ready.unknown} 个（约 $percent%），${100 - percent}% 的词你已经认识，" +
                    "正好是「i+1」的难度。"
            Fit.Hard ->
                "生词 ${ready.unknown} 个（约 $percent%），是当前列表里离你水平最近的一篇；" +
                    "再难就会一直在查词。"
        }
    }
}
