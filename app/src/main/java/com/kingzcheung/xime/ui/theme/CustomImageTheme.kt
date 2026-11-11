package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File

/**
 * 「我的图片」：用户自选一张图片做键盘背景。
 *
 * 配色沿用选图时正在用的主题，图片上压一层该主题的底色（与大漠流金同一做法），
 * 按键、候选字与原主题的对比度相近；深浅色、玻璃都按原主题走。
 * 图片转成 JPEG 存在 rime/themes/，备份恢复一并带上；换图用新文件名，键盘按文件名重新解码。
 * 选图后先进调整页（拖动、缩放、吸附边缘），确认才存；摆放存成与键盘尺寸无关的 [ThemeImagePlacement]。
 */
object CustomImageTheme {
    const val ID = "custom_image"
    private const val NAME = "我的图片"
    private const val KEY_SRC = "custom_theme_image_src"
    private const val KEY_BASE = "custom_theme_image_base"
    private const val KEY_PLACEMENT = "custom_theme_image_placement"
    private const val FILE_PREFIX = "custom-keyboard-bg-"
    /** 动态背景的轮播图片：同一前缀（[isCustomImage] 照样认、删除图片时一起删），换主图时留着。 */
    private const val SLIDE_PREFIX = FILE_PREFIX + "slide-"
    /**
     * 遮罩强度，与大漠流金一致。照片纯黑（浅色）或纯白（深色）处压过之后，
     * 按键文字对底仍有 6:1 以上，次要的候选文字约 3.5:1。
     */
    private const val OVERLAY_ALPHA = 0.72f
    /** 用户调过的遮罩（背景图片 › 浅色遮罩／深色遮罩）；没调过按 [OVERLAY_ALPHA]。 */
    private const val KEY_VEIL_LIGHT = "custom_theme_image_veil_light"
    private const val KEY_VEIL_DARK = "custom_theme_image_veil_dark"
    /** 遮罩最高 90%：再高图片几乎看不见，和不用图片一样。 */
    const val MAX_VEIL = 0.9f

    /** 当前沿用的主题；桌面图标配色按它走（[ThemeIconColors.forTheme]）。 */
    @Volatile var baseThemeId: String = SoftBlueTheme.ID
        private set

    private fun themesDir(context: Context) = File(SchemaManager.getRimeDir(context), "themes")

    fun hasImage(context: Context): Boolean = imageSource(context) != null

    /** 这张背景是不是用户自己选的图（按存图的文件名认，允许裸文件名、`themes/…` 和 `rime/themes/…` 等路径）。 */
    fun isCustomImage(background: BackgroundConfig?): Boolean {
        if (background?.type != "image") return false
        val names = listOfNotNull(background.src, background.srcDark)
            .map { it.substringAfterLast('/').substringAfterLast('\\') }
        return names.any { it.startsWith(FILE_PREFIX) }
    }

    /** 已存的图片（themes/…），没有时为 null。 */
    fun imageSource(context: Context): String? {
        val src = SettingsPreferences.getPrefsPublic(context).getString(KEY_SRC, null) ?: return null
        return src.takeIf { File(themesDir(context), src.removePrefix("themes/")).isFile }
    }

    /**
     * iOS 渐变和自身不能做底：它们没有固定的底色可压在图片上。
     * 莫奈取色可以（11-03）：取当下壁纸色做遮罩和键色，换壁纸后由 [KeyboardThemes.refreshDynamicSchemes] 重算。
     */
    private fun usableBase(theme: KeyboardColorScheme) =
        theme.id != ID && theme.id != TransparentGlassTheme.ID

    /** 这套配色能不能垫在图片下面（iOS 渐变没有固定底色，不能）。 */
    fun canUnderlay(theme: KeyboardColorScheme): Boolean = usableBase(theme)

    /**
     * 开着图片时换配色：只换图片下面的配色，图片留着（11-09 用户：图片和配色是两个系统，
     * 原来要先选配色再选图片才记得住）。在主线程调用也可以，只写一项设置。
     */
    fun setBase(context: Context, baseThemeId: String) {
        check(SettingsPreferences.getPrefsPublic(context).edit().putString(KEY_BASE, baseThemeId).commit()) { "无法保存设置" }
    }

    /** 选图时要沿用的主题：当前就是「我的图片」时沿用它原来的底。 */
    fun baseFor(context: Context, currentThemeId: String): String =
        if (currentThemeId == ID) SettingsPreferences.getPrefsPublic(context).getString(KEY_BASE, null) ?: SoftBlueTheme.ID
        else currentThemeId

    /** 图片上压的遮罩（0 不压，越大图片越淡、按键文字越清楚）；浅色、深色各自一份。 */
    fun veil(context: Context, dark: Boolean): Float =
        SettingsPreferences.getPrefsPublic(context).getFloat(if (dark) KEY_VEIL_DARK else KEY_VEIL_LIGHT, OVERLAY_ALPHA)
            .takeIf { it.isFinite() }?.coerceIn(0f, MAX_VEIL) ?: OVERLAY_ALPHA

    fun setVeil(context: Context, dark: Boolean, value: Float) {
        SettingsPreferences.getPrefsPublic(context).edit()
            .putFloat(if (dark) KEY_VEIL_DARK else KEY_VEIL_LIGHT, value.coerceIn(0f, MAX_VEIL)).apply()
    }

    /** 已存的摆放；旧版本没有这一项，按原来的居中铺满。 */
    internal fun placement(context: Context): ThemeImagePlacement =
        ThemeImagePlacement.decode(SettingsPreferences.getPrefsPublic(context).getString(KEY_PLACEMENT, null))

    internal fun build(context: Context, themes: List<KeyboardColorScheme>): KeyboardColorScheme? {
        val src = imageSource(context) ?: return null
        val baseId = SettingsPreferences.getPrefsPublic(context).getString(KEY_BASE, null)
        val base = resolveBase(themes, baseId) ?: return null
        baseThemeId = base.id
        return scheme(base, src, placement(context), veil(context, false), veil(context, true))
    }

    /** 调整页预览用：[baseId] 的配色加上还没存的 [src] 与 [placement]，遮罩用已存的 [veilLight]/[veilDark]。 */
    internal fun preview(themes: List<KeyboardColorScheme>, baseId: String, src: String, placement: ThemeImagePlacement,
        veilLight: Float = OVERLAY_ALPHA, veilDark: Float = OVERLAY_ALPHA) =
        resolveBase(themes, baseId)?.let { scheme(it, src, placement, veilLight, veilDark) }

    /**
     * 调整页预览取键色、字色用的主题 id：预览主题还没存进主题表，按 [ID] 取色会落到全局默认色；
     * 它的键色、字色都抄自沿用的主题，按那个主题取色与存好后的键盘一致。
     */
    internal fun previewColorThemeId(themes: List<KeyboardColorScheme>, baseId: String): String =
        resolveBase(themes, baseId)?.id ?: ID

    private fun resolveBase(themes: List<KeyboardColorScheme>, baseId: String?) =
        themes.firstOrNull { it.id == baseId && usableBase(it) } ?: themes.firstOrNull { it.id == SoftBlueTheme.ID }

    private fun scheme(base: KeyboardColorScheme, src: String, placement: ThemeImagePlacement,
        veilLight: Float, veilDark: Float): KeyboardColorScheme {
        fun Color.rgb() = (toArgb() and 0xFFFFFF).toLong()
        return base.copy(
            id = ID, name = NAME,
            // 莫奈取色做底时，「我的图片」本身只是这一刻壁纸色的快照：不能再被当成动态主题按壁纸整体重建
            // （那样会丢掉图片），但取色规则仍按完整主题走。
            isDynamic = false, useThemeColors = base.useThemeColors || base.isDynamic,
            keyboardBackground = BackgroundConfig(type = "image", src = src, fit = placement.fit,
                zoom = placement.zoom, alignX = placement.alignX, alignY = placement.alignY,
                overlayAlpha = veilLight, overlayAlphaDark = veilDark,
                overlayColor = base.keyboardBgLight.rgb(), overlayColorDark = base.keyboardBgDark.rgb()),
            candidateBarBackground = null,
        )
    }

    /**
     * 读入 [uri]（按 EXIF 摆正、长边缩到 2048），存成新的 JPEG，返回它的 themes/… 路径。
     * 还不启用：调整页确认后 [commit]，取消时 [discard]。
     * 在 IO 线程调用；读不出图片时抛异常，原来的图片和设置不变。
     */
    fun importImage(context: Context, uri: Uri): String = importAs(context, uri, FILE_PREFIX)

    private fun importAs(context: Context, uri: Uri, prefix: String): String {
        val bitmap = com.kingzcheung.xime.ocr.loadOcrGalleryImage(context, uri)
        val dir = themesDir(context).apply { mkdirs() }
        // 轮播图片一次选好几张，同一毫秒里也不能重名。
        val name = if (prefix == SLIDE_PREFIX) "$prefix${System.currentTimeMillis()}-${(0..99999).random()}.jpg"
            else "$prefix${System.currentTimeMillis()}.jpg"
        val target = File(dir, name)
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "无法保存图片" } }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
        return "themes/$name"
    }

    /** 启用 [src] 和它的摆放，记下沿用的主题，删掉其他旧图。在 IO 线程调用。 */
    internal fun commit(context: Context, src: String, baseThemeId: String, placement: ThemeImagePlacement) {
        check(SettingsPreferences.getPrefsPublic(context).edit()
            .putString(KEY_SRC, src).putString(KEY_BASE, baseThemeId)
            .putString(KEY_PLACEMENT, placement.encode()).commit()) { "无法保存设置" }
        val keep = src.removePrefix("themes/")
        themesDir(context).listFiles()?.filter { it.name.startsWith(FILE_PREFIX) && !it.name.startsWith(SLIDE_PREFIX) && it.name != keep }
            ?.forEach { it.delete() }
    }

    /** 还在的轮播图片（themes/…）；文件没了的跳过。 */
    fun slides(context: Context, stored: List<String>): List<String> =
        stored.filter { it.removePrefix("themes/").startsWith(SLIDE_PREFIX) && File(themesDir(context), it.removePrefix("themes/")).isFile }

    /**
     * 加几张轮播图片（按 EXIF 摆正、长边缩到 2048，和主图同样存成 JPEG），连同已有的最多 [BackgroundMotionPreferences.MAX_SLIDES] 张。
     * 读不出的图片跳过；返回加进去的张数。在 IO 线程调用。
     */
    fun addSlides(context: Context, uris: List<Uri>): Int {
        val existing = slides(context, com.kingzcheung.xime.settings.BackgroundMotionPreferences.read(context).slides)
        val room = (com.kingzcheung.xime.settings.BackgroundMotionPreferences.MAX_SLIDES - existing.size).coerceAtLeast(0)
        val added = uris.take(room).mapNotNull { uri ->
            runCatching { importAs(context, uri, SLIDE_PREFIX) }
                .onFailure { com.kingzcheung.xime.util.FileLogger.e("Appearance", "Slide image failed", it) }.getOrNull()
        }
        if (added.isNotEmpty()) com.kingzcheung.xime.settings.BackgroundMotionPreferences.setSlides(context, existing + added)
        return added.size
    }

    /** 清空轮播图片（主图不动）。在 IO 线程调用。 */
    fun clearSlides(context: Context) {
        com.kingzcheung.xime.settings.BackgroundMotionPreferences.setSlides(context, emptyList())
        themesDir(context).listFiles()?.filter { it.name.startsWith(SLIDE_PREFIX) }?.forEach { it.delete() }
    }

    /** 去掉一张轮播图片，文件一起删；其余的顺序不变。在 IO 线程调用。 */
    fun removeSlide(context: Context, src: String) {
        val prefs = com.kingzcheung.xime.settings.BackgroundMotionPreferences
        prefs.setSlides(context, prefs.read(context).slides.filter { it != src })
        File(themesDir(context), src.removePrefix("themes/")).takeIf { it.name.startsWith(SLIDE_PREFIX) }?.delete()
    }

    /**
     * 设置页的小图：主图或轮播图（themes/…）按 [maxPx] 降采样读入，不整张解码。
     * 文件没了或读不出时 null。在 IO 线程调用。
     */
    fun thumbnail(context: Context, src: String, maxPx: Int): Bitmap? {
        val file = File(themesDir(context), src.removePrefix("themes/"))
        if (!file.name.startsWith(FILE_PREFIX) || !file.isFile) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx.coerceAtLeast(1)) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(file.path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 调整页取消：删掉刚导入、没启用的图片；已在用的那张不动。 */
    fun discard(context: Context, src: String) {
        if (src == SettingsPreferences.getPrefsPublic(context).getString(KEY_SRC, null)) return
        File(themesDir(context), src.removePrefix("themes/")).takeIf { it.name.startsWith(FILE_PREFIX) }?.delete()
    }

    /**
     * 删除「我的图片」：清掉设置和图片文件，主题列表里不再有它。
     * 返回原来沿用的主题，正在用「我的图片」时换回它。在 IO 线程调用。
     */
    fun delete(context: Context): String {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val base = prefs.getString(KEY_BASE, null) ?: SoftBlueTheme.ID
        check(prefs.edit().remove(KEY_SRC).remove(KEY_BASE).remove(KEY_PLACEMENT)
            .remove(com.kingzcheung.xime.settings.BackgroundMotionPreferences.KEY_SLIDES).commit()) { "无法保存设置" }
        themesDir(context).listFiles()?.filter { it.name.startsWith(FILE_PREFIX) }?.forEach { it.delete() }
        return base
    }
}
