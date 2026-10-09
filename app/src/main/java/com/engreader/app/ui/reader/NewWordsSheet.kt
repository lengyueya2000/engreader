package com.engreader.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.engreader.app.nlp.NewWord
import com.engreader.app.ui.components.BandChip
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.theme.Palette

/**
 * The article's new words, listed before reading.
 *
 * Extensive reading works when the unknown rate is low enough to guess from
 * context; past roughly one word in ten it stops being reading and becomes
 * decoding. This panel is the escape hatch for a text worth reading but slightly
 * over that line: see what is new, hear it, save it, then read.
 *
 * Words arrive hardest-first, because those are the ones that decide whether the
 * passage is readable at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewWordsSheet(
    words: List<NewWord>,
    onDismiss: () -> Unit,
    onSaveAll: () -> Unit,
    onSpeak: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 16.dp
                )
        ) {
            Text(
                text = "这篇里的生词",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "共 ${words.size} 个。先扫一遍，读的时候会顺很多；" +
                    "也可以一次性加入生词本，读完再复习。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            HairLine()
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(words, key = { it.lemma }) { word ->
                    NewWordRow(word = word, onSpeak = { onSpeak(word.lemma) })
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onSaveAll)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "全部加入生词本（${words.size} 个）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NewWordRow(word: NewWord, onSpeak: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = word.lemma,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (word.gloss.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = word.gloss,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BandChip(word.band)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onSpeak)
                .padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "朗读",
                tint = Palette.Pine,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
