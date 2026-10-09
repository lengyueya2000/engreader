package com.engreader.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Checklist
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engreader.app.nlp.Paragraph
import com.engreader.app.nlp.Sentence
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.containerScopedViewModel
import com.engreader.app.ui.theme.Palette
import kotlinx.coroutines.launch

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
) {
    // Scoped to this article rather than to the activity: the reader is opened once
    // per article, and an activity-scoped ViewModel would keep serving the first one.
    val viewModel = containerScopedViewModel(key = "reader:$articleId") {
        ReaderViewModel(it, articleId)
    }
    val scope = rememberCoroutineScope()
    val state = viewModel.state
    val listState = rememberLazyListState()
    var showTypography by remember { mutableStateOf(false) }
    var showQuiz by remember { mutableStateOf(false) }
    val liveSpeechState by viewModel.speechState.collectAsStateCompat()

    LaunchedEffect(articleId) { viewModel.load() }

    DisposableEffect(articleId) {
        onDispose { viewModel.flushProgress() }
    }

    // Keep the sentence being read on screen during listening mode.
    LaunchedEffect(state.speakingSentence) {
        val index = state.speakingSentence
        if (index >= 0 && state.listening) {
            val paragraphIndex = state.flatSentences.getOrNull(index)?.paragraphIndex ?: return@LaunchedEffect
            runCatching { listState.animateScrollToItem(paragraphIndex + 1) }
        }
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp,
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
                            savedLemmas = state.savedLemmas,
                            highlightSaved = state.highlightSaved,
                            speakingText = state.flatSentences
                                .getOrNull(state.speakingSentence)
                                ?.takeIf { state.listening && it.paragraphIndex == index }
                                ?.text,
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
                                val flat = state.flatSentences.indexOfFirst {
                                    it.paragraphIndex == index && it.sentenceIndex == indexInParagraph
                                }
                                if (flat >= 0) viewModel.speakSentence(flat)
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
                        Spacer(Modifier.height(16.dp))
                    }
                }

                ReaderTopBar(
                    title = state.article?.title.orEmpty(),
                    saved = state.article?.saved == true,
                    listening = state.listening,
                    speechAvailable = liveSpeechState != com.engreader.app.tts.SpeechState.Unavailable,
                    theme = theme,
                    onBack = {
                        viewModel.flushProgress()
                        viewModel.stopListening()
                        onBack()
                    },
                    onToggleSave = {
                        val article = state.article ?: return@ReaderTopBar
                        scope.launch {
                            viewModel.setSaved(!article.saved)
                            onSavedChanged()
                        }
                    },
                    onToggleListen = { viewModel.toggleListening(scope) },
                )

                if (state.listening) {
                    ListeningBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        current = state.speakingSentence + 1,
                        total = state.flatSentences.size,
                        onPrevious = viewModel::previousSentence,
                        onToggle = { viewModel.stopListening() },
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
            onSaveAll = { scope.launch { viewModel.saveUnknownWords() } },
            onSpeak = { viewModel.speakWord(it) },
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
            onFontSize = viewModel::setFontSize,
            onLineHeight = viewModel::setLineHeight,
            onTheme = viewModel::setTheme,
            onHighlightSaved = viewModel::setHighlightSaved,
            onShowTranslation = viewModel::setShowTranslation,
            onSpeechRate = viewModel::setSpeechRate,
            onSpeechLocale = viewModel::setSpeechLocale,
            onDismiss = { showTypography = false },
        )
    }

    if (showQuiz) {
        QuizSheet(
            questions = state.quiz,
            onDismiss = { showQuiz = false },
            onFinish = { correct, total ->
                scope.launch { viewModel.recordQuiz(correct, total) }
            },
        )
    }
}

/** Bridges `StateFlow` into Compose state without pulling in collectAsStateWithLifecycle. */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> {
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
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
 * The Chinese translation, when shown, sits directly under the paragraph it belongs
 * to so the two are read together rather than looked up in a separate panel.
 */
@Composable
private fun ParagraphBlock(
    paragraph: Paragraph,
    sentences: List<Sentence>,
    savedLemmas: Set<String>,
    highlightSaved: Boolean,
    speakingText: String?,
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
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val annotated = remember(paragraph, savedLemmas, speakingText, highlightSaved, bodyColor, highlightColor, savedColor) {
        buildAnnotatedString {
            append(paragraph.text)
            if (speakingText != null) {
                val at = paragraph.text.indexOf(speakingText)
                if (at >= 0) {
                    addStyle(
                        SpanStyle(background = highlightColor),
                        at,
                        at + speakingText.length,
                    )
                }
            }
            if (highlightSaved) {
                paragraph.tokens.forEach { token ->
                    val lemma = com.engreader.app.nlp.Tokenizer.normalize(token.text)
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
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 9.dp)) {
        Text(
            text = annotated,
            color = bodyColor,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineHeightMultiplier).sp,
            fontFamily = FontFamily.Serif,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(paragraph, sentences, speakingText) {
                    detectTapGestures(
                        onTap = { position ->
                            val textLayout = layout ?: return@detectTapGestures
                            val offset = textLayout.getOffsetForPosition(position)
                            val sentence = sentences.firstOrNull { offset in it.start until it.end }
                            val word = com.engreader.app.nlp.Paragraphs.wordAt(paragraph, offset)
                            if (speakingText != null && sentence != null) {
                                val index = sentences.indexOf(sentence)
                                if (index >= 0) onSentenceTapWhileListening(index)
                            } else if (word != null) {
                                onWordTap(word, sentence?.text ?: paragraph.text)
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

@Composable
private fun ReaderTopBar(
    title: String,
    saved: Boolean,
    listening: Boolean,
    speechAvailable: Boolean,
    /** The reading surface: the bar sits on it, so its colours must follow it. */
    theme: com.engreader.app.ui.theme.ReadingTheme,
    onBack: () -> Unit,
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
