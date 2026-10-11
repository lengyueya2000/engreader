package com.engreader.app.model

/** A readable article: either fetched from a feed or bundled as starter content. */
data class Article(
    val id: Long = 0,
    val sourceId: String = "",
    val sourceName: String = "",
    val title: String,
    val subtitle: String = "",
    val url: String = "",
    val author: String = "",
    val publishedAt: Long = 0,
    val difficulty: Int = 3,
    val body: String,
    val saved: Boolean = false,
    val fetchedAt: Long = 0,
    val lastReadAt: Long = 0,
    val readSeconds: Int = 0,
    val quiz: List<QuizQuestion> = emptyList(),
    /**
     * Chinese translation of each paragraph, aligned with `Paragraphs.split(body)`.
     * Empty until the user asks for a translation, and cached afterwards so the
     * article never has to be sent to the service twice.
     */
    val translation: List<String> = emptyList(),
    /**
     * Distinct study-worthy lemmas in the body, newline-joined.
     *
     * Cached so the personal unknown rate can be recomputed as the wordbook grows
     * without re-tokenising the body; empty until it is first needed.
     */
    val vocabProfile: String = "",
    /**
     * Id of the imported book this is a chapter of, or 0 for a standalone article.
     *
     * Chapters are ordinary article rows so the reader, dictionary, grammar panel
     * and quiz need no book-specific code; this is the only thing that marks them.
     */
    val bookId: Long = 0,
    /** Position of this chapter inside its book, from 0. Meaningless when [bookId] is 0. */
    val chapterIndex: Int = 0,
    /**
     * Words in [body], as stored in the `wordCount` column.
     *
     * Schema v4 added the column and backfilled it, so reading it back is free.
     * Anything built in memory without the column falls back to counting once.
     */
    private val storedWordCount: Int = 0,
    /**
     * Paragraph the reader had scrolled to, from the `readParagraph` column.
     *
     * Zero for a piece that has never been scrolled, and for everything stored before
     * schema v5 added the column, which is the same thing: the top of the text.
     */
    val readParagraph: Int = 0,
) {
    /** Cached content lemmas, or an empty list when the profile has not been built yet. */
    val contentLemmas: List<String>
        get() = com.engreader.app.nlp.VocabularyProfile.decode(vocabProfile)

    val wordCount: Int get() = if (storedWordCount > 0) storedWordCount else countedWords
    private val countedWords: Int by lazy { com.engreader.app.nlp.Tokenizer.countWords(body) }

    val estimatedMinutes: Int get() = (wordCount / 180).coerceAtLeast(1)

    /**
     * Live blogs are a stream of short timestamped updates rather than an article.
     * They are still readable, but the paragraphs do not connect, so the reader
     * says so instead of leaving the user wondering why it reads choppily.
     */
    val isLiveBlog: Boolean
        get() = com.engreader.app.source.LiveBlog.isLive(title, url)

    /** Label for where the text came from, when the feed name is unknown. */
    val originLabel: String
        get() = when {
            sourceName.isNotBlank() -> sourceName
            sourceId == "gutenberg" -> "公版文本"
            else -> "外刊"
        }
}

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String = "",
)

/** A headline from a feed that has not been opened (and so has no full text yet). */
data class FeedItem(
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val summary: String,
    val url: String,
    val publishedAt: Long,
    val imageUrl: String = "",
)

/** A content source shown on the Discover screen. */
data class Source(
    val id: String,
    val name: String,
    /** Short label for the filter chips, where the full name would not fit. */
    val shortName: String,
    val feedUrl: String,
    val blurb: String,
    val accent: Int,
    /** Rough reading difficulty, 1..5, used to sort the feed. */
    val difficulty: Int,
)

/** A curated topic the user can pull fresh headlines for. */
data class Topic(
    val id: String,
    val name: String,
    val feedUrl: String,
    val blurb: String,
)
