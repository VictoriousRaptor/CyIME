package com.kingzcheung.xime.clipboard

/** One recent-image budget shared by copied images and the optional screenshot feed. */
internal const val RECENT_CLIPBOARD_IMAGE_LIMIT = 50

/**
 * 「图片占用上限」（用户 11-17：剪贴板图片限制大小，默认 200 MB，可以调），存在 clipboard_board 里，单位 MB。
 * 以前写死 500 MB。
 */
internal const val CLIPBOARD_IMAGE_CACHE_MB_KEY = "image_cache_mb"
internal const val DEFAULT_CLIPBOARD_IMAGE_CACHE_MB = 200
internal val CLIPBOARD_IMAGE_CACHE_MB_OPTIONS = listOf(100, 200, 500, 1000)

/** 存着的值不在选项里（没设过、旧版本写的）时用默认。 */
internal fun clipboardImageCacheMb(stored: Int): Int =
    stored.takeIf { it in CLIPBOARD_IMAGE_CACHE_MB_OPTIONS } ?: DEFAULT_CLIPBOARD_IMAGE_CACHE_MB

internal fun clipboardImageCacheMbLabel(mb: Int): String = if (mb >= 1000) "${mb / 1000} GB" else "$mb MB"

/** Copied images kept in the app cache may use at most this much space in total (default 「图片占用上限」). */
internal const val CLIPBOARD_IMAGE_CACHE_BYTES = DEFAULT_CLIPBOARD_IMAGE_CACHE_MB * 1024L * 1024

internal data class CachedImageFile(val name: String, val bytes: Long, val lastModified: Long)

/**
 * Oldest cached copies to delete so the cache fits [maxBytes]. Fixed (pinned) images are never
 * evicted, even when they alone exceed the budget.
 */
internal fun clipboardImageEvictions(files: List<CachedImageFile>, protectedNames: Set<String>,
    maxBytes: Long = CLIPBOARD_IMAGE_CACHE_BYTES): List<String> {
    var total = files.sumOf { it.bytes }
    if (total <= maxBytes) return emptyList()
    val evicted = mutableListOf<String>()
    for (file in files.filterNot { it.name in protectedNames }.sortedBy { it.lastModified }) {
        if (total <= maxBytes) break
        evicted += file.name
        total -= file.bytes
    }
    return evicted
}

/** A transient provider failure may keep cached metadata, but cannot undo an opt-out/removal. */
internal fun clipboardMediaAfterRefreshFailure(
    previous: List<ClipboardImage>, includeRecent: Boolean,
    pinnedKeys: Set<String>, hiddenUris: Set<String>,
    includeGalleryPhotos: Boolean = true,
): List<ClipboardImage> = previous.filter {
    val pinned = "image:${it.uri}" in pinnedKeys
    it.uri !in hiddenUris && (includeRecent || pinned) &&
        (includeGalleryPhotos || it.label != ScreenshotImageSource.PHOTO_LABEL || pinned)
}

internal fun isMediaImageUri(uri: String): Boolean = uri.startsWith("content://media/")

/** One source per copy: a gallery/screenshot source is kept, because the feed hides it by that URI. */
internal fun preferredImageSource(existing: String?, incoming: String): String =
    listOfNotNull(existing, incoming).firstOrNull(::isMediaImageUri) ?: existing ?: incoming

/**
 * A pasted (or copied) screenshot is cached into history while the feed still lists the original:
 * the same picture would show twice. Keep one card — the fixed one if only one is fixed, else the copy.
 */
internal fun withoutFeedDuplicates(
    copied: List<ClipboardImage>, screenshots: List<ClipboardImage>, pinnedKeys: Set<String>,
): Pair<List<ClipboardImage>, List<ClipboardImage>> {
    fun pinned(image: ClipboardImage) = "image:${image.uri}" in pinnedKeys
    val pinnedShots = screenshots.filter(::pinned).mapTo(mutableSetOf()) { it.uri }
    val keptCopied = copied.filterNot { image -> image.source?.let { it in pinnedShots } == true && !pinned(image) }
    val coveredShots = keptCopied.mapNotNullTo(mutableSetOf()) { it.source }
    return keptCopied to screenshots.filterNot { it.uri in coveredShots && !pinned(it) }
}

internal fun retainedClipboardImages(
    copied: List<ClipboardImage>, screenshots: List<ClipboardImage>,
    pinnedKeys: Set<String>, hiddenUris: Set<String>,
): List<ClipboardImage> {
    var recent = 0
    val (keptCopied, keptShots) = withoutFeedDuplicates(copied, screenshots, pinnedKeys)
    return (keptCopied + keptShots).distinctBy { it.uri }
        .filterNot { it.uri in hiddenUris }
        .sortedByDescending { it.timestamp }
        .filter { "image:${it.uri}" in pinnedKeys || recent++ < RECENT_CLIPBOARD_IMAGE_LIMIT }
}
