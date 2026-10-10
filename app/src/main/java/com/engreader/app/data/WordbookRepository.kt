package com.engreader.app.data

import android.content.ContentValues
import android.database.Cursor
import com.engreader.app.dict.Dictionary
import com.engreader.app.dict.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A word in the user's wordbook, with its spaced-repetition state. */
data class SavedWord(
    val lemma: String,
    val display: String,
    val translation: String,
    val phonetic: String,
    val firstSeenAt: Long,
    val seenCount: Int,
    val mastered: Boolean,
    val box: Int,
    val dueAt: Long,
    val correct: Int,
    val wrong: Int,
    val note: String,
) {
    val accuracy: Float
        get() {
            val total = correct + wrong
            return if (total == 0) 0f else correct.toFloat() / total
        }

    val isDue: Boolean get() = dueAt <= System.currentTimeMillis()
}

/** One day of study activity, for the streak and volume charts. */
data class StudyDay(val day: String, val seconds: Int, val lookups: Int, val wordsRead: Int)

/**
 * A saved word together with the sentence it was first met in.
 *
 * Review shows this rather than the bare entry: a word is remembered with the
 * context it was met in, and the sentence is also what makes the card answerable
 * without the definition being shown first.
 */
data class ReviewCard(
    val word: SavedWord,
    /** The sentence the word was tapped in, or "" when it was never met in one. */
    val sentence: String,
    /** The form as written in that sentence, e.g. `derived` for `derive`. */
    val surface: String,
)

/**
 * The wordbook plus its Leitner-box review scheduling.
 *
 * A new word starts in box 0 and is due immediately. A correct review promotes it
 * one box, a wrong review sends it back to box 1; the box index selects the
 * interval, so words the user keeps missing come back sooner.
 */
class WordbookRepository(
    private val db: UserDb,
    private val dictionary: Dictionary,
) {

    /**
     * Adds a word to the wordbook, or bumps it when already present.
     *
     * Deliberately does not write a look-up row: the look-up that led here is
     * recorded by the caller, and recording it again here would double every count
     * on the stats screen for any word the user saves.
     */
    suspend fun save(entry: WordEntry) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.writableDatabase.execSQL(
            """
            INSERT INTO word (lemma, display, translation, phonetic, firstSeenAt, seenCount, dueAt)
            VALUES (?, ?, ?, ?, ?, 1, ?)
            ON CONFLICT(lemma) DO UPDATE SET
                seenCount = seenCount + 1,
                display = excluded.display,
                translation = excluded.translation,
                phonetic = excluded.phonetic
            """.trimIndent(),
            arrayOf(
                entry.lemma, entry.queried, entry.translation, entry.phonetic, now, now,
            ),
        )
        Unit
    }

    /**
     * Records that the user looked a word up, keeping the sentence it was in.
     *
     * The context is stored rather than re-derived later: the article body is on
     * disk, but finding the sentence again would mean re-splitting the whole text
     * for every word, and the sentence the user actually tapped is not necessarily
     * the first place the word appears.
     */
    suspend fun recordLookup(
        lemma: String,
        articleId: Long?,
        sentence: String = "",
        surface: String = "",
    ) = withContext(Dispatchers.IO) {
        db.writableDatabase.insert(
            "lookup", null,
            ContentValues().apply {
                put("lemma", lemma)
                put("articleId", articleId)
                put("lookedUpAt", System.currentTimeMillis())
                put("sentence", sentence.take(MAX_SENTENCE))
                put("surface", surface.take(64))
            },
        )
        Unit
    }

    /**
     * Review queue with the context each word was met in.
     *
     * The context comes from the most recent look-up that recorded one: an early
     * look-up made before sentences were stored has an empty `sentence`, and the
     * `sentence <> ''` filter skips those rather than letting one blank out a card
     * that has real context available.
     */
    suspend fun dueCards(limit: Int = 30): List<ReviewCard> = withContext(Dispatchers.IO) {
        val words = due(limit)
        if (words.isEmpty()) return@withContext emptyList()
        val context = contextFor(words.map { it.lemma })
        words.map { word ->
            val found = context[word.lemma.lowercase()]
            ReviewCard(
                word = word,
                sentence = found?.first.orEmpty(),
                surface = found?.second.orEmpty(),
            )
        }
    }

    /** Most recent recorded sentence and surface form, keyed by lowercase lemma. */
    private fun contextFor(lemmas: List<String>): Map<String, Pair<String, String>> {
        if (lemmas.isEmpty()) return emptyMap()
        val placeholders = lemmas.joinToString(",") { "?" }
        val out = HashMap<String, Pair<String, String>>()
        db.readableDatabase.rawQuery(
            "SELECT lemma, sentence, surface FROM lookup " +
                "WHERE sentence <> '' AND lemma IN ($placeholders) " +
                "ORDER BY lookedUpAt ASC",
            lemmas.map { it.lowercase() }.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                // Ascending order means the last write for a lemma wins, so the
                // context kept is the most recent one.
                out[c.getString(0).lowercase()] = c.getString(1) to c.getString(2)
            }
        }
        return out
    }

    suspend fun contains(lemma: String): Boolean = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM word WHERE lemma = ? LIMIT 1", arrayOf(lemma),
        ).use { it.moveToFirst() }
    }

    /**
     * True when the reader has demonstrated knowing [lemma].
     *
     * Same bar as [knownLemmas]: mastered, or answered correctly at least once.
     * Being in the wordbook is not enough — it is there because it was not known.
     */
    suspend fun isKnown(lemma: String): Boolean = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM word WHERE lemma = ? AND (mastered = 1 OR correct > 0) LIMIT 1",
            arrayOf(lemma),
        ).use { it.moveToFirst() }
    }

    suspend fun remove(lemma: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("word", "lemma = ?", arrayOf(lemma))
        Unit
    }

    suspend fun setNote(lemma: String, note: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "word", ContentValues().apply { put("note", note) }, "lemma = ?", arrayOf(lemma),
        )
        Unit
    }

    /**
     * Marks a word as mastered, or puts it back into the review queue.
     *
     * Mastering parks the word at `Long.MAX_VALUE` so no `dueAt <= now` query can
     * match it. Un-mastering has to undo that explicitly: clearing only the flag
     * would leave the parked timestamp in place and the word would never come due
     * again, which is indistinguishable from losing it.
     */
    suspend fun setMastered(lemma: String, mastered: Boolean) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "word",
            ContentValues().apply {
                put("mastered", if (mastered) 1 else 0)
                put(
                    "dueAt",
                    if (mastered) Long.MAX_VALUE else System.currentTimeMillis(),
                )
            },
            "lemma = ?", arrayOf(lemma),
        )
        Unit
    }

    suspend fun all(): List<SavedWord> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "$SELECT ORDER BY mastered ASC, dueAt ASC, firstSeenAt DESC", null,
        ).use { it.toWords() }
    }

    suspend fun due(limit: Int = 20): List<SavedWord> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "$SELECT WHERE mastered = 0 AND dueAt <= ? ORDER BY box ASC, dueAt ASC LIMIT ?",
            arrayOf(System.currentTimeMillis().toString(), limit.toString()),
        ).use { it.toWords() }
    }

    suspend fun dueCount(): Int = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM word WHERE mastered = 0 AND dueAt <= ?",
            arrayOf(System.currentTimeMillis().toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    suspend fun totalCount(): Int = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM word", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    suspend fun masteredCount(): Int = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM word WHERE mastered = 1", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Lemmas the reader can be assumed to know.
     *
     * Two conditions, both conservative: explicitly marked mastered, or saved and
     * since answered correctly at least once. Saving alone does not qualify — a
     * word is saved precisely because it was not known — and neither does a word
     * that has only ever been answered wrong.
     */
    suspend fun knownLemmas(): Set<String> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT lemma FROM word WHERE mastered = 1 OR (correct > 0 AND seenCount >= 1)",
            null,
        ).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0).lowercase()) }
        }
    }

    /**
     * Applies a review outcome. [correct] promotes the word; a miss demotes it so
     * it reappears in the same session's queue.
     *
     * One statement, so the counters cannot lose an update. The previous
     * read-then-write pair was not atomic: two answers arriving together — a fast
     * double tap, or the session flush racing the UI — both read the same `box` and
     * both wrote the same next value, so one of them vanished and the interval was
     * computed from a stale box.
     */
    suspend fun review(lemma: String, correct: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // The box and the interval are derived inside SQL, from the row's own value.
        // The expressions are built from a Kotlin boolean and a constant table, so
        // nothing user-supplied reaches the statement text.
        val nextBox = if (correct) "MIN(box + 1, $MAX_BOX)" else "1"
        val intervals = INTERVALS_MS.withIndex()
            .joinToString(" ") { (index, ms) -> "WHEN ${index + 1} THEN ? + $ms" }
        db.writableDatabase.execSQL(
            """
            UPDATE word SET
                box = $nextBox,
                dueAt = CASE $nextBox $intervals ELSE ? END,
                correct = correct + ${if (correct) 1 else 0},
                wrong = wrong + ${if (correct) 0 else 1},
                mastered = CASE WHEN $nextBox >= $MAX_BOX THEN 1 ELSE mastered END
            WHERE lemma = ?
            """.trimIndent(),
            // One `?` per interval branch, then the fallback, then the lemma.
            Array(INTERVALS_MS.size + 1) { now.toString() } + lemma,
        )
        Unit
    }

    /**
     * Picks the wrong-answer options for a review card: real words from the
     * wordbook or dictionary with the same part of speech and a similar band, so
     * the distractor is not trivially eliminable.
     */
    suspend fun distractors(target: SavedWord, count: Int = 3): List<String> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<String>()
            val targetGloss = dictionary.firstGloss(target.translation)
            db.readableDatabase.rawQuery(
                "$SELECT WHERE lemma <> ? AND translation <> '' ORDER BY RANDOM() LIMIT 24",
                arrayOf(target.lemma),
            ).use { c ->
                while (c.moveToNext() && out.size < count) {
                    val gloss = dictionary.firstGloss(c.getString(c.getColumnIndexOrThrow("translation")))
                    if (gloss.isNotBlank() && gloss != targetGloss) out += gloss
                }
            }
            out
        }

    suspend fun recordSession(articleId: Long, seconds: Int, wordsRead: Int, lookups: Int) =
        withContext(Dispatchers.IO) {
            if (seconds <= 0 && lookups <= 0) return@withContext
            db.writableDatabase.insert(
                "session", null,
                ContentValues().apply {
                    put("articleId", articleId)
                    put("day", DayKey.of(System.currentTimeMillis()))
                    put("seconds", seconds)
                    put("wordsRead", wordsRead)
                    put("lookups", lookups)
                    put("finishedAt", System.currentTimeMillis())
                },
            )
            Unit
        }

    suspend fun recordQuiz(articleId: Long, correct: Int, total: Int) = withContext(Dispatchers.IO) {
        db.writableDatabase.insert(
            "quiz", null,
            ContentValues().apply {
                put("articleId", articleId)
                put("correct", correct)
                put("total", total)
                put("takenAt", System.currentTimeMillis())
            },
        )
        Unit
    }

    private fun Cursor.toWords(): List<SavedWord> = buildList {
        while (moveToNext()) add(toWord())
    }

    private fun Cursor.toWord(): SavedWord = SavedWord(
        lemma = getString(getColumnIndexOrThrow("lemma")),
        display = getString(getColumnIndexOrThrow("display")),
        translation = getString(getColumnIndexOrThrow("translation")),
        phonetic = getString(getColumnIndexOrThrow("phonetic")),
        firstSeenAt = getLong(getColumnIndexOrThrow("firstSeenAt")),
        seenCount = getInt(getColumnIndexOrThrow("seenCount")),
        mastered = getInt(getColumnIndexOrThrow("mastered")) == 1,
        box = getInt(getColumnIndexOrThrow("box")),
        dueAt = getLong(getColumnIndexOrThrow("dueAt")),
        correct = getInt(getColumnIndexOrThrow("correct")),
        wrong = getInt(getColumnIndexOrThrow("wrong")),
        note = getString(getColumnIndexOrThrow("note")),
    )

    private companion object {
        const val SELECT =
            "SELECT lemma, display, translation, phonetic, firstSeenAt, seenCount, mastered, " +
                "box, dueAt, correct, wrong, note FROM word"

        const val MAX_BOX = 5

        /** Longest sentence stored per look-up; a paragraph pasted in as one line is truncated. */
        const val MAX_SENTENCE = 400

        /** Leitner intervals: 10 min, 1 day, 3 days, 7 days, 21 days. */
        val INTERVALS_MS = longArrayOf(
            10 * 60_000L,
            10 * 60_000L,
            24 * 3_600_000L,
            3 * 24 * 3_600_000L,
            7 * 24 * 3_600_000L,
            21 * 24 * 3_600_000L,
        )
    }
}

/** Local-date keys for the activity tables; `yyyy-MM-dd` sorts lexicographically. */
object DayKey {
    fun of(millis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }
}
