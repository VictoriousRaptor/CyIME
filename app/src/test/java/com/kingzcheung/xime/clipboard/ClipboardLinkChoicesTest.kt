package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 候选栏剪贴板：带链接的给干净链接、B 站给 BV 号（用户 11-01），原文照旧可选。 */
class ClipboardLinkChoicesTest {
    private fun choices(text: String) = ClipboardLinkChoices.linkChoices(text)

    @Test fun bilibiliShareKeepsOnlyPartAndTime() {
        val web = "【CyIME 输入法演示-哔哩哔哩】 https://www.bilibili.com/video/BV1GJ411x7h7/?share_source=copy_web&vd_source=0a1b2c3d&p=2&t=95"
        assertEquals(listOf("https://www.bilibili.com/video/BV1GJ411x7h7/?p=2&t=95", "BV1GJ411x7h7"), choices(web))
        // 只有链接、后面一串跟踪参数：给干净链接和 BV 号。
        assertEquals(listOf("https://www.bilibili.com/video/BV1GJ411x7h7", "BV1GJ411x7h7"),
            choices("https://www.bilibili.com/video/BV1GJ411x7h7?spm_id_from=333.1007.tianma.1-1-1.click&vd_source=abc"))
        // 手机网页版同样认。
        assertEquals("BV1GJ411x7h7", ClipboardLinkChoices.bilibiliVideoId("https://m.bilibili.com/video/BV1GJ411x7h7?p=3"))
    }

    @Test fun shortLinkIsGivenWithoutTheTitleButHasNoBv() {
        // b23.tv 短链接不联网展不开：只给链接本身，没有 BV 号。
        assertEquals(listOf("https://b23.tv/AbCdEf1"), choices("【标题-哔哩哔哩】 https://b23.tv/AbCdEf1"))
        assertEquals(listOf("b23.tv/AbCdEf1"), choices("看这个 b23.tv/AbCdEf1 哈哈"))
    }

    @Test fun otherSitesLoseTrackingButKeepRealParameters() {
        assertEquals("https://example.com/a?id=7#top",
            ClipboardLinkChoices.cleanLink("https://example.com/a?utm_source=x&id=7&fbclid=abc#top"))
        // 微信文章的 mid、sn 是打开文章要用的，不能当跟踪参数去掉。
        val wx = "https://mp.weixin.qq.com/s?__biz=MzA&mid=2650&idx=1&sn=abc"
        assertEquals(wx, ClipboardLinkChoices.cleanLink(wx))
        // YouTube 的 si 是分享来源，去掉；别的网站的 si 不动。
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=42", ClipboardLinkChoices.cleanLink("https://youtu.be/dQw4w9WgXcQ?si=Xyz&t=42"))
        assertEquals("https://example.com/?si=1", ClipboardLinkChoices.cleanLink("https://example.com/?si=1"))
    }

    @Test fun bilibiliPagesOtherThanVideosKeepTheirParameters() {
        // 搜索、收藏夹靠参数打开：只去跟踪参数，不能只剩前半段（11-18）。
        assertEquals("https://search.bilibili.com/all?keyword=CyIME&from_source=webtop_search",
            ClipboardLinkChoices.cleanLink("https://search.bilibili.com/all?keyword=CyIME&from_source=webtop_search&spm_id_from=333.1007&vd_source=abc"))
        assertEquals("https://space.bilibili.com/123/favlist?fid=456&ftype=create",
            ClipboardLinkChoices.cleanLink("https://space.bilibili.com/123/favlist?fid=456&ftype=create&spm_id_from=333.1007"))
        // 番剧页和视频页一样只留分 P、时间。
        assertEquals("https://www.bilibili.com/bangumi/play/ep123?t=30",
            ClipboardLinkChoices.cleanLink("https://www.bilibili.com/bangumi/play/ep123?from_spmid=666.25&t=30"))
    }

    @Test fun closingBracketThatBelongsToTheLinkIsKept() {
        // 维基的括号是链接的一部分；多出来的右括号（链接写在括号里）照旧不算。
        assertEquals(listOf("https://en.wikipedia.org/wiki/Mercury_(planet)"),
            choices("看 https://en.wikipedia.org/wiki/Mercury_(planet) 这个"))
        assertEquals(listOf("https://example.com/page"), choices("(see https://example.com/page)."))
        assertEquals(emptyList<String>(), choices("https://en.wikipedia.org/wiki/Mercury_(planet)"))
    }

    @Test fun nothingExtraWhenTheCopyIsAlreadyACleanLinkOrHasNoLink() {
        assertEquals(emptyList<String>(), choices("https://example.com/page"))
        assertEquals(emptyList<String>(), choices("明天下午三点开会"))
        assertNull(ClipboardLinkChoices.bilibiliVideoId("https://example.com/video/BV1GJ411x7h7"))
        // 句末标点不算链接。
        assertEquals(listOf("https://example.com/page"), choices("地址是 https://example.com/page。"))
        assertEquals(listOf("https://example.com/page"), choices("see https://example.com/page."))
    }

    @Test fun extractedItemsComeFirstForTheShortCapsuleThenTheOriginals() {
        val texts = listOf("123456", "【标题】 https://www.bilibili.com/video/BV1GJ411x7h7/?vd_source=a")
        assertEquals(
            listOf(
                ClipboardLinkChoices.Choice(1, "https://www.bilibili.com/video/BV1GJ411x7h7/", extracted = true),
                ClipboardLinkChoices.Choice(1, "BV1GJ411x7h7", extracted = true),
                ClipboardLinkChoices.Choice(0, "123456"),
                ClipboardLinkChoices.Choice(1, texts[1]),
            ),
            ClipboardLinkChoices.expand(texts))
        // 什么都提取不出来：只有原文，还是一个胶囊。
        val plain = listOf(ClipboardItem(1, "明天下午三点开会"))
        assertEquals(listOf(ClipboardLinkChoices.Choice(0, "明天下午三点开会")), ClipboardLinkChoices.expandItems(plain))
        assertEquals(0, ClipboardLinkChoices.extractedCount(plain))
    }

    private fun numbers(text: String) = ClipboardLinkChoices.numberChoices(text)

    @Test fun longNumbersInsideTextAreExtractedWithoutSeparators() {
        assertEquals(listOf("13812345678"), numbers("张三 13812345678 明天到"))
        assertEquals(listOf("09012345678"), numbers("电話は090-1234-5678です"))
        assertEquals(listOf("+8613812345678"), numbers("联系电话：+86 138 1234 5678"))
        assertEquals(listOf("6222021234567890"), numbers("卡号 6222 0212 3456 7890 户名张三"))
        // 单号和电话都在：各给一项，按出现顺序。
        assertEquals(listOf("773012345678901", "13812345678"), numbers("快递单号773012345678901，收件电话13812345678"))
    }

    @Test fun shortNumbersDatesMoneyAndCodesAreNotExtracted() {
        // 原文本身就是这串数字：不重复给。
        assertEquals(emptyList<String>(), numbers("13812345678"))
        assertEquals(emptyList<String>(), numbers("验证码 123456，5 分钟内有效"))
        assertEquals(emptyList<String>(), numbers("2026-11-08 16:00 开会"))
        assertEquals(emptyList<String>(), numbers("2026年11月8日 见"))
        assertEquals(emptyList<String>(), numbers("合计 1,234,567.89 元"))
        assertEquals(emptyList<String>(), numbers("金额 12345678.50"))
        // 连着字母的单号不拆半截；第 1 2 3 名不拼成一串。
        assertEquals(emptyList<String>(), numbers("顺丰 SF1234567890123"))
        assertEquals(emptyList<String>(), numbers("12345678abc"))
        assertEquals(emptyList<String>(), numbers("第 1 2 3 4 5 6 7 名"))
        // 链接里的数字不算。
        assertEquals(emptyList<String>(), numbers("看 https://example.com/item/12345678901?id=99999999"))
    }

    @Test fun linksAndNumbersShareTheShortCapsule() {
        assertEquals(listOf("https://example.com/a", "13812345678"),
            ClipboardLinkChoices.extractedChoices("预约 https://example.com/a?utm_source=x 电话 13812345678"))
        // 只是一个干净链接：前面没有短胶囊。
        assertEquals(emptyList<String>(), ClipboardLinkChoices.extractedChoices("https://example.com/item/12345678901"))
    }
}
