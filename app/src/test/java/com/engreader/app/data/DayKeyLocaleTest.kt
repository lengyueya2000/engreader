package com.engreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * `DayKey` has to produce the same ASCII key whatever language the phone is set to.
 *
 * The activity tables are read with `ORDER BY day` and compared against the day keys
 * `streak`/`todaySeconds` derive, so a key written with the device's own digits — and
 * the reader's language can change between two launches — interleaves two alphabets and
 * matches neither. The probe that found this ran the real compiled class on a JVM under
 * these locales.
 */
class DayKeyLocaleTest {

    @Test
    fun `day keys keep ascii digits under every localised numbering system`() {
        withLocale("ar-EG", "ar-SA", "fa-IR", "ne-NP", "mr-IN", "bn-IN", "th-TH-u-nu-thai", "my-MM") {
            val key = DayKey.of(millis(2026, Calendar.FEBRUARY, 25))
            assertEquals("2026-02-25", key)
            assertTrue("non-ascii digit in '$key'", key.all { it.code < 128 })
        }
    }

    @Test
    fun `consecutive days still sort and compare under a localised locale`() {
        withLocale("ar-EG") {
            val first = DayKey.of(millis(2026, Calendar.FEBRUARY, 25))
            val second = DayKey.of(millis(2026, Calendar.FEBRUARY, 26))
            assertEquals("2026-02-25", first)
            assertEquals("2026-02-26", second)
            // The ordering the SQL `ORDER BY day` and the speed window rely on.
            assertTrue("$first should sort before $second", first < second)
        }
    }

    @Test
    fun `the recent window is built from the same ascii keys`() {
        withLocale("fa-IR") {
            val now = millis(2026, Calendar.FEBRUARY, 25)
            val keys = DayKey.recent(7, now)
            assertEquals(7, keys.size)
            assertTrue(keys.all { key -> key.all { it.code < 128 } })
            assertTrue(DayKey.of(now) in keys)
        }
    }

    private fun withLocale(vararg tags: String, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            tags.forEach {
                Locale.setDefault(Locale.forLanguageTag(it))
                block()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun millis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, 12, 0, 0)
        }.timeInMillis
}