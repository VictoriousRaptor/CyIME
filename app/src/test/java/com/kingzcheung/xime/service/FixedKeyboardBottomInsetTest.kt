package com.kingzcheung.xime.service

import android.app.Activity
import android.graphics.Insets
import android.graphics.Rect
import android.provider.Settings
import android.view.DisplayCutout
import android.view.WindowInsets
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "sw360dp-w360dp-h800dp-mdpi")
class FixedKeyboardBottomInsetTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun measure(controls: Int = 0, cutout: Int = 0, mandatory: Int = 0): Int {
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, controls))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, mandatory))
        if (cutout > 0) {
            @Suppress("DEPRECATION")
            insets.setDisplayCutout(DisplayCutout(Rect(0, 0, 0, cutout), emptyList()))
        }
        val reported = insets.build()
        return fixedKeyboardBottomInsetPx(reported, activity.window, extractBottomInset(reported, activity.window))
    }
    private fun mode(value: Int) = Settings.Secure.putInt(activity.contentResolver, "navigation_mode", value)

    /** 下面多数用例测「贴底」关着时的系统建议和校准；贴底默认开另有用例。 */
    // 这些是 1.15.66 的用例：「底部位置」固定在自动档（66 的算法），再按用例开关贴底。
    @Before fun edgeOff() {
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.AUTO)
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, false)
    }

    @Test fun edgeIsOnByDefaultAndItsLoweringIsSeparateFromTheOldCalibration() {
        ShadowBuild.setManufacturer("OPPO")
        SettingsPreferences.getPrefsPublic(activity).edit().remove(SettingsPreferences.KEY_EDGE_TO_EDGE_KEYBOARD).commit()
        // 老用户按系统建议调低过 20 dp（三键导航时那时的下限挡着，实际没压到按钮）。
        SettingsPreferences.getPrefsPublic(activity).edit()
            .putInt(SettingsPreferences.KEY_KEYBOARD_BOTTOM_LOWERING_DP, 20).commit()
        assertEquals(true, SettingsPreferences.edgeToEdgeKeyboard(activity))
        assertEquals(0, SettingsPreferences.getKeyboardBottomAdjustmentDp(activity))
        mode(2)
        assertEquals(0, measure())
        // 三键导航：贴底默认开也不动，老的调低值不会搬过来压住按钮。
        mode(0)
        assertEquals(48, measure(controls = 48))
        // 识别不出手势导航时，贴底下还能自己调低，一直到 0。
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -30)
        assertEquals(18, measure(controls = 48))
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -80)
        assertEquals(0, measure(controls = 48))
        // 关掉贴底回到原来的校准值和下限。
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, false)
        assertEquals(-20, SettingsPreferences.getKeyboardBottomAdjustmentDp(activity))
        assertEquals(48, measure(controls = 48))
        // 「恢复默认」回到自动档（66 的贴底）、留白清零（11-08 加「底部位置」后）。
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, true)
        SettingsPreferences.resetKeyboardBottomCalibration(activity)
        assertEquals(true, SettingsPreferences.edgeToEdgeKeyboard(activity))
        assertEquals(SettingsPreferences.KeyboardBottomPosition.AUTO, SettingsPreferences.keyboardBottomPosition(activity))
        assertEquals(0, SettingsPreferences.getKeyboardBottomAdjustmentDp(activity))
    }

    @Test fun aManualCorrectionCanReachZeroButNeverPassSystemButtonsOrACutout() {
        ShadowBuild.setManufacturer("OPPO")
        mode(2)
        assertEquals(48, measure())
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -12)
        assertEquals(36, measure())
        assertEquals(0, SettingsPreferences.getKeyboardBottomPaddingDp(activity))
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -48)
        assertEquals(0, measure())
        assertEquals(20, measure(controls = 20))
        assertEquals(16, measure(cutout = 16))
        mode(0)
        assertEquals(48, measure())
        mode(2)
        assertEquals(0, measure())
        SettingsPreferences.setKeyboardBottomPaddingDp(activity, 0)
        assertEquals(48, measure())
        SettingsPreferences.setKeyboardBottomPaddingDp(activity, 17)
        assertEquals(48, measure())
        assertEquals(17, SettingsPreferences.getKeyboardBottomPaddingDp(activity))
    }

    @Test fun floatingHandleUsesTheMandatoryGestureAreaWhileAnUnsetPositionUsesTheEstimate() {
        ShadowBuild.setManufacturer("OPPO")
        mode(2)
        val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 18)).build()
        val normal = extractBottomInset(insets, activity.window)
        val floor = fixedKeyboardMinimumInsetPx(insets, activity.window, normal)
        assertEquals(0, floor)
        assertEquals(18, floatingMinimumInsetPx(activity.window, insets, floor))
        assertEquals(48, SettingsPreferences.getFloatingOffsetY(activity, defaultDp = normal))
        SettingsPreferences.setFloatingOffsetY(activity, 18)
        assertEquals(18, SettingsPreferences.getFloatingOffsetY(activity, defaultDp = normal))
    }

    @Test fun anUnknownNavigationModeCanUseAnExplicitSystemGestureReport() {
        ShadowBuild.setManufacturer("unknown")
        Settings.Secure.putString(activity.contentResolver, "navigation_mode", null)
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -48)
        val reported = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 18)).build()
        assertEquals(0, fixedKeyboardBottomInsetPx(reported, activity.window, 48))
        assertEquals(48, measure())
    }

    @Test fun onlyAnOptedInButtonFreeGesturePhoneCanDropTheReserve() {
        ShadowBuild.setManufacturer("OPPO")
        mode(2)
        assertEquals(48, measure())
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, true)
        assertEquals(0, measure())
        assertEquals(16, measure(cutout = 16))
        // 贴底即忽略系统给的底部边衬区：手势导航时系统报的可点区域（常是整条按钮框）不再留位（11-03）。
        assertEquals(0, measure(controls = 20))
        assertEquals(0, measure(controls = 48))
        assertEquals(16, measure(controls = 48, cutout = 16))
        mode(0)
        // 三键导航的样子（整条都是强制手势区）：留着。
        assertEquals(48, measure(mandatory = 48))
        // navigation_mode 写 0，但底下没有能按的东西（11-08「别让我看到底下有缝」）：贴到底。
        assertEquals(0, measure())
        assertEquals(16, measure(cutout = 16))
        Settings.Secure.putInt(activity.contentResolver, "hide_navigationbar_enable", 1)
        assertEquals(0, measure(mandatory = 48))
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, false)
        assertEquals(48, measure())
    }

    @Test fun aGestureSizedSystemNavBarCountsAsGestureNavigation() {
        // 手势条 16dp、给输入法 48dp：手势导航。
        assertEquals(true, navBarResourceShowsGestureBar(resourcePx = 42, insetPx = 126, density = 2.625f))
        // 三键：资源和 inset 一样高。
        assertEquals(false, navBarResourceShowsGestureBar(resourcePx = 126, insetPx = 126, density = 2.625f))
        // 资源已是手势条，但 inset 也只有这么高：没有多出来的，不算。
        assertEquals(false, navBarResourceShowsGestureBar(resourcePx = 42, insetPx = 42, density = 2.625f))
        // 读不到资源。
        assertEquals(false, navBarResourceShowsGestureBar(resourcePx = 0, insetPx = 126, density = 2.625f))
    }

    @Test fun theKeyboardReadsGestureNavigationFromTheInsetsItself() {
        // 平板实测（三键导航）：nav、mandatory、tappable 都是 90，不是手势。
        assertEquals(false, insetsShowGestureNavigation(navPx = 90, mandatoryPx = 90, tappablePx = 90, density = 1.875f))
        // 手势导航：48dp 按钮框里强制手势区只有手势条那段，可点区域没占满。
        assertEquals(true, insetsShowGestureNavigation(navPx = 126, mandatoryPx = 63, tappablePx = 0, density = 2.625f))
        // 可点区域占满整条：那条里有按钮，不算。
        assertEquals(false, insetsShowGestureNavigation(navPx = 126, mandatoryPx = 63, tappablePx = 126, density = 2.625f))
        // 没报手势区、或手势区比 32dp 还高，都不猜。
        assertEquals(false, insetsShowGestureNavigation(navPx = 126, mandatoryPx = 0, tappablePx = 0, density = 2.625f))
        assertEquals(false, insetsShowGestureNavigation(navPx = 160, mandatoryPx = 100, tappablePx = 0, density = 2.625f))
    }

    @Test fun edgeDropsTheEmptyFrameWhenOnlyTheInsetsShowGestureNavigation() {
        // 厂商不认识、navigation_mode 还写 0：贴底时按 inset 自己认出手势导航，底下那条去掉。
        ShadowBuild.setManufacturer("unknown")
        mode(0)
        val reported = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 18)).build()
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, true)
        assertEquals(0, fixedKeyboardBottomInsetPx(reported, activity.window, 48))
        // 三键导航（手势区和导航栏一样高、按钮占满）照旧整条留着。
        val buttons = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, 48)).build()
        assertEquals(48, fixedKeyboardBottomInsetPx(buttons, activity.window, 48))
        // 贴底关着不猜。
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, false)
        assertEquals(48, fixedKeyboardBottomInsetPx(reported, activity.window, 48))
    }

    @Test fun nothingToPressMeansNoButtonsAndNotAThreeButtonBar() {
        // 手势条 16dp，什么都没报成可点：贴到底。
        assertEquals(true, nothingToPressAtBottom(tappablePx = 0, navButtonsPx = 0, navPx = 42, mandatoryPx = 42, density = 2.625f))
        // 48dp 框里只报了手势条那段强制手势区：贴到底。
        assertEquals(true, nothingToPressAtBottom(tappablePx = 0, navButtonsPx = 0, navPx = 126, mandatoryPx = 63, density = 2.625f))
        // 系统报了可点区域，或本窗口里画了返回/切换输入法按钮：留着。
        assertEquals(false, nothingToPressAtBottom(tappablePx = 126, navButtonsPx = 0, navPx = 126, mandatoryPx = 63, density = 2.625f))
        assertEquals(false, nothingToPressAtBottom(tappablePx = 0, navButtonsPx = 126, navPx = 126, mandatoryPx = 63, density = 2.625f))
        // 整条 48dp 都是强制手势区：三键导航的样子，可点区域没报也留着。
        assertEquals(false, nothingToPressAtBottom(tappablePx = 0, navButtonsPx = 0, navPx = 126, mandatoryPx = 126, density = 2.625f))
    }

    @Test fun windowGapIgnoresOnePixelRounding() {
        assertEquals(0, imeWindowBottomGapPx(screenBottomPx = 1752, windowBottomPx = 1752))
        assertEquals(0, imeWindowBottomGapPx(screenBottomPx = 1752, windowBottomPx = 1751))
        assertEquals(90, imeWindowBottomGapPx(screenBottomPx = 1752, windowBottomPx = 1662))
        assertEquals(0, imeWindowBottomGapPx(screenBottomPx = 1752, windowBottomPx = 1800))
    }

    @Test fun samsungHideButtonKeepsRoomUnlessEdgeIsOn() {
        ShadowBuild.setManufacturer("samsung")
        mode(2)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_hint", 1)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_button_to_hide_keyboard", 1)
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -48)
        assertEquals(48, measure())
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, true)
        assertEquals(0, measure())
        mode(0)
        assertEquals(48, measure(controls = 48))
    }

    @Test fun floatingKeyboardIgnoresEdgeAndStillAvoidsReportedButtons() {
        ShadowBuild.setManufacturer("OPPO")
        mode(2)
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, true)
        val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, 30))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 18)).build()
        val normal = extractBottomInset(insets, activity.window)
        assertEquals(0, fixedKeyboardMinimumInsetPx(insets, activity.window, normal))
        assertEquals(30, floatingMinimumInsetPx(activity.window, insets,
            fixedKeyboardMinimumInsetPx(insets, activity.window, normal, edge = false)))
    }

    @Test fun visibleAndroidNavigationFrameProtectsItsRealTopOnAGesturePhone() {
        ShadowBuild.setManufacturer("samsung")
        mode(2)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_button_to_hide_keyboard", 0)
        val decor = activity.window.decorView as android.widget.FrameLayout
        val frame = Class.forName("android.inputmethodservice.navigationbar.NavigationBarFrame")
            .getConstructor(android.content.Context::class.java).newInstance(activity) as android.view.View
        decor.addView(frame, android.widget.FrameLayout.LayoutParams(-1, 18, android.view.Gravity.BOTTOM)
            .apply { bottomMargin = 4 })
        val exact = android.view.View.MeasureSpec.EXACTLY
        decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(360, exact), android.view.View.MeasureSpec.makeMeasureSpec(800, exact))
        decor.layout(0, 0, 360, 800)
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -48)
        assertEquals(22, measure())
        assertEquals(32, measure(controls = 32))
        frame.visibility = android.view.View.GONE
        assertEquals(0, measure())
        val host = android.view.View(activity)
        decor.addView(host)
        val reported = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48)).build()
        val observed = mutableListOf<Int>()
        host.observeKeyboardBottomInsets(activity.window) { observed += fixedKeyboardBottomInsetPx(it, activity.window, 48) }
        host.dispatchApplyWindowInsets(reported)
        frame.visibility = android.view.View.VISIBLE
        decor.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(listOf(0, 22), observed)
        mode(0)
        assertEquals(48, measure())
    }

    /** 「贴到最底」说的是不给导航按钮留位：系统在键盘下面画了按钮那条也贴到屏幕底边；另两档照旧留着那条（11-10）。 */
    @Test fun flushIgnoresTheSystemImeButtonsButTheOtherChoicesKeepThem() {
        mode(2)
        val decor = activity.window.decorView as android.widget.FrameLayout
        val frame = Class.forName("android.inputmethodservice.navigationbar.NavigationBarFrame")
            .getConstructor(android.content.Context::class.java).newInstance(activity) as android.view.View
        decor.addView(frame, android.widget.FrameLayout.LayoutParams(-1, 48, android.view.Gravity.BOTTOM))
        val exact = android.view.View.MeasureSpec.EXACTLY
        decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(360, exact), android.view.View.MeasureSpec.makeMeasureSpec(800, exact))
        decor.layout(0, 0, 360, 800)
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.FLUSH)
        assertEquals(0, measure())
        assertEquals(12, measure(cutout = 12))
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.AUTO)
        assertEquals(48, measure())
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS)
        assertEquals(48, measure())
    }

    /** 键盘调节的底边把手要放在系统画的「切换输入法 / 收起键盘」那条上面（11-10 用户截图）：量出那条的上沿。 */
    @Test fun resizeHandleKnowsWhereTheSystemImeButtonsStart() {
        val decor = activity.window.decorView as android.widget.FrameLayout
        val host = android.view.View(activity)
        decor.addView(host)
        val exact = android.view.View.MeasureSpec.EXACTLY
        fun layout() {
            decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(360, exact), android.view.View.MeasureSpec.makeMeasureSpec(800, exact))
            decor.layout(0, 0, 360, 800)
        }
        layout()
        assertEquals(null, imeNavButtonsTopInWindowPx(host))
        val frame = Class.forName("android.inputmethodservice.navigationbar.NavigationBarFrame")
            .getConstructor(android.content.Context::class.java).newInstance(activity) as android.view.View
        decor.addView(frame, android.widget.FrameLayout.LayoutParams(-1, 48, android.view.Gravity.BOTTOM))
        layout()
        assertEquals(752, imeNavButtonsTopInWindowPx(host))
        frame.visibility = android.view.View.GONE
        assertEquals(null, imeNavButtonsTopInWindowPx(host))
    }

    @Test @Config(qualifiers = "sw800dp-w800dp-h1200dp")
    fun tabletKeepsTheTaskbarEvenIfTheWindowReportsNoButtons() {
        mode(2)
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, true)
        assertEquals(48, measure())
    }

    @Test @Config(qualifiers = "sw800dp-w800dp-h1200dp")
    fun aGestureOnlyTabletWithoutABottomTaskbarCanLowerItsKeyboard() {
        mode(2)
        val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 18)).build()
        assertEquals(18, fixedKeyboardBottomInsetPx(insets, activity.window, 18))
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -18)
        assertEquals(0, fixedKeyboardBottomInsetPx(insets, activity.window, 18))
        assertEquals(18, floatingMinimumInsetPx(activity.window, insets, 0))
    }

    @Test @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun android10UsesItsLegacyTappableElementInsets() {
        mode(2)
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, true)
        fun measureQ(tappable: Int): Int {
            val insets = WindowInsets.Builder().setSystemWindowInsets(Insets.of(0, 0, 0, 48))
                .setTappableElementInsets(Insets.of(0, 0, 0, tappable)).build()
            return fixedKeyboardBottomInsetPx(insets, activity.window, extractBottomInset(insets, activity.window))
        }
        assertEquals(0, measureQ(48))
        assertEquals(0, measureQ(0))
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, false)
        assertEquals(48, measureQ(48))
    }
}
