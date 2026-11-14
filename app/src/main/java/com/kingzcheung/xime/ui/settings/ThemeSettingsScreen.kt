package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Opacity
import androidx.compose.material.icons.twotone.BlurOn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.viewmodel.ThemeSettingsViewModel
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.FrostedGlassProfiles
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.VisualStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsContent(
    onBack: () -> Unit,
    onThemeChanged: () -> Unit = {}
) {
    var showIconSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val subPage = if (showIconSettings) "icons" else null
    SettingsSubPages(subPage, onBack = { showIconSettings = false }) { shown ->
        ThemeSettingsPage(shown, onBack, onThemeChanged,
            openIcons = { showIconSettings = true }, closeSubPage = { showIconSettings = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSettingsPage(
    subPage: String?,
    onBack: () -> Unit,
    onThemeChanged: () -> Unit,
    openIcons: () -> Unit,
    closeSubPage: () -> Unit,
) {
    val viewModel: ThemeSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val glass = FrostedGlassProfiles(
        rememberKeyboardInputPreferences(false).frostedGlass,
        rememberKeyboardInputPreferences(true).frostedGlass,
    )
    if (subPage == "icons") {
        IconSettingsContent(onBack = closeSubPage, onChanged = onThemeChanged)
        return
    }
    
    var previewTheme by remember { mutableStateOf<KeyboardColorScheme?>(null) }
    var showGlassSettings by remember { mutableStateOf(false) }
    // 背景图片和配色分开（11-09 用户：「这是两个系统」）：开着图片时，配色列表里选中的是图片下面那套配色，
    // 换配色只换底，图片留着；「我的图片」不再作为一张配色卡出现，改由「背景图片」里的开关打开、关闭。
    val imageOn = uiState.colorTheme == com.kingzcheung.xime.ui.theme.CustomImageTheme.ID
    val schemeId = if (imageOn) com.kingzcheung.xime.ui.theme.CustomImageTheme.baseFor(context, uiState.colorTheme)
        else com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(uiState.colorTheme).id
    val schemes = uiState.colorThemes.filter { it.id != com.kingzcheung.xime.ui.theme.CustomImageTheme.ID }
    fun applyScheme(theme: KeyboardColorScheme) {
        if (imageOn && com.kingzcheung.xime.ui.theme.CustomImageTheme.canUnderlay(theme)) {
            com.kingzcheung.xime.ui.theme.CustomImageTheme.setBase(context, theme.id)
            com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
            viewModel.refreshThemes()
            viewModel.setColorTheme(com.kingzcheung.xime.ui.theme.CustomImageTheme.ID)
        } else viewModel.setColorTheme(theme.id)
    }
    KeyboardSettingsScaffold("主题与外观", onBack, preview = previewTheme == null && !showGlassSettings) { modifier ->
        val listState = rememberSettingsSearchListState()
        LazyColumn(
            state = listState,
            modifier = modifier.settingsGutterScroll(listState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding()
        ) {
            item {
                Text(
                    text = "显示模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            item {
                val currentTheme = uiState.colorThemes.firstOrNull { it.id == uiState.colorTheme }
                    ?: com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(uiState.colorTheme)
                Row(
                    modifier = Modifier.settingsSearchAnchor("显示模式", bleed = 6.dp, bleedVertical = 6.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyboardThemeCard(
                        theme = currentTheme,
                        glass = glass,
                        previewAspectRatio = 1.3f,
                        isSelected = uiState.darkMode == 2,
                        onClick = {
                            changeAppearance(context, onThemeChanged) { viewModel.setDarkMode(2) }
                        },
                        modifier = Modifier.weight(1f),
                        title = "跟随系统"
                    )
                    KeyboardThemeCard(
                        theme = currentTheme,
                        glass = glass,
                        previewAspectRatio = 1.3f,
                        title = "浅色",
                        isSelected = uiState.darkMode == 0,
                        previewDark = false,
                        onClick = {
                            changeAppearance(context, onThemeChanged) { viewModel.setDarkMode(0) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    KeyboardThemeCard(
                        theme = currentTheme,
                        glass = glass,
                        previewAspectRatio = 1.3f,
                        title = "深色",
                        isSelected = uiState.darkMode == 1,
                        previewDark = true,
                        onClick = {
                            changeAppearance(context, onThemeChanged) { viewModel.setDarkMode(1) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            item {
                SettingsSection(title = "材质效果") {
                    SettingsToggleItem(icon = Icons.TwoTone.BlurOn, title = "玻璃效果",
                        subtitle = "沿用当前配色，切换主题时保持", checked = glass.dark.enabled,
                        onCheckedChange = { enabled ->
                            changeAppearance(context, onThemeChanged) {
                                FrostedGlassPreferences.saveProfiles(context, glass.withEnabled(enabled))
                                // 液态玻璃建在玻璃上：关玻璃时一起关。
                                if (!enabled) SettingsPreferences.setGlassLiquid(context, false)
                            }
                        }, costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.GLASS)
                    // 液态玻璃不用先开玻璃：打开它就把玻璃一起打开。
                    SettingsToggleItem(icon = Icons.TwoTone.AutoAwesome, title = "液态玻璃",
                        subtitle = "按键加细亮边，按住变亮；图片背景会在边缘折射",
                        checked = glass.dark.enabled && com.kingzcheung.xime.ui.theme.LiquidGlass.enabled(context),
                        onCheckedChange = { enabled ->
                            changeAppearance(context, onThemeChanged) {
                                if (enabled && !glass.dark.enabled) FrostedGlassPreferences.saveProfiles(context, glass.withEnabled(true))
                                SettingsPreferences.setGlassLiquid(context, enabled)
                            }
                        }, costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.LIQUID_GLASS)
                    if (glass.dark.enabled) {
                        SettingsItem(icon = Icons.TwoTone.Opacity, title = "透明度与模糊",
                            subtitle = "浅色、深色分开设置", showArrow = true,
                            onClick = { showGlassSettings = true })
                        // 「柔化顶部边缘」11-01 并进本页下面的「键盘顶部样式」（圆角／直角／柔化三选一）。
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "配色方案",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.settingsSearchAnchor("配色方案", bleed = 6.dp, bleedVertical = 4.dp).fillMaxWidth().padding(bottom = 6.dp)
                )
            }
            
            item {
                val shownTheme = schemes.firstOrNull { it.id == schemeId }
                    ?: com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(schemeId)
                Text(
                    // 莫奈取色按每台设备的壁纸取色，不能说各设备相同。
                    text = if (shownTheme.isDynamic) "${shownTheme.name}跟随系统壁纸取色。点击可预览。"
                        else "${shownTheme.name}在各设备使用相同配色。点击可预览。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            
            schemes.chunked(3).forEach { rowThemes ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowThemes.forEach { theme ->
                            KeyboardThemeCard(
                                theme = theme,
                                glass = glass,
                                isSelected = schemeId == theme.id,
                                onClick = {
                                    previewTheme = theme
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowThemes.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(1.dp))
                }
            }
            
            item {
                // 动态背景只对图片背景起作用：当前主题有背景图（我的图片、大漠流金等）时才放出来。
                val imageTheme = (uiState.colorThemes.firstOrNull { it.id == uiState.colorTheme }
                    ?: com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(uiState.colorTheme))
                    .keyboardBackground?.type == "image"
                CustomImageThemeSection(currentThemeId = uiState.colorTheme, imageTheme = imageTheme, onUseImage = { on ->
                    changeAppearance(context, onThemeChanged) {
                        if (on) {
                            // 打开图片：现在的配色能垫在图片下面就用它做底，否则沿用上次的底。
                            schemes.firstOrNull { it.id == schemeId }
                                ?.takeIf(com.kingzcheung.xime.ui.theme.CustomImageTheme::canUnderlay)
                                ?.let { com.kingzcheung.xime.ui.theme.CustomImageTheme.setBase(context, it.id) }
                            com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
                            viewModel.refreshThemes()
                            viewModel.setColorTheme(com.kingzcheung.xime.ui.theme.CustomImageTheme.ID)
                        } else viewModel.setColorTheme(schemeId)
                    }
                }, onSaved = {
                    viewModel.refreshThemes()
                    changeAppearance(context, onThemeChanged) {
                        viewModel.setColorTheme(com.kingzcheung.xime.ui.theme.CustomImageTheme.ID)
                    }
                }, onDeleted = { baseThemeId ->
                    viewModel.refreshThemes()
                    // 正在用「我的图片」时换回选图时沿用的主题。
                    if (uiState.colorTheme == com.kingzcheung.xime.ui.theme.CustomImageTheme.ID) {
                        changeAppearance(context, onThemeChanged) { viewModel.setColorTheme(baseThemeId) }
                    } else onThemeChanged()
                }, onImageChanged = {
                    viewModel.refreshThemes()
                    onThemeChanged()
                })
            }

            // 视觉样式只有一组卡片，直接放在本页；按键显示（字号、角标、气泡、底部）也属于外观。
            item {
                VisualStylePicker(VisualStyles.current) { style ->
                    changeAppearance(context, onThemeChanged) { SettingsPreferences.setVisualStyle(context, style) }
                }
            }
            item {
                KeyShapePicker(com.kingzcheung.xime.ui.theme.KeyShapes.current) { shape ->
                    changeAppearance(context, onThemeChanged) { SettingsPreferences.setKeyShape(context, shape) }
                }
            }
            item { KeyboardAppearanceSettings() }
            item {
                SettingsSection(title = "桌面图标") {
                    SettingsItem(icon = Icons.TwoTone.Apps, title = "图标设置",
                        subtitle = if (com.kingzcheung.xime.ui.theme.IconAppearance.linked) "与视觉样式同步" else "独立选择",
                        onClick = openIcons, showArrow = true)
                }
            }
        }

        previewTheme?.let { theme ->
            ThemePreviewSheet(
                theme = theme,
                onApply = {
                    changeAppearance(context, onThemeChanged) {
                        applyScheme(theme)
                        previewTheme = null
                    }
                },
                onDismiss = { previewTheme = null },
            )
        }
        if (showGlassSettings) {
            FrostedGlassSettingsSheet(
                theme = uiState.colorThemes.firstOrNull { it.id == uiState.colorTheme }
                    ?: com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(uiState.colorTheme),
                onApply = { draftGlass ->
                    changeAppearance(context, onThemeChanged) {
                        FrostedGlassPreferences.saveProfiles(context, draftGlass)
                        showGlassSettings = false
                    }
                },
                onDismiss = { showGlassSettings = false },
            )
        }
    }
}

/**
 * 「我的图片」上压的遮罩，一行一条：左边浅色／深色，中间滑条，右边百分比。越高图片越淡、按键文字越清楚，0% 是原图。
 * 浅色、深色键盘底色不同，同一张图要淡的程度不同，所以各一条。滑条按 5% 一档吸附，不画刻度点（原来 18 个点挤成一排）。
 * 拖动时只改数字，松手才存并刷新键盘；默认 72%，与原来一样。
 */
@Composable
private fun CustomImageVeilSlider(dark: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val title = if (dark) "深色遮罩" else "浅色遮罩"
    var veil by remember { mutableStateOf(com.kingzcheung.xime.ui.theme.CustomImageTheme.veil(context, dark)) }
    Row(Modifier.settingsSearchAnchor(title).fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(if (dark) "深色" else "浅色", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(40.dp))
        androidx.compose.material3.Slider(
            value = veil,
            onValueChange = { veil = kotlin.math.round(it * 20f) / 20f },
            onValueChangeFinished = {
                changeAppearance(context, onChanged) {
                    com.kingzcheung.xime.ui.theme.CustomImageTheme.setVeil(context, dark, veil)
                    com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
                }
            },
            valueRange = 0f..com.kingzcheung.xime.ui.theme.CustomImageTheme.MAX_VEIL,
            modifier = Modifier.weight(1f).semantics { contentDescription = title }
                .testTag(if (dark) "custom-image-veil-dark" else "custom-image-veil-light"),
        )
        Text("${kotlin.math.round(veil * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(44.dp))
    }
}

/**
 * 主题与外观 › 背景图片：一张卡管背景图的全部事（用户 11-13 要求按真实用途重做，设计理由见补丁的 DESIGN.md）。
 *
 * 1. 没有图片：「用自己的图片 >」，选一张图，进调整页，确认后换成「我的图片」。
 * 2. 有图片：同一行变成总开关（原来另有一行「使用背景图片」），关掉时图片留着，键盘用配色方案的底色。
 *    下面一排小图：第一张是主图（点它调整位置或换一张），后面是轮流显示的图，每张 × 删，＋ 加图。
 *    图片开着时再给浅色、深色两条遮罩。
 * 3. 当前主题有背景图（我的图片、大漠流金等）时接着放「动态背景」：它只对图片背景起作用，开了才轮流换图。
 */
@Composable
private fun CustomImageThemeSection(currentThemeId: String, imageTheme: Boolean, onUseImage: (Boolean) -> Unit,
    onSaved: () -> Unit, onDeleted: (String) -> Unit, onImageChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasImage by remember { mutableStateOf(com.kingzcheung.xime.ui.theme.CustomImageTheme.hasImage(context)) }
    // 调整页正在调的图片（刚选的新图或已在用的那张）与要沿用的主题。
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val motion = com.kingzcheung.xime.ui.theme.rememberBackgroundMotionSettings()
    val slides = remember(motion.slides) { com.kingzcheung.xime.ui.theme.CustomImageTheme.slides(context, motion.slides) }
    val imageOn = currentThemeId == com.kingzcheung.xime.ui.theme.CustomImageTheme.ID
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val base = com.kingzcheung.xime.ui.theme.CustomImageTheme.baseFor(context, currentThemeId)
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { com.kingzcheung.xime.ui.theme.CustomImageTheme.importImage(context, uri) }
            }
            imported.onSuccess { editing = it to base }.onFailure {
                com.kingzcheung.xime.util.FileLogger.e("Appearance", "Custom background failed", it)
                android.widget.Toast.makeText(context, "这张图片读不出来，换一张试试", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val slidePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(com.kingzcheung.xime.settings.BackgroundMotionPreferences.MAX_SLIDES)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) { com.kingzcheung.xime.ui.theme.CustomImageTheme.addSlides(context, uris) }
            if (added < uris.size) android.widget.Toast.makeText(context,
                if (added == 0 && slides.size >= com.kingzcheung.xime.settings.BackgroundMotionPreferences.MAX_SLIDES)
                    "最多 ${com.kingzcheung.xime.settings.BackgroundMotionPreferences.MAX_SLIDES + 1} 张"
                else "有 ${uris.size - added} 张没有加上", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun pickOne() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    SettingsSection(title = "背景图片") {
        // 主图换了（调整页确认后 editing 清空）、顶上来或删了，这里跟着重组，图片排的第一张随之换。
        val main = if (hasImage) com.kingzcheung.xime.ui.theme.CustomImageTheme.imageSource(context) else null
        if (main == null) {
            SettingsItem(icon = Icons.TwoTone.Image, title = "用自己的图片",
                subtitle = "从相册选一张图片做键盘背景，按键和文字的颜色沿用当前主题",
                onClick = { pickOne() }, showArrow = true)
        } else {
            SettingsToggleItem(icon = Icons.TwoTone.Image, title = "用自己的图片",
                subtitle = if (imageOn) "开着：换配色只换按键和文字的颜色，图片留着（iOS 渐变除外）"
                    else "关着：键盘用配色方案的底色，图片留着",
                checked = imageOn, onCheckedChange = onUseImage)
            BackgroundImageStrip(
                main = main, slides = slides, imageOn = imageOn, motionOn = motion.enabled,
                onAdjust = {
                    editing = main to com.kingzcheung.xime.ui.theme.CustomImageTheme.baseFor(context,
                        com.kingzcheung.xime.ui.theme.CustomImageTheme.ID)
                },
                onReplace = { pickOne() },
                onRemoveMain = {
                    // 只剩这一张：先确认再整个删；后面还有图：下一张顶上来，不用确认（删的只是这一张）。
                    if (slides.isEmpty()) confirmDelete = true
                    else scope.launch {
                        val promoted = withContext(Dispatchers.IO) {
                            runCatching { com.kingzcheung.xime.ui.theme.CustomImageTheme.promoteNextSlide(context) }
                        }
                        promoted.onSuccess { moved ->
                            // 刚好别处把轮播图删光了：照只剩一张处理。
                            if (!moved) { confirmDelete = true; return@onSuccess }
                            com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
                            onImageChanged()
                        }.onFailure {
                            com.kingzcheung.xime.util.FileLogger.e("Appearance", "Custom background promote failed", it)
                            android.widget.Toast.makeText(context, "没有删除成功，请重试", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onAdd = { slidePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveSlide = { src -> scope.launch(Dispatchers.IO) { com.kingzcheung.xime.ui.theme.CustomImageTheme.removeSlide(context, src) } },
            )
            // 遮罩只管「我的图片」：图片关着时调它看不到任何变化，不放出来。
            if (imageOn) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                    Text("遮罩", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Text("越高图片越淡、按键上的字越清楚；浅色、深色键盘各一条",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    CustomImageVeilSlider(dark = false, onChanged = onImageChanged)
                    CustomImageVeilSlider(dark = true, onChanged = onImageChanged)
                }
            }
        }
        if (imageTheme) BackgroundMotionSettingsItems()
    }
    editing?.let { (src, base) ->
        val existing = src == com.kingzcheung.xime.ui.theme.CustomImageTheme.imageSource(context)
        CustomImageEditorSheet(
            src = src,
            baseThemeId = base,
            initial = if (existing) com.kingzcheung.xime.ui.theme.CustomImageTheme.placement(context)
                else com.kingzcheung.xime.ui.theme.ThemeImagePlacement.DEFAULT,
            onConfirm = { placement ->
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { com.kingzcheung.xime.ui.theme.CustomImageTheme.commit(context, src, base, placement) }
                    }
                    saved.onSuccess {
                        com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
                        hasImage = true
                        editing = null
                        onSaved()
                    }.onFailure {
                        com.kingzcheung.xime.util.FileLogger.e("Appearance", "Custom background save failed", it)
                        android.widget.Toast.makeText(context, "图片没有保存成功，请重试", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = {
                editing = null
                scope.launch(Dispatchers.IO) { com.kingzcheung.xime.ui.theme.CustomImageTheme.discard(context, src) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除图片") },
            text = { Text("这是最后一张，删掉后没有背景图片；正在用它时，键盘留下图片下面那套配色。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { com.kingzcheung.xime.ui.theme.CustomImageTheme.delete(context) }
                        }
                        deleted.onSuccess { base ->
                            com.kingzcheung.xime.ui.theme.KeyboardThemes.reload(context)
                            hasImage = false
                            onDeleted(base)
                        }.onFailure {
                            com.kingzcheung.xime.util.FileLogger.e("Appearance", "Custom background delete failed", it)
                            android.widget.Toast.makeText(context, "没有删除成功，请重试", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
