package com.engreader.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * User-owned storage: saved articles, the wordbook, look-up history, reading
 * sessions and quiz results.
 *
 * Raw SQLite rather than Room: the schema is small and hand-written SQL keeps
 * annotation processing (and therefore build time) out of the project.
 */
class UserDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE article (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                sourceId     TEXT    NOT NULL DEFAULT '',
                title        TEXT    NOT NULL,
                subtitle     TEXT    NOT NULL DEFAULT '',
                url          TEXT    NOT NULL DEFAULT '',
                author       TEXT    NOT NULL DEFAULT '',
                publishedAt  INTEGER NOT NULL DEFAULT 0,
                difficulty   INTEGER NOT NULL DEFAULT 3,
                body         TEXT    NOT NULL,
                translation  TEXT    NOT NULL DEFAULT '',
                saved        INTEGER NOT NULL DEFAULT 0,
                fetchedAt    INTEGER NOT NULL DEFAULT 0,
                lastReadAt   INTEGER NOT NULL DEFAULT 0,
                readSeconds  INTEGER NOT NULL DEFAULT 0,
                quizJson     TEXT    NOT NULL DEFAULT '',
                -- Newline-joined distinct content lemmas. Cached so the personal
                -- unknown rate can be recomputed as the wordbook grows without
                -- re-tokenising and re-grading the body every time.
                vocabProfile TEXT    NOT NULL DEFAULT '',
                -- Word count, written when the body is. Cached because the shelf and
                -- the table of contents need a total for a few hundred chapters
                -- without loading a single body, and the SQL approximation they used
                -- (counting spaces) disagreed with the reader's own count.
                wordCount    INTEGER NOT NULL DEFAULT 0,
                -- Set when the row is a chapter of an imported book. A plain article
                -- leaves both at zero, which is what keeps the two kinds apart in
                -- every query that lists one or the other.
                bookId       INTEGER NOT NULL DEFAULT 0,
                chapterIndex INTEGER NOT NULL DEFAULT 0,
                -- The paragraph the reader had scrolled to, so reopening a long piece
                -- resumes where it was left instead of at the top. Zero means the top,
                -- which is also what every article imported before this column has.
                readParagraph INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX idx_article_url ON article(url) WHERE url <> ''")
        db.execSQL("CREATE INDEX idx_article_book ON article(bookId, chapterIndex)")

        db.execSQL(
            """
            CREATE TABLE word (
                lemma        TEXT    PRIMARY KEY COLLATE NOCASE,
                display      TEXT    NOT NULL,
                translation  TEXT    NOT NULL DEFAULT '',
                phonetic     TEXT    NOT NULL DEFAULT '',
                firstSeenAt  INTEGER NOT NULL DEFAULT 0,
                seenCount    INTEGER NOT NULL DEFAULT 1,
                mastered     INTEGER NOT NULL DEFAULT 0,
                -- Leitner box: 0 = new, 1..5 = increasing interval.
                box          INTEGER NOT NULL DEFAULT 0,
                dueAt        INTEGER NOT NULL DEFAULT 0,
                correct      INTEGER NOT NULL DEFAULT 0,
                wrong        INTEGER NOT NULL DEFAULT 0,
                note         TEXT    NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_word_due ON word(dueAt)")

        db.execSQL(
            """
            CREATE TABLE lookup (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                lemma      TEXT    NOT NULL,
                articleId  INTEGER,
                lookedUpAt INTEGER NOT NULL,
                -- The sentence the word was tapped in, and the form as written.
                -- Review shows the word in the context it was met in rather than
                -- as a bare dictionary entry.
                sentence   TEXT    NOT NULL DEFAULT '',
                surface    TEXT    NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_lookup_time ON lookup(lookedUpAt)")
        db.execSQL("CREATE INDEX idx_lookup_lemma ON lookup(lemma, lookedUpAt DESC)")

        db.execSQL(
            """
            CREATE TABLE session (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                articleId  INTEGER NOT NULL,
                day        TEXT    NOT NULL,
                seconds    INTEGER NOT NULL DEFAULT 0,
                wordsRead  INTEGER NOT NULL DEFAULT 0,
                lookups    INTEGER NOT NULL DEFAULT 0,
                finishedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_session_day ON session(day)")
        db.execSQL("CREATE INDEX idx_session_article ON session(articleId)")

        db.execSQL(
            """
            CREATE TABLE quiz (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                articleId INTEGER NOT NULL,
                correct   INTEGER NOT NULL,
                total     INTEGER NOT NULL,
                takenAt   INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_quiz_article ON quiz(articleId)")

        createBookTable(db)
    }

    /**
     * An imported book.
     *
     * Chapters are rows in `article` with `bookId` pointing here, so the reader, the
     * dictionary and the quiz all work on a book chapter without knowing it is one.
     * `lastChapter` is on the book because "where was I" has to survive the reader
     * opening another chapter to check something.
     *
     * Every statement is `IF NOT EXISTS`: this runs from both `onCreate` and
     * `onUpgrade`, and a half-applied upgrade has to be re-runnable, which the comment
     * claimed but the plain `CREATE TABLE` did not deliver.
     */
    private fun createBookTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                title        TEXT    NOT NULL,
                author       TEXT    NOT NULL DEFAULT '',
                format       TEXT    NOT NULL DEFAULT '',
                fileName     TEXT    NOT NULL DEFAULT '',
                coverFile    TEXT    NOT NULL DEFAULT '',
                chapterCount INTEGER NOT NULL DEFAULT 0,
                addedAt      INTEGER NOT NULL DEFAULT 0,
                lastReadAt   INTEGER NOT NULL DEFAULT 0,
                readSeconds  INTEGER NOT NULL DEFAULT 0,
                lastChapter  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_book_recent ON book(lastReadAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_article_book ON article(bookId, chapterIndex)")
    }

    /**
     * Adds the columns introduced after v1, leaving existing rows alone.
     *
     * An earlier version dropped and recreated every table, which was acceptable
     * while nothing had shipped but destroys the wordbook and reading history on
     * upgrade. Each statement is guarded by a `PRAGMA table_info` check, so a
     * partially-applied upgrade can simply be re-run.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addColumn(db, "lookup", "sentence", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "lookup", "surface", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "article", "vocabProfile", "TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_lookup_lemma ON lookup(lemma, lookedUpAt DESC)"
            )
        }
        if (oldVersion < 3) {
            addColumn(db, "article", "bookId", "INTEGER NOT NULL DEFAULT 0")
            addColumn(db, "article", "chapterIndex", "INTEGER NOT NULL DEFAULT 0")
            createBookTable(db)
        }
        if (oldVersion < 4) {
            // Backfilled with the reader's own word count rather than the space
            // approximation the old queries used, so the shelf agrees with the article
            // list from the first launch after the upgrade.
            addColumn(db, "article", "wordCount", "INTEGER NOT NULL DEFAULT 0")
            backfillWordCounts(db)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_article ON session(articleId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_quiz_article ON quiz(articleId)")
        }
        if (oldVersion < 5) {
            // Where the reader stopped, as a paragraph number. Nothing is backfilled:
            // every existing row starts at 0, which is the top of the text, and that is
            // the honest answer for an article whose position was never recorded.
            addColumn(db, "article", "readParagraph", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Fills [article.wordCount] for rows written before the column existed.
     *
     * The count comes from the same token rule the reader uses, so the shelf, the
     * table of contents and the article list all report one number. `SQLiteOpenHelper`
     * already runs the upgrade in a transaction, and the work is one pass over the
     * stored bodies on a database the repositories only touch from a background
     * dispatcher.
     */
    private fun backfillWordCounts(db: SQLiteDatabase) {
        val rows = mutableListOf<Pair<Long, String>>()
        db.rawQuery("SELECT id, body FROM article", null).use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1).orEmpty()
        }
        val update = db.compileStatement("UPDATE article SET wordCount = ? WHERE id = ?")
        rows.forEach { (id, body) ->
            update.bindLong(1, com.engreader.app.nlp.Tokenizer.countWords(body).toLong())
            update.bindLong(2, id)
            update.executeUpdateDelete()
        }
    }

    private fun addColumn(db: SQLiteDatabase, table: String, column: String, spec: String) {
        if (hasColumn(db, table, column)) return
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $spec")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                if (c.getString(nameIndex).equals(column, ignoreCase = true)) return true
            }
            false
        }

    companion object {
        const val NAME = "engreader.db"
        const val VERSION = 5
    }
}
