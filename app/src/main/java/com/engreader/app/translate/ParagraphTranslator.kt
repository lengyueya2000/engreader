package com.engreader.app.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translates an article's paragraphs into Chinese.
 *
 * The engines are tried in order and the first one that answers wins; a machine
 * translation service is not something the app can bundle, so "no key, no setup"
 * here means "no key, no setup for the user" — the request goes out over the
 * network to a public endpoint.
 *
 * A cache entry is reused by *position*: the stored translation is a parallel list with
 * no copy of the text it came from, so this class cannot tell a slot that still matches
 * its paragraph from one whose paragraph was edited in place. The guard for that sits
 * one layer up — `ArticleRepository.persist` blanks the stored translation whenever the
 * body changes — and a writer that bypasses it would re-display the old Chinese for an
 * edited paragraph of the same length. The KDoc here used to claim the content
 * correspondence was checked here, which it never was.
 */
class ParagraphTranslator(private val engines: List<TranslateEngine> = DEFAULT_ENGINES) {

    /**
     * Engine that produced the last successful result, for reporting.
     *
     * Written on [Dispatchers.IO] and read from wherever the UI asks, so the field is
     * volatile: without it the write is free to stay in the worker's cache and the
     * reader can keep seeing the previous engine — or none at all.
     */
    @Volatile
    var lastEngine: String? = null
        private set

    /**
     * Returns translations aligned with [paragraphs]; blank paragraphs stay blank.
     *
     * [cached] is the previous result, normally loaded from the article row. Entries are
     * reused by position, and only when the cached list is the same length as
     * [paragraphs]; a cache produced from the same paragraph list — which is what
     * `ArticleRepository` guarantees by clearing it when the body changes — costs
     * nothing to reuse, so a re-run after an edit only pays for the empty slots.
     */
    suspend fun translate(
        paragraphs: List<String>,
        cached: List<String> = emptyList(),
    ): List<String> = withContext(Dispatchers.IO) {
        val reuse = align(paragraphs, cached)
        val pending = paragraphs.indices.filter { reuse[it] == null && paragraphs[it].isNotBlank() }
        if (pending.isEmpty()) return@withContext paragraphs.indices.map { reuse[it] ?: "" }

        var lastFailure: TranslationException? = null
        for (engine in engines) {
            try {
                val translated = engine.translate(pending.map { paragraphs[it] })
                if (translated.size != pending.size) {
                    throw TranslationException("${engine.label} 返回的段数不对")
                }
                val out = paragraphs.indices.map { reuse[it] ?: "" }.toMutableList()
                pending.forEachIndexed { offset, index -> out[index] = translated[offset] }
                lastEngine = engine.id
                return@withContext out
            } catch (e: TranslationException) {
                lastFailure = e
            } catch (e: Exception) {
                lastFailure = TranslationException(e.message ?: "翻译失败", e)
            }
        }
        throw lastFailure ?: TranslationException("没有可用的翻译服务")
    }

    /**
     * Matches cached translations to the current paragraphs, by position.
     *
     * Cache and body are stored as parallel lists and the cache carries no copy of the
     * text it was produced from, so the only check available here is the length: if the
     * paragraph count has changed the body was re-split upstream and position means
     * nothing, so the cache is discarded rather than shown against the wrong paragraph.
     * A same-length body edit cannot be detected here at all — `ArticleRepository.persist`
     * blanks the cache when the text changes, and that is the guarantee this relies on.
     */
    private fun align(paragraphs: List<String>, cached: List<String>): Array<String?> {
        val out = arrayOfNulls<String>(paragraphs.size)
        if (cached.size != paragraphs.size) return out
        paragraphs.indices.forEach { i ->
            cached[i].takeIf { it.isNotBlank() }?.let { out[i] = it }
        }
        return out
    }

    companion object {
        val DEFAULT_ENGINES: List<TranslateEngine> = listOf(GoogleTranslateEngine(), MyMemoryEngine())
    }
}
