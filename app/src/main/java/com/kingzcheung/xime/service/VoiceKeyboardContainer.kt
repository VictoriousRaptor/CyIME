package com.kingzcheung.xime.service

import android.content.Context
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

class VoiceKeyboardContainer(
    context: Context,
    private val uiStateProvider: () -> InputUIState,
    private val onUiStateChanged: (InputUIState) -> Unit,
    private val onPerformVibration: (View) -> Unit,
    private val onPerformUndo: () -> Unit,
    private val onPerformSearch: () -> Unit,
    private val onStopRecognition: () -> Unit,
    private val isRecording: () -> Boolean,
    private val setRecording: (Boolean) -> Unit,
    private val onVoiceDismiss: () -> Unit = {},
    private val onTouchCancel: () -> Unit = {},
    private val acceptTouchDown: (Long) -> Boolean = { true },
    private val allowPreviewTouch: (MotionEvent) -> Boolean = { true },
) : FrameLayout(context) {

    private var discardTouchGesture = false
    private var isTrackingVoiceButtons = false
    private var lastLeftActive = false
    private var lastRightActive = false

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }

    fun enableVoiceButtonTracking() {
        isTrackingVoiceButtons = true
    }

    private var edgeExclusion: KeyboardEdgeExclusion? = null

    /** 按键区左右两边挡住系统边缘手势，null 是不挡，见 [keyboardEdgeExclusionBands]。 */
    fun setEdgeExclusion(spec: KeyboardEdgeExclusion?) {
        if (spec == edgeExclusion) return
        edgeExclusion = spec
        applyEdgeExclusion()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) applyEdgeExclusion()
    }

    private fun applyEdgeExclusion() {
        if (!com.kingzcheung.xime.platform.AndroidPlatform.gestureExclusion) return
        // 只设这个容器自己的排除区；键盘调节层设在里面的 Compose 视图上，两者互不覆盖（系统把各视图的合起来）。
        systemGestureExclusionRects = keyboardEdgeExclusionBands(width, height, edgeExclusion, resources.displayMetrics.density)
            .map { android.graphics.Rect(it.left, it.top, it.right, it.bottom) }
    }

    fun updateHeight(heightDp: Int) {
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()
        val params = layoutParams ?: return
        if (params.height != heightPx) {
            params.height = heightPx
            layoutParams = params
            requestLayout()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Android may deliver old touches only after a blocked main thread resumes.
        // Consume the whole stale gesture, including its UP, rather than replaying a key.
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN)
            discardTouchGesture = !acceptTouchDown(ev.downTime) || !allowPreviewTouch(ev)
        if (discardTouchGesture) {
            if (ev?.actionMasked == MotionEvent.ACTION_UP || ev?.actionMasked == MotionEvent.ACTION_CANCEL) discardTouchGesture = false
            return true
        }
        ev?.let(KeyLatencyProbe::touch)
        ev?.let(::logCanceledTouch)
        ev?.let {
            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleActionDown(it)
                }

                MotionEvent.ACTION_UP -> {
                    handleActionUp()
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 系统手势（如三指截图）截走触摸流时，IME 收不到 UP，Compose 手势也不会被取消。
                    // 这里把 cancel 上抛，触发活动键盘 remount 来取消所有进行中的手势协程。
                    handleActionUp()
                    onTouchCancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    handleActionMove(it)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 系统收走的触摸记一行（11-19 荣耀用户：a 和后一个字母快打必丢，日志里没有输入法拒收的记录）。
     * 整段被收走（ACTION_CANCEL，如边缘返回手势）时所有手指一起作废；单根被判为误触（FLAG_CANCELED）时只丢那一根。
     * 只记手指个数、横向位置（占键盘宽的百分比，看是不是贴着屏幕边）和按了多久，不记按的是哪个键。
     */
    private fun logCanceledTouch(ev: MotionEvent) {
        val masked = ev.actionMasked
        val whole = masked == MotionEvent.ACTION_CANCEL
        val one = (masked == MotionEvent.ACTION_POINTER_UP || masked == MotionEvent.ACTION_UP) &&
            ev.flags and MotionEvent.FLAG_CANCELED != 0
        if (!whole && !one) return
        val w = width.coerceAtLeast(1)
        val xs = (0 until ev.pointerCount).joinToString(",") { (ev.getX(it) * 100 / w).toInt().toString() }
        com.kingzcheung.xime.util.FileLogger.w("TouchCancel",
            "${if (whole) "gesture" else "pointer"} pointers=${ev.pointerCount} x%=$xs " +
                "heldMs=${ev.eventTime - ev.downTime} width=$w")
    }

    private fun handleActionDown(ev: MotionEvent) {
        val uiState = uiStateProvider()
        val isVoiceMode = uiState.isVoiceMode && !uiState.voiceSticky

        lastLeftActive = false
        lastRightActive = false

        if (isVoiceMode) {
            val yThreshold = height * 0.6f

            if (ev.y > yThreshold) {
                isTrackingVoiceButtons = true
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(bottomActive = true)
                ))
            }
        }
    }

    private fun handleActionUp() {
        val state = uiStateProvider()

        // 常驻语音（工具栏进入）不拦截触摸：麦克风按钮自行结束语音
        if (state.voiceSticky) {
            isTrackingVoiceButtons = false
            lastLeftActive = false
            lastRightActive = false
            return
        }

        if (state.isVoiceMode || isRecording()) {
            if (state.voiceButtonState.leftActive) {
                onPerformUndo()
            } else if (state.voiceButtonState.rightActive) {
                onPerformSearch()
            }

            if (isRecording()) {
                onStopRecognition()
                setRecording(false)
            }

            if (state.isVoiceMode) {
                onVoiceDismiss()
            }
        }

        isTrackingVoiceButtons = false
        lastLeftActive = false
        lastRightActive = false
    }

    private fun handleActionMove(ev: MotionEvent) {
        val isVoiceMode = uiStateProvider().isVoiceMode && !uiStateProvider().voiceSticky

        if (isVoiceMode && isTrackingVoiceButtons) {
            val yThreshold = height * 0.6f
            val leftButtonEnd = width * 0.25f
            val rightButtonStart = width * 0.75f

            if (ev.y > yThreshold) {
                when {
                    ev.x < leftButtonEnd -> {
                        if (!lastLeftActive) {
                            onPerformVibration(this@VoiceKeyboardContainer)
                            lastLeftActive = true
                        }
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(leftActive = true)
                        ))
                    }
                    ev.x > rightButtonStart -> {
                        if (!lastRightActive) {
                            onPerformVibration(this@VoiceKeyboardContainer)
                            lastRightActive = true
                        }
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(rightActive = true)
                        ))
                    }
                    else -> {
                        lastLeftActive = false
                        lastRightActive = false
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(bottomActive = true)
                        ))
                    }
                }
            } else if (ev.x < leftButtonEnd) {
                if (!lastLeftActive) {
                    onPerformVibration(this@VoiceKeyboardContainer)
                    lastLeftActive = true
                }
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(leftActive = true)
                ))
            } else if (ev.x > rightButtonStart) {
                if (!lastRightActive) {
                    onPerformVibration(this@VoiceKeyboardContainer)
                    lastRightActive = true
                }
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(rightActive = true)
                ))
            } else {
                lastLeftActive = false
                lastRightActive = false
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState()
                ))
            }
        }
    }
}
