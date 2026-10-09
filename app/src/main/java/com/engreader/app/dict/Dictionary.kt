package com.engreader.app.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Read-only word look-up. The grammar analyser and the quiz builder depend on this
 * rather than on [Dictionary] directly, so both can be exercised without an
 * Android context.
 */
interface Lexicon {
    fun lookup(raw: String): WordEntry?

    /** Chinese glosses as `(part of speech, gloss)` pairs, domain labels removed. */
    fun glosses(translation: String): List<Pair<PartOfSpeech, String>>

    /** First Chinese gloss of an entry's translation blob. */
    fun firstGloss(translation: String): String

    /** Prefix suggestions as `(word, gloss)` pairs. */
    fun suggest(prefix: String, limit: Int = 20): List<Pair<String, String>>

    /** Glosses of the same part of speech and a similar frequency, for quiz options. */
    fun distractors(entry: WordEntry, pos: PartOfSpeech, count: Int): List<String>

    /** English definitions of a headword, used by the in-English mode. */
    fun definitions(headword: String): List<Pair<PartOfSpeech, String>>
}

/**
 * Read-only access to the dictionary bundled in `assets/dict.db`.
 *
 * The asset is copied into internal storage on first launch so SQLite can open it
 * with a real file path; afterwards the copy is reused. All access is synchronous
 * and callers are expected to be on a background dispatcher.
 */
class Dictionary(private val context: Context) : Lexicon, FamilyLexicon {

    @Volatile
    private var db: SQLiteDatabase? = null

    private val file: File get() = File(context.filesDir, DB_NAME)

    private fun open(): SQLiteDatabase {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            if (!file.exists() || file.length() == 0L) {
                context.assets.open(DB_NAME).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val opened = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            db = opened
            return opened
        }
    }

    /**
     * Resolves a tapped word to the reading a learner most likely wants.
     *
     * Many English forms are also headwords in their own right: `found` is the verb
     * 建立 but is usually `find` in the past tense, and `left` is 左边的 but usually
     * `leave`. Rather than pick one arbitrarily, both readings are ranked by corpus
     * frequency; the more common one becomes the entry and the other is named as
     * [WordEntry.alsoForm] so the sheet can offer it.
     */
    override fun lookup(raw: String): WordEntry? {
        val query = raw.trim().trimEnd('.', ',', ';', ':', '!', '?', '"', '\'', ')', ']')
        if (query.isEmpty()) return null
        val lower = query.lowercase()

        val own = entryFor(lower, query)
        val lemma = lemmaFor(lower)?.takeIf { !it.equals(lower, ignoreCase = true) }
        val fromLemma = lemma?.let { entryFor(it, query) }

        return when {
            own == null -> fromLemma
            fromLemma == null -> own
            frequencyRank(own) <= frequencyRank(fromLemma) -> own.withAlternative(fromLemma)
            else -> fromLemma.withAlternative(own)
        }
    }

    /**
     * Attaches the reading that was not chosen, unless it is the form the user
     * already tapped — "was 也可以理解为 was" would be nonsense.
     */
    private fun WordEntry.withAlternative(other: WordEntry): WordEntry {
        if (other.lemma.equals(queried, ignoreCase = true) ||
            other.lemma.equals(lemma, ignoreCase = true)
        ) {
            return this
        }
        return copy(
            otherReadings = listOf(
                other.lemma to glosses(other.translation).map { (pos, gloss) ->
                    if (pos == PartOfSpeech.Unknown) gloss else "${pos.label} $gloss"
                },
            ),
        )
    }

    /** Lower is more common. Unranked words sort last. */
    private fun frequencyRank(entry: WordEntry): Int =
        listOf(entry.frq, entry.bnc).filter { it > 0 }.minOrNull() ?: Int.MAX_VALUE

    /** Looks up a specific headword, used when the user switches to the other reading. */
    fun lookupHeadword(headword: String, queried: String): WordEntry? =
        entryFor(headword.lowercase(), queried)

    /**
     * Glosses that could plausibly be mistaken for [entry]'s.
     *
     * Distractors have to be the same part of speech and a comparable frequency, or
     * the question is answerable without reading: pairing 中心 with 表面暗淡的 gives
     * the answer away by register alone. Candidates are drawn from a frequency
     * window around the target and filtered by part of speech.
     */
    override fun distractors(entry: WordEntry, pos: PartOfSpeech, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val target = firstGloss(entry.translation)
            .substringBefore(',').substringBefore('；').substringBefore(';').trim()
        if (target.isBlank()) return emptyList()
        val rank = listOf(entry.frq, entry.bnc).filter { it > 0 }.minOrNull() ?: 8000
        val low = (rank / 3).coerceAtLeast(1)
        val high = (rank * 3).coerceAtMost(60_000)

        val out = LinkedHashSet<String>()
        open().rawQuery(
            "SELECT translation FROM word WHERE frq BETWEEN ? AND ? AND word <> ? " +
                "ORDER BY RANDOM() LIMIT 120",
            arrayOf(low.toString(), high.toString(), entry.lemma),
        ).use { c ->
            while (c.moveToNext() && out.size < count) {
                val translation = c.getString(0).orEmpty()
                // Same first-sense-only trimming the quiz applies to the answer, so
                // no option stands out by being visibly longer.
                val gloss = firstGloss(translation)
                    .substringBefore(',').substringBefore('；').substringBefore(';').trim()
                if (gloss.isBlank() || gloss == target || gloss.length > 12) continue
                if (out.contains(gloss)) continue
                val candidatePos = PartOfSpeechParser.parse(
                    translation.split("\\n", "\n").firstOrNull { it.isNotBlank() }.orEmpty(),
                )
                if (candidatePos == pos) out += gloss
            }
        }
        return out.toList()
    }

    private fun lemmaFor(form: String): String? =
        open().rawQuery("SELECT lemma FROM form WHERE form = ? LIMIT 1", arrayOf(form))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun entryFor(lemma: String, queried: String): WordEntry? =
        open().rawQuery(
            "SELECT word, phonetic, translation, definition, collins, oxford, tag, frq, bnc, exchange " +
                "FROM word WHERE word = ? LIMIT 1",
            arrayOf(lemma),
        ).use { c ->
            if (!c.moveToFirst()) return null
            WordEntry(
                lemma = c.getString(0),
                queried = queried,
                phonetic = c.getString(1).orEmpty(),
                translation = c.getString(2).orEmpty(),
                definition = c.getString(3).orEmpty(),
                collins = c.getInt(4),
                oxford = c.getInt(5) == 1,
                tag = c.getString(6).orEmpty(),
                frq = c.getInt(7),
                bnc = c.getInt(8),
                exchange = c.getString(9).orEmpty(),
            )
        }

    /**
     * English definitions of [headword], resolved through the lemma table so the
     * reader can ask for the definition of `derived` and get `derive`'s.
     */
    override fun definitions(headword: String): List<Pair<PartOfSpeech, String>> {
        val key = headword.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        val resolved = lemmaFor(key) ?: key
        return entryFor(resolved, headword)?.definitionLines.orEmpty()
    }

    /**
     * Headwords beginning with [prefix], for the word-family panel.
     *
     * Ordered by corpus frequency so the common derivation (`derivation`) comes
     * before the obscure one (`derivational`), and capped because the panel shows at
     * most a handful.
     */
    override fun wordsStartingWith(prefix: String, limit: Int): List<WordForm> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        return open().rawQuery(
            "SELECT word, translation FROM word WHERE word LIKE ? AND translation <> '' " +
                "ORDER BY collins DESC, frq ASC LIMIT ?",
            arrayOf("$p%", limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val word = c.getString(0)
                    val gloss = firstGloss(c.getString(1).orEmpty())
                    if (gloss.isNotBlank()) add(WordForm(word, gloss))
                }
            }
        }
    }

    /** Short gloss of one headword, resolved through the lemma table. */
    override fun shortGloss(headword: String): String {
        val key = headword.trim().lowercase()
        if (key.isEmpty()) return ""
        val resolved = lemmaFor(key) ?: key
        return entryFor(resolved, headword)?.let { firstGloss(it.translation) }.orEmpty()
    }

    /** Related forms of [entry] for the look-up sheet's family panel. */
    fun family(entry: WordEntry): List<WordForm> = WordFamily.related(this, entry)

    /** Prefix suggestions for the wordbook search field. */
    override fun suggest(prefix: String, limit: Int): List<Pair<String, String>> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        return open().rawQuery(
            "SELECT word, translation FROM word WHERE word LIKE ? " +
                "ORDER BY collins DESC, frq ASC LIMIT ?",
            arrayOf("$p%", limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.getString(0) to firstGloss(c.getString(1).orEmpty()))
                }
            }
        }
    }

    /** First Chinese gloss only — everything after a newline is noise in a list row. */
    override fun firstGloss(translation: String): String =
        glosses(translation).firstOrNull()?.second
            ?: translation.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * Splits an ECDICT translation blob into `(part of speech, gloss)` rows.
     *
     * Entries look like `n. 分析\n[计] 分析机` or `v. 相信`. Lines in square
     * brackets carry a subject label rather than a part of speech — `[计]` is
     * computing, `[医]` medicine — and for a reader working through news prose
     * those technical senses are noise, so they are dropped unless they are all
     * the entry has.
     */
    override fun glosses(translation: String): List<Pair<PartOfSpeech, String>> {
        val lines = translation.split("\\n", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        val general = lines.filterNot { it.startsWith("[") }
        val chosen = general.ifEmpty { lines }
        return chosen.map { line ->
            PartOfSpeechParser.parse(line) to PartOfSpeechParser.stripPrefix(line)
        }
    }

    fun close() {
        synchronized(this) {
            db?.close()
            db = null
        }
    }

    companion object {
        private const val DB_NAME = "dict.db"
    }
}
