package com.engreader.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.engreader.app.model.Article
import com.engreader.app.model.FeedItem
import com.engreader.app.model.QuizQuestion
import com.engreader.app.model.SearchHit
import com.engreader.app.nlp.Paragraphs
import com.engreader.app.nlp.Tokenizer
import com.engreader.app.nlp.VocabularyGrader
import com.engreader.app.nlp.VocabularyProfile
import com.engreader.app.source.ArticleExtractor
import com.engreader.app.source.ArticleFetcher
import com.engreader.app.source.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        // The feed summary is often repeated verbatim as the article's opening
        // paragraph; keeping both shows the reader the same sentence twice.
        val body = stripDuplicateLead(extracted.body, extracted.summary.ifBlank { item.summary })
        val values = ContentValues().apply {
            put("sourceId", item.sourceId)
            put("title", extracted.title.ifBlank { item.title })
            put("subtitle", extracted.summary.ifBlank { item.summary })
            put("url", item.url)
            put("author", extracted.author)
            put("publishedAt", extracted.publishedAt.takeIf { it > 0 } ?: item.publishedAt)
            put("difficulty", difficulty)
            put("body", body)
            put("wordCount", Tokenizer.countWords(body))
            // The body may have changed under a re-fetch, so the cached lemma list no
            // longer describes it. Left in place, the personal unknown rate and the new
            // words panel would report the previous version of the article forever.
            put("vocabProfile", "")
            put("fetchedAt", now)
        }
        val id = if (existingId != null) {
            // The translation is keyed by paragraph *position*, and the reader only
            // checks that the count still lines up. A re-fetch returning the same
            // number of paragraphs of different text therefore showed the old Chinese
            // under the new English. It is dropped only when the text really changed:
            // rebuilding costs a network round-trip, and a re-fetch that returned the
            // same body leaves it valid.
            if (!bodyUnchanged(existingId, body)) values.put("translation", "")
            db.writableDatabase.update("article", values, "id = ?", arrayOf(existingId.toString()))
            existingId
        } else {
            val inserted = db.writableDatabase.insertWithOnConflict(
                "article", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
            // `CONFLICT_IGNORE` returns -1 when another row already holds this URL,
            // which happens when two fetches of the same headline race. The stored row
            // is the right answer either way, so it is read back rather than treated as
            // a failure.
            if (inserted > 0) inserted else findByUrl(item.url)?.id ?: -1L
        }
        require(id > 0) { "Could not store article ${item.url}" }
        requireNotNull(get(id))
    }

    /**
     * True when the body already stored for [id] is the same text as [body].
     *
     * Compared inside SQLite rather than by reading the stored body back: the point
     * is to decide whether anything derived from the old body is still valid, and
     * pulling a full article into the heap to compare two strings would be the most
     * expensive part of the re-fetch.
     */
    private fun bodyUnchanged(id: Long, body: String): Boolean =
        db.readableDatabase.rawQuery(
            "SELECT body = ? FROM article WHERE id = ?",
            arrayOf(body, id.toString()),
        ).use { it.moveToFirst() && it.getInt(0) == 1 }

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
     * Whether any article is stored at all, without loading one.
     *
     * The first-launch seed check only needs a yes/no, and reading every row to
     * answer it meant tokenising the whole library before the home list could show.
     */
    suspend fun hasAny(): Boolean = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT 1 FROM article LIMIT 1", null)
            .use { it.moveToFirst() }
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

    /**
     * Records how far down the text the reader had got.
     *
     * Written as a paragraph number rather than a pixel offset: the offset depends on
     * the font size, the line height and the window width, all of which change, while
     * the paragraph the reader stopped at is the same paragraph afterwards.
     */
    suspend fun setReadParagraph(id: Long, paragraph: Int) = withContext(Dispatchers.IO) {
        if (id <= 0 || paragraph < 0) return@withContext
        db.writableDatabase.execSQL(
            "UPDATE article SET readParagraph = ? WHERE id = ?",
            arrayOf(paragraph, id),
        )
        Unit
    }

    /**
     * Finds the paragraphs containing [query].
     *
     * Scoped to the chapter when the reader is in one, and to the whole book when the
     * row is a chapter: a novel is searched as a novel, and a single article as itself.
     * The scan is done in memory rather than with SQL `LIKE`, because a paragraph is the
     * unit of a result and the paragraph boundaries are the reader's, not the database's
     * — `LIKE` would have to be re-derived from the same text afterwards anyway.
     *
     * Reading a whole book off disk is the expensive part, so the loop checks for
     * cancellation: the reader cancels the previous search on every keystroke, and
     * without a check here a cancelled scan would still read every remaining chapter
     * to the end while the next one waits behind it.
     */
    suspend fun search(
        bookId: Long,
        articleId: Long,
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        val needle = query.trim()
        if (needle.length < 2) return@withContext emptyList()
        val sql = if (bookId > 0) {
            "SELECT id, chapterIndex, title, body FROM article WHERE bookId = ? " +
                "ORDER BY chapterIndex ASC"
        } else {
            "SELECT id, chapterIndex, title, body FROM article WHERE id = ?"
        }
        val arg = (if (bookId > 0) bookId else articleId).toString()
        val hits = mutableListOf<SearchHit>()
        db.readableDatabase.rawQuery(sql, arrayOf(arg)).use { c ->
            val idIndex = c.getColumnIndexOrThrow("id")
            val chapterIndex = c.getColumnIndexOrThrow("chapterIndex")
            val title = c.getColumnIndexOrThrow("title")
            val body = c.getColumnIndexOrThrow("body")
            while (c.moveToNext() && hits.size < limit) {
                // Once per chapter: a chapter is the largest unit the scan cannot
                // interrupt, so this bounds the work left after a cancel.
                ensureActive()
                val rowId = c.getLong(idIndex)
                val chapter = c.getInt(chapterIndex)
                val chapterTitle = c.getString(title).orEmpty()
                val text = c.getString(body).orEmpty()
                Paragraphs.texts(text).forEachIndexed { index, paragraph ->
                    if (hits.size >= limit) return@forEachIndexed
                    if (paragraph.contains(needle, ignoreCase = true)) {
                        hits += SearchHit(
                            articleId = rowId,
                            chapterIndex = chapter,
                            chapterTitle = chapterTitle,
                            paragraphIndex = index,
                            text = paragraph,
                        )
                    }
                }
            }
        }
        hits
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
            storedWordCount = getInt(getColumnIndexOrThrow("wordCount")),
            readParagraph = getInt(getColumnIndexOrThrow("readParagraph")),
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
        /** Most results one search returns; a common word in a novel would otherwise list thousands. */
        const val SEARCH_LIMIT = 120

        const val SELECT =
            "SELECT id, sourceId, title, subtitle, url, author, publishedAt, difficulty, body, " +
                "saved, fetchedAt, lastReadAt, readSeconds, quizJson, translation, vocabProfile, " +
                "bookId, chapterIndex, wordCount, readParagraph " +
                "FROM article"
    }
}
