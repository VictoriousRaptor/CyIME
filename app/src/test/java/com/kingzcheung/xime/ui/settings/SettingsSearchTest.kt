package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun titles(q: String) = searchSettings(q).map { it.title }

    @Test fun layoutManagementIsReachableWithThePreviousName() {
        for (query in listOf("布局管理", "自定义布局", "布局排序")) {
            assertTrue(searchSettings(query).any { it.title == "布局管理" && it.target == SEARCH_TARGET_CUSTOM_LAYOUT })
        }
    }

    @Test fun everydayWordsFindTheSwitch() {
        // 用户以为九键长按坏了：搜“长按”要能找到九键拂动。
        assertTrue("拂动选字母" in titles("长按"))
        assertTrue("数字框自动数字键盘" in titles("验证码"))
        assertTrue("按键效果" in titles("振动"))
    }

    @Test fun englishWordsFindTheGlossSetting() {
        // 用户 10-30：想在中文键盘里选英文单词，搜“单词”“英文”找不到要选「释义」的那一项。
        for (q in listOf("单词", "英文单词", "选英文", "下一项")) assertEquals(q, "候选注释内容", titles(q).first())
        assertTrue("候选注释内容" in titles("英文"))
    }

    /** 11-02 整理：中文输出、拼音纠错、模糊音收进「中文输入」页，英文输入单独一页；搜到的都去那一页。 */
    @Test fun chineseAndEnglishInputItemsOpenTheirOwnPages() {
        val chinese = listOf("繁体输出", "生僻字", "时间日期候选", "候选里显示表情", "打符号名出符号", "希腊字母",
            "邻键纠错", "双拼邻键纠错", "九键按数字也模糊", "模糊音也用于双拼", "模糊音")
        val english = listOf("网址、邮箱框自动切英文", "选词后自动加空格", "标点后自动加空格", "打 @ 时给邮箱后缀")
        for (title in chinese) SettingsSearchIndex.single { it.title == title }.let {
            assertEquals(title, SettingsRoutes.ChineseInput, it.target)
            assertEquals(title, "输入 › 语言与方案 › 中文输入", it.path)
        }
        for (title in english) SettingsSearchIndex.single { it.title == title }.let {
            assertEquals(title, SettingsRoutes.EnglishInput, it.target)
            assertEquals(title, "输入 › 语言与方案 › 英文输入", it.path)
        }
        assertEquals(SettingsRoutes.ChineseInput, settingsJumpEntry("语言与方案 › 中文输入")?.target)
    }

    @Test fun titleMatchesComeFirst() {
        assertEquals("拂动选字母", titles("拂动").first())
    }

    @Test fun allTermsMustMatchAndBlankFindsNothing() {
        assertEquals("假名方向提示", titles("假名 方向").first())
        assertTrue(titles("假名 方向").none { it == "按键效果" })
        assertTrue(titles("   ").isEmpty())
        assertTrue(titles("不存在的设置词").isEmpty())
    }

    @Test fun layoutSubPagesExistAndEveryPageIsSearchable() {
        val pages = LayoutDisplayPages.map { it.first }.toSet()
        val linked = SettingsSearchIndex.mapNotNull { it.target.substringAfter("?page=", "").ifEmpty { null } }.toSet()
        assertEquals(pages, linked)
    }

    /** 「时长与距离」默认收起：里面每一项搜到都要能让它自动展开（用户 10-31）。 */
    @Test fun foldedTimingItemsExpandOnSearch() {
        val folded = SettingsSearchIndex.filter { it.section == "时长与距离" }.map { it.title }
        assertEquals(6, folded.size)
        assertTrue("没登记自动展开：${folded - TIMING_SEARCH_KEYS}", TIMING_SEARCH_KEYS.containsAll(folded))
    }

    /** 滑行（英文、中文、九键和两个选项）11-09 合到「键盘布局 › 滑行输入」一页，搜「滑行输入」先出这一页，各项都在同一页。 */
    @Test fun glideSettingsLiveOnOnePage() {
        val found = searchSettings("滑行输入")
        assertEquals("滑行输入", found.first().title)
        assertEquals(LayoutInputCategory.Glide.route, found.first().target)
        for (title in listOf("英文滑行", "中文滑行", "九键滑行", "滑行时实时显示词", "逐字删除中文滑行")) {
            val entry = found.single { it.title == title }
            assertEquals(title, LayoutInputCategory.Glide.route, entry.target)
            assertEquals(title, "输入 › 键盘布局 › 滑行输入", entry.path)
        }
        assertTrue("九键滑行" in titles("九宫格 滑行"))
    }

    /** 新设置树：每项只出现一处，路径都从首页五组之一开始。 */
    @Test fun everySettingLivesInOnePlaceUnderTheNewGroups() {
        val dup = SettingsSearchIndex.groupBy { it.title }.filterValues { it.size > 1 }.keys
        assertTrue("重复：$dup", dup.isEmpty())
        val groups = setOf("配置选择", "输入", "外观", "智能", "系统")
        val stray = SettingsSearchIndex.filter { it.path.substringBefore(" › ") !in groups }.map { it.title }
        assertTrue("不在新分组下：$stray", stray.isEmpty())
    }

    /** 说明里的词、同义说法、语音输入常见的同音错字都能找到。 */
    @Test fun descriptionsSynonymsAndVoiceTypos() {
        assertEquals("触觉反馈模式", titles("触觉反馈").first())
        assertTrue("触觉反馈模式" in titles("震动"))
        assertTrue("按键振动振幅" in titles("震动强度"))
        assertTrue("空格两侧的标点" in titles("拳角"))
        assertTrue("空格两侧的标点" in titles("板角"))
        assertTrue("靠边停靠" in titles("扶窗"))
        assertTrue("游戏直通" in titles("打游戏"))
        assertTrue("游戏直通" in titles("WASD"))
        assertTrue("显示模式" in titles("夜间模式"))
        assertTrue("玻璃效果" in titles("毛玻璃"))
        assertTrue("卡片显示行数" in titles("剪切板"))
        assertTrue("性能与省电" in titles("卡顿"))
        assertTrue("我的短语" in titles("快捷短语"))
        assertTrue("Ctrl + 空格" in titles("ctrl"))
        assertTrue("Ctrl + 空格" in titles("ＣＴＲＬ"))
    }

    /** 开着「繁体输出」搜设置：繁体字按简体找（10-31 新用户模拟里搜“十四鍵”什么都没找到）。 */
    @Test fun traditionalCharactersFindSimplifiedSettings() {
        assertEquals(titles("繁体输出"), titles("繁體輸出"))
        assertTrue("繁体输出" in titles("繁體"))
        assertTrue("繁体输出" in titles("注音轉簡體"))
        assertEquals(titles("14键"), titles("十四鍵"))
        assertTrue(titles("十四鍵").isNotEmpty())
        assertTrue("卡片显示行数" in titles("剪貼板"))
    }

    /** 「时间日期候选」按说法能搜到（用户 10-31 要的功能）。 */
    @Test fun timeAndDateCandidatesAreSearchable() {
        assertTrue("时间日期候选" in titles("当前时间"))
        assertTrue("时间日期候选" in titles("日期"))
    }

    /** 「打符号名出符号」按符号名和说法能搜到。 */
    @Test fun symbolNameCandidatesAreSearchable() {
        assertTrue("打符号名出符号" in titles("加号"))
        assertTrue("打符号名出符号" in titles("符号名"))
    }

    /** 「希腊字母」按字母名和说法能搜到。 */
    @Test fun greekLetterCandidatesAreSearchable() {
        assertTrue("希腊字母" in titles("希腊"))
        assertTrue("希腊字母" in titles("西塔"))
        assertTrue("希腊字母" in titles("alpha"))
    }

    /** 14 键默认没启用：搜“14键”要能找到启用它的「已安装的方案」，不只是 14 键的手势设置。 */
    @Test fun fourteenKeySearchFindsWhereToEnableIt() {
        assertTrue("已安装的方案" in titles("14键"))
        assertTrue("已安装的方案" in titles("启用14键"))
    }

    /** 词没有连着出现时按相邻两字近似匹配：“候选字号”“按键声音大小”这类自己组的说法。 */
    @Test fun nearMatchesFindSettingsWithoutExactWords() {
        assertEquals("候选字大小", titles("候选字号").first())
        assertTrue("音量大小" in titles("按键声音大小"))
        assertTrue("长按语言键弹出列表" in titles("语言键长按时间"))
        // 只沾一个字的不算：太宽的近似会把整张列表都翻出来。
        assertTrue(titles("键盘高度调节").size < 5)
    }

    /** 「数字行」：用户说法「数字栏」「平板一排数字」都能找到。 */
    @Test fun numberRowIsSearchable() {
        assertTrue("数字行" in titles("数字栏"))
        assertTrue("数字行" in titles("平板 一排数字"))
    }

    /** 11-01 整理：新功能和用户自己的说法都能找到。 */
    @Test fun newFeaturesAndEverydayWordsAreSearchable() {
        assertTrue("中英混输" in titles("中英混打"))
        assertTrue("拍照识别模型" in titles("OCR"))
        assertTrue("拍照识别模型" in titles("拍照识字"))
        assertTrue("逗号键分词" in titles("隔音符"))
        assertTrue("中文滑行" in titles("划词"))
        assertTrue("生僻字" in titles("罕用字"))
        assertTrue("插件市场" in titles("扩展市场"))
        assertTrue("按键音色" in titles("案件音色"))
        assertTrue("浅色遮罩" in titles("变暗"))
        assertTrue("深色遮罩" in titles("背景暗度"))
        assertTrue("数字转阿拉伯数字" in titles("阿拉伯数字"))
        assertTrue("数字转阿拉伯数字" in titles("中文数字"))
        assertTrue("语音纠错" in titles("纠错"))
        assertTrue("语音纠错" in titles("同音字"))
    }

    /** 11-09 反馈：按「取消候选按钮」「关闭候选按钮」找候选栏最左边的取消键，排第一。 */
    @Test fun candidateCancelButtonFoundByOtherWordOrders() {
        for (q in listOf("取消候选按钮", "关闭候选按钮", "候选时取消按钮", "取消候选", "关闭候选", "关闭按钮")) {
            assertEquals(q, "候选栏取消按钮", titles(q).first())
        }
        assertTrue("联想关闭键用叉号" in titles("取消联想"))
        assertTrue("键盘尺寸与位置" in titles("单手模式"))
    }

    /** 字幕是插件（11-05）：装着时搜到字幕插件页里的设置；没装时这些换成一条「字幕」去插件市场。 */
    @Test fun captionSettingsFollowThePlugin() {
        val found = searchSettings("字幕")
        assertTrue(found.any { it.title == "自动保存字幕" && it.target == CAPTION_PLUGIN_TARGET })
        assertEquals(found, withCaptionPluginState(found, captionActive = true))
        val offered = withCaptionPluginState(found, captionActive = false)
        assertTrue(offered.none { it.target == CAPTION_PLUGIN_TARGET })
        assertEquals(1, offered.count { it.target == SettingsRoutes.MarketPlugins && it.title == "字幕" })
        // 搜别的不受影响。
        val other = searchSettings("繁体输出")
        assertEquals(other, withCaptionPluginState(other, captionActive = false))
    }

    /** 定位的锚点必须真在页面源码里，改了行标题而没改索引会在这里失败。 */
    @Test fun everyAnchorExistsOnItsPage() {
        val dir = java.io.File("src/main/java/com/kingzcheung/xime/ui/settings")
        // 「拼音纠错」里每种纠错一行，标题来自 SpellingPatches.Typo，页面上按它循环画出来。
        val typoTitles = java.io.File("src/main/java/com/kingzcheung/xime/settings/SpellingPatches.kt")
        val source = (dir.listFiles { f -> f.extension == "kt" && f.name != "SettingsSearch.kt" }!! + typoTitles)
            .joinToString("\n") { it.readText() }
        val missing = SettingsSearchIndex.flatMap { listOfNotNull(it.anchor, it.section) }.distinct()
            .filter { "\"$it\"" !in source }
        assertTrue("页面上找不到：$missing", missing.isEmpty())
    }

    @Test fun focusPrefersTheRowAndFallsBackToItsGroup() {
        val entry = SettingsSearchIndex.first { it.title == "移动光标时振动" }
        // 行此刻不显示（不能移动光标时）：只有组在页面上，组亮。
        SettingsSearchFocus.open(entry)
        val first = SettingsSearchFocus.request!!
        SettingsSearchFocus.register("光标与空格")
        assertTrue(SettingsSearchFocus.claim("光标与空格", first))
        assertNull(SettingsSearchFocus.request)
        // 行和组都在：组让给行，行亮一次后请求清空。
        SettingsSearchFocus.open(entry)
        val second = SettingsSearchFocus.request!!
        SettingsSearchFocus.register("移动光标时振动")
        assertFalse(SettingsSearchFocus.claim("光标与空格", second))
        assertTrue(SettingsSearchFocus.claim("移动光标时振动", second))
        assertFalse(SettingsSearchFocus.claim("移动光标时振动", second))
        SettingsSearchFocus.unregister("光标与空格")
        SettingsSearchFocus.unregister("移动光标时振动")
        // 整页条目不带定位。
        SettingsSearchFocus.open(SettingsSearchIndex.first { it.title == "主题与外观" })
        assertNull(SettingsSearchFocus.request)
    }
}
