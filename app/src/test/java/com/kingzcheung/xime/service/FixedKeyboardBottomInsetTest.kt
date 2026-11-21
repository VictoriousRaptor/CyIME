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

    // 「底部位置」默认自动；每个用例自己设导航方式和厂商。
    @Before fun auto() {
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.AUTO)
    }

    @Test fun oldEdgeOffUsersStartOnKeepButtonsAndTheSwitchNoLongerChangesTheAlgorithm() {
        val prefs = SettingsPreferences.getPrefsPublic(activity)
        prefs.edit().remove(SettingsPreferences.KEY_KEYBOARD_BOTTOM_POSITION).putBoolean(SettingsPreferences.KEY_EDGE_TO_EDGE_KEYBOARD, false).commit()
        assertEquals(SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS, SettingsPreferences.keyboardBottomPosition(activity))
        prefs.edit().remove(SettingsPreferences.KEY_EDGE_TO_EDGE_KEYBOARD).commit()
        assertEquals(SettingsPreferences.KeyboardBottomPosition.AUTO, SettingsPreferences.keyboardBottomPosition(activity))
        // 自动档里旧开关不再参与：手势导航贴到底，开关是什么都一样。
        ShadowBuild.setManufacturer("OPPO")
        mode(2)
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.AUTO)
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, false)
        assertEquals(0, measure())
        SettingsPreferences.setEdgeToEdgeKeyboard(activity, true)
        assertEquals(0, measure())
    }

    /** 盘点冲突 2：调低只存一套，切「底部位置」时数值不变；升级前停在「留出导航按钮」的旧调低值接着用。 */
    @Test fun loweringIsOneSetAcrossPositions() {
        ShadowBuild.setManufacturer("OPPO")
        mode(0)
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS)
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -30)
        assertEquals(18, measure(controls = 48))
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.AUTO)
        assertEquals(-30, SettingsPreferences.getKeyboardBottomAdjustmentDp(activity))
        assertEquals(18, measure(controls = 48))
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS)
        assertEquals(-30, SettingsPreferences.getKeyboardBottomAdjustmentDp(activity))

        val prefs = SettingsPreferences.getPrefsPublic(activity)
        prefs.edit().clear().putString(SettingsPreferences.KEY_KEYBOARD_BOTTOM_POSITION, "KEEP_BUTTONS")
            .putInt(SettingsPreferences.KEY_KEYBOARD_BOTTOM_LOWERING_DP, 20).commit()
        assertEquals(-20, SettingsPreferences.getKeyboardBottomAdjustmentDp(activity))
        // 自动档的老用户：旧键是贴底默认开之前按系统建议调的，不搬过来。
        prefs.edit().putString(SettingsPreferences.KEY_KEYBOARD_BOTTOM_POSITION, "AUTO").commit()
        assertEquals(0, SettingsPreferences.getKeyboardBottomAdjustmentDp(activity))
        // 写过新键之后只认新键。
        prefs.edit().putString(SettingsPreferences.KEY_KEYBOARD_BOTTOM_POSITION, "KEEP_BUTTONS").commit()
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -6)
        assertEquals(-6, SettingsPreferences.getKeyboardBottomAdjustmentDp(activity))
    }

    @Test fun aManualCorrectionCanReachZeroAndRaisingIsStoredApart() {
        ShadowBuild.setManufacturer("OPPO")
        mode(0)
        assertEquals(48, measure(controls = 48))
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -12)
        assertEquals(36, measure(controls = 48))
        assertEquals(0, SettingsPreferences.getKeyboardBottomPaddingDp(activity))
        SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, -48)
        assertEquals(0, measure(controls = 48))
        SettingsPreferences.setKeyboardBottomPaddingDp(activity, 17)
        assertEquals(48, measure(controls = 48))
        assertEquals(17, SettingsPreferences.getKeyboardBottomPaddingDp(activity))
    }

    /** 决策表在固定键盘上的结果（OPPO：navigation_mode 写得不可靠，只看它的厂商开关和 inset）。 */
    @Test fun autoFollowsTheDecisionTable() {
        ShadowBuild.setManufacturer("OPPO")
        // 4 navigation_mode=2：贴到底，只躲屏幕缺口；系统报的可点区域（常是整条按钮框）不留位（11-03）。
        mode(2)
        assertEquals(0, measure())
        assertEquals(16, measure(cutout = 16))
        assertEquals(0, measure(controls = 48))
        assertEquals(16, measure(controls = 48, cutout = 16))
        mode(0)
        // 5 可点区域占满：三键。
        assertEquals(48, measure(controls = 48))
        // 6 navigation_mode 写 0，但底下没有能按的东西（11-08「别让我看到底下有缝」）：贴到底。
        assertEquals(0, measure())
        assertEquals(0, measure(mandatory = 18))
        assertEquals(16, measure(cutout = 16))
        // 7 整条都是强制手势区、可点区域没报：说不清，整条留着。
        assertEquals(48, measure(mandatory = 48))
        // 4 厂商开关开着：手势。
        Settings.Secure.putInt(activity.contentResolver, "hide_navigationbar_enable", 1)
        assertEquals(0, measure(mandatory = 48))
        // 3 厂商开关关着：三键，inset 什么都没报也整条留。
        Settings.Secure.putInt(activity.contentResolver, "hide_navigationbar_enable", 0)
        assertEquals(48, measure())
        // 开关矛盾（mode=2 而厂商开关关）：看 inset。
        mode(2)
        assertEquals(0, measure())
        assertEquals(48, measure(controls = 48))
    }

    /** 11-19 三星三键被挡：他的手机和平板实测 navigation_mode=0、navigation_bar_gesture_while_hidden=0。 */
    @Test fun samsungThreeButtonKeepsTheWholeBarEvenWhenTheInsetsReportNothing() {
        ShadowBuild.setManufacturer("samsung")
        mode(0)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_while_hidden", 0)
        assertEquals(48, measure())
        assertEquals(48, measure(mandatory = 18))
        // 开关没读到，三星的 navigation_mode=0 也算三键。
        Settings.Global.putString(activity.contentResolver, "navigation_bar_gesture_while_hidden", null)
        assertEquals(48, measure())
        // 三星手势：贴到底。
        mode(2)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_while_hidden", 1)
        assertEquals(0, measure())
    }

    /**
     * 11-21 三星用户截图：系统那条「⌄ 收起键盘」盖住了最下一排。那层在键盘窗口外面，键盘盖不住它（盘点冲突 4 当时想反了）：
     * 自动档竖屏给它留位，悬浮键盘照旧避开。
     */
    @Test fun samsungHideButtonKeepsItsBarInAutoAndFloatingAvoidsIt() {
        ShadowBuild.setManufacturer("samsung")
        mode(2)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_while_hidden", 1)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_hint", 1)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_button_to_hide_keyboard", 1)
        assertEquals(48, measure())
        // 关了「显示隐藏键盘按钮」：照旧贴到底。
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_button_to_hide_keyboard", 0)
        assertEquals(0, measure())
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_button_to_hide_keyboard", 1)
        val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48)).build()
        val normal = extractBottomInset(insets, activity.window)
        assertEquals(48, normal)
        assertEquals(48, floatingMinimumInsetPx(activity.window, insets, normal))
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS)
        assertEquals(48, measure())
    }

    /** 三星说明里「显示隐藏键盘按钮」只在竖屏显示：横屏照旧贴到底。 */
    @Test @Config(qualifiers = "sw360dp-w800dp-h360dp-land-mdpi")
    fun samsungHideButtonDoesNotKeepTheBarInLandscape() {
        ShadowBuild.setManufacturer("samsung")
        mode(2)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_while_hidden", 1)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_hint", 1)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_button_to_hide_keyboard", 1)
        assertEquals(0, measure())
    }

    /**
     * 他的 S23 Ultra（One UI 8.5、Android 16）11-21 实测，换算成 dp（密度 2.625）：
     * 手势 +「显示隐藏键盘按钮」：导航栏 39px=15dp，可点区域、手势区 126px=48dp，那条里有切换键盘和 ⌄；三键：三项都是 48dp。
     * 两种都要留出整条 48dp，不管系统开关写的是什么。
     */
    @Test fun samsungOneUi85MeasuredReadingsKeepTheButtons() {
        ShadowBuild.setManufacturer("samsung")
        fun measureBars(nav: Int, tappable: Int, mandatory: Int): Int {
            val insets = WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, tappable))
                .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, mandatory)).build()
            return fixedKeyboardBottomInsetPx(insets, activity.window, extractBottomInset(insets, activity.window))
        }
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_button_to_hide_keyboard", 0)
        for ((navigationMode, whileHidden) in listOf(2 to 1, 0 to 0)) {
            mode(navigationMode)
            Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_while_hidden", whileHidden)
            assertEquals("mode=$navigationMode", 48, measureBars(nav = 15, tappable = 48, mandatory = 48))
        }
        mode(0)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_while_hidden", 0)
        assertEquals(48, measureBars(nav = 48, tappable = 48, mandatory = 48))
        // 手势、没报可点区域（没有按钮）：贴到底。
        mode(2)
        Settings.Global.putInt(activity.contentResolver, "navigation_bar_gesture_while_hidden", 1)
        assertEquals(0, measureBars(nav = 15, tappable = 0, mandatory = 32))
    }

    @Test fun floatingKeyboardAvoidsReportedButtonsAndTheGestureZone() {
        ShadowBuild.setManufacturer("OPPO")
        mode(2)
        val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, 30))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 18)).build()
        val normal = extractBottomInset(insets, activity.window)
        assertEquals(0, fixedKeyboardMinimumInsetPx(insets, activity.window, normal))
        assertEquals(30, floatingMinimumInsetPx(activity.window, insets, normal))
        val zoneOnly = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 18)).build()
        assertEquals(18, floatingMinimumInsetPx(activity.window, zoneOnly, 48))
        assertEquals(48, SettingsPreferences.getFloatingOffsetY(activity, defaultDp = 48))
        // 手势区没报：按 32dp 留，拖条整段都能拖。
        assertEquals(32, floatingMinimumInsetPx(activity.window,
            WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48)).build(), 48))
        // 三键：整条。
        mode(0)
        val buttons = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 48)).build()
        assertEquals(48, floatingMinimumInsetPx(activity.window, buttons, 48))
    }

    @Test fun diagnosticLineNamesTheStepAndTheReadings() {
        ShadowBuild.setManufacturer("samsung")
        mode(0)
        val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48)).build()
        val line = bottomNavDiagnostic(activity, readBottomNav(insets, activity, activity.window),
            SettingsPreferences.KeyboardBottomPosition.AUTO, -4, 44)
        for (part in listOf("Android ", "CyIME ", "navigation_mode=0", "导航栏 48", "第2步 三键", "底部位置 自动 调整 -4 dp", "留 44 dp"))
            org.junit.Assert.assertTrue("$part 不在 $line", part in line)
        SettingsPreferences.setKeyboardBottomDiagnostic(activity, line)
        assertEquals(line, SettingsPreferences.getKeyboardBottomDiagnostic(activity))
    }

    @Test fun windowGapIgnoresOnePixelRounding() {
        assertEquals(0, imeWindowBottomGapPx(screenBottomPx = 1752, windowBottomPx = 1752))
        assertEquals(0, imeWindowBottomGapPx(screenBottomPx = 1752, windowBottomPx = 1751))
        assertEquals(90, imeWindowBottomGapPx(screenBottomPx = 1752, windowBottomPx = 1662))
        assertEquals(0, imeWindowBottomGapPx(screenBottomPx = 1752, windowBottomPx = 1800))
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
        // 第 3 步：系统按钮条 18 + 4 = 22，可点区域不再另算。
        assertEquals(22, measure())
        assertEquals(22, measure(controls = 32))
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
        // 三星 navigation_mode=0 明说三键：第 2 步先于按钮条，整条三键留着，不能只留 22 把三键挡住。
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
        assertEquals(48, measure())
    }

    @Test @Config(qualifiers = "sw800dp-w800dp-h1200dp")
    fun aGestureOnlyTabletWithoutABottomTaskbarSitsAtTheBottom() {
        mode(2)
        val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 18)).build()
        assertEquals(0, fixedKeyboardBottomInsetPx(insets, activity.window, 18))
        assertEquals(18, floatingMinimumInsetPx(activity.window, insets, 18))
    }

    @Test @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun android10UsesItsLegacyTappableElementInsets() {
        mode(2)
        fun measureQ(tappable: Int): Int {
            val insets = WindowInsets.Builder().setSystemWindowInsets(Insets.of(0, 0, 0, 48))
                .setTappableElementInsets(Insets.of(0, 0, 0, tappable)).build()
            return fixedKeyboardBottomInsetPx(insets, activity.window, extractBottomInset(insets, activity.window))
        }
        assertEquals(0, measureQ(48))
        assertEquals(0, measureQ(0))
        SettingsPreferences.setKeyboardBottomPosition(activity, SettingsPreferences.KeyboardBottomPosition.KEEP_BUTTONS)
        assertEquals(48, measureQ(48))
    }
}
