package com.engreader.app.source

import com.engreader.app.nlp.Tokenizer

/**
 * Pulls article body text out of a news page.
 *
 * This is a readability-style heuristic, not a per-site scraper: it collects every
 * `<p>` in the document, drops chrome (captions, credits, cookie and subscribe
 * copy, related-links blocks) and keeps the paragraphs that read like prose. It is
 * deliberately site-agnostic so a feed added later still works.
 */
object ArticleExtractor {

    private val JUNK = Regex(
        "(cookie|subscribe|newsletter|sign up|sign-up|advertisement|share this|" +
            "read more|related content|most viewed|download the app|follow us|" +
            "all rights reserved|terms of service|privacy policy|terms and conditions|" +
            "support the guardian|funded by readers|available for everyone|" +
            "this article was amended|we use cookies|enable javascript|" +
            "click here|find out more|advert|sponsored|photo:|illustration:|" +
            "getty images|reuters/|associated press|afp/)",
        RegexOption.IGNORE_CASE,
    )

    private val CAPTION_ATTRS = Regex(
        "(caption|credit|byline|timestamp|standfirst|meta|tag|signup|newsletter|" +
            "subscri|advert|promo|related|footer|header|nav|menu|toolbar|share)",
        RegexOption.IGNORE_CASE,
    )

    private val PARAGRAPH = Regex("(?is)<p\\b([^>]*)>(.*?)</p>")
    private val HEADING = Regex("(?is)<h([12])\\b[^>]*>(.*?)</h\\1>")
    private val TITLE_TAG = Regex("(?is)<title[^>]*>(.*?)</title>")

    /** A whole link, so the text a card headline is made of can be told apart. */
    private val ANCHOR = Regex("(?is)<a\\b[^>]*>.*?</a\\s*>")

    /**
     * A whole `<meta>` tag, with quoted values allowed to contain `>`.
     *
     * A plain `[^>]*` would end the tag early on `content="a > b"`, which is legal
     * and not rare in descriptions.
     */
    private val META_TAG = Regex("(?is)<meta\\b((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>")

    /**
     * One attribute out of a tag's attribute list.
     *
     * The closing quote is matched back to the opening one, so a value containing the
     * other quote character is captured whole: a regex ending in `["']` truncated
     * `content="Britain's economy slows"` at the apostrophe and produced the title
     * `Britain`.
     */
    private fun attr(attrs: String, name: String): String? {
        val match = Regex(
            "(?is)(?:^|\\s)$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
        ).find(attrs) ?: return null
        val groups = match.groupValues
        return groups[2].ifEmpty { groups[3].ifEmpty { groups[4] } }.takeIf { it.isNotBlank() }
    }

    /**
     * Value of the first `<meta>` whose `name`/`property`/`itemprop` is in [keys].
     *
     * Scans tag by tag rather than with one regex: HTML puts `content` before or after
     * the identifying attribute depending on the generator, and a pattern that demands
     * one order silently finds nothing on the other.
     */
    private fun metaContent(doc: String, keys: List<String>): String? {
        for (tag in META_TAG.findAll(doc)) {
            val attrs = tag.groupValues[1]
            val key = attr(attrs, "property") ?: attr(attrs, "name") ?: attr(attrs, "itemprop")
            if (key != null && key.lowercase() in keys) {
                attr(attrs, "content")?.let { return it }
            }
        }
        return null
    }

    data class Extracted(
        val title: String,
        val author: String,
        val publishedAt: Long,
        val summary: String,
        val paragraphs: List<String>,
    ) {
        val body: String get() = paragraphs.joinToString("\n\n")
        val wordCount: Int get() = Tokenizer.countWords(body)
        val isUsable: Boolean get() = wordCount >= 120
    }

    fun extract(html: String, fallbackTitle: String = ""): Extracted {
        val doc = Html.stripNoise(html)
        val title = metaContent(doc, TITLE_KEYS)
            ?: firstGroup(TITLE_TAG, doc)?.substringBefore(" | ")?.substringBefore(" - ")
            ?: fallbackTitle

        val body = collectParagraphs(doc)
        return Extracted(
            title = Html.decode(title).trim(),
            author = metaContent(doc, AUTHOR_KEYS)?.let { Html.decode(it).trim() }.orEmpty(),
            publishedAt = metaContent(doc, DATE_KEYS)?.let { RssParser.parseDate(it) } ?: 0L,
            summary = metaContent(doc, DESC_KEYS)?.let { Html.decode(it).trim() }.orEmpty(),
            paragraphs = body,
        )
    }

    private fun collectParagraphs(doc: String): List<String> {
        // Headings are used to filter: a section head is not body prose, so a block
        // that matches one is dropped rather than kept as a paragraph.
        val headingTexts = HEADING.findAll(doc)
            .map { Html.text(it.groupValues[2]) }
            .filter { it.length in 4..90 && it.split(' ').size in 2..12 }
            .toSet()

        // Link ranges, so a card headline can be told from prose: "related stories"
        // blocks are written as ordinary paragraphs and were kept, which is how the
        // body ended up carrying "Fort Hood attacker to be executed…" as if it were
        // a sentence of the article.
        val links = ANCHOR.findAll(doc).map { it.range }.toList()

        val out = mutableListOf<String>()
        for (match in PARAGRAPH.findAll(doc)) {
            val attrs = match.groupValues[1]
            if (CAPTION_ATTRS.containsMatchIn(attrs)) continue
            val inner = match.groupValues[2]
            val text = Html.text(inner)
            if (text.length < 55) continue
            if (JUNK.containsMatchIn(text) && text.length < 240) continue
            // Reject nav-ish paragraphs: too many short fragments or link markers.
            if (text.count { it == '|' } >= 2) continue
            val words = text.split(' ').size
            if (words < 9) continue
            // A paragraph that is only a link, or that sits inside one, is a headline
            // on a card, not a sentence: nothing about it is written as prose.
            if (links.any { match.range.first in it }) continue
            if (bareWords(inner) < 3) continue
            out += text
        }

        if (out.size >= 3) return out

        // Some sites (and JSON-rendered pages) expose prose outside <p>. Fall back
        // to the longest text blocks between block-level tags.
        return fallbackBlocks(doc, headingTexts, out)
    }

    /** Words in [html] that are not inside a link, i.e. words the site did not wrap. */
    private fun bareWords(html: String): Int =
        Html.text(ANCHOR.replace(html, " ")).split(' ').count { word -> word.any { it.isLetter() } }

    private fun fallbackBlocks(
        doc: String,
        headings: Set<String>,
        seed: MutableList<String>,
    ): List<String> {
        // The raw match offset is kept so the surviving blocks can be put back in
        // document order. Sorting by `doc.indexOf(text)` does not work: the text has
        // been entity-decoded and whitespace-collapsed, so it usually does not appear
        // verbatim and `indexOf` returns -1 for every block.
        val blocks = Regex("(?is)<(?:div|section|article|li|blockquote)\\b[^>]*>(.*?)</(?:div|section|article|li|blockquote)>")
            .findAll(doc)
            .map { it.range.first to Html.text(it.groupValues[1]) }
            .filter { (_, text) -> text.length in 80..4000 }
            .filterNot { (_, text) -> JUNK.containsMatchIn(text) && text.length < 240 }
            .filterNot { (_, text) -> headings.contains(text) }
            .toList()
        val seedSet = seed.toSet()
        val merged = (blocks.filterNot { it.second in seedSet } + blocks.filter { it.second in seedSet })
            .distinctBy { it.second }
            .sortedByDescending { it.second.length }
            .take(40)
            .sortedBy { it.first }
            .map { it.second }
        return if (merged.isEmpty()) seed else merged
    }

    private fun firstGroup(regex: Regex, doc: String): String? =
        regex.find(doc)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private val TITLE_KEYS = listOf("og:title", "twitter:title")
    private val DESC_KEYS = listOf("og:description", "description", "twitter:description")
    private val AUTHOR_KEYS = listOf("author", "article:author", "og:article:author")
    private val DATE_KEYS = listOf(
        "article:published_time", "datepublished", "pubdate", "publishdate",
        "og:article:published_time", "date",
    )

    /** Rough difficulty 1..5 from average word length, long words and sentence length. */
    fun estimateDifficulty(text: String): Int {
        val words = Tokenizer.words(text)
        if (words.isEmpty()) return 3
        val avgLen = words.sumOf { it.length }.toDouble() / words.size
        val longRatio = words.count { it.length >= 8 }.toDouble() / words.size
        val sentences = Regex("[.!?]").findAll(text).count().coerceAtLeast(1)
        val avgSentence = words.size.toDouble() / sentences
        var score = 0
        score += when {
            avgLen < 4.3 -> 0
            avgLen < 4.7 -> 1
            avgLen < 5.1 -> 2
            avgLen < 5.5 -> 3
            else -> 4
        }
        score += when {
            longRatio < 0.08 -> 0
            longRatio < 0.14 -> 1
            longRatio < 0.20 -> 2
            else -> 3
        }
        score += when {
            avgSentence < 15 -> 0
            avgSentence < 20 -> 1
            avgSentence < 26 -> 2
            else -> 3
        }
        return ((score + 2) / 2).coerceIn(1, 5)
    }

    /**
     * Blends the text-shape estimate with the share of words the dictionary rates
     * above B1.
     *
     * Word length and sentence length alone mis-grade prose: Austen reads as
     * "simple" by those measures while Darwin reads as merely "moderate". The
     * proportion of advanced vocabulary is what actually separates them, so it gets
     * half the weight.
     */
    fun estimateDifficulty(text: String, vocabularyScore: Int): Int {
        val shape = estimateDifficulty(text)
        return Math.round((shape * 0.5f) + (vocabularyScore * 0.5f)).coerceIn(1, 5)
    }
}
