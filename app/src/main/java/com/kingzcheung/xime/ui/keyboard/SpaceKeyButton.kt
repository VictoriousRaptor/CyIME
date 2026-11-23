package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 全键盘、九键、笔画和分栏布局共用的空格交互。 */
@Composable
fun SpaceKeyButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    schemaName: String = "",
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    isVoiceMode: Boolean = LocalKeyboardInputActions.current.isVoiceMode,
    voiceSticky: Boolean = LocalKeyboardInputActions.current.voiceSticky,
    isSttEnabled: Boolean = LocalKeyboardInputActions.current.isSttEnabled,
    onVoiceModeChange: ((Boolean) -> Unit)? = LocalKeyboardInputActions.current.onVoiceModeChange,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: TextUnit = 14.sp,
    /**
     * 空格往上滑一下（没到长按时间、往上多于往旁边）松手时做的事，如九键「空格上滑输入 0」；null 时上滑照旧什么都不做。
     * 滑的时候空格上渐渐写出 [swipeUpLabel]，过了点击容差就是到位，松手执行；滑回来就不算。
     */
    onSwipeUp: (() -> Unit)? = null,
    swipeUpLabel: String? = null,
) {
    val settings = LocalKeyboardInputPreferences.current
    val actions = LocalKeyboardInputActions.current
    val onMove by rememberUpdatedState(actions.onCursorMove)
    val onMoveVertical by rememberUpdatedState(actions.onCursorMoveVertical)
    val onCursorMode by rememberUpdatedState(actions.onCursorModeChange)
    val onVoiceToggle by rememberUpdatedState(actions.onVoiceToggle)
    val onVoiceHoldEnd by rememberUpdatedState(actions.onVoiceHoldEnd)
    val onVoiceHoldCancel by rememberUpdatedState(actions.onVoiceHoldCancel)
    val voiceOn by rememberUpdatedState(isVoiceMode)
    val holdAction = settings.effectiveSpaceHold
    val cursorUnitPx = rememberCursorUnitPx()
    // 滑到左右两把锁上锁定（SpaceVoiceLock）：键盘外没有提供时为 null，长按说话照旧、不能锁定。
    val voiceLock = LocalSpaceVoiceLock.current
    DisposableEffect(Unit) { onDispose {
        onCursorMode?.invoke(false)
        voiceLock?.holding = false
        voiceLock?.armed = false
        voiceLock?.side = 0
        voiceLock?.cancelShown = false
        voiceLock?.cancelArmed = false
    } }
    // 空格在 Compose 根里的左上角：手指位置换算到根坐标，和锁按钮比。只在手势里读，不触发重组。
    val spaceOrigin = remember { floatArrayOf(0f, 0f) }
    // 这个空格自己在根里的范围：开始按住说话时才交给锁（别的布局里也有空格，平时不去改共用的那一份）。
    var spaceBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val currentClick by rememberUpdatedState(onClick)
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    val currentSwipeUp by rememberUpdatedState(onSwipeUp)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    var cursorActive by remember { mutableStateOf(false) }
    // 键缝动效：空格真的输入了、移光标走了一格时告诉光层（关着时这些调用什么都不做）。
    val gapLightTrigger = remember { KeyGapLightTrigger() }
    // 按住空格说话且开了「上滑取消」：键面写「上滑取消」；手指上滑过线后变成删除色、写「松手取消」。
    var voiceHolding by remember { mutableStateOf(false) }
    var voiceCancelArmed by remember { mutableStateOf(false) }
    // 手指往上走了多少（0 = 还在空格上，1 = 到取消线）：删除色跟着一点点涨上来，看得出再往上就要取消了。
    var voiceCancelPull by remember { mutableFloatStateOf(0f) }
    // 这次长按开了语音：空格从按住处铺开一层强调色、写「正在听…」、麦克风随音量起伏（用户 11-20：长按后看不出进没进语音）。
    var voiceHoldStarted by remember { mutableStateOf(false) }
    var voiceHoldX by remember { mutableFloatStateOf(0f) }
    // 空格上滑（[onSwipeUp]）：往上走了多少（0~1，到点击容差为 1）和到没到位。到位后松手执行。
    var swipeUpProgress by remember { mutableFloatStateOf(0f) }
    var swipeUpArmed by remember { mutableStateOf(false) }
    val frostedGlass = settings.frostedGlass
    val shadow = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, backgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) Modifier.drawBehind {
            drawRoundRect(crispShadowColor(backgroundColor), topLeft = Offset(0f, shadowElevation.toPx()),
                size = size, cornerRadius = CornerRadius(KeyShapes.current.radius(shadowShapeRadius).toPx()))
        } else Modifier
    }
    val keyFill = frostedKeyColor(
        backgroundColor, textColor, frostedGlass,
        legacyStateColor = if (pressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor,
        pressed = pressed,
    )
    // 上滑取消的提示只在麦克风真开着时显示（语音总开关关着时长按不会开麦）。
    val showVoiceCancel = voiceHolding && isVoiceMode
    // 已锁定、还在听：手指离开了，空格照样是在听的样子，写「点按结束」。
    val voiceLocked = voiceLock?.locked == true && isVoiceMode
    // 进入语音的样子同样只在麦克风真开着时出现：总开关关着、输入框不许语音时不假装在听。
    val showVoiceHold = (voiceHoldStarted || voiceLocked) && isVoiceMode
    // 锁按钮跟着麦克风真开着才浮出来（按住说话、但麦没开起来时不出锁）；只由开始这次说话的这个空格去写。
    // 开了上滑取消时中间的取消按钮一起浮出，过线时跟空格一起变红。
    SideEffect {
        if (voiceHoldStarted && voiceLock != null) {
            voiceLock.holding = isVoiceMode
            voiceLock.cancelShown = showVoiceCancel
            voiceLock.cancelArmed = showVoiceCancel && voiceCancelArmed
        }
    }
    // 手指在锁按钮上：松手就锁定。和锁按钮「到位」的样子同一个条件。
    val showVoiceLockArmed = voiceHoldStarted && voiceLock?.lockReady == true
    // 锁定后这句结束了（点麦克风停、发送后没接着听、离开输入框）：稍等一下再解除，发送后接着听时锁定的样子不闪掉。
    LaunchedEffect(isVoiceMode, voiceLock?.locked) {
        if (!isVoiceMode && voiceLock?.locked == true) {
            delay(SPACE_VOICE_LOCK_RELEASE_MS)
            if (!voiceOn) voiceLock?.locked = false
        }
    }
    val accentColor = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.accent
        ?: androidx.compose.material3.MaterialTheme.colorScheme.primary
    // 用删除键上滑清空同一套删除色，任何主题、玻璃下都认得出：往上滑时先淡淡透出，过线 0.14 秒涨满；
    // 滑回来退掉，松手取消时随键面一起淡出。
    val cancelTarget = when {
        !showVoiceCancel -> 0f
        voiceCancelArmed -> 1f
        else -> VOICE_CANCEL_PREVIEW * voiceCancelPull
    }
    val cancelProgress by animateFloatAsState(cancelTarget, tween(when {
        !showVoiceCancel -> VOICE_HOLD_FADE_MS
        voiceCancelArmed -> VOICE_CANCEL_ARM_MS
        else -> VOICE_CANCEL_FOLLOW_MS
    }), label = "voiceCancel")
    val cancelColors = if (cancelProgress > 0f) keyFlickFeedbackColors(keyFill, textColor,
        accentColor, KeyFlickFeedback.DELETE, cancelProgress, frostedGlass.enabled) else null
    // 音量（0~1，没在听时 0）：只在绘制时读，音量变了只重画不重组。
    val voiceLevel = LocalKeyGapLightVoice.current
    val voiceSpread = remember { Animatable(0f) }
    LaunchedEffect(showVoiceHold) {
        if (showVoiceHold) {
            voiceSpread.snapTo(0f)
            voiceSpread.animateTo(1f, tween(VOICE_HOLD_SPREAD_MS, easing = FastOutSlowInEasing))
        } else voiceSpread.animateTo(0f, tween(VOICE_HOLD_FADE_MS))
    }
    // 换语言后空格先写语言名再淡成图标（用户 11-09，像 iOS）；只在平时不写语言名时出现。
    val displayName = schemaName.replace("拼音九键", "拼音")
    LaunchedEffect(displayName) { SpaceLanguageFlash.observe(displayName, android.os.SystemClock.uptimeMillis()) }
    val languageFlash = SpaceLanguageFlash.current
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(languageFlash, displayName) {
        val now = android.os.SystemClock.uptimeMillis()
        val start = SpaceLanguageFlash.alpha(languageFlash, displayName, now)
        flashAlpha.snapTo(start)
        if (start <= 0f || languageFlash == null) return@LaunchedEffect
        val holdLeft = languageFlash.startMs + SpaceLanguageFlash.HOLD_MS - now
        if (holdLeft > 0L) delay(holdLeft)
        val fadeLeft = languageFlash.startMs + SpaceLanguageFlash.HOLD_MS + SpaceLanguageFlash.FADE_MS -
            android.os.SystemClock.uptimeMillis()
        flashAlpha.animateTo(0f, tween(fadeLeft.coerceIn(1L, SpaceLanguageFlash.FADE_MS).toInt(), easing = LinearEasing))
    }
    val flashVisible by remember { derivedStateOf { flashAlpha.value > 0f } }
    BoxWithConstraints(
        modifier.fillMaxSize().testTag("space-key")
            .onGloballyPositioned {
                it.positionInRoot().let { p -> spaceOrigin[0] = p.x; spaceOrigin[1] = p.y }
                spaceBounds = it.boundsInRoot()
            }
            .pointerInput(holdAction, settings.cursorStepDp, settings.cursorSwipeLines, cursorUnitPx, settings.spaceHoldDelayMs,
                settings.voiceSwipeCancel) {
                val step = settings.cursorStepDp * cursorUnitPx
                val tapTolerance = maxOf(viewConfiguration.touchSlop, 24.dp.toPx())
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    // 刚移完光标的这一下不输入空格，也就不该有按键音和振动（按住照常进入移动光标）。
                    val guarded = SpaceAfterCursorGuard.blocksSpace(down.uptimeMillis)
                    if (!guarded) currentPress?.invoke()
                    // 已锁定在听：这一下（点或按住都算）就是结束，松手结束并上屏，不输入空格、不再长按。
                    val endsLock = voiceLock?.locked == true && voiceOn
                    // 这一下按下时有没有上滑动作：整个手势按按下时的算，中途候选变了也不换。
                    val swipeUp = currentSwipeUp?.takeIf { !endsLock && !guarded }
                    var held = false
                    var movedBeforeHold = false
                    // 按住说话（用户 10-30：长按开了语音松手不停，和点麦克风是两回事）：这次长按开的语音，松手就结束并上屏。
                    var startedVoice = false
                    var lastX = down.position.x
                    var lastY = down.position.y
                    val cursorSteps = CursorStepAccumulator(step)
                    // A line is a much larger edit than a character. Keep its threshold
                    // independent of fast horizontal settings; coalesced events never skip rows.
                    val verticalSteps = CursorStepAccumulator(maxOf(48f * cursorUnitPx, step * 5f))
                    val timer = scope.launch {
                        if (endsLock) return@launch
                        delay(settings.spaceHoldDelayMs)
                        held = true
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        when (holdAction) {
                            SpaceHoldAction.CURSOR -> {
                                cursorActive = true
                                gapLightTrigger.cursorAt(lastX)
                                SpaceCursorShift.begin()
                                CursorLineMetrics.request()
                                onCursorMode?.invoke(true)
                            }
                            SpaceHoldAction.REPEAT -> while (true) { currentClick(); delay(70L) }
                            SpaceHoldAction.VOICE_TOGGLE -> {
                                // 工具栏麦克风已经在听时，长按只把它结束，松手不再动。
                                startedVoice = !voiceOn
                                onVoiceToggle?.invoke()
                                voiceHolding = startedVoice && settings.voiceSwipeCancel
                                if (startedVoice) { voiceHoldX = lastX; voiceHoldStarted = true; voiceLock?.space = spaceBounds }
                            }
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (held && holdAction == SpaceHoldAction.REPEAT &&
                                (change.position.x < 0f || change.position.x > size.width ||
                                    change.position.y < 0f || change.position.y > size.height)) break
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            // 滑到左右任一把浮起来的锁上（弧线以内）：松手锁定。那里不算上滑取消。
                            var lockArmed = false
                            var onCancelButton = false
                            if (startedVoice && voiceLock != null) {
                                val inRoot = Offset(spaceOrigin[0] + change.position.x, spaceOrigin[1] + change.position.y)
                                val side = voiceLock.zoneSide(inRoot)
                                lockArmed = spaceVoiceLockArmed(side != 0, released = !change.pressed,
                                    wasArmed = voiceLock.armed)
                                // 抬手那一下偏出去：还算刚才那一把。
                                voiceLock.side = if (side != 0) side else if (lockArmed) voiceLock.side else 0
                                onCancelButton = voiceLock.cancelButtonContains(inRoot)
                                voiceLock.finger = inRoot
                                if (lockArmed != voiceLock.armed) {
                                    voiceLock.armed = lockArmed
                                    if (lockArmed && voiceLock.holding) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            }
                            // 上滑取消：手指高过空格顶边一个键高（或在中间的取消按钮上）才算，左右挪不管；滑回来就恢复松手上屏。
                            if (voiceHolding) {
                                voiceCancelPull = if (lockArmed) 0f else (-change.position.y / size.height).coerceIn(0f, 1f)
                                val armed = spaceVoiceCancelArmed(lockArmed, change.position.y, size.height.toFloat(), onCancelButton)
                                if (armed != voiceCancelArmed) {
                                    voiceCancelArmed = armed
                                    if (armed) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            }
                            // 空格上滑：长按还没开始时，往上比往旁边多、超过点击容差就到位；滑回来或往旁边偏多了就不算。
                            // 长按一开始（光标、语音、连续空格）就不再是上滑，照旧交给长按。
                            if (swipeUp != null) {
                                val up = down.position.y - change.position.y
                                val vertical = !held && up > abs(change.position.x - down.position.x)
                                swipeUpProgress = if (vertical) (up / tapTolerance).coerceIn(0f, 1f) else 0f
                                val armed = vertical && up > tapTolerance
                                if (armed != swipeUpArmed) {
                                    swipeUpArmed = armed
                                    if (armed) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            }
                            // 拇指点空格常带几毫米横移：在约 4 mm 内仍算一次点击（三星、Gboard 偏移 3.6 mm 仍上屏），
                            // 超出才算滑动。系统 touchSlop 只有约 8 dp，曾让 2.4 mm 的偏移把空格吞掉（1.12.36 实测）。
                            if (!held && (abs(change.position.x - down.position.x) > tapTolerance ||
                                abs(change.position.y - down.position.y) > tapTolerance)) {
                                movedBeforeHold = true
                                timer.cancel()
                            }
                            if (cursorActive) {
                                cursorSteps.stepPx = lineCursorStepPx(view.rootView.width / 2f, settings.cursorSwipeLines,
                                    CursorLineMetrics.charsPerLine, step, cursorUnitPx)
                                val steps = cursorSteps.move(dx)
                                gapLightTrigger.cursorAt(change.position.x)
                                if (steps != 0) gapLightTrigger.cursorStep()
                                if (steps != 0) onMove?.invoke(steps)
                                val rows = verticalSteps.move(dy).coerceIn(-1, 1)
                                if (rows != 0) onMoveVertical?.invoke(rows)
                                if (steps != 0 || rows != 0) SpaceAfterCursorGuard.mark(change.uptimeMillis)
                            }
                            change.consume()
                            if (!change.pressed) {
                                if (endsLock) {
                                    voiceLock?.locked = false
                                    onVoiceHoldEnd?.invoke()
                                } else if (swipeUp != null && swipeUpArmed && !held) {
                                    gapLightTrigger.tap()
                                    swipeUp()
                                } else if (!held && !movedBeforeHold && !guarded) {
                                    gapLightTrigger.tap()
                                    currentClick()
                                }
                                break
                            }
                        }
                    } finally {
                        if (cursorActive) {
                            SpaceAfterCursorGuard.mark(android.os.SystemClock.uptimeMillis())
                            SpaceCursorShift.end()
                        }
                        gapLightTrigger.cursorEnd()
                        timer.cancel()
                        if (startedVoice) {
                            val lockNow = voiceLock?.lockReady == true
                            // 松手时记一笔（只记状态，不记文字）：再有人说「动画到了松手没锁上」，日志里看得出卡在哪一项。
                            com.kingzcheung.xime.util.FileLogger.i("SpaceVoiceLock", "release armed=${voiceLock?.armed} side=${voiceLock?.side} " +
                                "holding=${voiceLock?.holding} voiceOn=$voiceOn lock=$lockNow cancel=${voiceHolding && voiceCancelArmed}")
                            when {
                                // 锁定：手指离开也一直听，点一下空格结束。和锁按钮画「到位」用同一个条件（[SpaceVoiceLockState.lockReady]）。
                                lockNow -> {
                                    voiceLock?.lockedSide = voiceLock?.side?.takeIf { it != 0 } ?: 1
                                    voiceLock?.locked = true
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                                voiceHolding && voiceCancelArmed -> onVoiceHoldCancel?.invoke()
                                else -> onVoiceHoldEnd?.invoke()
                            }
                        }
                        voiceLock?.holding = false
                        voiceLock?.armed = false
                        voiceLock?.side = 0
                        voiceLock?.cancelShown = false
                        voiceLock?.cancelArmed = false
                        voiceLock?.finger = Offset.Unspecified
                        voiceHolding = false
                        voiceCancelArmed = false
                        voiceCancelPull = 0f
                        voiceHoldStarted = false
                        swipeUpProgress = 0f
                        swipeUpArmed = false
                        pressed = false
                        cursorActive = false
                        onCursorMode?.invoke(false)
                        if (!guarded) currentRelease?.invoke()
                    }
                }
            }
            .padding(scaledKeyVisualPadding())
            .keyGlow(Modifier.then(shadow)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(cancelColors?.background ?: keyFill)
            .drawBehind {
                // 空格上滑：往上走时一层强调色跟着变深（键内渐变，不弹气泡），到位时最深。
                val up = swipeUpProgress
                if (up > 0f) drawRect(accentColor.copy(alpha = (if (swipeUpArmed) 0.24f else 0.12f) * up))
                // 开始说话：强调色从按住的地方铺满整个空格，之后随音量轻轻变深；上滑时一点点让给删除色。
                val spread = voiceSpread.value * (1f - cancelProgress)
                if (spread > 0f) {
                    val level = voiceLevel?.invoke() ?: 0f
                    val x = voiceHoldX.coerceIn(0f, size.width)
                    val reach = kotlin.math.hypot(maxOf(x, size.width - x), size.height)
                    drawCircle(accentColor.copy(alpha = (0.20f + 0.16f * level) * spread),
                        radius = reach * voiceSpread.value, center = Offset(x, size.height / 2f))
                }
            },
                // 键缝动效：空格真的输入了一下，一块光从空格往上走到顶边；按住移光标时色块跟着手指。
                gapLight = GapLightKind.RISE, gapLightTrigger = gapLightTrigger),
        contentAlignment = Alignment.Center
    ) {
        val iconSize = keyIconSizeDp(maxWidth.value, maxHeight.value)
        val labelHeight = (maxHeight.value - iconSize).coerceAtLeast(1f)
        val labelSize = minOf(keyLabelSizeSp(schemaName, 10f,
            maxWidth.value, maxHeight.value, density.fontScale, settings.keyTextScale),
            labelHeight / (density.fontScale * 1.4f)).coerceAtLeast(1f)
        // 闪示时语言名独占这块键，不用给图标让高度。
        val flashSize = minOf(keyLabelSizeSp(displayName, 12f,
            maxWidth.value, maxHeight.value, density.fontScale, settings.keyTextScale),
            maxHeight.value / (density.fontScale * 1.4f)).coerceAtLeast(1f)
        if (showVoiceHold) {
            val hintSize = (maxHeight.value * 0.32f / density.fontScale).coerceIn(9f, 16f)
            val color = cancelColors?.foreground ?: textColor
            Row(Modifier.graphicsLayer { alpha = voiceSpread.value }.semantics { contentDescription = "空格，正在语音输入" },
                verticalAlignment = Alignment.CenterVertically) {
                VoiceHoldMic(lerp(accentColor, color, cancelProgress), (hintSize * 1.3f).dp, voiceLevel)
                if (voiceLocked && !voiceHoldStarted) {
                    Spacer(Modifier.width(2.dp))
                    // 锁身重、锁梁细：按外框居中看着偏下，和锁按钮一样按视觉重心往上提一点。
                    Icon(Icons.Default.Lock, contentDescription = null, tint = color,
                        modifier = Modifier.size((hintSize * 0.95f).dp)
                            .offset(y = -(hintSize * 0.95f * SPACE_VOICE_LOCK_ICON_LIFT).dp).testTag("space-voice-lock-icon"))
                }
                if (this@BoxWithConstraints.maxWidth.value >= hintSize * density.fontScale * 4.6f + hintSize * 1.3f + 8f) {
                    Spacer(Modifier.width(4.dp))
                    Text(when {
                        voiceLocked && !voiceHoldStarted -> "点按结束"
                        showVoiceLockArmed -> "松手锁定"
                        showVoiceCancel && voiceCancelArmed -> "松手取消"
                        showVoiceCancel -> "上滑取消"
                        else -> "正在听…"
                    }, color = color,
                        fontSize = hintSize.sp, lineHeight = (hintSize * 1.2f).sp, maxLines = 1, softWrap = false,
                        fontFamily = AppFonts.keyFontFamily,
                        modifier = Modifier.testTag(if (showVoiceCancel) "space-voice-cancel-hint" else "space-voice-hold-hint"))
                }
            }
        } else if (swipeUpLabel != null && swipeUpProgress > 0f) {
            // 往上滑时空格上写要打的字（如「0」），越往上越清楚；到位时用强调色。
            val size = (maxHeight.value * 0.5f / density.fontScale).coerceIn(10f, 24f)
            Text(swipeUpLabel, color = if (swipeUpArmed) accentColor else textColor,
                fontSize = size.sp, lineHeight = (size * 1.2f).sp, maxLines = 1, softWrap = false,
                fontFamily = AppFonts.keyFontFamily,
                modifier = Modifier.graphicsLayer { alpha = 0.35f + 0.65f * swipeUpProgress }
                    .testTag("space-swipe-up-label").semantics { contentDescription = "空格" })
        } else if (cursorActive) {
            SpaceCursorHint(textColor, maxWidth.value, maxHeight.value, density.fontScale, !settings.hideFunctionKeyHints)
        } else if (!settings.hideFunctionKeyHints && SpaceLanguageFlash.isLanguageName(displayName) && labelSize >= 8f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(displayName, color = textColor,
                    fontSize = labelSize.sp, lineHeight = (labelSize * 1.2f).sp, maxLines = 1, softWrap = false,
                    fontFamily = AppFonts.keyFontFamily)
                Icon(Icons.Default.SpaceBar, contentDescription = "空格", tint = textColor,
                    modifier = Modifier.size(iconSize.dp))
            }
        } else Box(contentAlignment = Alignment.Center) {
            // 键太矮时方案名会被压成看不清的小点，这时只留空格图标。
            // 「功能键不显示提示字」开着时空格只留图标（GitHub #11）；刚换语言时语言名占图标的位置，再淡回图标。
            Icon(Icons.Default.SpaceBar, contentDescription = "空格", tint = textColor,
                modifier = Modifier.size(iconSize.dp).graphicsLayer { alpha = 1f - flashAlpha.value })
            if (flashVisible) {
                Text(displayName, color = textColor,
                    fontSize = flashSize.sp, lineHeight = (flashSize * 1.2f).sp, maxLines = 1, softWrap = false,
                    fontFamily = AppFonts.keyFontFamily,
                    modifier = Modifier.testTag("space-language-flash").graphicsLayer { alpha = flashAlpha.value })
            }
        }
    }
}

private const val VOICE_HOLD_SPREAD_MS = 280
private const val VOICE_HOLD_FADE_MS = 150
/** 锁定的这句结束后多久解除锁定：发送后接着听（连续语音，0.5 秒后开麦）时锁定的样子接得上。 */
private const val SPACE_VOICE_LOCK_RELEASE_MS = 1500L
/** 还没到取消线时删除色最多涨到几成：看得出在往取消走，又和过线后的满色分得开。 */
private const val VOICE_CANCEL_PREVIEW = 0.4f
private const val VOICE_CANCEL_ARM_MS = 140
private const val VOICE_CANCEL_FOLLOW_MS = 60

/** 按住空格说话时键面上的麦克风：背后一圈随音量放大的光，看得出麦克风在收音。 */
@Composable
private fun VoiceHoldMic(color: Color, size: Dp, level: (() -> Float)?) {
    Box(Modifier.size(size).testTag("space-voice-hold-mic").drawBehind {
        val value = level?.invoke() ?: 0f
        drawCircle(color.copy(alpha = 0.18f + 0.30f * value), radius = this.size.minDimension * (0.55f + 0.35f * value))
    }, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = color, modifier = Modifier.fillMaxSize(0.8f))
    }
}

/**
 * 按住空格移光标时空格上的提示（用户 11-15：整块键盘不再压淡，中间也不再盖「光标调整」，那一层把键缝动效挡住了）。
 * 两头各一个箭头，中间写「移动光标」；箭头靠在键的两头，手指按在中间也看得见。
 * 键窄得放不下字、或开了「功能键不显示提示字」时只留箭头。
 */
@Composable
private fun SpaceCursorHint(color: Color, widthDp: Float, heightDp: Float, fontScale: Float, label: Boolean) {
    val textSize = (heightDp * 0.32f / fontScale).coerceIn(9f, 16f)
    val arrow = (heightDp * 0.5f).coerceIn(12f, 24f)
    // 四个字、两个箭头、中间留的空都放得下才写字。
    val showLabel = label && widthDp >= textSize * fontScale * 4.4f + arrow * 2f + 8f
    // 平时的空格图标不在了：读屏和测试仍按「空格」找到这个键。
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp).testTag("space-cursor-hint").semantics { contentDescription = "空格" },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = color, modifier = Modifier.size(arrow.dp))
        if (showLabel) Text("移动光标", color = color, fontSize = textSize.sp, lineHeight = (textSize * 1.2f).sp,
            maxLines = 1, softWrap = false, fontFamily = AppFonts.keyFontFamily)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = color, modifier = Modifier.size(arrow.dp))
    }
}
