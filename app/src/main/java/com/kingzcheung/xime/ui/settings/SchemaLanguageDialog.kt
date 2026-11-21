package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SchemaLanguageChoice
import com.kingzcheung.xime.settings.SchemaMeta

/** 没写语言的导入方案：单选它的语言（和「界面语言」同一种单选框）。null 是「不指定」。 */
@Composable
internal fun SchemaLanguageDialog(schema: SchemaMeta, onChosen: (InputLanguage?) -> Unit, onDismiss: () -> Unit) {
    // 没选过时显示按方案名猜的语言（中文）；「不指定」是 null。
    val current = schema.language.takeUnless { it == InputLanguage.UNSPECIFIED }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${schema.name}」的语言") },
        text = {
            Column {
                Text((if (!schema.languageChosen && current != null) "这个方案没写语言，现在按方案名当作${current.displayName}。" else "这个方案没写语言。") +
                    "布局、语音和手写会按这个语言。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
                (SchemaLanguageChoice.choices + listOf<InputLanguage?>(null)).forEach { language ->
                    val choose = { onChosen(language) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = choose)
                        .testTag("schema-language:${schema.schemaId}:${language?.id ?: InputLanguage.UNSPECIFIED.id}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = language == current, onClick = choose)
                        Spacer(Modifier.width(8.dp))
                        Text(language?.displayName ?: "不指定(${InputLanguage.UNSPECIFIED.displayName})",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (language == current) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
