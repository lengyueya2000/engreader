package com.engreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stand-in article id a restored session gets when its article is not on this
 * device.
 *
 * What matters is the arithmetic, not the feed: the id has to be unusable as a real
 * rowid, stable for one article, and distinct for two — the reading-speed window
 * groups sessions by it.
 */
class RestoreArticleIdTest {

    @Test
    fun `a synthetic id can never be a real rowid`() {
        val id = BackupRepository.syntheticArticleId("https://example.com/a")
        assertTrue("expected a negative id, got $id", id < 0)
        // -id lies in [SYNTHETIC_BASE, 2 * SYNTHETIC_BASE), i.e. below the range a
        // negated legacy rowid occupies.
        assertTrue(-id in BackupRepository.SYNTHETIC_BASE until 2 * BackupRepository.SYNTHETIC_BASE)
    }

    @Test
    fun `the same article key always yields the same id`() {
        assertEquals(
            BackupRepository.syntheticArticleId("book:7/3"),
            BackupRepository.syntheticArticleId("book:7/3"),
        )
    }

    @Test
    fun `different article keys yield different ids`() {
        val keys = listOf(
            "https://example.com/a",
            "https://example.com/b",
            "book:1/0",
            "book:1/1",
            "book:2/0",
        )
        val ids = keys.map { BackupRepository.syntheticArticleId(it) }
        assertEquals("ids collided: $ids", ids.size, ids.toSet().size)
    }
}