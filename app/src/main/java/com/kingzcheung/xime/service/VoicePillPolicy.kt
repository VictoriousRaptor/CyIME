package com.kingzcheung.xime.service

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * 输入框再次请求弹出键盘（用户点了输入框）时，语音悬浮条是否换回完整键盘。
 * 输入法自己请求弹出悬浮条的那次、旋转屏幕重建都不算用户请求。
 */
internal fun voicePillExpandsOnShowRequest(voiceCollapsed: Boolean, selfShow: Boolean, configChange: Boolean): Boolean =
    voiceCollapsed && !selfShow && !configChange

/** 听写进行中、键盘界面要离开的各种情况。 */
internal enum class VoiceSessionEnd {
    /** 键盘里按返回键（或返回手势）：就是退出键盘。 */
    BACK_KEY,
    /** 键盘上的收起按钮。 */
    COLLAPSE_BUTTON,
    /**
     * 键盘被收起、输入框焦点还在：往上翻看时 App 收起键盘，系统导航栏的收起键也是这一种
     * （输入法分不出两者）。
     */
    APP_HIDES_KEYBOARD,
    /** 输入框从有字变成空：多半是点了发送。 */
    FIELD_CLEARED,
    /** 输入框失去焦点。 */
    FOCUS_LEAVES,
    /** 焦点换到另一个输入框。 */
    FOCUS_TO_OTHER_FIELD,
    /** 换到密码等敏感输入框（无痕）。 */
    SENSITIVE_FIELD,
    HOME,
    APP_SWITCH,
    SCREEN_OFF,
    ROTATION,
    SWITCH_KEYBOARD,
    OPEN_CAPTIONS,
}

/**
 * 只有用户自己点收起按钮，听写才继续并显示悬浮条。点别处让应用收起键盘也算离开：
 * 听写结束、立即释放麦克风，不能再自己弹出悬浮条把焦点拉回输入框（用户 11-02）。
 */
internal fun voiceKeepsListeningAfter(end: VoiceSessionEnd): Boolean =
    end == VoiceSessionEnd.COLLAPSE_BUTTON

/** 听写中输入框从有字变成空（读不到时不算）。 */
internal fun voiceFieldCleared(hadText: Boolean, textNow: CharSequence?): Boolean =
    hadText && textNow != null && textNow.isEmpty()

/** 开车连续语音的记号：哪个应用里的悬浮条听写被打断、什么时候（elapsedRealtime）。 */
internal data class VoicePillResumeMark(val packageName: String, val atMs: Long)

/** 打断后多久内回来还自动续听：等对方回消息够用，又不至于隔很久突然开麦。 */
internal const val VOICE_PILL_RESUME_WINDOW_MS = 10 * 60_000L

/** 悬浮条听写结束时是否记下续听：只有用户自己收起的悬浮条、因发送或焦点离开而停下才算。 */
internal fun voicePillResumeArmsAfter(end: VoiceSessionEnd, pillByUser: Boolean, enabled: Boolean): Boolean =
    enabled && pillByUser && (end == VoiceSessionEnd.FIELD_CLEARED || end == VoiceSessionEnd.FOCUS_LEAVES ||
        end == VoiceSessionEnd.FOCUS_TO_OTHER_FIELD)

/** 回到输入框时是否自动续听：同一个应用、还在有效期内。 */
internal fun voicePillResumeApplies(mark: VoicePillResumeMark?, packageName: String?, nowMs: Long): Boolean =
    mark != null && packageName != null && mark.packageName == packageName &&
        nowMs - mark.atMs in 0..VOICE_PILL_RESUME_WINDOW_MS

/** 同一个输入框：应用包名 + 输入框编号。聊天应用里不同会话常共用一个编号，只认焦点没离开过的那一次。 */
internal data class VoiceFieldKey(val packageName: String, val fieldId: Int)

/**
 * 连续语音：这句听写因为发送停下（输入框从有字变空，或宿主为清空重启了同一个输入框）时，收尾后要不要在原输入框接着听。
 * 不看是不是收成了悬浮条；用户自己已经要求结束（点麦克风停、松开空格、按住上滑取消）的不接着听。
 * [textNow] 是停下那一刻输入框里的字：要读得到、而且是空的，才算真发出去了。
 */
internal fun voiceRelistenArmsAfterSend(enabled: Boolean, userEnding: Boolean, keyboardOwnField: Boolean,
    secret: Boolean, field: VoiceFieldKey?, textNow: CharSequence?): VoiceFieldKey? =
    field.takeIf { enabled && !userEnding && !keyboardOwnField && !secret && textNow != null && textNow.isEmpty() }

/** 真开麦前再查：焦点还在发送前那个输入框（换了框、换了应用都不开）。 */
internal fun voiceRelistenApplies(target: VoiceFieldKey?, current: VoiceFieldKey?): Boolean =
    target != null && target == current

/**
 * 宿主重启输入框时是不是「回到这个应用」：同一个输入框原地重启（发送清空、写满一行重设文字）不算，
 * 那种情况由 [voiceRelistenApplies] 管；否则一次发送会把悬浮条的记号用掉，下一次发送又没了（「两次一组」）。
 */
internal fun voicePillResumeOnStart(restarting: Boolean, last: VoiceFieldKey?, current: VoiceFieldKey?): Boolean =
    !(restarting && current != null && current == last)

/** 输入框本身是否允许自动开麦：密码等敏感框、不许语音的框、通话中都不开。 */
internal fun voicePillResumeFieldAllows(sensitive: Boolean, locksNativeInput: Boolean, inCall: Boolean, hasMicPermission: Boolean): Boolean =
    !sensitive && !locksNativeInput && !inCall && hasMicPermission

/**
 * 宿主在听写中重启了同一个输入框（B 站评论框写满一行时重设文字，光标被放回开头）后，听写接着写的位置；
 * 拿不准时返回 null，照旧结束听写。
 *
 * [text] 是输入框从 [startOffset] 起的文字；[candidates] 是重启前听写写到的位置（绝对位置，按可信程度排）；
 * [tail] 是该位置前应有的、听写最近写进去的字（为空时只认输入框末尾）。
 */
internal fun voiceResumeCaret(text: CharSequence, startOffset: Int, candidates: List<Int>, tail: String): Int? {
    for (at in candidates) {
        val index = at - startOffset
        if (at < 0 || index < 0 || index > text.length) continue
        if (if (tail.isEmpty()) index == text.length else text.subSequence(0, index).endsWith(tail)) return at
    }
    return null
}

/** A restarting editor can refuse the cursor move, or acknowledge it without applying it. */
internal fun InputConnection.restoreDictationPosition(
    candidates: List<Int>, tail: String, composing: Boolean,
): Int? {
    fun snapshot(): ExtractedText? {
        fun ExtractedText.valid() = startOffset >= 0 && text != null &&
            selectionStart in 0..text.length && selectionEnd in 0..text.length
        return runCatching { getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()?.takeIf { it.valid() }
            ?: readSurroundingWindow(2048, 2048, 0)?.takeIf { tail.isNotEmpty() && it.valid() }
    }
    return try {
        val initial = snapshot() ?: return null
        if (initial.selectionStart != initial.selectionEnd) return null
        val at = voiceResumeCaret(initial.text, initial.startOffset, candidates, tail) ?: return null
        val caret = initial.startOffset + initial.selectionEnd
        if (caret != at) {
            // The observed reset puts the caret at zero. Respect a user's move elsewhere.
            if (caret != 0 || !setSelection(at, at)) return null
            val updated = snapshot() ?: return null
            if (updated.selectionStart != updated.selectionEnd || updated.startOffset + updated.selectionEnd != at ||
                voiceResumeCaret(updated.text, updated.startOffset, listOf(at), tail) == null) return null
        }
        if (composing && !setComposingRegion(at - tail.length, at)) return null
        at
    } catch (_: RuntimeException) {
        null // The activity may close the editor while reconnecting.
    }
}
