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
 * reader who had never studied before today.
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
            val existing = mutableMapOf<String, Int>()
            database.rawQuery("SELECT lemma, box FROM word", null).use { c ->
                while (c.moveToNext()) existing[c.getString(0).lowercase()] = c.getInt(1)
            }
            val wordArray = root.optJSONArray("words") ?: JSONArray()
            for (i in 0 until wordArray.length()) {
                val word = wordArray.optJSONObject(i) ?: continue
                val lemma = word.optString("lemma").trim()
                if (lemma.isEmpty()) continue
                val knownBox = existing[lemma.lowercase()]
                if (knownBox == null) {
                    insertWord(database, word, lemma)
                    added++
                } else if (word.optInt("box", 0) > knownBox) {
                    updateWord(database, word, lemma)
                    updated++
                }
            }

            val sessionArray = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until sessionArray.length()) {
                val session = sessionArray.optJSONObject(i) ?: continue
                val finishedAt = session.optLong("finishedAt", 0)
                if (finishedAt <= 0) continue
                val articleId = session.optLong("articleId", 0)
                // A session already present is left alone; importing the same file
                // twice must not double the reading time it reports.
                val exists = database.rawQuery(
                    "SELECT 1 FROM session WHERE articleId = ? AND finishedAt = ? LIMIT 1",
                    arrayOf(articleId.toString(), finishedAt.toString()),
                ).use { it.moveToFirst() }
                if (exists) continue
                database.execSQL(
                    "INSERT INTO session (articleId, day, seconds, wordsRead, lookups, finishedAt) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        articleId,
                        session.optString("day"),
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
        db.readableDatabase.rawQuery(
            "SELECT articleId, day, seconds, wordsRead, lookups, finishedAt FROM session",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                array.put(
                    JSONObject().apply {
                        put("articleId", c.getLong(0))
                        put("day", c.getString(1))
                        put("seconds", c.getInt(2))
                        put("wordsRead", c.getInt(3))
                        put("lookups", c.getInt(4))
                        put("finishedAt", c.getLong(5))
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
                box         = ?,
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
    }
}
