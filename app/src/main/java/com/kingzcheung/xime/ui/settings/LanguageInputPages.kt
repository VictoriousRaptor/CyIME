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
                "连打六个字以上时，CyLM 可在原有整句后补一个读音相符的候选。",
                checked = input.cylmLongAlternative, onCheckedChange = { input.copy(cylmLongAlternative = it).save(context) })
            HorizontalDivider()
        }
        SettingsToggleItem(Icons.TwoTone.Translate, "繁体输出",
            "拼音、双拼、仓颉和注音的候选与上屏使用繁体字。",
            checked = input.traditionalOutput, onCheckedChange = { input.copy(traditionalOutput = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.TRADITIONAL)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.FontDownload, "生僻字",
            "全拼、双拼(26 键和 14 键)和九键打一个完整拼音(chan、gui；双拼打满两个键)时，在常用字后面补上约 1.8 万个生僻字(瀺、槻)。",
            checked = input.rareCharacters, onCheckedChange = { input.copy(rareCharacters = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.RARE_CHARACTERS)
        CandidateExtraLegend("rare-characters-legend", "gui", listOf("鬼", "贵", "归"), "槻", input.rareCharacters,
            skipped = true)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.Schedule, "时间日期候选",
            "打“时间”“日期”“星期”时显示本机时间或日期。输入 ts(九键 87)还可选当前 Unix 时间戳。",
            checked = input.datetimeCandidates, onCheckedChange = { input.copy(datetimeCandidates = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.DATETIME)
        val now = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date()) }
        CandidateExtraLegend("datetime-candidates-legend", "shijian", listOf("时间"), now, input.datetimeCandidates)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.EmojiEmotions, "候选里显示表情",
            "打「开心」「爱心」这类词时，在词后面多出对应的表情(😄、❤️)。",
            checked = input.candidateEmoji, onCheckedChange = { input.copy(candidateEmoji = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.EMOJI)
        CandidateExtraLegend("candidate-emoji-legend", "kaixin", listOf("开心"), "😄", input.candidateEmoji)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.Functions, "打符号名出符号",
            "打「加号」「问号」「括号」这类符号名时，在词后面多出对应的符号(+、？、（）)，词本身的位置不变。",
            checked = input.symbolNames, onCheckedChange = { input.copy(symbolNames = it).save(context) },
            costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.SYMBOL_NAMES)
        CandidateExtraLegend("symbol-names-legend", "jiahao", listOf("加号"), "+", input.symbolNames)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.Functions, "希腊字母",
            "打 xita、pi、alpha 这类字母名时，第 3 个候选给出希腊字母(θ、π、α)，前两个词不变。",
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
            "关掉后这两种框沿用当前语言。密码框照旧只能打英文。",
            checked = input.addressFieldsEnglish, onCheckedChange = { input.copy(addressFieldsEnglish = it).save(context) })
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.SpaceBar, "选词后自动加空格",
            "英文(含西班牙语、英文单词方案)选词后自动补一个空格，接着打下一个词不用再按空格。紧接着按空格不会多出一个；" +
                "打 , . ? ! : ; 时标点挪到空格前面；退格删掉这个空格。",
            checked = input.englishAutoSpace, onCheckedChange = { input.copy(englishAutoSpace = it).save(context) })
        AutoSpaceLegend("english-auto-space-legend", "点 world", "hello world", "|", input.englishAutoSpace)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.SpaceBar, "标点后自动加空格",
            "英文(含西班牙语、英文单词方案)键盘在字母后面打 , . ? ! : ; 时自动跟一个空格(Hello, world)。紧接着按空格不会多出一个，" +
                "连打 ... ?! 空格留在最后；3.14、1,000、10:30 和网址、邮箱、密码框不加；退格删掉这个空格。",
            checked = input.englishPunctuationSpace, onCheckedChange = { input.copy(englishPunctuationSpace = it).save(context) })
        AutoSpaceLegend("english-punctuation-space-legend", "打 ,", "Hello,", "|", input.englishPunctuationSpace)
        HorizontalDivider()
        SettingsToggleItem(Icons.TwoTone.AlternateEmail, "打 @ 时给邮箱后缀",
            "在邮箱名后面打 @，候选栏给 qq.com、163.com、gmail.com 这些常用后缀，点一下补全；接着打的字只留开头对得上的。" +
                "密码框里不给。",
            checked = input.emailSuffixes, onCheckedChange = { input.copy(emailSuffixes = it).save(context) })
        EmailSuffixLegend(input.emailSuffixes)
    }
}
