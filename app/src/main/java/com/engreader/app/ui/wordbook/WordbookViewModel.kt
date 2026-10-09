package com.engreader.app.ui.wordbook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.data.ReviewCard
import com.engreader.app.data.SavedWord
import com.engreader.app.dict.DifficultyBand
import com.engreader.app.dict.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WordbookState(
    val loading: Boolean = true,
    val words: List<SavedWord> = emptyList(),
    val filtered: List<SavedWord> = emptyList(),
    val query: String = "",
    val filter: WordFilter = WordFilter.All,
    val dueCount: Int = 0,
    val total: Int = 0,
    val mastered: Int = 0,
    /** Bands resolved per lemma, so each row can show its level without a re-query. */
    val bands: Map<String, DifficultyBand> = emptyMap(),
)

enum class WordFilter(val label: String) {
    All("全部"),
    Due("待复习"),
    Hard("常错"),
    Mastered("已掌握"),
}

class WordbookViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(WordbookState())
        private set

    suspend fun load() {
        state = state.copy(loading = true)
        val words = container.wordbook.all()
        val bands = withContext(Dispatchers.IO) {
            words.associate { it.lemma.lowercase() to (container.dictionary.lookup(it.lemma)?.band ?: DifficultyBand.Unknown) }
        }
        state = state.copy(
            loading = false,
            words = words,
            bands = bands,
            dueCount = container.wordbook.dueCount(),
            total = container.wordbook.totalCount(),
            mastered = container.wordbook.masteredCount(),
        )
        applyFilter()
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
        applyFilter()
    }

    fun setFilter(filter: WordFilter) {
        state = state.copy(filter = filter)
        applyFilter()
    }

    private fun applyFilter() {
        val q = state.query.trim().lowercase()
        val filtered = state.words.filter { word ->
            val matchesQuery = q.isEmpty() ||
                word.lemma.lowercase().contains(q) ||
                word.translation.lowercase().contains(q)
            val matchesFilter = when (state.filter) {
                WordFilter.All -> true
                WordFilter.Due -> word.isDue && !word.mastered
                WordFilter.Hard -> word.wrong > 0 && word.accuracy < 0.6f
                WordFilter.Mastered -> word.mastered
            }
            matchesQuery && matchesFilter
        }
        state = state.copy(filtered = filtered)
    }

    suspend fun remove(lemma: String) {
        container.wordbook.remove(lemma)
        load()
    }

    suspend fun setMastered(lemma: String, mastered: Boolean) {
        container.wordbook.setMastered(lemma, mastered)
        load()
    }

    suspend fun lookup(lemma: String): WordEntry? = withContext(Dispatchers.IO) {
        container.dictionary.lookup(lemma)
    }

    suspend fun dueWords(limit: Int = 20): List<SavedWord> = container.wordbook.due(limit)

    /** Due words with the sentence each was met in, for context-first review. */
    suspend fun dueCards(limit: Int = 30): List<ReviewCard> = container.wordbook.dueCards(limit)

    /**
     * English definitions of a saved word, shown on a review card once the reader
     * has been getting the word right.
     */
    suspend fun definitions(lemma: String): List<Pair<com.engreader.app.dict.PartOfSpeech, String>> =
        withContext(Dispatchers.IO) { container.dictionary.definitions(lemma) }

    /** Whether a word has been answered correctly, i.e. its gloss may be withdrawn. */
    suspend fun isKnown(lemma: String): Boolean = container.wordbook.isKnown(lemma)

    /** Saves the reader's own note against a word. */
    suspend fun setNote(lemma: String, note: String) {
        container.wordbook.setNote(lemma, note)
        load()
    }

    /** Related forms of a saved word, shown under its gloss. */
    suspend fun family(word: SavedWord): List<com.engreader.app.dict.WordForm> =
        withContext(Dispatchers.IO) {
            container.dictionary.lookup(word.lemma)?.let { container.dictionary.family(it) }
                ?: emptyList()
        }

    /** Applies a review result, which moves the word along the Leitner boxes. */
    suspend fun review(lemma: String, correct: Boolean) {
        container.wordbook.review(lemma, correct)
    }

    /**
     * Confusable glosses shown alongside the answer, so the card teaches the
     * distinction rather than only confirming the meaning.
     */
    suspend fun distractorGlosses(word: SavedWord): List<String> =
        container.wordbook.distractors(word, count = 3)

    fun speak(word: String) {
        container.speaker.prepare(container.settings.speechRate, container.settings.speechLocale)
        container.speaker.sayWord(word)
    }
}
