package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.kingzcheung.xime.platform.AndroidPlatform

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardResizeOverlay(
    initialHeightDp: Int,
    defaultHeightDp: Int,
    currentBottomPaddingDp: Int,
    isFloatingMode: Boolean,
    initialWidthDp: Int = 0,
    minWidthDp: Int = 200,
    maxWidthDp: Int = Int.MAX_VALUE,
    initialOpacity: Float = 1f,
    onHeightChange: (Int) -> Unit,
    onWidthChange: (Int) -> Unit = {},
    onGeometryChange: (ResizeGeometry) -> Unit = {},
    onBottomPaddingChange: (Int) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onReset: (Int) -> Unit,
    onConfirm: (Int, Int, Boolean, Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onFloatingModeChange: ((Boolean) -> Unit)? = null,
    isSplitKeyboard: Boolean = false,
    onSplitKeyboardChange: ((Boolean) -> Unit)? = null,
    splitKeyboardSupported: Boolean = true,
    onPositionDrag: ((Float, Float) -> Unit)? = null,
    onPositionDragEnd: (() -> Unit)? = null,
    defaultSize: ProtectedKeyboardSize? = null,
    /** 吸附到特定位置时的振动；为空时用系统触感。 */
    onSnapFeedback: (() -> Unit)? = null,
    /**
     * 拖动切换模式时另一种模式下键盘会落到的矩形（宿主 px，参数为目标是否悬浮、宿主宽高）；
     * 和点「悬浮」按钮切过去的结果相同。为空时不支持拖动切换。
     */
    modeSwitchTarget: ((toFloating: Boolean, hostWidthPx: Float, hostHeightPx: Float) -> ResizeRect?)? = null,
    /** 确认时保存悬浮键盘旋转角（度）；取消时不调用。 */
    onRotationConfirm: ((Float) -> Unit)? = null,
    defaultBottomPaddingDp: Int = 0,
    onPreviewControlsPositioned: ((android.graphics.Rect) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val hostView = LocalView.current
    val currentSnapFeedback by rememberUpdatedState(onSnapFeedback ?: {
        hostView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    })
    val roundedBottom = rememberRoundedKeyboardBottom()
    val roundedTop = rememberRoundedKeyboardTop()
    val cornerGlass = LocalKeyboardInputPreferences.current.frostedGlass.enabled
    val opacityEditingState = LocalKeyboardResizeOpacityEditing.current ?: remember { mutableStateOf(false) }
    var editingOpacity by opacityEditingState
    val opacityInteraction = remember { MutableInteractionSource() }
    val draggingOpacity by opacityInteraction.collectIsDraggedAsState()
    val pressingOpacity by opacityInteraction.collectIsPressedAsState()
    // 按住滑条时撤去遮罩，直接预览实际透明度。
    val adjustingOpacity = draggingOpacity || pressingOpacity
    // 调透明度不再进入单独的编辑态（返回/完成），外框和按钮留在原地；editingOpacity 只保留给旧调用方。
    val configuration = LocalConfiguration.current
    val footerDp = configuration.floatingFooterHeightDp
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    var currentHeightDp by remember { mutableFloatStateOf(initialHeightDp.toFloat()) }
    var currentWidthDp by remember { mutableFloatStateOf(initialWidthDp.toFloat()) }
    var currentBottomPaddingDpState by remember { mutableFloatStateOf(currentBottomPaddingDp.toFloat()) }
    var floatingMode by remember(isFloatingMode) { mutableStateOf(isFloatingMode) }
    if (floatingMode && rememberFloatingGeometryLocked()) {
        LaunchedEffect(Unit) { onCancel() }
        return
    }
    var splitMode by remember(isSplitKeyboard) { mutableStateOf(isSplitKeyboard) }
    // 分体两半贴边：开启前的宽度留着，关闭分体时还原。
    var rectBeforeSplit by remember { mutableStateOf<ResizeRect?>(null) }
    val splitActive = splitMode && splitKeyboardSupported
    var opacity by remember { mutableFloatStateOf(initialOpacity.coerceIn(0.3f, 1f)) }

    if (LocalOnBackPressedDispatcherOwner.current != null) {
        // The IME consumes system Back as cancellation of the entire resize transaction.
        // Only the visible return button keeps the draft and returns to size controls.
        BackHandler(onBack = onCancel)
    }

    val currentOnGeometryChange by rememberUpdatedState(onGeometryChange)
    val currentOnReset by rememberUpdatedState(onReset)
    val currentOnPreviewOpacity by rememberUpdatedState(onOpacityChange)
    val currentOnPaddingEdited by rememberUpdatedState(onBottomPaddingChange)

    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var laidOutWindowHeight by remember(hostView) { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .onGloballyPositioned {
                overlayOrigin = it.positionInWindow()
                laidOutWindowHeight = hostView.rootView.height
            },
    ) {
        val viewRect = viewRectOf(maxWidth.value * density.density, maxHeight.value * density.density)
        val previewState = LocalKeyboardResizePreviewState.current
        var dragRect by remember(floatingMode, maxWidth, maxHeight) { mutableStateOf<ResizeRect?>(null) }
        var dragEdge by remember { mutableStateOf(ResizeHandle.NONE) }
        // 悬浮键盘转着时，边框、把手和按钮跟键盘一起绕键盘中心转；手势在键盘自己的（未旋转）坐标里算。
        // 手势进行中以按下时的中心为轴：手势坐标 = 把真实矩形的中心绕轴转回去，写回时再转过来。
        val rotationState = previewState.rotation?.takeIf { floatingMode }
        val rotation = rotationState?.floatValue ?: 0f
        var gesturePivot by remember { mutableStateOf<Offset?>(null) }
        val frame = dragRect ?: previewState.rect?.let { rotateRectCenter(it, gesturePivot, -rotation) } ?: viewRect
        val frameSpanDp = (minOf(frame.width, frame.height * 1.5f) / density.density).roundToInt()
        val frameStyle = remember(frameSpanDp) { resizeFrameStyle(frameSpanDp) }
        val dragAreaHeightDp = floatingDragAreaHeightDp(previewState.bottomPaddingDp,
            previewState.floatingBottomClearanceDp, frame.height / density.density, footerDp)
        val dragAreaHeightPx = dragAreaHeightDp * density.density
        val currentPreviewRect by rememberUpdatedState(previewState.rect?.let { rotateRectCenter(it, gesturePivot, -rotation) })
        val currentInitialPreviewRect by rememberUpdatedState(previewState.initialRect)
        val currentPreviewRectSink by rememberUpdatedState(previewState.onRectChange)
        val currentOnPreviewRectChange: (ResizeRect) -> Unit = { rect ->
            currentPreviewRectSink(rotateRectCenter(rect, gesturePivot, rotationState?.floatValue ?: 0f))
        }
        val currentViewRect by rememberUpdatedState(viewRect)
        val currentPreviewState by rememberUpdatedState(previewState)
        fun maximumFixedPadding(contentHeight: Float): Float {
            val session = currentPreviewState
            return minOf(maxOf(defaultBottomPaddingDp + 80f, session.initialBottomPaddingDp.toFloat()),
                ((session.bounds ?: currentViewRect).height / density.density - contentHeight).coerceAtLeast(0f))
        }
        fun adjustFixedPadding(rect: ResizeRect, previous: Float, deltaDp: Float): Pair<ResizeRect, Float> {
            val session = currentPreviewState
            val view = currentViewRect
            val contentHeight = rect.height / density.density - previous
            val padding = (previous + deltaDp).coerceIn(0f, maximumFixedPadding(contentHeight))
            val updated = fixedKeyboardRect(view.width, view.height, contentHeight * density.density,
                padding * density.density, session.fixedBottomInsetDp * density.density,
                rect.width, rect.centerX - view.width / 2f, density.density)
            session.onBottomPaddingChange(padding.roundToInt())
            currentOnPaddingEdited(padding.roundToInt())
            currentOnPreviewRectChange(updated)
            return updated to padding
        }
        // 固定键盘底边把手：手势导航时抬到系统底部手势区上面，往上拖不会触发回桌面。
        // 系统在键盘下面画了「切换输入法 / 收起键盘」那条按钮时也抬到它上面，不被按钮盖住、按得到（11-10）。
        val reportedGesturePx = WindowInsets.mandatorySystemGestures.getBottom(density)
        val tappablePx = WindowInsets.tappableElement.getBottom(density)
        val gestureZoneTopPx = remember(hostView, floatingMode, laidOutWindowHeight, overlayOrigin, reportedGesturePx, tappablePx,
            previewState.fixedBottomInsetDp) {
            if (floatingMode || laidOutWindowHeight <= 0) null else listOfNotNull(
                com.kingzcheung.xime.service.bottomGestureZoneTopInWindowPx(hostView)
                    ?: reportedGesturePx.takeIf { it > 0 }?.let { laidOutWindowHeight - it },
                com.kingzcheung.xime.service.imeNavButtonsTopInWindowPx(hostView),
            ).minOrNull()?.let { it - overlayOrigin.y }
        }
        val bottomHandleClearancePx = BOTTOM_HANDLE_GESTURE_CLEARANCE_DP * density.density
        // 左右边把手常贴着屏幕边，从边上往里拖会被系统当成返回手势，直接退出调节（11-05 平板）。
        // 把两条边中间一段（系统每边最多认 200dp）从系统手势里排除；分体时外侧边不可拖，不排除。
        val edgeExclusionHitPx = RESIZE_EDGE_HIT_DP * density.density
        val edgeExclusionHalfPx = minOf(frame.height, RESIZE_EDGE_GESTURE_EXCLUSION_DP * density.density) / 2f
        androidx.compose.runtime.DisposableEffect(hostView, frame, overlayOrigin, splitActive, editingOpacity) {
            if (AndroidPlatform.gestureExclusion) {
                val viewInWindow = IntArray(2).also { hostView.getLocationInWindow(it) }
                val dx = overlayOrigin.x - viewInWindow[0]
                val dy = overlayOrigin.y - viewInWindow[1]
                hostView.systemGestureExclusionRects = if (splitActive || editingOpacity) emptyList() else
                    listOf(frame.left, frame.right).map { x ->
                        android.graphics.Rect((x - edgeExclusionHitPx + dx).roundToInt(),
                            (frame.centerY - edgeExclusionHalfPx + dy).roundToInt(),
                            (x + edgeExclusionHitPx + dx).roundToInt(),
                            (frame.centerY + edgeExclusionHalfPx + dy).roundToInt())
                    }
            }
            onDispose {
                if (AndroidPlatform.gestureExclusion)
                    hostView.systemGestureExclusionRects = emptyList()
            }
        }
        val bottomHandleY = if (floatingMode) frame.bottom
            else fixedBottomHandleY(frame, gestureZoneTopPx, bottomHandleClearancePx)
        // 从两条实际可拖边往里排：提示到边框等距，工具面板在两个提示之间居中。
        val usableHeight = with(density) { (bottomHandleY - frame.top).toDp() }
        val hintInset = minOf(RESIZE_EDGE_HIT_DP.dp, usableHeight / 8)
        val hintRowHeight = minOf(28.dp, usableHeight / 7)
        val hintBandPx = with(density) { (hintInset + hintRowHeight).toPx() }
        var paddingHintCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val currentGestureZoneTop by rememberUpdatedState(gestureZoneTopPx)
        fun splitGapFraction(rect: ResizeRect): Float = SplitKeyboardGeometry.resolvedGap(
            previewState.splitGapFraction, rect.width / density.density,
            SplitKeyboardGeometry.gutterDp(floatingMode), previewState.splitPitchDp)
        // 中缝两条内侧竖边：优先用实际渲染出的中缝，测量前按居中对称估算。
        val splitGapPx = if (splitActive) splitGapFraction(frame) * frame.width else 0f
        val measuredHole = LocalSplitKeyboardGap.current?.hole(0f)?.takeIf { splitActive }
        val splitInnerLeft = measuredHole?.let { it.left - overlayOrigin.x } ?: (frame.centerX - splitGapPx / 2f)
        val splitInnerRight = measuredHole?.let { it.right - overlayOrigin.x } ?: (frame.centerX + splitGapPx / 2f)
        val currentSplitEdges by rememberUpdatedState(splitInnerLeft to splitInnerRight)
        val currentSplitGapFraction by rememberUpdatedState<(ResizeRect) -> Float>(::splitGapFraction)

        // 调节界面的面板和按钮照键盘本体画（11-04「和键盘本体统一风格」）：面板是键盘底色，按钮是功能键
        // （功能键颜色、按键圆角、不描边），开着的「悬浮」「分体」和亮起的 Shift 同色，「确认」和回车键同色。
        // 玻璃主题也用实心色，盖住下面的键字。没有键盘配色时（预览、测试）退回 Material 主题色。
        val palette = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current
        val accentColor = (palette?.accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 1f)
        val functionColor = (palette?.function ?: MaterialTheme.colorScheme.surfaceVariant).copy(alpha = 1f)
        val preferredText = (palette?.functionText ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 1f)
        val functionTextColor = if (contrastRatio(functionColor, preferredText) >= 4.5f) preferredText else readableOn(functionColor)
        val enterColor = (palette?.enter ?: functionColor).copy(alpha = 1f)
        // 渐变主题取渐变的平均色（iOS 渐变深色的 background 是纯黑，面板会成一块黑板）。
        val panelColor = (palette?.surface ?: MaterialTheme.colorScheme.surface).copy(alpha = 1f)
        val keyText = (palette?.text ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 1f)
        val panelText = if (contrastRatio(panelColor, keyText) >= 4.5f) keyText else readableOn(panelColor)
        val keyRadius = LocalKeyCornerRadius.current
        // 开关亮起时的颜色直接用 Shift 锁定的算法；按实心键算，玻璃下也不透。
        val toggleOnColor = shiftKeyColors(functionColor, functionTextColor, accentColor, panelColor,
            LocalKeyboardInputPreferences.current.frostedGlass.copy(enabled = false),
            com.kingzcheung.xime.viewmodel.ShiftMode.CAPS, pressed = false, legacyStateColor = functionColor)
            .background.compositeOver(panelColor)
        val panelShape = remember(floatingMode, roundedBottom, roundedTop) {
            keyboardPanelShape(floatingMode, roundedBottom, roundedTop)
        }
        val currentOnFloatingModeChange by rememberUpdatedState(onFloatingModeChange)
        fun switchFloating(enabled: Boolean) {
            floatingMode = enabled
            if (!enabled) {
                opacity = 1f // 透明度 0%，即完全不透明。
                currentOnPreviewOpacity(opacity)
            }
            currentOnFloatingModeChange?.invoke(enabled)
        }
        // 拖动切换模式：固定上拖或悬浮下拖贴底，渐变框变到目标位置，满了松手即切换。
        val currentModeSwitchTarget by rememberUpdatedState(modeSwitchTarget)
        var morphTarget by remember(floatingMode) { mutableStateOf<ResizeRect?>(null) }
        var undockProgress by remember(floatingMode) { mutableFloatStateOf(0f) }
        val dockGesture = remember(floatingMode) { FloatingDockGesture() }
        TrackDockHold(dockGesture)
        val morphProgress = if (floatingMode) dockGesture.progress else undockProgress
        /** [cardMovedY] 是这一下卡片实际往下挪的距离：手指比卡片多走的（卡片已经顶在底边）才算往下顶。 */
        fun dragTowardDock(rect: ResizeRect, bounds: ResizeRect, amount: Offset, cardMovedY: Float) {
            val onBottom = bounds.bottom - rect.bottom <= 12f * density.density &&
                !isFloatingRotated(rotationState?.floatValue ?: 0f)
            if (onBottom && morphTarget == null)
                morphTarget = currentModeSwitchTarget?.invoke(false, currentViewRect.width, currentViewRect.height)
            dockGesture.move(amount.x / density.density, (amount.y - cardMovedY) / density.density,
                onBottom && morphTarget != null)
        }
        fun endMorph() {
            morphTarget = null
            undockProgress = 0f
            dockGesture.cancel()
        }
        // 固定键盘上拉拉满就变悬浮，悬浮键盘接着跟这根手指走，松手停在那里（确认时再保存）。
        // 上拉的手势在固定模式的控件里，一变悬浮就结束；外层的 undockFollowTracker 接着读手指。
        val undockFollow = LocalKeyboardUndockFollow.current
        fun followFromHere(id: androidx.compose.ui.input.pointer.PointerId, fingerInWindow: Offset): Boolean {
            val follow = undockFollow ?: return false
            endMorph()
            follow.start(id, fingerInWindow)
            switchFloating(true)
            return true
        }
        if (undockFollow != null && floatingMode) {
            LaunchedEffect(undockFollow) {
                androidx.compose.runtime.snapshotFlow { undockFollow.finger }.collect { finger ->
                    val rect = currentPreviewRect ?: return@collect
                    if (finger == null) return@collect
                    val moveBounds = currentPreviewState.bounds ?: currentViewRect
                    currentOnPreviewRectChange(undockFollowRect(rect, finger - overlayOrigin,
                        UNDOCK_FOLLOW_GRAB_DP * density.density, moveBounds))
                }
            }
            androidx.compose.runtime.DisposableEffect(undockFollow) {
                val finish = { currentPreviewState.onDragEnd() }
                undockFollow.onFinish = finish
                onDispose { if (undockFollow.onFinish === finish) undockFollow.onFinish = null }
            }
        }

        // This full-size pointer layer catches uncovered hits below the controls.
        // Do not consume down events on their common parent: that cancels Slider drags.
        // 一层只负责暗背景、边框和手势。真实键盘由同一个 previewRect 驱动。
        // 暗遮罩只盖键盘外面（11-03「这遮罩怎么还能把键盘挡住了」）：以前整屏盖 44% 黑，键盘也被压暗，
        // 看不出真实的样子。现在挖掉键盘卡片（跟着转过的角度）再画，只画在这一层自己的范围里。
        val hostMaxWidth = maxWidth
        val hostMaxHeight = maxHeight
        val layerPivot = gesturePivot ?: Offset(frame.centerX, frame.centerY)
        if (onPreviewControlsPositioned == null && !editingOpacity && !adjustingOpacity)
            Box(Modifier.fillMaxSize().testTag("keyboard-resize-scrim").drawBehind {
                val left = frame.left.roundToInt().toFloat()
                val top = frame.top.roundToInt().toFloat()
                val card = Size(frame.width.roundToInt().toFloat().coerceAtLeast(1f),
                    frame.height.roundToInt().toFloat().coerceAtLeast(1f))
                val hole = Path().apply {
                    addOutline(panelShape.createOutline(card, layoutDirection, this@drawBehind))
                    translate(Offset(left, top))
                    if (rotation != 0f) transform(androidx.compose.ui.graphics.Matrix().apply {
                        translate(layerPivot.x, layerPivot.y)
                        rotateZ(rotation)
                        translate(-layerPivot.x, -layerPivot.y)
                    })
                }
                clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.44f)) }
            })
        fun confirmResize() {
            val finalRect = currentPreviewRect ?: previewState.rect
            if (floatingMode && finalRect != null) {
                val geometry = finalRect.toGeometry(
                    viewWidthPx = viewRect.width,
                    viewHeightPx = viewRect.height,
                    dragBarHeightPx = footerDp * density.density,
                    density = density.density,
                )
                currentWidthDp = geometry.widthDp.toFloat()
                currentHeightDp = geometry.heightDp.toFloat()
                currentOnGeometryChange(geometry)
            } else if (finalRect != null) {
                currentHeightDp = finalRect.height / density.density - previewState.bottomPaddingDp
                currentOnGeometryChange(finalRect.toGeometry(viewRect.width, viewRect.height, 0f, density.density))
            }
            if (splitActive) previewState.onSplitGapCommit()
            if (floatingMode) rotationState?.let { onRotationConfirm?.invoke(it.floatValue) }
            onConfirm(currentHeightDp.roundToInt(), previewState.bottomPaddingDp, floatingMode, opacity)
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            rotationZ = rotation
            transformOrigin = TransformOrigin(layerPivot.x / size.width.coerceAtLeast(1f),
                layerPivot.y / size.height.coerceAtLeast(1f))
        }) {
        if (onPreviewControlsPositioned != null) {
            Spacer(Modifier.absoluteOffset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
                .onGloballyPositioned {
                    val r = it.boundsInWindow()
                    val margin = with(density) { (if (floatingMode) maxOf(RESIZE_CORNER_HIT_DP.dp,
                        (FLOATING_ROTATE_HANDLE_SIZE_DP + FLOATING_ROTATE_HANDLE_GAP_DP).dp) else RESIZE_EDGE_HIT_DP.dp).roundToPx() }
                    onPreviewControlsPositioned(android.graphics.Rect(r.left.toInt() - margin, r.top.toInt() - margin,
                        kotlin.math.ceil(r.right).toInt() + margin, kotlin.math.ceil(r.bottom).toInt() + margin))
                })
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(if (editingOpacity) "keyboard-opacity-backdrop" else "keyboard-resize-frame")
                .semantics { contentDescription = if (editingOpacity) "透明度调整" else "拖动边框调整大小，拖动内部移动悬浮键盘" }
                .drawBehind {
                    // 拖透明度时只留滑条：外框、角标、把手、遮罩都不画（11-03「就只留透明度那个条」）。
                    if (editingOpacity || adjustingOpacity) return@drawBehind
                    val stroke = frameStyle.strokeDp.dp.toPx()
                    val pillThickness = frameStyle.handleThicknessDp.dp.toPx()
                    val bracketThickness = frameStyle.bracketThicknessDp.dp.toPx()
                    val bracketLeg = frameStyle.cornerLegDp.dp.toPx()
                        .coerceAtMost(minOf(frame.width, frame.height) / 4f)
                    val pillLength = frameStyle.handleLengthDp.dp.toPx()
                        .coerceAtMost((frame.width - 2f * (bracketLeg + 8.dp.toPx())).coerceAtLeast(pillThickness))
                    val sidePillLength = frameStyle.handleLengthDp.dp.toPx()
                        .coerceAtMost((frame.height - 2f * (bracketLeg + 8.dp.toPx())).coerceAtLeast(pillThickness))

                    // 外框就是键盘卡片本身的矩形：取整方式与 FloatingKeyboardContainer 摆放卡片一致（roundToInt）。
                    val cardLeft = frame.left.roundToInt().toFloat()
                    val cardTop = frame.top.roundToInt().toFloat()
                    val cardRight = cardLeft + frame.width.roundToInt().toFloat()
                    val cardBottom = cardTop + frame.height.roundToInt().toFloat()
                    val cardSize = Size((cardRight - cardLeft).coerceAtLeast(1f), (cardBottom - cardTop).coerceAtLeast(1f))
                    // 形状用卡片自己的 panelShape：悬浮四角圆，固定键盘上下两角按设置。
                    val cardOutline = panelShape.createOutline(cardSize, layoutDirection, this)
                    val cardPath = Path().apply {
                        addOutline(cardOutline)
                        translate(Offset(cardLeft, cardTop))
                    }
                    val cornerRadius = FloatingKeyboardCardCorner.toPx().coerceAtMost(minOf(cardSize.width, cardSize.height) / 2f)
                    val bottomRadius = if (floatingMode || roundedBottom) cornerRadius else 0f
                    val topRadius = if (floatingMode || roundedTop) cornerRadius else 0f
                    // 正在拖的把手放大一点，其余保持原样。
                    fun emphasis(vararg handles: ResizeHandle) = if (dragEdge in handles) RESIZE_HANDLE_ACTIVE_SCALE else 1f

                    // 线、角标、把手全部裁在卡片轮廓里：沿轮廓画 2 倍宽的描边，露出的那一半外沿正好压在卡片边上。
                    clipPath(cardPath) {
                        // 1. 整圈边框。
                        drawPath(cardPath, accentColor.copy(alpha = 0.55f), style = Stroke(stroke * 2f))
                        // 2. 四角角标：贴着卡片圆角的一段弧，沿两条直边各延伸 cornerLeg，圆头；直角处是 L。
                        fun corner(cornerX: Float, cornerY: Float, dirX: Float, dirY: Float, radius: Float, grow: Float) {
                            // 四个角的臂长一致：直角（底边不圆）的角把圆角那段长度补到直臂上。
                            val r = radius.coerceAtMost(bracketLeg * grow)
                            val leg = (bracketLeg + (cornerRadius - r)) * grow
                            val path = Path().apply {
                                moveTo(cornerX + dirX * (r + leg), cornerY)
                                lineTo(cornerX + dirX * r, cornerY)
                                if (r > 0f) {
                                    val cx = cornerX + dirX * r
                                    val cy = cornerY + dirY * r
                                    val start = if (dirY > 0f) 270f else 90f
                                    val sweep = if (dirX * dirY > 0f) -90f else 90f
                                    arcTo(Rect(cx - r, cy - r, cx + r, cy + r), start, sweep, forceMoveTo = false)
                                } else {
                                    lineTo(cornerX, cornerY)
                                }
                                lineTo(cornerX, cornerY + dirY * (r + leg))
                            }
                            drawPath(path, accentColor.copy(alpha = if (grow > 1f) 1f else 0.8f), style = Stroke(bracketThickness * grow * 2f,
                                cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        // 3. 四边中间的调节条：外沿贴卡片边，往里长；拖动放大也只往里长。
                        fun pill(centerX: Float, centerY: Float, horizontal: Boolean, grow: Float) {
                            val thickness = pillThickness * grow
                            val length = (if (horizontal) pillLength else sidePillLength) * grow
                            // 画成以卡片边为中线、2 倍厚的圆角条，外面一半被裁掉。
                            val rect = if (horizontal) Rect(centerX - length / 2f, centerY - thickness, centerX + length / 2f, centerY + thickness)
                                else Rect(centerX - thickness, centerY - length / 2f, centerX + thickness, centerY + length / 2f)
                            drawRoundRect(accentColor, rect.topLeft, rect.size, CornerRadius(thickness))
                        }
                        pill(cardSize.width / 2f + cardLeft, cardTop, true, emphasis(ResizeHandle.TOP))
                        // 悬浮键盘的底边中段是移动条，不是缩放把手；固定键盘的底边调底部留白。
                        if (!floatingMode && bottomHandleY >= frame.bottom) {
                            pill(cardSize.width / 2f + cardLeft, cardBottom, true, emphasis(ResizeHandle.BOTTOM))
                        } else if (!floatingMode) {
                            // 抬离手势区的把手整条画在框里，粗细、长度与贴边时露出的一样。
                            val grow = emphasis(ResizeHandle.BOTTOM)
                            val thickness = pillThickness * grow
                            val length = pillLength * grow
                            drawRoundRect(accentColor, Offset(cardSize.width / 2f + cardLeft - length / 2f,
                                bottomHandleY - thickness / 2f), Size(length, thickness), CornerRadius(thickness / 2f))
                        }
                        if (splitGapPx > 0f) {
                            // 分体：外侧贴边不可拖，改为拖内侧两条竖边对称调整中缝。
                            listOf(splitInnerLeft, splitInnerRight).forEach { x ->
                                drawLine(accentColor, Offset(x, cardTop), Offset(x, cardBottom), stroke)
                                drawRoundRect(accentColor, Offset(x - pillThickness / 2f, frame.centerY - sidePillLength / 2f),
                                    Size(pillThickness, sidePillLength), CornerRadius(pillThickness / 2f))
                            }
                        } else {
                            pill(cardLeft, frame.centerY, false, emphasis(ResizeHandle.LEFT))
                            pill(cardRight, frame.centerY, false, emphasis(ResizeHandle.RIGHT))
                        }
                        corner(cardLeft, cardTop, 1f, 1f, topRadius, emphasis(ResizeHandle.TOP_LEFT))
                        corner(cardRight, cardTop, -1f, 1f, topRadius, emphasis(ResizeHandle.TOP_RIGHT))
                        corner(cardLeft, cardBottom, 1f, -1f, bottomRadius, emphasis(ResizeHandle.BOTTOM_LEFT))
                        corner(cardRight, cardBottom, -1f, -1f, bottomRadius, emphasis(ResizeHandle.BOTTOM_RIGHT))
                    }
                    // 悬浮键盘外四角的弧形角标：和平时点亮的悬浮键盘同一个样子，调节界面里一直亮着，
                    // 拖边、拖角、移动时跟着卡片走。固定键盘贴着屏幕边，外面没有地方画，只留框内角标。
                    if (floatingMode) {
                        val gap = PositionHighlightGap.toPx()
                        drawPositionCorners(Offset(cardLeft - gap, cardTop - gap),
                            Size(cardSize.width + gap * 2f, cardSize.height + gap * 2f), accentColor, 1f, cornerGlass)
                    }
                }
                .pointerInput(editingOpacity, floatingMode, isLandscape, hostMaxWidth, hostMaxHeight, splitActive,
                    previewState.fixedBottomInsetDp, defaultBottomPaddingDp) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (editingOpacity) {
                            down.consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        val hit = RESIZE_EDGE_HIT_DP.dp.toPx()
                        val cornerHit = RESIZE_CORNER_HIT_DP.dp.toPx()
                        val gestureViewRect = currentViewRect
                        val gestureRect = currentPreviewRect ?: dragRect ?: gestureViewRect
                        val gestureFloating = floatingMode && currentPreviewRect != null
                        val gestureBottomHandleY = if (gestureFloating) gestureRect.bottom else
                            fixedBottomHandleY(gestureRect, currentGestureZoneTop, BOTTOM_HANDLE_GESTURE_CLEARANCE_DP.dp.toPx())
                        dragEdge = resizeHandleAt(gestureRect, down.position, hit, gestureFloating, cornerHit, gestureBottomHandleY)
                        // 在固定模式直接拖提示也走同一个留白手势，提示两侧仍是原来的键盘移动区。
                        if (!floatingMode && dragEdge == ResizeHandle.NONE) {
                            paddingHintCoords?.takeIf { it.isAttached }?.let { hint ->
                                val local = hint.windowToLocal(overlayOrigin + down.position)
                                if (local.x in 0f..hint.size.width.toFloat() && local.y in 0f..hint.size.height.toFloat())
                                    dragEdge = ResizeHandle.BOTTOM
                            }
                        }
                        // 分体：内侧竖边优先；外侧贴边，左右边与四角只保留高度方向。
                        var splitEdge = 0
                        val splitStartGap = if (splitActive) currentSplitGapFraction(gestureRect) * gestureRect.width else 0f
                        if (splitActive) {
                            val (edgeLeft, edgeRight) = currentSplitEdges
                            val toLeft = kotlin.math.abs(down.position.x - edgeLeft)
                            val toRight = kotlin.math.abs(down.position.x - edgeRight)
                            if (down.position.y in gestureRect.top..gestureRect.bottom && minOf(toLeft, toRight) <= hit) {
                                splitEdge = if (toLeft <= toRight) -1 else 1
                            }
                            dragEdge = when (dragEdge) {
                                ResizeHandle.LEFT, ResizeHandle.RIGHT -> ResizeHandle.NONE
                                ResizeHandle.TOP_LEFT, ResizeHandle.TOP_RIGHT -> ResizeHandle.TOP
                                ResizeHandle.BOTTOM_LEFT, ResizeHandle.BOTTOM_RIGHT -> ResizeHandle.BOTTOM
                                else -> dragEdge
                            }
                            if (splitEdge != 0) dragEdge = ResizeHandle.NONE
                        }

                        val session = currentPreviewState
                        val stableBounds = session.bounds ?: gestureViewRect
                        val fixedRange = session.fixedHeightRange ?:
                            keyboardHeightBounds(configuration.screenHeightDp, isLandscape)
                        // 框外不是移动区域；框内空白和底部拖条仍可移动，控件自己消费触摸。
                        if (dragEdge == ResizeHandle.NONE && splitEdge == 0 &&
                            (down.position.x < gestureRect.left || down.position.x > gestureRect.right ||
                                down.position.y < gestureRect.top || down.position.y > gestureRect.bottom)) {
                            return@awaitEachGesture
                        }
                        val floatingHeightRange = floatingResizeHeightBounds(hostMaxHeight.value.roundToInt(), isLandscape)
                        val dragBarDp = if (gestureFloating) footerDp else 0
                        // 手机基准下限与可用区域取小：平板横屏不允许因为"按比例算"反而比手机更小。
                        val availableWidthDp = (stableBounds.width / density.density).roundToInt()
                        val availableHeightDp = (stableBounds.height / density.density).roundToInt()
                        val squareSpacing = com.kingzcheung.xime.settings.KeysConfigHelper.getKeyboardKeyConfig()
                            .spacingFor(session.squareSnap?.section ?: "qwerty")
                        // 悬浮最低高度和点亮四角直接拖用同一个（11-03「通过不同方法调整的最小尺寸不一致」）。
                        // 下限按屏幕短边算（11-03），横竖屏同一个值。
                        val screenShortSideDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
                        val minContentHeightDp = floatingResizeFloorContentDp(screenShortSideDp, availableHeightDp)
                        val minHeightPx = ((if (gestureFloating) minContentHeightDp else
                            fixedRange.first + session.bottomPaddingDp) + dragBarDp) * density.density
                        val screenMaxHeightPx = ((if (gestureFloating) floatingHeightRange.last else
                            fixedRange.last + session.bottomPaddingDp) + dragBarDp) * density.density
                        val baseMinWidthPx = if (gestureFloating) {
                            floatingResizeFloorWidthDp(screenShortSideDp, availableWidthDp).toFloat() * density.density
                        } else gestureRect.width
                        val maxWidthPx = if (gestureFloating) {
                            minOf(stableBounds.width, maxWidthDp.toFloat() * density.density)
                        } else gestureRect.width
                        val aspect = session.aspectLimits

                        var workingRect = gestureRect
                        var rawResizeRect = gestureRect
                        val fixedMove = FixedKeyboardMoveGesture(gestureRect, stableBounds, 12.dp.toPx())
                        var workingPadding = session.bottomPaddingDp.toFloat()
                        var didDrag = false
                        // 上拉拉满已经变成悬浮、交给外层跟手：这一段手势剩下的移动不再改固定键盘。
                        var followed = false
                        var lastChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        // 进入吸附位置振动一次；离开后再次进入才再振动。
                        var engagedSnap: String? = null
                        fun snapFeedback(target: String?) {
                            if (target != null && target != engagedSnap) currentSnapFeedback()
                            engagedSnap = target
                        }
                        // 中缝吸附点：自动缝宽（标定键距）和按键正方形的缝宽。
                        val splitWidthDp = gestureRect.width / density.density
                        val splitGutterDp = SplitKeyboardGeometry.gutterDp(floatingMode)
                        val splitRange = SplitKeyboardGeometry.gapRange(splitWidthDp, splitGutterDp)
                        val splitTargets = if (splitEdge == 0) emptyMap() else buildMap {
                            put("split-default", SplitKeyboardGeometry.defaultGap(splitWidthDp, splitGutterDp, session.splitPitchDp))
                            val extra = (if (gestureFloating) dragBarDp else session.bottomPaddingDp) * density.density
                            fun difference(fraction: Float) = KeyboardSquareSnap(10f, true, false, fraction)
                                .capDifference(gestureRect, density.density, extra, gestureFloating, squareSpacing)
                            var low = splitRange.start
                            var high = splitRange.endInclusive
                            if (difference(low) >= 0f && difference(high) <= 0f) {
                                repeat(24) {
                                    val mid = (low + high) / 2f
                                    if (difference(mid) > 0f) low = mid else high = mid
                                }
                                put("split-square", (low + high) / 2f)
                            }
                        }
                        var splitDrag = 0f
                        var pull = Offset.Zero
                        // Snap band grows with the keyboard: 6dp on phones, up to 16dp on tablets.
                        val snapBandDp = (gestureRect.width / density.density * 0.012f).coerceIn(6f, 16f)

                        fun applyDelta(amount: Offset) {
                            if (followed) return
                            didDrag = true
                            if (splitEdge != 0) {
                                splitDrag += amount.x
                                val raw = (splitStartGap + 2f * splitEdge * splitDrag) / gestureRect.width
                                var fraction = raw.coerceIn(splitRange.start, splitRange.endInclusive)
                                // Past a limit the edge stops; drop the excess so reversing moves it at once.
                                if (fraction != raw) splitDrag = (fraction * gestureRect.width - splitStartGap) / (2f * splitEdge)
                                val tolerance = snapBandDp.dp.toPx() / gestureRect.width
                                val target = splitTargets.entries.minByOrNull { kotlin.math.abs(it.value - fraction) }
                                    ?.takeIf { kotlin.math.abs(it.value - fraction) <= tolerance }
                                if (target != null) fraction = target.value
                                snapFeedback(target?.key)
                                session.onSplitGapChange(fraction)
                                return
                            }
                            if (gestureFloating) {
                                val proposed = rawResizeRect.dragBy(
                                    handle = dragEdge,
                                    dx = amount.x,
                                    dy = amount.y,
                                    bounds = stableBounds,
                                    minWidth = minOf(baseMinWidthPx, gestureRect.width),
                                    minHeight = minOf(minHeightPx, gestureRect.height),
                                    maxWidth = maxWidthPx,
                                    maxHeight = screenMaxHeightPx,
                                )
                                val cardMovedY = proposed.top - rawResizeRect.top
                                rawResizeRect = proposed
                                val reachable = if (dragEdge == ResizeHandle.NONE) proposed else
                                    gestureRect.resizeWithReachableWidth(proposed, dragEdge, stableBounds, aspect,
                                        density.density, dragBarDp * density.density)
                                workingRect = if (dragEdge == ResizeHandle.NONE) proposed else
                                    reachable.snapSquareKeys(dragEdge, session.squareSnap, stableBounds, density.density,
                                            dragBarDp * density.density, true, squareSpacing, thresholdDp = snapBandDp)
                                snapFeedback(if (workingRect != reachable) "square" else null)
                                dragRect = workingRect
                                currentOnPreviewRectChange(workingRect)
                                if (dragEdge == ResizeHandle.NONE) dragTowardDock(workingRect, stableBounds, amount, cardMovedY)
                                currentWidthDp = (workingRect.width / density.density)
                                currentHeightDp = (workingRect.height / density.density) - footerDp
                            } else {
                                when (dragEdge) {
                                    ResizeHandle.TOP, ResizeHandle.TOP_LEFT, ResizeHandle.TOP_RIGHT,
                                    ResizeHandle.LEFT, ResizeHandle.RIGHT, ResizeHandle.BOTTOM_LEFT, ResizeHandle.BOTTOM_RIGHT -> {
                                        val edge = when (dragEdge) {
                                            ResizeHandle.BOTTOM_LEFT -> ResizeHandle.LEFT
                                            ResizeHandle.BOTTOM_RIGHT -> ResizeHandle.RIGHT
                                            else -> dragEdge
                                        }
                                        val proposed = rawResizeRect.dragBy(
                                            handle = edge,
                                            dx = amount.x,
                                            dy = amount.y,
                                            bounds = stableBounds,
                                            minWidth = minOf(floatingResizeMinWidthDp(availableWidthDp) * density.density, gestureRect.width),
                                            minHeight = minOf(minHeightPx, gestureRect.height),
                                            maxWidth = stableBounds.width,
                                            maxHeight = screenMaxHeightPx,
                                        )
                                        rawResizeRect = proposed
                                        val reachable = gestureRect.resizeWithReachableWidth(proposed, edge, stableBounds, aspect,
                                            density.density, workingPadding * density.density)
                                        workingRect = reachable
                                            .snapSquareKeys(edge, session.squareSnap, stableBounds, density.density,
                                                workingPadding * density.density, false, squareSpacing, thresholdDp = snapBandDp)
                                        val snapped = workingRect.snapFixedEdgeToCenter(edge, stableBounds,
                                            floatingResizeMinWidthDp(availableWidthDp) * density.density, 12.dp.toPx())
                                        snapFeedback(when {
                                            snapped != workingRect -> "center"
                                            workingRect != reachable -> "square"
                                            else -> null
                                        })

                                        dragRect = snapped
                                        currentOnPreviewRectChange(snapped)
                                        currentHeightDp = workingRect.height / density.density - workingPadding
                                    }
                                    ResizeHandle.BOTTOM -> {
                                        // 固定键盘底边代表“底部留白”：拖上去增加留白，键盘整体上移，尺寸不变。
                                        val (updated, padding) = adjustFixedPadding(workingRect, workingPadding, -amount.y / density.density)
                                        workingRect = updated
                                        workingPadding = padding
                                        dragRect = workingRect
                                    }
                                    ResizeHandle.NONE -> {
                                        workingRect = fixedMove.move(amount.x)
                                        snapFeedback(if (fixedMove.snapped) "center" else null)
                                        dragRect = workingRect
                                        currentOnPreviewRectChange(workingRect)
                                        // 拖键盘内部空白往上拉，和顶上「拖动移动」胶囊一样过阻力后变悬浮。
                                        if (morphTarget != null) {
                                            pull += amount
                                            val before = undockProgress
                                            undockProgress = modeSwitchPullProgress(-pull.y / 1.dp.toPx(), pull.x / 1.dp.toPx())
                                            if (undockProgress >= 1f && before < 1f) {
                                                currentSnapFeedback()
                                                val change = lastChange
                                                if (change != null) followed = followFromHere(change.id, change.position + overlayOrigin)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isFloatingRotated(rotationState?.floatValue ?: 0f)) gesturePivot = Offset(gestureRect.centerX, gestureRect.centerY)
                        if (!gestureFloating && dragEdge == ResizeHandle.NONE && splitEdge == 0) {
                            morphTarget = currentModeSwitchTarget?.invoke(true, gestureViewRect.width, gestureViewRect.height)
                        }
                        var released = false
                        try {
                            val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                lastChange = change
                                applyDelta(over)
                            }
                            if (start != null) {
                                released = drag(start.id) { change ->
                                    lastChange = change
                                    applyDelta(change.positionChange())
                                    change.consume()
                                }
                            }
                        } finally {
                            val float = !floatingMode && released && morphTarget != null && undockProgress >= 1f
                            val dock = floatingMode && released && morphTarget != null && dockGesture.progress >= 1f
                            endMorph()
                            dragRect = null
                            gesturePivot = null
                            dragEdge = ResizeHandle.NONE
                            if (didDrag) currentPreviewState.onDragEnd()
                            if (float) switchFloating(true)
                            if (dock) switchFloating(false)
                        }
                    }
                },
        )

        morphTarget?.takeIf { morphProgress > 0f }?.let { target ->
            val p = morphProgress
            fun between(start: Float, end: Float) = start + (end - start) * p
            KeyboardModeMorphOutline(Modifier.zIndex(2f),
                ResizeRect(between(frame.left, target.left), between(frame.top, target.top),
                    between(frame.right, target.right), between(frame.bottom, target.bottom)),
                p, if (floatingMode) keyboardPanelShape(false, roundedBottom, roundedTop) else FloatingKeyboardCardShape,
                p >= 1f, "keyboard-resize-mode-switch-preview")
        }

        // 工具面板在键盘框内居中，边缘和控件以外的空白继续接收原来的拖动手势。
        val handleHitPx = RESIZE_EDGE_HIT_DP * density.density
        val defaultControls = resizeControlsRect(frame, density.density, floatingMode,
            dragAreaHeightPx).let { rect ->
            val top = frame.top + hintBandPx
            rect.copy(top = top, bottom = minOf(rect.bottom, bottomHandleY - hintBandPx).coerceAtLeast(top + 1f))
        }
        val controls = if (splitGapPx > 0f && splitInnerRight - splitInnerLeft - 2f * handleHitPx >= 240f * density.density)
            defaultControls.copy(left = splitInnerLeft + handleHitPx, right = splitInnerRight - handleHitPx) else defaultControls
        val controlsWidthDp = with(density) { controls.width.toDp() }
        val controlsHeightDp = with(density) { controls.height.toDp() }
        // 滑条、按钮、图标和文字按同一个倍率跟着键盘大小缩放（手机竖屏键盘上是原来的尺寸）。
        val controlScale = resizeControlScale(controlsWidthDp.value, usableHeight.value - 2f * RESIZE_CORNER_HIT_DP)
        // 滑条和操作共用宽度；宽屏不无限拉长按钮，窄屏仍避开两侧拖动热区。
        val panelWidth = minOf(with(density) { frame.width.toDp() } * 0.8f, controlsWidthDp - 12.dp * controlScale,
            360.dp * controlScale).coerceAtLeast(1.dp)
        val panelInset = 10.dp * controlScale
        val rowGap = 6.dp * controlScale
        val rowLimit = ((controlsHeightDp - panelInset * 2 - rowGap) / 2).coerceAtLeast(1.dp)
        val buttonHeight = minOf(44.dp * controlScale, rowLimit)
        val opacityHeight = minOf(maxOf(44.dp * controlScale, (24f * density.fontScale * controlScale).dp), rowLimit)
        // 滑条的触摸区域比文字和轨道高，计算视觉留白时扣掉它自带的上下空隙。
        val labelHeight = with(density) { (18.sp * controlScale).toDp() }
        val sliderSlack = ((opacityHeight - maxOf(18.dp * controlScale, labelHeight)) / 2).coerceAtLeast(0.dp)
        val controlIconSize = 18.dp * controlScale
        val showButtonLabels = panelWidth - panelInset * 2 >=
            (140f + 120f * density.fontScale).dp * controlScale
        // 按钮和键盘上的键同一个圆角；面板圆角 = 按键圆角 + 边距，和按钮同心；直角按键时面板也是直角。
        val panelRadius = if (keyRadius > 0.dp) keyRadius + panelInset else 0.dp
        val toolShape = RoundedCornerShape(panelRadius)
        val actionShape = RoundedCornerShape(keyRadius)
        Box(
            modifier = Modifier
                .absoluteOffset { IntOffset(controls.left.roundToInt(), controls.top.roundToInt()) }
                .size(controlsWidthDp, controlsHeightDp)
                .testTag("keyboard-resize-controls"),
        ) {
            Column(
                modifier = Modifier.width(panelWidth).align(Alignment.Center)
                    // 拖透明度时底板只包住滑条那一行（下面画），按钮那一行藏起来，位置不动、不跳。
                    .then(if (adjustingOpacity) Modifier else Modifier.background(panelColor, toolShape))
                    .padding(start = panelInset, end = panelInset, top = (panelInset - sliderSlack).coerceAtLeast(0.dp),
                        bottom = panelInset),
                verticalArrangement = Arrangement.spacedBy((rowGap - sliderSlack).coerceAtLeast(0.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 滑条照外框画（用户 2026-11-01 要求跟随主题、和外面边框一致）：轨道和四边把手一样粗、一样的圆头，
                // 走过的一段用把手的强调色，没走过的一段是强调色压在底板上的浅色；「透明度 N%」和按钮文字同字号、同颜色，
                // 放在滑条右边，不压在细轨道中间把它截成两段。
                val thumbSize = 18.dp * controlScale
                val trackHeight = frameStyle.handleThicknessDp.dp
                // 没走过的一段是主题强调色压在底板上的浅色（实心），任何主题下都带主题的颜色，不是灰线。
                val inactiveTrackColor = accentColor.copy(alpha = 0.30f).compositeOver(panelColor)
                val sliderBandTop = (panelInset - sliderSlack).coerceAtLeast(0.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(opacityHeight)
                        .drawBehind {
                            if (!adjustingOpacity) return@drawBehind
                            // 和平时的底板同一圆角、同一边距，只是只包这一行。
                            val dx = panelInset.toPx()
                            val dy = sliderBandTop.toPx()
                            val topLeft = Offset(-dx, -dy)
                            val band = Size(size.width + 2f * dx, size.height + 2f * dy)
                            val corner = CornerRadius(panelRadius.toPx())
                            drawRoundRect(panelColor, topLeft, band, corner)
                        }
                        .testTag("keyboard-resize-opacity-panel"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val sliderColors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = inactiveTrackColor,
                    )
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides opacityHeight) {
                        Slider(
                            value = 1f - opacity,
                            onValueChange = {
                                opacity = 1f - it
                                currentOnPreviewOpacity(opacity)
                            },
                            valueRange = 0f..0.7f,
                            interactionSource = opacityInteraction,
                            colors = sliderColors,
                            // 圆形滑块，自己画，按住、拖动都不变形。
                            thumb = {
                                Box(Modifier.size(thumbSize, opacityHeight), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(thumbSize).clip(CircleShape).background(accentColor)
                                        .testTag("keyboard-opacity-thumb"))
                                }
                            },
                            track = { sliderState ->
                                Spacer(Modifier.fillMaxWidth().height(trackHeight).drawBehind {
                                    // Slider 在两端为滑块各留半个直径。把可见轨道补到整行宽，和首尾按钮平齐。
                                    val halfThumb = thumbSize.toPx() / 2f
                                    val left = -halfThumb
                                    val fullWidth = size.width + 2f * halfThumb
                                    val corner = CornerRadius(size.height / 2f)
                                    drawRoundRect(inactiveTrackColor, Offset(left, 0f), Size(fullWidth, size.height), corner)
                                    val progress = sliderState.coercedValueAsFraction
                                    val rtl = layoutDirection == LayoutDirection.Rtl
                                    val thumbX = size.width * (if (rtl) 1f - progress else progress)
                                    if (progress > 0f) {
                                        val activeWidth = size.width * progress + halfThumb
                                        drawRoundRect(accentColor, Offset(if (rtl) thumbX else left, 0f),
                                            Size(activeWidth, size.height), corner)
                                    }
                                    drawCircle(panelColor, 12.dp.toPx() * controlScale, Offset(thumbX, size.height / 2f))
                                })
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(opacityHeight)
                                .semantics { contentDescription = "键盘透明度" }
                                .testTag("keyboard-opacity-slider"),
                        )
                    }
                    // 轨道两端各探出半个滑块，留够间距再放文字。
                    Spacer(Modifier.width(thumbSize * 0.5f + 6.dp * controlScale))
                    @Composable fun OpacityLabel(text: String, modifier: Modifier) = Text(
                        text = text,
                        modifier = modifier,
                        color = panelText,
                        fontSize = 13.sp * controlScale,
                        lineHeight = 18.sp * controlScale,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.End,
                    )
                    // 按最宽的「透明度 70%」占位，数字变化时滑条长度不跳。
                    Box(contentAlignment = Alignment.CenterEnd) {
                        OpacityLabel("透明度 70%", Modifier.alpha(0f).clearAndSetSemantics { })
                        OpacityLabel("透明度 ${((1f - opacity) * 100).roundToInt()}%",
                            Modifier.testTag("keyboard-opacity-overlay-label"))
                    }
                }

                @Composable
                fun EqualActionButton(
                    modifier: Modifier,
                    enabled: Boolean = true,
                    text: String? = null,
                    description: String,
                    // 非空即开关：文字始终是功能名，开启时用强调色实心填充，任何配色下都能一眼分辨。
                    selected: Boolean? = null,
                    emphasized: Boolean = false,
                    icon: @Composable () -> Unit,
                    onClick: () -> Unit,
                ) {
                    // 和键盘上的键一个样：平时是功能键，开着的开关像亮起的 Shift，「确认」像回车键。
                    val container = when {
                        selected == true -> toggleOnColor
                        emphasized -> enterColor
                        else -> functionColor
                    }
                    val preferredContent = if (selected == true) readableOn(container) else functionTextColor
                    val content = if (contrastRatio(container, preferredContent) >= 4.5f) preferredContent else readableOn(container)
                    androidx.compose.material3.Button(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = modifier.height(buttonHeight).semantics {
                            contentDescription = description
                            if (selected != null) this.selected = selected
                        },
                        shape = actionShape,
                        border = null,
                        elevation = null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = container,
                            contentColor = content,
                            disabledContainerColor = lerp(panelColor, functionColor, 0.5f),
                            disabledContentColor = functionTextColor.copy(alpha = 0.45f),
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp * controlScale, vertical = 0.dp),
                    ) {
                        icon()
                        if (text != null && showButtonLabels) {
                            Spacer(Modifier.width(3.dp * controlScale))
                            // 文字只占剩余宽度：放不下时被裁掉，而不是把整组图标和文字挤出中心。
                            Text(text, modifier = Modifier.weight(1f, fill = false), fontSize = 13.sp * controlScale,
                                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                        }
                    }
                }

                if (editingOpacity) Row(
                    modifier = Modifier.fillMaxWidth().alpha(if (adjustingOpacity) 0f else 1f)
                        .testTag("keyboard-opacity-actions"),
                    horizontalArrangement = Arrangement.spacedBy(rowGap),
                ) {
                    EqualActionButton(modifier = Modifier.weight(1f), description = "返回",
                        icon = { Text("返回") }, onClick = { editingOpacity = false })
                    EqualActionButton(modifier = Modifier.weight(1f), description = "完成",
                        icon = { Text("完成") }, onClick = ::confirmResize)
                } else Row(
                    modifier = Modifier.fillMaxWidth().alpha(if (adjustingOpacity) 0f else 1f)
                        .testTag("keyboard-resize-actions"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(rowGap),
                ) {
                    EqualActionButton(
                        modifier = Modifier.weight(1f),
                        text = "重置",
                        description = "重置",
                        icon = { Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(controlIconSize)) },
                    ) {
                        opacity = 1f
                        currentOnPreviewOpacity(opacity)
                        if (splitActive) previewState.onSplitGapChange(-1f)
                        if (floatingMode) {
                            rotationState?.floatValue = 0f
                            val initial = currentInitialPreviewRect ?: frame
                            if (defaultSize != null) {
                                val bounds = previewState.bounds ?: viewRect
                                val width = if (splitActive) bounds.width else defaultSize.width * density.density
                                val totalHeight = (defaultSize.height + footerDp) * density.density
                                val reset = ResizeRect(bounds.centerX - width / 2f, initial.bottom - totalHeight,
                                    bounds.centerX + width / 2f, initial.bottom).coerceInside(bounds)
                                currentOnPreviewRectChange(reset)
                                currentWidthDp = reset.width / density.density
                                currentHeightDp = reset.height / density.density - footerDp
                            } else currentOnPreviewRectChange(initial)
                        } else {
                            currentBottomPaddingDpState = defaultBottomPaddingDp.toFloat()
                            previewState.onBottomPaddingChange(defaultBottomPaddingDp)
                            currentOnPaddingEdited(defaultBottomPaddingDp)
                            val range = previewState.fixedHeightRange ?:
                                keyboardHeightBounds(configuration.screenHeightDp, isLandscape)
                            val availableWidth = (viewRect.width / density.density).roundToInt()
                            val size = defaultSize ?: protectKeyboardSize(availableWidth, availableWidth,
                                defaultHeightDp.coerceIn(range), 0, previewState.aspectLimits)
                            val height = size.height
                            currentOnPreviewRectChange(fixedKeyboardRect(
                                viewRect.width, viewRect.height, height * density.density, defaultBottomPaddingDp * density.density,
                                previewState.fixedBottomInsetDp * density.density,
                                widthPx = if (splitActive) viewRect.width else size.width * density.density,
                                density = density.density,
                            ))
                            currentHeightDp = height.toFloat()
                        }
                        currentPreviewState.onDragEnd()
                    }

                    EqualActionButton(
                        modifier = Modifier.weight(1f).testTag("keyboard-resize-floating-button"),
                        enabled = onFloatingModeChange != null,
                        text = "悬浮",
                        description = "悬浮键盘",
                        selected = floatingMode,
                        icon = {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(controlIconSize),
                            )
                        },
                    ) { switchFloating(!floatingMode) }

                    EqualActionButton(
                        modifier = Modifier.weight(1f).testTag("keyboard-resize-split-button"),
                        enabled = onSplitKeyboardChange != null && splitKeyboardSupported,
                        text = "分体",
                        description = "分体键盘",
                        selected = splitMode,
                        icon = {
                            Icon(
                                Icons.Default.VerticalSplit,
                                contentDescription = null,
                                modifier = Modifier.size(controlIconSize),
                            )
                        },
                    ) {
                        splitMode = !splitMode
                        onSplitKeyboardChange?.invoke(splitMode)
                        val current = currentPreviewRect ?: frame
                        if (splitMode && splitKeyboardSupported) {
                            val bounds = previewState.bounds ?: viewRect
                            rectBeforeSplit = current
                            currentOnPreviewRectChange(current.copy(left = bounds.left, right = bounds.right))
                        } else if (!splitMode) {
                            // 本次调节里开过分体就退回开之前的样子；一进来就是分体的，退回存着的非分体宽度和位置。
                            (rectBeforeSplit ?: previewState.nonSplitRect)
                                ?.let { currentOnPreviewRectChange(it.copy(top = current.top, bottom = current.bottom)) }
                            rectBeforeSplit = null
                        }
                    }

                    EqualActionButton(
                        modifier = Modifier.weight(1f),
                        text = "确认",
                        description = "确认",
                        emphasized = true,
                        icon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(controlIconSize)) },
                        onClick = ::confirmResize,
                    )
                }
            }
        }

        // 悬浮模式的底部整条移动拖条：最高层级、独立于控件布局，按钮与 slider 不会吃掉它的触摸；
        // 中央只移动位置；两端为四角调整热区，不被此条覆盖。
        val currentOnPositionDragEnd by rememberUpdatedState(onPositionDragEnd)
        val moveKeyboardBy by rememberUpdatedState<(Float, Float) -> Unit> { dxPx, dyPx ->
            val base = dragRect ?: currentPreviewRect
            if (base != null) {
                val moveBounds = currentPreviewState.bounds ?: currentViewRect
                val moved = base.translated(dxPx, if (floatingMode) dyPx else 0f).coerceInside(moveBounds)
                dragRect = moved
                currentOnPreviewRectChange(moved)
                if (floatingMode) dragTowardDock(moved, moveBounds, Offset(dxPx, dyPx), moved.top - base.top)
                // 预览矩形是调节事务的唯一位置源；确认时统一保存，避免同时改宿主偏移。
            }
        }
        @Composable
        fun EdgeHint(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
            Row(
                modifier.background(functionColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = functionTextColor, modifier = Modifier.size(13.dp))
                Text(label, color = functionTextColor, fontSize = 11.sp, lineHeight = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            }
        }
        if (!editingOpacity && !adjustingOpacity && !floatingMode) {
            // 系统手势区抬高把手时，底部提示和工具面板一起给它留位。
            Box(Modifier.absoluteOffset {
                IntOffset(frame.left.roundToInt(), (bottomHandleY - hintBandPx).roundToInt())
            }.size(with(density) { frame.width.toDp() }, hintRowHeight)
                .testTag("keyboard-resize-padding-bar")
                .semantics { contentDescription = "上下拖动调节底部留白" }, contentAlignment = Alignment.Center) {
                // 起手被系统拦走时，仍可在键盘内部点按微调，和拖动应用同一套尺寸及安全下限。
                Row(Modifier.background(functionColor, RoundedCornerShape(6.dp)), verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun Step(icon: ImageVector, description: String, delta: Float) {
                        val padding = previewState.bottomPaddingDp
                        val enabled = if (delta < 0f) padding > 0 else
                            padding < maximumFixedPadding(frame.height / density.density - padding).roundToInt()
                        Box(Modifier.size(32.dp, hintRowHeight).clickable(enabled = enabled) {
                            adjustFixedPadding(frame, previewState.bottomPaddingDp.toFloat(), delta)
                            currentPreviewState.onDragEnd()
                        }, contentAlignment = Alignment.Center) {
                            Icon(icon, description, Modifier.size(15.dp),
                                tint = if (enabled) functionTextColor else functionTextColor.copy(alpha = 0.38f))
                        }
                    }
                    Step(Icons.Default.Remove, "减少底部留白", -2f)
                    EdgeHint(Icons.Default.SwapVert, "底部留白 ${previewState.fixedBottomInsetDp + previewState.bottomPaddingDp} dp",
                        Modifier.onGloballyPositioned { paddingHintCoords = it })
                    Step(Icons.Default.Add, "增加底部留白", 2f)
                }
            }
        }
        if (!editingOpacity && !adjustingOpacity && splitGapPx > 0f) {
            Box(Modifier.absoluteOffset {
                IntOffset(frame.left.roundToInt(), (frame.top + hintInset.toPx()).roundToInt())
            }.size(with(density) { frame.width.toDp() }, hintRowHeight)
                .testTag("keyboard-resize-split-hint"), contentAlignment = Alignment.Center) {
                EdgeHint(Icons.Default.VerticalSplit, "拖动内侧边缘调整间距")
            }
        }
        var movePillCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val floatingMoveGesture = Modifier.pointerInput(floatingMode) {
            detectDragGestures(
                onDragStart = {
                    val base = dragRect ?: currentPreviewRect
                    gesturePivot = base?.takeIf { isFloatingRotated(rotationState?.floatValue ?: 0f) }
                        ?.let { Offset(it.centerX, it.centerY) }
                },
                onDragEnd = {
                    gesturePivot = null
                    val dock = dockGesture.progress >= 1f && morphTarget != null
                    endMorph()
                    dragRect = null
                    currentPreviewState.onDragEnd()
                    currentOnPositionDragEnd?.invoke()
                    if (dock) switchFloating(false)
                },
                onDragCancel = {
                    gesturePivot = null
                    endMorph()
                    dragRect = null
                    currentPreviewState.onDragEnd()
                    currentOnPositionDragEnd?.invoke()
                },
                onDrag = { change, amount ->
                    change.consume()
                    moveKeyboardBy(amount.x, amount.y)
                },
            )
        }
        if (!editingOpacity && !adjustingOpacity && !floatingMode && splitGapPx <= 0f) {
            val centered = kotlin.math.abs(frame.centerX - viewRect.centerX) < 0.5f
            val edgeCentered = kotlin.math.abs(frame.left - viewRect.centerX) < 0.5f ||
                kotlin.math.abs(frame.right - viewRect.centerX) < 0.5f
            val alignmentLabel = if (centered) "已居中" else if (edgeCentered) "边缘已对齐中线" else "未居中"
            Box(Modifier.absoluteOffset {
                IntOffset((frame.left + RESIZE_CORNER_HIT_DP.dp.toPx()).roundToInt(), (frame.top + hintInset.toPx()).roundToInt())
            }.size(with(density) { (frame.width.toDp() - (RESIZE_CORNER_HIT_DP * 2).dp).coerceAtLeast(1.dp) }, hintRowHeight)
                .zIndex(1f).testTag("keyboard-resize-fixed-move-bar")
                .semantics { stateDescription = alignmentLabel }
                .onGloballyPositioned { movePillCoords = it }
                .pointerInput(floatingMode, hostMaxWidth, hostMaxHeight) {
                    var movement: FixedKeyboardMoveGesture? = null
                    var moveSnapped = false
                    var pull = Offset.Zero
                    var followed = false
                    detectDragGestures(onDragStart = {
                        followed = false
                        val base = currentPreviewRect ?: currentViewRect
                        movement = FixedKeyboardMoveGesture(base,
                            currentPreviewState.bounds ?: currentViewRect, 12.dp.toPx())
                        pull = Offset.Zero
                        morphTarget = currentModeSwitchTarget?.invoke(true, currentViewRect.width, currentViewRect.height)
                    }, onDragEnd = {
                        val float = morphTarget != null && undockProgress >= 1f
                        endMorph()
                        movement = null
                        dragRect = null
                        currentPreviewState.onDragEnd()
                        if (float) switchFloating(true)
                    }, onDragCancel = {
                        endMorph()
                        movement = null
                        dragRect = null
                        currentPreviewState.onDragEnd()
                    }) { change, amount ->
                        change.consume()
                        if (followed) return@detectDragGestures
                        movement?.let { gesture ->
                            val moved = gesture.move(amount.x)
                            if (gesture.snapped && !moveSnapped) currentSnapFeedback()
                            moveSnapped = gesture.snapped
                            dragRect = moved
                            currentOnPreviewRectChange(moved)
                        }
                        if (morphTarget != null) {
                            // 上拖有阻力：先过一段死区，再拉满一段距离，渐变框才变成悬浮位置。
                            pull += amount
                            val before = undockProgress
                            undockProgress = modeSwitchPullProgress(-pull.y / 1.dp.toPx(), pull.x / 1.dp.toPx())
                            if (undockProgress >= 1f && before < 1f) {
                                currentSnapFeedback()
                                val coords = movePillCoords?.takeIf { it.isAttached }
                                if (coords != null) followed = followFromHere(change.id, coords.localToWindow(change.position))
                            }
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                EdgeHint(Icons.Default.OpenWith, "拖动移动")
            }
        }
        if (!editingOpacity && !adjustingOpacity && floatingMode && splitGapPx <= 0f) {
            // 悬浮模式也按设计图在顶边放「拖动移动」胶囊，拖它整块移动，和底部拖动条一样。
            Box(Modifier.absoluteOffset {
                IntOffset((frame.left + RESIZE_CORNER_HIT_DP.dp.toPx()).roundToInt(), (frame.top + hintInset.toPx()).roundToInt())
            }.size(with(density) { (frame.width.toDp() - (RESIZE_CORNER_HIT_DP * 2).dp).coerceAtLeast(1.dp) }, hintRowHeight)
                .zIndex(1f).testTag("keyboard-resize-floating-move-pill")
                .semantics { contentDescription = "拖动移动键盘，不改变尺寸" }
                .then(floatingMoveGesture), contentAlignment = Alignment.Center) {
                EdgeHint(Icons.Default.OpenWith, "拖动移动")
            }
        }
        if (!editingOpacity && !adjustingOpacity && floatingMode) {
            // 调节界面里长按底部拖动条也锁定（用户 11-09：不要锁定按钮）：按现在的样子确认并锁住，关掉调节界面；
            // 进度和普通悬浮一样沿卡片边框从底边中点走到顶边中点。
            var holdPhase by remember { mutableStateOf(FloatingLockHoldPhase.Idle) }
            val sweep = rememberFloatingLockSweep(holdPhase, FLOATING_LOCK_HOLD_MS - FLOATING_HOLD_QUIET_MS)
            Spacer(Modifier.absoluteOffset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
                .zIndex(2f).graphicsLayer { rotationZ = rotationState?.floatValue ?: 0f }
                .floatingLockOutline(false, sweep, accentColor))
            Box(
                modifier = Modifier
                    .absoluteOffset {
                        IntOffset((frame.left + RESIZE_CORNER_HIT_DP.dp.toPx()).roundToInt(), (frame.bottom - dragAreaHeightPx).roundToInt())
                    }
                    .size(with(density) { (frame.width.toDp() - (RESIZE_CORNER_HIT_DP * 2).dp).coerceAtLeast(1.dp) }, dragAreaHeightDp.dp)
                    .zIndex(1f)
                    .testTag("keyboard-resize-move-bar")
                    .floatingFooterHold(false, { holdPhase = it }) {
                        confirmResize()
                        com.kingzcheung.xime.settings.SettingsPreferences.setFloatingGeometryLocked(hostView.context, true)
                    }
                    .then(floatingMoveGesture),
            )
        }
        }
        if (!editingOpacity && !adjustingOpacity && floatingMode) {
            val card = previewState.rect ?: frame
            val bottomInset = viewRect.bottom - (previewState.bounds ?: viewRect).bottom
            val viewport = floatingControlViewport(viewRect.width, viewRect.height, overlayOrigin, bottomInset)
            val handle = rotationState?.let { floatingRotateHandleRect(card, rotation, viewport, density.density) }
            if (rotationState != null && handle != null) {
                RotateHandle(handle, card, rotationState, overlayOrigin, viewRect, previewState, functionColor, functionTextColor,
                    accentColor, currentPreviewRectSink, onEnd = { currentPreviewState.onDragEnd() })
            }
        }
    }
}

/** 绕 [pivot] 转 [degrees] 后矩形中心所在处（尺寸不变）；没有轴或没转时原样返回。 */
private fun rotateRectCenter(rect: ResizeRect, pivot: Offset?, degrees: Float): ResizeRect {
    if (pivot == null || !isFloatingRotated(degrees)) return rect
    val (x, y) = rotatePoint(rect.centerX, rect.centerY, pivot.x, pivot.y, degrees)
    return rect.translated(x - rect.centerX, y - rect.centerY)
}

/**
 * 调节界面里和锁定按钮并排的旋转手柄：按住拖动，键盘按手指绕键盘中心转过的角度旋转，
 * ±90°，靠近 0°/±90° 吸附；转到放不下顶部条的角度就停住，位置按旋转规则推回屏内，转回去时回到原处。
 */
@Composable
private fun RotateHandle(
    handleRect: ResizeRect,
    frame: ResizeRect,
    rotationState: MutableFloatState,
    overlayOrigin: Offset,
    viewRect: ResizeRect,
    previewState: KeyboardResizePreviewState,
    functionColor: Color,
    functionTextColor: Color,
    accentColor: Color,
    onRectChange: (ResizeRect) -> Unit,
    onEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val footerDp = LocalConfiguration.current.floatingFooterHeightDp
    var rotating by remember { mutableStateOf(false) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentFrame by rememberUpdatedState(frame)
    val currentOrigin by rememberUpdatedState(overlayOrigin)
    val currentView by rememberUpdatedState(viewRect)
    val currentPreview by rememberUpdatedState(previewState)
    val currentOnRectChange by rememberUpdatedState(onRectChange)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val degrees = rotationState.floatValue
    Box(
        Modifier
            .atFloatingControl(handleRect, density)
            .zIndex(3f)
            .clip(CircleShape)
            .onGloballyPositioned { coords = it }
            .testTag("keyboard-resize-rotate-handle")
            .semantics {
                contentDescription = "按住拖动旋转键盘"
                stateDescription = "${degrees.roundToInt()}°"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val handle = coords?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    down.consume()
                    val start = currentPreview.rect ?: currentFrame
                    val view = currentView
                    val d = density.density
                    val bottomInsetDp = (view.height - (currentPreview.bounds ?: view).bottom).coerceAtLeast(0f) / d
                    fun bounds(deg: Float) = floatingRotatedOffsetBounds(view.width / d, view.height / d,
                        start.width / d, start.height / d, deg, bottomInsetDp, footerDp = footerDp)
                    fun fits(deg: Float) = !isFloatingRotated(deg) || bounds(deg).feasible
                    val centerX = currentOrigin.x + start.centerX
                    val centerY = currentOrigin.y + start.centerY
                    fun angleOf(local: Offset): Float {
                        val p = handle.localToWindow(local)
                        return Math.toDegrees(kotlin.math.atan2((p.y - centerY).toDouble(), (p.x - centerX).toDouble())).toFloat()
                    }
                    val startPointer = angleOf(down.position)
                    val startAngle = rotationState.floatValue
                    rotating = true
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (!change.positionChanged()) continue
                            change.consume()
                            val target = snapFloatingRotation(startAngle + floatingAngleDelta(startPointer, angleOf(change.position)))
                            val next = limitFloatingRotation(rotationState.floatValue, target, ::fits)
                            rotationState.floatValue = next
                            // 每次都从按下时的位置推：转回去时键盘也回到原处，不会一路被挤走。
                            currentOnRectChange(if (!isFloatingRotated(next)) start else {
                                val b = bounds(next)
                                val cx = view.width / 2f + b.clampX((start.centerX - view.width / 2f) / d) * d
                                val bottom = view.height - b.clampY((view.height - start.bottom) / d) * d
                                ResizeRect(cx - start.width / 2f, bottom - start.height, cx + start.width / 2f, bottom)
                            })
                        }
                    } finally {
                        rotating = false
                        currentOnEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(26.dp).graphicsLayer { rotationZ = degrees; alpha = if (rotating) 1f else FLOATING_CONTROL_ALPHA }
            .background((if (rotating) accentColor else functionColor)
            .copy(alpha = if (rotating) .48f else .22f), CircleShape), contentAlignment = Alignment.Center) {
            if (rotating || isFloatingRotated(degrees)) {
                Text("${degrees.roundToInt()}°", color = (if (rotating) readableOn(accentColor) else functionTextColor)
                    .copy(alpha = .82f), fontSize = 11.sp, maxLines = 1, softWrap = false)
            } else {
                Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = null,
                    tint = functionTextColor.copy(alpha = .78f), modifier = Modifier.size(17.dp))
            }
        }
    }
}

/** 调节会话唯一预览矩形；真实键盘、边框、命中全部使用它。 */
internal data class KeyboardResizePreviewState(
    val aspectLimits: KeyboardAspectLimits = KeyboardAspectLimits.Letters,
    val squareSnap: KeyboardSquareSnap? = null,
    val rect: ResizeRect? = null,
    val initialRect: ResizeRect? = null,
    val onRectChange: (ResizeRect) -> Unit = {},
    val bounds: ResizeRect? = null,
    val fixedHeightRange: IntRange? = null,
    val fixedBottomInsetDp: Int = 0,
    val bottomPaddingDp: Int = 0,
    val floatingBottomClearanceDp: Float = 0f,
    val initialBottomPaddingDp: Int = 0,
    val onBottomPaddingChange: (Int) -> Unit = {},
    val onDragEnd: () -> Unit = {},
    /** 分体缝宽：本次调节的预览值或已保存值；null 表示自动。 */
    val splitGapFraction: Float? = null,
    val splitPitchDp: Float? = null,
    val onSplitGapChange: (Float) -> Unit = {},
    val onSplitGapCommit: () -> Unit = {},
    /** 悬浮键盘本次调节中的旋转角；只在悬浮调节时有。 */
    val rotation: MutableFloatState? = null,
    /** 存着的非分体位置（宿主 px）：本次调节开始时已是分体、关掉分体时退回这里；null 时不动。 */
    val nonSplitRect: ResizeRect? = null,
)

internal val LocalKeyboardResizePreviewState = compositionLocalOf { KeyboardResizePreviewState() }

/** Shares only the editor's visibility with the real floating drag bar; geometry stays unchanged. */
internal val LocalKeyboardResizeOpacityEditing = compositionLocalOf<MutableState<Boolean>?> { null }

// 兼容旧测试/调用方；新的 resize 不再依赖测量回读的 cardRect。
internal val LocalKeyboardResizeCardRect = compositionLocalOf<ResizeRect?> { null }

/** WCAG 对比度。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** 黑、白里和底色反差更大的那个。 */
private fun readableOn(background: Color): Color =
    if (contrastRatio(background, Color.Black) >= contrastRatio(background, Color.White)) Color.Black else Color.White
