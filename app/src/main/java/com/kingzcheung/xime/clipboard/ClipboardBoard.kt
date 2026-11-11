package com.kingzcheung.xime.clipboard

enum class ClipboardFilter(val label: String) {
    ALL("全部"), TEXT("文本"), LINK("链接"), ADDRESS("地址"), PHONE("电话"), EMAIL("邮箱"), IMAGE("图片")
}

sealed interface ClipboardCard {
    val key: String
    val timestamp: Long
    data class Text(val item: ClipboardItem) : ClipboardCard {
        override val key = "text:${item.id}"
        override val timestamp = item.timestamp
    }
    data class Image(val item: ClipboardImage) : ClipboardCard {
        override val key = "image:${item.uri}"
        override val timestamp = item.timestamp
    }
}

private val emailPattern = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
private val linkPattern = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
private val phonePattern = Regex("(?:\\+?[0-9][0-9 ()-]{7,}[0-9])")
private val addressPattern = Regex("〒\\s*[0-9]{3}|[都道府県].*[市区町村]|[省市自治区].*[路街巷弄号]|[0-9]+丁目|[0-9]+番地")

internal fun clipboardMatches(card: ClipboardCard, filter: ClipboardFilter): Boolean {
    if (filter == ClipboardFilter.ALL) return true
    if (card is ClipboardCard.Image) return filter == ClipboardFilter.IMAGE
    val text = (card as ClipboardCard.Text).item.text
    return when (filter) {
        ClipboardFilter.TEXT -> true
        ClipboardFilter.LINK -> linkPattern.containsMatchIn(text)
        ClipboardFilter.EMAIL -> emailPattern.containsMatchIn(text)
        ClipboardFilter.ADDRESS -> addressPattern.containsMatchIn(text)
        ClipboardFilter.PHONE -> phonePattern.findAll(text).any { match -> match.value.count(Char::isDigit) in 10..15 }
        else -> false
    }
}

internal fun clipboardCards(text: List<ClipboardItem>, images: List<ClipboardImage>, filter: ClipboardFilter,
    pinned: Set<String>): List<ClipboardCard> =
    (text.map { ClipboardCard.Text(it) } + images.map { ClipboardCard.Image(it) })
        .distinctBy { it.key }.filter { clipboardMatches(it, filter) }
        .sortedWith(compareByDescending<ClipboardCard> { it.key in pinned }.thenByDescending { it.timestamp })

/** Text pins live in Room; the board preferences retain only image identities. */
internal fun clipboardPinnedKeys(text: List<ClipboardItem>, storedPins: Set<String>,
    pendingTextPins: Map<Long, Boolean> = emptyMap()): Set<String> = buildSet {
    addAll(storedPins.filter { it.startsWith("image:") })
    text.forEach { item ->
        if (pendingTextPins[item.id] ?: item.isPinned) add("text:${item.id}")
    }
}

/** Keep immediate feedback until the observed database state acknowledges the change. */
internal fun pendingClipboardTextPins(text: List<ClipboardItem>, pending: Map<Long, Boolean>): Map<Long, Boolean> {
    val stored = text.associate { it.id to it.isPinned }
    return pending.filter { (id, pinned) -> id in stored && stored[id] != pinned }
}

internal fun legacyClipboardTextPinIds(storedPins: Set<String>): Set<Long> = storedPins
    .filter { it.startsWith("text:") }.mapNotNull { it.removePrefix("text:").toLongOrNull() }
    .filter { it > 0 }.toSet()

internal fun clipboardColumnCount(widthDp: Float): Int = (widthDp / 160f).toInt().coerceIn(1, 5)

// Keep the existing placeholder shape for the whole thumbnail lifetime. Decoding
// must not change masonry geometry as more images enter the viewport.
internal const val ClipboardThumbnailAspectRatio = .8f

internal data class ClipboardBoardContent(
    val pinned: List<ClipboardCard>,
    val recent: List<ClipboardCard>,
    val pinnedCount: Int,
    /** Folding pins only makes room for unpinned records; with none below there is nothing to fold. */
    val pinsCollapsible: Boolean = false,
) {
    val visible: List<ClipboardCard> get() = pinned + recent
}

/** Collapsing pins is presentation only: neither persistence nor recent history is truncated. */
internal fun clipboardBoardContent(cards: List<ClipboardCard>, pins: Set<String>, columns: Int,
    pinsExpanded: Boolean): ClipboardBoardContent {
    val (fixed, recent) = cards.partition { it.key in pins }
    val collapsible = fixed.size > columns.coerceAtLeast(1) && recent.isNotEmpty()
    return ClipboardBoardContent(
        pinned = if (pinsExpanded || !collapsible) fixed else fixed.take(columns.coerceAtLeast(1)),
        recent = recent,
        pinnedCount = fixed.size,
        pinsCollapsible = collapsible,
    )
}

/** A tall picture or long text must not turn the collapsed row into the whole panel. */
internal fun clipboardPinnedPreviewHeight(recordsHeightDp: Float): Float =
    (recordsHeightDp.coerceAtLeast(0f) * .35f).coerceAtMost(112f)

/**
 * 剪贴板里（没固定的）图片卡片多高（用户 11-09：图片太矮，给个选项）。固定的图片照旧只露一条。
 * 默认「标准」：以前的高度，比宽稍高；「按图片比例」长图高、宽图矮；「矮」是 11-09 前的样子。
 * 标准和按比例都不超过记录区的六成，一张长截图不会占满面板。
 */
internal enum class ClipboardImageHeight(val key: String, val label: String, val caption: String) {
    STANDARD("standard", "标准", "比宽稍高"),
    RATIO("ratio", "按图片比例", "长图高、宽图矮，最高到面板的六成"),
    LOW("low", "矮", "只露一段，一屏看到的记录多"),
}

/** Preference (board prefs) for [ClipboardImageHeight]. */
internal const val CLIPBOARD_IMAGE_HEIGHT_KEY = "image_height"

internal fun clipboardImageHeight(stored: String?): ClipboardImageHeight =
    ClipboardImageHeight.entries.firstOrNull { it.key == stored } ?: ClipboardImageHeight.STANDARD

/** A picture card at least this tall stays easy to tap, however wide the picture. */
internal const val ClipboardImageMinHeight = 80f

/**
 * One recent picture card's height in dp. [aspect] is the picture's width / height, null while
 * not yet read (it then keeps the standard shape, so nothing jumps unless the picture differs).
 */
internal fun clipboardImageCardHeight(mode: ClipboardImageHeight, widthDp: Float, recordsHeightDp: Float,
    aspect: Float?): Float {
    if (mode == ClipboardImageHeight.LOW) return clipboardPinnedPreviewHeight(recordsHeightDp)
    val most = (recordsHeightDp.coerceAtLeast(0f) * .6f).coerceAtLeast(ClipboardImageMinHeight)
    val shape = if (mode == ClipboardImageHeight.RATIO) aspect?.takeIf { it > 0f } ?: ClipboardThumbnailAspectRatio
        else ClipboardThumbnailAspectRatio
    return (widthDp.coerceAtLeast(0f) / shape).coerceIn(ClipboardImageMinHeight, most)
}
