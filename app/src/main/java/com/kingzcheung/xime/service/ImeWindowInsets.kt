package com.kingzcheung.xime.service

import android.content.Context
import android.util.Log
import android.view.Window
import android.view.WindowInsets
import android.view.View
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlin.math.roundToInt
import com.kingzcheung.xime.platform.AndroidPlatform
import com.kingzcheung.xime.platform.DeviceVendor

private const val INSETS_TAG = "ImeWindowInsets"

/** 获取导航栏高度（dp）。 */
internal fun tryGetNavBarHeightDp(context: Context, imeWindow: Window?): Int {
    return try {
        if (AndroidPlatform.windowInsetTypes) {
            val decorView = imeWindow?.decorView
            if (decorView != null) {
                val insets = decorView.rootWindowInsets
                if (insets != null) {
                    val px = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars()
                    ).bottom
                    val dp = (px / context.resources.displayMetrics.density).roundToInt()
                    return dp
                }
            }
        }
        val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val dp = if (resId > 0) (context.resources.getDimensionPixelSize(resId) / context.resources.displayMetrics.density).roundToInt() else 0
        return dp
    } catch (e: Exception) {
        Log.w(INSETS_TAG, "NavBar: error", e)
        0
    }
}

/** 获取可见导航栏高度（dp）；手势导航时只算手势条，与 [extractBottomInset] 同一规则。 */
internal fun tryGetVisibleNavBarHeightDp(context: Context, imeWindow: Window?): Int {
    return try {
        if (AndroidPlatform.windowInsetTypes) {
            val decorView = imeWindow?.decorView
            if (decorView != null) {
                val insets = decorView.rootWindowInsets
                if (insets != null) {
                    val px = visibleBottomPx(readBottomNav(insets, context, imeWindow),
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
                    val dp = if (px > 0) (px / context.resources.displayMetrics.density).roundToInt() else 0
                    return dp
                }
            }
        }
        0
    } catch (e: Exception) {
        Log.w(INSETS_TAG, "NavBar: visibleOnly error", e)
        0
    }
}

/** 获取状态栏高度（dp）。 */
internal fun tryGetStatusBarHeightDp(context: Context, imeWindow: Window?): Int {
    return try {
        if (AndroidPlatform.windowInsetTypes) {
            val decorView = imeWindow?.decorView
            if (decorView != null) {
                val insets = decorView.rootWindowInsets
                if (insets != null) {
                    val px = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars()
                    ).top
                    if (px > 0) return (px / context.resources.displayMetrics.density).roundToInt()
                }
            }
        }
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) (context.resources.getDimensionPixelSize(resId) / context.resources.displayMetrics.density).roundToInt() else 0
    } catch (e: Exception) { 0 }
}

/**
 * 底部导航是哪一种。键盘高度、系统建议值、悬浮键盘下限、调节把手都用同一个结论（11-19 盘点：原来三处各有一套判断，
 * 自动档又把五条线索用「或」叠在一起，修一次「有缝」就多一台被挡的机型）。
 */
internal enum class BottomNavKind(val label: String) {
    TASKBAR("任务栏"), IME_BUTTONS("系统按钮条"), SYSTEM_BUTTONS("系统盖在键盘上的按钮"), BUTTONS("三键"), GESTURE("手势"), UNKNOWN("说不清")
}

/** 一次读到的底部数值（px）和系统开关；读不到的开关为 null。 */
internal data class BottomNavReading(
    val navPx: Int,
    val tappablePx: Int,
    val mandatoryPx: Int,
    val cutoutPx: Int,
    /** 本窗口里系统画的「切换输入法 / 收起键盘」按钮条高度。 */
    val imeButtonsPx: Int = 0,
    val navigationMode: Int? = null,
    /** 厂商自己的全面屏手势开关，见 [vendorGestureNavigationOn]。 */
    val vendorGesture: Boolean? = null,
    /** navigation_mode 写得可靠的厂商（[gestureStripVerifiedVendor]）。 */
    val verifiedVendor: Boolean = false,
    val largeScreen: Boolean = false,
    /** 三星手势提示开着、又开了「显示隐藏键盘按钮」：按钮画在键盘下面那条里。 */
    val samsungHideButton: Boolean = false,
    /** 横屏。三星说明里「显示隐藏键盘按钮」只在竖屏显示。 */
    val landscape: Boolean = false,
    /** 手势导航时系统给的手势条高度。 */
    val gestureBarPx: Int = 0,
    val density: Float = 1f,
)

/** 判断结果和命中的是决策表第几步（诊断行里写出来）。 */
internal data class BottomNavDecision(val kind: BottomNavKind, val step: Int)

/**
 * 决策表，按顺序第一条命中就停，每一步只有一个结果：
 * 1 大屏有导航栏 → 任务栏；
 * 2 厂商手势开关说关（且 navigation_mode 没说手势），或 navigation_mode 可靠的厂商写 0/1 → 三键；
 * 3 本窗口里系统画了按钮条 → 按钮条（系统只在手势导航时画；开关明说三键时仍按整条三键留，只留按钮条会挡住三键）；
 * 4 系统在键盘窗口外面画了一层按钮、盖在键盘上 → 给它留位（11-21）：可点区域比导航栏高出一截（三星 One UI 8.5 手势 +
 *   「显示隐藏键盘按钮」实测导航栏 15dp、可点区域 48dp），或三星竖屏手势提示开着又开了「显示隐藏键盘按钮」（老 One UI 那条
 *   只有 ⌄，可点区域报不报没量过）。11-03 以为键盘会盖住这个按钮，三星用户截图是按钮盖住了最下一排；
 * 5 navigation_mode=2（厂商开关没说关），或厂商手势开关说开 → 手势；
 * 开关读不到或互相矛盾才看 inset：6 可点区域占满整条 → 三键；7 可点区域没占满，且强制手势区比导航栏矮或整条只有手势条高 → 手势；
 * 8 其余说不清 → 整条留着（用户可选「贴到最底」）。
 */
internal fun classifyBottomNav(r: BottomNavReading): BottomNavDecision {
    val slack = (4 * r.density).roundToInt()
    val gestureBarMax = (GESTURE_BAR_MAX_DP * r.density).roundToInt()
    fun at(kind: BottomNavKind, step: Int) = BottomNavDecision(kind, step)
    return when {
        r.largeScreen && r.navPx > 0 -> at(BottomNavKind.TASKBAR, 1)
        r.vendorGesture == false && r.navigationMode != 2 ||
            r.verifiedVendor && r.vendorGesture != true && (r.navigationMode == 0 || r.navigationMode == 1) ->
            at(BottomNavKind.BUTTONS, 2)
        r.imeButtonsPx > 0 -> at(BottomNavKind.IME_BUTTONS, 3)
        r.tappablePx > r.navPx + slack || r.samsungHideButton && !r.landscape -> at(BottomNavKind.SYSTEM_BUTTONS, 4)
        r.navigationMode == 2 && r.vendorGesture != false || r.vendorGesture == true -> at(BottomNavKind.GESTURE, 5)
        r.navPx <= 0 && r.tappablePx <= 0 -> at(BottomNavKind.GESTURE, 7)
        r.tappablePx > 0 && r.tappablePx >= r.navPx - slack -> at(BottomNavKind.BUTTONS, 6)
        r.tappablePx < r.navPx && (r.mandatoryPx < r.navPx || r.navPx <= gestureBarMax) -> at(BottomNavKind.GESTURE, 7)
        else -> at(BottomNavKind.UNKNOWN, 8)
    }
}

private fun WindowInsets.navBottomPx(): Int = if (AndroidPlatform.windowInsetTypes)
    getInsets(WindowInsets.Type.navigationBars()).bottom else @Suppress("DEPRECATION") systemWindowInsetBottom

private fun WindowInsets.tappableBottomPx(): Int = when {
    AndroidPlatform.windowInsetTypes -> getInsets(WindowInsets.Type.tappableElement()).bottom
    AndroidPlatform.gestureInsets -> @Suppress("DEPRECATION") tappableElementInsets.bottom
    else -> 0
}

private fun WindowInsets.mandatoryBottomPx(): Int = when {
    AndroidPlatform.windowInsetTypes -> getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
    AndroidPlatform.gestureInsets -> @Suppress("DEPRECATION") mandatorySystemGestureInsets.bottom
    else -> 0
}

/** 读一次底部数值和系统开关。[window] 为 null 时看不到系统按钮条。 */
internal fun readBottomNav(insets: WindowInsets, context: Context, window: Window?): BottomNavReading {
    val resolver = context.contentResolver
    val vendor = AndroidPlatform.vendor
    val gestureHint = readSetting { android.provider.Settings.Global.getInt(resolver, "navigation_bar_gesture_hint") }
    val hideButton = vendor == DeviceVendor.SAMSUNG && gestureHint != 0 &&
        readSetting { android.provider.Settings.Global.getInt(resolver, "navigation_bar_button_to_hide_keyboard") } == 1
    return BottomNavReading(
        navPx = insets.navBottomPx(),
        tappablePx = insets.tappableBottomPx(),
        mandatoryPx = insets.mandatoryBottomPx(),
        cutoutPx = insets.displayCutout?.safeInsetBottom ?: 0,
        imeButtonsPx = window?.let(::imeNavButtonHeightPx) ?: 0,
        navigationMode = readSetting { android.provider.Settings.Secure.getInt(resolver, "navigation_mode") },
        vendorGesture = vendorGestureNavigationOn(vendor) { global, key ->
            readSetting {
                if (global) android.provider.Settings.Global.getInt(resolver, key)
                else android.provider.Settings.Secure.getInt(resolver, key)
            }
        },
        verifiedVendor = gestureStripVerifiedVendor(vendor),
        largeScreen = context.resources.configuration.smallestScreenWidthDp >= 600,
        samsungHideButton = hideButton,
        landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
        gestureBarPx = gestureBarHeightPx(context, hintHidden = gestureHint == 0),
        density = context.resources.displayMetrics.density,
    )
}

/**
 * 多类型检测底部 inset（系统建议值）。
 * 与主流输入法一致：navigationBars、mandatorySystemGestures 与 tappableElement 取最大，
 * 全部为空时再回退 systemGestures。手势导航下只留手势条，见 [visibleBottomPx]。
 */
internal fun extractBottomInset(
    insets: WindowInsets,
    imeWindow: Window? = null,
): Int {
    if (!AndroidPlatform.windowInsetTypes) {
        @Suppress("DEPRECATION")
        return insets.systemWindowInsetBottom.coerceAtLeast(0)
    }
    val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
    val tappable = insets.getInsets(WindowInsets.Type.tappableElement()).bottom
    val mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
    val gestures = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
    val primary = maxOf(nav, mandatory, tappable)
    val full = if (primary > 0) primary else gestures
    val pick = if (imeWindow == null || full <= 0) full
        else visibleBottomPx(readBottomNav(insets, imeWindow.context, imeWindow), full)
    Log.d(INSETS_TAG, "bottom: nav=$nav tappable=$tappable mandatory=$mandatory gestures=$gestures pick=$pick")
    return pick
}

/**
 * 系统建议值里底部系统栏留多高：判断是手势、又是 navigation_mode 写得可靠的厂商（三星）、键盘下面没画隐藏键盘按钮时，
 * 只留手势条；其余整条。vivo 等手势导航时也在键盘下面画「切换输入法」按钮，没核对过的厂商建议值照旧整条。
 */
internal fun visibleBottomPx(r: BottomNavReading, fullPx: Int): Int {
    if (fullPx <= 0) return 0
    val kind = classifyBottomNav(r).kind
    if (kind != BottomNavKind.GESTURE || !r.verifiedVendor || r.samsungHideButton || r.gestureBarPx <= 0) return fullPx
    return minOf(fullPx, r.gestureBarPx)
}

/**
 * 手势导航时能不能把建议值缩到手势条：只认核对过底部有没有系统按钮、navigation_mode 写得可靠的厂商（三星）。
 * 其他厂商（如 vivo 在键盘下面画「切换输入法」按钮）建议值整条留着。
 */
internal fun gestureStripVerifiedVendor(vendor: DeviceVendor): Boolean =
    vendor == DeviceVendor.SAMSUNG

/** 手势导航时键盘下面最多留多高（dp）：手势条本身；也是「整条只有手势条高」的上限。 */
internal const val GESTURE_BAR_MAX_DP = 24

/** 手势提示（手势条）隐藏时只留一小截，让最下一排避开屏幕圆角。 */
internal const val GESTURE_HINT_HIDDEN_DP = 12

/** 系统的手势条高度：手势导航时 navigation_bar_height 是手势条那一小条，限制在 [GESTURE_BAR_MAX_DP] 内。 */
private fun gestureBarHeightPx(context: Context, hintHidden: Boolean): Int {
    val max = ((if (hintHidden) GESTURE_HINT_HIDDEN_DP else GESTURE_BAR_MAX_DP) *
        context.resources.displayMetrics.density).roundToInt()
    val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    val px = if (resId > 0) runCatching { context.resources.getDimensionPixelSize(resId) }.getOrDefault(0) else 0
    return if (px in 1..max) px else max
}

/** Pixel 等机型手势导航时，系统在输入法窗口底部自己画返回/切换输入法按钮（NavigationBarFrame）。 */
private fun imeNavButtonHeightPx(imeWindow: Window): Int = imeNavButtonHeightInDecorPx(imeWindow.decorView)

/**
 * 系统在输入法窗口里画的那条「切换输入法 / 收起键盘」按钮的上沿（px，[view] 所在窗口的坐标）；没画时为 null。
 * 键盘调节的底边把手要放在它上面：落在按钮那条里会被系统按钮盖住，也按不到（11-10 用户截图）。
 */
internal fun imeNavButtonsTopInWindowPx(view: View): Int? {
    val decor = view.rootView
    return imeNavButtonHeightInDecorPx(decor).takeIf { it > 0 }?.let { decor.height - it }
}

private fun imeNavButtonHeightInDecorPx(decor: View): Int {
    fun find(view: android.view.View): Int {
        val name = view.javaClass.name
        if ((name == "android.inputmethodservice.navigationbar.NavigationBarFrame" ||
                name == "android.inputmethodservice.NavigationBarFrame") && view.isShown && view.width >= view.height) {
            val frame = android.graphics.Rect()
            val windowBounds = android.graphics.Rect()
            if (view.getGlobalVisibleRect(frame) && decor.getGlobalVisibleRect(windowBounds)) {
                return (windowBounds.bottom - frame.top).coerceIn(0, windowBounds.height())
            }
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
            val height = find(view.getChildAt(i))
            if (height > 0) return height
        }
        return 0
    }
    return runCatching { find(decor) }.getOrDefault(0)
}

/** 系统可以先派发 insets，再摆放输入法导航按钮。只在这一帧重查，取到真实位置后再修正。 */
internal fun View.observeKeyboardBottomInsets(window: Window?, update: (WindowInsets) -> Unit) {
    var pending: OneShotPreDrawListener? = null
    setOnApplyWindowInsetsListener { view, insets ->
        pending?.removeListener()
        val beforeLayout = window?.let(::imeNavButtonHeightPx)
        update(insets)
        if (window != null) pending = view.doOnPreDraw {
            pending = null
            if (imeNavButtonHeightPx(window) != beforeLayout) update(insets)
        }
        view.onApplyWindowInsets(insets)
    }
}

/**
 * 固定键盘底部下限（px）。「底部位置」三档：贴到最底 = 只躲屏幕底部缺口（系统画的按钮条也盖住，说明里写了）；
 * 留出导航按钮 = 整条导航栏；自动 = 决策表 [classifyBottomNav] 的结论，不再用几条线索「或」起来猜。
 * 系统盖在键盘上的按钮（第 4 步，如三星「隐藏键盘」按钮）留出它的高度：那层在键盘窗口外面，键盘盖不住它，只会被它挡住（11-21）。
 */
internal fun fixedKeyboardMinimumInsetPx(insets: WindowInsets, window: Window, normalPx: Int,
    position: SettingsPreferences.KeyboardBottomPosition = SettingsPreferences.keyboardBottomPosition(window.context),
    reading: BottomNavReading = readBottomNav(insets, window.context, window)): Int {
    if (!AndroidPlatform.gestureInsets) return maxOf(normalPx, insets.displayCutout?.safeInsetBottom ?: 0)
    val r = reading
    val controls = maxOf(r.cutoutPx, r.tappablePx, r.imeButtonsPx)
    return when (position) {
        SettingsPreferences.KeyboardBottomPosition.FLUSH -> r.cutoutPx
        SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS -> maxOf(normalPx, controls, r.navPx)
        SettingsPreferences.KeyboardBottomPosition.AUTO -> when (classifyBottomNav(r).also {
            // 读数和走到第几步写进 logcat：以后看日志就知道那条是谁留的、按的哪一步（11-21 只拿到 inset，没拿到当时的判断）。
            Log.d(INSETS_TAG, "decision: step=${it.step} ${it.kind} nav=${r.navPx} tappable=${r.tappablePx} " +
                "mandatory=${r.mandatoryPx} ime=${r.imeButtonsPx} mode=${r.navigationMode} vendor=${r.vendorGesture} hide=${r.samsungHideButton}")
        }.kind) {
            BottomNavKind.TASKBAR -> maxOf(r.navPx, controls)
            BottomNavKind.IME_BUTTONS -> maxOf(r.cutoutPx, r.imeButtonsPx)
            BottomNavKind.SYSTEM_BUTTONS -> maxOf(controls, if (r.samsungHideButton) normalPx else 0)
            BottomNavKind.GESTURE -> r.cutoutPx
            BottomNavKind.BUTTONS, BottomNavKind.UNKNOWN -> maxOf(normalPx, controls, r.navPx)
        }
    }
}

/**
 * 固定键盘的底边自检（11-03「他如果自己检测到自己没贴底的话…能不能做到」）：固定卡片的位置每变一次，
 * 量一次输入法窗口底边离屏幕底边多远。窗口没铺到屏幕底边时，底下那条是系统画的，键盘这边的留白调到 0 也去不掉；
 * 这时去掉窗口底边的系统栏适配，让窗口铺到屏幕底边，之后照常按导航方式留位。返回量到的缺口（px）。
 */
internal fun checkImeWindowReachesScreenBottom(window: Window): Int {
    val decor = window.decorView
    if (!decor.isAttachedToWindow || decor.height <= 0) return 0
    val location = IntArray(2)
    decor.getLocationOnScreen(location)
    val gap = imeWindowBottomGapPx(screenBottomPx(window), location[1] + decor.height)
    if (gap > 0 && AndroidPlatform.windowInsetTypes) {
        val attributes = window.attributes
        val bottom = WindowInsets.Side.BOTTOM
        if (attributes.fitInsetsSides and bottom != 0) {
            attributes.fitInsetsSides = attributes.fitInsetsSides and bottom.inv()
            window.attributes = attributes
            Log.w(INSETS_TAG, "bottom-edge: window ends ${gap}px above the screen bottom, stop fitting the bottom bar")
        }
    }
    return gap
}

/** 窗口底边离屏幕底边的缺口（px）；1 px 以内是取整误差，不算。 */
internal fun imeWindowBottomGapPx(screenBottomPx: Int, windowBottomPx: Int): Int =
    (screenBottomPx - windowBottomPx).takeIf { it > 1 } ?: 0

private fun screenBottomPx(window: Window): Int =
    if (AndroidPlatform.windowMetrics) window.windowManager.currentWindowMetrics.bounds.bottom
    else android.graphics.Point().also {
        @Suppress("DEPRECATION") window.windowManager.defaultDisplay.getRealSize(it)
    }.y

/** 三档都从该档的位置往下调，可以一直调到 0；抬高另存（keyboardBottomPaddingDp），不在这里。 */
internal fun fixedKeyboardBottomInsetPx(insets: WindowInsets, window: Window, normalPx: Int,
    minimumPx: Int = fixedKeyboardMinimumInsetPx(insets, window, normalPx)): Int {
    val context = window.context
    val adjustment = SettingsPreferences.getKeyboardBottomAdjustmentDp(context).coerceAtMost(0)
    return (minimumPx + adjustment * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(0)
}

/**
 * 手势导航时系统底部手势区的上沿（px，[view] 所在窗口的坐标）：从这里往下按住上滑会被系统当成回桌面。
 * 是不是手势导航看同一张决策表；手势区报得太小或没报时按 [GESTURE_ZONE_MIN_DP] 算。系统盖在键盘上的按钮（第 4 步）也算进去，
 * 把手放在按钮上面。不是手势导航、Android 9 或读不到时为 null。
 */
internal fun bottomGestureZoneTopInWindowPx(view: android.view.View): Int? {
    if (!AndroidPlatform.gestureInsets) return null
    val insets = view.rootWindowInsets ?: return null
    return runCatching {
        val r = readBottomNav(insets, view.context, null)
        when (classifyBottomNav(r).kind) {
            BottomNavKind.GESTURE -> view.rootView.height - maxOf(r.mandatoryPx, (GESTURE_ZONE_MIN_DP * r.density).roundToInt())
            // 盖在键盘上的系统按钮：把手放在按钮和手势区上面，落在按钮里按不到。
            BottomNavKind.SYSTEM_BUTTONS -> view.rootView.height -
                maxOf(r.mandatoryPx, r.tappablePx, if (r.samsungHideButton) r.navPx else 0, (GESTURE_ZONE_MIN_DP * r.density).roundToInt())
            else -> null
        }
    }.getOrNull()
}

/**
 * 悬浮键盘最低能放到哪（px）。手势导航：避开系统报的按钮和底部手势区（没报时按 [GESTURE_ZONE_MIN_DP]），拖条整段都能拖；
 * 三星隐藏键盘按钮开着时整条留。其余整条导航栏留着。和固定键盘用同一张决策表。
 */
internal fun floatingMinimumInsetPx(window: Window, insets: WindowInsets, normalPx: Int,
    reading: BottomNavReading = readBottomNav(insets, window.context, window)): Int {
    if (!AndroidPlatform.gestureInsets) return maxOf(normalPx, insets.displayCutout?.safeInsetBottom ?: 0)
    val r = reading
    val controls = maxOf(r.cutoutPx, r.tappablePx, r.imeButtonsPx)
    return when (classifyBottomNav(r).kind) {
        BottomNavKind.GESTURE, BottomNavKind.IME_BUTTONS, BottomNavKind.SYSTEM_BUTTONS -> maxOf(controls,
            if (r.mandatoryPx > 0) r.mandatoryPx else (GESTURE_ZONE_MIN_DP * r.density).roundToInt(),
            if (r.samsungHideButton) normalPx else 0)
        else -> maxOf(normalPx, controls, r.navPx, r.mandatoryPx)
    }
}

/** 手势导航时底部手势区至少按这么高（dp）算；系统没报或报得比这小时用它。 */
internal const val GESTURE_ZONE_MIN_DP = 32f

/**
 * 反馈底部问题时要的一行（11-19：以前的反馈几乎都没有机型、系统版本、导航方式），设置页「底部留白校准」显示、可复制。
 */
internal fun bottomNavDiagnostic(context: Context, r: BottomNavReading,
    position: SettingsPreferences.KeyboardBottomPosition, adjustmentDp: Int, keptPx: Int): String {
    val decision = classifyBottomNav(r)
    fun dp(px: Int) = if (r.density > 0f) (px / r.density).roundToInt() else px
    val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    return listOf(
        AndroidPlatform.deviceLabel,
        "Android ${android.os.Build.VERSION.RELEASE}",
        android.os.Build.DISPLAY.orEmpty(),
        "CyIME ${com.kingzcheung.xime.BuildConfig.VERSION_NAME}",
        if (landscape) "横屏" else "竖屏",
        "navigation_mode=${r.navigationMode ?: "无"}",
        "厂商手势开关=${r.vendorGesture?.let { if (it) "开" else "关" } ?: "无"}",
        "导航栏 ${dp(r.navPx)} 可点 ${dp(r.tappablePx)} 手势区 ${dp(r.mandatoryPx)} 按钮条 ${dp(r.imeButtonsPx)} 缺口 ${dp(r.cutoutPx)} dp",
        "第${decision.step}步 ${decision.kind.label}",
        "底部位置 ${position.label} 调整 $adjustmentDp dp",
        "留 ${dp(keptPx)} dp",
    ).joinToString(" · ")
}

/**
 * 厂商自己的全面屏手势开关：开着为 true，关着为 false，不是这些厂商或读不到为 null。
 * 小米 force_fsg_nav_bar（Global）、vivo navigation_gesture_on、OPPO/一加/realme hide_navigationbar_enable、
 * 华为/荣耀 secure_gesture_navigation（Secure）、三星 navigation_bar_gesture_while_hidden（Global）；值非 0 即手势。
 */
internal fun vendorGestureNavigationOn(
    vendor: DeviceVendor,
    read: (global: Boolean, key: String) -> Int?,
): Boolean? {
    val (global, key) = when (vendor) {
        DeviceVendor.XIAOMI -> true to "force_fsg_nav_bar"
        DeviceVendor.VIVO -> false to "navigation_gesture_on"
        DeviceVendor.OPPO -> false to "hide_navigationbar_enable"
        DeviceVendor.HUAWEI, DeviceVendor.HONOR -> false to "secure_gesture_navigation"
        // 三星「滑动手势」：One UI 的 navigation_bar_gesture_while_hidden（Global），三键导航时为 0。
        DeviceVendor.SAMSUNG -> true to "navigation_bar_gesture_while_hidden"
        DeviceVendor.OTHER -> return null
    }
    return read(global, key)?.let { it != 0 }
}

private inline fun readSetting(read: () -> Int): Int? = try { read() } catch (_: Exception) { null }

/** 获取当前活跃的底部 inset（px）。 */
internal fun getActiveBottomInsetPx(imeWindow: Window?): Int {
    return try {
        val decorView = imeWindow?.decorView ?: return 0
        val insets = decorView.rootWindowInsets ?: return 0
        val px = extractBottomInset(insets, imeWindow)
        Log.d(INSETS_TAG, "getActiveBottomInsetPx=$px (rootWindowInsets)")
        px
    } catch (e: Exception) { 0 }
}
