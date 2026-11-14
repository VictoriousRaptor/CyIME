package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.QwertyBottomKey
import com.kingzcheung.xime.ui.keyboard.QwertyBottomRowConfig
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette

/** Same cell/dialog/move interaction as the number-row editor, with fixed function identities. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QwertyBottomRowSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = rememberKeyboardInputPreferences()
    val config = prefs.qwertyBottomRow
    val cycle = prefs.symbolKeyCycle
    val visible = config.visible(cycle)
    var editing by rememberSaveable { mutableStateOf<QwertyBottomKey?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var reset by rememberSaveable { mutableStateOf(false) }
    fun save(next: QwertyBottomRowConfig) {
        // Write just this editor's key; a full preferences snapshot could overwrite another page's change.
        SettingsPreferences.getPrefsPublic(context).edit().putString(QwertyBottomRowConfig.PREF_KEY, next.encode()).apply()
    }
    LaunchedEffect(visible) { if (editing !in visible) editing = null }
    Scaffold(topBar = {
        TopAppBar(title = { Text("26 键底栏") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多选项") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("恢复默认") }, onClick = { menu = false; reset = true })
                }
            }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().settingsVerticalScroll().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("点按格子调整顺序；逗号、句号可以移除或加回。修改立即生效。",
                style = MaterialTheme.typography.bodyMedium)
            val palette = LocalKeyboardPalette.current
            val normalBg = palette?.key ?: MaterialTheme.colorScheme.surface
            val normalText = palette?.text ?: MaterialTheme.colorScheme.onSurface
            val specialBg = palette?.function ?: MaterialTheme.colorScheme.secondaryContainer
            val specialText = palette?.functionText ?: MaterialTheme.colorScheme.onSecondaryContainer
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(palette?.background ?: MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                config.slots(cycle, split = false).forEach { slot ->
                    val key = requireNotNull(slot.key)
                    val background = if (slot.special) specialBg else normalBg
                    val foreground = if (slot.special) specialText else normalText
                    Box(Modifier.weight(slot.weight).height(48.dp).clip(RoundedCornerShape(7.dp))
                        .background(background)
                        .then(if (key == editing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp)) else Modifier)
                        .clickable { editing = key }
                        .testTag("bottom-editor-key:${key.id}"), contentAlignment = Alignment.Center) {
                        when (key) {
                            QwertyBottomKey.LANGUAGE -> Icon(Icons.Default.Language, "语言", tint = foreground, modifier = Modifier.size(22.dp))
                            QwertyBottomKey.ENTER -> Text("↵", color = foreground, fontSize = 18.sp)
                            else -> Text(key.label(cycle), color = foreground, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
            }
            val missing = listOf(QwertyBottomKey.COMMA, QwertyBottomKey.PERIOD).filter { it !in config.keys }
            OutlinedButton(onClick = { adding = true }, enabled = missing.isNotEmpty(), modifier = Modifier.testTag("bottom-editor-add")) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("添加标点")
            }
            Text("空格必须保留，可将其他键移到空格任意一侧；回车固定最右。左右两端各两个键使用特殊颜色。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("只保留一个标点时，副符号是另一种标点；两种都显示时沿用全角、半角规则。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (cycle) Text("符号键循环切换已开启：?123 使用符号键的位置，123 暂时隐藏，关闭循环后回到原位置。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    editing?.let { key ->
        val at = visible.indexOf(key)
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(when (key) {
            QwertyBottomKey.SYMBOLS -> if (cycle) "符号／数字切换键" else "符号键"
            QwertyBottomKey.NUMBERS -> "数字键"
            QwertyBottomKey.COMMA -> "逗号键"
            QwertyBottomKey.PERIOD -> "句号键"
            else -> key.label + "键"
        }) }, text = {
            if (key.movable) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = at > 0, onClick = { save(config.move(key, -1, cycle)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "左移")
                }
                IconButton(enabled = at >= 0 && at < visible.lastIndex - 1, onClick = { save(config.move(key, 1, cycle)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "右移")
                }
                if (key.optional) IconButton(onClick = { save(config.remove(key)); editing = null }) {
                    Icon(Icons.Default.Delete, "移除")
                }
            } else Text(if (key == QwertyBottomKey.SPACE) "空格必须保留。移动其他按键可以调整空格两侧的排列。" else "回车固定在最右侧。")
        }, confirmButton = { TextButton(onClick = { editing = null }) { Text("完成") } })
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加标点") }, text = {
        Column {
            listOf(QwertyBottomKey.COMMA, QwertyBottomKey.PERIOD).filter { it !in config.keys }.forEach { key ->
                TextButton(onClick = { save(config.add(key)); adding = false },
                    modifier = Modifier.fillMaxWidth().testTag("bottom-editor-add:${key.id}")) {
                    Text(if (key == QwertyBottomKey.COMMA) "， 逗号键" else "。 句号键")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("恢复默认底栏？") },
        text = { Text("恢复默认顺序并显示逗号、句号。其他布局和符号键循环切换设置保持原值。") },
        confirmButton = { TextButton(onClick = { save(QwertyBottomRowConfig.Default); editing = null; reset = false }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}
