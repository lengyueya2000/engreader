package com.engreader.app.data

import com.engreader.app.dict.Dictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the stats screen shows, assembled in one pass. */
data class ProgressSnapshot(
    val streakDays: Int,
    val todaySeconds: Int,
    val weekSeconds: Int,
    val articlesStarted: Int,
    val articlesFinished: Int,
    /**
     * Words in the distinct articles the reader has spent time on.
     *
     * Distinct, not summed over sessions: the article's whole word count is written
     * on every flush, so a piece read three times used to be counted three times.
     */
    val wordsRead: Int,
    val lookups: Int,
    val wordbookTotal: Int,
    val wordbookMastered: Int,
    val dueNow: Int,
    val quizAccuracy: Float,
    val quizCount: Int,
    val recentDays: List<StudyDay>,
    val topWords: List<Pair<String, Int>>,
    val bands: Map<com.engreader.app.dict.DifficultyBand, Int>,
    /** Reading speed over the last two weeks, or null when there is too little data. */
    val speed: ReadingSpeed?,
)

/**
 * Reading speed, in words per minute.
 *
 * An estimate rather than a measurement: the session table stores the article's
 * whole word count against the time spent on it, so a piece left half-read reports
 * faster than it was. Sessions shorter than [ReadingSpeed.MIN_SESSION_SECONDS] are
 * excluded, because a few seconds on a long article would otherwise dominate the
 * average, and the figure is only produced once enough time has accumulated to be
 * worth reading at all.
 */
data class ReadingSpeed(
    /** Words per minute across the window. */
    val wordsPerMinute: Int,
    /** Words per minute over the same window a week earlier, or 0 when unknown. */
    val previousWordsPerMinute: Int,
    /** Total minutes the figure is based on, so the UI can say how solid it is. */
    val sampledMinutes: Int,
) {
    /** Positive when the reader has sped up since the previous window. */
    val change: Int get() = if (previousWordsPerMinute <= 0) 0 else wordsPerMinute - previousWordsPerMinute

    val hasTrend: Boolean get() = previousWordsPerMinute > 0

    companion object {
        /** Below this, a session is a glance rather than reading. */
        const val MIN_SESSION_SECONDS = 30
    }
}

/**
 * Aggregates the study tables into the numbers the progress screen renders.
 *
 * Kept separate from [WordbookRepository] because these are cross-table rollups:
 * they read sessions, lookups and the wordbook together and are only needed when
 * the progress screen is on screen.
 */
class ProgressRepository(
    private val db: UserDb,
    private val dictionary: Dictionary,
) {

    suspend fun snapshot(): ProgressSnapshot = withContext(Dispatchers.IO) {
        val today = DayKey.of(System.currentTimeMillis())
        val days = readDays(limit = 60)
        val byDay = days.associateBy { it.day }

        val wordsRead = distinctWordsRead()

        // Look-ups are counted from the lookup table rather than from the session
        // rollup: every look-up writes a row there immediately, while a session is
        // only written when the reader closes, so the two can disagree mid-session.
        val lookups = count("SELECT COUNT(*) FROM lookup")

        val week = days.take(7).sumOf { it.seconds }

        ProgressSnapshot(
            streakDays = streak(days.map { it.day }),
            todaySeconds = byDay[today]?.seconds ?: 0,
            weekSeconds = week,
            articlesStarted = count("SELECT COUNT(*) FROM article WHERE lastReadAt > 0"),
            articlesFinished = count("SELECT COUNT(DISTINCT articleId) FROM session WHERE seconds > 60"),
            wordsRead = wordsRead,
            lookups = lookups,
            wordbookTotal = count("SELECT COUNT(*) FROM word"),
            wordbookMastered = count("SELECT COUNT(*) FROM word WHERE mastered = 1"),
            dueNow = countParam(
                "SELECT COUNT(*) FROM word WHERE mastered = 0 AND dueAt <= ?",
                System.currentTimeMillis().toString(),
            ),
            quizAccuracy = quizAccuracy(),
            quizCount = count("SELECT COUNT(*) FROM quiz"),
            recentDays = days.take(14).reversed(),
            topWords = topLookups(),
            bands = wordBands(),
            speed = readingSpeed(),
        )
    }

    /**
     * Words in the distinct articles that have been read.
     *
     * `MAX(wordsRead)` per article rather than `SUM`: every flush writes the
     * article's whole word count, so summing counts a re-read as new words. The
     * article's own count is constant across its rows, so the maximum is it.
     */
    private fun distinctWordsRead(): Int =
        db.readableDatabase.rawQuery(
            "SELECT SUM(w) FROM (SELECT MAX(wordsRead) w FROM session GROUP BY articleId)",
            null,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Reading speed over the last 14 days, against the 14 days before that.
     *
     * Summed rather than averaged per session: a single 40-second session on a
     * 600-word article would otherwise report 900 wpm and swamp a per-session mean.
     * Summing words and time separately weights each session by how long it lasted,
     * which is what "how fast do I read" means.
     */
    private fun readingSpeed(): ReadingSpeed? {
        val now = System.currentTimeMillis()
        val windowMs = 14L * 24 * 3_600_000
        val recent = speedWindow(now - windowMs, now)
        val previous = speedWindow(now - 2 * windowMs, now - windowMs)

        val minutes = recent.first / 60
        if (minutes < MIN_TOTAL_MINUTES) return null
        return ReadingSpeed(
            wordsPerMinute = recent.second.toFloat().div(recent.first / 60f).toInt(),
            previousWordsPerMinute = if (previous.first / 60 >= MIN_TOTAL_MINUTES) {
                previous.second.toFloat().div(previous.first / 60f).toInt()
            } else {
                0
            },
            sampledMinutes = minutes,
        )
    }

    /** `(seconds, words)` read in `[from, to)`, ignoring glances. */
    private fun speedWindow(from: Long, to: Long): Pair<Int, Int> =
        db.readableDatabase.rawQuery(
            "SELECT SUM(seconds), SUM(wordsRead) FROM session " +
                "WHERE finishedAt >= ? AND finishedAt < ? AND seconds >= ?",
            arrayOf(from.toString(), to.toString(), ReadingSpeed.MIN_SESSION_SECONDS.toString()),
        ).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) 0 to 0 else c.getInt(0) to c.getInt(1)
        }

    private fun readDays(limit: Int): List<StudyDay> =
        db.readableDatabase.rawQuery(
            "SELECT day, SUM(seconds), SUM(lookups), SUM(wordsRead) FROM session " +
                "GROUP BY day ORDER BY day DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(StudyDay(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3)))
                }
            }
        }

    /**
     * Consecutive days with activity, counting back from today or yesterday.
     *
     * "Yesterday" is stepped with `Calendar`, not by subtracting 24 hours: on a DST
     * transition day that subtraction lands on the wrong calendar date, and the
     * streak would then read 0 for someone who had studied both days.
     */
    private fun streak(dayKeysDescending: List<String>): Int {
        if (dayKeysDescending.isEmpty()) return 0
        val today = DayKey.of(System.currentTimeMillis())
        val yesterday = DayKey.of(
            java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_MONTH, -1)
            }.timeInMillis,
        )
        val first = dayKeysDescending.first()
        if (first != today && first != yesterday) return 0

        var count = 0
        var cursor = java.util.Calendar.getInstance()
        if (first == yesterday) cursor.add(java.util.Calendar.DAY_OF_MONTH, -1)
        val present = dayKeysDescending.toHashSet()
        while (present.contains(DayKey.of(cursor.timeInMillis))) {
            count++
            cursor.add(java.util.Calendar.DAY_OF_MONTH, -1)
        }
        return count
    }

    private fun quizAccuracy(): Float {
        db.readableDatabase.rawQuery("SELECT SUM(correct), SUM(total) FROM quiz", null).use { c ->
            if (!c.moveToFirst()) return 0f
            val correct = c.getInt(0)
            val total = c.getInt(1)
            return if (total == 0) 0f else correct.toFloat() / total
        }
    }

    private fun topLookups(): List<Pair<String, Int>> =
        db.readableDatabase.rawQuery(
            "SELECT lemma, COUNT(*) c FROM lookup GROUP BY lemma ORDER BY c DESC LIMIT 10", null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }

    private fun wordBands(): Map<com.engreader.app.dict.DifficultyBand, Int> {
        val lemmas = db.readableDatabase.rawQuery("SELECT lemma FROM word", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val counts = mutableMapOf<com.engreader.app.dict.DifficultyBand, Int>()
        lemmas.forEach { lemma ->
            val band = dictionary.lookup(lemma)?.band ?: com.engreader.app.dict.DifficultyBand.Unknown
            counts[band] = (counts[band] ?: 0) + 1
        }
        return counts
    }

    private fun count(sql: String): Int =
        db.readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun countParam(sql: String, vararg args: String): Int =
        db.readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private companion object {
        /** Minutes of reading below which a speed figure says more about noise than about the reader. */
        const val MIN_TOTAL_MINUTES = 20
    }
}
