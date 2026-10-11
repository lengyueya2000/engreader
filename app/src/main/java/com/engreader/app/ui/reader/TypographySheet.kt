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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.engreader.app.tts.SpeechBackend
import com.engreader.app.tts.VoiceCatalog
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.theme.ReadingTheme

/** Reader settings: type size, line height, surface colour, and speech options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySheet(
    fontSize: Int,
    lineHeight: Float,
    theme: ReadingTheme,
    highlightSaved: Boolean,
    showTranslation: Boolean,
    speechRate: Float,
    speechLocale: String,
    speechVoice: String,
    activeVoice: VoiceCatalog.BundledVoice?,
    backend: SpeechBackend,
    speechReady: Boolean,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onTheme: (ReadingTheme) -> Unit,
    onHighlightSaved: (Boolean) -> Unit,
    onShowTranslation: (Boolean) -> Unit,
    onSpeechRate: (Float) -> Unit,
    onSpeechLocale: (String) -> Unit,
    onSpeechVoice: (String) -> Unit,
    onPreviewVoice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Each slider holds its own value while the finger is down and commits on
    // release. Persisting on every step wrote the settings file once per frame of
    // the drag and recomposed the whole article behind the sheet for each of them.
    var previewSize by remember(fontSize) { mutableStateOf(fontSize.toFloat()) }
    var previewLineHeight by remember(lineHeight) { mutableStateOf(lineHeight) }
    var previewRate by remember(speechRate) { mutableStateOf(speechRate) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // The sheet is taller than a phone screen once the speech section is
                // expanded, and a bottom sheet does not scroll on its own — without
                // this the voice list is simply unreachable.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp)
        ) {
            Text(
                text = "排版与朗读",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(18.dp))

            SettingRow(Icons.Outlined.FormatSize, "字号", "${previewSize.toInt()}sp")
            Slider(
                value = previewSize,
                onValueChange = { previewSize = it },
                onValueChangeFinished = { onFontSize(previewSize.toInt()) },
                valueRange = 15f..30f,
                steps = 14,
            )

            Spacer(Modifier.height(6.dp))
            SettingRow(Icons.AutoMirrored.Outlined.Subject, "行距", "%.2f×".format(previewLineHeight))
            Slider(
                value = previewLineHeight,
                onValueChange = { previewLineHeight = it },
                onValueChangeFinished = { onLineHeight(previewLineHeight) },
                valueRange = 1.35f..2.3f,
                steps = 18,
            )

            Spacer(Modifier.height(10.dp))
            HairLine()
            Spacer(Modifier.height(14.dp))
            Text(
                "阅读底色",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadingTheme.entries.forEach { option ->
                    val selected = option == theme
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(option.background)
                            .clickable { onTheme(option) }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Aa",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            color = option.body,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = option.body,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                            }
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = option.body,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                label = "高亮生词本中的词",
                detail = "读过的生词在正文里带下划线，方便复现记忆",
                checked = highlightSaved,
                onCheckedChange = onHighlightSaved,
            )

            Spacer(Modifier.height(6.dp))
            ToggleRow(
                label = "显示句子中文翻译",
                detail = "每句英文下面用小字附上对应的中文，逐句对照",
                checked = showTranslation,
                onCheckedChange = onShowTranslation,
            )
            Text(
                text = "首次打开某篇文章需要联网获取译文，之后保存在本机。" +
                    "译文按段落获取，切句只在中英文句数一致时才逐句显示，否则整段对照。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 0.dp, bottom = 4.dp),
            )

            Spacer(Modifier.height(6.dp))
            HairLine()
            Spacer(Modifier.height(14.dp))
            SettingRow(Icons.Outlined.Headphones, "朗读语速", "%.2f×".format(previewRate))
            Slider(
                value = previewRate,
                onValueChange = { previewRate = it },
                onValueChangeFinished = { onSpeechRate(previewRate) },
                valueRange = 0.5f..1.4f,
                steps = 8,
            )

            Spacer(Modifier.height(6.dp))
            Text(
                "朗读口音",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceCatalog.accents.forEach { (tag, label) ->
                    val selected = speechLocale == tag
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .clickable { onSpeechLocale(tag) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            VoiceList(
                voices = VoiceCatalog.forLocale(speechLocale),
                speechVoice = speechVoice,
                activeVoice = activeVoice,
                speechReady = speechReady,
                onSpeechVoice = onSpeechVoice,
                onPreviewVoice = onPreviewVoice,
            )

            if (backend == SpeechBackend.System) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "内置语音没能加载，正在用这台机器的系统语音代替。音色由系统决定，" +
                        "不同设备差别很大。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The voices available for the selected accent.
 *
 * Every voice here is one the app ships, so the list is short and each row can say
 * what the voice actually is. A preview button sits on the selected row so the
 * choice can be heard without leaving the sheet.
 */
@Composable
private fun VoiceList(
    voices: List<VoiceCatalog.BundledVoice>,
    speechVoice: String,
    activeVoice: VoiceCatalog.BundledVoice?,
    speechReady: Boolean,
    onSpeechVoice: (String) -> Unit,
    onPreviewVoice: () -> Unit,
) {
    Text(
        "朗读音色",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = "语音随应用一起安装，不依赖手机自带的朗读引擎，因此在任何设备上听起来都一样。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (!speechReady) {
        Text(
            text = "正在载入语音模型，首次使用需要几秒钟。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (voices.isEmpty()) {
        Text(
            text = "这个口音暂时没有可选音色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val effective = if (speechVoice.isBlank()) activeVoice?.id.orEmpty() else speechVoice
    voices.forEach { voice ->
        val selected = voice.id == effective
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer
                )
                .clickable { onSpeechVoice(voice.id) }
                .padding(vertical = 8.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = voice.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = voice.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.VolumeUp,
                    contentDescription = "试听",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onPreviewVoice),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onPreviewVoice)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "试听当前音色",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
