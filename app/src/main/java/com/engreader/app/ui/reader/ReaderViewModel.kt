package com.engreader.app.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.engreader.app.data.AppContainer
import com.engreader.app.dict.WordEntry
import com.engreader.app.model.Article
import com.engreader.app.model.Book
import com.engreader.app.model.ChapterRef
import com.engreader.app.model.QuizQuestion
import com.engreader.app.model.SearchHit
import com.engreader.app.nlp.NewWord
import com.engreader.app.nlp.Paragraph
import com.engreader.app.nlp.Paragraphs
import com.engreader.app.nlp.Sentence
import com.engreader.app.nlp.SentenceAnalysis
import com.engreader.app.nlp.Sentences
import com.engreader.app.nlp.VocabularyProfile
import com.engreader.app.tts.PlaybackService
import com.engreader.app.tts.SpeechBackend
import com.engreader.app.tts.SpeechEvent
import com.engreader.app.tts.SpeechState
import com.engreader.app.tts.VoiceCatalog
import com.engreader.app.ui.theme.ReadingTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A paragraph plus the sentences inside it, computed once per article. */
data class ReaderParagraph(
    val paragraph: Paragraph,
    val sentences: List<Sentence>,
)

/** Where the paragraph translations for the open article stand. */
enum class TranslationStatus { Idle, Loading, Ready, Failed }

data class ReaderState(
    val loading: Boolean = true,
    val article: Article? = null,
    val paragraphs: List<ReaderParagraph> = emptyList(),
    val error: String? = null,
    /** Word currently shown in the look-up sheet. */
    val lookup: LookupState? = null,
    /** Sentence currently opened in the grammar panel. */
    val analysis: SentenceAnalysis? = null,
    val analysisSentence: String = "",
    val savedLemmas: Set<String> = emptySet(),
    val quiz: List<QuizQuestion> = emptyList(),
    val quizVisible: Boolean = false,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.75f,
    val theme: ReadingTheme = ReadingTheme.Paper,
    val showTranslation: Boolean = false,
    /** Chinese translation per paragraph, aligned with [paragraphs]. */
    val translation: List<String> = emptyList(),
    val translationStatus: TranslationStatus = TranslationStatus.Idle,
    val translationError: String? = null,
    val highlightSaved: Boolean = true,
    val speechRate: Float = 0.95f,
    val speechLocale: String = "en-GB",
    /** Bundled voice the reader picked, or blank to use the accent's default. */
    val speechVoice: String = "",
    val speakingSentence: Int = -1,
    val listening: Boolean = false,
    /** Flattened sentence list used by listening mode and by next/prev navigation. */
    val flatSentences: List<FlatSentence> = emptyList(),
    /**
     * How much of this article is new to this reader.
     *
     * Null until computed, and null for an article whose vocabulary the dictionary
     * cannot describe — the number would be meaningless rather than zero.
     */
    val vocabulary: VocabularyProfile.Result? = null,
    val vocabularyVerdict: VocabularyProfile.Verdict? = null,
    /** The unknown words resolved for the pre-study panel. */
    val newWords: List<NewWord> = emptyList(),
    /** True while the pre-study list of the article's new words is open. */
    val newWordsVisible: Boolean = false,
    /**
     * Set when this article is a chapter of an imported book.
     *
     * A chapter reads exactly like an article — that is the whole design — so this is
     * only used for the extras that need the book around it: the table-of-contents
     * sheet, the previous/next chapter buttons and the progress write-back.
     */
    val book: Book? = null,
    val chapters: List<ChapterRef> = emptyList(),
    val contentsVisible: Boolean = false,
    /**
     * Paragraph to scroll to when the text first appears.
     *
     * Read once from the stored position and then left alone: the screen consumes it to
     * place the list, and moving it as the reader scrolls would make the scroll effect
     * that watches it fire again on every scroll.
     */
    val resumeParagraph: Int = 0,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    /** True while a query is being looked up, so the sheet can say it is working. */
    val searchRunning: Boolean = false,
)

/** A sentence with its position in the flattened article, for TTS queueing. */
data class FlatSentence(
    val paragraphIndex: Int,
    val sentenceIndex: Int,
    val text: String,
)

data class LookupState(
    val query: String,
    val entry: WordEntry?,
    val saved: Boolean,
    val loading: Boolean = false,
    val glosses: List<Pair<com.engreader.app.dict.PartOfSpeech, String>> = emptyList(),
    val inSentence: String = "",
    /** Related forms of the same word, shown so derivations are not learnt twice. */
    val family: List<com.engreader.app.dict.WordForm> = emptyList(),
    /**
     * True when the sheet should lead with the English definition.
     *
     * Set once the reader has demonstrated knowing the word; the Chinese gloss is
     * still one tap away, so this withdraws the crutch rather than removing it.
     */
    val englishFirst: Boolean = false,
    /** Whether the Chinese glosses are currently expanded behind the English ones. */
    val showChinese: Boolean = false,
)

/** Builds the sheet's view of an entry, including its gloss breakdown. */
private fun AppContainer.lookupState(
    query: String,
    entry: WordEntry?,
    saved: Boolean,
    sentence: String,
    glosses: List<Pair<com.engreader.app.dict.PartOfSpeech, String>> = emptyList(),
    family: List<com.engreader.app.dict.WordForm> = emptyList(),
) = LookupState(
    query = query,
    entry = entry,
    saved = saved,
    loading = false,
    glosses = glosses,
    inSentence = sentence,
    family = family,
)

/**
 * Drives the reader: article loading, look-ups, grammar analysis, and listening
 * mode.
 *
 * Reading time is accumulated here and flushed to the session table on exit,
 * because the reader is the only place that knows whether the user is actually
 * looking at the screen.
 */
class ReaderViewModel(
    private val container: AppContainer,
    private val articleId: Long,
) : ViewModel() {

    var state by mutableStateOf(ReaderState())
        private set

    val speechState: StateFlow<SpeechState> get() = container.speaker.state

    /** Which engine is speaking, so the settings sheet can say so. */
    val speechBackend: StateFlow<SpeechBackend> get() = container.speaker.backend

    /** The bundled voices, for the picker. */
    val voices: StateFlow<List<VoiceCatalog.BundledVoice>> get() = container.speaker.voices

    /** The voice in use, shown so the reader can see what is loaded. */
    val activeVoice: StateFlow<VoiceCatalog.BundledVoice?> get() = container.speaker.activeVoice

    private var lookups = 0
    private var sessionStart = 0L
    private var speechJob: Job? = null
    private var translationJob: Job? = null
    private var lastSpokenIndex = -1

    /**
     * Where playback stopped when it was paused rather than ended.
     *
     * Kept so the lock-screen play button and the reader's own listen toggle both pick
     * the sentence back up instead of starting the article again. Reset by every path
     * that ends the session outright.
     */
    private var pausedIndex = -1

    /**
     * Paragraph the list is scrolled to, as reported by the screen.
     *
     * Held here rather than read back from the list state at flush time: the flush runs
     * from `onCleared` and from the app going to the background, both of which are after
     * the composition that owns the list state has already gone.
     */
    private var readParagraph = 0

    /** The position as last written, so a flush that changes nothing writes nothing. */
    private var savedParagraph = 0

    /** A paragraph to scroll to, asked for before the text was ready. */
    private var pendingJump = -1

    private var searchJob: Job? = null

    /**
     * Bumped by every look-up and every grammar analysis.
     *
     * Both await a dictionary or parse result and then write the card they belong to.
     * A second tap while the first is still reading starts a second call, and without
     * a generation the slower one lands last and shows the word the reader has already
     * moved past.
     */
    private var cardGeneration = 0

    suspend fun load() {
        state = state.copy(loading = true)
        val article = container.articles.get(articleId)
        if (article == null) {
            state = state.copy(loading = false, error = "文章已不存在")
            return
        }
        // Splitting a long article into paragraphs and sentences is pure CPU work over
        // the whole body; on the main thread it is a visible stall when the reader
        // opens, which is exactly the moment a stall is most obvious.
        val (paragraphs, flat) = withContext(Dispatchers.Default) {
            val split = Paragraphs.split(article.body).map { p ->
                ReaderParagraph(p, Sentences.split(p.text))
            }
            val flat = buildList {
                split.forEachIndexed { pi, rp ->
                    rp.sentences.forEachIndexed { si, s ->
                        add(FlatSentence(pi, si, s.text))
                    }
                }
            }
            split to flat
        }
        val settings = container.settings
        state = state.copy(
            loading = false,
            article = article,
            paragraphs = paragraphs,
            flatSentences = flat,
            savedLemmas = container.wordbook.all().map { it.lemma.lowercase() }.toSet(),
            quiz = article.quiz,
            fontSize = settings.fontSize,
            lineHeight = settings.lineHeight,
            theme = settings.readingTheme,
            showTranslation = settings.showTranslation,
            translation = article.translation,
            translationStatus = if (article.translation.isNotEmpty()) {
                TranslationStatus.Ready
            } else {
                TranslationStatus.Idle
            },
            highlightSaved = settings.highlightSavedWords,
            speechRate = settings.speechRate,
            speechLocale = settings.speechLocale,
            speechVoice = settings.speechVoice,
            resumeParagraph = article.readParagraph.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)),
        )
        readParagraph = state.resumeParagraph
        savedParagraph = readParagraph
        container.articles.markOpened(articleId)
        sessionStart = System.currentTimeMillis()
        // A chapter of a book also advances the book: this is what makes "continue
        // reading" on the shelf point at the chapter the reader actually stopped in,
        // rather than at whatever chapter was opened most recently by any route.
        if (article.bookId > 0) {
            val book = container.books.bookOfChapter(articleId)
            val chapters = container.books.chapters(article.bookId)
            state = state.copy(book = book, chapters = chapters)
            container.books.markOpened(article.bookId, article.chapterIndex)
        }
        container.speaker.prepare(
            rate = settings.speechRate,
            localeTag = settings.speechLocale,
            voiceId = settings.speechVoice,
        )
        // A previous session may have left the switch on with nothing behind it (an
        // article read before translations existed, or one whose run failed).
        if (settings.showTranslation && article.translation.isEmpty()) requestTranslation()
        assessVocabulary(article)
        // A jump asked for while the body was being split — a search hit in another
        // chapter — lands now that there are paragraphs to scroll to.
        applyPendingJump()
    }

    /**
     * Works out how much of the open article is new to this reader.
     *
     * Runs after the article is on screen rather than before: the reading experience
     * does not depend on it, and it needs the dictionary and the wordbook, both of
     * which are slower than the paragraph split.
     */
    private suspend fun assessVocabulary(article: Article) {
        val lemmas = container.articles.contentLemmas(article)
        if (lemmas.size < VocabularyProfile.MIN_PROFILED_LEMMAS) return
        val known = container.wordbook.knownLemmas()
        val result = withContext(Dispatchers.IO) {
            container.vocabulary.assess(lemmas, known)
        }
        state = state.copy(
            vocabulary = result,
            vocabularyVerdict = container.vocabulary.verdict(result.rate, result.unknown.size),
            newWords = withContext(Dispatchers.IO) { result.asNewWords(container.dictionary) },
        )
    }

    /** Saves every word in the article's new-word list, so a hard text can be pre-studied. */
    fun saveUnknownWords() {
        val unknown = state.vocabulary?.unknown.orEmpty()
        state = state.copy(
            savedLemmas = state.savedLemmas + unknown.map { it.lowercase() },
            newWordsVisible = false,
        )
        // The panel closes immediately and the writes continue on the container's
        // scope, so dismissing it does not cancel the saves it just promised.
        container.appScope.launch(Dispatchers.IO) {
            unknown.forEach { lemma ->
                container.dictionary.lookup(lemma)?.let { container.wordbook.save(it) }
            }
        }
    }

    fun setNewWordsVisible(visible: Boolean) {
        state = state.copy(newWordsVisible = visible)
    }

    /**
     * Flushes reading time and look-up count.
     *
     * Runs on the container's scope rather than the caller's: the reader calls this
     * as it is being disposed, at which point the composition scope is already
     * cancelled and a write launched there would never reach the database.
     */
    fun flushProgress() {
        savePosition()
        if (sessionStart == 0L) return
        val now = System.currentTimeMillis()
        val seconds = ((now - sessionStart) / 1000).toInt()
        if (seconds <= 0) return
        sessionStart = now
        val words = state.article?.wordCount ?: 0
        val bookId = state.article?.bookId ?: 0
        val count = lookups
        lookups = 0
        container.appScope.launch {
            container.wordbook.recordSession(articleId, seconds, words, count)
            container.articles.addReadSeconds(articleId, seconds)
            // Time on a chapter is time on the book: without this the shelf would show
            // a book that was read for an hour as never having been opened.
            if (bookId > 0) container.books.addReadSeconds(bookId, seconds)
        }
    }

    /**
     * Records where the list is, for the next time the piece is opened.
     *
     * Called from a snapshot flow on every scroll position change, so it only assigns:
     * the write happens in [savePosition] when the reader leaves or the app goes to the
     * background. Writing on every scroll would put a database statement behind every
     * fling.
     */
    fun recordPosition(paragraph: Int) {
        if (paragraph < 0) return
        readParagraph = paragraph
    }

    /** Writes the position, if it has moved since the last write. */
    private fun savePosition() {
        if (state.article == null) return
        if (readParagraph == savedParagraph) return
        val at = readParagraph
        savedParagraph = at
        container.appScope.launch { container.articles.setReadParagraph(articleId, at) }
    }

    // --------------------------------------------------------------- searching

    fun setSearchVisible(visible: Boolean) {
        state = state.copy(searchVisible = visible)
        if (!visible) {
            searchJob?.cancel()
            searchJob = null
            state = state.copy(searchRunning = false)
        }
    }

    /**
     * Searches the open piece, or the whole book when the open piece is a chapter.
     *
     * Debounced, and each new query cancels the one before it: a search reads the whole
     * book off disk, and running one per keystroke would queue a scan per letter while
     * the reader is still typing.
     */
    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        val needle = query.trim()
        state = state.copy(
            searchQuery = query,
            searchRunning = needle.length >= MIN_SEARCH_CHARS,
            searchResults = if (needle.length < MIN_SEARCH_CHARS) emptyList() else state.searchResults,
        )
        if (needle.length < MIN_SEARCH_CHARS) return
        val article = state.article ?: return
        searchJob = container.appScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val hits = container.articles.search(article.bookId, articleId, needle)
            // Results for a query the reader has already typed past are dropped rather
            // than shown: the debounce makes that unlikely, not impossible.
            if (state.searchQuery.trim() == needle) {
                state = state.copy(searchResults = hits, searchRunning = false)
            }
        }
    }

    /**
     * Scrolls to a paragraph, or asks for the chapter holding it to be opened.
     *
     * Returns the id of the article the hit lives in, so the screen can tell whether it
     * is a jump within the open piece or a chapter change.
     */
    fun jumpToHit(hit: SearchHit) {
        state = state.copy(searchVisible = false)
        if (hit.articleId == articleId) jumpToParagraph(hit.paragraphIndex)
    }

    /**
     * Brings a paragraph into view, whether or not the text has loaded yet.
     *
     * A jump asked for before the body is split is remembered and applied at the end of
     * [load]; one asked for afterwards takes effect immediately.
     */
    fun jumpToParagraph(index: Int) {
        if (index < 0) return
        pendingJump = index
        applyPendingJump()
    }

    private fun applyPendingJump() {
        if (pendingJump < 0 || state.loading || state.paragraphs.isEmpty()) return
        val target = pendingJump.coerceIn(0, state.paragraphs.lastIndex)
        pendingJump = -1
        readParagraph = target
        state = state.copy(resumeParagraph = target)
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Called when the app leaves the foreground.
     *
     * Reading time used to be the wall-clock gap between opening the article and
     * leaving it, so pocketing the phone for an hour added an hour of "reading". The
     * clock is stopped here and restarted in [onEnterForeground].
     *
     * Read-aloud is deliberately left running: it is the one thing meant to continue
     * with the screen off, and [PlaybackService] is what keeps the process alive so it
     * can. Anything else stops the voice, which is the reader pressing pause or the
     * notification's stop button.
     */
    fun onEnterBackground() {
        flushProgress()
    }

    /** Restarts the reading clock after [onEnterBackground] stopped it. */
    fun onEnterForeground() {
        if (state.article != null) sessionStart = System.currentTimeMillis()
    }

    // ------------------------------------------------------------- chapters

    fun setContentsVisible(visible: Boolean) {
        state = state.copy(contentsVisible = visible)
    }

    /** Chapter before or after this one, for the reader's footer buttons. */
    fun chapterNeighbour(offset: Int): ChapterRef? {
        val article = state.article ?: return null
        if (article.bookId <= 0) return null
        return state.chapters.firstOrNull { it.index == article.chapterIndex + offset }
    }

    /**
     * Resolves a chapter index to the article that holds it.
     *
     * The screen navigates by pushing a new reader overlay for the target chapter, so
     * this only has to answer "which article is that"; the reading position is written
     * here rather than left to the new chapter's own load, so that opening a chapter
     * and immediately backing out still moves the bookmark.
     */
    suspend fun articleIdOfChapter(bookId: Long, index: Int): Long? {
        val id = container.books.chapterIdAt(bookId, index) ?: return null
        container.books.markOpened(bookId, index)
        return id
    }

    // ------------------------------------------------------------- look-ups

    suspend fun lookup(rawWord: String, sentence: String) {
        val query = rawWord.trim()
        if (query.isEmpty()) return
        val generation = ++cardGeneration
        state = state.copy(
            lookup = LookupState(query = query, entry = null, saved = false, loading = true, inSentence = sentence),
        )
        // The dictionary is a SQLite file and the gloss/family breakdown are two more
        // queries; all of it used to run on the main thread under the sheet's spinner.
        val (entry, glosses, family) = withContext(Dispatchers.IO) {
            val found = container.dictionary.lookup(query)
            Triple(
                found,
                found?.let { container.dictionary.glosses(it.translation) } ?: emptyList(),
                found?.let { container.dictionary.family(it) } ?: emptyList(),
            )
        }
        // A tap that arrived while this was reading has already shown its own card, and
        // its answer is the one the reader is waiting for.
        if (generation != cardGeneration) return
        val saved = entry?.let { container.wordbook.contains(it.lemma) } ?: false
        // Once a word has been answered correctly the Chinese gloss steps back and
        // the English definition leads, with the gloss still one tap away. Only when
        // the dictionary actually has an English definition: leading with English
        // and having nothing to lead with would be worse than the crutch.
        val englishFirst = entry != null &&
            entry.hasEnglishDefinition &&
            container.wordbook.isKnown(entry.lemma)
        state = state.copy(
            lookup = container.lookupState(query, entry, saved, sentence)
                .copy(englishFirst = englishFirst, glosses = glosses, family = family),
        )
        if (entry != null) {
            lookups++
            container.wordbook.recordLookup(entry.lemma, articleId, sentence, query)
            if (container.settings.autoSaveLookups && !saved) {
                container.wordbook.save(entry)
                state = state.copy(
                    lookup = state.lookup?.copy(saved = true),
                    savedLemmas = state.savedLemmas + entry.lemma.lowercase(),
                )
            }
        }
    }

    /** Reveals or hides the Chinese glosses behind an English-first card. */
    fun setShowChinese(show: Boolean) {
        val lookup = state.lookup ?: return
        state = state.copy(lookup = lookup.copy(showChinese = show))
    }

    /**
     * Switches the sheet to the word's other reading.
     *
     * `found` is both 建立 and the past tense of `find`; the dictionary ranks them by
     * corpus frequency, and this lets the user see the one it did not pick.
     */
    suspend fun switchReading(headword: String) {
        val lookup = state.lookup ?: return
        // The reading being left is offered as the alternative, so the switch can be
        // undone: without it the chip vanished and there was no way back.
        val (entry, glosses, family) = withContext(Dispatchers.IO) {
            val found = container.dictionary
                .lookupHeadword(headword, lookup.query, previous = lookup.entry) ?: return@withContext null
            Triple(
                found,
                container.dictionary.glosses(found.translation),
                container.dictionary.family(found),
            )
        } ?: return
        val saved = container.wordbook.contains(entry.lemma)
        state = state.copy(
            lookup = container.lookupState(
                lookup.query, entry, saved, lookup.inSentence, glosses, family,
            ).copy(
                englishFirst = entry.hasEnglishDefinition &&
                    container.wordbook.isKnown(entry.lemma),
                // A deliberate switch to another reading is a request to see it;
                // keeping the gloss collapsed would hide the thing just asked for.
                showChinese = true,
            ),
        )
    }

    suspend fun toggleSave() {
        val lookup = state.lookup ?: return
        val entry = lookup.entry ?: return
        if (lookup.saved) {
            container.wordbook.remove(entry.lemma)
            state = state.copy(
                lookup = lookup.copy(saved = false),
                savedLemmas = state.savedLemmas - entry.lemma.lowercase(),
            )
        } else {
            container.wordbook.save(entry)
            state = state.copy(
                lookup = lookup.copy(saved = true),
                savedLemmas = state.savedLemmas + entry.lemma.lowercase(),
            )
        }
    }

    fun dismissLookup() {
        state = state.copy(lookup = null)
    }

    // -------------------------------------------------------------- grammar

    suspend fun analyze(sentence: String) {
        // Parsing a sentence runs the tokeniser, the POS guesser and a dictionary
        // lookup per word — tens of milliseconds of work that used to block the tap.
        val generation = ++cardGeneration
        val analysis = withContext(Dispatchers.Default) { container.grammar.analyze(sentence) }
        // Tapping a second sentence before the first finished parsing must show the
        // second one, whichever parse happens to return first.
        if (generation != cardGeneration) return
        state = state.copy(analysis = analysis, analysisSentence = sentence)
    }

    fun dismissAnalysis() {
        state = state.copy(analysis = null)
    }

    // ------------------------------------------------------------- settings

    fun setFontSize(size: Int) {
        container.settings.fontSize = size
        state = state.copy(fontSize = container.settings.fontSize)
    }

    fun setLineHeight(value: Float) {
        container.settings.lineHeight = value
        state = state.copy(lineHeight = container.settings.lineHeight)
    }

    fun setTheme(theme: ReadingTheme) {
        container.settings.readingTheme = theme
        state = state.copy(theme = theme)
    }

    fun setShowTranslation(show: Boolean) {
        container.settings.showTranslation = show
        state = state.copy(showTranslation = show)
        if (show && state.translation.isEmpty() && state.translationStatus != TranslationStatus.Loading) {
            requestTranslation()
        }
    }

    /**
     * Fetches the paragraph translations for the open article.
     *
     * Held here rather than in the reader screen so a translation survives the
     * screen being recreated, and so the reader can restore an article's cached
     * translation without touching the network.
     */
    fun requestTranslation() {
        if (state.translationStatus == TranslationStatus.Loading) return
        val article = state.article ?: return
        val paragraphs = state.paragraphs.map { it.paragraph.text }
        if (paragraphs.isEmpty()) return
        translationJob?.cancel()
        state = state.copy(translationStatus = TranslationStatus.Loading, translationError = null)
        translationJob = container.appScope.launch {
            try {
                val translated = container.translator.translate(paragraphs, article.translation)
                container.articles.setTranslation(article.id, translated)
                state = state.copy(
                    translation = translated,
                    translationStatus = TranslationStatus.Ready,
                    translationError = null,
                )
            } catch (e: CancellationException) {
                // Superseded by a newer request (or the reader closed). Reporting this
                // as a failure would overwrite the newer run's Loading state.
                throw e
            } catch (e: Exception) {
                state = state.copy(
                    translationStatus = TranslationStatus.Failed,
                    translationError = e.message ?: "翻译失败",
                )
            }
        }
    }

    fun setHighlightSaved(value: Boolean) {
        container.settings.highlightSavedWords = value
        state = state.copy(highlightSaved = value)
    }

    fun setSpeechRate(rate: Float) {
        container.settings.speechRate = rate
        container.speaker.setRate(rate)
        state = state.copy(speechRate = rate)
    }

    fun setSpeechLocale(tag: String) {
        container.settings.speechLocale = tag
        // A voice belongs to the accent it was chosen for, so switching accent clears
        // the pick and lets the accent's own default take over.
        container.settings.speechVoice = ""
        container.speaker.selectVoice(tag, "")
        state = state.copy(speechLocale = tag, speechVoice = "")
    }

    /** Pins a bundled voice by id; blank returns to the accent's default. */
    fun setSpeechVoice(id: String) {
        container.settings.speechVoice = id
        container.speaker.selectVoice(state.speechLocale, id)
        state = state.copy(speechVoice = id)
    }

    /** Speaks a sample of the current voice without touching the reading queue. */
    fun previewVoice() = container.speaker.preview()

    // ------------------------------------------------------------ listening

    /**
     * Starts sentence-by-sentence playback from [fromIndex].
     *
     * The queue is advanced by the engine's completion callback rather than by a
     * timer, so the highlight stays in step with the audio on slow devices.
     */
    fun startListening(scope: CoroutineScope, fromIndex: Int = 0) {
        val sentences = state.flatSentences
        if (sentences.isEmpty()) return
        lastSpokenIndex = fromIndex.coerceIn(0, sentences.lastIndex)
        state = state.copy(listening = true, speakingSentence = lastSpokenIndex)
        speakAt(lastSpokenIndex)
        observeSpeech(scope)
        openPlaybackService()
    }

    /**
     * Brings up the foreground service that keeps the voice alive off-screen.
     *
     * The callbacks are the service's only way back in — it holds no reference to this
     * ViewModel — so they are registered here and dropped in [stopListening]. They run
     * on the app scope, which outlives any screen: the reader may well be off-screen
     * when the lock-screen play button is pressed.
     */
    private fun openPlaybackService() {
        val article = state.article ?: return
        PlaybackService.Hooks.resume = { resumeListening() }
        PlaybackService.Hooks.pause = { pauseListening() }
        PlaybackService.Hooks.stop = { stopListening() }
        PlaybackService.start(container.appContext, article.title, playbackSubtitle())
    }

    /** What the notification says under the title: the book's name, or the article's. */
    private fun playbackSubtitle(): String {
        val article = state.article ?: return ""
        val book = state.book?.title.orEmpty()
        return if (book.isNotBlank()) book else article.subtitle.ifBlank { article.originLabel }
    }

    /**
     * Stops the voice but keeps the session, so the notification's play button works.
     *
     * Stopping the service here instead would take the notification with it, and there
     * would be nothing left on the lock screen to press. Used by both the reader's own
     * pause button and the lock-screen one, which is what keeps the two consistent:
     * either one can be resumed from the other.
     */
    fun pauseListening() {
        if (!state.listening) return
        val at = lastSpokenIndex
        container.speaker.stop()
        speechJob?.cancel()
        speechJob = null
        state = state.copy(listening = false, speakingSentence = -1)
        lastSpokenIndex = -1
        pausedIndex = at
        val article = state.article
        PlaybackService.setPlaying(
            container.appContext,
            playing = false,
            title = article?.title.orEmpty(),
            subtitle = playbackSubtitle(),
        )
    }

    private fun resumeListening() {
        resumeFrom(container.appScope)
    }

    private fun speakAt(index: Int) {
        val sentences = state.flatSentences
        if (index !in sentences.indices) {
            stopListening()
            return
        }
        lastSpokenIndex = index
        state = state.copy(speakingSentence = index, listening = true)
        container.speaker.speak(sentences[index].text, "sentence:$index")
        // The next sentence is synthesised while this one plays. Synthesis takes a
        // sizeable fraction of the sentence's own duration, and that time used to be
        // heard as a pause between sentences; the engine generates on its own thread,
        // so this overlaps with playback rather than delaying it.
        sentences.getOrNull(index + 1)?.let { container.speaker.prefetch(it.text) }
    }

    private fun observeSpeech(scope: CoroutineScope) {
        if (speechJob?.isActive == true) return
        speechJob = scope.launch {
            container.speaker.events.collect { event ->
                if (!state.listening) return@collect
                when (event) {
                    is SpeechEvent.Finished -> {
                        val id = event.utteranceId.removePrefix("sentence:")
                        val index = id.toIntOrNull() ?: return@collect
                        // Ignore a late callback from a sentence the user already skipped.
                        if (index != lastSpokenIndex) return@collect
                        val next = index + 1
                        if (next < state.flatSentences.size) {
                            speakAt(next)
                        } else {
                            stopListening()
                        }
                    }
                    is SpeechEvent.Failed -> stopListening()
                }
            }
        }
    }

    /**
     * The reader's own listen button.
     *
     * Pauses rather than stops, so it matches the lock screen: the session stays up and
     * pressing it again carries on from the same sentence instead of starting over.
     */
    fun toggleListening(scope: CoroutineScope) {
        if (state.listening) pauseListening() else resumeFrom(scope)
    }

    /**
     * Continues from where playback was paused, or from what the reader is looking at.
     *
     * Starting from the top of the article is wrong once the reader has scrolled: a
     * chapter of a novel is thousands of words long, and the position they left the text
     * at is exactly the part they want read to them.
     */
    private fun resumeFrom(scope: CoroutineScope) {
        val from = if (pausedIndex >= 0) pausedIndex else firstSentenceAtReadPosition()
        pausedIndex = -1
        startListening(scope, from)
    }

    /** Flat index of the first sentence of the paragraph at the top of the list. */
    private fun firstSentenceAtReadPosition(): Int {
        val index = state.flatSentences.indexOfFirst { it.paragraphIndex >= readParagraph }
        return if (index < 0) 0 else index
    }

    /** Ends the session: the voice, the notification and the callbacks all go. */
    fun stopListening() {
        container.speaker.stop()
        speechJob?.cancel()
        speechJob = null
        state = state.copy(listening = false, speakingSentence = -1)
        lastSpokenIndex = -1
        pausedIndex = -1
        // Dropped before the service so a teardown cannot call back into a ViewModel
        // that is being cleared.
        PlaybackService.Hooks.resume = null
        PlaybackService.Hooks.pause = null
        PlaybackService.Hooks.stop = null
        PlaybackService.stop(container.appContext)
    }

    /** Taps a sentence while listening to jump playback to it. */
    fun speakSentence(index: Int) {
        if (!state.listening) return
        speakAt(index)
    }

    fun nextSentence() {
        if (lastSpokenIndex + 1 < state.flatSentences.size) speakAt(lastSpokenIndex + 1)
    }

    fun previousSentence() {
        if (lastSpokenIndex - 1 >= 0) speakAt(lastSpokenIndex - 1)
    }

    fun speakWord(word: String) {
        container.speaker.sayWord(word)
    }

    // ----------------------------------------------------------------- quiz

    suspend fun prepareQuiz(): List<QuizQuestion> {
        state.quiz.takeIf { it.isNotEmpty() }?.let { return it }
        val article = state.article ?: return emptyList()
        // Building the questions re-reads the whole article and looks every candidate
        // up in the dictionary; it is the heaviest thing the reader does on demand.
        val questions = withContext(Dispatchers.Default) {
            container.quiz.build(article.body, maxQuestions = 5)
        }
        if (questions.isNotEmpty()) container.articles.saveQuiz(articleId, questions)
        state = state.copy(quiz = questions)
        return questions
    }

    fun setQuizVisible(visible: Boolean) {
        state = state.copy(quizVisible = visible)
    }

    /**
     * Adds or removes the article from the saved list.
     *
     * The row is updated in place and the write is handed to the container's scope:
     * the screen's own scope is cancelled the moment the reader leaves, and a bookmark
     * that was tapped a moment before backing out still has to reach the database.
     */
    fun setSaved(saved: Boolean) {
        val article = state.article ?: return
        state = state.copy(article = article.copy(saved = saved))
        container.appScope.launch(Dispatchers.IO) { container.articles.setSaved(article.id, saved) }
    }

    fun recordQuiz(correct: Int, total: Int) {
        container.appScope.launch(Dispatchers.IO) {
            container.wordbook.recordQuiz(articleId, correct, total)
        }
    }

    override fun onCleared() {
        super.onCleared()
        flushProgress()
        speechJob?.cancel()
        searchJob?.cancel()
        // A translation already under way keeps running on the container's scope: it
        // writes straight to the article row, so leaving the reader mid-fetch should
        // still leave the article translated when the user comes back.
        //
        // Leaving the reader also ends the playback session: the queue and the
        // highlight are this ViewModel's, so a service left running would have nothing
        // left to advance it.
        stopListening()
    }

    private companion object {
        /** Shorter queries match nearly every paragraph and say nothing. */
        const val MIN_SEARCH_CHARS = 2

        /** Long enough to cover the gap between keystrokes, short enough to feel immediate. */
        const val SEARCH_DEBOUNCE_MS = 220L
    }
}
