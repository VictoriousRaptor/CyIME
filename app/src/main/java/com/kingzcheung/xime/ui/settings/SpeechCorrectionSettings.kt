package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.service.VoiceCorrections
import com.kingzcheung.xime.settings.SettingsPreferences

/** 语音纠错：别人提的需求，默认关(VoiceCorrections)。打开后下面出词表，每行一条。 */
@Composable
internal fun SpeechCorrectionSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SettingsPreferences.isSttCorrections(context)) }
    var rules by remember { mutableStateOf(SettingsPreferences.getSttCorrectionsText(context)) }
    Column(Modifier.fillMaxWidth().settingsSearchAnchor("语音纠错")) {
        Row(Modifier.fillMaxWidth().testTag("speech-corrections")
            .toggleable(value = enabled, role = Role.Switch,
                onValueChange = { enabled = it; SettingsPreferences.setSttCorrections(context, it) })
            .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("语音纠错", style = MaterialTheme.typography.bodyLarge)
                Text("上屏前把认错的词换成对的，每行一条：错词 空格 正词。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = null, colors = settingsSwitchColors())
        }
        if (enabled) {
            val count = remember(rules) { VoiceCorrections.of(rules).size }
            OutlinedTextField(
                value = rules,
                onValueChange = { rules = it; SettingsPreferences.setSttCorrectionsText(context, it) },
                label = { Text("纠错词表") },
                placeholder = { Text("南崩 难绷\n塞伯朋克 赛博朋克") },
                supportingText = { Text("$count 条") },
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .testTag("speech-corrections-text"),
            )
        }
    }
}
