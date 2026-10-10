package com.engreader.app.ui.wordbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.engreader.app.data.ReviewCard
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.nlp.Cloze
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.containerViewModel
import com.engreader.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** Which face of a card is showing. */
private enum class CardFace {
    /** The word is hidden behind a blank in the sentence it was met in. */
    Context,

    /** The reader has revealed the answer: sentence, word, and meaning together. */
    Revealed,

    /** No sentence was recorded for this word, so it falls back to the bare card. */
    WordOnly,
}

/**
 * Flashcard review.
 *
 * The default card is the sentence the word was met in, with the word blanked out:
 * a word is remembered with its context, and a sentence with a gap in it is a
 * question the reader can actually answer, where `procrastination → 拖延` on its own
 * is only a recognition test. Words looked up before context was recorded fall back
 * to the bare word-and-meaning card.
 *
 * The self-report still drives the Leitner box, so no automatic grading is
 * attempted — the learner is the authority on whether a word came back to them.
 */
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val viewModel = containerViewModel(key = "wordbook:review") { WordbookViewModel(it) }
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<ReviewCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var index by remember { mutableIntStateOf(0) }
    var face by remember { mutableStateOf(CardFace.Context) }
    var correct by remember { mutableIntStateOf(0) }
    var wrong by remember { mutableIntStateOf(0) }
    var distractors by remember { mutableStateOf<List<String>>(emptyList()) }
    var definitions by remember { mutableStateOf<List<Pair<PartOfSpeech, String>>>(emptyList()) }
    /** Lemma whose extras are being fetched, so a stale fetch cannot land on a new card. */
    var revealFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        queue = viewModel.dueCards(limit = 30)
        loading = false
    }

    val current = queue.getOrNull(index)
    val word = current?.word

    LaunchedEffect(current) {
        // A card with no recorded sentence has nothing to blank out, so it opens on
        // the word itself rather than showing an empty context block.
        face = if (current == null || current.sentence.isBlank()) CardFace.WordOnly else CardFace.Context
        distractors = emptyList()
        definitions = emptyList()
        revealFor = null
    }

    // Keyed on both, so advancing the card cancels an in-flight fetch instead of
    // letting it write the previous card's extras into the new one.
    LaunchedEffect(current, revealFor) {
        val card = current ?: return@LaunchedEffect
        if (revealFor != card.word.lemma) return@LaunchedEffect
        distractors = viewModel.distractorGlosses(card.word)
        // The English definition joins the answer once the word has been answered
        // correctly before: the reader is ready to read the definition rather than
        // only the gloss.
        definitions = if (viewModel.isKnown(card.word.lemma)) {
            viewModel.definitions(card.word.lemma)
        } else {
            emptyList()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "复习",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                // Only while a card is on screen: past the last one `index` has
                // already advanced, and "2 / 1" is what that renders as.
                if (index < queue.size) {
                    Text(
                        text = "${index + 1} / ${queue.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (correct + wrong > 0) {
                Text(
                    text = "对 $correct · 错 $wrong",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }

        if (queue.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { (index).toFloat() / queue.size },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainer,
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }

            queue.isEmpty() -> EmptyState(
                title = "今天没有到期的词",
                detail = "记忆曲线会按 10 分钟 → 1 天 → 3 天 → 7 天 → 21 天的间隔安排复习。先去读一篇新文章吧。",
                modifier = Modifier.fillMaxSize(),
                actionLabel = "返回",
                onAction = onBack,
            )

            current == null || word == null -> EmptyState(
                title = "本轮复习完成",
                detail = "答对 $correct 个，答错 $wrong 个。答错的词会很快再次出现。",
                modifier = Modifier.fillMaxSize(),
                actionLabel = "返回生词本",
                onAction = onBack,
            )

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp)
            ) {
                Spacer(Modifier.height(20.dp))

                if (face != CardFace.Revealed) {
                    ContextPrompt(
                        card = current,
                        onSpeak = { viewModel.speak(word.lemma) },
                    )
                } else {
                    AnswerCard(
                        card = current,
                        definitions = definitions,
                        distractors = distractors,
                        onSpeak = { viewModel.speak(word.lemma) },
                    )
                }

                Spacer(Modifier.height(20.dp))

                if (face != CardFace.Revealed) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                face = CardFace.Revealed
                                revealFor = word.lemma
                            }
                            .padding(vertical = 15.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (current.sentence.isBlank()) "显示释义" else "对答案",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (current.sentence.isBlank()) {
                            "想不起来也没关系，直接显示，然后选「没想起来」。"
                        } else {
                            "先在脑子里把空填上，再对答案。想不起来也没关系。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnswerButton(
                            label = "没想起来",
                            icon = Icons.Outlined.Close,
                            tint = Palette.Clay,
                            fill = Palette.ClaySoft,
                            modifier = Modifier.weight(1f),
                        ) {
                            // The write is detached so backing out of the session
                            // immediately after answering still records the answer; the
                            // queue advance below is local UI state and stays here.
                            viewModel.reviewDetached(word.lemma, correct = false)
                            wrong++
                            // A missed word returns at the end of this session's queue.
                            queue = queue + current
                            index++
                        }
                        AnswerButton(
                            label = "记住了",
                            icon = Icons.Outlined.Check,
                            tint = Palette.Pine,
                            fill = Palette.PineSoft,
                            modifier = Modifier.weight(1f),
                        ) {
                            viewModel.reviewDetached(word.lemma, correct = true)
                            correct++
                            index++
                        }
                    }
                }

                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp
                    )
                )
            }
        }
    }
}

/**
 * The question face: the sentence with the target word blanked out.
 *
 * The word itself is deliberately not shown above the sentence. Showing both makes
 * the sentence decoration, and the reader can answer by pattern-matching the
 * spelling rather than by recalling the meaning.
 */
@Composable
private fun ContextPrompt(card: ReviewCard, onSpeak: () -> Unit) {
    val blanked = remember(card.sentence, card.surface, card.word.lemma) {
        val target = card.surface.ifBlank { card.word.lemma }
        val text = Cloze.blank(card.sentence, target)
        // The stored surface form is what the article actually used, but a word
        // looked up through a lemma (`derive` tapped as `derived`) can still miss if
        // the sentence was recorded from a different occurrence. Falling back to the
        // lemma keeps the card answerable instead of printing the answer.
        if (text == card.sentence) Cloze.blank(card.sentence, card.word.lemma) else text
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 28.dp, horizontal = 20.dp),
    ) {
        Text(
            text = "这个词在这句话里是什么意思？",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = blanked,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onSpeak)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "朗读",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "听发音",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "盒子 ${card.word.box}/5 · 已复习 ${card.word.correct + card.word.wrong} 次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The answer face: the word, its meaning, and the sentence it came from. */
@Composable
private fun AnswerCard(
    card: ReviewCard,
    definitions: List<Pair<PartOfSpeech, String>>,
    distractors: List<String>,
    onSpeak: () -> Unit,
) {
    val word = card.word
    val gloss = remember(word.translation) {
        word.translation.lineSequence().firstOrNull { it.isNotBlank() }
            ?.substringBefore("\\n").orEmpty()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 24.dp, horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = word.display,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (word.phonetic.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "/${word.phonetic}/",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onSpeak)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "朗读",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = gloss,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // The definition is added once the reader has answered this word right
        // before, so the card grows with the learner instead of showing both
        // languages from the start.
        if (definitions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "English definition",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            definitions.take(3).forEach { (_, text) ->
                Text(
                    text = "· $text",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (card.sentence.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "原文",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = card.sentence,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (word.note.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "我的笔记",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = word.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (distractors.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "易混释义（不要记串）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            distractors.forEach {
                Text(
                    text = "· $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnswerButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    fill: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}
