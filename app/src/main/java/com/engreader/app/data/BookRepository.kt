package com.engreader.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.engreader.app.book.BookFormat
import com.engreader.app.book.BookText
import com.engreader.app.book.EpubParser
import com.engreader.app.book.MobiParser
import com.engreader.app.model.Article
import com.engreader.app.model.Book
import com.engreader.app.model.ChapterRef
import com.engreader.app.source.ArticleExtractor
import com.engreader.app.nlp.VocabularyGrader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imported books: reading a file into the database, listing what has been imported,
 * and removing it again.
 *
 * A book becomes a `book` row plus one `article` row per chapter. Chapters being
 * ordinary articles is what lets the reader, the dictionary, the grammar panel, the
 * quiz and listening mode work on a book without a line of book-specific code — the
 * only thing that distinguishes a chapter is its `bookId`.
 *
 * Import is synchronous and runs on [Dispatchers.IO]: it reads and parses a file the
 * user just picked, and there is nothing to show until it is done.
 */
class BookRepository(
    private val context: Context,
    private val db: UserDb,
    private val grader: VocabularyGrader,
) {

    /**
     * Reads [uri] as a book and stores it.
     *
     * The file is parsed entirely in memory and only the resulting chapters are kept:
     * a 25 MB EPUB with a hundred illustrations is worth about a megabyte of text,
     * and storing the original would mean re-parsing it on every open. The cover is
     * the one binary asset kept, because the shelf shows it.
     *
     * The book row, its cover path and every chapter are written in one transaction.
     * They used to be split — the row and the cover were committed before the chapter
     * loop began — so a failure part way through left a book on the shelf with no
     * chapters and an orphan image on disk, which is exactly what the comment on the
     * transaction claimed could not happen.
     */
    suspend fun import(uri: Uri, displayName: String): Book = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BookFormat.Companion.Unsupported("读不到这个文件")
        if (bytes.isEmpty()) throw BookFormat.Companion.Unsupported("这个文件是空的")

        val format = BookFormat.detect(bytes)
            ?: throw BookFormat.Companion.Unsupported(
                "认不出这个格式。目前支持 EPUB、MOBI 和 AZW3。"
            )

        val parsed = when (format) {
            BookFormat.Epub -> EpubParser.parse(bytes).let {
                Parsed(it.title, it.author, it.chapters, it.cover, it.coverExtension)
            }
            BookFormat.Mobi, BookFormat.Azw3 -> MobiParser.parse(bytes).let {
                Parsed(it.title, it.author, it.chapters, it.cover, it.coverExtension)
            }
        }

        val title = parsed.title.ifBlank { displayName.substringBeforeLast('.') }
        val fileName = displayName.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        val now = System.currentTimeMillis()

        var coverFile = ""
        val id = try {
            db.writableDatabase.run {
                beginTransaction()
                try {
                    val rowId = insertOrThrow(
                        "book", null,
                        ContentValues().apply {
                            put("title", title)
                            put("author", parsed.author)
                            put("format", format.label)
                            put("fileName", fileName)
                            put("chapterCount", parsed.chapters.size)
                            put("addedAt", now)
                            put("lastChapter", 0)
                        },
                    )
                    // The image is written while the row's id is known but before the
                    // commit, so a failed write aborts the whole import rather than
                    // leaving a book whose cover points at nothing.
                    coverFile = parsed.cover?.let { writeCover(rowId, it, parsed.coverExtension) }.orEmpty()
                    if (coverFile.isNotEmpty()) {
                        update(
                            "book",
                            ContentValues().apply { put("coverFile", coverFile) },
                            "id = ?", arrayOf(rowId.toString()),
                        )
                    }

                    // One transaction for the whole book: a novel is a few hundred
                    // inserts, and committing each one would make an import of Moby
                    // Dick take seconds.
                    parsed.chapters.forEachIndexed { index, chapter ->
                        val values = ContentValues().apply {
                            put("sourceId", BOOK_SOURCE)
                            put("title", chapter.title.ifBlank { "第 ${index + 1} 节" })
                            put("subtitle", parsed.author)
                            put("url", chapterUrl(rowId, index))
                            put("author", parsed.author)
                            put("publishedAt", 0)
                            put("difficulty", ArticleExtractor.estimateDifficulty(
                                chapter.text, grader.grade(chapter.text).grade,
                            ))
                            put("body", chapter.text)
                            put("wordCount", chapter.wordCount)
                            put("fetchedAt", now)
                            put("bookId", rowId)
                            put("chapterIndex", index)
                        }
                        insertWithOnConflict(
                            "article", null, values,
                            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                        )
                    }
                    setTransactionSuccessful()
                    rowId
                } finally {
                    endTransaction()
                }
            }
        } catch (e: Exception) {
            // The row was rolled back, so the image written above belongs to nothing.
            if (coverFile.isNotEmpty()) File(coversDir(), coverFile).delete()
            throw e
        }

        requireNotNull(get(id)) { "书籍写入失败" }
    }

    suspend fun all(): List<Book> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("$SELECT ORDER BY addedAt DESC", null).use { it.toBooks() }
    }

    /** The imported book with this file name, or null. */
    suspend fun findByFileName(fileName: String): Book? = withContext(Dispatchers.IO) {
        if (fileName.isBlank()) return@withContext null
        db.readableDatabase.rawQuery("$SELECT WHERE fileName = ? LIMIT 1", arrayOf(fileName))
            .use { if (it.moveToFirst()) it.toBook() else null }
    }

    /** Books the reader has opened, most recent first, for the "continue reading" card. */
    suspend fun recent(limit: Int = 20): List<Book> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "$SELECT WHERE lastReadAt > 0 ORDER BY lastReadAt DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { it.toBooks() }
    }

    suspend fun get(id: Long): Book? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("$SELECT WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toBook() else null }
    }

    /**
     * The book's chapters as a table of contents: titles only, no bodies.
     *
     * A novel is a few hundred chapters, and loading every body to draw a list of
     * titles would read megabytes off disk for nothing. The reader loads the one
     * chapter it is opening, by id.
     */
    suspend fun chapters(bookId: Long): List<ChapterRef> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id, chapterIndex, title, wordCount FROM article " +
                "WHERE bookId = ? ORDER BY chapterIndex ASC",
            arrayOf(bookId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ChapterRef(
                            articleId = c.getLong(0),
                            index = c.getInt(1),
                            title = c.getString(2),
                            wordCount = c.getInt(3),
                        )
                    )
                }
            }
        }
    }

    /** Id of the chapter at [index], or null when the book has no such chapter. */
    suspend fun chapterIdAt(bookId: Long, index: Int): Long? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id FROM article WHERE bookId = ? AND chapterIndex = ? LIMIT 1",
            arrayOf(bookId.toString(), index.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** The book a chapter belongs to, or null for a standalone article. */
    suspend fun bookOfChapter(articleId: Long): Book? = withContext(Dispatchers.IO) {
        val bookId = db.readableDatabase.rawQuery(
            "SELECT bookId FROM article WHERE id = ?", arrayOf(articleId.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (bookId <= 0) null else get(bookId)
    }

    /** Records where the reader is in the book.
     *
     * Written on every chapter change rather than on exit: leaving the app from the
     * middle of a chapter is the normal way to stop, and `onCleared` is not guaranteed
     * to run when the process is killed.
     */
    suspend fun markOpened(id: Long, chapterIndex: Int) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "book",
            ContentValues().apply {
                put("lastReadAt", System.currentTimeMillis())
                put("lastChapter", chapterIndex)
            },
            "id = ?", arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun addReadSeconds(id: Long, seconds: Int) = withContext(Dispatchers.IO) {
        if (seconds <= 0) return@withContext
        db.writableDatabase.execSQL(
            "UPDATE book SET readSeconds = readSeconds + ? WHERE id = ?",
            arrayOf(seconds, id),
        )
        Unit
    }

    /**
     * Removes a book, its chapters, its cover image and the study rows that belong to
     * its chapters.
     *
     * A chapter is an ordinary article, so a session or a quiz result for one would
     * survive the delete and keep contributing to the stats screen's totals — and
     * "articles started" would count a book that is no longer on the shelf. Look-ups
     * are deliberately left alone: the word was still met while reading, and the
     * wordbook's history should not lose it because the book it came from was deleted.
     */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val cover = get(id)?.coverFile
        db.writableDatabase.beginTransaction()
        try {
            val chapters = "articleId IN (SELECT id FROM article WHERE bookId = ?)"
            val args = arrayOf(id.toString())
            db.writableDatabase.delete("session", chapters, args)
            db.writableDatabase.delete("quiz", chapters, args)
            db.writableDatabase.delete("article", "bookId = ?", args)
            db.writableDatabase.delete("book", "id = ?", args)
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
        // Deleted after the commit: a rolled-back delete must not take the image with
        // it, or the book would come back with a broken cover.
        cover?.takeIf { it.isNotBlank() }?.let { File(coversDir(), it).delete() }
        Unit
    }

    /** The cover image on disk, or null when the book has none. */
    fun coverFile(book: Book): File? {
        if (book.coverFile.isBlank()) return null
        val file = File(coversDir(), book.coverFile)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * True when a book with this file name was already imported.
     *
     * Kept for callers that want to ask without importing; [import] itself uses
     * [findByFileName], because a second import of the same file used to create a
     * second book and a second set of chapter rows — the URL uniqueness that protects
     * articles is scoped per book id, so the chapters did not collide either.
     */
    suspend fun isImported(fileName: String): Boolean = findByFileName(fileName) != null

    private fun writeCover(bookId: Long, bytes: ByteArray, extension: String): String {
        val dir = coversDir()
        if (!dir.exists() && !dir.mkdirs()) return ""
        val ext = extension.ifBlank { "jpg" }
        val file = File(dir, "$bookId.$ext")
        return try {
            file.writeBytes(bytes)
            file.name
        } catch (_: Exception) {
            ""
        }
    }

    private fun coversDir(): File = File(context.filesDir, "covers")

    private fun Cursor.toBooks(): List<Book> = buildList { while (moveToNext()) add(toBook()) }

    private fun Cursor.toBook(): Book {
        val id = getLong(getColumnIndexOrThrow("id"))
        return Book(
            id = id,
            title = getString(getColumnIndexOrThrow("title")),
            author = getString(getColumnIndexOrThrow("author")),
            format = getString(getColumnIndexOrThrow("format")),
            fileName = getString(getColumnIndexOrThrow("fileName")),
            coverFile = getString(getColumnIndexOrThrow("coverFile")),
            chapterCount = getInt(getColumnIndexOrThrow("chapterCount")),
            addedAt = getLong(getColumnIndexOrThrow("addedAt")),
            lastReadAt = getLong(getColumnIndexOrThrow("lastReadAt")),
            readSeconds = getInt(getColumnIndexOrThrow("readSeconds")),
            lastChapter = getInt(getColumnIndexOrThrow("lastChapter")),
            wordCount = wordCountOf(id),
        )
    }

    /**
     * Word count of the whole book, summed from its chapters.
     *
     * Read from the stored column rather than computed: a book's chapters are written
     * once and never change, so this is a cheap `SUM` over an indexed column, and the
     * space-counting expression it replaced disagreed with [Article.wordCount] — which
     * the reader and the article list both show.
     */
    private fun wordCountOf(bookId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT SUM(wordCount) FROM article WHERE bookId = ?",
            arrayOf(bookId.toString()),
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else 0 }

    /** What a parser produced, normalised so EPUB and MOBI share one import path. */
    private data class Parsed(
        val title: String,
        val author: String,
        val chapters: List<EpubParser.Chapter>,
        val cover: ByteArray?,
        val coverExtension: String,
    )

    private companion object {
        /** `sourceId` for a book chapter, so `Catalog.displayName` can label it. */
        const val BOOK_SOURCE = "book"

        const val SELECT =
            "SELECT id, title, author, format, fileName, coverFile, chapterCount, addedAt, " +
                "lastReadAt, readSeconds, lastChapter FROM book"

        /**
         * A chapter's URL.
         *
         * The article table has a unique index on non-empty URLs, and chapters need a
         * stable identity of their own — one that cannot collide with a feed's. The
         * book id makes it unique across imports, and the index keeps it meaningful.
         */
        fun chapterUrl(bookId: Long, index: Int): String = "book:$bookId/$index"
    }
}
