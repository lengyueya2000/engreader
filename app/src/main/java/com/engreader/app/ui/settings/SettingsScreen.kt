package com.engreader.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.engreader.app.data.BackupRepository
import com.engreader.app.tts.VoiceCatalog
import com.engreader.app.ui.LocalContainer
import com.engreader.app.ui.components.HairLine
import com.engreader.app.ui.theme.ReadingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings that are not reading-specific.
 *
 * Reader typography lives in the reader's own sheet, next to the text it affects;
 * this screen holds the preferences that apply across the app.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val settings = container.settings

    var autoSave by remember { mutableStateOf(settings.autoSaveLookups) }
    var highlight by remember { mutableStateOf(settings.highlightSavedWords) }
    var dailyGoal by remember { mutableStateOf(settings.dailyGoalMinutes) }
    var speechRate by remember { mutableStateOf(settings.speechRate) }
    var speechLocale by remember { mutableStateOf(settings.speechLocale) }
    var speechVoice by remember { mutableStateOf(settings.speechVoice) }
    var theme by remember { mutableStateOf(settings.readingTheme) }
    var showTranslation by remember { mutableStateOf(settings.showTranslation) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    /**
     * Writes the backup to the file the reader picked.
     *
     * The file is chosen through the system picker rather than written to a folder of
     * the app's choosing, so the export lands somewhere the reader can find it and the
     * app needs no storage permission.
     */
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupMessage = runCatching {
                val json = container.backup.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    } ?: throw IllegalStateException("打不开所选文件")
                }
                "已导出。"
            }.getOrElse { "导出失败：${it.message ?: "未知错误"}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupMessage = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw IllegalStateException("打不开所选文件")
                }
                val result = container.backup.restore(text)
                "已导入：新增生词 ${result.wordsAdded} 个，更新 ${result.wordsUpdated} 个，" +
                    "阅读记录 ${result.sessionsAdded} 条。"
            }.getOrElse { "导入失败：${it.message ?: "未知错误"}" }
        }
    }

    LaunchedEffect(Unit) {
        container.speaker.prepare(
            rate = settings.speechRate,
            localeTag = settings.speechLocale,
            voiceId = settings.speechVoice,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(Modifier.padding(horizontal = 22.dp)) {
            Group("查词与生词本")
            ToggleRow(
                label = "查词时自动加入生词本",
                detail = "打开后每次查词都会保存，复习队列会变长",
                checked = autoSave,
            ) {
                autoSave = it
                settings.autoSaveLookups = it
            }
            ToggleRow(
                label = "正文高亮生词本中的词",
                detail = "已收藏的词在文章里带下划线",
                checked = highlight,
            ) {
                highlight = it
                settings.highlightSavedWords = it
            }

            Spacer(Modifier.height(22.dp))
            HairLine()
            Spacer(Modifier.height(18.dp))

            Group("每日目标")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$dailyGoal 分钟",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(90.dp),
                )
                Slider(
                    value = dailyGoal.toFloat(),
                    onValueChange = { dailyGoal = it.toInt() },
                    onValueChangeFinished = { settings.dailyGoalMinutes = dailyGoal },
                    valueRange = 5f..60f,
                    steps = 10,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "按每分钟约 150 词计算，${dailyGoal} 分钟大约是一篇 800 词的外刊短文。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))
            HairLine()
            Spacer(Modifier.height(18.dp))

            Group("朗读")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%.2f×".format(speechRate),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(90.dp),
                )
                Slider(
                    value = speechRate,
                    onValueChange = {
                        speechRate = it
                        container.speaker.setRate(it)
                    },
                    onValueChangeFinished = { settings.speechRate = speechRate },
                    valueRange = 0.5f..1.4f,
                    steps = 8,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "口音",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceCatalog.accents.forEach { (tag, label) ->
                    ChoiceChip(label, speechLocale == tag) {
                        speechLocale = tag
                        settings.speechLocale = tag
                        // The stored voice belongs to the old accent, so it is cleared
                        // and the new accent's own default takes over.
                        settings.speechVoice = ""
                        speechVoice = ""
                        container.speaker.selectVoice(tag, "")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "语音随应用一起安装，不依赖手机自带的朗读引擎，" +
                    "因此在任何设备上听起来都一样。切换口音需要重新载入模型，约一两秒。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Text(
                text = "音色",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            VoiceCatalog.forLocale(speechLocale).forEach { voice ->
                val selected = if (speechVoice.isBlank()) {
                    voice == VoiceCatalog.defaultFor(speechLocale)
                } else {
                    voice.id == speechVoice
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                        .clickable {
                            speechVoice = voice.id
                            settings.speechVoice = voice.id
                            container.speaker.selectVoice(speechLocale, voice.id)
                        }
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(22.dp))
            HairLine()
            Spacer(Modifier.height(18.dp))

            Group("段落中文翻译")
            ToggleRow(
                label = "默认显示段落译文",
                detail = "每段英文下方附上中文；首次打开某篇文章时需要联网获取，之后存在本机",
                checked = showTranslation,
            ) {
                showTranslation = it
                settings.showTranslation = it
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "译文由公开的在线翻译服务生成（优先 Google 翻译，失败时改用 MyMemory），" +
                    "因此需要网络。文章正文会以段落为单位发送到该服务；译文保存在本机，不会重复发送。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))
            HairLine()
            Spacer(Modifier.height(18.dp))

            Group("默认阅读底色")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadingTheme.entries.forEach { option ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(option.background)
                            .clickable {
                                theme = option
                                settings.readingTheme = option
                            }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Aa",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            color = option.body,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (theme == option) option.body else option.subtle,
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            HairLine()
            Spacer(Modifier.height(18.dp))

            Group("备份与恢复")
            Text(
                text = "生词本里的复习进度、笔记和阅读记录只存在这台设备上，" +
                    "导出成文件后可以在换手机或重装后导回来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackupButton(
                    icon = Icons.Outlined.FileUpload,
                    label = "导出备份",
                    modifier = Modifier.weight(1f),
                ) { exportLauncher.launch(BackupRepository.FILE_NAME) }
                BackupButton(
                    icon = Icons.Outlined.FileDownload,
                    label = "导入备份",
                    modifier = Modifier.weight(1f),
                ) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }
            backupMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(22.dp))
            HairLine()
            Spacer(Modifier.height(18.dp))

            Group("关于内容来源")
            Text(
                text = "词库来自开源项目 ECDICT（约 5.9 万条常用词，含音标、词频与考试标签），" +
                    "内置文章为 Project Gutenberg 公版文本选段。外刊全文来自各媒体公开发布的 " +
                    "RSS 摘要与网页，仅用于个人学习阅读；若某篇无法抓取，应用会退回到摘要。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Text(
                text = "朗读用的是内置神经网络语音（Piper VITS），随安装包提供，" +
                    "不需要系统语音引擎，也不需要联网。只有在内置模型无法载入时，" +
                    "才会退回系统自带的朗读引擎。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp))
        }
    }
}

/** One of the two backup actions: an icon over a label, sized to share a row. */
@Composable
private fun BackupButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(10.dp))
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
            .padding(vertical = 10.dp),
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

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
