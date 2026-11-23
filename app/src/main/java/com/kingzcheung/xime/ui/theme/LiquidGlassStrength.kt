package com.kingzcheung.xime.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp

/**
 * 液态玻璃强度（主题与外观 › 材质效果 › 液态玻璃强度，用户 11-15「有用户想加强液态玻璃的效果」「三挡差不多了」）。
 *
 * [STANDARD] 就是加这一项之前的样子，也是默认值：数一个都不能动，没选过的人看到的和原来一样。
 * 后两档只加在按键上。11-22 改（用户：「加强的看起来很丑」，群里：「没有折射和色散，只有模糊」）：
 * 原来后两档是更粗的亮边加三圈白描边和上半一层白，像塑料边框；折射画在键帽底色下面，浅色键 80% 不透明时几乎被盖住。
 * 现在后两档在 Android 13 以上画 [lens]：键帽上面、文字下面一圈透镜，图片背景时把键外面的图弯进边里，
 * 带色散、提一点饱和度，高光按光照方向渐变；三圈白描边和上半那层白去掉（[bandDp]、[sheenAlpha] 等为 0）。
 * Android 12 及以下、着色器编译不过时只剩更粗的亮边。
 * 模糊和两个不透明度不跟档位变，仍然只听「透明度与模糊」的滑条。面板件（工具栏胶囊、卡片）的细亮边也不变。
 *
 * [id] 存进设置（[com.kingzcheung.xime.settings.SettingsPreferences.KEY_GLASS_LIQUID_STRENGTH]），不要改。
 */
internal enum class LiquidGlassStrength(
    val id: String,
    val label: String,
    /** 亮边粗细。 */
    val strokeDp: Float,
    /** 亮边沿左上到右下四处的白色不透明度：深色、浅色。 */
    private val rimDark: FloatArray,
    private val rimLight: FloatArray,
    /** 亮边里面那圈厚度有多宽；0 = 不画。 */
    val bandDp: Float,
    /** 厚度最亮处（左上）的白色不透明度，深色下的数；浅色乘 1.5。 */
    private val bandAlpha: Float,
    /** 浅色下厚度的背光一侧（右下）改画很淡的暗边：白键上画白色显不出来。这是最深处的黑色不透明度。 */
    private val shadeAlpha: Float,
    /** 键面上半那层淡高光顶上的白色不透明度，深色下的数；浅色乘 1.6。 */
    private val sheenAlpha: Float,
    /**
     * 键帽底色下面那圈图片背景折射（标准档用）：折射圈的宽度、最大偏移、色散（绿、蓝各比红多偏这么多倍、两倍）。
     * 后两档在有 [lens] 时不画这一圈（透镜已经画在键帽上面）。
     */
    val bevelDp: Float,
    val bendDp: Float,
    val spread: Float,
    /** 键帽上面的透镜；null = 不画（标准档）。 */
    val lens: LiquidLens? = null,
) {
    STANDARD("standard", "标准", .75f, floatArrayOf(.45f, .10f, .06f, .22f), floatArrayOf(.75f, .10f, .06f, .35f),
        0f, 0f, 0f, 0f, 5f, 9.5f, .03f),
    STRONG("strong", "加强", 1.2f, floatArrayOf(.72f, .16f, .10f, .40f), floatArrayOf(.90f, .16f, .10f, .50f),
        0f, 0f, 0f, 0f, 8f, 16f, .05f,
        LiquidLens(bevelDp = 9f, bendDp = 18f, spread = .10f, visibility = .80f, saturation = 1.25f,
            specular = .65f, backLight = .28f, specularWidthDp = 1f, glow = .08f, shade = .07f)),
    STRONGEST("strongest", "最强", 1.7f, floatArrayOf(.95f, .24f, .14f, .58f), floatArrayOf(1f, .24f, .14f, .65f),
        0f, 0f, 0f, 0f, 11f, 24f, .08f,
        LiquidLens(bevelDp = 12f, bendDp = 30f, spread = .16f, visibility = .90f, saturation = 1.4f,
            specular = .9f, backLight = .4f, specularWidthDp = 1.2f, glow = .14f, shade = .10f));

    fun rimAlphas(dark: Boolean): FloatArray = if (dark) rimDark else rimLight
    fun bandAlpha(dark: Boolean): Float = (bandAlpha * if (dark) 1f else 1.5f).coerceAtMost(1f)
    fun shadeAlpha(dark: Boolean): Float = if (dark) 0f else shadeAlpha
    fun sheenAlpha(dark: Boolean): Float = (sheenAlpha * if (dark) 1f else 1.6f).coerceAtMost(1f)

    companion object {
        /** 没存过、或存的是认不得的值（别的版本写的）都按标准。 */
        fun of(id: String?): LiquidGlassStrength = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

/**
 * 加强、最强的透镜（11-22）。圆角矩形外圈 [bevelDp] 宽的一圈当成凸透镜的边：越靠外越斜，
 * 偏移按圆弧走（里面平、最外面最陡），最外面往外取 [bendDp] 远的图，所以键外面的图被弯进边里。
 *
 * - [spread]：色散。五次取样，红偏得最少、蓝最多，各通道摊在相邻几次上，边上是柔和的彩虹而不是红绿两道线。
 * - [visibility]：这一圈透镜盖住键帽底色多少（最外面）；剩下的透出底色，等于用键色轻轻染一下。
 * - [saturation]：透镜里的图提一点饱和度。
 * - [specular]、[backLight]、[specularWidthDp]：高光沿边走，光从左上来，最亮在左上、右下有弱一些的回光，
 *   往里按 [specularWidthDp] 指数淡掉；[glow] 是左上那一侧透镜里多一层很淡的亮。
 * - [shade]：浅色、没有图片时右下那一侧很淡的暗边（白键上的白高光看不见）。
 */
internal class LiquidLens(
    val bevelDp: Float,
    val bendDp: Float,
    val spread: Float,
    val visibility: Float,
    val saturation: Float,
    val specular: Float,
    val backLight: Float,
    val specularWidthDp: Float,
    val glow: Float,
    val shade: Float,
)

/**
 * 一个键（或面板件）的亮边：[path] 是亮边走的圆角矩形，画的时候也裁在它里面。
 * [bandWidths] 空着、[sheen] 为 null 就是只有一道细亮边（标准档和面板件）。
 */
internal class LiquidRimGeometry(
    val path: Path, val brush: Brush, val stroke: Float, val visible: Boolean,
    val bandWidths: FloatArray, val bandBrush: Brush?, val sheen: Brush?,
)

/**
 * 按尺寸算一次、缓存起来的亮边。[body] 为 false（面板件）时不画厚度和高光。
 *
 * 厚度：亮边里面叠三圈由宽到窄的实色，越靠边越亮，分三级，不用模糊也不画阴影
 * （第一版画过厚度渐变加阴影，看着像塑料按钮）。朝光的左上最亮，右下有一道弱一些的回光；
 * 浅色下右下换成很淡的暗边。
 */
internal fun CacheDrawScope.liquidRimGeometry(
    radius: Dp, dark: Boolean, strength: LiquidGlassStrength = LiquidGlassStrength.STANDARD, body: Boolean = true,
): LiquidRimGeometry {
    val stroke = strength.strokeDp * density
    val inset = stroke / 2f
    val corner = (radius.toPx() - inset).coerceIn(0f, size.minDimension / 2f)
    val bounds = Rect(inset, inset, size.width - inset, size.height - inset)
    val path = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(corner))) }
    val end = Offset(size.width, size.height)
    val rim = strength.rimAlphas(dark)
    val brush = Brush.linearGradient(rim.map { Color.White.copy(alpha = it) }, Offset.Zero, end)
    val band = strength.bandDp * density
    val bandAlpha = strength.bandAlpha(dark) / LIQUID_BAND_STEPS
    val drawBody = body && band > 0f && bandAlpha > 0f
    val bandBrush = if (!drawBody) null else {
        val shade = strength.shadeAlpha(dark) / LIQUID_BAND_STEPS
        val back = if (shade > 0f) listOf(Color.Black.copy(alpha = shade * .35f), Color.Black.copy(alpha = shade))
            else listOf(Color.White.copy(alpha = bandAlpha * .12f), Color.White.copy(alpha = bandAlpha * .55f))
        Brush.linearGradient(listOf(Color.White.copy(alpha = bandAlpha), Color.White.copy(alpha = bandAlpha * .22f)) + back,
            Offset.Zero, end)
    }
    // 画的时候裁在 path 里面，所以每圈只露出里面一半：最宽的一圈正好 band 宽。
    val widths = if (!drawBody) FloatArray(0) else
        FloatArray(LIQUID_BAND_STEPS) { band * 2f * (LIQUID_BAND_STEPS - it) / LIQUID_BAND_STEPS }
    val sheen = strength.sheenAlpha(dark).takeIf { body && it > 0f }?.let {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = it), Color.Transparent), 0f, size.height * .5f)
    }
    return LiquidRimGeometry(path, brush, stroke, bounds.width > 0f && bounds.height > 0f, widths, bandBrush, sheen)
}

private const val LIQUID_BAND_STEPS = 3

/**
 * 画亮边（先画高光和厚度，亮边压在最上面）。调用方已经裁在 [LiquidRimGeometry.path] 里。
 * 标准档只有最后那一笔，和加强度之前画的完全一样。
 */
internal fun DrawScope.drawLiquidRim(rim: LiquidRimGeometry, held: Boolean = false, alpha: Float = 1f) {
    rim.sheen?.let { drawPath(rim.path, it, alpha = alpha) }
    rim.bandBrush?.let { brush -> for (width in rim.bandWidths) drawPath(rim.path, brush, alpha = alpha, style = Stroke(width)) }
    drawPath(rim.path, rim.brush, alpha = alpha, style = Stroke(if (held) rim.stroke * 1.6f else rim.stroke))
}

/**
 * 设置页「液态玻璃强度」三张卡片里的示意键：用和键盘上同一段代码画这一档（不含图片折射）。
 * 后两档在 Android 13 以上画透镜的高光（[liquidLensSample]），否则画亮边。[dark] 是这个键的底色算不算深色。
 */
internal fun Modifier.liquidGlassSample(radius: Dp, dark: Boolean, strength: LiquidGlassStrength): Modifier =
    then(liquidLensSample(radius, dark, strength) ?: Modifier.drawWithCache {
        val rim = liquidRimGeometry(radius, dark, strength)
        onDrawWithContent {
            if (rim.visible) clipPath(rim.path) { drawLiquidRim(rim) }
            drawContent()
        }
    })
