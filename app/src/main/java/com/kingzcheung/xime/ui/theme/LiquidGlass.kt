package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.platform.AndroidPlatform

/**
 * 液态玻璃（主题与外观 › 材质效果，默认关；打开时把玻璃一起打开）。
 *
 * 保守版（用户 11-02「代价挺大，做得保守一点」）：给按键画一道细亮边，按住时边缘变亮、键面略亮。
 * 不跟手指、不做动画；高光路径按尺寸缓存，键盘不动不重画，按下只多画一帧（按键本来就会因按下重画）。
 * 输入法读不到背后应用的像素（模糊由系统在 ImeGlassBlurWindow 里做），所以透明背景下做不出弯折背后文字。
 * 背景是自己的图片（「我的图片」开玻璃时不透明）时像素在手里：Android 13 以上按键边缘折射这张图
 * （用户 11-02「自定义键盘背景的时候可以把按键做成这种玻璃效果」）。
 *
 * 11-15 加「液态玻璃强度」三档（[LiquidGlassStrength]）：标准就是上面这个保守版，也是默认；加强、最强是用户自己选的。
 */
internal object LiquidGlass {
    private val on = mutableStateOf(false)
    private val level = mutableStateOf(LiquidGlassStrength.STANDARD)
    // SharedPreferences 只弱引用监听器，这里必须强引用住。
    @Volatile private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 可在组合里读：设置页改了，键盘和预览立刻跟。 */
    fun enabled(context: Context): Boolean {
        if (listener == null) bind(context.applicationContext ?: context)
        return on.value
    }

    /** 强度档位，同样可在组合里读。只在液态玻璃开着时有意义。 */
    fun strength(context: Context): LiquidGlassStrength {
        if (listener == null) bind(context.applicationContext ?: context)
        return level.value
    }

    /**
     * 键盘里除按键以外的面板件（工具栏开启/按下的胶囊、剪贴板预览条、剪贴板卡片、布局/方案卡片）也画同一道细亮边。
     * 跟按键同一个条件：当前外观开着玻璃、液态玻璃开着。设置页的同款卡片没有键盘偏好（玻璃视为关），不画。
     */
    @androidx.compose.runtime.Composable
    fun surfacesOn(): Boolean =
        com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences.current.frostedGlass.enabled &&
            enabled(androidx.compose.ui.platform.LocalContext.current)

    /** 测试换了 Application 后重新读开关并监听新的偏好。 */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun rebind(context: Context) {
        val app = context.applicationContext ?: context
        listener?.let { SettingsPreferences.getPrefsPublic(app).unregisterOnSharedPreferenceChangeListener(it) }
        listener = null
        bind(app)
    }

    @Synchronized
    private fun bind(context: Context) {
        if (listener != null) return
        val prefs = SettingsPreferences.getPrefsPublic(context)
        on.value = SettingsPreferences.glassLiquid(context)
        level.value = LiquidGlassStrength.of(SettingsPreferences.glassLiquidStrength(context))
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SettingsPreferences.KEY_GLASS_LIQUID) on.value = SettingsPreferences.glassLiquid(context)
            if (key == null || key == SettingsPreferences.KEY_GLASS_LIQUID_STRENGTH)
                level.value = LiquidGlassStrength.of(SettingsPreferences.glassLiquidStrength(context))
        }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }
}

/**
 * 替代按键上的视觉样式材质：[cap] 是按键原来的键帽（裁剪 + 底色），亮边画在底色上面、文字下面。
 */
internal fun Modifier.liquidGlassCap(cap: Modifier, radius: Dp): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val palette = LocalMaterialPalette.current ?: MaterialPalette(colors.surface, colors.primary)
    val dark = palette.surface.luminance() < .5f
    val strength = LiquidGlass.strength(androidx.compose.ui.platform.LocalContext.current)
    val held = remember { mutableStateOf(false) }
    val observed = pointerInput(Unit) {
        awaitEachGesture {
            // 只旁观，不消费：不影响点击、滑动和长按。
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            held.value = true
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            held.value = false
        }
    }
    observed.then(liquidRefraction(radius, strength)).then(cap).then(liquidRim(radius, dark, held, strength))
}

/**
 * 图片背景的边缘折射：只在键帽外圈约 5dp（加强 8dp、最强 11dp）取样并弯折，保留轻微色散，键中央直接透出键盘已画好的背景。
 * 没有图片、背景半透明（再画会叠深）、Android 12 及以下都不画。
 * 不另存图片（直接用共用背景那张），按住时不鼓起，键盘不动就不重画。
 */
private fun Modifier.liquidRefraction(radius: Dp, strength: LiquidGlassStrength): Modifier =
    if (AndroidPlatform.runtimeShader) liquidRefraction33(radius, strength) else this

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.liquidRefraction33(radius: Dp, strength: LiquidGlassStrength): Modifier = composed {
    val root = LocalView.current.rootView
    val backdrop = LocalSharedKeyboardBackdrop.current?.takeIf { it.windowRoot === root && it.opacity >= .99f }
        ?: return@composed this
    val image = backdrop.image ?: return@composed this
    val shader = LiquidRefractionShader.shared ?: return@composed this
    var origin by remember { mutableStateOf<Offset?>(null) }
    onGloballyPositioned { origin = it.positionInRoot() }.drawWithCache {
        val bitmap = image.asAndroidBitmap()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val imageShader = BitmapShader(bitmap, android.graphics.Shader.TileMode.DECAL, android.graphics.Shader.TileMode.DECAL)
            .apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }
        onDrawBehind {
            val at = origin ?: return@onDrawBehind
            val frame = backdrop.frame
            if (frame.size.width <= 0 || frame.size.height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return@onDrawBehind
            val offset = frame.origin + backdrop.windowToRoot - at
            val motion = backdrop.motion?.transform?.dominant ?: BackgroundMotionTransform.Identity
            val width = frame.size.width.toFloat()
            val height = frame.size.height.toFloat()
            // Same centre and translation as withSharedBackdropMotion. Both the frame and the key
            // are relative to the Compose root, including while the IME window slides into place.
            val left = offset.x + width * ((1f - motion.scale) / 2f + motion.shiftX)
            val top = offset.y + height * ((1f - motion.scale) / 2f + motion.shiftY)
            LiquidRefractionShader.bind(shader, imageShader, size.width, size.height, radius.toPx(),
                strength.bevelDp * density, strength.bendDp * density, strength.spread,
                left, top, bitmap.width / (width * motion.scale), bitmap.height / (height * motion.scale))
            paint.shader = shader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
            paint.shader = null
        }
    }
}

/**
 * 全进程只编译一次；各键在主线程上依次设参数再画，每次改参数系统都会生成新的着色器快照，所以共用安全。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object LiquidRefractionShader {
    private const val SOURCE = """
        uniform shader image;
        uniform float2 iSize;
        uniform float radius;
        uniform float bevel;
        uniform float strength;
        uniform float spread;
        uniform float2 imageOrigin;
        uniform float2 pixelsPerUnit;
        half4 main(float2 p) {
            float2 c = iSize * 0.5;
            float r = min(radius, min(c.x, c.y));
            float2 rel = p - c;
            float2 q = abs(rel) - c + r;
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            // The unbent centre already contains the actual backdrop, including slide crossfades.
            if (d > 0.5 || d <= -bevel) return half4(0.0);
            float2 g = (q.x > 0.0 && q.y > 0.0) ? normalize(q) : ((q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
            float2 n = g * sign(rel);
            float e = 1.0 - clamp(-d / bevel, 0.0, 1.0);
            float2 uv = (p - imageOrigin) * pixelsPerUnit;
            float2 off = -n * (strength * e * e * e) * pixelsPerUnit;
            half4 red = image.eval(uv + off);
            half4 green = image.eval(uv + off * (1.0 + spread));
            half4 blue = image.eval(uv + off * (1.0 + 2.0 * spread));
            half coverage = min(red.a, min(green.a, blue.a));
            return half4(min(half3(red.r, green.g, blue.b), half3(coverage)), coverage)
                * half(e * clamp(0.5 - d, 0.0, 1.0));
        }
    """

    val shared: RuntimeShader? by lazy { try { RuntimeShader(SOURCE) } catch (_: Throwable) { null } }

    fun bind(shader: RuntimeShader, image: android.graphics.Shader, width: Float, height: Float,
        radius: Float, bevel: Float, strength: Float, spread: Float,
        left: Float, top: Float, pixelsPerX: Float, pixelsPerY: Float) {
        shader.setInputShader("image", image)
        shader.setFloatUniform("iSize", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("spread", spread)
        shader.setFloatUniform("imageOrigin", left, top)
        shader.setFloatUniform("pixelsPerUnit", pixelsPerX, pixelsPerY)
    }
}

/**
 * 一道细亮边：朝光的左上亮，对角右下有一道弱一些的回光，中间几乎没有。不画厚度渐变和阴影
 * （第一版画了，看着像塑料按钮）。加强、最强两档多画的厚度和高光见 [liquidRimGeometry]。
 */
private fun liquidRim(radius: Dp, dark: Boolean, held: androidx.compose.runtime.State<Boolean>,
    strength: LiquidGlassStrength): Modifier =
    Modifier.drawWithCache {
        val rim = liquidRimGeometry(radius, dark, strength)
        val heldFill = Color.White.copy(alpha = if (dark) .04f else .08f)
        onDrawWithContent {
            val down = held.value
            if (rim.visible) clipPath(rim.path) {
                if (down) drawPath(rim.path, heldFill)
                drawLiquidRim(rim, held = down)
            }
            drawContent()
        }
    }

/**
 * 面板件上的同一道细亮边（不含按住变亮）：画在底色上面、内容下面，[radius] 大于半高时就是胶囊。
 * 路径和渐变按尺寸缓存，只在本来就要重画时跟着画；[alpha] 在绘制阶段读，跟随按下淡出也不多组合一次。
 * 调用方先用 [LiquidGlass.surfacesOn] 判断，没开时原来的描边一点不变。
 * 「液态玻璃强度」不管面板件：这里一直是标准档的细亮边。
 */
internal fun Modifier.liquidGlassEdge(radius: Dp, alpha: () -> Float = { 1f }): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val palette = LocalMaterialPalette.current ?: MaterialPalette(colors.surface, colors.primary)
    val dark = palette.surface.luminance() < .5f
    drawWithCache {
        val rim = liquidRimGeometry(radius, dark, body = false)
        onDrawWithContent {
            val shown = alpha().coerceIn(0f, 1f)
            if (rim.visible && shown > 0f) clipPath(rim.path) { drawLiquidRim(rim, alpha = shown) }
            drawContent()
        }
    }
}
