package com.engreader.app.nlp

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One frequency band of the test, as a range of corpus ranks.
 *
 * [from] and [to] are inclusive COCA ranks — rank 1 is the most frequent word in
 * English — and [width] is how many words the band claims. The bands widen as they
 * descend because the dictionary's own coverage thins out there and a narrow band
 * would hold too few words to sample.
 */
data class VocabBand(val index: Int, val from: Int, val to: Int, val label: String) {
    val width: Int get() = to - from + 1
}

/** One word put to the reader, with the band it was drawn from. */
data class VocabProbe(val word: String, val band: Int)

/**
 * What the answers add up to.
 *
 * [low] and [high] are a 95% interval, and they are wide on purpose: sixty-four
 * yes/no answers cannot pin a vocabulary down to a single number, and a test that
 * reported only [estimate] would be claiming a precision it does not have.
 */
data class VocabEstimate(
    val estimate: Int,
    val low: Int,
    val high: Int,
    val sampled: Int,
    val known: Int,
)

/**
 * Estimates a reader's vocabulary size from a frequency-band sampling test.
 *
 * The method is the one Nation's Vocabulary Size Test uses: take a handful of words
 * from each frequency band, ask whether the reader knows each, and scale the known
 * fraction of a band up to the band's whole width. Summing the bands gives the number
 * of words the reader is estimated to know among the 50,000 most frequent.
 *
 * Two caveats are built into the presentation rather than hidden. The bands are
 * assumed independent and each band binomial, so the interval is narrower than the
 * truth — real knowledge is clustered, and a reader who knows one word of a band
 * often knows its neighbours. And a self-report counts a word as known when the
 * reader recognises it, which over-counts: recognition is easier than recall, and a
 * word met once feels familiar.
 *
 * Pure logic: the words come from the dictionary through the repository, and the
 * arithmetic here is what the tests pin down.
 */
object VocabularySize {

    /**
     * The bands, most frequent first.
     *
     * They stop at 50,000 because that is where the bundled dictionary stops being
     * usable as a population: past it, ECDICT holds mostly technical and archaic words
     * a reader is not expected to know, and scaling a sample of those up would inflate
     * the estimate rather than inform it.
     */
    val BANDS: List<VocabBand> = listOf(
        VocabBand(0, 1, 1000, "前 1000"),
        VocabBand(1, 1001, 2000, "1000–2000"),
        VocabBand(2, 2001, 3000, "2000–3000"),
        VocabBand(3, 3001, 5000, "3000–5000"),
        VocabBand(4, 5001, 8000, "5000–8000"),
        VocabBand(5, 8001, 12000, "8000–12000"),
        VocabBand(6, 12001, 20000, "12000–20000"),
        VocabBand(7, 20001, 50000, "20000–50000"),
    )

    /** Words asked per band. Eight across eight bands is 64 questions, about two minutes. */
    const val PER_BAND = 8

    /** 1.96 standard errors either way, the usual normal approximation to 95%. */
    private const val Z = 1.96

    /** The population the estimate covers, and the ceiling on the interval. */
    private val POPULATION = 50_000

    /**
     * Which words to take from a band, as offsets into its frequency-ordered list.
     *
     * Spread rather than adjacent: eight words in a row share a frequency, and a
     * reader who knows one of them very likely knows the rest, so a contiguous run
     * would behave like a single question asked eight times. The stride walks the band
     * and the seeded jitter stops two tests with the same band size from asking the
     * same words — the seed is the test's start time, so a retest does not measure
     * memory of the previous one.
     */
    fun offsets(seed: Long, band: VocabBand, available: Int, count: Int = PER_BAND): List<Int> {
        if (available <= 0 || count <= 0) return emptyList()
        val take = minOf(count, available)
        val rng = Random(seed * 1_000_003L + band.index)
        val stride = available / take
        val out = LinkedHashSet<Int>()
        for (i in 0 until take) {
            val base = i * stride
            val jitter = if (stride > 1) rng.nextInt(stride) else 0
            out += (base + jitter).coerceIn(0, available - 1)
        }
        // Distinct can collapse a couple of offsets in a band barely wider than the
        // sample; the missing ones are filled from the front rather than dropped.
        var fill = 0
        while (out.size < take) out += fill++
        return out.sorted()
    }

    /**
     * Scales the per-band hit rates up to the band widths.
     *
     * A band with no answers is skipped rather than treated as zero known: an
     * unanswerable band is missing data, and counting it as "knows none" would drag
     * the estimate down for a reason the reader never gave.
     */
    fun estimate(knownPerBand: List<Int>, sampledPerBand: List<Int>): VocabEstimate {
        var total = 0.0
        var variance = 0.0
        var certain = 0.0
        var sampled = 0
        var known = 0
        BANDS.forEachIndexed { index, band ->
            val n = sampledPerBand.getOrElse(index) { 0 }
            if (n <= 0) return@forEachIndexed
            val k = knownPerBand.getOrElse(index) { 0 }.coerceIn(0, n)
            sampled += n
            known += k
            val p = k.toDouble() / n
            total += p * band.width
            // Agresti–Coull: the raw `p(1-p)` is exactly zero when a band is unanimous,
            // so eight "yes" answers would report an interval of zero width — certainty
            // that eight questions cannot support, and a "0–0" for a reader who simply
            // said no to everything. Two pseudo-observations either way keep the
            // variance positive and put a unanimous band on the same footing as a mixed
            // one, which is what the point estimate already assumes.
            val adjusted = (k + 2.0) / (n + 4.0)
            variance += band.width.toDouble() * band.width * adjusted * (1 - adjusted) / (n + 4.0)
            // A band the reader aced is evidence they know most of it, so the interval
            // never starts below what those bands alone account for — otherwise a reader
            // who answered yes to all eight of the top 1,000 could be told they might
            // know nothing at all.
            if (k == n) certain += adjusted * band.width
        }
        val estimate = total.roundToInt()
        val margin = (Z * sqrt(variance)).roundToInt()
        return VocabEstimate(
            estimate = estimate,
            low = maxOf(estimate - margin, certain.roundToInt()).coerceIn(0, estimate),
            high = (estimate + margin).coerceIn(estimate, POPULATION),
            sampled = sampled,
            known = known,
        )
    }

    /** The same, from the answered probes rather than pre-counted bands. */
    fun estimate(answers: List<Pair<VocabProbe, Boolean>>): VocabEstimate {
        val sampled = IntArray(BANDS.size)
        val known = IntArray(BANDS.size)
        answers.forEach { (probe, isKnown) ->
            if (probe.band !in BANDS.indices) return@forEach
            sampled[probe.band]++
            if (isKnown) known[probe.band]++
        }
        return estimate(known.toList(), sampled.toList())
    }
}
