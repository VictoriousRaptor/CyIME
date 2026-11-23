package com.kingzcheung.xime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 一行的面板输入框（翻译栏、剪贴板搜索）：左右拖动时整行字跟着滚，光标不动，和设置页的搜索框一样。
 *
 * 安卓 11 起 EditText 横着拖会拖光标（系统的「从任意处拖动光标」），翻译栏里字一长，想往回看前面的字只能把光标拖过去（用户 11-22）。
 * 现在横着拖过触摸阈值后，这一下手势由这里接管：先给 EditText 发一个取消（光标、长按菜单都不动），之后只改横向滚动位置。
 * 点一下、长按照旧交给 EditText：点哪光标去哪，长按出粘贴菜单；拖光标下面的小把手也照旧能挪光标。
 *
 * 光标滚出框外时不画：安卓会把框外的光标贴着框边画（Editor 的 clampHorizontalPosition），
 * 拖完后光标看着停在框边的两个字中间，打字却加在末尾（平板 11-22 验收「保护|人」）。
 * 光标回到框里（打字、点一下、拖回来）就照常画。
 *
 * 安卓 10 起光标由这里自己画（[ownCursor]）：系统光标留着、换成透明的（点一下出把手、长按粘贴这些照旧），
 * 看得见的那根竖线按这一行字的排版位置画在选区末尾。系统光标的位置是缓存的，平板上打完字、拖回末尾后
 * 常画在倒数第二个字前面（提交一长串后差好几个字），打字却加在最后（平板 20261506 验收「好|吗」）。
 * 自己画的竖线和字用同一份排版、同一次绘制，不会对不上。安卓 9 换不了光标图，照旧用系统光标。
 *
 * 字清空、打字去了应用输入框时横向滚动回到开头：不然长串翻完清空后，提示「打字，停一下…」按原来的
 * 滚动位置画，前半截被滚走（平板 20261506 验收）。
 */
internal class DragScrollEditText(context: Context) : EditText(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    /** 外面要不要光标（翻译栏：打字去了应用输入框时不要）；真画不画还要看光标在不在框里。 */
    private var cursorWanted = true
    /** 构造完了才动光标：TextView 构造时就会回调 onSelectionChanged、onTextChanged，那时下面的字段还没初始化。 */
    private var ready = false
    private val ownCursor = com.kingzcheung.xime.platform.AndroidPlatform.textCursorDrawable
    private val cursorWidth = maxOf(1, (2 * resources.displayMetrics.density).roundToInt())
    private val cursorPaint = Paint()
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() {
            if (!cursorWanted || !isFocused) return
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    init {
        if (com.kingzcheung.xime.platform.AndroidPlatform.textCursorDrawable) {
            // 系统光标还在（把手、粘贴菜单要靠它），只是看不见
            textCursorDrawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setSize(cursorWidth, 1)
            }
        }
        ready = true
    }

    /** 代替 [setCursorVisible]：光标在框外时先不画，回到框里再画。 */
    fun setCursorWanted(visible: Boolean) {
        if (cursorWanted == visible) return
        cursorWanted = visible
        // 回到栏里时不主动滚：点回来那一下按当前位置落光标，滚了会落到别的字上；打字时 TextView 自己把光标滚进框里
        if (ready && shouldResetTextScroll(text.isNullOrEmpty(), cursorWanted, isFocused)) resetTextScroll()
        applyCursor()
        restartBlink()
    }

    /** 横向滚回这一行的开头（提示语、字都从头看）。 */
    private fun resetTextScroll() {
        if (scrollX != 0) scrollTo(0, scrollY)
    }

    private fun applyCursor() {
        if (!ready) return
        // 自己画光标时系统光标只管把手和菜单，跟着外面要不要；框外不画在 onDraw 里管
        val show = if (ownCursor) cursorWanted else cursorWanted && !cursorOutside()
        if (isCursorVisible != show) isCursorVisible = show
    }

    /** 打字、挪光标后光标先常亮，停下来再闪，和系统光标一样。 */
    private fun restartBlink() {
        if (!ready || !ownCursor) return
        removeCallbacks(blink)
        blinkOn = true
        invalidate()
        if (isAttachedToWindow && isFocused && cursorWanted) postDelayed(blink, BLINK_MS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ready || !ownCursor || !cursorWanted || !blinkOn || !isFocused) return
        val layout = layout ?: return
        val start = selectionStart
        val end = selectionEnd
        if (start < 0 || start != end || end > layout.text.length) return
        val x = layout.getPrimaryHorizontal(end)
        val visible = width - compoundPaddingLeft - compoundPaddingRight
        if (cursorOutsideView(x, scrollX, visible)) return
        val line = layout.getLineForOffset(end)
        val top = extendedPaddingTop + verticalTextOffset(layout)
        // 画布已经按 scrollX 平移过；字从 compoundPaddingLeft 开始排，竖线不出这一行字的可见范围
        val left = (compoundPaddingLeft + x).roundToInt()
            .coerceIn(scrollX + compoundPaddingLeft, maxOf(scrollX + compoundPaddingLeft, scrollX + width - compoundPaddingRight - cursorWidth))
        cursorPaint.color = currentTextColor
        canvas.drawRect(left.toFloat(), (top + layout.getLineTop(line)).toFloat(),
            (left + cursorWidth).toFloat(), (top + layout.getLineBottom(line)).toFloat(), cursorPaint)
    }

    /** 字在框里竖直方向的偏移（和 TextView 按 gravity 摆字一样）。 */
    private fun verticalTextOffset(layout: Layout): Int {
        val vertical = gravity and Gravity.VERTICAL_GRAVITY_MASK
        if (vertical == Gravity.TOP) return 0
        val box = measuredHeight - extendedPaddingTop - extendedPaddingBottom
        val text = layout.height
        if (text >= box) return 0
        return if (vertical == Gravity.BOTTOM) box - text else (box - text) / 2
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (ready && text.isNullOrEmpty()) {
            resetTextScroll()
            // 换成提示语的排版在下一帧，那时再复位一次，免得排版时又按旧位置滚回去
            post { if (this.text.isNullOrEmpty()) resetTextScroll() }
        }
        restartBlink()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (ready && shouldResetTextScroll(text.isNullOrEmpty(), cursorWanted, focused)) resetTextScroll()
        restartBlink()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        if (ready) removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    private fun cursorOutside(): Boolean {
        val layout = layout ?: return false
        val start = selectionStart
        val end = selectionEnd
        if (start < 0 || start != end || end > layout.text.length) return false
        return cursorOutsideView(layout.getPrimaryHorizontal(end), scrollX, width - totalPaddingLeft - totalPaddingRight)
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        applyCursor()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready) return
        // 选区变了以后 TextView 会在下一帧前把光标滚进框里，那时 onScrollChanged 再算；不用滚时这里算
        post { applyCursor() }
        restartBlink()
    }

    @SuppressLint("ClickableViewAccessibility") // 点击照旧交给 EditText（它在抬手时 performClick）
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && isHorizontalDrag(event.x - downX, event.y - downY, touchSlop)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                }
                if (dragging) {
                    scrollTextBy(lastX - event.x)
                    lastX = event.x
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scrollTextBy(dx: Float) {
        val layout = layout ?: return
        if (layout.lineCount == 0) return
        val visible = width - totalPaddingLeft - totalPaddingRight
        val target = clampTextScroll(scrollX + dx.roundToInt(),
            floor(layout.getLineLeft(0)).toInt(), ceil(layout.getLineRight(0)).toInt(), visible)
        if (target != scrollX) scrollTo(target, scrollY)
    }
}

/** 光标亮、灭各多久（和系统光标一样 500 ms）。 */
private const val BLINK_MS = 500L

/** 光标的横坐标（这一行字里的位置）在不在框里看得到的范围外；留 2 像素，贴着框边的不算。 */
internal fun cursorOutsideView(x: Float, scrollX: Int, visibleWidth: Int): Boolean =
    x < scrollX - 2 || x > scrollX + visibleWidth + 2

/** 横向滚动要不要回到开头：字清空了，或者光标不在这个框里（打字去了应用输入框、框没焦点）。 */
internal fun shouldResetTextScroll(textEmpty: Boolean, cursorWanted: Boolean, focused: Boolean): Boolean =
    textEmpty || !cursorWanted || !focused

/** 横着拖：横向超过触摸阈值，且不比竖向少。 */
internal fun isHorizontalDrag(dx: Float, dy: Float, touchSlop: Int): Boolean =
    abs(dx) > touchSlop && abs(dx) >= abs(dy)

/** 横向滚动位置夹在这一行字的范围里：字没超出框就不滚，超出了最多滚到最后一个字贴着右边。 */
internal fun clampTextScroll(target: Int, lineLeft: Int, lineRight: Int, visibleWidth: Int): Int {
    val max = maxOf(lineLeft, lineRight - visibleWidth)
    return target.coerceIn(minOf(lineLeft, max), max)
}
