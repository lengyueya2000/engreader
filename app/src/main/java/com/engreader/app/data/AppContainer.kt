package com.engreader.app.data

import android.content.Context
import com.engreader.app.dict.Dictionary
import com.engreader.app.nlp.GrammarAnalyzer
import com.engreader.app.nlp.QuizBuilder
import com.engreader.app.nlp.VocabularyGrader
import com.engreader.app.nlp.VocabularyProfile
import com.engreader.app.source.ArticleFetcher
import com.engreader.app.source.SeedLibrary
import com.engreader.app.tts.Speaker
import com.engreader.app.translate.ParagraphTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph. The app has a handful of long-lived singletons and no
 * need for a DI framework; keeping construction explicit makes startup order
 * obvious (the dictionary must be open before the first look-up).
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /**
     * Outlives any screen. Used for writes that must not be cancelled when the UI
     * that triggered them goes away — flushing reading time as the reader closes is
     * the main one, and the screen's own scope is already dead by then.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val userDb = UserDb(appContext)

    val dictionary = Dictionary(appContext)

    val grammar = GrammarAnalyzer(dictionary)

    val quiz = QuizBuilder(dictionary)

    val grader = VocabularyGrader(dictionary)

    val vocabulary = VocabularyProfile(dictionary)

    val fetcher = ArticleFetcher(appContext)

    val seedLibrary = SeedLibrary(appContext)

    val speaker = Speaker(appContext)

    val translator = ParagraphTranslator()

    val articles = ArticleRepository(userDb, fetcher, grader, vocabulary)

    val books = BookRepository(appContext, userDb, grader)

    val wordbook = WordbookRepository(userDb, dictionary)

    val backup = BackupRepository(userDb)

    val progress = ProgressRepository(userDb, dictionary)

    val settings = SettingsRepository(appContext)
}
