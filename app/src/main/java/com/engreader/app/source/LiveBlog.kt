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

    private val TITLE_MARKERS = listOf(
        "– live", "— live", "- live",
        "– latest", "— latest",
        "live updates", "latest updates", "live blog", "liveblog",
        "as it happened", "rolling coverage",
    )

    private val URL_MARKERS = listOf("/live/", "live-blog", "liveblog", "/live-", "-live/")

    /** Punctuation that sets a coverage label apart from the headline itself. */
    private val SEPARATORS = listOf("\u2013", "\u2014", "-", ":", "|", ",")

    fun isLive(title: String, url: String = ""): Boolean {
        val t = title.lowercase().trim()
        if (endsWithLiveLabel(t)) return true
        if (TITLE_MARKERS.any { t.contains(it) }) return true
        val u = url.lowercase()
        return URL_MARKERS.any { u.contains(it) }
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
