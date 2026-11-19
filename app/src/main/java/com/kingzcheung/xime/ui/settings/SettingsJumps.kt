package com.kingzcheung.xime.ui.settings

import androidx.compose.material3.MaterialTheme
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * 设置页说明里的指路（用户 11-01「设置页面内相关设置的跳转」）：「按键与手势 › 长按空格」这样写在「」里的设置，
 * 点一下就跳过去并高亮那一项，和设置搜索、键盘菜单长按走同一条路（[SettingsSearchFocus] + 导航到 [SettingsSearchEntry.target]）。
 * 由 [SettingsScreen] 提供；不在设置导航图里的页面拿到 null，照旧显示成普通文字。
 */
internal val LocalSettingsJump = staticCompositionLocalOf<((SettingsSearchEntry) -> Unit)?> { null }

private val Bracketed = Regex("「([^「」]+)」")

/**
 * 「」里的 [label] 指的是哪一项。按「›」分段，从最后一段往前找第一段认得的：
 * 先认索引里的标题（设置项或整页），再认组名（如「删除键」「材质效果」，跳到那一页并亮整组）。
 * 「：」后面是选项值（「角标输入方式：长按」），只按前面的设置名找。同名的取路径里含上一段的那条。
 * 都不认得返回 null，文字照旧不可点。
 */
internal fun settingsJumpEntry(label: String, index: List<SettingsSearchEntry> = SettingsSearchIndex): SettingsSearchEntry? {
    val parts = label.split('›').map { it.substringBefore('：').trim() }.filter { it.isNotEmpty() }
    for (i in parts.indices.reversed()) {
        val name = parts[i]
        val parent = parts.getOrNull(i - 1)
        val titled = index.filter { it.title == name }
        (titled.firstOrNull { parent != null && parent in it.path } ?: titled.firstOrNull())?.let { return it }
        val grouped = index.filter { it.section == name }
        (grouped.firstOrNull { parent != null && parent in it.path } ?: grouped.firstOrNull())?.let {
            return it.copy(title = name, anchor = name, section = null)
        }
    }
    return null
}

/** 当前在 [pattern]（带 {参数} 的导航路由）、参数为 [arg] 的页面时，[target] 是不是就是这一页（只需高亮，不再压一层）。 */
internal fun settingsPageMatches(pattern: String?, arg: (String) -> String?, target: String): Boolean {
    if (pattern == null || pattern.substringBefore('?') != target.substringBefore('?')) return false
    val wanted = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val names = Regex("\\{(\\w+)\\}").findAll(pattern).map { it.groupValues[1] }.toList()
    return names.all { arg(it) == wanted[it] }
}

/**
 * 把 [text] 里的指路变成链接：[jumps] 为 null 时「」里认得的都算（锁定提示总是在说另一项设置），
 * 否则只认列出的那几个（说明里「」也用来写按键名、选项名，不能一概当成跳转）。
 */
@Composable
internal fun settingsJumpText(text: String, jumps: List<String>?, linkColor: Color): AnnotatedString {
    val english = com.kingzcheung.xime.ui.i18n.LocalUiTranslations.current
    if (english != null) return englishJumpText(text, jumps, linkColor, english)
    val jump = LocalSettingsJump.current ?: return AnnotatedString(text)
    return settingsJumpLinks(text, jumps, linkColor, jump)
}

/** 中文说明里「」认得的设置名变成链接。 */
private fun settingsJumpLinks(text: String, jumps: List<String>?, linkColor: Color,
                              jump: (SettingsSearchEntry) -> Unit): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        for (match in Bracketed.findAll(text)) {
            val label = match.groupValues[1]
            val entry = if (jumps == null || label in jumps) settingsJumpEntry(label) else null
            if (entry == null) continue
            append(text, at, match.range.first + 1)
            withLink(LinkAnnotation.Clickable("settings-jump:$label", style) { jump(entry) }) { append(label) }
            at = match.range.last
        }
        append(text, at, text.length)
    }
}

/** 带指路链接的一段说明；样式由调用处给，和原来那段 Text 一样。 */
@Composable
internal fun SettingsJumpText(text: String, jumps: List<String>, style: TextStyle, color: Color = Color.Unspecified,
                              modifier: Modifier = Modifier) {
    Text(settingsJumpText(text, jumps, MaterialTheme.colorScheme.primary), style = style, color = color, modifier = modifier)
}

/**
 * 英文界面的指路：整段先换成英文，再按中文「」里认得的设置名，把它们的英文名（英文表里「」写成 “ ”）变成链接。
 * 说明没有英文时照旧是中文，链接也照旧。
 */
@Composable
private fun englishJumpText(text: String, jumps: List<String>?, linkColor: Color,
                            english: com.kingzcheung.xime.ui.i18n.UiTranslations): AnnotatedString {
    val translated = english.translate(text)
    val jump = LocalSettingsJump.current ?: return AnnotatedString(translated)
    if (translated == text) return settingsJumpLinks(text, jumps, linkColor, jump)
    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val links = Bracketed.findAll(text).mapNotNull { match ->
        val label = match.groupValues[1]
        val entry = if (jumps == null || label in jumps) settingsJumpEntry(label) else null
        entry?.let { english.translate(label) to it }
    }.distinctBy { it.first }.toList()
    return buildAnnotatedString {
        append(translated)
        for ((name, entry) in links) {
            var from = translated.indexOf(name)
            while (from >= 0) {
                val end = from + name.length
                addLink(LinkAnnotation.Clickable("settings-jump:${entry.title}", style) { jump(entry) }, from, end)
                from = translated.indexOf(name, end)
            }
        }
    }
}
