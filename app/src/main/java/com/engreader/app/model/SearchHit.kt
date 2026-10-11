package com.engreader.app.model

/**
 * One paragraph that contains what the reader searched for.
 *
 * [paragraphIndex] is the paragraph's number inside its own chapter, which is what the
 * reader scrolls to: the list holds one item per paragraph, so the number is a position
 * and not just a label.
 */
data class SearchHit(
    val articleId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val paragraphIndex: Int,
    /** The whole paragraph, so the sheet can show the match in its own sentence. */
    val text: String,
) {
    /** A short window around the first match, with an ellipsis where text was cut. */
    fun snippet(query: String, radius: Int = SNIPPET_RADIUS): String = snippetOf(text, query, radius)

    companion object {
        /** Characters of context kept on each side of the match. */
        const val SNIPPET_RADIUS = 70

        /**
         * The part of [text] worth showing for [query].
         *
         * A whole paragraph is often several lines, and the match can sit in the middle
         * of it; a window around the match says more in one line of a result list than
         * the paragraph's opening does. Matching is case-insensitive because that is how
         * the search itself matches.
         */
        fun snippetOf(text: String, query: String, radius: Int = SNIPPET_RADIUS): String {
            val trimmed = text.trim()
            if (query.isBlank()) return trimmed.take(2 * radius)
            val at = trimmed.indexOf(query, ignoreCase = true)
            if (at < 0) return trimmed.take(2 * radius)
            val from = (at - radius).coerceAtLeast(0)
            val to = (at + query.length + radius).coerceAtMost(trimmed.length)
            val head = if (from > 0) "…" else ""
            val tail = if (to < trimmed.length) "…" else ""
            return head + trimmed.substring(from, to) + tail
        }
    }
}
