package com.kingzcheung.xime.clipboard

/**
 * 候选栏剪贴板胶囊里，带链接的那一条再给「干净链接」和 B 站的 BV 号（用户 11-01：
 * 复制 B 站分享是「标题 + 链接」一整段，想只贴链接；链接后面一大串跟踪参数要去掉，分 P、时间留着）。
 * 夹在文字里的长数字（手机号、单号、卡号）也单独给一项，去掉中间的空格和横线（用户 11-08）。
 *
 * 只用已经读到的文字，不多读剪贴板，不联网：b23.tv 短链接展不开，只给短链接本身。
 */
internal object ClipboardLinkChoices {
    /**
     * 候选栏的一项：[text] 是显示和粘贴的文字，[source] 是它来自哪一条剪贴板记录（标记已用时用原文），
     * [extracted] 为 true 是从原文里提取出来的链接、BV 号或长数字。
     */
    data class Choice(val source: Int, val text: String, val extracted: Boolean = false)

    /**
     * 候选栏分两个胶囊（用户 11-08）：前面短的放提取出来的（所有记录的提取项按记录顺序），
     * 后面长的放原文。顺序就是提取项在前、原文在后，点选按这个顺序的下标回到记录。
     * 什么都提取不出来时只有原文，照旧一个胶囊。
     */
    fun expand(texts: List<String>): List<Choice> = split(texts.indices.map { index ->
        extractedChoices(texts[index]) to texts[index]
    })

    /** 同 [expand]，但记下的密码（#95）只给原文一项，不拆链接、不提数字。 */
    fun expandItems(items: List<ClipboardItem>): List<Choice> = split(items.map { item ->
        (if (item.sensitive) emptyList() else extractedChoices(item.text)) to item.text
    })

    private fun split(rows: List<Pair<List<String>, String>>): List<Choice> =
        rows.flatMapIndexed { index, (extracted, _) -> extracted.map { Choice(index, it, extracted = true) } } +
            rows.mapIndexed { index, (_, text) -> Choice(index, text) }

    /** 候选栏前面那个短胶囊里有几项（[expandItems] 结果开头的这些都是提取项）。 */
    fun extractedCount(items: List<ClipboardItem>): Int = expandItems(items).count { it.extracted }

    /** 候选栏上显示的字：密码显示成圆点，点了照常贴原文。 */
    fun shownText(items: List<ClipboardItem>, choice: Choice): String =
        if (items.getOrNull(choice.source)?.sensitive == true) SENSITIVE_MASK else choice.text

    /** 记下的密码在剪贴板面板和候选栏上显示成这样（#95）。 */
    const val SENSITIVE_MASK = "••••••••"

    /** 一条剪贴板文字能提取出来的几项：先链接（干净链接、BV 号），再长数字；不含原文本身，什么都没有时为空。 */
    fun extractedChoices(text: String): List<String> = (linkChoices(text) + numberChoices(text)).distinct()

    /** 一条剪贴板文字能多给的几项（不含原文本身），没有链接时为空。 */
    fun linkChoices(text: String): List<String> {
        val raw = firstLink(text) ?: return emptyList()
        val clean = cleanLink(raw)
        val bv = bilibiliVideoId(clean)
        return listOfNotNull(clean.takeIf { it != text.trim() }, bv?.takeIf { it != clean && it != text.trim() }).distinct()
    }

    // 链接到空白、引号、尖括号或中文标点为止；末尾的英文标点（句号、逗号、多出来的右括号）不算链接。
    private val linkPattern = Regex(
        "(?:https?://|www\\.|(?<![\\w.])(?:b23\\.tv|bili2233\\.cn)/)[^\\s<>\"'，。、；：！？（）【】「」《》“”‘’]+",
        RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?"
    private val closers = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun firstLink(text: String): String? =
        linkPattern.find(text)?.value?.let { trimTrailing(it) }?.takeIf { it.length > 4 }

    /**
     * 去掉链接末尾的句读。右括号只在比左括号多时才去：维基这类 Mercury_(planet) 的括号是链接的一部分，
     * 原来一律去掉，前面那项「干净链接」就少了结尾（11-18 一加 Ace 5：粘到浏览器只有前半段）。
     */
    private fun trimTrailing(link: String): String {
        var end = link.length
        while (end > 0) {
            val last = link[end - 1]
            val opener = closers[last]
            val drop = if (opener != null) {
                val part = link.substring(0, end)
                part.count { it == opener } < part.count { it == last }
            } else last in TRAILING_PUNCTUATION
            if (!drop) break
            end--
        }
        return link.substring(0, end)
    }

    /**
     * 长数字：至少 [MIN_NUMBER_DIGITS] 位，数字之间可以隔一个空格或横线（138 1234 5678、090-1234-5678），
     * 开头的 + 留着。前后连着字母或数字的不算（SF1234567 这种单号不拆半截）；带小数点、千分位的金额不算；
     * 日期（2026-11-08、2026年11月8日）和链接里的数字先去掉，免得拼成一串假号码。最多给 [MAX_NUMBERS] 个。
     */
    fun numberChoices(text: String): List<String> {
        val trimmed = text.trim()
        var rest = linkPattern.replace(text, " ")
        rest = datePattern.replace(rest, " ")
        return numberPattern.findAll(rest).map { match ->
            val value = match.value
            (if (value.startsWith('+')) "+" else "") + value.filter { it.isDigit() }
        }.filter { number -> number.count { it.isDigit() } in MIN_NUMBER_DIGITS..MAX_NUMBER_DIGITS && number != trimmed }
            .distinct().take(MAX_NUMBERS).toList()
    }

    private const val MIN_NUMBER_DIGITS = 7
    private const val MAX_NUMBER_DIGITS = 30
    private const val MAX_NUMBERS = 3

    // 隔开的每段至少两位，免得「第 1 2 3 名」这类被连成一串；+ 前面不能紧贴字母数字。
    // 整串一次吃完不回退：「12345678abc」不会退成前几位算一个号码。
    private val numberPattern = Regex(
        "(?<![0-9A-Za-z+])(?<![0-9][.,])(?>\\+?[0-9]+(?:[ \\-][0-9]{2,})*)(?![0-9A-Za-z])(?![.,][0-9])")
    private val datePattern = Regex("(?<![0-9])[0-9]{4}\\s*[-/.年]\\s*[0-9]{1,2}\\s*[-/.月]\\s*[0-9]{1,2}(?![0-9])\\s*日?")

    private fun host(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase()

    private fun isBilibili(host: String) =
        host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" || host == "bili2233.cn"

    /**
     * 只有视频页的参数全是来源记录。搜索（keyword）、收藏夹（fid）、稍后再看（bvid）这些页面靠参数打开，
     * 原来按整个 B 站只留 p、t，干净链接打开就是搜索首页、空收藏夹（11-18：粘到浏览器只剩前半段）。
     */
    private fun isBilibiliVideoPage(host: String, base: String): Boolean {
        if (!isBilibili(host)) return false
        if (host == "b23.tv" || host == "bili2233.cn") return true
        val path = base.substringAfter("://", base).substringAfter('/', "")
        return path.startsWith("video/") || path.startsWith("bangumi/play/")
    }

    private fun isOneOf(host: String, vararg domains: String) = domains.any { host == it || host.endsWith(".$it") }

    /**
     * B 站视频页（含番剧、短链接）只留分 P 和时间；B 站别的页面和别的网站一样，只去常见的跟踪参数，
     * 其余参数原样留着（有的网站要靠它打开）。
     */
    private val bilibiliKeep = setOf("p", "t")
    private val trackingExact = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "igshid", "igsh", "mc_cid", "mc_eid",
        "_hsenc", "_hsmi", "yclid", "spm", "spm_id_from", "vd_source", "unique_k", "share_source", "share_medium",
        "share_plat", "share_session_id", "share_tag", "share_from", "share_id", "sharer_shareid", "sharer_shareinfo",
        "sharer_shareinfo_first", "xhsshare", "app_platform", "app_version", "ignoreengage",
    )
    private val trackingPrefixes = listOf("utm_")

    private fun isTracking(name: String, host: String): Boolean {
        val key = name.lowercase()
        if (key in trackingExact || trackingPrefixes.any { key.startsWith(it) }) return true
        // si 是 YouTube、Spotify 分享带的来源标记；别的网站的 si 可能有用，不动。
        return key == "si" && isOneOf(host, "youtube.com", "youtu.be", "spotify.com")
    }

    /** 去掉跟踪参数后的链接；没有可去的就原样返回。锚点（#）照旧留着。 */
    fun cleanLink(url: String): String {
        val fragmentAt = url.indexOf('#')
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
        val beforeFragment = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val queryAt = beforeFragment.indexOf('?')
        if (queryAt < 0) return url
        val base = beforeFragment.substring(0, queryAt)
        val host = host(base)
        val bilibili = isBilibiliVideoPage(host, base)
        val kept = beforeFragment.substring(queryAt + 1).split('&').filter { it.isNotEmpty() }.filter { pair ->
            val name = pair.substringBefore('=')
            if (bilibili) name.lowercase() in bilibiliKeep else !isTracking(name, host)
        }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private val bvPattern = Regex("(?<![0-9A-Za-z])BV1[0-9A-Za-z]{9}(?![0-9A-Za-z])")

    /** B 站视频链接里的 BV 号；b23.tv 短链接里没有，返回 null。 */
    fun bilibiliVideoId(url: String): String? {
        if (!isBilibili(host(url))) return null
        val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
        return bvPattern.find(path)?.value
    }
}
