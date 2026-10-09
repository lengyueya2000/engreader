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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onTheme: (ReadingTheme) -> Unit,
    onHighlightSaved: (Boolean) -> Unit,
    onShowTranslation: (Boolean) -> Unit,
    onSpeechRate: (Float) -> Unit,
    onSpeechLocale: (String) -> Unit,
    onDismiss: () -> Unit,
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
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp)
        ) {
            Text(
                text = "排版与朗读",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(18.dp))

            SettingRow(Icons.Outlined.FormatSize, "字号", "${fontSize}sp")
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { onFontSize(it.toInt()) },
                valueRange = 15f..30f,
                steps = 14,
            )

            Spacer(Modifier.height(6.dp))
            SettingRow(Icons.AutoMirrored.Outlined.Subject, "行距", "%.2f×".format(lineHeight))
            Slider(
                value = lineHeight,
                onValueChange = onLineHeight,
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
                label = "显示段落中文翻译",
                detail = "每段英文下方附上中文译文",
                checked = showTranslation,
                onCheckedChange = onShowTranslation,
            )
            Text(
                text = "首次打开某篇文章需要联网获取译文，之后保存在本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 0.dp, bottom = 4.dp),
            )

            Spacer(Modifier.height(6.dp))
            HairLine()
            Spacer(Modifier.height(14.dp))
            SettingRow(Icons.Outlined.Headphones, "朗读语速", "%.2f×".format(speechRate))
            Slider(
                value = speechRate,
                onValueChange = onSpeechRate,
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
                listOf("en-GB" to "英式", "en-US" to "美式", "en-AU" to "澳式").forEach { (tag, label) ->
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
            Spacer(Modifier.height(10.dp))
            Text(
                text = "朗读使用系统内置的英语语音。若没有声音，请在系统设置的「文字转语音」中安装英语语音包。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
