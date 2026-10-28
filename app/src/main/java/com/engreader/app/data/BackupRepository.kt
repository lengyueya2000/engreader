package com.engreader.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What an import did, for the message the settings screen shows afterwards. */
data class RestoreResult(
    val wordsAdded: Int,
    val wordsUpdated: Int,
    val sessionsAdded: Int,
)

/**
 * Writes the study data out as one JSON file, and reads it back.
 *
 * The wordbook is the only thing in the app that cannot be recreated: articles can be
 * fetched again and books re-imported, but months of review intervals, notes and
 * reading history live nowhere else. The export exists so that changing phones, or
 * clearing the app's data, does not throw that away.
 *
 * Reading history is exported alongside it because the streak and the words-per-minute
 * figure are computed from it, and a restored wordbook with no history would show a
 * reader who had never studied before today. A session travels under the article's
 * stable URL rather than under `session.articleId`, which is a rowid from the device
 * the file was written on and means nothing on the device it is restored to.
 *
 * What the file holds is the wordbook and the reading sessions, and only those. The
 * look-up log, the quiz results and the vocabulary-size history are not in it: after a
 * restore the progress screen reports them as zero while the day chart — built from the
 * restored sessions — still shows the reading that was done. Adding those tables is a
 * change to the file format, and one older builds have to keep accepting.
 */
class BackupRepository(private val db: UserDb) {

    /** The whole backup as a JSON string, ready to be written to a file. */
    suspend fun export(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("words", words())
        root.put("sessions", sessions())
        root.toString(2)
    }

    /**
     * Merges a backup into the database.
     *
     * A merge rather than a replace: a reader who imports an old file onto a phone they
     * have been using should keep what they have done since. For a word that exists on
     * both sides the more advanced state wins — the higher Leitner box, the larger
     * counts — and a note or translation is only taken when the current row has none.
     * A lemma that the file itself names twice is merged the same way instead of
     * aborting the import, so one duplicate line cannot cost the reader the whole
     * wordbook.
     *
     * Reading sessions are matched to the local article by its stable URL, not by the
     * exported `articleId`: the rowid belongs to the exporting device.
     */
    suspend fun restore(json: String): RestoreResult = withContext(Dispatchers.IO) {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw IllegalArgumentException("这不是 EngReader 导出的备份文件", it)
        }
        val format = root.optString("format")
        if (format != FORMAT) throw IllegalArgumentException("备份文件格式不认识：$format")
        if (root.optInt("version", 0) > VERSION) {
            throw IllegalArgumentException("备份来自更新版本的应用，请先升级再导入")
        }
        val database = db.writableDatabase
        var added = 0
        var updated = 0
        var sessionsAdded = 0
        database.beginTransaction()
        try {
            // Lowercase lemmas the wordbook already holds: `word.lemma` is
            // `PRIMARY KEY COLLATE NOCASE`, so the two sides are compared lowercased.
            val existing = mutableSetOf<String>()
            database.rawQuery("SELECT lemma FROM word", null).use { c ->
                while (c.moveToNext()) existing += c.getString(0).lowercase()
            }
            val wordArray = root.optJSONArray("words") ?: JSONArray()
            for (i in 0 until wordArray.length()) {
                val word = wordArray.optJSONObject(i) ?: continue
                val lemma = word.optString("lemma").trim()
                if (lemma.isEmpty()) continue
                // `existing` is updated as the loop inserts. Without that, a lemma this
                // same file has already contributed looks new, `insertWord`'s `execSQL`
                // throws on the UNIQUE constraint, there is no `catch` around this
                // block, and `setTransactionSuccessful` is never reached — the whole
                // restore rolls back and the wordbook arrives empty. A file that names
                // one lemma twice (hand-edited, or merged from two devices) is exactly
                // the fresh-phone import the backup exists for, so the second
                // occurrence has to merge instead.
                val known = !existing.add(lemma.lowercase())
                if (!known) {
                    insertWord(database, word, lemma)
                    added++
                } else {
                    updateWord(database, word, lemma)
                    updated++
                }
            }

            // The (articleId, finishedAt) pairs already stored, read once: the row-by-row
            // existence query this replaces ran a query per session inside the write
            // transaction, holding the write lock for the whole loop.
            val knownSessions = mutableSetOf<Pair<Long, Long>>()
            database.rawQuery("SELECT articleId, finishedAt FROM session", null).use { c ->
                while (c.moveToNext()) knownSessions += c.getLong(0) to c.getLong(1)
            }
            val sessionArray = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until sessionArray.length()) {
                val session = sessionArray.optJSONObject(i) ?: continue
                val finishedAt = session.optLong("finishedAt", 0)
                if (finishedAt <= 0) continue
                val articleId = localArticleId(database, session)
                // A session already present is left alone; importing the same file
                // twice must not double the reading time it reports.
                if (!knownSessions.add(articleId to finishedAt)) continue
                database.execSQL(
                    "INSERT INTO session (articleId, day, seconds, wordsRead, lookups, finishedAt) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        articleId,
                        // Re-derived from the timestamp rather than copied out of the
                        // file: `day` is the writing device's local day, so a file
                        // from another timezone used to shift a day, and a hand-edited
                        // file could inject a key no calendar ever produces.
                        DayKey.of(finishedAt),
                        session.optInt("seconds"),
                        session.optInt("wordsRead"),
                        session.optInt("lookups"),
                        finishedAt,
                    ),
                )
                sessionsAdded++
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        RestoreResult(wordsAdded = added, wordsUpdated = updated, sessionsAdded = sessionsAdded)
    }

    /**
     * The local `article.id` a restored session belongs to.
     *
     * The file's `articleId` is the exporting device's `AUTOINCREMENT` rowid, so it
     * points at whatever row happens to own that id here — on a fresh install, at the
     * bundled starter passages — which used to fabricate "已读完" counts and mix
     * unrelated articles into the reading-speed window. Sessions therefore travel
     * under the article's stable key (its URL; a chapter's is `book:<bookId>/<index>`)
     * and are resolved against the local rows.
     *
     * An article that is not on this device gets a synthetic negative id: negative so
     * it can never collide with a real rowid, and derived from the key so the flushes
     * of one article still collapse into a single group where the reading-speed window
     * aggregates them. The reading time itself keeps counting towards the streak and
     * the day chart, which is why history is exported at all.
     */
    private fun localArticleId(database: android.database.sqlite.SQLiteDatabase, session: JSONObject): Long {
        val key = session.optString("articleKey").trim()
        if (key.isNotEmpty()) {
            val local = database.rawQuery(
                "SELECT id FROM article WHERE url = ? LIMIT 1", arrayOf(key),
            ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            return if (local > 0) local else syntheticArticleId(key)
        }
        // A file written before `articleKey` existed carries the exporting device's
        // rowid. It is meaningless here, but its magnitude still groups one article's
        // flushes together, and a negative id cannot land on a local row.
        val legacy = session.optLong("articleId", 0)
        return if (legacy > 0) -legacy else 0L
    }

    private fun words(): JSONArray {
        val array = JSONArray()
        db.readableDatabase.rawQuery(
            "SELECT lemma, display, translation, phonetic, firstSeenAt, seenCount, mastered, " +
                "box, dueAt, correct, wrong, note FROM word",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                array.put(
                    JSONObject().apply {
                        put("lemma", c.getString(0))
                        put("display", c.getString(1))
                        put("translation", c.getString(2))
                        put("phonetic", c.getString(3))
                        put("firstSeenAt", c.getLong(4))
                        put("seenCount", c.getInt(5))
                        put("mastered", c.getInt(6))
                        put("box", c.getInt(7))
                        put("dueAt", c.getLong(8))
                        put("correct", c.getInt(9))
                        put("wrong", c.getInt(10))
                        put("note", c.getString(11))
                    },
                )
            }
        }
        return array
    }

    private fun sessions(): JSONArray {
        val array = JSONArray()
        // The article's URL, not its rowid: a rowid is local to this database, and a
        // restored session used to land on whichever unrelated article happened to own
        // the same id on the new device.
        db.readableDatabase.rawQuery(
            "SELECT s.day, s.seconds, s.wordsRead, s.lookups, s.finishedAt, a.url " +
                "FROM session s LEFT JOIN article a ON a.id = s.articleId",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                array.put(
                    JSONObject().apply {
                        put("articleKey", c.getString(5).orEmpty())
                        put("day", c.getString(0))
                        put("seconds", c.getInt(1))
                        put("wordsRead", c.getInt(2))
                        put("lookups", c.getInt(3))
                        put("finishedAt", c.getLong(4))
                    },
                )
            }
        }
        return array
    }

    private fun insertWord(database: android.database.sqlite.SQLiteDatabase, word: JSONObject, lemma: String) {
        database.execSQL(
            "INSERT INTO word (lemma, display, translation, phonetic, firstSeenAt, seenCount, " +
                "mastered, box, dueAt, correct, wrong, note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                lemma,
                word.optString("display").ifBlank { lemma },
                word.optString("translation"),
                word.optString("phonetic"),
                word.optLong("firstSeenAt", System.currentTimeMillis()),
                word.optInt("seenCount", 1),
                word.optInt("mastered", 0),
                word.optInt("box", 0),
                word.optLong("dueAt", 0),
                word.optInt("correct", 0),
                word.optInt("wrong", 0),
                word.optString("note"),
            ),
        )
    }

    private fun updateWord(database: android.database.sqlite.SQLiteDatabase, word: JSONObject, lemma: String) {
        database.execSQL(
            """
            UPDATE word SET
                display     = CASE WHEN display = '' THEN ? ELSE display END,
                translation = CASE WHEN translation = '' THEN ? ELSE translation END,
                phonetic    = CASE WHEN phonetic = '' THEN ? ELSE phonetic END,
                seenCount   = MAX(seenCount, ?),
                mastered    = MAX(mastered, ?),
                box         = MAX(box, ?),
                dueAt       = MAX(dueAt, ?),
                correct     = MAX(correct, ?),
                wrong       = MAX(wrong, ?),
                note        = CASE WHEN note = '' THEN ? ELSE note END
            WHERE lemma = ? COLLATE NOCASE
            """.trimIndent(),
            arrayOf(
                word.optString("display"),
                word.optString("translation"),
                word.optString("phonetic"),
                word.optInt("seenCount", 1),
                word.optInt("mastered", 0),
                word.optInt("box", 0),
                word.optLong("dueAt", 0),
                word.optInt("correct", 0),
                word.optInt("wrong", 0),
                word.optString("note"),
                lemma,
            ),
        )
    }

    companion object {
        /** Stamped into the file so an unrelated JSON is rejected instead of half-read. */
        const val FORMAT = "engreader.backup"

        /** Bumped when the shape of the file changes in a way older readers cannot take. */
        const val VERSION = 1

        /** What the file picker offers to save as. */
        const val FILE_NAME = "engreader-backup.json"

        /**
         * Stand-in `articleId` for a restored session whose article is not on this
         * device, derived from the article's stable key.
         *
         * The ids are at or below `-SYNTHETIC_BASE`, which keeps them out of the range a
         * real (positive) rowid occupies and out of the range of `-rowid` for any rowid
         * a device could practically hold, so a pre-`articleKey` file cannot collide
         * with one either. Equal keys give equal ids, which is what keeps one article's
         * flushes in a single group in the reading-speed window; `hashCode` can collide
         * for two different keys, and the only consequence is that those two articles
         * share a group there.
         */
        internal fun syntheticArticleId(key: String): Long =
            -SYNTHETIC_BASE - (key.hashCode().toLong() and 0xFFFF_FFFFL)

        internal const val SYNTHETIC_BASE = 0x1_0000_0000L
    }
}
