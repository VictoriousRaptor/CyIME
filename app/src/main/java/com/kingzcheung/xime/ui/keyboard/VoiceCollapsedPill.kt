package com.kingzcheung.xime.ui.keyboard

import android.graphics.Rect
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 语音输入时收起键盘后的底部悬浮条（仿 Gboard）：语言、展开键盘、状态、麦克风、退格。
 * 外观沿用实体键盘浮条；宿主透明全屏，只有这条胶囊进入可触摸区域。
 */
@Composable
internal fun VoiceCollapsedPill(
    listening: Boolean,
    /** 这段听写是开车连续语音自动续上的：写明，点麦克风就停。 */
    autoResumed: Boolean = false,
    /** 实时麦克风峰值（0~1）；只在绘制阶段读取，音量变化不触发重组。 */
    amplitude: () -> Float,
    languageLabel: String,
    languageActions: KeyboardInputActions,
    onSwitchLanguage: () -> Unit,
    onShowKeyboard: () -> Unit,
    onBackspace: () -> Unit,
    /** 按下/抬起删除键：与键盘删除键同一套按键反馈（音效、震动）。 */
    onBackspacePress: () -> Unit = {},
    onBackspaceRelease: () -> Unit = {},
    /** 按住退格连删的间隔，跟键盘删除键同一个「长按删除速度」。 */
    backspaceRepeatMs: Long = com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.DEFAULT.intervalMs,
    onMic: () -> Unit,
    backgroundColor: Color,
    contentColor: Color,
    selectedTextColor: Color,
    accentColor: Color,
    frostedGlass: com.kingzcheung.xime.settings.FrostedGlassConfig,
    surface: @Composable () -> Modifier,
    onBoundsChanged: (Rect?) -> Unit,
    /** 与实体键盘浮条共用的比例位置：拖动、吸边、记住位置都同一套。 */
    position: HardwareToolbarPosition = HardwareToolbarPosition(),
    onPositionChange: (HardwareToolbarPosition) -> Unit = {},
    dockAtEdge: Boolean = true,
    /** 拖动和松手归位期间为真：宿主先不改 App 留空，停稳再改一次。 */
    onMovingChange: (Boolean) -> Unit = {},
) {
    val themeKey = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.key ?: backgroundColor
    val shadowRadius = with(LocalDensity.current) { 1.dp.toPx() }
    val textSurface = remember(frostedGlass, backgroundColor, themeKey, shadowRadius, contentColor, selectedTextColor, accentColor) {
        if (frostedGlass.enabled) resolveFrostedTextSurface(backgroundColor, contentColor, selectedTextColor, accentColor, frostedGlass, themeKey, shadowRadius) else null
    }
    val color = textSurface?.text ?: contentColor
    val currentBoundsCallback by rememberUpdatedState(onBoundsChanged)
    DisposableEffect(Unit) { onDispose { currentBoundsCallback(null) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 宽度同原来：两侧各留 16dp，最宽 400dp；位置交给拖动宿主（默认贴底居中）。
        val barWidth = minOf(400.dp, (maxWidth - 32.dp).coerceAtLeast(0.dp))
        // 像 Gboard：拖动时左右能伸出屏幕；往侧边推过去松手就靠边变竖条（和实体键盘浮条同一规则），
        // 竖条从上到下与横条从左到右同序：语言、展开键盘、麦克风、退格；竖条不放状态文字，听写看麦克风光圈。
        DraggableFloatingBar(position, onPositionChange, barWidth, dockAtEdge = dockAtEdge,
            sideDockSize = DpSize(VoicePillButton + 8.dp, VoicePillButton * 4 + 8.dp), dockPreviewColor = accentColor,
            onMovingChange = onMovingChange) { vertical ->
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, LocalFrostedTextSurface provides textSurface) {
            // 底板加一圈浅阴影和细边：玻璃开着、主题底色和页面同色时（黑白浅色），底板本身和页面分不出来，
            // 横条只因压在页面按钮上才像有底，竖条靠在空白处就只剩四个按钮浮着（平板 11-18 截图）。
            val pillShape = RoundedCornerShape(HardwareCandidateCornerRadius)
            val pillSurface = Modifier
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    currentBoundsCallback(Rect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt()))
                }
                .shadow(3.dp, pillShape)
                .clip(pillShape).then(surface())
                .border(1.dp, color.copy(alpha = 0.12f), pillShape)
                .testTag("voice-collapsed-pill").padding(4.dp)
                .glassTextHalo(textSurface?.halo)
            if (vertical) Column(Modifier.fillMaxWidth().then(pillSurface),
                horizontalAlignment = Alignment.CenterHorizontally) {
                HardwareToolbarLanguageKey(color, languageActions, languageLabel, onSwitchLanguage)
                HardwareToolbarAction(Icons.Default.Keyboard, "展开键盘", "voice-pill-keyboard", color, onClick = onShowKeyboard)
                VoicePillMic(listening, amplitude, color, accentColor, onMic)
                VoicePillBackspace(color, onBackspace, onBackspacePress, onBackspaceRelease, backspaceRepeatMs)
            } else Row(
                Modifier.fillMaxWidth().then(pillSurface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HardwareToolbarLanguageKey(color, languageActions, languageLabel, onSwitchLanguage)
                HardwareToolbarAction(Icons.Default.Keyboard, "展开键盘", "voice-pill-keyboard", color, onClick = onShowKeyboard)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text(when {
                        listening && autoResumed -> "已自动接着听…"
                        listening -> "正在听取…"
                        else -> "点按麦克风说话"
                    }, color = color, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // 麦克风在右数第二个，与展开后工具栏里麦克风的位置一致。
                VoicePillMic(listening, amplitude, color, accentColor, onMic)
                VoicePillBackspace(color, onBackspace, onBackspacePress, onBackspaceRelease, backspaceRepeatMs)
            }
        }
        }
    }
}

/** 悬浮条上每个按钮的边长；竖条宽、高按它算。 */
private val VoicePillButton = 48.dp

/** 悬浮条麦克风：听写时用与工具栏相同的音量光圈（正圆、随音量放大）。 */
@Composable
private fun VoicePillMic(listening: Boolean, amplitude: () -> Float, color: Color, accentColor: Color, onClick: () -> Unit) {
    val currentAmplitude by rememberUpdatedState(amplitude)
    val level by animateFloatAsState(if (listening) microphoneLevel(currentAmplitude()) else 0f,
        animationSpec = tween(100), label = "voice-pill-level")
    Box(
        Modifier.size(VoicePillButton)
            .then(if (listening) Modifier.microphoneLevelHalo({ level }, accentColor) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (listening) "停止语音输入" else "语音输入"; selected = listening }
            .testTag("voice-pill-mic"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Mic, null, tint = if (listening) accentColor else color, modifier = Modifier.size(22.dp))
    }
}

/** 删除键的手势：点按删一个，长按后按键盘删除键的节奏连续删除（复用同一个手势检测）。 */
internal fun voicePillBackspaceActions(
    onDelete: () -> Unit, onPress: () -> Unit, onRelease: () -> Unit, onPressedChange: (Boolean) -> Unit,
    repeatIntervalMs: Long = com.kingzcheung.xime.keyboard.DeleteRepeatSpeed.DEFAULT.intervalMs,
) = RepeatingKeyActions(
    onTap = onDelete,
    onPress = { onPressedChange(true); onPress() },
    onRelease = { onPressedChange(false); onRelease() },
    onPreview = {},
    onRepeat = onDelete,
    repeatIntervalMs = repeatIntervalMs,
)

@Composable
private fun VoicePillBackspace(color: Color, onDelete: () -> Unit, onPress: () -> Unit, onRelease: () -> Unit, repeatMs: Long) {
    var pressed by remember { mutableStateOf(false) }
    val currentDelete by rememberUpdatedState(onDelete)
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    val currentRepeatMs by rememberUpdatedState(repeatMs)
    Box(
        Modifier.size(VoicePillButton)
            .semantics { contentDescription = "删除"; role = Role.Button; onClick { currentDelete(); true } }
            .pointerInput(Unit) {
                detectRepeatingKeyGestures {
                    voicePillBackspaceActions({ currentDelete() }, { currentPress() }, { currentRelease() }, { pressed = it }, currentRepeatMs)
                }
            }
            .testTag("voice-pill-backspace"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = if (pressed) .14f else 0f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.Backspace, null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}
