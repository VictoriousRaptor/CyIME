package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Dialpad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.i18n.Text
import com.kingzcheung.xime.ui.keyboard.numberPadRows
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences

/**
 * 「数字位置」（11-22 群里有人问能不能改数字键盘的数字位置，作者答应加）：两张小键盘图，点哪张用哪种，
 * 图上就是键盘上的样子，不用看字也分得清。默认「1 2 3 在上」，和以前一样；键见 [SettingsPreferences.isNumberPadTop789]。
 */
@Composable
internal fun NumberPadOrderChoice() {
    val context = LocalContext.current
    val top789 = rememberKeyboardInputPreferences().numberPadTop789
    val colors = MaterialTheme.colorScheme
    Column(Modifier.settingsSearchAnchor("数字位置").fillMaxWidth().padding(16.dp).testTag("number-pad-order")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 图标、标题和说明同 SettingsItem，一列对齐。
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.TwoTone.Dialpad, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("数字位置", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("数字键盘最上面一排是 1 2 3 还是 7 8 9", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 和说明左对齐（16 + 图标 40 + 16 = 56dp 起），两张一样宽，平板上不拉得太宽。
        BoxWithConstraints(Modifier.padding(start = 56.dp).widthIn(max = SETTINGS_LEGEND_MAX_WIDTH).fillMaxWidth()) {
            val spacing = 10.dp
            val cardWidth = (maxWidth - spacing) / 2
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                for (option in listOf(false, true)) {
                    val label = if (option) "7 8 9 在上" else "1 2 3 在上"
                    PreviewChoiceCard(label, chosen = top789 == option, width = cardWidth, previewHeight = 72.dp,
                        containerColor = colors.surface, contentColor = colors.onSurface, accentColor = colors.primary,
                        tag = "number-pad-order:${if (option) "789" else "123"}",
                        onSelect = { SettingsPreferences.setNumberPadTop789(context, option) }) {
                        MiniatureKeyboard(numberPadRows(option), letterLayout = false, contentColor = colors.onSurface,
                            modifier = Modifier, fillKeys = !colors.surface.isNearBlack())
                    }
                }
            }
        }
    }
}
