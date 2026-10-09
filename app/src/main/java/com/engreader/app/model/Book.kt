package com.engreader.app.model

/**
 * An imported book: an EPUB, MOBI or AZW3 the reader brought in from storage.
 *
 * A book is a container for chapters, and every chapter is stored as an ordinary
 * [Article] — that is what lets the reader, the dictionary, the grammar panel, the
 * quiz and listening mode work on a book with no code of their own. The book row
 * holds only what belongs to the whole volume: who wrote it, what it is called, and
 * how far through it the reader has got.
 */
data class Book(
    val id: Long = 0,
    val title: String,
    val author: String = "",
    /** `EPUB`, `MOBI` or `AZW3`, for the badge on the shelf. */
    val format: String = "",
    /** File name the book was imported from, shown as its origin. */
    val fileName: String = "",
    /** Name of the cover image inside the app's files directory, or blank. */
    val coverFile: String = "",
    val chapterCount: Int = 0,
    val addedAt: Long = 0,
    val lastReadAt: Long = 0,
    val readSeconds: Int = 0,
    /**
     * Chapter the reader left off in, as an index into the chapter list.
     *
     * Stored on the book rather than derived from the chapters' own timestamps: the
     * question "where was I" has to survive the reader opening a different chapter
     * to check something, which would otherwise become the newest one.
     */
    val lastChapter: Int = 0,
    /** Word count of the whole book, summed from its chapters when it is listed. */
    val wordCount: Int = 0,
) {
    val originLabel: String get() = if (format.isBlank()) "导入书籍" else "$format 导入"

    /** How much of the book has been opened, as a fraction, for the shelf progress bar. */
    val progress: Float
        get() = if (chapterCount <= 0) 0f else ((lastChapter + 1).toFloat() / chapterCount).coerceIn(0f, 1f)

    val estimatedMinutes: Int get() = (wordCount / 180).coerceAtLeast(1)
}

/**
 * One chapter, as the table of contents needs it.
 *
 * Deliberately not an [Article]: a hundred chapters is a hundred bodies, and the
 * contents sheet only ever shows the titles. The reader loads the body it is
 * actually opening, by id.
 */
data class ChapterRef(
    val articleId: Long,
    val index: Int,
    val title: String,
    val wordCount: Int,
)
