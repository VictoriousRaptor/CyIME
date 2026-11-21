package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连续语音：同一个输入框发送后接着听，不要求先收成悬浮条；离开、用户自己停下都不接着听。 */
class VoiceRelistenPolicyTest {
    private val chat = VoiceFieldKey("com.tencent.mm", 7)

    private fun arms(enabled: Boolean = true, userEnding: Boolean = false, keyboardOwnField: Boolean = false,
        secret: Boolean = false, field: VoiceFieldKey? = chat, textNow: CharSequence? = "") =
        voiceRelistenArmsAfterSend(enabled, userEnding, keyboardOwnField, secret, field, textNow)

    @Test fun `sending while listening keeps this field for listening again`() {
        assertEquals(chat, arms())
    }

    @Test fun `continuous voice off, the user already stopping, keyboard's own box and password fields do not`() {
        assertNull("setting off", arms(enabled = false))
        assertNull("tapped the mic off or let go of space before sending", arms(userEnding = true))
        assertNull("translate bar inside the keyboard", arms(keyboardOwnField = true))
        assertNull("password field", arms(secret = true))
        assertNull("no app package", arms(field = null))
    }

    @Test fun `a host restart only counts as sending when the field really became empty`() {
        assertNull("restart kept the text (comment box wrapping a line)", arms(textNow = "好的"))
        assertNull("could not read the field", arms(textNow = null))
    }

    @Test fun `listens again only while focus is still in the same field`() {
        assertTrue(voiceRelistenApplies(chat, VoiceFieldKey("com.tencent.mm", 7)))
        assertFalse("another field in the same app", voiceRelistenApplies(chat, VoiceFieldKey("com.tencent.mm", 8)))
        assertFalse("another app", voiceRelistenApplies(chat, VoiceFieldKey("com.tencent.mobileqq", 7)))
        assertFalse("nothing to resume", voiceRelistenApplies(null, chat))
        assertFalse("no editor", voiceRelistenApplies(chat, null))
    }

    @Test fun `a same-field restart after sending does not use up the voice bar's come-back mark`() {
        // 「两次一组」：发送时宿主重启同一个输入框，以前被当成回到应用，把记号用掉，下一次发送就断了。
        assertFalse(voicePillResumeOnStart(restarting = true, last = chat, current = chat))
        assertTrue("coming back to the app", voicePillResumeOnStart(restarting = false, last = chat, current = chat))
        assertTrue("restart into another field", voicePillResumeOnStart(restarting = true, last = chat,
            current = VoiceFieldKey("com.tencent.mm", 9)))
        assertTrue("first field after start", voicePillResumeOnStart(restarting = true, last = null, current = chat))
    }
}
