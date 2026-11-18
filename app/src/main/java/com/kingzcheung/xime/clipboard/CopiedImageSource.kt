package com.kingzcheung.xime.clipboard

internal data class CopiedImageSource(val uri: String, val resolvedMime: String?)

/** ClipDescription describes the entire clip, not each item in a mixed clip. */
internal fun copiedImageSource(items: List<CopiedImageSource>, advertisedMime: String?): CopiedImageSource? =
    copiedImageSources(items, advertisedMime).firstOrNull()

/**
 * 一次复制里所有能读的图片（相册多选复制会给好几项）：认得出是图片的在前，
 * 类型暂时读不到、但整条复制标着图片的在后。第一项就是 [copiedImageSource]。
 */
internal fun copiedImageSources(items: List<CopiedImageSource>, advertisedMime: String?): List<CopiedImageSource> {
    val readable = items.filter { it.uri.startsWith("content://") }
    val images = readable.filter { it.resolvedMime?.startsWith("image/") == true }
    val unresolved = if (advertisedMime?.startsWith("image/") != true) emptyList()
        else readable.filter { it.resolvedMime.isNullOrBlank() || it.resolvedMime == "application/octet-stream" }
    return (images + unresolved).distinctBy { it.uri }
}
