package com.kingzcheung.xime.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** 「我的图片」：只在图片还在时出现，配色和遮罩沿用选图时的主题。 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class CustomImageThemeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val themes = KeyboardThemes.themes.filter { it.id != CustomImageTheme.ID }

    private fun choose(base: String): File {
        val file = File(SchemaManager.getRimeDir(context), "themes/custom-keyboard-bg-1.jpg")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
        SettingsPreferences.getPrefsPublic(context).edit()
            .putString("custom_theme_image_src", "themes/custom-keyboard-bg-1.jpg")
            .putString("custom_theme_image_base", base).commit()
        return file
    }

    @After fun clear() {
        SettingsPreferences.getPrefsPublic(context).edit()
            .remove("custom_theme_image_src").remove("custom_theme_image_base")
            .remove("custom_theme_image_placement")
            .remove("custom_theme_image_veil_light").remove("custom_theme_image_veil_dark")
            .remove(com.kingzcheung.xime.settings.BackgroundMotionPreferences.KEY_SLIDES).commit()
        File(SchemaManager.getRimeDir(context), "themes").listFiles()
            ?.filter { it.name.startsWith("custom-keyboard-bg-") }?.forEach { it.delete() }
    }

    @Test fun absentUntilAnImageIsChosen() {
        assertNull(CustomImageTheme.build(context, themes))
        choose("mint_green").delete()
        assertNull(CustomImageTheme.build(context, themes))
    }

    @Test fun keepsTheBaseColoursAndTintsTheImageWithItsBackground() {
        choose("mint_green")
        val base = themes.first { it.id == "mint_green" }
        val custom = CustomImageTheme.build(context, themes)!!
        assertEquals(CustomImageTheme.ID, custom.id)
        assertEquals(base.keyBgLight, custom.keyBgLight)
        assertEquals(base.candidateTextColorDark, custom.candidateTextColorDark)
        val bg = custom.keyboardBackground!!
        assertEquals("image", bg.type)
        assertEquals("themes/custom-keyboard-bg-1.jpg", bg.src)
        assertEquals((base.keyboardBgLight.toArgb() and 0xFFFFFF).toLong(), bg.overlayColor)
        assertEquals((base.keyboardBgDark.toArgb() and 0xFFFFFF).toLong(), bg.overlayColorDark)
        assertEquals(ThemeIconColors.forTheme("mint_green"), ThemeIconColors.forTheme(CustomImageTheme.ID))
    }

    @Test fun glassHasNoSolidBackgroundSoSoftBlueIsUsed() {
        choose(TransparentGlassTheme.ID)
        assertEquals(themes.first { it.id == SoftBlueTheme.ID }.keyBgLight, CustomImageTheme.build(context, themes)!!.keyBgLight)
    }

    @Test fun oldSettingsWithoutPlacementStillCoverCentred() {
        choose("mint_green")
        val bg = CustomImageTheme.build(context, themes)!!.keyboardBackground!!
        assertEquals("cover", bg.fit)
        assertEquals(1f, bg.zoom)
        assertEquals(0.5f, bg.alignX)
        assertEquals(0.5f, bg.alignY)
    }

    @Test fun commitKeepsTheAdjustedPlacementAndRemovesOtherImages() {
        val old = choose("mint_green")
        val fresh = File(old.parentFile, "custom-keyboard-bg-2.jpg").apply { writeBytes(byteArrayOf(2)) }
        CustomImageTheme.commit(context, "themes/custom-keyboard-bg-2.jpg", "mint_green",
            ThemeImagePlacement("fit_height", 1f, 0f, 0.5f))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        val bg = CustomImageTheme.build(context, themes)!!.keyboardBackground!!
        assertEquals("themes/custom-keyboard-bg-2.jpg", bg.src)
        assertEquals("fit_height", bg.fit)
        assertEquals(0f, bg.alignX)
    }

    @Test fun cancellingANewPickDeletesOnlyThatFile() {
        val inUse = choose("mint_green")
        val picked = File(inUse.parentFile, "custom-keyboard-bg-3.jpg").apply { writeBytes(byteArrayOf(3)) }
        CustomImageTheme.discard(context, "themes/custom-keyboard-bg-3.jpg")
        assertFalse(picked.exists())
        // 调整已在用的图片后取消，不能把它删掉。
        CustomImageTheme.discard(context, "themes/custom-keyboard-bg-1.jpg")
        assertTrue(inUse.exists())
    }

    /** 删掉第一张而后面还有图：下一张顶上来，铺满居中；配色和其余的顺序不变，文件改成主图的名字。 */
    @Test fun removingTheFirstImagePromotesTheNextOne() {
        val old = choose("mint_green")
        CustomImageTheme.commit(context, "themes/custom-keyboard-bg-1.jpg", "mint_green",
            ThemeImagePlacement("fit_height", 1.4f, 0.2f, 0.5f))
        val dir = old.parentFile!!
        val slides = listOf("a", "b").map { name ->
            File(dir, "custom-keyboard-bg-slide-$name.jpg").apply { writeBytes(byteArrayOf(2)) }
            "themes/custom-keyboard-bg-slide-$name.jpg"
        }
        com.kingzcheung.xime.settings.BackgroundMotionPreferences.setSlides(context, slides)

        assertTrue(CustomImageTheme.promoteNextSlide(context))
        val main = CustomImageTheme.imageSource(context)!!
        assertTrue(main, main.startsWith("themes/custom-keyboard-bg-") && !main.contains("slide-"))
        assertFalse("旧主图删掉", old.exists())
        assertFalse("顶上来的那张不再留着轮播文件名", File(dir, "custom-keyboard-bg-slide-a.jpg").exists())
        assertEquals(listOf("themes/custom-keyboard-bg-slide-b.jpg"),
            com.kingzcheung.xime.settings.BackgroundMotionPreferences.read(context).slides)
        assertEquals(ThemeImagePlacement.DEFAULT, CustomImageTheme.placement(context))
        assertEquals(themes.first { it.id == "mint_green" }.keyBgLight, CustomImageTheme.build(context, themes)!!.keyBgLight)

        assertTrue(CustomImageTheme.promoteNextSlide(context))
        assertEquals(emptyList<String>(), com.kingzcheung.xime.settings.BackgroundMotionPreferences.read(context).slides)
        assertFalse("只剩一张时不动，由确认后的删除处理", CustomImageTheme.promoteNextSlide(context))
        assertTrue(CustomImageTheme.hasImage(context))
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("custom-keyboard-bg-") })
    }

    @Test fun deleteRemovesTheThemeAndReturnsTheBase() {
        val file = choose("mint_green")
        assertEquals("mint_green", CustomImageTheme.delete(context))
        assertFalse(file.exists())
        assertFalse(CustomImageTheme.hasImage(context))
        assertNull(CustomImageTheme.build(context, themes))
    }

    /** 遮罩没调过时与原来一样 72%；浅色、深色各自存，超出 0~90% 的值收回范围里。 */
    @Test fun veilDefaultsToTheOldStrengthAndIsStoredPerAppearance() {
        choose("mint_green")
        val untouched = CustomImageTheme.build(context, themes)!!.keyboardBackground!!
        assertEquals(0.72f, untouched.overlayAlpha!!, 1e-6f)
        assertEquals(0.72f, untouched.overlayAlphaDark!!, 1e-6f)

        CustomImageTheme.setVeil(context, dark = false, value = 0.3f)
        CustomImageTheme.setVeil(context, dark = true, value = 0.55f)
        val tuned = CustomImageTheme.build(context, themes)!!.keyboardBackground!!
        assertEquals(0.3f, tuned.overlayAlpha!!, 1e-6f)
        assertEquals(0.55f, tuned.overlayAlphaDark!!, 1e-6f)

        CustomImageTheme.setVeil(context, dark = false, value = -1f)
        CustomImageTheme.setVeil(context, dark = true, value = 2f)
        assertEquals(0f, CustomImageTheme.veil(context, dark = false), 1e-6f)
        assertEquals(CustomImageTheme.MAX_VEIL, CustomImageTheme.veil(context, dark = true), 1e-6f)
    }

    /** 调整页预览用传进来的遮罩，和存好后的键盘一样。 */
    @Test fun previewUsesTheGivenVeil() {
        val bg = CustomImageTheme.preview(themes, "mint_green", "themes/custom-keyboard-bg-1.jpg",
            ThemeImagePlacement.DEFAULT, veilLight = 0.2f, veilDark = 0.4f)!!.keyboardBackground!!
        assertEquals(0.2f, bg.overlayAlpha!!, 1e-6f)
        assertEquals(0.4f, bg.overlayAlphaDark!!, 1e-6f)
    }

    /** 第一次选图时主题表里还没有「我的图片」：调整页预览按沿用的主题取键色，不能落到全局的半透明白键。 */
    @Test fun previewTakesKeyColoursFromTheBaseBeforeTheImageIsSaved() {
        val id = CustomImageTheme.previewColorThemeId(themes, PureBlackTheme.ID)
        assertEquals(PureBlackTheme.ID, id)
        val base = themes.first { it.id == PureBlackTheme.ID }
        assertEquals(base.keyBgDark, KeyboardThemes.getKeyBgColorOverride(id, true))
        assertEquals(base.keyTextColorDark, KeyboardThemes.getKeyTextColorOverride(id, true))
        assertEquals(SoftBlueTheme.ID, CustomImageTheme.previewColorThemeId(themes, TransparentGlassTheme.ID))
    }

    /** 11-09 用户：图片和配色是两个系统。开着图片换配色，只换图片下面的颜色，图片和摆放不动。 */
    @Test fun changingTheSchemeKeepsTheImage() {
        choose("mint_green")
        CustomImageTheme.setBase(context, SoftBlueTheme.ID)
        val custom = CustomImageTheme.build(context, themes)!!
        val blue = themes.first { it.id == SoftBlueTheme.ID }
        assertEquals(blue.keyBgLight, custom.keyBgLight)
        assertEquals("themes/custom-keyboard-bg-1.jpg", custom.keyboardBackground!!.src)
        assertEquals(SoftBlueTheme.ID, CustomImageTheme.baseFor(context, CustomImageTheme.ID))
    }

    @Test fun iosGradientCannotSitUnderTheImage() {
        assertFalse(CustomImageTheme.canUnderlay(themes.first { it.id == TransparentGlassTheme.ID }))
        assertTrue(CustomImageTheme.canUnderlay(themes.first { it.id == SoftBlueTheme.ID }))
    }
}
