package com.engreader.app.ui.listening

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engreader.app.tts.ListeningSession
import com.engreader.app.tts.SleepOption
import com.engreader.app.tts.SleepTimer
import com.engreader.app.ui.LocalContainer
import com.engreader.app.ui.theme.Palette
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState

/** Speeds offered on the listening screen; the reader's own rate is whatever was last set. */
private val SPEEDS = listOf(0.8f, 0.95f, 1.15f, 1.35f)

/**
 * Listening with nothing to read.
 *
 * The reader's read-along keeps the text in front of the reader and follows their eye;
 * this is the other half of the same voice — the screen you open to put the phone in a
 * pocket. It shows what is being said, where in the piece it is, and the three controls
 * that matter with the screen off: previous, play/pause, next. The queue itself lives in
 * [ListeningSession] on the container, so this screen can be closed at any point and the
 * voice keeps going.
 */
@Composable
fun ListeningScreen(
    onBack: () -> Unit,
    onOpenArticle: (Long) -> Unit,
) {
    val container = LocalContainer.current
    val session = container.listening
    val state by session.state.collectAsState()

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // The countdown is a property of the clock, not of the session, so it is ticked here
    // rather than pushed through the queue's state once a second. Only while a deadline
    // exists: with no timer armed there is nothing to count down, and a permanent 1 Hz
    // recomposition of the whole screen would be pure waste.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.sleepUntil) {
        if (state.sleepUntil <= 0L) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = statusBar)
            .padding(bottom = navBar),
    ) {
        Header(
            title = state.title,
            subtitle = state.subtitle,
            onBack = onBack,
            onOpenText = { if (state.articleId > 0L) onOpenArticle(state.articleId) },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !state.active -> EmptyState(
                    text = state.error ?: "没有正在朗读的内容",
                    hint = "在文章里点右上角的耳机图标，或在书架上选一章开始听。",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> NowPlaying(state, Modifier.align(Alignment.Center))
            }
        }

        if (state.active) {
            Controls(
                playing = state.playing,
                onPrevious = session::previous,
                onToggle = { if (state.playing) session.pause() else session.resume() },
                onNext = session::next,
            )
            Spacer(Modifier.height(14.dp))
            Progress(state)
            Spacer(Modifier.height(18.dp))
            RateRow(state.rate, session::setRate)
            Spacer(Modifier.height(14.dp))
            SleepRow(
                chosen = state.sleep,
                remaining = SleepTimer.remainingLabel(state.sleepUntil, now),
                onChoose = session::setSleep,
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenText: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.Close, contentDescription = "收起", tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "听力模式" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (title.isNotBlank()) {
            IconButton(onClick = onOpenText) {
                Icon(
                    Icons.Outlined.AutoStories,
                    contentDescription = "打开原文",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The sentence being read, and where it sits in the piece. */
@Composable
private fun NowPlaying(state: ListeningSession.State, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.chapterCount > 1) {
            Text(
                text = "第 ${state.chapterIndex + 1} / ${state.chapterCount} 章",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = state.currentText,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (state.currentChinese.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.currentChinese,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Controls(
    playing: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Outlined.SkipPrevious,
                contentDescription = "上一句",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "暂停" else "继续",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Outlined.SkipNext,
                contentDescription = "下一句",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun Progress(state: ListeningSession.State) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else (state.index + 1f) / state.total },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "第 ${state.index + 1} / ${state.total} 句",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (!state.advanceChapter && state.bookId > 0L) {
                Text(
                    text = "读完本章停下",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RateRow(rate: Float, onRate: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "语速",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        SPEEDS.forEach { option ->
            Chip(
                label = "%.2f×".format(option),
                selected = kotlin.math.abs(option - rate) < 0.01f,
                onClick = { onRate(option) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SleepRow(chosen: SleepOption, remaining: String, onChoose: (SleepOption) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "定时关闭",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (remaining.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = remaining,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepOption.entries.forEach { option ->
                Chip(
                    label = option.label,
                    selected = option == chosen,
                    onClick = { onChoose(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.PineSoft else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Palette.Pine else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Kept local so the screen does not depend on the reader's empty-state component. */
@Composable
private fun EmptyState(text: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
