package com.engreader.app.ui.reader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engreader.app.nlp.Paragraph
import com.engreader.app.nlp.Paragraphs
import com.engreader.app.nlp.Sentence
import com.engreader.app.nlp.Tokenizer
import com.engreader.app.nlp.WordSpan
import com.engreader.app.translate.SentenceAlignment
import com.engreader.app.tts.SpeechState
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.containerScopedViewModel
import com.engreader.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** Extra space left between the spoken sentence and the nearest screen edge. */
private val SCROLL_MARGIN = 28.dp

/** How many frames to wait for a scrolled-to paragraph to report its sentence bounds. */
private const val COMPOSE_FRAMES = 8

/** Height of the bar over the text, plus the header space under it. */
private val CONTENT_TOP_INSET = 64.dp

/** Room left under the text so the last lines are not hidden by the playback bar. */
private val CONTENT_BOTTOM_INSET = 120.dp

/**
 * Where each sentence currently sits on screen, in window coordinates.
 *
 * A paragraph cannot answer this for itself: a long paragraph scrolls in and out of
 * composition as a whole, and the sentence being read is frequently in a paragraph the
 * list has not composed at all. The reader therefore registers one rectangle per
 * sentence here, and removes it when that sentence leaves composition — what remains
 * is exactly the sentences the auto-scroll can compare against the screen.
 *
 * A rectangle rather than the sentence's composable, because the scroll runs in a
 * `LaunchedEffect` that outlives any one paragraph.
 */
private class SpokenBounds {
    private val boxes = mutableMapOf<Int, Rect>()

    /**
     * Records where sentence [index] is, or forgets it when it has left composition.
     *
     * A rectangle with no area is not a position: a node reports one before it has been
     * placed, and treating it as the top of the screen sends the scroll chasing a
     * sentence that is not there.
     */
    fun putOrRemove(index: Int, box: Rect?) {
        if (index < 0) return
        if (box == null || box.width <= 0f || box.height <= 0f) {
            boxes.remove(index)
        } else {
            boxes[index] = box
        }
    }

    fun of(index: Int): Rect? = boxes[index]

    fun clear() = boxes.clear()
}

/**
 * Where a layout is on screen, unclipped.
 *
 * [boundsInWindow] is not usable here: it is clipped to the window, so a sentence that
 * has scrolled just past the bottom edge reports an empty rectangle, which is
 * indistinguishable from a sentence that has not been composed. The scroll would then
 * never learn that the sentence it is following is off-screen, which is the one thing
 * it exists to find out.
 */
private fun unclippedBounds(coordinates: LayoutCoordinates): Rect {
    val topLeft = coordinates.positionInWindow()
    return Rect(
        left = topLeft.x,
        top = topLeft.y,
        right = topLeft.x + coordinates.size.width,
        bottom = topLeft.y + coordinates.size.height,
    )
}

/**
 * Asks for the notification permission the playback notification needs, once.
 *
 * Only from Android 13, and only when it has not already been granted: below that the
 * permission does not exist, and a granted one would prompt for nothing. A refusal is
 * not an error — the voice is unaffected, the reader just cannot stop it from the
 * notification shade.
 */
private fun askForNotificationPermission(context: Context, launch: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) launch(Manifest.permission.POST_NOTIFICATIONS)
}

/**
 * The reading surface.
 *
 * Each paragraph is a single `Text` whose annotated string carries the styling for
 * saved words and the currently spoken sentence. Tap and long-press offsets come
 * from the text layout, so a tap maps to an exact word rather than to a guess.
 */
@Composable
fun ReaderScreen(
    articleId: Long,
    onBack: () -> Unit,
    onSavedChanged: () -> Unit,
    /**
     * Opens another chapter in place of this one, optionally at a paragraph.
     *
     * Replacing rather than stacking is deliberate: walking from chapter one to
     * chapter twelve should still leave the reader one back-press from the shelf, not
     * twelve. The caller owns the navigation, so this screen stays unaware of it.
     *
     * A paragraph of zero means the chapter's own stored position, which is what a
     * chapter opened from the table of contents wants; a search hit names the paragraph
     * it found.
     */
    onOpenChapter: (Long, Int) -> Unit = { _, _ -> },
    /**
     * A paragraph to land on in the piece being opened, or zero for its stored position.
     *
     * This is the second half of [onOpenChapter]: when a search hit lives in the chapter
     * already on screen the ViewModel is reused rather than rebuilt, so the jump has to
     * arrive as a parameter change rather than as a new destination.
     */
    initialParagraph: Int = 0,
) {
    // Scoped to this article rather than to the activity: the reader is opened once
    // per article, and an activity-scoped ViewModel would keep serving the first one.
    val viewModel = containerScopedViewModel(key = "reader:$articleId") {
        ReaderViewModel(it, articleId)
    }
    val scope = rememberCoroutineScope()
    val state = viewModel.state
    val listState = rememberLazyListState()
    /**
     * How many list items come before the first paragraph.
     *
     * The header, plus the translation-failure notice when there is one. Every jump to a
     * paragraph — the listening auto-scroll, the search results, restoring the reading
     * position — has to add it, and having one number for it is what keeps them from
     * disagreeing.
     */
    val leadingItems = 1 + if (state.showTranslation && state.translationStatus == TranslationStatus.Failed) 1 else 0
    /**
     * Where each sentence currently is on screen, for the listening auto-scroll.
     *
     * Held here rather than in a paragraph so it survives paragraphs scrolling out of
     * composition: the sentence being spoken is often in a paragraph the list has not
     * composed yet, and the scroll needs to know that before it can bring it in.
     */
    val spokenBounds = remember { SpokenBounds() }
    var showTypography by remember { mutableStateOf(false) }
    var showQuiz by remember { mutableStateOf(false) }
    val context = LocalContext.current
    /**
     * Asked for the first time the reader presses play, not at launch.
     *
     * The permission is only about the playback notification — the voice works without
     * it — so it is asked for at the moment the notification would appear, which is the
     * only point where the reason for it is on screen.
     */
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val liveSpeechState by viewModel.speechState.collectAsStateWithLifecycle()
    val liveVoice by viewModel.activeVoice.collectAsStateWithLifecycle()
    val liveBackend by viewModel.speechBackend.collectAsStateWithLifecycle()

    LaunchedEffect(articleId) { viewModel.load() }

    // A jump requested from outside — a search hit in the chapter already open. Runs
    // before load has finished when the chapter is new, in which case the ViewModel
    // holds the request until there are paragraphs to scroll to.
    LaunchedEffect(initialParagraph) {
        if (initialParagraph > 0) viewModel.jumpToParagraph(initialParagraph)
    }

    DisposableEffect(articleId) {
        onDispose {
            viewModel.flushProgress()
            spokenBounds.clear()
        }
    }

    // Reading time and playback follow the app's own foreground state, not just this
    // screen's: a reader who pockets the phone is not reading, and the wall clock used
    // to keep counting. The listener is registered on the activity's lifecycle, which
    // is the one that actually changes when the app is backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onEnterBackground()
                Lifecycle.Event.ON_START -> viewModel.onEnterForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep the sentence being read on screen during listening mode.
    //
    // Following the paragraph is not enough: a paragraph of a novel is easily taller
    // than the screen, so scrolling its first line to the top leaves the sentence
    // actually being spoken below the fold — the reading then walks off the bottom of
    // the page with nothing bringing it back. Each sentence therefore reports its own
    // bounds, and the list is nudged only when the spoken sentence leaves a comfortable
    // band: scrolling on every sentence would drag the page under the reader's eyes.
    val density = LocalDensity.current
    // The list's own bounds, in window coordinates — the same space the sentence
    // rectangles are measured in, so the two can be compared directly.
    var surfaceTop by remember { mutableFloatStateOf(0f) }
    var surfaceHeight by remember { mutableIntStateOf(0) }
    val topReserve = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() + CONTENT_TOP_INSET.toPx()
    }
    val bottomReserve = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx() +
            CONTENT_BOTTOM_INSET.toPx()
    }
    val margin = with(density) { SCROLL_MARGIN.toPx() }
    // One instance for the whole list, so an unchanged wordbook lets every paragraph
    // skip recomposition instead of being redrawn on each state change.
    val savedLemmas = remember(state.savedLemmas) { SavedLemmaSet(state.savedLemmas) }
    // The flat index of each paragraph's first sentence. The flat list is built
    // paragraph by paragraph, so this is normally just a running sum; it is computed
    // rather than assumed because a paragraph whose text holds no sentences at all
    // would make the arithmetic wrong for every paragraph after it.
    val flatStart = remember(state.flatSentences) {
        val out = HashMap<Int, Int>()
        state.flatSentences.forEachIndexed { flat, sentence ->
            out.putIfAbsent(sentence.paragraphIndex, flat)
        }
        out
    }

    LaunchedEffect(state.speakingSentence, state.listening) {
        val flat = state.speakingSentence
        if (!state.listening || flat < 0) return@LaunchedEffect
        // A sentence inside a paragraph the list has not composed has no bounds to read,
        // so its paragraph is brought on screen first; the nudge below then places it.
        // Jumped rather than animated: the frame wait that follows cannot tell a settled
        // list from one still travelling, and the nudge would cancel the animation
        // half-way and leave the paragraph where it stood.
        var known = spokenBounds.of(flat)
        if (known == null) {
            val paragraphIndex = state.flatSentences.getOrNull(flat)?.paragraphIndex ?: return@LaunchedEffect
            runCatching { listState.scrollToItem(leadingItems + paragraphIndex) }
            repeat(COMPOSE_FRAMES) {
                if (known == null) {
                    withFrameNanos { }
                    known = spokenBounds.of(flat)
                }
            }
        }
        val box = known ?: return@LaunchedEffect
        if (surfaceHeight <= 0) return@LaunchedEffect
        val top = surfaceTop + topReserve + margin
        val bottom = (surfaceTop + surfaceHeight - bottomReserve - margin).coerceAtLeast(top + 1f)
        // A sentence taller than the band cannot fit in it; aligning its top is the
        // only thing that keeps its beginning readable.
        val delta = when {
            box.height > bottom - top -> box.top - top
            box.top < top -> box.top - top
            box.bottom > bottom -> box.bottom - bottom
            else -> 0f
        }
        if (delta != 0f) runCatching { listState.animateScrollBy(delta) }
    }

    /**
     * Puts the list back where the reader left it.
     *
     * Once per article, when the text has been laid out. A position of zero is the top
     * of the text and is not restored: that is what everything stored before the
     * position was recorded has, and scrolling to the top is what the list does anyway.
     */
    var restored by remember(articleId) { mutableStateOf(false) }
    LaunchedEffect(articleId, state.loading, state.resumeParagraph) {
        if (state.loading) return@LaunchedEffect
        val target = state.resumeParagraph
        if (target > 0) runCatching { listState.scrollToItem(leadingItems + target) }
        restored = true
    }

    // Reports the top paragraph as the reader scrolls, so leaving the piece records
    // where they were. Gated on [restored]: the first frame of a reopened article is at
    // the top, and recording that would erase the position just restored.
    LaunchedEffect(listState, leadingItems) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> if (restored) viewModel.recordPosition(index - leadingItems) }
    }

    val theme = state.theme

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }

            state.error != null -> EmptyState(
                title = "打不开这篇文章",
                detail = state.error.orEmpty(),
                modifier = Modifier.fillMaxSize(),
            )

            else -> {
                val article = state.article ?: return@Box
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            val box = coordinates.boundsInWindow()
                            surfaceTop = box.top
                            surfaceHeight = box.height.toInt()
                        },
                    contentPadding = PaddingValues(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + CONTENT_TOP_INSET,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + CONTENT_BOTTOM_INSET,
                    ),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = 22.dp)) {
                            Text(
                                text = article.title,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = FontFamily.Serif,
                                ),
                                color = theme.body,
                            )
                            if (article.subtitle.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = article.subtitle,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                    ),
                                    color = theme.subtle,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MetaText(
                                    text = article.originLabel,
                                    color = theme.subtle,
                                )
                                Spacer(Modifier.width(10.dp))
                                MetaText(text = "${article.wordCount} 词", color = theme.subtle)
                                Spacer(Modifier.width(10.dp))
                                MetaText(text = "难度 ${article.difficulty}/5", color = theme.subtle)
                            }
                            Spacer(Modifier.height(16.dp))
                            HairLine(Modifier.background(theme.subtle.copy(alpha = 0.3f)))
                            // The personal unknown rate, when it could be computed:
                            // this is the number that decides whether the piece is
                            // worth reading now, which the corpus-based 难度 is not.
                            state.vocabulary?.let { vocab ->
                                val verdict = state.vocabularyVerdict
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "生词 ${vocab.unknown.size}/${vocab.total}" +
                                            "（${(vocab.rate * 100).toInt()}%）",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Serif,
                                        ),
                                        color = theme.subtle,
                                    )
                                    verdict?.let {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "· ${it.label}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = theme.body,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (vocab.unknown.isNotEmpty()) {
                                        Text(
                                            text = "预习生词",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = theme.body,
                                            modifier = Modifier.clickable {
                                                viewModel.setNewWordsVisible(true)
                                            },
                                        )
                                    }
                                }
                                verdict?.let {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = it.detail,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Serif,
                                        ),
                                        color = theme.subtle,
                                    )
                                }
                            }
                            if (article.isLiveBlog) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "这是一篇滚动直播，由多条短更新拼成，段落之间不连贯；" +
                                        "用来练查词和拆句可以，练篇章理解建议换一篇常规报道。",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Serif,
                                    ),
                                    color = theme.subtle,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (state.showTranslation && state.translationStatus == TranslationStatus.Failed) {
                        item {
                            TranslationNotice(
                                message = state.translationError ?: "翻译失败",
                                theme = theme,
                                onRetry = { viewModel.requestTranslation() },
                            )
                        }
                    }

                    itemsIndexed(
                        items = state.paragraphs,
                        key = { index, _ -> index },
                    ) { index, rp ->
                        ParagraphBlock(
                            paragraph = rp.paragraph,
                            sentences = rp.sentences,
                            savedLemmas = savedLemmas,
                            highlightSaved = state.highlightSaved,
                            listening = state.listening,
                            speakingSentenceIndex = state.flatSentences
                                .getOrNull(state.speakingSentence)
                                ?.takeIf { state.listening && it.paragraphIndex == index }
                                ?.sentenceIndex
                                ?: -1,
                            /** Flat index of this paragraph's first sentence. */
                            firstFlatIndex = flatStart[index] ?: -1,
                            onSentenceBounds = spokenBounds::putOrRemove,
                            translation = state.translation
                                .getOrNull(index)
                                ?.takeIf { state.showTranslation && it.isNotBlank() },
                            translationLoading = state.showTranslation &&
                                state.translationStatus == TranslationStatus.Loading &&
                                state.translation.getOrNull(index).isNullOrBlank(),
                            bodyColor = theme.body,
                            subtleColor = theme.subtle,
                            highlightColor = if (theme.isDark) Color(0xFF2E4A40) else Color(0xFFDCEDE4),
                            savedColor = if (theme.isDark) Color(0xFF7FC8B0) else Color(0xFF1F5A4A),
                            fontSize = state.fontSize,
                            lineHeightMultiplier = state.lineHeight,
                            onWordTap = { word, sentence ->
                                scope.launch { viewModel.lookup(word, sentence) }
                            },
                            onSentenceLongPress = { sentence ->
                                scope.launch { viewModel.analyze(sentence) }
                            },
                            onSentenceTapWhileListening = { indexInParagraph ->
                                // The flat list is built paragraph by paragraph, so a
                                // sentence's flat index is its paragraph's first plus
                                // its offset. Scanning the whole list per tap was
                                // linear in the length of the article.
                                val first = flatStart[index] ?: -1
                                val flat = if (first < 0) -1 else first + indexInParagraph
                                if (flat in state.flatSentences.indices) viewModel.speakSentence(flat)
                            },
                        )
                    }

                    item {
                        Spacer(Modifier.height(24.dp))
                        Row(
                            Modifier.padding(horizontal = 22.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReaderAction(
                                label = "做理解题",
                                icon = Icons.Outlined.Checklist,
                                surface = if (theme.isDark) Color(0xFF262A2E) else Color(0xFFF0EBE1),
                                content = theme.body,
                                accent = if (theme.isDark) Palette.Mint else Palette.Pine,
                            ) {
                                scope.launch {
                                    viewModel.prepareQuiz()
                                    showQuiz = true
                                }
                            }
                            ReaderAction(
                                label = if (state.showTranslation) "隐藏译文" else "中文译文",
                                icon = Icons.Outlined.Translate,
                                surface = if (theme.isDark) Color(0xFF262A2E) else Color(0xFFF0EBE1),
                                content = theme.body,
                                accent = if (state.showTranslation) {
                                    if (theme.isDark) Palette.AmberLight else Palette.Amber
                                } else if (theme.isDark) {
                                    Palette.Mint
                                } else {
                                    Palette.Pine
                                },
                            ) { viewModel.setShowTranslation(!state.showTranslation) }
                            ReaderAction(
                                label = "排版",
                                icon = Icons.Outlined.TextFields,
                                surface = if (theme.isDark) Color(0xFF262A2E) else Color(0xFFF0EBE1),
                                content = theme.body,
                                accent = if (theme.isDark) Palette.Mint else Palette.Pine,
                            ) { showTypography = true }
                        }
                        if (state.book != null) {
                            Spacer(Modifier.height(20.dp))
                            ChapterNav(
                                theme = theme,
                                previous = viewModel.chapterNeighbour(-1),
                                next = viewModel.chapterNeighbour(1),
                                index = article.chapterIndex,
                                total = state.chapters.size,
                                onOpen = { chapter ->
                                    // The bookmark moves before the new chapter is even
                                    // opened, so a jump that fails to render still counts
                                    // as having read up to here.
                                    val bookId = state.book?.id ?: return@ChapterNav
                                    scope.launch {
                                        viewModel.articleIdOfChapter(bookId, chapter.index)
                                            ?.let { onOpenChapter(it, 0) }
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                ReaderTopBar(
                    // A chapter is titled by its book, not by itself: "第 12 章" alone
                    // does not tell the reader which of several open books this is.
                    title = state.book?.let { "${it.title} · ${article.title}" }
                        ?: article.title,
                    saved = state.article?.saved == true,
                    listening = state.listening,
                    speechAvailable = liveSpeechState != SpeechState.Unavailable,
                    hasContents = state.book != null,
                    theme = theme,
                    onBack = {
                        viewModel.flushProgress()
                        viewModel.stopListening()
                        onBack()
                    },
                    onOpenContents = { viewModel.setContentsVisible(true) },
                    onOpenSearch = { viewModel.setSearchVisible(true) },
                    onToggleSave = {
                        val article = state.article ?: return@ReaderTopBar
                        viewModel.setSaved(!article.saved)
                        onSavedChanged()
                    },
                    onToggleListen = {
                        if (!state.listening) {
                            askForNotificationPermission(context) { notifications.launch(it) }
                        }
                        viewModel.toggleListening(scope)
                    },
                )

                if (state.listening) {
                    ListeningBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        current = state.speakingSentence + 1,
                        total = state.flatSentences.size,
                        onPrevious = viewModel::previousSentence,
                        onToggle = viewModel::pauseListening,
                        onNext = viewModel::nextSentence,
                    )
                }
            }
        }
    }

    state.lookup?.let { lookup ->
        LookupSheet(
            state = lookup,
            onDismiss = viewModel::dismissLookup,
            onToggleSave = {
                scope.launch {
                    viewModel.toggleSave()
                    onSavedChanged()
                }
            },
            onSpeak = { viewModel.speakWord(it) },
            onAnalyzeSentence = {
                scope.launch { viewModel.analyze(lookup.inSentence) }
                viewModel.dismissLookup()
            },
            onSwitchReading = { headword -> scope.launch { viewModel.switchReading(headword) } },
            onShowChinese = viewModel::setShowChinese,
            onOpenRelated = { word -> scope.launch { viewModel.lookup(word, lookup.inSentence) } },
        )
    }

    state.analysis?.let { analysis ->
        AnalysisSheet(analysis = analysis, onDismiss = viewModel::dismissAnalysis)
    }

    if (state.newWordsVisible) {
        NewWordsSheet(
            words = state.newWords,
            onDismiss = { viewModel.setNewWordsVisible(false) },
            onSaveAll = { viewModel.saveUnknownWords() },
            onSpeak = { viewModel.speakWord(it) },
        )
    }

    if (state.contentsVisible) {
        val book = state.book
        if (book != null) {
            ContentsSheet(
                book = book,
                chapters = state.chapters,
                currentIndex = state.article?.chapterIndex ?: 0,
                onOpen = { chapter ->
                    viewModel.setContentsVisible(false)
                    if (chapter.articleId != articleId) {
                        scope.launch {
                            viewModel.articleIdOfChapter(book.id, chapter.index)
                                ?.let { onOpenChapter(it, 0) }
                        }
                    }
                },
                onDismiss = { viewModel.setContentsVisible(false) },
            )
        }
    }

    if (state.searchVisible) {
        SearchSheet(
            query = state.searchQuery,
            results = state.searchResults,
            running = state.searchRunning,
            bookTitle = state.book?.title,
            onQuery = viewModel::setSearchQuery,
            onOpen = { hit ->
                viewModel.jumpToHit(hit)
                // A hit in another chapter is a navigation: the screen owns where the
                // reader is, so the ViewModel only reports what was found.
                if (hit.articleId != articleId) onOpenChapter(hit.articleId, hit.paragraphIndex)
            },
            onDismiss = { viewModel.setSearchVisible(false) },
        )
    }

    if (showTypography) {
        TypographySheet(
            fontSize = state.fontSize,
            lineHeight = state.lineHeight,
            theme = state.theme,
            highlightSaved = state.highlightSaved,
            showTranslation = state.showTranslation,
            speechRate = state.speechRate,
            speechLocale = state.speechLocale,
            speechVoice = state.speechVoice,
            activeVoice = liveVoice,
            backend = liveBackend,
            speechReady = liveSpeechState == SpeechState.Ready,
            onFontSize = viewModel::setFontSize,
            onLineHeight = viewModel::setLineHeight,
            onTheme = viewModel::setTheme,
            onHighlightSaved = viewModel::setHighlightSaved,
            onShowTranslation = viewModel::setShowTranslation,
            onSpeechRate = viewModel::setSpeechRate,
            onSpeechLocale = viewModel::setSpeechLocale,
            onSpeechVoice = viewModel::setSpeechVoice,
            onPreviewVoice = viewModel::previewVoice,
            onDismiss = { showTypography = false },
        )
    }

    if (showQuiz) {
        QuizSheet(
            questions = state.quiz,
            onDismiss = { showQuiz = false },
            onFinish = { correct, total -> viewModel.recordQuiz(correct, total) },
        )
    }
}

@Composable
private fun MetaText(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * Shown above the article when a translation run failed.
 *
 * The translation is an aid, not the point of the screen, so a failure never blocks
 * reading: the English stays exactly as it was and this is the only trace of it.
 */
@Composable
private fun TranslationNotice(
    message: String,
    theme: com.engreader.app.ui.theme.ReadingTheme,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        Text(
            text = "译文没能取到：$message",
            style = MaterialTheme.typography.labelSmall,
            color = theme.subtle,
        )
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
            Text("重试", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * One paragraph.
 *
 * Tap → look up the word under the finger. Long-press → analyse the sentence under
 * the finger. While listening, a tap jumps playback to that sentence.
 *
 * With translations on, the paragraph is broken into its sentences and each English
 * sentence is followed by its own Chinese line in smaller type, so the two can be
 * read together. The split only happens when the Chinese divides into exactly as many
 * sentences as the English has ([SentenceAlignment.align]); otherwise the paragraph
 * and its translation are shown whole, as before — a line of Chinese under the wrong
 * sentence would be worse than no pairing.
 */
@Composable
private fun ParagraphBlock(
    paragraph: Paragraph,
    sentences: List<Sentence>,
    savedLemmas: SavedLemmaSet,
    highlightSaved: Boolean,
    /** True while the article is being read aloud, so a tap jumps playback. */
    listening: Boolean,
    /**
     * Sentence currently being spoken inside this paragraph, or -1.
     *
     * An index rather than the text: two sentences in a paragraph can read identically,
     * and matching on the text would then highlight the wrong one.
     */
    speakingSentenceIndex: Int,
    /**
     * Flat index of this paragraph's first sentence, or -1 when the paragraph has none.
     *
     * Sentences report their bounds under `firstFlatIndex + indexInParagraph`, which is
     * the same index the reader's playback state uses, so the auto-scroll can look up
     * where the spoken sentence is without knowing anything about paragraphs.
     */
    firstFlatIndex: Int,
    /** Reports where a sentence is, or null when it has left composition. */
    onSentenceBounds: (Int, Rect?) -> Unit,
    translation: String?,
    translationLoading: Boolean,
    bodyColor: Color,
    subtleColor: Color,
    highlightColor: Color,
    savedColor: Color,
    fontSize: Int,
    lineHeightMultiplier: Float,
    onWordTap: (String, String) -> Unit,
    onSentenceLongPress: (String) -> Unit,
    onSentenceTapWhileListening: (Int) -> Unit,
) {
    val english = remember(sentences) { sentences.map { it.text } }
    val sentenceTranslations = remember(english, translation) {
        translation?.takeIf { it.isNotBlank() }?.let { SentenceAlignment.align(english, it) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 9.dp)) {
        if (sentenceTranslations != null) {
            sentenceTranslations.forEachIndexed { index, chinese ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                SentenceBlock(
                    sentence = sentences[index],
                    indexInParagraph = index,
                    paragraph = paragraph,
                    savedLemmas = savedLemmas,
                    highlightSaved = highlightSaved,
                    listening = listening,
                    highlighted = index == speakingSentenceIndex,
                    flatIndex = if (firstFlatIndex >= 0) firstFlatIndex + index else -1,
                    onBounds = onSentenceBounds,
                    bodyColor = bodyColor,
                    subtleColor = subtleColor,
                    highlightColor = highlightColor,
                    savedColor = savedColor,
                    fontSize = fontSize,
                    lineHeightMultiplier = lineHeightMultiplier,
                    onWordTap = onWordTap,
                    onSentenceLongPress = onSentenceLongPress,
                    onSentenceTapWhileListening = onSentenceTapWhileListening,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = chinese,
                    color = subtleColor,
                    // Smaller than the body: the translation supports the English, and
                    // matching its size invites reading the Chinese instead.
                    fontSize = (fontSize - 3).coerceAtLeast(13).sp,
                    lineHeight = ((fontSize - 3) * 1.5f).sp,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@Column
        }

        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        // The spoken sentence's own offsets rather than `indexOf` of its text: a
        // paragraph of dialogue can hold the same sentence twice, and the first
        // occurrence is then the wrong one to underline. Keyed on the index, not on
        // the text, so two consecutive sentences that read alike still move the range.
        val spokenSentence = sentences.getOrNull(speakingSentenceIndex)
        val spokenRange = remember(paragraph, speakingSentenceIndex) {
            spokenSentence?.let { it.start until it.end }
        }
        val annotated = remember(paragraph, savedLemmas, spokenSentence, highlightSaved, highlightColor, savedColor) {
            buildEnglish(
                text = paragraph.text,
                tokens = paragraph.tokens,
                highlight = spokenRange,
                savedLemmas = savedLemmas.values,
                highlightSaved = highlightSaved,
                highlightColor = highlightColor,
                savedColor = savedColor,
            )
        }

        // Without a per-sentence split the paragraph is one `Text`, so the auto-scroll
        // is told where the spoken sentence's own lines sit inside it. The rectangle is
        // rebuilt whenever the layout or the text's position changes, which is what
        // keeps it correct as the list scrolls under it.
        var origin by remember { mutableStateOf<Offset?>(null) }
        val flatIndex = if (firstFlatIndex >= 0 && speakingSentenceIndex >= 0) {
            firstFlatIndex + speakingSentenceIndex
        } else {
            -1
        }
        LaunchedEffect(flatIndex, spokenRange, layout, origin) {
            val measured = layout
            val at = origin
            if (flatIndex < 0 || spokenRange == null || measured == null || at == null) {
                onSentenceBounds(flatIndex, null)
                return@LaunchedEffect
            }
            val lastOffset = (spokenRange.last - 1).coerceAtLeast(spokenRange.first)
            if (lastOffset >= measured.layoutInput.text.length) return@LaunchedEffect
            val firstLine = measured.getLineForOffset(spokenRange.first)
            val lastLine = measured.getLineForOffset(lastOffset)
            onSentenceBounds(
                flatIndex,
                Rect(
                    left = at.x,
                    top = at.y + measured.getLineTop(firstLine),
                    right = at.x + measured.size.width,
                    bottom = at.y + measured.getLineBottom(lastLine),
                ),
            )
        }
        DisposableEffect(flatIndex) {
            onDispose { onSentenceBounds(flatIndex, null) }
        }

        Text(
            text = annotated,
            color = bodyColor,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineHeightMultiplier).sp,
            fontFamily = FontFamily.Serif,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { origin = unclippedBounds(it).topLeft }
                .pointerInput(paragraph, sentences, listening) {
                    detectTapGestures(
                        onTap = { position ->
                            val textLayout = layout ?: return@detectTapGestures
                            val offset = textLayout.getOffsetForPosition(position)
                            // Matched by index rather than by the sentence object: two
                            // sentences in one paragraph can be textually equal, and
                            // `indexOf` would then always resolve to the first of them.
                            val sentenceIndex = sentences.indexOfFirst { offset in it.start until it.end }
                            val sentence = sentences.getOrNull(sentenceIndex)
                            if (listening) {
                                // While listening a tap is a request to jump playback to
                                // that sentence, anywhere in the paragraph — not only
                                // inside the one currently being spoken.
                                if (sentenceIndex >= 0) onSentenceTapWhileListening(sentenceIndex)
                            } else {
                                val word = Paragraphs.wordAt(paragraph, offset)
                                if (word != null) onWordTap(word, sentence?.text ?: paragraph.text)
                            }
                        },
                        onLongPress = { position ->
                            val textLayout = layout ?: return@detectTapGestures
                            val offset = textLayout.getOffsetForPosition(position)
                            val sentence = sentences.firstOrNull { offset in it.start until it.end }
                                ?: sentences.firstOrNull()
                            if (sentence != null) onSentenceLongPress(sentence.text)
                        },
                    )
                },
            onTextLayout = { layout = it },
        )

        if (translation != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = translation,
                color = subtleColor,
                // Slightly smaller than the body: the translation is a support for the
                // English, and matching its size invites reading the Chinese instead.
                fontSize = (fontSize - 3).coerceAtLeast(13).sp,
                lineHeight = ((fontSize - 3) * 1.5f).sp,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (translationLoading) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "翻译中…",
                color = subtleColor.copy(alpha = 0.7f),
                fontSize = (fontSize - 4).coerceAtLeast(12).sp,
                fontFamily = FontFamily.SansSerif,
            )
        }
    }
}

/**
 * One English sentence in a translated paragraph.
 *
 * A separate `Text` per sentence is what makes the Chinese line sit under the right
 * sentence; the tap offsets stay exact because a sentence's own text is a contiguous
 * slice of the paragraph, so an offset inside it is only [Sentence.start] away from
 * the paragraph offset the word lookup expects.
 */
@Composable
private fun SentenceBlock(
    sentence: Sentence,
    /** Index of this sentence inside its paragraph, which is what playback jumps to. */
    indexInParagraph: Int,
    paragraph: Paragraph,
    savedLemmas: SavedLemmaSet,
    highlightSaved: Boolean,
    listening: Boolean,
    highlighted: Boolean,
    /** Flat index of this sentence, which is what the auto-scroll looks up. */
    flatIndex: Int,
    /** Reports where this sentence is on screen, or null when it leaves composition. */
    onBounds: (Int, Rect?) -> Unit,
    bodyColor: Color,
    subtleColor: Color,
    highlightColor: Color,
    savedColor: Color,
    fontSize: Int,
    lineHeightMultiplier: Float,
    onWordTap: (String, String) -> Unit,
    onSentenceLongPress: (String) -> Unit,
    onSentenceTapWhileListening: (Int) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tokens = remember(paragraph, sentence) {
        paragraph.tokens
            .filter { it.start >= sentence.start && it.start < sentence.end }
            .map { WordSpan(it.start - sentence.start, it.end.coerceAtMost(sentence.end) - sentence.start, it.text) }
    }
    val annotated = remember(sentence, tokens, savedLemmas, highlighted, highlightSaved, highlightColor, savedColor) {
        buildEnglish(
            text = sentence.text,
            tokens = tokens,
            highlight = if (highlighted) sentence.text.indices else null,
            savedLemmas = savedLemmas.values,
            highlightSaved = highlightSaved,
            highlightColor = highlightColor,
            savedColor = savedColor,
        )
    }

    // Where this sentence sits, for the listening auto-scroll. Reported on every
    // position change rather than only when it becomes the spoken one: the scroll
    // reads it as the list moves, and a rectangle recorded once would be stale the
    // moment anything above it changed height.
    DisposableEffect(flatIndex) {
        onDispose { onBounds(flatIndex, null) }
    }

    Text(
        text = annotated,
        color = bodyColor,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeightMultiplier).sp,
        fontFamily = FontFamily.Serif,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onBounds(flatIndex, unclippedBounds(it)) }
            .pointerInput(sentence, listening) {
                detectTapGestures(
                    onTap = { position ->
                        if (listening) {
                            onSentenceTapWhileListening(indexInParagraph)
                            return@detectTapGestures
                        }
                        val textLayout = layout ?: return@detectTapGestures
                        val offset = textLayout.getOffsetForPosition(position)
                        val word = Paragraphs.wordAt(paragraph, sentence.start + offset)
                        if (word != null) onWordTap(word, sentence.text)
                    },
                    onLongPress = { onSentenceLongPress(sentence.text) },
                )
            },
        onTextLayout = { layout = it },
    )
}

/**
 * The wordbook's lemmas in a form Compose can skip on.
 *
 * A `Set<String>` is an interface, so the compiler cannot prove it immutable and
 * treats every paragraph as changed on each recomposition of the reader — which,
 * while an article is being read aloud, happens every few seconds. This wrapper is
 * declared immutable and compares by content, so an equal set skips. The backing set
 * is replaced rather than mutated.
 */
@Immutable
private class SavedLemmaSet(val values: Set<String>) {
    operator fun contains(lemma: String): Boolean = lemma in values
    override fun equals(other: Any?): Boolean = other is SavedLemmaSet && values == other.values
    override fun hashCode(): Int = values.hashCode()
}

/**
 * The English run styled for the reader: the spoken sentence highlighted, and every
 * word already in the wordbook underlined. [tokens] are offsets into [text].
 */
private fun buildEnglish(
    text: String,
    tokens: List<WordSpan>,
    highlight: IntRange?,
    savedLemmas: Set<String>,
    highlightSaved: Boolean,
    highlightColor: Color,
    savedColor: Color,
) = buildAnnotatedString {
    append(text)
    highlight?.let { addStyle(SpanStyle(background = highlightColor), it.first, it.last + 1) }
    if (highlightSaved) {
        tokens.forEach { token ->
            val lemma = Tokenizer.normalize(token.text)
            if (lemma.isNotEmpty() && savedLemmas.contains(lemma)) {
                addStyle(
                    SpanStyle(
                        color = savedColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium,
                    ),
                    token.start,
                    token.end,
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    saved: Boolean,
    listening: Boolean,
    speechAvailable: Boolean,
    /** True when this article is a chapter, i.e. when a table of contents exists. */
    hasContents: Boolean,
    /** The reading surface: the bar sits on it, so its colours must follow it. */
    theme: com.engreader.app.ui.theme.ReadingTheme,
    onBack: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleSave: () -> Unit,
    onToggleListen: () -> Unit,
) {
    val onSurface = theme.body
    val accent = if (theme.isDark) Palette.Mint else Palette.Pine
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.background)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = onSurface,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = theme.subtle,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasContents) {
            IconButton(onClick = onOpenContents) {
                Icon(
                    Icons.AutoMirrored.Outlined.List,
                    contentDescription = "目录",
                    tint = onSurface,
                )
            }
        }
        IconButton(onClick = onOpenSearch) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "查找",
                tint = onSurface,
            )
        }
        if (speechAvailable) {
            IconButton(onClick = onToggleListen) {
                Icon(
                    imageVector = if (listening) Icons.Filled.Pause else Icons.Filled.Headphones,
                    contentDescription = if (listening) "暂停朗读" else "开始朗读",
                    tint = if (listening) accent else onSurface,
                )
            }
        }
        IconButton(onClick = onToggleSave) {
            Icon(
                imageVector = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (saved) "取消收藏" else "收藏",
                tint = if (saved) accent else onSurface,
            )
        }
    }
}

/**
 * Previous / next chapter, at the foot of a chapter.
 *
 * Placed after the article rather than pinned to the screen: the reader reaches it by
 * finishing the chapter, which is exactly when the next one is wanted. A fixed bar
 * would also fight the listening bar for the same edge of the screen.
 */
@Composable
private fun ChapterNav(
    theme: com.engreader.app.ui.theme.ReadingTheme,
    previous: com.engreader.app.model.ChapterRef?,
    next: com.engreader.app.model.ChapterRef?,
    index: Int,
    total: Int,
    onOpen: (com.engreader.app.model.ChapterRef) -> Unit,
) {
    val surface = if (theme.isDark) Color(0xFF262A2E) else Color(0xFFF0EBE1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Text(
            text = "第 ${index + 1} / $total 章",
            style = MaterialTheme.typography.labelSmall,
            color = theme.subtle,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChapterNavButton(
                label = "上一章",
                enabled = previous != null,
                surface = surface,
                content = theme.body,
                onClick = { previous?.let(onOpen) },
                modifier = Modifier.weight(1f),
            )
            ChapterNavButton(
                label = "下一章",
                enabled = next != null,
                surface = surface,
                content = theme.body,
                onClick = { next?.let(onOpen) },
                modifier = Modifier.weight(1f),
            )
        }
        if (previous != null || next != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = next?.title ?: previous?.title.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Serif,
                ),
                color = theme.subtle,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChapterNavButton(
    label: String,
    enabled: Boolean,
    surface: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            // A disabled button keeps its shape but drops to a whisper: removing it
            // would make the row jump at the first and last chapter.
            color = if (enabled) content else content.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun ReaderAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    surface: Color,
    content: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        // Three of these share one row; without this the last label wraps to two
        // lines and the buttons no longer line up.
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            softWrap = false,
        )
    }
}
