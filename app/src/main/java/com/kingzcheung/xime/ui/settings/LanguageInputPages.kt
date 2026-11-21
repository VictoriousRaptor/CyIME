package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AlternateEmail
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.FontDownload
import androidx.compose.material.icons.twotone.Functions
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material.icons.twotone.SpaceBar
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/*
 * 语言与方案 › 中文输入 / 英文输入（11-02 整理设置）：原来都排在「语言与方案」一页里，约 30 行，模糊音 13 行夹在中间；
 * 现在各占一页，语言与方案只留两行入口。各组、各开关原样搬来，名字、说明、存的值和行为都不变。
 */

/** 中文输入：中文输出、拼音纠错、模糊音。 */
@Composable
fun ChineseInputSettingsContent(onBack: () -> Unit) {
    val list = rememberLazyListState()
    KeyboardSettingsScaffold("中文输入", onBack, preview = true, modifier = Modifier.testTag("chinese-input-settings")) { modifier ->
        SettingsSearchAutoScroll(list)
        LazyColumn(modifier = modifier.settingsGutterScroll(list).testTag("chinese-input-settings-list")
            .padding(horizontal = 16.dp, vertical = 8.dp),
            state = list, verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding()) {
            item(key = "chinese-output") { ChineseOutputSection() }
            item(key = "neighbor-correction") { NeighborCorrectionSection() }
            item(key = "fuzzy-pinyin") { FuzzyPinyinSection() }
        }
    }
}

/** 英文输入：网址邮箱切英文、自动加空格、邮箱后缀。 */
@Composable
fun EnglishInputSettingsContent(onBack: () -> Unit) {
    val list = rememberLazyListState()
    KeyboardSettingsScaffold("英文输入", onBack, preview = true, modifier = Modifier.testTag("english-input-settings")) { modifier ->
        SettingsSearchAutoScroll(list)
        LazyColumn(modifier = modifier.settingsGutterScroll(list).testTag("english-input-settings-list")
            .padding(horizontal = 16.dp, vertical = 8.dp),
            state = list, verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding()) {
            item(key = "english-input") { EnglishInputSection() }
        }
    }
}

@Composable
private fun ChineseOutputSection() {
    val context = LocalContext.current
    val input = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
    val hasCylm = remember(context) {
        val dir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(context)
        dir.resolve("cylm_model.lm").isFile && dir.resolve("cylm_lexicon.cylx").isFile
    }
    SettingsSection(title = "中文输出") {
        if (hasCylm) {
            SettingsToggleItem(Icons.TwoTone.Translate, "全拼长句备选",
                "打长句时多给一个整句候选",
                checked = input.cylmLongAlternative, onCheckedChange = { input.copy(cylmLongAlternative = it).save(context) })
            HorizontalDivider()
        }
        SettingsToggleItem(Icons.TwoTone.Translate, "繁体输出",
            "候选和上屏都用繁体字",
            checked = input.traditionalOutput, onCheckedChange = { input.copy(traditionalOutput = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.TRADITIONAL)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.FontDownload, "生僻字",
            "在常用字后面给出中文生僻字",
            checked = input.rareCharacters, onCheckedChange = { input.copy(rareCharacters = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.RARE_CHARACTERS)
        CandidateExtraLegend("rare-characters-legend", "chan", listOf("缠", "馋", "产"), "瀺", input.rareCharacters,
            skipped = true)
        HorizontalDivider()
        // 11-20 从生僻字分出来（他：是日语的字就叫日语的字，是什么就叫什么），各自默认关，候选后面注「日」「韩」「粤」。
        SettingsToggleItem(Icons.TwoTone.FontDownload, "日文汉字",
            "在中文字后面给出日本的字和日本人名地名",
            checked = input.japaneseKanji, onCheckedChange = { input.copy(japaneseKanji = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.JAPANESE_KANJI)
        CandidateExtraLegend("japanese-kanji-legend", "ze", listOf("泽", "则", "责"), "沢", input.japaneseKanji,
            skipped = true)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.FontDownload, "韩文汉字",
            "在中文字后面给出韩国自造的汉字",
            checked = input.koreanHanja, onCheckedChange = { input.copy(koreanHanja = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.KOREAN_HANJA)
        CandidateExtraLegend("korean-hanja-legend", "jia", listOf("家", "加", "假"), "乫", input.koreanHanja,
            skipped = true)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.FontDownload, "粤语字",
            "在中文字后面给出粤语用字",
            checked = input.cantoneseCharacters, onCheckedChange = { input.copy(cantoneseCharacters = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.CANTONESE_CHARACTERS)
        CandidateExtraLegend("cantonese-characters-legend", "qu", listOf("去", "区", "取"), "佢", input.cantoneseCharacters,
            skipped = true)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.Schedule, "时间日期候选",
            "打“时间”“日期”“星期”时给出当前时间",
            checked = input.datetimeCandidates, onCheckedChange = { input.copy(datetimeCandidates = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.DATETIME)
        val now = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date()) }
        CandidateExtraLegend("datetime-candidates-legend", "shijian", listOf("时间"), now, input.datetimeCandidates)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.EmojiEmotions, "候选里显示表情",
            "在词后面给出对应的表情",
            checked = input.candidateEmoji, onCheckedChange = { input.copy(candidateEmoji = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.EMOJI)
        CandidateExtraLegend("candidate-emoji-legend", "kaixin", listOf("开心"), "😄", input.candidateEmoji)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.Functions, "打符号名出符号",
            "打符号的名字时多给一个符号，如 加号 +、摄氏度 ℃、平方 ²、约等于 ≈",
            checked = input.symbolNames, onCheckedChange = { input.copy(symbolNames = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.SYMBOL_NAMES)
        CandidateExtraLegend("symbol-names-legend", "sheshidu", listOf("摄氏度"), "℃", input.symbolNames)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.Functions, "希腊字母",
            "打 xita、pi、alpha 时给出 θ、π、α",
            checked = input.greekLetters, onCheckedChange = { input.copy(greekLetters = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.GREEK_LETTERS)
        CandidateExtraLegend("greek-letters-legend", "pi", listOf("批", "皮"), "π", input.greekLetters)
    }
}

@Composable
private fun EnglishInputSection() {
    val context = LocalContext.current
    val input = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
    SettingsSection(title = "英文输入") {
        SettingsToggleItem(Icons.TwoTone.Link, "网址、邮箱框自动切英文",
            "在网址和邮箱输入框里自动用英文",
            checked = input.addressFieldsEnglish, onCheckedChange = { input.copy(addressFieldsEnglish = it).save(context) })
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.SpaceBar, "选词后自动加空格",
            "英文选词后自动加一个空格",
            checked = input.englishAutoSpace, onCheckedChange = { input.copy(englishAutoSpace = it).save(context) })
        AutoSpaceLegend("english-auto-space-legend", "点 world", "hello world", "|", input.englishAutoSpace)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.SpaceBar, "标点后自动加空格",
            "英文打 , . ? ! 后自动加一个空格",
            checked = input.englishPunctuationSpace, onCheckedChange = { input.copy(englishPunctuationSpace = it).save(context) })
        AutoSpaceLegend("english-punctuation-space-legend", "打 ,", "Hello,", "|", input.englishPunctuationSpace)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.AlternateEmail, "打 @ 时给邮箱后缀",
            "打 @ 后给出 gmail.com 等常用后缀",
            checked = input.emailSuffixes, onCheckedChange = { input.copy(emailSuffixes = it).save(context) })
        EmailSuffixLegend(input.emailSuffixes)
    }
}
