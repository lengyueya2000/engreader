package com.engreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The date and accuracy arithmetic the statistics screens are built on.
 *
 * All of it is pure — a day key is a formatted calendar date and an accuracy is a ratio
 * of two counters — so none of it needs a database to check.
 */
class StudyStatsTest {

    // ------------------------------------------------------------------- DayKey

    @Test
    fun `a day key is a zero-padded calendar date`() {
        // Zero padding is what makes the keys sort lexicographically, which the
        // `ORDER BY day` in the activity queries depends on.
        val january = calendar(2024, Calendar.JANUARY, 5).timeInMillis
        assertEquals("2024-01-05", DayKey.of(january))
        assertEquals("2024-11-23", DayKey.of(calendar(2024, Calendar.NOVEMBER, 23).timeInMillis))
    }

    @Test
    fun `the recent window holds seven distinct days ending today`() {
        val now = calendar(2024, Calendar.MARCH, 1, hour = 12).timeInMillis
        val keys = DayKey.recent(7, now)

        assertEquals(7, keys.size)
        assertTrue(keys.contains(DayKey.of(now)))
        // Six days back is inside the window; seven is not. The window is seven days
        // *including* today, which is the off-by-one the calendar stepping exists for.
        assertTrue(keys.contains(DayKey.of(daysBefore(now, 6))))
        assertFalse(keys.contains(DayKey.of(daysBefore(now, 7))))
    }

    @Test
    fun `the recent window steps by calendar day across a month end`() {
        // 2024 is a leap year, so the seven days ending 1 March include 29 February.
        // Stepping by 24 hours rather than by calendar day is what this guards: it
        // would skip or repeat a date wherever the day is not 24 hours long.
        val now = calendar(2024, Calendar.MARCH, 1, hour = 12).timeInMillis
        val keys = DayKey.recent(7, now)
        assertEquals(7, keys.size)
        assertEquals(setOf(6, 5, 4, 3, 2, 1, 0).map { DayKey.of(daysBefore(now, it)) }.toSet(), keys)
    }

    @Test
    fun `a window of no days is empty`() {
        assertTrue(DayKey.recent(0, System.currentTimeMillis()).isEmpty())
        assertTrue(DayKey.recent(-1, System.currentTimeMillis()).isEmpty())
    }

    // ---------------------------------------------------------------- SavedWord

    @Test
    fun `a word with no reviews has no accuracy to report`() {
        val word = word(correct = 0, wrong = 0)
        assertEquals(0, word.attempts)
        // 0% is what an untouched word reads as, which is why the caller has to ask
        // `attempts` first — the two are indistinguishable from the number alone.
        assertEquals(0f, word.accuracy, 0f)
    }

    @Test
    fun `accuracy is the share of reviews answered correctly`() {
        assertEquals(4, word(correct = 3, wrong = 1).attempts)
        assertEquals(0.75f, word(correct = 3, wrong = 1).accuracy, 0.0001f)
        assertEquals(1f, word(correct = 5, wrong = 0).accuracy, 0.0001f)
    }

    @Test
    fun `a word answered only wrong reads as zero percent but has attempts`() {
        val word = word(correct = 0, wrong = 2)
        assertEquals(2, word.attempts)
        assertEquals(0f, word.accuracy, 0f)
    }

    @Test
    fun `mastering a word parks it far enough ahead to never come due`() {
        // `setMastered` writes `Long.MAX_VALUE` into `dueAt`, which is what keeps every
        // `dueAt <= now` query from matching it.
        assertFalse(word(dueAt = Long.MAX_VALUE).isDue)
        assertTrue(word(dueAt = System.currentTimeMillis() - 1).isDue)
        assertFalse(word(dueAt = System.currentTimeMillis() + 60_000).isDue)
    }

    // ------------------------------------------------------------------ helpers

    private fun word(correct: Int = 0, wrong: Int = 0, dueAt: Long = 0L) = SavedWord(
        lemma = "derive",
        display = "derived",
        translation = "v. 得出",
        phonetic = "",
        firstSeenAt = 0L,
        seenCount = 1,
        mastered = dueAt == Long.MAX_VALUE,
        box = 1,
        dueAt = dueAt,
        correct = correct,
        wrong = wrong,
        note = "",
    )

    private fun calendar(year: Int, month: Int, day: Int, hour: Int = 0): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }

    private fun daysBefore(millis: Long, days: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = millis
            add(Calendar.DAY_OF_MONTH, -days)
        }.timeInMillis
}
