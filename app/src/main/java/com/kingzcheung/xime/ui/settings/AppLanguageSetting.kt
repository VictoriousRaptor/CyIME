package com.kingzcheung.xime.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.settings.AppLanguage
import com.kingzcheung.xime.settings.AppLanguages

/**
 * 设置首页「界面语言」一行：点开是单选框（不另开一页，首页不放只有一个设置的子页）。
 * 语言名永远用各自的文字写（简体中文、繁體中文、English、日本語），看不懂当前界面的人也找得到自己的语言；
 * 标题后面总带英文 Language，同一个原因。Android 12 及更早点这一行打开系统语言设置。
 */
@Composable
internal fun AppLanguageItem() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppLanguages.current(context)) }
    var picking by remember { mutableStateOf(false) }
    val supported = AppLanguages.supported
    SettingsItem(
        icon = Icons.TwoTone.Translate,
        title = stringResource(R.string.settings_app_language),
        subtitle = stringResource(if (supported) R.string.settings_app_language_summary
            else R.string.settings_app_language_summary_old_android),
        searchKey = "界面语言",
        status = current.label(),
        onClick = {
            if (supported) picking = true
            else runCatching { context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) }
        },
    )
    if (picking) AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text(stringResource(R.string.settings_app_language)) },
        text = {
            Column {
                AppLanguage.entries.forEach { language ->
                    val choose = {
                        picking = false
                        if (language != current) {
                            current = language
                            // 系统随即换语言并重建本页，新文字马上出来；键盘下次弹出时也换过来。
                            AppLanguages.set(context, language)
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = choose)
                        .testTag("app-language:${language.name}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = language == current, onClick = choose)
                        Spacer(Modifier.width(8.dp))
                        Text(language.label(), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (language == current) FontWeight.Medium else FontWeight.Normal)
                    }
                }
                Text(stringResource(R.string.settings_app_language_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_close)) } },
    )
}

/** 「跟随系统」按当前界面语言写，其余四种用各自的文字。 */
@Composable
private fun AppLanguage.label(): String =
    if (this == AppLanguage.SYSTEM) stringResource(R.string.settings_app_language_system) else nativeName
