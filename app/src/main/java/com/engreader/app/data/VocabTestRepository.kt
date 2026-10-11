package com.engreader.app.data

import com.engreader.app.dict.Dictionary
import com.engreader.app.nlp.VocabEstimate
import com.engreader.app.nlp.VocabProbe
import com.engreader.app.nlp.VocabularySize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One past sitting of the vocabulary-size test. */
data class VocabResult(
    val takenAt: Long,
    val estimate: Int,
    val low: Int,
    val high: Int,
    val sampled: Int,
    val known: Int,
)

/**
 * Runs the vocabulary-size test and keeps its history.
 *
 * The words come from the bundled dictionary by frequency band, and the arithmetic is
 * [VocabularySize]'s. This class is the seam between the two: it turns a seed into a
 * question list and answers into a stored row.
 */
class VocabTestRepository(
    private val db: UserDb,
    private val dictionary: Dictionary,
) {

    /**
     * A fresh question list, [perBand] words from each band.
     *
     * [seed] is the test's start time, so a retest asks a different set and measures
     * vocabulary rather than memory of the previous questions. Two calls with the same
     * seed return the same list, which is what makes the sampling testable.
     */
    suspend fun sample(seed: Long, perBand: Int = VocabularySize.PER_BAND): List<VocabProbe> =
        withContext(Dispatchers.IO) {
            buildList {
                VocabularySize.BANDS.forEach { band ->
                    val available = dictionary.countInRank(band.from, band.to)
                    VocabularySize.offsets(seed, band, available, perBand).forEach { offset ->
                        dictionary.wordAtRank(band.from, band.to, offset)?.let { word ->
                            add(VocabProbe(word, band.index))
                        }
                    }
                }
            }
        }

    /** Stores a finished test. [takenAt] is now, so the chart's x-axis is real time. */
    suspend fun record(result: VocabEstimate, takenAt: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            db.writableDatabase.execSQL(
                "INSERT INTO vocab_test (takenAt, estimate, low, high, sampled, known) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(
                    takenAt, result.estimate, result.low, result.high, result.sampled, result.known,
                ),
            )
            Unit
        }

    /** Past tests, oldest first so the chart reads left to right. */
    suspend fun history(limit: Int = 60): List<VocabResult> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT takenAt, estimate, low, high, sampled, known FROM vocab_test " +
                "ORDER BY takenAt DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        VocabResult(
                            takenAt = c.getLong(0),
                            estimate = c.getInt(1),
                            low = c.getInt(2),
                            high = c.getInt(3),
                            sampled = c.getInt(4),
                            known = c.getInt(5),
                        )
                    )
                }
            }.reversed()
        }
    }

    suspend fun latest(): VocabResult? = history(limit = 1).lastOrNull()

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM vocab_test", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
