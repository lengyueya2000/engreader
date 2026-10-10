package com.engreader.app.source

/**
 * Recognises rolling live coverage from a headline or URL.
 *
 * Live blogs are a stream of short timestamped updates rather than an article: the
 * paragraphs do not connect, so they are poor material for practising whole-text
 * reading even though they are fine for look-ups and sentence breakdowns. Feeds
 * label them inconsistently — the Guardian appends "– live", "– latest updates" or
 * "| live", the BBC uses "/live/" in the path — so several markers are needed.
 */
object LiveBlog {

    /**
     * Phrases that are a label in their own right, matched on word boundaries.
     *
     * A bare `contains` matched inside longer words: "- live" is a prefix of
     * "- Liverpool", so "Man Utd - Liverpool" and "The Beatles - Live at the BBC"
     * were both read as rolling coverage. The dash labels are not listed here at all —
     * a trailing "– live" is [endsWithLiveLabel]'s job, and matching it anywhere in
     * the title is exactly what flagged a band name.
     */
    private val TITLE_REGEX = listOf(
        "latest updates", "live updates", "live blog", "liveblog",
        "as it happened", "rolling coverage",
    ).map { bounded(it) }

    /**
     * URL shapes, anchored to a path segment.
     *
     * Anchoring is what keeps an unrelated path out: "olive-blog" has no `/` before
     * `live` and "deliver-live/" has `-`, so neither is a `live` segment. The plain
     * `contains` check flagged both.
     */
    private val URL_REGEX = listOf(
        Regex("(?:^|/)live(?![A-Za-z0-9])"),
        Regex("(?:^|/)live[-_]"),
        Regex("(?:^|/)liveblog(?![A-Za-z0-9])"),
    )

    /** Punctuation that sets a coverage label apart from the headline itself. */
    private val SEPARATORS = listOf("\u2013", "\u2014", "-", ":", "|", ",")

    private fun bounded(marker: String) =
        Regex("(?<![A-Za-z0-9])${Regex.escape(marker)}(?![A-Za-z0-9])")

    fun isLive(title: String, url: String = ""): Boolean {
        val t = title.lowercase().trim()
        if (endsWithLiveLabel(t)) return true
        if (TITLE_REGEX.any { it.containsMatchIn(t) }) return true
        val u = url.lowercase()
        return URL_REGEX.any { it.containsMatchIn(u) }
    }

    /**
     * True when a trailing "live" reads as a coverage label rather than as the verb.
     *
     * Matching the bare letters flagged "The will to live" and "How to live"; matching
     * a trailing word still does, because "to live" ends with the word "live". A real
     * label is set off by punctuation ("… protests – live") or stands after a noun
     * ("Election results live"), so an infinitive's `to` is what rules it out.
     */
    private fun endsWithLiveLabel(t: String): Boolean {
        if (!t.endsWith("live")) return false
        val head = t.dropLast("live".length).trimEnd()
        if (head.isEmpty()) return false
        if (SEPARATORS.any { head.endsWith(it) }) return true
        val before = head.substringAfterLast(' ').trim()
        return before.isNotEmpty() && before != "to"
    }
}
