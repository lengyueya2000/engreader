package com.engreader.app.ui.listening

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engreader.app.ui.LocalContainer
import com.engreader.app.ui.theme.Palette

/**
 * The strip above the tab bar that keeps listening reachable once its screen is closed.
 *
 * Without this there is no way back into the listening screen from inside the app: the
 * reader's bar only exists while the reader does, and the notification is outside the
 * app. It shows what is playing and the two controls worth having in the way — pause and
 * skip — and the whole strip opens the full screen.
 *
 * Nothing is drawn when no piece is loaded, so it costs the layout nothing until it is
 * wanted.
 */
@Composable
fun ListeningMiniPlayer(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = LocalContainer.current.listening
    val state by session.state.collectAsState()
    if (!state.active) return

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else (state.index + 1f) / state.total },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Palette.PineSoft,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { "听力模式" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.currentText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Trailing and unshrinkable: a long sentence used to push the position
                    // out of the row entirely, so the one number that says how much is
                    // left was the first thing to go.
                    if (state.total > 0) {
                        if (state.currentText.isNotBlank()) {
                            Text(
                                text = "　·　",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                        Text(
                            text = "第 ${state.index + 1} / ${state.total} 句",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { if (state.playing) session.pause() else session.resume() },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "暂停" else "继续",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = session::next, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Outlined.SkipNext,
                    contentDescription = "下一句",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = session::stop, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "停止朗读",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
