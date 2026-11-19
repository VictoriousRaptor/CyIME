package com.kingzcheung.xime.ui.i18n

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * 当前界面用的英文表；中文界面（和键盘本身）是 null，文字照原样显示。
 * 由 [ProvideUiTranslations] 在设置等界面的根上给出，键盘不经过它，所以键上的中文不受影响。
 */
val LocalUiTranslations = staticCompositionLocalOf<UiTranslations?> { null }

object UiTexts {
    const val ASSET = "i18n/en.tsv"

    @Volatile private var loaded: UiTranslations? = null

    fun english(context: Context): UiTranslations = loaded ?: synchronized(this) {
        loaded ?: runCatching {
            context.applicationContext.assets.open(ASSET).bufferedReader().use { reader -> UiTranslations.parse(reader.readText()) }
        }.getOrElse { UiTranslations.EMPTY }.also { loaded = it }
    }

    /** 界面语言是英文时的表，否则 null（简体、繁体、日语界面不换：它们走各自的 strings.xml）。 */
    fun forConfiguration(context: Context, configuration: Configuration = context.resources.configuration): UiTranslations? =
        if (configuration.locales[0]?.language == "en") english(context) else null

    /** 不在 Compose 里的文字（Toast 等）用：英文界面换成英文，其余原样。 */
    fun text(context: Context, zh: String): String = forConfiguration(context)?.translate(zh) ?: zh
}

/**
 * 设置等界面里 `Toast.makeText` 的替身，参数和返回值一样：英文界面时先把中文提示换成英文，其余原样。
 * 键盘服务里的 Toast 不经过这里。
 */
object UiToast {
    fun makeText(context: Context, text: CharSequence, duration: Int): Toast =
        Toast.makeText(context, if (text is String) UiTexts.text(context, text) else text, duration)

    fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast.makeText(context, resId, duration)
}

/** 设置、字幕等界面的根上包一层：英文界面时其中的 [Text] 显示英文。 */
@Composable
fun ProvideUiTranslations(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val table = remember(configuration.locales) { UiTexts.forConfiguration(context, configuration) }
    CompositionLocalProvider(LocalUiTranslations provides table, content = content)
}

/** 显示用户自己的内容（词条、剪贴板记录、字幕原文）时包一层，里面的字一律不换。 */
@Composable
fun UntranslatedContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiTranslations provides null, content = content)
}

/** [text] 在当前界面语言下的样子。 */
@Composable
fun tr(text: String): String {
    val table = LocalUiTranslations.current
    return remember(table, text) { table?.translate(text) ?: text }
}

/**
 * 和 material3 的 Text 一样，只是英文界面时先查英文表。设置页各文件 import 这个代替
 * `androidx.compose.material3.Text`，调用处不用改。
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
        textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
        softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style,
    )
}

/** 带样式的文字原样交给 material3（样式按字的位置标的，整句换掉会错位；要翻的在拼之前用 [tr]）。 */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
        textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
        softWrap = softWrap, maxLines = maxLines, minLines = minLines, inlineContent = inlineContent,
        onTextLayout = onTextLayout, style = style,
    )
}
