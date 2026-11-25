package com.kingzcheung.xime.service

import android.view.KeyEvent
import com.kingzcheung.xime.settings.*
import org.junit.Assert.*
import org.junit.Test

class PhysicalKeyboardPolicyTest {
    private val profile = InputProfile(InputLanguage.CHINESE, InputScheme.PINYIN, InputLayout.T9,
        EngineProfile.Rime("installed-backend"))
    private fun source(state: CandidateState = CandidateState(), english: Boolean = false, pending: Boolean = false) =
        HardwareCandidateSnapshot(1, profile, state, english, predictionPending = pending)
    private val composing = CandidateState(inputText = "ni", isComposing = true, candidates = listOf("你", "呢"))

    @Test fun `all arrows always belong to the editor across input states and engine readiness`() {
        val states = listOf(source(), source(composing), source(pending = true),
            source(CandidateState(associationCandidates = listOf("世界"))),
            source(CandidateState(pendingEnglishText = "hel", associationCandidates = listOf("hello")), english = true))
        for (state in states) for (ready in listOf(true, false, null))
            for (key in listOf(HardwareCandidateKey.LEFT, HardwareCandidateKey.RIGHT, HardwareCandidateKey.UP, HardwareCandidateKey.DOWN)) {
                assertEquals(HardwareCandidateDecision.MoveCursor, physicalCandidateDecision(key, state, ready))
            }
    }

    @Test fun `escape never dismisses the IME even after repeated cancel or pending predictions`() {
        for (state in listOf(source(), source(composing), source(pending = true)))
            for (ready in listOf(true, false, null)) repeat(4) {
                assertEquals(HardwareCandidateDecision.Cancel, physicalCandidateDecision(HardwareCandidateKey.CANCEL, state, ready))
            }
    }

    @Test fun `space selects the composing word across layouts and native languages`() {
        for (layout in listOf(InputLayout.T9, InputLayout.QWERTY, InputLayout.MERGED14)) {
            val state = source(composing).copy(profile = profile.copy(layout = layout))
            assertEquals(HardwareCandidateDecision.Confirm(0, false),
                physicalCandidateDecision(HardwareCandidateKey.SPACE, state, true))
        }
        val japanese = source(composing.copy(inputText = "kana", candidates = listOf("仮名", "かな")))
            .copy(profile = profile.copy(language = InputLanguage.JAPANESE))
        assertEquals(HardwareCandidateDecision.Confirm(0, false),
            physicalCandidateDecision(HardwareCandidateKey.SPACE, japanese, true))
    }

    @Test fun `space follows the selected T9 candidate but not an unrelated layout focus`() {
        val next = composing.advanceT9Candidate(profile)
        assertEquals(HardwareCandidateDecision.Confirm(1, false),
            physicalCandidateDecision(HardwareCandidateKey.SPACE, source(next), true))
        assertEquals(HardwareCandidateDecision.Confirm(0, false), physicalCandidateDecision(
            HardwareCandidateKey.SPACE, source(next).copy(profile = profile.copy(layout = InputLayout.QWERTY)), true))
    }

    @Test fun `space in expanded candidates uses the displayed filtered mapping`() {
        val state = source(composing.copy(expandedCandidates = listOf(
            com.kingzcheung.xime.rime.RimeCandidate("你好", "ni hao"),
            com.kingzcheung.xime.rime.RimeCandidate("你", "ni")), expandedCandidatesLoaded = true))
            .copy(expanded = true, singleCharOnly = true)
        val decision = physicalCandidateDecision(HardwareCandidateKey.SPACE, state, true)
        assertEquals(HardwareCandidateDecision.Confirm(0, false), decision)
        assertEquals(1, state.expandedGlobalIndices!![(decision as HardwareCandidateDecision.Confirm).index])
        assertEquals(HardwareCandidateDecision.Consume, physicalCandidateDecision(HardwareCandidateKey.SPACE,
            state.copy(state = state.state.copy(expandedCandidates = emptyList())), true))
    }

    @Test fun `space never emits raw letters or selects stale candidates while composition is unavailable`() {
        for (ready in listOf(false, null)) {
            assertEquals(HardwareCandidateDecision.Consume,
                physicalCandidateDecision(HardwareCandidateKey.SPACE, source(composing), ready))
        }
        // The engine has input the UI does not show yet, or the bar still shows clipboard records.
        for (state in listOf(source(), source(composing.copy(isShowingRecentClipboard = true)))) {
            assertEquals(HardwareCandidateDecision.Consume,
                physicalCandidateDecision(HardwareCandidateKey.SPACE, state, true))
        }
        // A current composition the engine has no candidate for is committed as typed, without a space (user, 10-24).
        assertEquals(HardwareCandidateDecision.CommitTyped,
            physicalCandidateDecision(HardwareCandidateKey.SPACE, source(composing.copy(candidates = emptyList())), true))
    }

    @Test fun `idle association and English spaces stay literal and never select predictions`() {
        for (state in listOf(source(), source(pending = true),
            source(CandidateState(associationCandidates = listOf("世界"))),
            source(CandidateState(pendingEnglishText = "hello", associationCandidates = listOf("help")), english = true),
            source(CandidateState(candidates = listOf("clipboard"), isShowingRecentClipboard = true)))) {
            for (ready in listOf(false, null)) assertEquals(HardwareCandidateDecision.LiteralSpace,
                physicalCandidateDecision(HardwareCandidateKey.SPACE, state, ready))
        }
        assertEquals(HardwareCandidateDecision.LiteralSpace,
            physicalCandidateDecision(HardwareCandidateKey.SPACE, source(composing, english = true), true))
    }

    @Test fun `enter preserves raw composition and editor actions instead of confirming candidates`() {
        for (state in listOf(source(), source(composing), source(CandidateState(associationCandidates = listOf("世界"))))) {
            for (ready in listOf(true, false, null)) assertEquals(HardwareCandidateDecision.DefaultInput,
                physicalCandidateDecision(HardwareCandidateKey.ENTER, state, ready))
        }
    }

    @Test fun `only displayed numbered candidates claim digits`() {
        assertEquals(HardwareCandidateDecision.Confirm(1, false), physicalCandidateDecision(HardwareCandidateKey.DIGIT, source(composing), true, 1))
        val suggestions = source(CandidateState(associationCandidates = listOf("世界", "朋友")))
        assertEquals(HardwareCandidateDecision.Confirm(0, true), physicalCandidateDecision(HardwareCandidateKey.DIGIT, suggestions, false, 0))
        for (state in listOf(source(), source(pending = true), source(composing.copy(candidates = emptyList())),
            source(CandidateState(pendingEnglishText = "hello", associationCandidates = listOf("hello")), english = true))) {
            for (digit in 0..9) assertEquals(HardwareCandidateDecision.DefaultInput,
                physicalCandidateDecision(HardwareCandidateKey.DIGIT, state, true, digit))
        }
        assertEquals(HardwareCandidateDecision.DefaultInput, physicalCandidateDecision(HardwareCandidateKey.DIGIT, source(composing), true, 9))
        assertEquals(HardwareCandidateDecision.DefaultInput, physicalCandidateDecision(HardwareCandidateKey.DIGIT, source(composing), false, 0))
        assertEquals(HardwareCandidateDecision.DefaultInput, physicalCandidateDecision(HardwareCandidateKey.DIGIT,
            source(CandidateState(candidates = listOf("clipboard"), isShowingRecentClipboard = true)), false, 0))
    }

    @Test fun `all US punctuation and number row shift pairs are mapped independently of language`() {
        val pairs = listOf(
            Triple(KeyEvent.KEYCODE_SLASH, "/", "?"), Triple(KeyEvent.KEYCODE_BACKSLASH, "\\", "|"),
            Triple(KeyEvent.KEYCODE_SEMICOLON, ";", ":"), Triple(KeyEvent.KEYCODE_APOSTROPHE, "'", "\""),
            Triple(KeyEvent.KEYCODE_GRAVE, "`", "~"), Triple(KeyEvent.KEYCODE_COMMA, ",", "<"),
            Triple(KeyEvent.KEYCODE_PERIOD, ".", ">"), Triple(KeyEvent.KEYCODE_MINUS, "-", "_"),
            Triple(KeyEvent.KEYCODE_EQUALS, "=", "+"), Triple(KeyEvent.KEYCODE_LEFT_BRACKET, "[", "{"),
            Triple(KeyEvent.KEYCODE_RIGHT_BRACKET, "]", "}"))
        for ((code, plain, shifted) in pairs) {
            assertEquals(plain, keyCodeToKey(code, false))
            assertEquals(shifted, keyCodeToKey(code, true))
            assertNull(hardwareCandidateDigitIndex(code, true))
        }
        val shifted = ")!@#$%^&*("
        for (digit in 0..9) {
            assertEquals(digit.toString(), keyCodeToKey(KeyEvent.KEYCODE_0 + digit, false))
            assertEquals(shifted[digit].toString(), keyCodeToKey(KeyEvent.KEYCODE_0 + digit, true))
            assertNull(hardwareCandidateDigitIndex(KeyEvent.KEYCODE_0 + digit, true))
            assertEquals(digit.toString(), keyCodeToKey(KeyEvent.KEYCODE_NUMPAD_0 + digit, false))
            assertNull(hardwareCandidateDigitIndex(KeyEvent.KEYCODE_NUMPAD_0 + digit, false))
        }
    }

    /** GitHub #290：中文标点下 Shift 打的 " ( ) 等交给 Rime 出全角，英文 / 半角和小键盘照旧原样上屏。 */
    @Test fun `shifted symbols follow chinese punctuation while numpad stays literal`() {
        for (symbol in listOf("\"", "(", ")", ":", "?", "!", "<", ">", "_", "{", "}", "~")) {
            assertFalse(symbol, hardwareSymbolIsLiteral(symbol, shifted = true, numpad = false, chinesePunctuation = true))
            assertTrue(symbol, hardwareSymbolIsLiteral(symbol, shifted = true, numpad = false, chinesePunctuation = false))
        }
        assertTrue(hardwareSymbolIsLiteral("+", shifted = false, numpad = true, chinesePunctuation = true))
        assertTrue(hardwareSymbolIsLiteral("7", shifted = false, numpad = true, chinesePunctuation = true))
        assertFalse("不按 Shift 的符号本来就走 Rime", hardwareSymbolIsLiteral("'", shifted = false, numpad = false, chinesePunctuation = true))
        assertFalse("Shift 字母不归这里管", hardwareSymbolIsLiteral("A", shifted = true, numpad = false, chinesePunctuation = false))
    }

    @Test fun `digits and a dot after an all-capitals composition are typed as they are`() {
        assertTrue(compositionIsAllCapitals("N"))
        assertTrue(compositionIsAllCapitals("AGENTS"))
        assertFalse("夹着拼音照旧", compositionIsAllCapitals("zuogeDNA"))
        assertFalse(compositionIsAllCapitals("ni"))
        assertFalse(compositionIsAllCapitals(""))
        assertFalse(compositionIsAllCapitals(null))
        for (key in listOf("0", "1", "9", ".")) assertTrue(key, capitalsKeepLiteralKey(key))
        for (key in listOf(",", "a", "N", "10", "space", "。")) assertFalse(key, capitalsKeepLiteralKey(key))
    }

    /** 屏幕键盘数字行的大写例外只在雾凇 26 键：14 键打 di 送进引擎的是 DU，点 2 还要得到「第2」（11-22 审查）。 */
    @Test fun `screen digit keeps capitals only on the 26-key rime_ice scheme`() {
        assertTrue(screenDigitKeepsCapitals("rime_ice", "N"))
        assertFalse("26 键拼音照旧先上屏候选", screenDigitKeepsCapitals("rime_ice", "di"))
        assertFalse("14 键合并键是大写代表字母", screenDigitKeepsCapitals("pinyin_14jian", "DU"))
        assertFalse("双拼 14 键同样", screenDigitKeepsCapitals("double_pinyin_flypy_14jian", "DU"))
        assertFalse(screenDigitKeepsCapitals("t9_pinyin", "34"))
    }

    /** 要在实体键盘数字选词、. 翻页之前拦下，不然 N1 的 1 还是会选词。 */
    @Test fun `the capitals check runs before digit selection and paging`() {
        val src = java.io.File("src/main/java/com/kingzcheung/xime/service/ImeKeyRouter.kt").readText()
        val body = src.substringAfter("private fun routeKeyPress(")
        val capitals = body.indexOf("compositionIsAllCapitals(")
        assertTrue(capitals > 0)
        assertTrue(capitals < body.indexOf("processHardwareCandidateKey(hardwareEvent)) return@command"))
        assertTrue(capitals < body.indexOf("if (hardwarePageDirection != 0 &&"))
    }

    /**
     * #548：, . 是翻页键，从 onKeyDown 的翻页分支进来时不带 hardwareEvent。数字后半角（#527）和大写后原样（#528）
     * 都得认 hardwarePageDirection，不然 104 里 123. 还是「。」、AGENTS. 的点还是翻页。
     */
    @Test fun `paging keys also get the digit and capitals rules`() {
        val router = java.io.File("src/main/java/com/kingzcheung/xime/service/ImeKeyRouter.kt").readText()
        val body = router.substringAfter("private fun routeKeyPress(")
        val physical = body.indexOf("val physical = hardwareEvent != null || hardwarePageDirection != 0")
        assertTrue(physical > 0)
        assertTrue(physical < body.indexOf("compositionIsAllCapitals("))
        val pagedDigit = body.indexOf("if (hardwareEvent == null && hardwarePageDirection != 0 && !zhuyinHardwareActive() &&")
        assertTrue(pagedDigit > 0)
        assertTrue("要在翻页之前", pagedDigit < body.indexOf("if (hardwarePageDirection != 0 &&"))
        val service = java.io.File("src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt").readText()
        assertTrue("翻页分支仍然不带 hardwareEvent，规则靠方向认",
            "keyRouter.handleKeyPress(symbol, e.isShiftPressed, hardwarePageDirection = pageDirection)" in service)
        assertTrue(digitSeparatorStaysHalfWidth(".", "3"))
        assertTrue(digitSeparatorStaysHalfWidth(",", "1"))
    }
}
