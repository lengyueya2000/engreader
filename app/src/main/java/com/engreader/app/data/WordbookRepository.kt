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
    /** Reviews answered, right or wrong. Zero means the word has never been tested. */
    val attempts: Int get() = correct + wrong

    /**
     * Share of reviews answered correctly.
     *
     * Meaningless when [attempts] is zero, and it reads as 0% rather than as "no
     * data" — callers that display it have to check [attempts] first.
     */
    val accuracy: Float
        get() = if (attempts == 0) 0f else correct.toFloat() / attempts

    val isDue: Boolean get() = dueAt <= System.currentTimeMillis()
}

/** One day of study activity, for the streak and volume charts. */
data class StudyDay(val day: String, val seconds: Int, val lookups: Int)

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
     *
     * An update-then-insert inside one transaction rather than `INSERT … ON CONFLICT
     * … DO UPDATE`: that syntax is SQLite 3.24, and the platform SQLite is 3.18 on
     * API 26, 3.19 on 27 and 3.22 on 28 — every device below Android 10 would fail
     * the statement with a syntax error and the wordbook would silently stop saving.
     */
    suspend fun save(entry: WordEntry) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val bumped = database.compileStatement(
                "UPDATE word SET seenCount = seenCount + 1, display = ?, " +
                    "translation = ?, phonetic = ? WHERE lemma = ?",
            ).use { statement ->
                statement.bindString(1, entry.queried)
                statement.bindString(2, entry.translation)
                statement.bindString(3, entry.phonetic)
                statement.bindString(4, entry.lemma)
                statement.executeUpdateDelete()
            }
            if (bumped == 0) {
                val inserted = database.insert(
                    "word", null,
                    ContentValues().apply {
                        put("lemma", entry.lemma)
                        put("display", entry.queried)
                        put("translation", entry.translation)
                        put("phonetic", entry.phonetic)
                        put("firstSeenAt", now)
                        put("seenCount", 1)
                        put("dueAt", now)
                    },
                )
                // `insert` reports failure as -1 instead of throwing, and the commit
                // below is unconditional — so without this check a storage failure
                // committed an empty transaction while the reader had already been told
                // the word was saved and highlighted it. Throwing skips
                // `setTransactionSuccessful` and the `finally` rolls back.
                check(inserted != -1L) { "could not save ${entry.lemma}" }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        Unit
    }

    /**
     * Records that the user looked a word up, keeping the sentence it was in.
     *
     * The context is stored rather than re-derived later: the article body is on
     * disk, but finding the sentence again would mean re-splitting the whole text
     * for every word, and the sentence the user actually tapped is not necessarily
     * the first place the word appears.
     *
     * Returns false when the row could not be written: `insert` reports that as -1
     * rather than throwing, so the outcome was previously indistinguishable from a
     * successful write.
     */
    suspend fun recordLookup(
        lemma: String,
        articleId: Long?,
        sentence: String = "",
        surface: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        db.writableDatabase.insert(
            "lookup", null,
            ContentValues().apply {
                put("lemma", lemma)
                put("articleId", articleId)
                put("lookedUpAt", System.currentTimeMillis())
                put("sentence", sentence.take(MAX_SENTENCE))
                put("surface", surface.take(64))
            },
        ) != -1L
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

    /** True when a row was removed; a miss leaves the in-memory list claiming otherwise. */
    suspend fun remove(lemma: String): Boolean = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("word", "lemma = ?", arrayOf(lemma)) > 0
    }

    /** True when the note reached a row; false when there was none to write. */
    suspend fun setNote(lemma: String, note: String): Boolean = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "word", ContentValues().apply { put("note", note) }, "lemma = ?", arrayOf(lemma),
        ) > 0
    }

    /**
     * Marks a word as mastered, or puts it back into the review queue.
     *
     * Mastering parks the word at `Long.MAX_VALUE` so no `dueAt <= now` query can
     * match it. Un-mastering has to undo that explicitly: clearing only the flag
     * would leave the parked timestamp in place and the word would never come due
     * again, which is indistinguishable from losing it.
     *
     * Returns false when no row matched, which is the only signal that the flag the
     * UI already flipped in memory did not reach the database.
     */
    suspend fun setMastered(lemma: String, mastered: Boolean): Boolean =
        withContext(Dispatchers.IO) {
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
        ) > 0
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
     * Lemmas whose review has come due, for the highlight in the article body.
     *
     * The same condition [due] uses — saved, not mastered, past its box's interval —
     * but returned as a set of spellings rather than cards: the reader only needs to
     * ask "is this word on the list" while it paints a paragraph, and asking per word
     * would be a query per word. Mastering parks a word at `Long.MAX_VALUE`, so a
     * mastered word can never appear here.
     */
    suspend fun dueLemmas(): Set<String> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT lemma FROM word WHERE mastered = 0 AND dueAt <= ?",
            arrayOf(System.currentTimeMillis().toString()),
        ).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0).lowercase()) }
        }
    }

    /**
     * Applies a review outcome. [correct] promotes the word; a miss demotes it —
     * clearing the mastered flag as well as the box, so it really is back in the
     * queue rather than hidden by a filter every consumer applies.
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
        // A miss has to clear `mastered` as well as drop the box. Leaving the flag
        // alone left the word at `mastered = 1, box = 1` — a state no query can see,
        // because `due()`, `dueCount()`, `dueLemmas()` and the stats screen all filter
        // on that flag — so a mastered word answered wrong never came back. That is
        // what `setMastered(false)` does, and it is what "a miss demotes it" means.
        val mastered = if (correct) {
            "CASE WHEN $nextBox >= $MAX_BOX THEN 1 ELSE mastered END"
        } else {
            "0"
        }
        val intervals = INTERVALS_MS.withIndex()
            .joinToString(" ") { (index, ms) -> "WHEN ${index + 1} THEN ? + $ms" }
        db.writableDatabase.execSQL(
            """
            UPDATE word SET
                box = $nextBox,
                dueAt = CASE $nextBox $intervals ELSE ? END,
                correct = correct + ${if (correct) 1 else 0},
                wrong = wrong + ${if (correct) 0 else 1},
                mastered = $mastered
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
     *
     * A bounded random page first, then a sequential pass only if that page did not
     * fill the card. The random page is what keeps two sittings from offering the
     * same options, but on its own it can offer fewer than [count]: the filter
     * rejects blanks, the answer itself and duplicate glosses, so a small wordbook
     * used to hand the reader a two-option question. `ORDER BY RANDOM()` with a
     * `LIMIT` is a bounded sort of the `word` table, which is why it stays.
     */
    suspend fun distractors(target: SavedWord, count: Int = 3): List<String> =
        withContext(Dispatchers.IO) {
            if (count <= 0) return@withContext emptyList()
            val targetGloss = dictionary.firstGloss(target.translation)
            val out = LinkedHashSet<String>()
            collectDistractors(
                "$SELECT WHERE lemma <> ? AND translation <> '' ORDER BY RANDOM() LIMIT ?",
                arrayOf(target.lemma, (count * 8).toString()),
                targetGloss,
                count,
                out,
            )
            var offset = 0
            val page = count * 32
            while (out.size < count) {
                val rows = collectDistractors(
                    "$SELECT WHERE lemma <> ? AND translation <> '' LIMIT ? OFFSET ?",
                    arrayOf(target.lemma, page.toString(), offset.toString()),
                    targetGloss,
                    count,
                    out,
                )
                if (rows == 0) break
                offset += page
            }
            out.toList()
        }

    /** Adds glosses of the cursor's rows to [out] until it holds [count]; returns rows seen. */
    private fun collectDistractors(
        sql: String,
        args: Array<String>,
        targetGloss: String,
        count: Int,
        out: MutableSet<String>,
    ): Int {
        var rows = 0
        db.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                rows++
                if (out.size >= count) continue
                val gloss = dictionary.firstGloss(c.getString(c.getColumnIndexOrThrow("translation")))
                if (gloss.isNotBlank() && gloss != targetGloss) out += gloss
            }
        }
        return rows
    }

    /**
     * Writes one flush of reading: the time since the last one, and how far into the
     * article the reader had got.
     *
     * [wordsRead] is coverage, not words gained, so it repeats across the flushes of one
     * sitting and the reading-speed figure takes the maximum per article. Storing the
     * increment instead would need the position at the previous flush, which is not known
     * once the reader has left and come back.
     */
    suspend fun recordSession(articleId: Long, seconds: Int, wordsRead: Int, lookups: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (seconds <= 0 && lookups <= 0) return@withContext false
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
            ) != -1L
        }

    /** True when the result reached a row; `insert` returns -1 rather than throwing. */
    suspend fun recordQuiz(articleId: Long, correct: Int, total: Int): Boolean =
        withContext(Dispatchers.IO) {
            db.writableDatabase.insert(
                "quiz", null,
                ContentValues().apply {
                    put("articleId", articleId)
                    put("correct", correct)
                    put("total", total)
                    put("takenAt", System.currentTimeMillis())
                },
            ) != -1L
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

    internal companion object {
        const val SELECT =
            "SELECT lemma, display, translation, phonetic, firstSeenAt, seenCount, mastered, " +
                "box, dueAt, correct, wrong, note FROM word"

        const val MAX_BOX = 5

        /** Longest sentence stored per look-up; a paragraph pasted in as one line is truncated. */
        const val MAX_SENTENCE = 400

        /**
         * Leitner intervals, one per box: 10 min, 1 day, 3 days, 7 days, 21 days.
         *
         * Indexed by `box - 1` — the `CASE` above branches on `WHEN index + 1` because
         * a box is 1-based. A duplicated leading entry here shifted every interval by
         * one box, so the second review came 10 minutes later instead of a day and a
         * word graduated after 7 days instead of 21.
         */
        val INTERVALS_MS = longArrayOf(
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
    /**
     * The key for [millis], always in ASCII digits.
     *
     * `String.format` without a Locale formats with the default one, and a language
     * whose digits are not ASCII (Arabic, Persian, Devanagari, Thai, Burmese) produced
     * keys like `٢٠٢٦-٠٢-٢٥`. Those still sort among themselves, which is why the
     * breakage hides: rows written before a language change keep ASCII keys, and
     * `ORDER BY day` then interleaves two alphabets while `recent`'s set comparison
     * matches neither — the reader's streak reads 0 after studying.
     */
    fun of(millis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        return "%04d-%02d-%02d".format(
            java.util.Locale.US,
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * The keys of the last [days] calendar days, ending with [now]'s own day.
     *
     * A set rather than a count so callers can filter rows by it: the activity tables
     * have no row for a day with no reading, so "the last seven days" cannot be
     * expressed as the first seven rows of a descending list — that reaches back as
     * many calendar days as there were quiet ones.
     *
     * Stepped with `Calendar` rather than by subtracting 24 hours, for the reason
     * `ProgressRepository.streak` documents: on a DST transition day that subtraction
     * lands on the wrong calendar date.
     */
    fun recent(days: Int, now: Long): Set<String> {
        if (days <= 0) return emptySet()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        return buildSet {
            repeat(days) {
                add(of(cal.timeInMillis))
                cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
            }
        }
    }
}
