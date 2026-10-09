package com.engreader.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.engreader.app.model.Article
import com.engreader.app.model.FeedItem
import com.engreader.app.model.QuizQuestion
import com.engreader.app.nlp.VocabularyGrader
import com.engreader.app.nlp.VocabularyProfile
import com.engreader.app.source.ArticleExtractor
import com.engreader.app.source.ArticleFetcher
import com.engreader.app.source.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the article table: turning a feed headline into a readable article,
 * listing what the user has read, and recording reading progress.
 *
 * Every method is main-safe; the heavy work runs on [Dispatchers.IO].
 */
class ArticleRepository(
    private val db: UserDb,
    private val fetcher: ArticleFetcher,
    private val grader: VocabularyGrader,
    private val profile: VocabularyProfile,
) {

    /**
     * Fetches the full text for a headline and stores it, reusing an existing row
     * when the same URL was opened before so the user's progress is preserved.
     */
    suspend fun open(item: FeedItem): Article {
        val existing = findByUrl(item.url)
        if (existing != null && existing.wordCount >= 120) {
            markOpened(existing.id)
            return existing.copy(lastReadAt = System.currentTimeMillis())
        }
        val extracted = fetcher.article(item)
        return persist(item, extracted, existingId = existing?.id)
    }

    /** Saves an already-extracted article; used by the bundled starter content. */
    suspend fun save(item: FeedItem, extracted: ArticleExtractor.Extracted): Article =
        persist(item, extracted, existingId = findByUrl(item.url)?.id)

    /**
     * Content lemmas of [article], computing and caching them on first use.
     *
     * Cached because the personal unknown rate is asked for on the discover list,
     * the home list and in the reader; re-tokenising a 1,000-word body once per
     * screen would be visible, and the answer never changes for a stored body.
     */
    suspend fun contentLemmas(article: Article): List<String> {
        if (article.vocabProfile.isNotEmpty()) return VocabularyProfile.decode(article.vocabProfile)
        val lemmas = withContext(Dispatchers.IO) { profile.contentLemmas(article.body) }
        if (lemmas.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                db.writableDatabase.update(
                    "article",
                    ContentValues().apply { put("vocabProfile", VocabularyProfile.encode(lemmas)) },
                    "id = ?", arrayOf(article.id.toString()),
                )
            }
        }
        return lemmas
    }

    /**
     * Stores a pre-generated translation, used by the bundled passages so they read
     * with Chinese available and no network. Silently ignored when the article has
     * since been removed.
     */
    suspend fun seedTranslation(url: String, translation: List<String>) {
        if (translation.isEmpty()) return
        findByUrl(url)?.let { setTranslation(it.id, translation) }
    }

    private suspend fun persist(
        item: FeedItem,
        extracted: ArticleExtractor.Extracted,
        existingId: Long?,
    ): Article = withContext(Dispatchers.IO) {
        val difficulty = ArticleExtractor.estimateDifficulty(extracted.body, grader.grade(extracted.body).grade)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("sourceId", item.sourceId)
            put("title", extracted.title.ifBlank { item.title })
            put("subtitle", extracted.summary.ifBlank { item.summary })
            put("url", item.url)
            put("author", extracted.author)
            put("publishedAt", extracted.publishedAt.takeIf { it > 0 } ?: item.publishedAt)
            put("difficulty", difficulty)
            // The feed summary is often repeated verbatim as the article's opening
            // paragraph; keeping both shows the reader the same sentence twice.
            put("body", stripDuplicateLead(extracted.body, extracted.summary.ifBlank { item.summary }))
            put("fetchedAt", now)
        }
        val id = if (existingId != null) {
            db.writableDatabase.update("article", values, "id = ?", arrayOf(existingId.toString()))
            existingId
        } else {
            db.writableDatabase.insertWithOnConflict(
                "article", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
        require(id > 0) { "Could not store article ${item.url}" }
        requireNotNull(get(id))
    }

    suspend fun get(id: Long): Article? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("$SELECT WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toArticle() else null }
    }

    suspend fun findByUrl(url: String): Article? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        db.readableDatabase.rawQuery("$SELECT WHERE url = ? LIMIT 1", arrayOf(url))
            .use { if (it.moveToFirst()) it.toArticle() else null }
    }

    /** Recently opened or saved articles, most recent first. Book chapters are excluded. */
    suspend fun recent(limit: Int = 40): List<Article> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "$SELECT WHERE lastReadAt > 0 AND bookId = 0 ORDER BY lastReadAt DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { it.toArticles() }
    }

    suspend fun saved(): List<Article> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "$SELECT WHERE saved = 1 AND bookId = 0 ORDER BY fetchedAt DESC", null,
        ).use { it.toArticles() }
    }

    /**
     * Every stored article, used by the stats screen and by the quiz generator.
     *
     * Book chapters are left out: they are listed through their book, and including
     * them would make the shelf look like it held four hundred articles the moment a
     * novel was imported.
     */
    suspend fun all(): List<Article> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "$SELECT WHERE bookId = 0 ORDER BY fetchedAt DESC", null,
        ).use { it.toArticles() }
    }

    /**
     * Chapters of one book, in reading order.
     *
     * Chapters are ordered by `chapterIndex`, never by `fetchedAt`: they are written
     * in one transaction and would otherwise be at the mercy of how the insert loop
     * happened to time them.
     */
    suspend fun bookChapters(bookId: Long): List<Article> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "$SELECT WHERE bookId = ? ORDER BY chapterIndex ASC", arrayOf(bookId.toString()),
        ).use { it.toArticles() }
    }

    suspend fun chapterAt(bookId: Long, index: Int): Article? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "$SELECT WHERE bookId = ? AND chapterIndex = ? LIMIT 1",
            arrayOf(bookId.toString(), index.toString()),
        ).use { if (it.moveToFirst()) it.toArticle() else null }
    }

    suspend fun setSaved(id: Long, saved: Boolean) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "article",
            ContentValues().apply { put("saved", if (saved) 1 else 0) },
            "id = ?", arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun setTranslation(id: Long, translation: List<String>) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "article",
            ContentValues().apply { put("translation", encodeTranslation(translation)) },
            "id = ?", arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun saveQuiz(id: Long, questions: List<QuizQuestion>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        questions.forEach { q ->
            array.put(
                JSONObject().apply {
                    put("q", q.question)
                    put("o", JSONArray(q.options))
                    put("a", q.answerIndex)
                    put("e", q.explanation)
                }
            )
        }
        db.writableDatabase.update(
            "article",
            ContentValues().apply { put("quizJson", array.toString()) },
            "id = ?", arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun markOpened(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "article",
            ContentValues().apply { put("lastReadAt", System.currentTimeMillis()) },
            "id = ?", arrayOf(id.toString()),
        )
        Unit
    }

    suspend fun addReadSeconds(id: Long, seconds: Int) = withContext(Dispatchers.IO) {
        if (seconds <= 0) return@withContext
        db.writableDatabase.execSQL(
            "UPDATE article SET readSeconds = readSeconds + ? WHERE id = ?",
            arrayOf(seconds, id),
        )
        Unit
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("article", "id = ?", arrayOf(id.toString()))
        Unit
    }

    private fun Cursor.toArticles(): List<Article> = buildList {
        while (moveToNext()) add(toArticle())
    }

    /**
     * Translations are stored as a JSON array rather than joined on blank lines: the
     * list is positional (entry *i* belongs to paragraph *i*), and a blank entry is
     * meaningful — it marks a paragraph that failed or was skipped — so an encoding
     * that could not represent it would shift every later paragraph.
     */
    private fun encodeTranslation(translation: List<String>): String {
        if (translation.isEmpty()) return ""
        val array = JSONArray()
        translation.forEach { array.put(it) }
        return array.toString()
    }

    private fun decodeTranslation(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Drops a leading paragraph that merely repeats the standfirst, which feeds
     * routinely duplicate. Only an exact-ish match is removed, so a real opening
     * sentence is never lost.
     */
    private fun stripDuplicateLead(body: String, summary: String): String {
        val lead = summary.trim()
        if (lead.length < 40) return body
        val paragraphs = body.split("\n\n")
        if (paragraphs.isEmpty()) return body
        val first = paragraphs.first().trim()
        val similar = first.equals(lead, ignoreCase = true) ||
            (first.length > 40 && lead.startsWith(first, ignoreCase = true)) ||
            (lead.length > 40 && first.startsWith(lead, ignoreCase = true))
        return if (similar && paragraphs.size > 1) {
            paragraphs.drop(1).joinToString("\n\n")
        } else {
            body
        }
    }

    private fun Cursor.toArticle(): Article {
        val sourceId = getString(getColumnIndexOrThrow("sourceId"))
        return Article(
            id = getLong(getColumnIndexOrThrow("id")),
            sourceId = sourceId,
            sourceName = Catalog.displayName(sourceId),
            title = getString(getColumnIndexOrThrow("title")),
            subtitle = getString(getColumnIndexOrThrow("subtitle")),
            url = getString(getColumnIndexOrThrow("url")),
            author = getString(getColumnIndexOrThrow("author")),
            publishedAt = getLong(getColumnIndexOrThrow("publishedAt")),
            difficulty = getInt(getColumnIndexOrThrow("difficulty")),
            body = getString(getColumnIndexOrThrow("body")),
            saved = getInt(getColumnIndexOrThrow("saved")) == 1,
            fetchedAt = getLong(getColumnIndexOrThrow("fetchedAt")),
            lastReadAt = getLong(getColumnIndexOrThrow("lastReadAt")),
            readSeconds = getInt(getColumnIndexOrThrow("readSeconds")),
            quiz = parseQuiz(getString(getColumnIndexOrThrow("quizJson"))),
            translation = decodeTranslation(getString(getColumnIndexOrThrow("translation"))),
            vocabProfile = getString(getColumnIndexOrThrow("vocabProfile")),
            bookId = getLong(getColumnIndexOrThrow("bookId")),
            chapterIndex = getInt(getColumnIndexOrThrow("chapterIndex")),
        )
    }

    private fun parseQuiz(json: String): List<QuizQuestion> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val options = o.getJSONArray("o")
                    add(
                        QuizQuestion(
                            question = o.getString("q"),
                            options = buildList {
                                for (j in 0 until options.length()) add(options.getString(j))
                            },
                            answerIndex = o.getInt("a"),
                            explanation = o.optString("e"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val SELECT =
            "SELECT id, sourceId, title, subtitle, url, author, publishedAt, difficulty, body, " +
                "saved, fetchedAt, lastReadAt, readSeconds, quizJson, translation, vocabProfile, " +
                "bookId, chapterIndex " +
                "FROM article"
    }
}
