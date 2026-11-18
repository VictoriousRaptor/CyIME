package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import com.kingzcheung.xime.ui.keyboard.InputGestureLock
import com.kingzcheung.xime.ui.keyboard.InputGestureLocks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 双拼辅助码（用户 11-15）：关 / 自然码辅助码 / 小鹤形码，三选一，默认关。所有双拼 26 键方案共用；
 * 改完在后台写方案补丁再重新部署（[SpellingPatches.apply]），和「双拼简拼」同一条路。
 * 打字时左下角的 Shift 键变成「辅」，和「Shift 键分词」互锁（[InputGestureLocks]）。
 */
@Composable
internal fun DoublePinyinAuxCodeSetting(keyboardPrefs: KeyboardInputPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember(context) { mutableStateOf(SpellingPatches.auxCode(context)) }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SpellingPatches.AUX_CODE_KEY) selected = SpellingPatches.auxCode(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // 「Shift 键分词」开着时打开辅助码会让 Shift 键分词失效：先锁住，写明是谁锁的；已经开着的照常能改、能关。
    val lock = InputGestureLocks.lockFor(keyboardPrefs, InputGestureLock.Option.DOUBLE_PINYIN_AUX_CODE, true)
        ?.takeIf { selected == null }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("double-pinyin-aux-code")) {
        Text("辅助码", style = MaterialTheme.typography.bodyLarge)
        Text("双拼 26 键打完拼音，点左下角的「辅」（打字时 Shift 键变成它；实体键盘按 `），再打第一个字的形码，" +
            "候选只留第一个字对得上的：小鹤双拼打 hc，点「辅」再打 nz，只剩“好”和“好”开头的词。不点「辅」时和关着一样。改完要几秒重新部署",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lock != null) SettingsLockNote(lock.message)
        Column(Modifier.selectableGroup()) {
            (listOf(null) + SpellingPatches.AuxCode.entries).forEach { code ->
                val enabled = lock == null || code == null
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("double-pinyin-aux-code:${code?.id ?: "off"}")
                    .selectable(selected == code, enabled = enabled, role = Role.RadioButton) {
                        if (selected == code) return@selectable
                        selected = code
                        SpellingPatches.setAuxCode(context, code)
                        scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                    }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == code, onClick = null, enabled = enabled)
                    Column(Modifier.weight(1f)) {
                        Text(code?.title ?: "关", style = MaterialTheme.typography.bodyMedium)
                        if (code != null) Text(code.example, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
