package com.kingzcheung.xime.ui.menubar

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.keyboard.keyboardPanelBackground
import com.kingzcheung.xime.ui.keyboard.keyboardPhysicalMetrics
import com.kingzcheung.xime.ui.keyboard.CALIBRATED_QWERTY_PITCH_MM
import com.kingzcheung.xime.ui.settings.LayoutPreviewChoices
import com.kingzcheung.xime.ui.settings.ProfileChoiceRow

/** Language stays unchanged; layout/encoding selectors expose installed combinations. */
@Composable
fun SchemaListView(
    schemas: List<SchemaInfo>, currentSchemaId: String,
    backgroundColor: Color, accentColor: Color, keyTextColor: Color, keyBgColor: Color,
    onSelectSchema: (String) -> Unit,
    modifier: Modifier = Modifier, availableSchemas: List<SchemaInfo> = schemas,
    /** 点了布局（不是方案）：手写开着时要回到所选的键盘布局。 */
    onSelectLayout: (String) -> Unit = onSelectSchema,
    /**
     * 键盘里为 true：读装着的方案，方案一行和「语言与方案」列同一份（[SchemaPicking]）——关着的五笔、仓颉、
     * 注音等也列出，点了启用（用户 11-22：设置里有，键盘面板里没有）。
     */
    listInstalled: Boolean = false,
    /** 点了关着的方案：面板先收起，启用和部署在后台做完再换上。 */
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current
    val all = InputModes.available((schemas + availableSchemas).distinctBy { it.schemaId })
        .filter { it.profile.mode == InputMode.KEYBOARD }
    val current = all.firstOrNull { it.schemaId == currentSchemaId }
    val entries = all.filter { current != null && it.profile.language == current.profile.language }
    var layoutId by remember(currentSchemaId, current?.profile) { mutableStateOf(current?.profile?.layout?.id) }
    // 方案一行和设置页同一个来源、同一个函数：每种方案一张卡片，当前布局没有的方案（注音、笔画）用它自己的布局。
    val switchable = (schemas + availableSchemas).map { it.schemaId }.toSet()
    val installed by produceState<Pair<List<SchemaInfo>, List<String>>?>(null, listInstalled) {
        if (listInstalled) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SchemaManager.discoverSchemas(context).map { it.toSchemaInfo() } to SchemaManager.getEnabledSchemas(context)
        }
    }
    val pool = InputModes.ordered(context, installed?.let { (found, enabled) ->
        SchemaPicking.offered((all + found).distinctBy { it.schemaId }, enabled + switchable)
    } ?: all)
    val schemes = current?.let { SchemaPicking.schemeRow(pool, it) }.orEmpty()
    BoxWithConstraints(modifier.fillMaxWidth().keyboardPanelBackground(backgroundColor).testTag("input-profile-panel")) {
    val density = LocalDensity.current
    val display = LocalView.current.display
    val configuration = LocalConfiguration.current
    val targetCellDp = remember(context, display, configuration, density.density) {
        keyboardPhysicalMetrics(context, display, density.density)?.dpFromMillimeters(CALIBRATED_QWERTY_PITCH_MM)
    }
    // 分体键盘与正常 26 键错位一致；预览随「三行错位」设置。
    val qwertyStagger = com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences.current.let {
        com.kingzcheung.xime.ui.keyboard.resolvedQwertyStagger(it.qwertyStagger, it.qwertyStaggerCustom,
            ((maxWidth.value - 16f) / 10f).coerceAtLeast(0f), targetCellDp)
    }
    val typography = MaterialTheme.typography
    // Both rows share the actual content height; no independent preview/text caps
    // that leave the scheme row tiny and most of a tall panel unused.
    val (layoutHeight, schemeHeight) = with(density) {
        val schemeHeight = maxOf(48.dp,
            typography.labelSmall.lineHeight.toDp() + typography.bodyMedium.lineHeight.toDp() + 6.dp)
        val layoutLabelHeight = typography.labelLarge.lineHeight.toDp() + 4.dp
        val minimum = maxOf(schemeHeight, layoutLabelHeight + 24.dp)
        val available = if (constraints.hasBoundedHeight) maxHeight - 17.dp else minimum * 2.5f
        // Keep 60:40 except when a short panel needs the scheme's minimum touch/text height.
        val bottom = (available * .4f).coerceAtLeast(schemeHeight)
        (available - bottom).coerceAtLeast(minimum) to bottom
    }
    Column(Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp)) {
        if (current == null) {
            Text("正在读取当前输入方式…", color = keyTextColor)
        } else {
            val menu = LayoutMenuPreferences.observe(current.profile.language)
            val layouts = menu.shown(menu.ordered(InputProfileSelection.layoutRow(entries, all)))
            val switches = layouts.associate { it.id to InputProfileSelection.switchLayout(entries, current, it.id, all) }
            LayoutPreviewChoices(layouts, current.profile.language, layoutId,
                "profile-layout-list", itemTagPrefix = "panel-layout:",
                unavailableIds = switches.filterValues { it?.keepsScheme == false }.keys,
                unavailableNotes = InputProfileSelection.unavailableNotes(current, switches),
                rowHeight = layoutHeight,
                qwertyPreviewStagger = qwertyStagger,
                onManage = {
                    context.startActivity(Intent(context, com.kingzcheung.xime.CustomLayoutActivity::class.java)
                        .putExtra(com.kingzcheung.xime.CustomLayoutActivity.EXTRA_LANGUAGE, current.profile.language.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                containerColor = keyBgColor, contentColor = keyTextColor, accentColor = accentColor) { id ->
                layoutId = id
                // This is an explicit combination choice, not a layout rebind: one tap switches,
                // keeping the current scheme when the layout offers it (拼音 26 → 拼音 14).
                // 布局没有当前方案（小鹤双拼 → 九键）时照样切换，但要说出换成了什么方案。
                switches[id]?.let { switch ->
                    if (!switch.keepsScheme) layouts.firstOrNull { it.id == id }?.let { layout ->
                        android.widget.Toast.makeText(context, InputProfileSelection.switchHint(layout, current, switch.entry),
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    LetterLayouts.selectFor(context, switch.entry, id)
                    onSelectLayout(switch.entry.schemaId)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 2.dp), color = keyTextColor.copy(alpha = .15f))
            ProfileChoiceRow("方案", "profile-scheme-list",
                schemes.map { it.schemaId to it.schemeLabel },
                currentSchemaId, itemTagPrefix = "panel-scheme:",
                previews = schemes.associate { it.schemaId to InputProfiles.schemePreview(it.profile) },
                rowHeight = schemeHeight,
                containerColor = keyBgColor, contentColor = keyTextColor, accentColor = accentColor,
                onManage = {
                    // 设置已开着（从键盘齿轮进的、或正在设置页里打字）时，只带 NEW_TASK 的同一个 Intent
                    // 只会把旧页面拉到前面、收不到 onNewIntent，看起来就是点了没反应；和齿轮、长按菜单走同一个入口。
                    context.startActivity(com.kingzcheung.xime.service.settingsActivityIntent(context,
                        com.kingzcheung.xime.ui.settings.SettingsRoutes.Schema))
                }) { id ->
                if (id in switchable) onSelectSchema(id)
                else schemes.firstOrNull { it.schemaId == id }?.let { entry ->
                    // 关着的方案：和设置页一样启用并部署，好了键盘自动换上；面板先收起，不用等。
                    android.widget.Toast.makeText(context, "正在启用${entry.schemeLabel}，几秒后自动换上",
                        android.widget.Toast.LENGTH_SHORT).show()
                    SchemaPicking.enableAndPick(context, id, entry.profile.language) { ok ->
                        if (!ok) android.widget.Toast.makeText(context, "方案部署失败，到「已安装的方案」点「重新部署(修复)」可看原因",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    onClose()
                }
            }
        }
    }
    }
}
