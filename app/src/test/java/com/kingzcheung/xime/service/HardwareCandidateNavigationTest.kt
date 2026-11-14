package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.InputCommandQueue
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.settings.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class HardwareCandidateNavigationTest {
    private val profile = InputProfile(InputLanguage.CHINESE, InputScheme.PINYIN, InputLayout.T9,
        EngineProfile.Rime("installed-backend"))
    private val composition = CandidateState(inputText = "ceshi", isComposing = true,
        candidates = listOf("测试", "侧室", "测视"), engineRevision = 4L)
    private fun snapshot(state: CandidateState = composition, english: Boolean = false) =
        HardwareCandidateSnapshot(1, profile, state, english)

    @Test fun chineseAndJapaneseCompositionNavigateAndConfirmWithoutMovingTheEditor() {
        for (language in listOf(InputLanguage.CHINESE, InputLanguage.JAPANESE)) {
            val navigation = HardwareCandidateNavigation()
            val source = snapshot().copy(profile = profile.copy(language = language))
            assertEquals(HardwareCandidateDecision.Highlight(1), navigation.decide(HardwareCandidateKey.RIGHT, source, true))
            assertEquals(HardwareCandidateDecision.Confirm(1, false), navigation.decide(HardwareCandidateKey.SPACE, source, true))
            assertEquals(HardwareCandidateDecision.Highlight(0), navigation.decide(HardwareCandidateKey.LEFT, source, true))
            assertEquals(HardwareCandidateDecision.Confirm(0, false), navigation.decide(HardwareCandidateKey.SPACE, source, true))
        }
    }

    @Test fun englishSpaceNeverChoosesCompletionAfterNavigation() {
        val navigation = HardwareCandidateNavigation()
        val source = snapshot(CandidateState(pendingEnglishText = "tes",
            associationCandidates = listOf("test", "testing", "tests")), english = true)
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, source, false))
        assertEquals(HardwareCandidateDecision.Highlight(1), navigation.decide(HardwareCandidateKey.RIGHT, source, false))
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, source, false))
        repeat(8) { navigation.decide(HardwareCandidateKey.RIGHT, source, false) }
        assertEquals(3, navigation.selectedIndex(source))
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, source, false))
    }

    @Test fun chineseSuggestionSpaceNeverSelectsEvenAfterNavigation() {
        val navigation = HardwareCandidateNavigation()
        val source = snapshot(CandidateState(associationCandidates = listOf("世界", "朋友", "大家")))
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, source, false))
        navigation.decide(HardwareCandidateKey.RIGHT, source, false)
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, source, false))
    }

    @Test fun enterAlwaysPreservesRawCompositionOrEditorAction() {
        val navigation = HardwareCandidateNavigation()
        assertEquals(HardwareCandidateDecision.DefaultInput,
            navigation.decide(HardwareCandidateKey.ENTER, snapshot(), true))
        val suggestions = snapshot(CandidateState(associationCandidates = listOf("世界", "朋友")))
        assertEquals(HardwareCandidateDecision.DefaultInput,
            navigation.decide(HardwareCandidateKey.ENTER, suggestions, false))
        navigation.decide(HardwareCandidateKey.RIGHT, suggestions, false)
        assertEquals(HardwareCandidateDecision.DefaultInput,
            navigation.decide(HardwareCandidateKey.ENTER, suggestions, false))
        assertEquals(HardwareCandidateDecision.DefaultInput,
            navigation.decide(HardwareCandidateKey.ENTER, suggestions.copy(predictionPending = true), false))
        assertEquals(HardwareCandidateDecision.DefaultInput,
            navigation.decide(HardwareCandidateKey.ENTER, suggestions.copy(associationLimit = 0), false))
        val english = snapshot(CandidateState(pendingEnglishText = "hel", associationCandidates = listOf("hello")), true)
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.ENTER, english, false))
        navigation.decide(HardwareCandidateKey.RIGHT, english, false)
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.ENTER, english, false))
    }

    @Test fun numberSelectionUsesTheSameVisibleListForEverySource() {
        for (source in listOf(snapshot(),
            snapshot(CandidateState(associationCandidates = listOf("世界", "朋友", "大家"))))) {
            val navigation = HardwareCandidateNavigation()
            for (index in source.words.indices) {
                assertEquals(HardwareCandidateDecision.Confirm(index, source.presentation.association),
                    navigation.decide(HardwareCandidateKey.DIGIT, source, !source.presentation.association, digitIndex = index))
            }
            assertEquals(if (source.presentation.association) HardwareCandidateDecision.DefaultInput else HardwareCandidateDecision.Consume,
                navigation.decide(HardwareCandidateKey.DIGIT, source, false, digitIndex = 9))
        }
        val navigation = HardwareCandidateNavigation()
        val suggestions = snapshot(CandidateState(associationCandidates = listOf("旧词")))
        for (source in listOf(snapshot(CandidateState()), suggestions.copy(predictionPending = true), suggestions.copy(associationLimit = 0))) {
            assertEquals(HardwareCandidateDecision.DefaultInput,
                navigation.decide(HardwareCandidateKey.DIGIT, source, false, digitIndex = 0))
        }
    }

    @Test fun englishNumbersAreLiteralUntilTheUserExplicitlyNavigatesCandidates() {
        val navigation = HardwareCandidateNavigation()
        val source = snapshot(CandidateState(pendingEnglishText = "hello", associationCandidates = listOf("hello", "hellos")), true)
        for (index in 0..9) assertEquals(HardwareCandidateDecision.DefaultInput,
            navigation.decide(HardwareCandidateKey.DIGIT, source, false, digitIndex = index))
        navigation.decide(HardwareCandidateKey.RIGHT, source, false)
        assertEquals(HardwareCandidateDecision.Confirm(1, true), navigation.decide(HardwareCandidateKey.DIGIT, source, false, digitIndex = 1))
        val nextWord = source.copy(state = source.state.copy(pendingEnglishText = "you"))
        assertEquals(HardwareCandidateDecision.DefaultInput,
            navigation.decide(HardwareCandidateKey.DIGIT, nextWord, false, digitIndex = 1))
    }

    @Test fun idleDigitsAreLiteralInEveryLanguageButUnfinishedDecodingKeepsOwnership() {
        for (language in InputLanguage.entries) {
            val source = snapshot(CandidateState(), language == InputLanguage.ENGLISH)
                .copy(profile = profile.copy(language = language))
            for (index in 0..9) {
                assertEquals(HardwareCandidateDecision.DefaultInput,
                    HardwareCandidateNavigation().decide(HardwareCandidateKey.DIGIT, source, false, digitIndex = index))
            }
        }
        // The previous letter is already in the FIFO/engine, while its candidate UI is late.
        assertEquals(HardwareCandidateDecision.Consume, HardwareCandidateNavigation().decide(
            HardwareCandidateKey.DIGIT, snapshot(CandidateState()), true, digitIndex = 0))
    }

    @Test fun pendingPredictionNeverNavigatesOrConfirmsOldWords() {
        val navigation = HardwareCandidateNavigation()
        val source = snapshot(CandidateState(associationCandidates = listOf("旧词", "旧候选")))
        navigation.decide(HardwareCandidateKey.RIGHT, source, false)
        val waiting = source.copy(predictionPending = true)
        assertEquals(HardwareCandidateDecision.Host, navigation.decide(HardwareCandidateKey.RIGHT, waiting, false))
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, waiting, false))
        assertNull(navigation.selectedIndex(source))
        assertEquals(HardwareCandidateDecision.Cancel, navigation.decide(HardwareCandidateKey.CANCEL, waiting, false))
    }

    @Test fun unrelatedPredictionCompletionDoesNotResetCompositionOrEnglishSelection() {
        for (source in listOf(snapshot(), snapshot(CandidateState(pendingEnglishText = "tes",
            associationCandidates = listOf("test")), true))) {
            val navigation = HardwareCandidateNavigation()
            val waiting = source.copy(predictionPending = true)
            navigation.decide(HardwareCandidateKey.RIGHT, waiting, !source.english)
            assertEquals(1, navigation.selectedIndex(source))
            assertEquals(if (source.presentation.association) HardwareCandidateDecision.DefaultInput else HardwareCandidateDecision.Confirm(1, false),
                navigation.decide(HardwareCandidateKey.SPACE, source, !source.english))
        }
    }

    @Test fun unavailableEngineProtectsKnownCompositionWithoutDisablingAnIdleEditor() {
        val navigation = HardwareCandidateNavigation()
        val emptyUi = snapshot(CandidateState())
        for (key in listOf(HardwareCandidateKey.LEFT, HardwareCandidateKey.RIGHT,
            HardwareCandidateKey.UP, HardwareCandidateKey.DOWN, HardwareCandidateKey.SPACE)) {
            assertEquals(HardwareCandidateDecision.Consume, navigation.decide(key, emptyUi, true))
            assertEquals(if (key == HardwareCandidateKey.SPACE) HardwareCandidateDecision.DefaultInput else HardwareCandidateDecision.Host,
                navigation.decide(key, emptyUi, null))
            assertEquals(HardwareCandidateDecision.Consume, navigation.decide(key, snapshot(), null))
        }
        assertEquals(HardwareCandidateDecision.Host, navigation.decide(HardwareCandidateKey.LEFT, emptyUi, false))
    }

    @Test fun changedCandidateIdentityCannotReuseAnOldSelection() {
        val source = snapshot()
        for (changed in listOf(
            source.copy(editor = 2),
            source.copy(profile = profile.copy(language = InputLanguage.JAPANESE)),
            source.copy(state = composition.copy(engineRevision = 5)),
            source.copy(state = composition.copy(inputText = "ce")),
            source.copy(state = composition.copy(candidates = composition.candidates.reversed())),
            source.copy(state = composition.copy(candidateComments = listOf("changed"))),
            source.copy(state = composition.copy(candidateActions = listOf(CandidateAction.plugin("replacement")))),
        )) {
            val navigation = HardwareCandidateNavigation()
            navigation.decide(HardwareCandidateKey.RIGHT, source, true)
            assertNull(navigation.selectedIndex(changed))
            assertEquals(HardwareCandidateDecision.Confirm(0, false), navigation.decide(HardwareCandidateKey.SPACE, changed, true))
            assertNull(navigation.selectedIndex(source))
        }
    }

    @Test fun newEnglishPrefixAndAssociationContextInvalidateExplicitSpaceSelection() {
        val source = snapshot(CandidateState(pendingEnglishText = "tes", associationCandidates = listOf("test")), true)
        for (changed in listOf(
            source.copy(state = source.state.copy(pendingEnglishText = "test")),
            source.copy(associationContext = "new surrounding context"),
        )) {
            val navigation = HardwareCandidateNavigation()
            navigation.decide(HardwareCandidateKey.RIGHT, source, false)
            assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, changed, false))
        }
    }

    @Test fun nativeSelectionIsLimitedToTenDisplayedCandidatesAndTouchArrowsRetainPaging() {
        val navigation = HardwareCandidateNavigation()
        val source = snapshot(composition.copy(candidates = (0..15).map { "词$it" }, hasNextPage = true, hasPrevPage = true))
        repeat(20) { navigation.decide(HardwareCandidateKey.RIGHT, source, true) }
        assertEquals(9, navigation.selectedIndex(source))
        assertEquals(HardwareCandidateDecision.Page(1, wholeWindow = true),
            navigation.decide(HardwareCandidateKey.RIGHT, source, true, pageAtBoundary = true))
        assertEquals(HardwareCandidateDecision.Page(-1), navigation.decide(HardwareCandidateKey.LEFT, source, true, pageAtBoundary = true))
    }

    @Test fun escapeCancelsCompositionAndEnglishCandidatesBeforeArrowsCanMoveTheEditor() {
        for (source in listOf(snapshot(), snapshot(CandidateState(pendingEnglishText = "typed"), true))) {
            val navigation = HardwareCandidateNavigation()
            assertEquals(HardwareCandidateDecision.Cancel, navigation.decide(HardwareCandidateKey.CANCEL, source, !source.english))
            // Clearing pending text is a state operation, not a request to delete its already committed characters.
            val cleared = source.copy(state = CandidateState())
            assertEquals(HardwareCandidateDecision.Host, navigation.decide(HardwareCandidateKey.RIGHT, cleared, false))
        }
    }

    @Test fun heldCandidateArrowRemainsOwnedUntilReleaseEvenAfterCandidatesAreCancelled() {
        for (key in listOf(HardwareCandidateKey.LEFT, HardwareCandidateKey.RIGHT,
            HardwareCandidateKey.UP, HardwareCandidateKey.DOWN)) {
            val navigation = HardwareCandidateNavigation()
            navigation.startPress(key, repeat = false)
            assertNotEquals(HardwareCandidateDecision.Host, navigation.decide(key, snapshot(), true))
            navigation.retainPress(key)
            navigation.clear()
            navigation.startPress(key, repeat = true)
            assertEquals(HardwareCandidateDecision.Host, navigation.decide(key, snapshot(CandidateState()), false))
            assertTrue(navigation.ownsPress(key))
            navigation.releasePress(key)
            assertFalse(navigation.ownsPress(key))
            navigation.startPress(key, repeat = false)
            assertFalse(navigation.ownsPress(key))
        }
    }

    @Test fun sideDockHiddenSuggestionsDoNotOwnArrowsOrSelectInvisibleWords() {
        val navigation = HardwareCandidateNavigation()
        val source = snapshot(CandidateState(pendingEnglishText = "hel", associationCandidates = listOf("hello", "help")), true)
        navigation.decide(HardwareCandidateKey.RIGHT, source, false)
        val hidden = source.copy(associationLimit = 0)
        assertTrue(hidden.words.isEmpty())
        assertNull(navigation.selectedIndex(hidden))
        for (key in listOf(HardwareCandidateKey.LEFT, HardwareCandidateKey.RIGHT, HardwareCandidateKey.UP, HardwareCandidateKey.DOWN)) {
            assertEquals(HardwareCandidateDecision.Host, navigation.decide(key, hidden, false))
        }
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, hidden, false))
        assertEquals(HardwareCandidateDecision.Cancel, navigation.decide(HardwareCandidateKey.CANCEL, hidden, false))
    }

    @Test fun fullKeyboardCanNavigateAllItsEnglishSuggestions() {
        val navigation = HardwareCandidateNavigation()
        val state = CandidateState(pendingEnglishText = "te", associationCandidates = listOf("test", "text", "testing", "tests"))
        val full = snapshot(state, true).copy(associationLimit = Int.MAX_VALUE)
        assertEquals(listOf("te", "test", "text", "testing", "tests"), full.words)
        assertEquals(full.words, full.presentation.words)
        repeat(4) { navigation.decide(HardwareCandidateKey.RIGHT, full, false) }
        assertEquals(4, navigation.selectedIndex(full))
        assertEquals(HardwareCandidateDecision.DefaultInput, navigation.decide(HardwareCandidateKey.SPACE, full, false))
        assertNull(navigation.selectedIndex(full.copy(associationLimit = 3)))
    }

    @Test fun verticalArrowsNavigateAssociationsButPageNativeComposition() {
        val navigation = HardwareCandidateNavigation()
        val suggestions = snapshot(CandidateState(associationCandidates = listOf("世界", "朋友", "大家")))
        assertEquals(HardwareCandidateDecision.Highlight(1), navigation.decide(HardwareCandidateKey.DOWN, suggestions, false))
        assertEquals(HardwareCandidateDecision.Highlight(0), navigation.decide(HardwareCandidateKey.UP, suggestions, false))
        assertEquals(HardwareCandidateDecision.Consume, navigation.decide(HardwareCandidateKey.UP, snapshot(), true))
        assertEquals(HardwareCandidateDecision.Consume, navigation.decide(HardwareCandidateKey.DOWN, snapshot(), true))
        val middlePage = snapshot(composition.copy(hasPrevPage = true, hasNextPage = true))
        assertEquals(HardwareCandidateDecision.Page(-1), navigation.decide(HardwareCandidateKey.UP, middlePage, true))
        assertEquals(HardwareCandidateDecision.Page(1), navigation.decide(HardwareCandidateKey.DOWN, middlePage, true))
        assertNull(navigation.selectedIndex(middlePage))
    }

    @Test fun missingDefaultEngineActionsDoNotInvalidateAnUnchangedCandidateSelection() {
        val navigation = HardwareCandidateNavigation()
        val source = snapshot()
        navigation.decide(HardwareCandidateKey.RIGHT, source, true)
        val explicit = source.copy(state = composition.copy(candidateActions = composition.candidates.indices.map(CandidateAction::engine)))
        assertTrue(source.sameSource(explicit))
        assertEquals(1, navigation.selectedIndex(explicit))
        assertEquals(HardwareCandidateDecision.Confirm(1, false), navigation.decide(HardwareCandidateKey.SPACE, explicit, true))
        val partial = source.copy(state = composition.copy(candidateActions = listOf(CandidateAction.engine(0))))
        assertEquals(1, navigation.selectedIndex(partial))
        val changed = source.copy(state = composition.copy(candidateActions = listOf(CandidateAction.plugin("替换"))))
        assertNull(navigation.selectedIndex(changed))
    }

    @Test fun expandedCandidatesAreNotTruncatedAndKeepTheirGlobalCommitIndices() {
        val navigation = HardwareCandidateNavigation()
        val candidates = (0..17).map { RimeCandidate("候选$it", "编码$it") }
        val source = snapshot(composition.copy(expandedCandidates = candidates, expandedCandidatesLoaded = true)).copy(expanded = true)
        assertEquals(candidates.map { it.text }, source.words)
        assertEquals(candidates.map { it.comment }, source.comments)
        assertEquals(candidates.indices.toList(), source.expandedGlobalIndices)
        repeat(15) { navigation.decide(HardwareCandidateKey.RIGHT, source, true) }
        assertEquals(15, navigation.selectedIndex(source))
        assertEquals(HardwareCandidateDecision.Confirm(15, false), navigation.decide(HardwareCandidateKey.SPACE, source, true))
        assertEquals(15, source.expandedGlobalIndices!![15])
    }

    @Test fun expandedSingleCharacterFilterUsesCodePointsAndOriginalGlobalIndices() {
        val candidates = listOf(RimeCandidate("测试", "ce shi"), RimeCandidate("字", "zi"),
            RimeCandidate("𠀀", "supplementary"), RimeCandidate("词语", "ci yu"), RimeCandidate("词", "ci"))
        val source = snapshot(composition.copy(expandedCandidates = candidates, expandedCandidatesLoaded = true))
            .copy(expanded = true, singleCharOnly = true)
        assertEquals(listOf("字", "𠀀", "词"), source.words)
        assertEquals(listOf("zi", "supplementary", "ci"), source.comments)
        assertEquals(listOf(1, 2, 4), source.expandedGlobalIndices)
        val navigation = HardwareCandidateNavigation()
        assertEquals(HardwareCandidateDecision.Highlight(1), navigation.decide(HardwareCandidateKey.RIGHT, source, true))
        assertEquals(HardwareCandidateDecision.Confirm(1, false), navigation.decide(HardwareCandidateKey.SPACE, source, true))
        assertEquals(2, source.expandedGlobalIndices!![1])
        for (changed in listOf(
            source.copy(singleCharOnly = false),
            source.copy(expanded = false),
            source.copy(state = source.state.copy(expandedCandidatesLoaded = false)),
            source.copy(state = source.state.copy(expandedCandidates = candidates.reversed())),
            source.copy(state = source.state.copy(engineRevision = 5)),
            source.copy(editor = 2),
        )) assertNull(navigation.selectedIndex(changed))
    }

    @Test fun emptyExpandedFilterDoesNotFallBackToUnfilteredCandidatesOrTheEditor() {
        val source = snapshot(composition.copy(expandedCandidates = listOf(RimeCandidate("测试", "ce shi")),
            expandedCandidatesLoaded = true)).copy(expanded = true, singleCharOnly = true)
        assertEquals(emptyList<Int>(), source.expandedGlobalIndices)
        assertTrue(source.words.isEmpty())
        val navigation = HardwareCandidateNavigation()
        assertEquals(HardwareCandidateDecision.Consume, navigation.decide(HardwareCandidateKey.RIGHT, source, true))
        assertEquals(HardwareCandidateDecision.Consume, navigation.decide(HardwareCandidateKey.SPACE, source, true))
    }

    @Test fun expandedAppendKeepsTheSelectedGlobalCandidateAndSpaceConfirmation() {
        val candidates = (0 until 128).map { RimeCandidate((0x4E00 + it).toChar().toString(), "code$it") }
        for (singleChar in listOf(false, true)) {
            val initial = snapshot(composition.copy(expandedCandidates = candidates.take(64), expandedCandidatesLoaded = true))
                .copy(expanded = true, singleCharOnly = singleChar)
            val extended = initial.copy(state = initial.state.copy(expandedCandidates = candidates))
            val navigation = HardwareCandidateNavigation()
            navigation.decide(HardwareCandidateKey.RIGHT, initial, true)
            assertTrue(initial.sameSource(extended))
            assertFalse("Shrinking is not an append", extended.sameSource(initial))
            assertEquals(1, navigation.selectedIndex(extended))
            assertEquals(HardwareCandidateDecision.Confirm(1, false), navigation.decide(HardwareCandidateKey.SPACE, extended, true))
            assertEquals(1, extended.expandedGlobalIndices!![1])
            assertEquals(initial.words[1], extended.words[1])
            assertEquals(initial.comments[1], extended.comments[1])
        }
    }

    @Test fun expandedReorderShrinkOrChangedExistingCommentInvalidatesTheSelection() {
        val candidates = (0 until 64).map { RimeCandidate("词$it", "code$it") }
        val initial = snapshot(composition.copy(expandedCandidates = candidates, expandedCandidatesLoaded = true)).copy(expanded = true)
        for (replacement in listOf(candidates.reversed(), candidates.dropLast(1),
            candidates.toMutableList().also { it[1] = RimeCandidate(it[1].text, "changed") })) {
            val navigation = HardwareCandidateNavigation()
            navigation.decide(HardwareCandidateKey.RIGHT, initial, true)
            val changed = initial.copy(state = initial.state.copy(expandedCandidates = replacement))
            assertFalse(initial.sameSource(changed))
            assertNull(navigation.selectedIndex(changed))
        }
    }

    @Test fun expandedVerticalArrowsPageTheLocalViewportEvenAtNativePageBoundaries() {
        val source = snapshot(composition.copy(hasPrevPage = false, hasNextPage = false,
            expandedCandidates = listOf(RimeCandidate("测", "ce")), expandedCandidatesLoaded = true)).copy(expanded = true)
        val navigation = HardwareCandidateNavigation()
        assertEquals(HardwareCandidateDecision.Page(-1), navigation.decide(HardwareCandidateKey.UP, source, true))
        assertEquals(HardwareCandidateDecision.Page(1), navigation.decide(HardwareCandidateKey.DOWN, source, true))
    }

    @Test fun rapidLetterAndArrowAreDecidedInFifoOrderEvenWhileUiPublicationIsDelayed() = runBlocking {
        val queue = InputCommandQueue(this, Dispatchers.Default)
        val letterStarted = CompletableDeferred<Unit>()
        val releaseLetter = CompletableDeferred<Unit>()
        var engineHasInput = false
        val oldEmptyUi = snapshot(CandidateState())
        var decision: HardwareCandidateDecision? = null
        try {
            queue.submit {
                letterStarted.complete(Unit)
                releaseLetter.await()
                engineHasInput = true
            }
            withTimeout(3000) { letterStarted.await() }
            val arrow = queue.submit {
                decision = HardwareCandidateNavigation().decide(HardwareCandidateKey.RIGHT, oldEmptyUi, engineHasInput)
            }
            assertFalse(arrow.isCompleted)
            releaseLetter.complete(Unit)
            withTimeout(3000) { arrow.join() }
            assertEquals(HardwareCandidateDecision.Consume, decision)
        } finally { queue.close() }
    }

    private val pinyin26 = InputProfile(InputLanguage.CHINESE, InputScheme.PINYIN, InputLayout.QWERTY,
        EngineProfile.Rime("installed-backend"))
    private val twentyCandidates = CandidateState(inputText = "shi", isComposing = true,
        candidates = (1..20).map { "候选$it" }, hasNextPage = true, engineRevision = 7L)

    // 有意与 Windows 不同：空格不跟随工具栏高亮，上屏后的联想永远不被空格选中（见 physicalCandidateDecision）。
    @Test fun physicalSpaceKeepsTheDefaultCandidateAndNeverPicksSuggestions() {
        val source = HardwareCandidateSnapshot(1, pinyin26, composition, false)
        assertEquals(HardwareCandidateDecision.Confirm(0, false),
            physicalCandidateDecision(HardwareCandidateKey.SPACE, source, true))
        val suggestions = HardwareCandidateSnapshot(1, pinyin26, CandidateState(associationCandidates = listOf("世界", "朋友")), false)
        assertEquals(HardwareCandidateDecision.LiteralSpace,
            physicalCandidateDecision(HardwareCandidateKey.SPACE, suggestions, false))
    }

    @Test fun toolbarShowsAnEngineLongerThanTenAsWindows() {
        val navigation = HardwareCandidateNavigation()
        assertEquals(0, navigation.windowStart(twentyCandidates))
        val first = HardwareCandidateSnapshot(1, pinyin26, twentyCandidates, false)
        assertEquals((1..10).map { "候选$it" }, first.words)
        assertTrue(first.presentation.hasNextPage)
        assertFalse(first.presentation.hasPreviousPage)
        assertTrue(navigation.moveWindow(twentyCandidates, 1))
        val second = first.copy(windowStart = navigation.windowStart(twentyCandidates))
        assertEquals((11..20).map { "候选$it" }, second.words)
        assertTrue(second.presentation.hasPreviousPage)
        // The engine still has a next page: the window cannot pass the end of this page by itself.
        assertTrue(second.presentation.hasNextPage)
        assertFalse(navigation.moveWindow(twentyCandidates, 1))
        assertTrue(navigation.moveWindow(twentyCandidates, -1))
        assertEquals(0, navigation.windowStart(twentyCandidates))
        assertFalse(navigation.moveWindow(twentyCandidates, -1))
        // A page that fits needs no window.
        assertFalse(navigation.moveWindow(composition, 1))
        assertEquals(composition.candidates, HardwareCandidateSnapshot(1, pinyin26, composition, false).words)
    }

    @Test fun pagingBackOpensTheLastWindowOnlyForTheSameInput() {
        val navigation = HardwareCandidateNavigation()
        navigation.enginePaged(twentyCandidates, -1)
        // The old page is still on screen until the engine publishes the previous one.
        assertEquals(0, navigation.windowStart(twentyCandidates))
        val previousPage = twentyCandidates.copy(candidates = (21..40).map { "候选$it" }, engineRevision = 8L)
        assertEquals(10, navigation.windowStart(previousPage))
        assertEquals(10, navigation.windowStart(previousPage))
        navigation.enginePaged(previousPage, -1)
        val typedOn = previousPage.copy(inputText = "shij", engineRevision = 9L)
        assertEquals(0, navigation.windowStart(typedOn))
        navigation.enginePaged(previousPage, 1)
        assertEquals(0, navigation.windowStart(previousPage.copy(engineRevision = 10L)))
    }

    // 用户 11-13：平板接物理键盘，工具条只放得下前几个候选，+/- 和上下键一次跳 10 个，后面几个选不到。
    private val tenOnOnePage = CandidateState(inputText = "shi", isComposing = true,
        candidates = (1..10).map { "候选$it" }, engineRevision = 11L)

    @Test fun pagingMovesByWhatTheRowShowsSoNoCandidateIsSkipped() {
        val navigation = HardwareCandidateNavigation()
        navigation.reportShown(tenOnOnePage.candidates, 4)
        assertEquals(4, navigation.step(tenOnOnePage))
        val first = HardwareCandidateSnapshot(1, pinyin26, tenOnOnePage, false, windowStep = navigation.step(tenOnOnePage))
        // The engine has no next page, yet six candidates are still hidden: Down pages instead of doing nothing.
        assertTrue(first.presentation.hasNextPage)
        assertEquals(HardwareCandidateDecision.Page(1), navigation.decide(HardwareCandidateKey.DOWN, first, true))
        assertTrue(navigation.moveWindow(tenOnOnePage, 1))
        assertEquals(4, navigation.windowStart(tenOnOnePage))
        val second = HardwareCandidateSnapshot(1, pinyin26, tenOnOnePage, false, windowStart = 4)
        assertEquals("候选5", second.words[0])
        // The row now draws 候选5… and fits three of them.
        navigation.reportShown(second.words, 3)
        assertTrue(navigation.moveWindow(tenOnOnePage, 1))
        assertEquals(7, navigation.windowStart(tenOnOnePage))
        navigation.reportShown(tenOnOnePage.candidates.drop(7), 3)
        val last = HardwareCandidateSnapshot(1, pinyin26, tenOnOnePage, false, windowStart = 7,
            windowStep = navigation.step(tenOnOnePage))
        assertFalse(last.presentation.hasNextPage)
        assertFalse(navigation.moveWindow(tenOnOnePage, 1))
        // Back returns through the same windows, not by a fixed stride.
        assertTrue(navigation.moveWindow(tenOnOnePage, -1))
        assertEquals(4, navigation.windowStart(tenOnOnePage))
        assertTrue(navigation.moveWindow(tenOnOnePage, -1))
        assertEquals(0, navigation.windowStart(tenOnOnePage))
        assertFalse(navigation.moveWindow(tenOnOnePage, -1))
    }

    @Test fun anUnmeasuredRowKeepsTheNumberedTen() {
        val navigation = HardwareCandidateNavigation()
        assertEquals(HARDWARE_PAGE_SIZE, navigation.step(twentyCandidates))
        // A count measured for other words (the previous keystroke) does not apply.
        navigation.reportShown(listOf("别的", "词"), 1)
        assertEquals(HARDWARE_PAGE_SIZE, navigation.step(twentyCandidates))
        navigation.reportShown(twentyCandidates.candidates.take(9), 5)  // the Samsung-style bar draws nine
        assertEquals(5, navigation.step(twentyCandidates))
        val whole = HardwareCandidateSnapshot(1, pinyin26, tenOnOnePage, false)
        assertFalse(whole.presentation.hasNextPage)
        assertEquals(tenOnOnePage.candidates, whole.words)
    }

    @Test fun walkingOffTheLastNumberedWordSkipsTheWholeWindow() {
        val navigation = HardwareCandidateNavigation()
        navigation.reportShown(twentyCandidates.candidates, 4)
        assertTrue(navigation.moveWindow(twentyCandidates, 1, stride = 10))
        assertEquals(10, navigation.windowStart(twentyCandidates))
        assertTrue(navigation.moveWindow(twentyCandidates, -1))
        assertEquals(0, navigation.windowStart(twentyCandidates))
    }

    @Test fun pagingBackToAPageOfShortRowsOpensAtItsTail() {
        val navigation = HardwareCandidateNavigation()
        navigation.reportShown(twentyCandidates.candidates, 4)
        navigation.enginePaged(twentyCandidates, -1)
        val previousPage = twentyCandidates.copy(candidates = (21..40).map { "候选$it" }, engineRevision = 8L)
        assertEquals(16, navigation.windowStart(previousPage))
        // Earlier windows are still reached, four at a time.
        assertTrue(navigation.moveWindow(previousPage, -1))
        assertEquals(12, navigation.windowStart(previousPage))
    }

    @Test fun windowedSelectionMapsVisiblePositionsToTheEnginePage() {
        val navigation = HardwareCandidateNavigation()
        navigation.moveWindow(twentyCandidates, 1)
        val source = HardwareCandidateSnapshot(1, pinyin26, twentyCandidates, false,
            windowStart = navigation.windowStart(twentyCandidates))
        assertEquals(HardwareCandidateDecision.Confirm(2, false),
            physicalCandidateDecision(HardwareCandidateKey.DIGIT, source, true, digitIndex = 2))
        assertEquals(12, source.windowStart + 2)
        assertEquals("候选13", source.words[2])
        assertEquals(HardwareCandidateDecision.Confirm(0, false),
            physicalCandidateDecision(HardwareCandidateKey.SPACE, source, true))
    }

    /** A spelling the engine has nothing for: Space leaves the letters as typed and never adds a space (user, 10-24). */
    @Test fun spaceWithNoCandidateCommitsTheLettersAsTypedWithoutASpace() {
        val nothing = snapshot(CandidateState(inputText = "xqzk", isComposing = true, engineRevision = 9L))
        assertEquals(HardwareCandidateDecision.CommitTyped,
            physicalCandidateDecision(HardwareCandidateKey.SPACE, nothing, true))
        // The engine could not be read: keep the composition rather than guess.
        assertEquals(HardwareCandidateDecision.Consume,
            physicalCandidateDecision(HardwareCandidateKey.SPACE, nothing, null))
        // The engine has candidates but the expanded single-character filter shows none: not "nothing".
        val filtered = snapshot(composition.copy(expandedCandidates = listOf(RimeCandidate("测试", "ce shi")),
            expandedCandidatesLoaded = true)).copy(expanded = true, singleCharOnly = true)
        assertEquals(HardwareCandidateDecision.Consume,
            physicalCandidateDecision(HardwareCandidateKey.SPACE, filtered, true))
        // With candidates Space still confirms, and English still types a plain space.
        assertEquals(HardwareCandidateDecision.Confirm(0, false),
            physicalCandidateDecision(HardwareCandidateKey.SPACE, snapshot(), true))
        assertEquals(HardwareCandidateDecision.LiteralSpace,
            physicalCandidateDecision(HardwareCandidateKey.SPACE, snapshot(CandidateState(), english = true), false))
    }
}
