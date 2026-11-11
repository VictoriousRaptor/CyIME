package com.kingzcheung.xime.clipboard

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache

/**
 * 「按图片比例」要的宽高比：只读图片头（不解码），按地址记在内存里，同一张图只读一次。
 * 读不到（已删、没权限）就是 null，卡片按标准形状。
 */
internal object ClipboardImageAspect {
    private val known = LruCache<String, Float>(256)

    fun cached(uri: String): Float? = known.get(uri)

    /** Reads the picture's header; call off the main thread. */
    fun read(context: Context, uri: String): Float? {
        known.get(uri)?.let { return it }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return (options.outWidth.toFloat() / options.outHeight).also { known.put(uri, it) }
    }
}
