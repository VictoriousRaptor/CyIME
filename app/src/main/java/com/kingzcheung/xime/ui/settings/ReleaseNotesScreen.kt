package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.model.DownloadMirrors
import com.kingzcheung.xime.update.AppUpdateCatalog
import com.kingzcheung.xime.update.ReleaseNote
import com.kingzcheung.xime.update.ReleaseNoteMarkdown
import com.kingzcheung.xime.update.ReleaseNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 关于 › 更新内容：安装包里带的各版发布说明，新的在前；上次看过之后的几版默认展开。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val current = BuildConfig.VERSION_NAME
    // 先记下打开前看到哪一版，再记成已看过：首页提示随之消失。
    val seenBefore = remember { ReleaseNotes.seenVersion(context) }
    LaunchedEffect(Unit) { ReleaseNotes.markSeen(context, current) }
    val notes by produceState<List<ReleaseNote>?>(null) {
        value = withContext(Dispatchers.IO) { ReleaseNotes.load(context) }
    }
    val releasesPage = AppUpdateCatalog.releasesPage(DownloadMirrors.prefersDomestic(context))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("更新内容") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).settingsVerticalScroll().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val list = notes ?: return@Column
            val expandedAtOpen = remember(list) {
                ReleaseNotes.expandedVersions(list.map { it.version }, current, seenBefore)
            }
            if (list.none { it.version == current }) {
                SettingsSection(title = "CyIME $current") {
                    SettingsListRow("这一版的说明没有带在安装包里", "点这里打开发布页查看",
                        Modifier.clickable { uriHandler.openUri(releasesPage) }) { OpenIcon() }
                }
            }
            list.forEach { note ->
                ReleaseNoteCard(note, current, initiallyExpanded = note.version in expandedAtOpen)
            }
            Column {
                SettingsSection(title = "更早的版本") {
                    SettingsListRow("发布页", "全部版本的说明和安装包",
                        Modifier.clickable { uriHandler.openUri(releasesPage) }) { OpenIcon() }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OpenIcon() {
    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 12.dp).size(18.dp))
}

@Composable
private fun ReleaseNoteCard(note: ReleaseNote, current: String, initiallyExpanded: Boolean) {
    var expanded by remember(note.version) { mutableStateOf(initiallyExpanded) }
    val blocks = remember(note.markdown) { ReleaseNoteMarkdown.withoutTitle(ReleaseNoteMarkdown.blocks(note.markdown)) }
    val newer = AppUpdateCatalog.isNewer(note.version, current)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CyIME ${note.version}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        val tag = when {
                            note.version == current -> "当前版本"
                            newer -> "比当前版本新"
                            else -> null
                        }
                        if (tag != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (!expanded) {
                        val summary = remember(note.markdown) { ReleaseNoteMarkdown.summary(note.markdown) }
                        if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    blocks.forEach { ReleaseNoteBlock(it) }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNoteBlock(block: ReleaseNoteMarkdown.Block) {
    when (block) {
        is ReleaseNoteMarkdown.Heading -> Text(
            inlineText(block.text),
            style = if (block.level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (block.level <= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp)
        )
        is ReleaseNoteMarkdown.Bullet -> Row(Modifier.padding(start = (block.depth * 14).dp)) {
            Text(if (block.depth == 0) "•" else "◦", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(14.dp))
            Text(inlineText(block.text), style = MaterialTheme.typography.bodyMedium)
        }
        is ReleaseNoteMarkdown.Paragraph -> Text(inlineText(block.text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun inlineText(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, linkColor, codeBackground) {
        buildAnnotatedString {
            ReleaseNoteMarkdown.spans(text).forEach { span ->
                when (span) {
                    is ReleaseNoteMarkdown.Link -> withLink(
                        LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = linkColor)))
                    ) { append(span.text) }
                    is ReleaseNoteMarkdown.Text -> when {
                        span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
                        span.code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground.copy(alpha = .6f))) { append(span.text) }
                        else -> append(span.text)
                    }
                }
            }
        }
    }
}
