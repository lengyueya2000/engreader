package com.engreader.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.engreader.app.model.QuizQuestion
import com.engreader.app.ui.components.EmptyState
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.theme.Palette

/** Playback controls shown while listening; a compact bar so text stays visible. */
@Composable
fun ListeningBar(
    modifier: Modifier = Modifier,
    current: Int,
    total: Int,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一句", tint = MaterialTheme.colorScheme.onPrimary)
        }
        IconButton(onClick = onToggle) {
            Icon(Icons.Filled.Pause, contentDescription = "停止朗读", tint = MaterialTheme.colorScheme.onPrimary)
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Outlined.SkipNext, contentDescription = "下一句", tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "正在朗读第 $current / $total 句",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                text = "点击句子可跳转，再点一次继续",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * Comprehension quiz.
 *
 * Questions are generated from the article's own vocabulary and reference
 * structures, so the answers are derivable from the text rather than from a
 * model's guess. The explanation for each answer states the rule that was used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizSheet(
    questions: List<QuizQuestion>,
    onDismiss: () -> Unit,
    onFinish: (correct: Int, total: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var index by remember { mutableIntStateOf(0) }
    /**
     * Question index → chosen option index.
     *
     * The score is derived from this rather than counted as answers come in, so
     * going back and answering a question again replaces its entry instead of
     * adding a second point. Counting on click let "上一题" inflate the result past
     * the number of questions, and that inflated score is what got recorded.
     */
    var answers by remember { mutableStateOf(emptyMap<Int, Int>()) }
    var finished by remember { mutableStateOf(false) }

    val selected = answers[index]
    val correctCount = answers.count { (questionIndex, optionIndex) ->
        questions.getOrNull(questionIndex)?.answerIndex == optionIndex
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp)
        ) {
            if (questions.isEmpty()) {
                EmptyState(
                    title = "这篇文章出不了题",
                    detail = "文章太短，或者里面的词都太基础。换一篇长一点的文章再试。",
                    modifier = Modifier.height(240.dp),
                )
                return@Column
            }

            if (finished) {
                val accuracy = if (questions.isEmpty()) 0f else correctCount.toFloat() / questions.size
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = if (accuracy >= 0.6f) Palette.Pine else Palette.Amber,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "答对 $correctCount / ${questions.size}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            accuracy >= 0.8f -> "这篇的词汇和句式基本吃透了。"
                            accuracy >= 0.5f -> "大意抓住了，错题涉及的词值得再看一遍。"
                            else -> "先把生词加入生词本，明天再读一遍这篇。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "理解自测",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${index + 1} / ${questions.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val question = questions[index]
            Spacer(Modifier.height(14.dp))
            Text(
                text = question.question,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            question.options.forEachIndexed { optionIndex, option ->
                val isSelected = selected == optionIndex
                val isAnswer = optionIndex == question.answerIndex
                val revealed = selected != null
                val fill = when {
                    revealed && isAnswer -> MaterialTheme.colorScheme.primaryContainer
                    revealed && isSelected -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(fill)
                        .clickable(enabled = selected == null) {
                            answers = answers + (index to optionIndex)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (revealed && isAnswer) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ('A' + optionIndex).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (revealed && isAnswer) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (revealed && (isAnswer || isSelected)) {
                        Icon(
                            imageVector = if (isAnswer) Icons.Outlined.CheckCircle else Icons.Outlined.Close,
                            contentDescription = null,
                            tint = if (isAnswer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }

            if (selected != null && question.explanation.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                HairLine()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = question.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (index > 0) {
                    QuizButton("上一题", false) { index-- }
                }
                QuizButton(
                    label = if (index == questions.lastIndex) "看结果" else "下一题",
                    primary = true,
                    enabled = selected != null,
                ) {
                    if (index == questions.lastIndex) {
                        finished = true
                        onFinish(correctCount, questions.size)
                    } else {
                        index++
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QuizButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surfaceContainer
                    primary -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                primary -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
