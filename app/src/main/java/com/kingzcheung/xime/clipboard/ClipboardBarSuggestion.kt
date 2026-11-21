package com.kingzcheung.xime.clipboard

import android.content.Context

/**
 * 「候选栏显示刚复制的内容」(用户 11-20 转来的反馈：最新复制的内容要能选不显示)。存在 clipboard_board 里，
 * 默认开 = 以前的样子。关掉后打开键盘不再在候选栏冒出刚复制的文字和图片；新短信验证码有自己的开关，照旧显示。
 */
internal const val CLIPBOARD_SHOW_IN_BAR_KEY = "show_in_candidate_bar"

internal fun clipboardShowsInCandidateBar(context: Context): Boolean =
    context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE).getBoolean(CLIPBOARD_SHOW_IN_BAR_KEY, true)

/** 候选栏要显示的剪贴板条目：验证码在前；[includeCopied] 关掉时只剩验证码。 */
internal fun recentBarItems(sms: ClipboardItem?, copied: List<ClipboardItem>, cutoff: Long,
    includeCopied: Boolean): List<ClipboardItem> =
    listOfNotNull(sms) + if (!includeCopied) emptyList()
    else copied.filter { it.timestamp >= cutoff && !it.consumed && it.text != sms?.text }
