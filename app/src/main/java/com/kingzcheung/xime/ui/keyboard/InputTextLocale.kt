package com.kingzcheung.xime.ui.keyboard

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.LocaleList
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputScheme

/**
 * 键盘上的汉字按哪种写法画（候选、编码、按键）。同一个字在简体中文、繁体中文、日文、韩文里写法不同（直、骨、角…），
 * 不说是哪种语言时跟系统语言走：日文系统上打中文，候选会是日本写法。Gboard 换了键盘语言字形跟着换，这里一样。
 *
 * 中文：注音、仓颉（出繁体字）和「繁体输出」开着时按台湾写法，其他按大陆写法；日语 ja；韩语 ko。
 * 英文等字母语言不指定，跟系统走。
 */
internal fun inputTextLocaleTag(language: InputLanguage, scheme: InputScheme, traditionalOutput: Boolean): String? =
    when (language) {
        InputLanguage.CHINESE ->
            if (scheme == InputScheme.ZHUYIN || scheme == InputScheme.CANGJIE || traditionalOutput) "zh-TW" else "zh-CN"
        InputLanguage.JAPANESE -> "ja-JP"
        InputLanguage.KOREAN -> "ko-KR"
        else -> null
    }

/** 里面没自己指定 style 的文字按当前输入语言的写法画；自己指定 style 的用 [withInputTextLocale]。 */
@Composable
internal fun ProvideInputTextLocale(language: InputLanguage, scheme: InputScheme, traditionalOutput: Boolean,
    content: @Composable () -> Unit) {
    val tag = inputTextLocaleTag(language, scheme, traditionalOutput)
    val locale = remember(tag) { tag?.let { LocaleList(it) } }
    if (locale == null) content()
    else CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(localeList = locale), content = content)
}

/** 自己写的 TextStyle 也带上外面定的写法。 */
@Composable
internal fun androidx.compose.ui.text.TextStyle.withInputTextLocale(): androidx.compose.ui.text.TextStyle =
    LocalTextStyle.current.localeList?.let { copy(localeList = it) } ?: this
