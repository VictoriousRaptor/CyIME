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

/**
 * Preference (board prefs): 列表显示（用户 11-14：有人想要剪贴板文字一条一横排；平铺 / 列表用顶栏按钮切换，记住上次）。
 * 列表每条记录占一整行；没固定的图片不一张占一行，拼成一排左右滑。默认 false：照旧按宽度分几列的瀑布流平铺。
 */
internal const val CLIPBOARD_LIST_LAYOUT_KEY = "list_layout"

/** Key of the one row holding the recent pictures in the list layout. */
internal const val CLIPBOARD_IMAGE_STRIP_KEY = "image-strip"

/** The list layout only where there is text: a tab of nothing but pictures keeps its columns. */
internal fun clipboardListLayout(wanted: Boolean, cards: List<ClipboardCard>): Boolean =
    wanted && cards.any { it is ClipboardCard.Text }

/** Recent records in the list layout: the text [before] the newest picture, all recent [images] in one row, then the rest of the text. */
internal data class ClipboardImageStrip(
    val before: List<ClipboardCard>,
    val images: List<ClipboardCard.Image>,
    val after: List<ClipboardCard>,
) {
    /** The order shown, for keyboard focus and selection. */
    val ordered: List<ClipboardCard> get() = before + images + after
}

internal fun clipboardImageStrip(recent: List<ClipboardCard>): ClipboardImageStrip {
    val first = recent.indexOfFirst { it is ClipboardCard.Image }
    if (first < 0) return ClipboardImageStrip(recent, emptyList(), emptyList())
    return ClipboardImageStrip(recent.take(first), recent.filterIsInstance<ClipboardCard.Image>(),
        recent.drop(first).filterIsInstance<ClipboardCard.Text>())
}

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
 * 「完整」（用户 11-17，默认）：铺满卡片宽度，按图片比例定高，长图最高到整个记录区，超出的下半截裁掉
 * （用户 11-18：原来缩小整张显示，竖长截图两边空出一大块，不行）；点开照旧看原图。
 * 配合「全部」只显示最新几张图片，不会满屏都是图。
 * 「标准」：11-17 前的默认，比宽稍高，裁剪；「按图片比例」长图高、宽图矮；「矮」是 11-09 前的样子。
 * 标准和按比例都不超过记录区的六成，一张长截图不会占满面板。
 */
internal enum class ClipboardImageHeight(val key: String, val label: String, val caption: String) {
    FULL("full", "完整", "铺满宽度，长图最高到面板高度，超出的下面裁掉"),
    STANDARD("standard", "标准", "比宽稍高，上下裁掉一些"),
    RATIO("ratio", "按图片比例", "长图高、宽图矮，最高到面板的六成"),
    LOW("low", "矮", "只露一段，一屏看到的记录多"),
}

/** Preference (board prefs) for [ClipboardImageHeight]. */
internal const val CLIPBOARD_IMAGE_HEIGHT_KEY = "image_height"

internal fun clipboardImageHeight(stored: String?): ClipboardImageHeight =
    ClipboardImageHeight.entries.firstOrNull { it.key == stored } ?: ClipboardImageHeight.FULL

/** 这种高度要读图片的宽高比。 */
internal val ClipboardImageHeight.followsPicture: Boolean
    get() = this == ClipboardImageHeight.RATIO || this == ClipboardImageHeight.FULL

/**
 * Preference (board prefs)：「全部」里显示几张没固定的图片（用户 11-17：一打开全是图片，挤掉后面的文字）。
 * 只算最新的几张；固定的图片、文字、「图片」页和分组页都不受影响，图片本身也不删。0 = 不限（11-17 前的样子）。
 * 几种来源各至少留一张（用户 11-19：拍几张照片，截图就全被挤掉了），见 [withRecentImageCount]。
 */
internal const val CLIPBOARD_RECENT_IMAGE_COUNT_KEY = "recent_image_count"
internal val ClipboardRecentImageCounts = listOf(2, 4, 8, 0)
internal fun clipboardRecentImageCount(stored: Int): Int = if (stored in ClipboardRecentImageCounts) stored else 4

/** 剪贴板图片从哪来：真的复制过的、系统截图、相册里最近的照片。 */
internal enum class ClipboardImageKind { COPIED, SCREENSHOT, PHOTO }

internal val ClipboardImage.kind: ClipboardImageKind
    get() = when {
        // 复制进历史的（含粘过的截图）带着来源和哈希，算复制的。
        source != null || sha256 != null -> ClipboardImageKind.COPIED
        label == ScreenshotImageSource.SCREENSHOT_LABEL -> ClipboardImageKind.SCREENSHOT
        label == ScreenshotImageSource.PHOTO_LABEL -> ClipboardImageKind.PHOTO
        else -> ClipboardImageKind.COPIED
    }

/**
 * [cards] with only [count] unfixed pictures (0 keeps them all). Text and fixed pictures stay.
 * 每种来源先留它最新的一张（复制的、截图、照片，位置不够时按这个先后），剩下的位置按新旧补满：
 * 4 张、连拍了一串照片时是 3 张照片 + 1 张截图（用户 11-19）。只有一种来源时就是最新的几张，和以前一样。
 */
internal fun withRecentImageCount(cards: List<ClipboardCard>, pins: Set<String>, count: Int): List<ClipboardCard> {
    if (count <= 0) return cards
    // Cards are fixed first, then newest first, so the unfixed pictures met here are newest first.
    val unfixed = cards.filterIsInstance<ClipboardCard.Image>().filter { it.key !in pins }
    val kept = LinkedHashSet<String>()
    for (kind in ClipboardImageKind.entries) {
        if (kept.size >= count) break
        unfixed.firstOrNull { it.item.kind == kind }?.let { kept += it.key }
    }
    for (card in unfixed) {
        if (kept.size >= count) break
        kept += card.key
    }
    return cards.filter { it !is ClipboardCard.Image || it.key in pins || it.key in kept }
}

/** A picture card at least this tall stays easy to tap, however wide the picture. */
internal const val ClipboardImageMinHeight = 80f

/**
 * One recent picture card's height in dp. [aspect] is the picture's width / height, null while
 * not yet read (it then keeps the standard shape, so nothing jumps unless the picture differs).
 */
internal fun clipboardImageCardHeight(mode: ClipboardImageHeight, widthDp: Float, recordsHeightDp: Float,
    aspect: Float?): Float {
    if (mode == ClipboardImageHeight.LOW) return clipboardPinnedPreviewHeight(recordsHeightDp)
    val share = if (mode == ClipboardImageHeight.FULL) 1f else .6f
    val most = (recordsHeightDp.coerceAtLeast(0f) * share).coerceAtLeast(ClipboardImageMinHeight)
    val shape = if (mode.followsPicture) aspect?.takeIf { it > 0f } ?: ClipboardThumbnailAspectRatio
        else ClipboardThumbnailAspectRatio
    return (widthDp.coerceAtLeast(0f) / shape).coerceIn(ClipboardImageMinHeight, most)
}
