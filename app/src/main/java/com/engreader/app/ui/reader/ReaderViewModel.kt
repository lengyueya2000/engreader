package com.engreader.app.ui.reader

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engreader.app.data.AppContainer
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.dict.RootBreakdown
import com.engreader.app.dict.Roots
import com.engreader.app.dict.WordEntry
import com.engreader.app.dict.WordForm
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
import com.engreader.app.tts.ListeningSession
import com.engreader.app.tts.SpeechBackend
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
    /**
     * Lemmas whose review has come due, marked in the body so review happens in context.
     *
     * A subset of [savedLemmas] in practice — a word is only scheduled once it is saved
     * — but kept separate because the two are styled differently and can be turned on
     * and off independently.
     */
    val dueLemmas: Set<String> = emptySet(),
    val quiz: List<QuizQuestion> = emptyList(),
    val fontSize: Int = 19,
    val lineHeight: Float = 1.75f,
    val theme: ReadingTheme = ReadingTheme.Paper,
    val showTranslation: Boolean = false,
    /** Chinese translation per paragraph, aligned with [paragraphs]. */
    val translation: List<String> = emptyList(),
    val translationStatus: TranslationStatus = TranslationStatus.Idle,
    val translationError: String? = null,
    val highlightSaved: Boolean = true,
    /** Whether words whose review is due are marked in the body. */
    val highlightDue: Boolean = true,
    val speechRate: Float = 0.95f,
    val speechLocale: String = "en-GB",
    /** Bundled voice the reader picked, or blank to use the accent's default. */
    val speechVoice: String = "",
    /**
     * Whether the read-aloud follows each English sentence with its Chinese.
     *
     * Independent of [showTranslation]: the Chinese can be read out without the
     * paragraph translations being on screen, and both read from the same
     * [translation] list.
     */
    val speakTranslation: Boolean = false,
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
    val glosses: List<Pair<PartOfSpeech, String>> = emptyList(),
    val inSentence: String = "",
    /** Related forms of the same word, shown so derivations are not learnt twice. */
    val family: List<WordForm> = emptyList(),
    /** The word cut into prefix, root and suffix, when the table recognises it. */
    val roots: RootBreakdown? = null,
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

/** Everything the look-up sheet shows, resolved in one background pass. */
private data class LookupParts(
    val entry: WordEntry?,
    val glosses: List<Pair<PartOfSpeech, String>>,
    val family: List<WordForm>,
    val roots: RootBreakdown?,
)

/**
 * Reads an entry's glosses, family and morpheme breakdown.
 *
 * The root cutter needs to know whether a remainder stands on its own — `un` + `happy`
 * yes, `un` + `derstand` no — so the dictionary is handed to it as the predicate. That
 * is a query per candidate, which is why this runs on the IO dispatcher with the rest.
 */
private fun AppContainer.lookupParts(entry: WordEntry?): LookupParts = LookupParts(
    entry = entry,
    glosses = entry?.let { dictionary.glosses(it.translation) } ?: emptyList(),
    family = entry?.let { dictionary.family(it) } ?: emptyList(),
    roots = entry?.let { Roots.explain(it.lemma) { candidate -> dictionary.lookup(candidate) != null } },
)

/** Builds the sheet's view of an entry, including its gloss breakdown. */
private fun AppContainer.lookupState(
    query: String,
    entry: WordEntry?,
    saved: Boolean,
    sentence: String,
    glosses: List<Pair<PartOfSpeech, String>> = emptyList(),
    family: List<WordForm> = emptyList(),
    roots: RootBreakdown? = null,
) = LookupState(
    query = query,
    entry = entry,
    saved = saved,
    loading = false,
    glosses = glosses,
    inSentence = sentence,
    family = family,
    roots = roots,
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

    /** The voice in use, shown so the reader can see what is loaded. */
    val activeVoice: StateFlow<VoiceCatalog.BundledVoice?> get() = container.speaker.activeVoice

    private var lookups = 0
    private var sessionStart = 0L

    /**
     * True while the shared queue is playing this piece, so [sessionStart] is parked.
     *
     * The queue counts its own wall-clock time and writes the same rows, so exactly one
     * of the two is allowed to hold the clock. See [handOverClock].
     */
    private var queueOwnsClock = false

    /** Whether the reader is on screen, so the clock is not restarted in the background. */
    private var foreground = true

    private var translationJob: Job? = null

    /**
     * Collects the shared playback queue for as long as this screen is alive.
     *
     * The queue itself outlives the screen — that is the point of it living in the
     * container — so this only mirrors it into [state], and is cancelled with the
     * ViewModel rather than with the playback.
     */
    private var listeningJob: Job? = null

    /**
     * Paragraph the list is scrolled to, as reported by the screen.
     *
     * Held here rather than read back from the list state at flush time: the flush runs
     * from `onCleared` and from the app going to the background, both of which are after
     * the composition that owns the list state has already gone.
     */
    private var readParagraph = 0

    /**
     * The furthest paragraph the reader has had on screen this session.
     *
     * Tracked separately from [readParagraph], which follows the scroll and therefore
     * moves back up when the reader scrolls back. This is a high-water mark, because
     * what a flush reports as read must never shrink.
     */
    private var maxParagraph = 0

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
            dueLemmas = container.wordbook.dueLemmas(),
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
            highlightDue = settings.highlightDueWords,
            speechRate = settings.speechRate,
            speechLocale = settings.speechLocale,
            speechVoice = settings.speechVoice,
            speakTranslation = settings.speakTranslation,
            resumeParagraph = article.readParagraph.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)),
        )
        readParagraph = state.resumeParagraph
        savedParagraph = readParagraph
        // Reopening resumes partway in, and the part already behind the reader counts as
        // read: starting the high-water mark at the top would report the coverage of a
        // piece picked up at chapter's end as one paragraph.
        maxParagraph = readParagraph
        // The queue in the container may be playing something else, or this very article
        // from before it was reopened; either way this screen now follows it.
        observeListening()
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
        // The Chinese voice is a second 19 MB graph. Loading it here rather than at the
        // first sentence means the wait overlaps with the reader opening the article,
        // where nothing is being read aloud yet, instead of with the first pause
        // between an English sentence and its Chinese.
        if (settings.speakTranslation) container.speaker.prepareTranslation()
        // A previous session may have left the switch on with nothing behind it (an
        // article read before translations existed, or one whose run failed). Reading
        // the Chinese aloud needs the translation just as much as showing it does.
        if ((settings.showTranslation || settings.speakTranslation) && article.translation.isEmpty()) {
            requestTranslation()
        }
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
        val lemmas = unknown.map { it.lowercase() }
        state = state.copy(
            savedLemmas = state.savedLemmas + lemmas,
            // Saving schedules the word for review now, so it is due from this moment:
            // marking it here keeps the body's amber highlight in step with the write.
            dueLemmas = state.dueLemmas + lemmas,
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
     *
     * Called once per trip out of the foreground as well as on the way out, so one
     * reading of a piece leaves several rows. The seconds are the increment since the
     * last flush, and the words are the piece's coverage as of now, which is why the
     * statistics aggregate the words per article rather than summing them.
     *
     * A look-up count is flushed even when no time has passed, so a tap that lands in
     * the same second as the previous flush — or while the queue owns the clock — still
     * reaches the database instead of waiting for the next interval that has seconds to
     * report.
     */
    fun flushProgress() {
        savePosition()
        val count = lookups
        val startedAt = sessionStart
        val seconds = if (startedAt == 0L) {
            0
        } else {
            ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        }
        if (seconds <= 0 && count <= 0) return
        if (seconds > 0) sessionStart = System.currentTimeMillis()
        val words = if (seconds > 0) coveredWords() else 0
        val bookId = state.article?.bookId ?: 0
        lookups = 0
        container.appScope.launch {
            // `insert` reports a failed write by returning -1 instead of throwing, and the
            // clock and the look-up count above have already been reset. A write that did
            // not land would therefore take this stretch of reading out of the statistics
            // with nothing to show for it, so the counters are put back and the next flush
            // tries again. Time is re-measured from the original start rather than from
            // now, so the retry does not lose the seconds that just passed.
            val written = container.wordbook.recordSession(articleId, seconds, words, count)
            if (!written) {
                if (seconds > 0) sessionStart = startedAt
                lookups += count
                Log.w(TAG, "session write failed; reading time kept for the next flush")
                return@launch
            }
            if (seconds > 0) {
                container.articles.addReadSeconds(articleId, seconds)
                // Time on a chapter is time on the book: without this the shelf would show
                // a book that was read for an hour as never having been opened.
                if (bookId > 0) container.books.addReadSeconds(bookId, seconds)
            }
        }
    }

    /**
     * Words of the piece the reader has actually got through.
     *
     * Paragraphs up to the furthest one reached, not the article's total word count.
     * Writing the total on every flush made 阅读速度 climb with every trip to the
     * background, because the speed is words over time and the words were being
     * counted again each time.
     *
     * Zero while the text is still loading: nothing has been read yet, and falling
     * back to the article's total would put the old error back.
     */
    private fun coveredWords(): Int =
        Paragraphs.wordsUpTo(state.paragraphs.map { it.paragraph }, maxParagraph)

    /**
     * Records where the list is, for the next time the piece is opened.
     *
     * Called from a snapshot flow on every scroll position change, so it only assigns:
     * the write happens in [savePosition] when the reader leaves or the app goes to the
     * background. Writing on every scroll would put a database statement behind every
     * fling.
     *
     * [lastVisible] is the last paragraph on screen, which is how far the reader has
     * actually got; the top one alone would report only the first screenful of a piece
     * that fits on one screen, and nothing at all of the rest.
     */
    fun recordPosition(paragraph: Int, lastVisible: Int = paragraph) {
        if (paragraph < 0) return
        readParagraph = paragraph
        if (lastVisible > maxParagraph) maxParagraph = lastVisible
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
        foreground = false
        flushProgress()
        // The clock stops here and starts again in [onEnterForeground]. Leaving the
        // timestamp at "now" only discarded the gap when the reader came back; a reader
        // killed while the phone was in a pocket banked the whole time it was away.
        sessionStart = 0L
    }

    /** Restarts the reading clock after [onEnterBackground] stopped it. */
    fun onEnterForeground() {
        foreground = true
        if (state.article != null && !queueOwnsClock) sessionStart = System.currentTimeMillis()
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
        // The dictionary is a SQLite file and the gloss/family/root work is several
        // more queries; all of it used to run on the main thread under the sheet's
        // spinner.
        val parts = withContext(Dispatchers.IO) {
            container.lookupParts(container.dictionary.lookup(query))
        }
        // A tap that arrived while this was reading has already shown its own card, and
        // its answer is the one the reader is waiting for.
        if (generation != cardGeneration) return
        val entry = parts.entry
        val saved = entry?.let { container.wordbook.contains(it.lemma) } ?: false
        // Once a word has been answered correctly the Chinese gloss steps back and
        // the English definition leads, with the gloss still one tap away. Only when
        // the dictionary actually has an English definition: leading with English
        // and having nothing to lead with would be worse than the crutch.
        val englishFirst = entry != null &&
            entry.hasEnglishDefinition &&
            container.wordbook.isKnown(entry.lemma)
        state = state.copy(
            lookup = container.lookupState(
                query, entry, saved, sentence, parts.glosses, parts.family, parts.roots,
            ).copy(englishFirst = englishFirst),
        )
        if (entry != null) {
            lookups++
            container.wordbook.recordLookup(entry.lemma, articleId, sentence, query)
            if (container.settings.autoSaveLookups && !saved) {
                container.wordbook.save(entry)
                state = state.copy(
                    lookup = state.lookup?.copy(saved = true),
                    savedLemmas = state.savedLemmas + entry.lemma.lowercase(),
                    // Saving schedules the first review for now, so the word is due the
                    // moment it is saved; the body marks it without a reload.
                    dueLemmas = state.dueLemmas + entry.lemma.lowercase(),
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
        val parts = withContext(Dispatchers.IO) {
            val found = container.dictionary
                .lookupHeadword(headword, lookup.query, previous = lookup.entry) ?: return@withContext null
            container.lookupParts(found)
        } ?: return
        val entry = parts.entry ?: return
        val saved = container.wordbook.contains(entry.lemma)
        state = state.copy(
            lookup = container.lookupState(
                lookup.query, entry, saved, lookup.inSentence,
                parts.glosses, parts.family, parts.roots,
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
                // Removing the word takes it out of the review queue too, so the body
                // must stop marking it.
                dueLemmas = state.dueLemmas - entry.lemma.lowercase(),
            )
        } else {
            container.wordbook.save(entry)
            state = state.copy(
                lookup = lookup.copy(saved = true),
                savedLemmas = state.savedLemmas + entry.lemma.lowercase(),
                dueLemmas = state.dueLemmas + entry.lemma.lowercase(),
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

    fun setHighlightDue(value: Boolean) {
        container.settings.highlightDueWords = value
        state = state.copy(highlightDue = value)
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
     * The queue lives in [ListeningSession], which is app-scoped: the voice has to keep
     * going when the reader is gone, and a queue owned by this ViewModel could not
     * outlive the screen it belongs to. What stays here is the binding between the
     * queue's flat sentence index and the paragraph on screen — both sides build that
     * list with the same split, so the numbers name the same sentence.
     *
     * Leaving the reader deliberately does not stop the voice. Listening is meant to
     * carry on with the phone in a pocket, and the notification holds the stop button
     * for when it should not.
     */
    fun startListening(fromIndex: Int = 0) {
        container.listening.start(articleId, fromIndex)
    }

    /**
     * The reader's listen button.
     *
     * Pauses rather than stops when this article is already loaded, so it matches the
     * lock screen: pressing it again carries on from the same sentence instead of
     * starting the article over.
     */
    fun toggleListening() {
        container.listening.toggle(articleId, firstSentenceAtReadPosition())
    }

    fun pauseListening() = container.listening.pause()

    /**
     * Taps a sentence while listening to jump playback to it.
     */
    fun speakSentence(index: Int) {
        if (!state.listening) return
        container.listening.seek(index)
    }

    fun nextSentence() = container.listening.next()

    fun previousSentence() = container.listening.previous()

    /**
     * Mirrors the shared queue into this screen's state.
     *
     * The reader is a view onto the session rather than its owner, so every change —
     * a chapter rolling into the next one, the lock screen pausing, the sleep timer
     * firing — arrives as state instead of as a call. A session playing some other
     * article leaves this screen dark rather than highlighting its text.
     */
    private fun observeListening() {
        if (listeningJob?.isActive == true) return
        listeningJob = viewModelScope.launch {
            container.listening.state.collect { session ->
                val mine = session.active && session.articleId == articleId
                val listening = mine && session.playing
                handOverClock(listening)
                val index = if (listening) session.index else -1
                if (state.listening != listening || state.speakingSentence != index) {
                    state = state.copy(listening = listening, speakingSentence = index)
                }
            }
        }
    }

    /**
     * Stops the reader's clock while the shared queue is playing this piece, and starts
     * it again when it is not.
     *
     * Both this screen and the queue count wall-clock time and write it to the same
     * tables, so a reader left open behind a playing queue recorded every second twice —
     * the daily goal, the streak and the reading-speed chart all read from those rows.
     * Handing the clock over at the moment the queue takes it, rather than subtracting
     * afterwards, also banks the reader's own partial interval first, so the seconds
     * either side of the handover are counted once each and none is lost.
     *
     * The restart is skipped while the reader is in the background: the queue can be
     * paused from the lock screen, and starting the clock there would count time the
     * phone spent in a pocket.
     */
    private fun handOverClock(toQueue: Boolean) {
        if (toQueue == queueOwnsClock) return
        queueOwnsClock = toQueue
        if (toQueue) {
            flushProgress()
            sessionStart = 0L
        } else if (foreground) {
            sessionStart = System.currentTimeMillis()
        }
    }

    /** Flat index of the first sentence of the paragraph at the top of the list. */
    private fun firstSentenceAtReadPosition(): Int {
        val index = state.flatSentences.indexOfFirst { it.paragraphIndex >= readParagraph }
        return if (index < 0) 0 else index
    }

    fun speakWord(word: String) {
        container.speaker.sayWord(word)
    }

    /**
     * Plays a phrase for the connected-speech panel: [slow] is the 0.6× comparison
     * pace, otherwise the reader's own rate, so the two can be heard back to back.
     *
     * One-shot either way — the panel is opened from a tap, not from read-aloud, and
     * a phrase spoken here must not advance the reading queue.
     */
    fun speakPhrase(text: String, slow: Boolean) {
        if (slow) container.speaker.saySlow(text) else container.speaker.sayPhrase(text)
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
        listeningJob?.cancel()
        searchJob?.cancel()
        // A translation already under way keeps running on the container's scope: it
        // writes straight to the article row, so leaving the reader mid-fetch should
        // still leave the article translated when the user comes back.
        //
        // Playback is deliberately left alone. It belongs to the container now, and
        // leaving the reader is exactly when a listener wants it to keep going — the
        // notification carries the stop button for when it should not.
    }

    private companion object {
        const val TAG = "ReaderViewModel"

        /** Shorter queries match nearly every paragraph and say nothing. */
        const val MIN_SEARCH_CHARS = 2

        /** Long enough to cover the gap between keystrokes, short enough to feel immediate. */
        const val SEARCH_DEBOUNCE_MS = 220L
    }
}
