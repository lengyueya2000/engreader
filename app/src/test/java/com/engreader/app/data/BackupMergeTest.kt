package com.engreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rules a restore applies to one word at a time.
 *
 * `restore` itself needs a real `SQLiteDatabase`, so what is pinned here is the decision
 * it makes per row. The rule that matters: a word already in the wordbook is only
 * overwritten when the file's box is further along.
 *
 * The reason `decide` exists as a function at all is a bug it now prevents. `word.lemma`
 * is a NOCASE primary key, and `restore` used to decide "insert or update" against a map
 * it never wrote back to. A backup carrying the same lemma twice — two devices' exports
 * merged by hand, or the same word differing only in case — therefore tried to insert it
 * twice, hit the unique constraint, and rolled back the entire restore. The reader was
 * told the import had failed and got none of it, from a file that was perfectly good.
 * The caller now feeds every decision back into the map; these tests pin the rule that
 * makes the second occurrence an update or a skip instead of a second insert.
 */
class BackupMergeTest {

    @Test
    fun `a word the wordbook has never seen is inserted`() {
        assertEquals(BackupRepository.Merge.Insert, BackupRepository.decide(null, 0))
        assertEquals(BackupRepository.Merge.Insert, BackupRepository.decide(null, 5))
    }

    @Test
    fun `a further-along box replaces the stored one`() {
        assertEquals(BackupRepository.Merge.Update, BackupRepository.decide(1, 2))
        assertEquals(BackupRepository.Merge.Update, BackupRepository.decide(0, 5))
    }

    @Test
    fun `an equal or earlier box leaves the wordbook alone`() {
        // A reader importing an old file must not have today's progress rolled back.
        assertEquals(BackupRepository.Merge.Skip, BackupRepository.decide(3, 3))
        assertEquals(BackupRepository.Merge.Skip, BackupRepository.decide(5, 1))
        assertEquals(BackupRepository.Merge.Skip, BackupRepository.decide(2, 0))
    }

    @Test
    fun `the same lemma twice never produces two inserts`() {
        // This is the shape that used to roll back the whole restore. Walk a file that
        // repeats a word — once in lower case, once capitalised — through the caller's
        // loop, feeding each decision back the way `restore` now does.
        val known = mutableMapOf<String, Int>()
        val decisions = mutableListOf<BackupRepository.Merge>()
        for ((lemma, box) in listOf("apple" to 1, "Apple" to 2, "APPLE" to 2)) {
            val key = lemma.lowercase()
            val decision = BackupRepository.decide(known[key], box)
            decisions += decision
            if (decision != BackupRepository.Merge.Skip) known[key] = box
        }
        assertEquals(
            listOf(
                BackupRepository.Merge.Insert,
                BackupRepository.Merge.Update,
                BackupRepository.Merge.Skip,
            ),
            decisions,
        )
        assertEquals(1, known.size)
        assertEquals(2, known["apple"])
    }

    @Test
    fun `a file repeating one word inserts it exactly once`() {
        // Without the write-back every one of these would be an Insert, and the second
        // would violate the NOCASE primary key.
        val known = mutableMapOf<String, Int>()
        val inserts = listOf("beta", "beta", "Beta", "BETA").count { lemma ->
            val key = lemma.lowercase()
            val decision = BackupRepository.decide(known[key], 0)
            if (decision != BackupRepository.Merge.Skip) known[key] = 0
            decision == BackupRepository.Merge.Insert
        }
        assertEquals(1, inserts)
        assertTrue(known.isNotEmpty())
    }
}
