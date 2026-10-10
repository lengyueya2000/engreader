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
 * Only the paragraphs that actually changed are sent. Editing a body later, or a
 * partial earlier run, therefore costs nothing to bring up to date.
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
     * [cached] is the previous result, normally loaded from the article row: entries
     * that are already present and correspond to an unchanged paragraph are reused
     * as-is, so a re-run after a body edit only pays for the edited paragraphs.
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
     * Matches cached translations to the current paragraphs.
     *
     * Cache and body are stored as parallel lists, so an entry is only reusable when
     * the two still line up. If the paragraph count has changed the body was
     * re-split upstream and position means nothing, so the cache is discarded rather
     * than shown against the wrong paragraph.
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
