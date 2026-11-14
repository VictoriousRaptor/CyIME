package com.kingzcheung.xime.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class EditorSessionInputPolicyTest {
    @Test fun termiusShellCanComposeChineseWhileItsCredentialsStillUsePasswordPolicy() {
        fun field(app: String, type: Int, options: Int) = EditorInfo().apply {
            packageName = app
            inputType = type
            imeOptions = options
        }
        val termius = "com.server.auditor.ssh.client"
        val shell = field(termius, 0x91, 0x50000001)
        val session = EditorSessionInputPolicy()
        assertFalse(session.begin(shell, false, false, "rime_ice").isAsciiMode)
        assertTrue(session.choose(false))
        assertFalse(session.directInput)
        assertTrue(EditorInfoClassifier.isTerminalEditor(shell))
        assertTrue(EditorInfoClassifier.isSecretEditor(shell))
        assertTrue(com.kingzcheung.xime.settings.IncognitoState.isSensitiveEditor(shell))
        for (password in listOf(field(termius, 0x81, 0x50000001), field(termius, 0x12, 0x50000001))) {
            assertFalse(EditorInfoClassifier.isTerminalEditor(password))
            assertTrue(session.begin(password, false, false, "rime_ice").isAsciiMode)
            assertFalse(session.choose(false))
        }
        // 可见密码框（Termius 登录页、别的应用的代码编辑器）先给英文，可以切回中文，仍按密码框不学词。
        for (visible in listOf(field(termius, 0x91, EditorInfo.IME_ACTION_DONE), field("other.editor", 0x91, 0x50000001))) {
            assertFalse(EditorInfoClassifier.isTerminalEditor(visible))
            assertTrue(EditorInfoClassifier.isSecretEditor(visible))
            assertTrue(session.begin(visible, false, false, "rime_ice").isAsciiMode)
            assertTrue(session.choose(false))
        }
    }
    private fun editor(type: Int, id: Int = 1) = EditorInfo().apply {
        inputType = type
        fieldId = id
        packageName = "fixture.invalid"
    }

    @Test fun allPasswordVariationsLockDirectAsciiWithoutSavingLanguage() {
        val types = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        types.forEach { type ->
            val session = EditorSessionInputPolicy()
            val entered = session.begin(editor(type), false, false, "original-layout")
            assertTrue(entered.isAsciiMode)
            assertTrue(session.directInput)
            assertTrue(session.locksNativeInput)
            assertFalse(session.choose(false, "other-layout"))
            assertNull(session.schemaOverride)
            assertTrue(session.asciiOverride == true)
            val restored = session.begin(editor(InputType.TYPE_CLASS_TEXT, 2), false, true, "original-layout")
            assertFalse(restored.isAsciiMode)
            assertEquals("original-layout", restored.currentSchemaId)
            assertFalse(session.scoped)
            assertNull(session.asciiOverride)
            assertFalse(session.engineAsciiOverride()!!)
            session.completePreparation(restored.isAsciiMode, restored.currentSchemaId)
            assertNull(session.engineAsciiOverride())
        }
    }

    /** 用户 11-13：写代码的软件（关联想用 VISIBLE_PASSWORD）被锁成英文，切不回中文。 */
    @Test fun visiblePasswordCodeEditorsStartEnglishButCanSwitchToChineseForThisFieldOnly() {
        val code = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val session = EditorSessionInputPolicy()
        assertTrue(session.begin(code, false, false, "rime_ice").isAsciiMode)
        assertEquals(EditorFieldPolicy.Kind.VISIBLE_PASSWORD, session.field.kind)
        assertTrue(session.directInput)
        assertFalse(session.locksNativeInput)
        assertTrue(session.field.allowsNativeInput)
        assertTrue(session.choose(false, "rime_ice"))
        assertFalse(session.directInput)
        // 同一个框重启连接（编辑器刷新）不丢中文。
        assertFalse(session.begin(code, true, false, "rime_ice").isAsciiMode)
        // 离开后原来的语言照旧，下一个可见密码框又先给英文。
        assertFalse(session.begin(editor(InputType.TYPE_CLASS_TEXT, 2), false, false, "rime_ice").isAsciiMode)
        assertTrue(session.begin(editor(code.inputType, 3), false, false, "rime_ice").isAsciiMode)
        // 英文时照密码框直接上屏：只收半角可见字符。
        assertEquals("a", session.field.typedLiteral("a"))
        assertTrue(session.field.typedLiteral("中").isEmpty())
    }

    /** 用户 11-13（Cxxdroid）：代码编辑器、终端、命令行一类的输入框都不能锁成只能打英文。 */
    @Test fun codeEditorsAndTerminalsNeverLockOutChinese() {
        val session = EditorSessionInputPolicy()
        // 终端（TYPE_NULL、Termux 不带类别的可见密码）：不改语言，中文照常（编码显示在候选栏，上屏走 commitText）。
        for (type in listOf(InputType.TYPE_NULL,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)) {
            val terminal = editor(type)
            assertTrue(EditorInfoClassifier.isTerminalEditor(terminal))
            assertFalse(session.begin(terminal, false, false, "rime_ice").isAsciiMode)
            assertFalse(session.scoped)
            assertFalse(session.locksNativeInput)
            assertFalse(session.directInput)
        }
        // 关联想、多行的普通文本框：不受限。
        for (type in listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)) {
            assertFalse(session.begin(editor(type, 2), false, false, "rime_ice").isAsciiMode)
            assertFalse(session.scoped)
        }
        // 借密码类型的多行编辑器：先给英文，能切中文；可见密码同样。
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
            val code = editor(InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 3)
            assertTrue(session.begin(code, false, false, "rime_ice").isAsciiMode)
            assertFalse(session.locksNativeInput)
            assertTrue(session.choose(false, "rime_ice"))
            assertFalse(session.directInput)
        }
        // 网址框（有的编辑器用它关联想）：先给英文也能切中文。
        val uri = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 4)
        session.begin(uri, false, false, "rime_ice")
        assertFalse(session.locksNativeInput)
        // 真正的密码框（单行）照旧锁英文。
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val password = editor(InputType.TYPE_CLASS_TEXT or variation, 5)
            assertTrue(session.begin(password, false, false, "rime_ice").isAsciiMode)
            assertTrue(session.locksNativeInput)
            assertFalse(session.choose(false, "rime_ice"))
        }
        assertTrue(EditorFieldPolicy.fromInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).locksNativeInput)
    }

    @Test fun numberPasswordIsDigitsEvenWhenSignedAndDecimalFlagsArePresent() {
        val policy = EditorFieldPolicy.fromInputType(InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_VARIATION_PASSWORD or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertTrue(policy.numberPanel)
        assertEquals("42", policy.typedLiteral("４２"))
        listOf("+", "-", ".", "a", "中").forEach { assertTrue(policy.typedLiteral(it).isEmpty()) }
    }

    @Test fun numericFlagsAreIndependentAndDoNotEnableChineseOrCalculatorExpressions() {
        for (signed in listOf(false, true)) for (decimal in listOf(false, true)) {
            val policy = EditorFieldPolicy.fromInputType(InputType.TYPE_CLASS_NUMBER or
                (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0) or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0))
            assertTrue(policy.numberPanel)
            assertTrue(policy.locksNativeInput)
            assertEquals("7", policy.typedLiteral("7"))
            assertEquals(signed, policy.typedLiteral("-").isNotEmpty())
            // 小数点不看 decimal 标记：纯数字框是否收小数由输入框自己决定（#92）。
            assertEquals(".", policy.typedLiteral("."))
            assertEquals(",", policy.typedLiteral("，"))
            assertTrue(policy.typedLiteral("*").isEmpty())
            assertTrue(policy.typedLiteral("中").isEmpty())
        }
    }

    @Test fun phoneAndDateTimeUseTheirOwnPunctuation() {
        val phone = EditorFieldPolicy.fromInputType(InputType.TYPE_CLASS_PHONE)
        val date = EditorFieldPolicy.fromInputType(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        val time = EditorFieldPolicy.fromInputType(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        val dateTime = EditorFieldPolicy.fromInputType(InputType.TYPE_CLASS_DATETIME)
        assertEquals("+*#,;", phone.typedLiteral("+*#,;"))
        assertEquals("/-.", date.typedLiteral("/-."))
        assertTrue(date.typedLiteral(":").isEmpty())
        assertEquals(":. PM", time.typedLiteral(":. PM"))
        assertTrue(time.typedLiteral("/").isEmpty())
        assertEquals("/-:", dateTime.typedLiteral("/-:"))
        listOf(phone, date, time, dateTime).forEach {
            assertTrue(it.numberPanel)
            assertTrue(it.locksNativeInput)
        }
    }

    @Test fun emailAndUriStartAsciiButExplicitNativeSelectionSurvivesRestartAndLeavesOriginalUntouched() {
        listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI).forEach { variation ->
            val session = EditorSessionInputPolicy()
            val info = editor(InputType.TYPE_CLASS_TEXT or variation)
            assertTrue(session.begin(info, false, false, "original-layout").isAsciiMode)
            assertFalse(session.locksNativeInput)
            assertTrue(session.choose(false, "international-layout"))
            assertFalse(session.directInput)
            val restarted = session.begin(info, true, false, "international-layout")
            assertFalse(restarted.isAsciiMode)
            assertEquals("international-layout", restarted.currentSchemaId)
            val restored = session.begin(editor(InputType.TYPE_CLASS_TEXT, 2), false, false, "international-layout")
            assertFalse(restored.isAsciiMode)
            assertEquals("original-layout", restored.currentSchemaId)
            assertNull(session.schemaOverride)
        }
    }

    @Test fun movingBetweenConstrainedFieldsDoesNotReplaceOriginalProfileWithTemporaryEnglish() {
        val session = EditorSessionInputPolicy()
        session.begin(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), false, false, "original")
        session.begin(editor(InputType.TYPE_CLASS_PHONE, 2), false, true, "temporary")
        val restored = session.begin(editor(InputType.TYPE_CLASS_TEXT, 3), false, true, "temporary")
        assertFalse(restored.isAsciiMode)
        assertEquals("original", restored.currentSchemaId)
    }

    @Test fun ordinaryNoSuggestionsSearchAndRawTerminalsDoNotForceEnglish() {
        listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).forEach { type ->
            val session = EditorSessionInputPolicy()
            assertFalse(session.begin(editor(type), false, false, "original").isAsciiMode)
            assertFalse(session.scoped)
            assertFalse(session.directInput)
        }
    }

    private fun uri(action: Int, hint: String? = null, id: Int = 1) =
        editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, id).apply {
            imeOptions = action or EditorInfo.IME_FLAG_NO_FULLSCREEN
            hintText = hint
        }

    @Test fun chromeNavigationBarKeepsLanguageAndLayoutAcrossNewConnections() {
        val bar = uri(EditorInfo.IME_ACTION_GO, "在 Google 中搜索或输入网址").apply {
            inputType = 0x80011
            imeOptions = 0x12000002
            packageName = "com.android.chrome"
        }
        for (english in listOf(false, true)) {
            val session = EditorSessionInputPolicy()
            assertEquals(english, session.begin(bar, false, english, "chinese-14").isAsciiMode)
            assertEquals(EditorFieldPolicy.Kind.SEARCH_OR_ADDRESS, session.field.kind)
            assertNull(session.engineAsciiOverride())
            assertFalse(session.directInput)
            session.completePreparation(english, "chinese-14")
            assertTrue(session.choose(!english, "other-layout"))
            bar.hintText = null
            bar.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            for (restart in listOf(true, false)) {
                val resumed = session.begin(bar, restart, english, "chinese-14")
                assertEquals(EditorSessionPresentation(!english, "other-layout"), resumed)
                assertFalse(session.directInput)
            }
            assertEquals(EditorSessionPresentation(english, "chinese-14"),
                session.begin(editor(InputType.TYPE_CLASS_TEXT, 2), false, !english, "other-layout"))
        }
    }

    @Test fun metadataSeparatesMixedInputsFromDedicatedAddressesWithoutReadingContent() {
        listOf(uri(EditorInfo.IME_ACTION_SEARCH), uri(EditorInfo.IME_ACTION_GO),
            uri(EditorInfo.IME_ACTION_GO, "Rechercher ou saisir une URL"),
            uri(EditorInfo.IME_ACTION_DONE, "Search the web or type a URL"),
            uri(EditorInfo.IME_ACTION_DONE, "輸入網址或搜尋"), uri(EditorInfo.IME_ACTION_NEXT, "搜索")
        ).forEach { assertEquals(EditorFieldPolicy.Kind.SEARCH_OR_ADDRESS, EditorFieldPolicy.from(it).kind) }
        listOf(uri(EditorInfo.IME_ACTION_NEXT), uri(EditorInfo.IME_ACTION_DONE),
            uri(EditorInfo.IME_ACTION_GO, "https://example.org"), uri(EditorInfo.IME_ACTION_GO, "请输入网址"),
            uri(EditorInfo.IME_ACTION_GO, "请输入 URL"), uri(EditorInfo.IME_ACTION_GO, "Server address"),
            uri(EditorInfo.IME_ACTION_GO, "Search engine URL"), uri(EditorInfo.IME_ACTION_DONE, "搜索引擎网址"),
            uri(EditorInfo.IME_ACTION_GO).apply { fieldName = "callback_url" }
        ).forEach { assertEquals(EditorFieldPolicy.Kind.ADDRESS, EditorFieldPolicy.from(it).kind) }
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val info = uri(EditorInfo.IME_ACTION_SEARCH, "搜索").apply { inputType = InputType.TYPE_CLASS_TEXT or variation }
            val session = EditorSessionInputPolicy()
            assertTrue(session.begin(info, false, false, "chinese-9").isAsciiMode)
            assertTrue(session.directInput)
        }
    }

    @Test fun coldEnginePreferenceAndTemporaryPasswordModeAreNotConfused() {
        val session = EditorSessionInputPolicy()
        val mixed = uri(EditorInfo.IME_ACTION_GO)
        session.begin(mixed, false, false, "")
        session.completePreparation(true, "saved-layout")
        session.choose(false, "temporary-layout")
        assertEquals(EditorSessionPresentation(true, "saved-layout"),
            session.begin(editor(InputType.TYPE_CLASS_TEXT, 2), false, false, "temporary-layout"))

        val password = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        session.begin(password, false, false, "chinese-9")
        assertFalse(session.begin(mixed, false, true, "temporary").isAsciiMode)
        assertEquals(false, session.engineAsciiOverride())
    }

    @Test fun anonymousPageEditorsKeepManualLanguageOnlyForRestartsOfTheSameField() {
        val session = EditorSessionInputPolicy()
        val page = uri(EditorInfo.IME_ACTION_GO, id = -1)
        session.begin(page, false, true, "original")
        session.choose(false, "chinese-14")
        page.inputType = page.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertEquals(EditorSessionPresentation(false, "chinese-14"), session.begin(page, true, true, "original"))
        assertEquals(EditorSessionPresentation(true, "original"), session.begin(page, false, false, "chinese-14"))
        page.imeOptions = EditorInfo.IME_ACTION_NEXT
        assertTrue(session.begin(page, true, false, "chinese-14").isAsciiMode)
        assertTrue(session.directInput)
    }

    @Test fun emailAndUrlKeepCurrentLanguageWhenAutoEnglishIsOffButPasswordsStayLocked() {
        listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI).forEach { variation ->
            val session = EditorSessionInputPolicy()
            val address = editor(InputType.TYPE_CLASS_TEXT or variation)
            val entered = session.begin(address, false, false, "chinese", addressEnglish = false)
            assertEquals(EditorSessionPresentation(false, "chinese"), entered)
            assertEquals(EditorFieldPolicy.Kind.ADDRESS, session.field.kind)
            assertFalse(session.directInput)
            assertNull(session.engineAsciiOverride())
            // Switching inside the field stays local, as with auto-English on.
            assertTrue(session.choose(true))
            assertTrue(session.directInput)
            assertEquals(EditorSessionPresentation(false, "chinese"),
                session.begin(editor(InputType.TYPE_CLASS_TEXT, 2), false, true, "chinese", addressEnglish = false))

            val password = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 3)
            assertTrue(session.begin(password, false, false, "chinese", addressEnglish = false).isAsciiMode)
            assertTrue(session.locksNativeInput)
        }
    }

    @Test fun emailFieldWithAutoEnglishOffAdoptsTheSavedLanguageOnColdStart() {
        val session = EditorSessionInputPolicy()
        val email = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        session.begin(email, false, false, "", addressEnglish = false)
        session.completePreparation(false, "saved-layout")
        session.choose(true)
        assertEquals(EditorSessionPresentation(false, "saved-layout"),
            session.begin(editor(InputType.TYPE_CLASS_TEXT, 2), false, true, "saved-layout"))
    }

    @Test fun onlyTypedKeysAreNormalizedAndNativeWordsAreNeverPartiallyInserted() {
        val policy = EditorFieldPolicy(EditorFieldPolicy.Kind.PASSWORD)
        // Individual fictional key samples only; no password or host content is retained by the policy.
        assertEquals("A", policy.typedLiteral("Ａ"))
        assertEquals("@", policy.typedLiteral("＠"))
        assertEquals(".", policy.typedLiteral("。"))
        assertEquals("\"", policy.typedLiteral("“"))
        assertEquals(" ", policy.typedLiteral("\u3000"))
        assertTrue(policy.typedLiteral("a中").isEmpty())
        assertTrue(policy.typedLiteral("\n").isEmpty())
    }
}
