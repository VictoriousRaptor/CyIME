package com.kingzcheung.xime.model

import com.kingzcheung.xime.speech.SpeechModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupGuidePlanTest {
    private val mb = 1024L * 1024
    private val gb = 1024 * mb
    private val sizes = mapOf(
        SpeechModelCatalog.SENSEVOICE to 228 * mb, SpeechModelCatalog.PARAFORMER to 226 * mb, SpeechModelCatalog.XASR to 136 * mb,
        SpeechModelCatalog.ZIPFORMER to 133 * mb, SpeechModelCatalog.ZIPFORMER_JA_INT8 to 153 * mb, SpeechModelCatalog.ZIPFORMER_JA to 586 * mb,
        BuiltinModelCatalog.SPEAKER_MODEL to 27 * mb,
        CandidateModelCatalog.NGRAM to 203 * mb, CandidateModelCatalog.NGRAM_SMALL to 105 * mb, CandidateModelCatalog.GRAMMAR to 399 * mb,
        "ochwpro" to 7 * mb,
    )
    private fun facts(tier: DeviceModelTier, free: Long? = 50 * gb, installed: Set<String> = emptySet(),
        inPackage: Set<String> = emptySet(), metered: Boolean = false, japanese: Boolean = false) = SetupFacts(
        tier = tier, memoryBytes = when (tier) { DeviceModelTier.LIGHT -> 3 * gb; DeviceModelTier.STANDARD -> 4 * gb; else -> 8 * gb },
        freeBytes = free, metered = metered, japanese = japanese,
        installed = installed.associateWith { sizes.getValue(it) }, inPackage = inPackage, sizes = sizes)
    private fun SetupPlan.keepIds() = keep.map { it.id }.toSet()
    private fun SetupPlan.removeIds() = remove.map { it.id }.toSet()

    @Test fun lightPhoneOnLiteGetsTheSmallestWorkingSet() {
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.LIGHT), SetupAnswers(VoiceUse.SOMETIMES, t9 = false, caption = false))
        // X-ASR finalizes instead of SenseVoice, so it costs no extra memory even here.
        assertEquals(setOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.XASR, "ochwpro"), plan.keepIds())
        assertEquals(SpeechModelCatalog.SENSEVOICE, plan.voiceMode)
        assertTrue(plan.keep.all { it.source == SetupSource.DOWNLOAD })
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun fullEditionDropsWhatThePersonDoesNotUse() {
        // 瘦身完整版：SenseVoice、声纹、手写解压在手机上，九键词组模型在词库目录里。
        val bundled = setOf(SpeechModelCatalog.SENSEVOICE,
            BuiltinModelCatalog.SPEAKER_MODEL, "ochwpro", CandidateModelCatalog.NGRAM)
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.ENHANCED, installed = bundled, inPackage = bundled),
            SetupAnswers(VoiceUse.SOMETIMES, t9 = false, caption = false))
        assertEquals(setOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.XASR, "ochwpro"), plan.keepIds())
        assertEquals(setOf(BuiltinModelCatalog.SPEAKER_MODEL, CandidateModelCatalog.NGRAM), plan.removeIds())
        assertEquals((27 + 203) * mb, plan.freedBytes)
        assertEquals(136 * mb, plan.downloadBytes) // only X-ASR is not in the package
        assertTrue(plan.notes.any { "安装包" in it })
    }

    @Test fun frequentVoiceOnStrongPhoneStreamsAndRefines() {
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.ENHANCED), SetupAnswers(VoiceUse.OFTEN, t9 = true, caption = false))
        assertEquals(SpeechModelCatalog.TWO_PASS, plan.voiceMode)
        assertTrue(plan.keepIds().containsAll(setOf(SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.SENSEVOICE,
            SpeechModelCatalog.XASR, CandidateModelCatalog.NGRAM)))
    }

    // 2026-11-01 same-path retest: X-ASR finalizing 5.5% vs SenseVoice 8.5% (Doubao 5.9%), and faster.
    @Test fun anyVoiceUseGetsXasrUnlessSpaceIsTight() {
        val sometimes = SetupAnswers(VoiceUse.SOMETIMES, t9 = false, caption = false)
        assertTrue(SpeechModelCatalog.XASR in SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD), sometimes).keepIds())
        // Under 3 GB free: not downloaded, but an X-ASR already on the phone stays.
        assertFalse(SpeechModelCatalog.XASR in SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD, free = 2 * gb), sometimes).keepIds())
        assertTrue(SpeechModelCatalog.XASR in SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD, free = 2 * gb,
            installed = setOf(SpeechModelCatalog.XASR)), sometimes).keepIds())
        // Under 1 GB free it goes, and the reason says why.
        val veryTight = SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD, free = 800 * mb,
            installed = setOf(SpeechModelCatalog.XASR)), sometimes)
        assertTrue(veryTight.remove.single { it.id == SpeechModelCatalog.XASR }.reason.contains("空间"))
    }

    // 11-04 「是否有内存焦虑」：选“有”只留必需的，语音空闲很快卸掉；没选时和以前一模一样。
    @Test fun memorySaverKeepsOnlyWhatIsNeeded() {
        val installed = setOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.XASR,
            CandidateModelCatalog.NGRAM, "ochwpro")
        val answers = SetupAnswers(VoiceUse.OFTEN, t9 = true, caption = false, memorySaver = true)
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.ENHANCED, installed = installed), answers)
        assertEquals(SpeechModelCatalog.SENSEVOICE, plan.voiceMode)
        assertEquals(setOf(SpeechModelCatalog.SENSEVOICE, CandidateModelCatalog.NGRAM_SMALL, "ochwpro"), plan.keepIds())
        assertEquals(setOf(SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.XASR, CandidateModelCatalog.NGRAM), plan.removeIds())
        assertTrue(plan.remove.all { "能省就省" in it.reason })
        assertEquals(SetupGuidePlanner.SAVER_VOICE_IDLE_MINUTES, plan.voiceIdleMinutes)
        assertTrue(plan.notes.any { "能省就省" in it })
        assertTrue(plan.notes.any { "从内存卸掉" in it })
        // 没选“有”：同一台设备照旧推荐，语音释放不改。
        val normal = SetupGuidePlanner.plan(facts(DeviceModelTier.ENHANCED, installed = installed), answers.copy(memorySaver = false))
        assertEquals(SpeechModelCatalog.TWO_PASS, normal.voiceMode)
        assertEquals(CandidateModelCatalog.NGRAM, normal.t9Model)
        assertNull(normal.voiceIdleMinutes)
        assertTrue(normal.notes.none { "能省就省" in it || "从内存卸掉" in it })
    }

    @Test fun memoryExtrasTurnOffOnlyWhatWasPickedAndOnlyWithSaver() {
        val extras = listOf(MemoryExtra("glass", "玻璃效果", "键盘不再半透明", 12.0),
            MemoryExtra("rare_characters", "生僻字", "候选里不再出生僻字", 8.0))
        val f = facts(DeviceModelTier.ENHANCED).copy(memoryExtras = extras)
        // 默认一个不选：答了“有”也不关任何功能。
        val picked = SetupGuidePlanner.suggest(f).copy(memorySaver = true)
        assertTrue(picked.memoryOff.isEmpty())
        assertTrue(SetupGuidePlanner.plan(f, picked).turnOff.isEmpty())
        val plan = SetupGuidePlanner.plan(f, picked.copy(memoryOff = setOf("glass")))
        assertEquals(listOf("glass"), plan.turnOff.map { it.id })
        assertTrue(plan.notes.toString(), plan.notes.any { it.startsWith("关掉玻璃效果，") && "12 MB" in it })
        // 改回“没有”：选过的也不关。
        assertTrue(SetupGuidePlanner.plan(f, picked.copy(memorySaver = false, memoryOff = setOf("glass"))).turnOff.isEmpty())
        // 只关现在开着的（列表里没有的 id 不算）。
        assertTrue(SetupGuidePlanner.plan(f, picked.copy(memoryOff = setOf("key_glow"))).turnOff.isEmpty())
    }

    @Test fun memorySaverAnswerIsRememberedAndOffByDefault() {
        assertFalse(SetupGuidePlanner.suggest(facts(DeviceModelTier.ENHANCED)).memorySaver)
        assertTrue(SetupGuidePlanner.suggest(facts(DeviceModelTier.ENHANCED).copy(memorySaver = true)).memorySaver)
    }

    @Test fun memorySaverWithoutVoiceLeavesVoiceReleaseAlone() {
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.ENHANCED),
            SetupAnswers(VoiceUse.NEVER, t9 = false, caption = false, memorySaver = true))
        assertNull(plan.voiceIdleMinutes)
        assertEquals(setOf("ochwpro"), plan.keepIds())
    }

    @Test fun memorySaverKeepsFullT9ModelWhenOfflineAndSmallIsMissing() {
        val offline = facts(DeviceModelTier.ENHANCED, installed = setOf(CandidateModelCatalog.NGRAM)).copy(online = false)
        val plan = SetupGuidePlanner.plan(offline, SetupAnswers(VoiceUse.NEVER, t9 = true, caption = false, memorySaver = true))
        assertEquals(CandidateModelCatalog.NGRAM, plan.t9Model)
        val online = SetupGuidePlanner.plan(offline.copy(online = true),
            SetupAnswers(VoiceUse.NEVER, t9 = true, caption = false, memorySaver = true))
        assertEquals(CandidateModelCatalog.NGRAM_SMALL, online.t9Model)
    }

    @Test fun frequentVoiceOnLightPhoneStaysOnOneModelAndSaysWhy() {
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.LIGHT), SetupAnswers(VoiceUse.OFTEN, t9 = true, caption = false))
        assertEquals(SpeechModelCatalog.SENSEVOICE, plan.voiceMode)
        assertFalse(SpeechModelCatalog.PARAFORMER in plan.keepIds())
        assertEquals(CandidateModelCatalog.NGRAM_SMALL, plan.t9Model)
        assertTrue(plan.notes.any { "内存 3 GB" in it })
    }

    @Test fun lowSpacePicksSmallerModels() {
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD, free = 1200 * mb),
            SetupAnswers(VoiceUse.OFTEN, t9 = true, caption = false))
        assertEquals(SpeechModelCatalog.SENSEVOICE, plan.voiceMode)
        assertEquals(CandidateModelCatalog.NGRAM_SMALL, plan.t9Model)
        // 不到 3 GB 已经直接挑了省空间的搭配，下载放得下，不会再出“放不下才降档”的那条提示。
        assertTrue(plan.notes.any { "剩余空间不到 3 GB" in it })
    }

    @Test fun noVoiceTurnsVoiceOffAndRemovesEveryVoiceModel() {
        val installed = setOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.XASR, "ochwpro")
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.ENHANCED, installed = installed),
            SetupAnswers(VoiceUse.NEVER, t9 = false, caption = false))
        assertFalse(plan.sttEnabled)
        assertNull(plan.voiceMode)
        assertEquals(setOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.XASR), plan.removeIds())
    }

    @Test fun captionKeepsVoiceModelsEvenWithoutVoiceTyping() {
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD), SetupAnswers(VoiceUse.NEVER, t9 = false, caption = true))
        assertFalse(plan.sttEnabled)
        assertTrue(plan.keepIds().containsAll(setOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.PARAFORMER,
            BuiltinModelCatalog.SPEAKER_MODEL)))
        assertFalse(SpeechModelCatalog.XASR in plan.keepIds())
    }

    @Test fun meteredNetworkWaitsForWifi() {
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD, metered = true),
            SetupAnswers(VoiceUse.SOMETIMES, t9 = false, caption = false))
        assertTrue(plan.downloadBytes > 0)
        assertTrue(plan.notes.any { "Wi-Fi" in it })
    }

    @Test fun japaneseKeepsItsChosenModelOnlyWhenJapaneseIsUsed() {
        val installed = setOf(SpeechModelCatalog.SENSEVOICE, SpeechModelCatalog.ZIPFORMER_JA_INT8)
        val answers = SetupAnswers(VoiceUse.SOMETIMES, t9 = false, caption = false)
        assertTrue(SpeechModelCatalog.ZIPFORMER_JA_INT8 in
            SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD, installed = installed, japanese = true), answers).keepIds())
        assertTrue(SpeechModelCatalog.ZIPFORMER_JA_INT8 in
            SetupGuidePlanner.plan(facts(DeviceModelTier.STANDARD, installed = installed), answers).removeIds())
    }

    @Test fun keepsTheNgramTierAlreadyInstalledAndDropsOldGrammar() {
        val installed = setOf(CandidateModelCatalog.NGRAM_SMALL, CandidateModelCatalog.GRAMMAR)
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.ENHANCED, installed = installed),
            SetupAnswers(VoiceUse.SOMETIMES, t9 = true, caption = false))
        assertEquals(CandidateModelCatalog.NGRAM_SMALL, plan.t9Model)
        assertEquals(setOf(CandidateModelCatalog.GRAMMAR), plan.removeIds())
    }

    @Test fun suggestionFollowsCurrentUse() {
        val f = facts(DeviceModelTier.ENHANCED).copy(streamingVoice = true, usesT9 = true)
        assertEquals(SetupAnswers(VoiceUse.OFTEN, t9 = true, caption = false), SetupGuidePlanner.suggest(f))
        assertEquals(VoiceUse.NEVER, SetupGuidePlanner.suggest(f.copy(sttEnabled = false)).voice)
        assertEquals(VoiceUse.SOMETIMES, SetupGuidePlanner.suggest(f.copy(tier = DeviceModelTier.LIGHT)).voice)
    }

    @Test fun everyItemIsExplainedInPlainWords() {
        val all = SetupGuidePlanner.managedIds.toSet()
        val plan = SetupGuidePlanner.plan(facts(DeviceModelTier.ENHANCED, installed = all), SetupAnswers(VoiceUse.OFTEN, true, true))
        (plan.keep + plan.remove).forEach { item ->
            assertTrue(item.id, item.reason.isNotBlank())
            assertFalse(item.title, Regex("Paraformer|Zipformer|SenseVoice|X-ASR").containsMatchIn(item.title + item.reason))
        }
    }

    @Test fun tabletIsCalledATablet() {
        // 平板上不能写「这台手机」（10-31 平板新装走查）。各种机型、空间、用法都过一遍，所有给人看的文字只说平板。
        val all = SetupGuidePlanner.managedIds.toSet()
        for (tier in DeviceModelTier.entries) for (free in listOf(50 * gb, 2 * gb, 500 * mb)) for (voice in VoiceUse.entries) {
            val bundled = setOf(SpeechModelCatalog.SENSEVOICE, CandidateModelCatalog.NGRAM)
            val plan = SetupGuidePlanner.plan(facts(tier, free = free, installed = all - CandidateModelCatalog.NGRAM, inPackage = bundled)
                .copy(device = "平板"), SetupAnswers(voice, t9 = true, caption = false))
            val text = (plan.keep + plan.remove).flatMap { listOf(it.title, it.reason) } + plan.notes
            text.forEach { assertFalse(it, "手机" in it) }
        }
        val light = SetupGuidePlanner.plan(facts(DeviceModelTier.LIGHT).copy(device = "平板"),
            SetupAnswers(VoiceUse.OFTEN, t9 = false, caption = false))
        assertTrue(light.notes.toString(), light.notes.any { "这台平板" in it })
    }

    private val doublePinyins = listOf("double_pinyin_flypy" to "小鹤双拼", "double_pinyin_shoudao" to "首道双拼",
        "double_pinyin_ziranma" to "自然码", "double_pinyin_znabc" to "智能ABC双拼", "double_pinyin_jiajia" to "拼音加加",
        "double_pinyin_microsoft" to "微软双拼").map { (id, name) ->
            DoublePinyinChoice(id, name, "xx'xx", lettersOnly = id == "double_pinyin_microsoft") }
    /** 新装：全拼 26 键，六种内置双拼都在方案栏里，没开模糊音，没有字幕图标。 */
    private fun typingFacts() = facts(DeviceModelTier.ENHANCED).copy(doublePinyinChoices = doublePinyins,
        shownDoublePinyin = doublePinyins.map { it.id }.toSet())

    @Test fun keepingEveryAnswerChangesNoInputSetting() {
        val f = typingFacts().copy(fuzzy = setOf("z_zh"), usesCaption = true)
        val input = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f)).input
        assertNull(input.switchTo)
        assertTrue(input.fuzzyOn.isEmpty() && input.fuzzyOff.isEmpty())
        assertNull(input.captionIcon)
        assertFalse(input.downloadWubi)
        assertEquals(listOf("打字方式还是全拼，不变。"), input.lines)
        // 全拼的人方案栏里用不到双拼：推荐藏起来，页面上是一个默认打开的开关。
        assertEquals(doublePinyins.map { it.id }, input.hideDoublePinyin)
    }

    @Test fun choosingADoublePinyinSwitchesToItAndHidesTheOthers() {
        val f = typingFacts()
        val input = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.DOUBLE_PINYIN,
            doublePinyin = "double_pinyin_ziranma")).input
        assertEquals("double_pinyin_ziranma", input.switchTo)
        assertEquals("double_pinyin_ziranma", input.showDoublePinyin)
        assertEquals(doublePinyins.map { it.id } - "double_pinyin_ziranma", input.hideDoublePinyin)
        assertTrue(input.lines.toString(), "中文 26 键改用自然码。" in input.lines)
        assertTrue(input.hideLine!!.startsWith("方案栏里藏起用不到的 5 种双拼：小鹤双拼、首道双拼、智能ABC双拼"))
    }

    @Test fun onNineKeysTheSchemeIsShownButNotSwitched() {
        val f = typingFacts().copy(keyboard = KeyboardWay.T9, usesT9 = true)
        val input = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.WUBI)).input
        assertNull(input.switchTo)
        assertTrue(input.useWubi && input.downloadWubi)
        assertTrue(input.lines.toString(), input.lines.any { it.startsWith("方案栏里打开五笔86") })
        // 已经在用五笔（如五笔98）就不装、不切。
        val wubi = typingFacts().copy(typing = TypingWay.WUBI)
        val same = SetupGuidePlanner.plan(wubi, SetupGuidePlanner.suggest(wubi)).input
        assertFalse(same.useWubi || same.downloadWubi)
        assertNull(same.switchTo)
    }

    @Test fun wubiDefaultsTo86AndCanPick98() {
        val f = typingFacts()
        // 不选就是 86（用户 11-12：五笔默认 86）。
        assertEquals(SetupGuidePlanner.WUBI, SetupGuidePlanner.suggest(f).wubi)
        val input = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.WUBI,
            wubi = SetupGuidePlanner.WUBI98)).input
        assertEquals(SetupGuidePlanner.WUBI98, input.wubiId)
        assertEquals(SetupGuidePlanner.WUBI98, input.switchTo)
        assertTrue(input.useWubi && input.downloadWubi)
        assertTrue(input.lines.toString(), input.lines.any { it.startsWith("下载五笔98方案") })
        assertTrue(input.lines.toString(), "中文 26 键改用五笔98。" in input.lines)
        // 98 已经装着：不下载，只切过去；86 装没装不影响。
        val installed = SetupGuidePlanner.plan(f.copy(wubi98Installed = true), SetupGuidePlanner.suggest(f).copy(
            typing = TypingWay.WUBI, wubi = SetupGuidePlanner.WUBI98)).input
        assertFalse(installed.downloadWubi)
        assertEquals(SetupGuidePlanner.WUBI98, installed.switchTo)
    }

    @Test fun fuzzyOnlyChangesTheGroupsWhoseAnswerChanged() {
        // 现在开着 z/zh 和 r/l：选平翘舌＋n/l → 只加 n/l，不把 c/ch、s/sh 也补上，r/l 不在向导里，不动。
        val f = typingFacts().copy(fuzzy = setOf("z_zh", "r_l"))
        val more = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(fuzzy = setOf(FuzzyHabit.RETROFLEX, FuzzyHabit.NL))).input
        assertEquals(setOf("n_l"), more.fuzzyOn)
        assertTrue(more.fuzzyOff.isEmpty())
        val none = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(fuzzy = emptySet())).input
        assertEquals(setOf("z_zh"), none.fuzzyOff)
        // 双拼、五笔不用模糊音：不改。
        val double = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.DOUBLE_PINYIN, fuzzy = emptySet())).input
        assertTrue(double.fuzzyOn.isEmpty() && double.fuzzyOff.isEmpty())
    }

    @Test fun captionAnswerAddsOrRemovesTheHomeScreenIcon() {
        val f = typingFacts()
        assertEquals(true, SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(caption = true)).input.captionIcon)
        val on = f.copy(usesCaption = true)
        assertEquals(false, SetupGuidePlanner.plan(on, SetupGuidePlanner.suggest(on).copy(caption = false)).input.captionIcon)
        // 老人包固定有图标。
        val elder = on.copy(captionIconChangeable = false)
        assertNull(SetupGuidePlanner.plan(elder, SetupGuidePlanner.suggest(elder).copy(caption = false)).input.captionIcon)
    }

    @Test fun otherInputWaysAreKeptAsTheyAre() {
        val f = typingFacts().copy(typing = TypingWay.KEEP, typingName = "仓颉", keyboard = KeyboardWay.KEEP)
        assertEquals(TypingWay.KEEP, SetupGuidePlanner.suggest(f).typing)
        val input = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f)).input
        assertNull(input.switchTo)
        assertTrue(input.hideDoublePinyin.isEmpty())
        assertTrue(input.lines.isEmpty())
    }

    @Test fun keyboardQuestionStartsAtTheCurrentKeyboardAndChangesNothingWhenKept() {
        val f = typingFacts()
        assertEquals(KeyboardWay.QWERTY, SetupGuidePlanner.suggest(f).keyboard)
        assertEquals(listOf(KeyboardWay.QWERTY, KeyboardWay.K14, KeyboardWay.T9),
            SetupGuidePlanner.keyboards(f, SetupGuidePlanner.suggest(f)))
        // QWJRTK 等字母排列也算 26 键：回答不变就不切，不会被换回 QWERTY。
        val input = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f)).input
        assertNull(input.switchTo)
        assertTrue(input.enable.isEmpty())
        // 五笔只问打法，不问键盘。
        assertTrue(SetupGuidePlanner.keyboards(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.WUBI)).isEmpty())
    }

    @Test fun choosingFourteenKeysTurnsTheLayoutOnAndSwitchesToIt() {
        val f = typingFacts()
        val full = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(keyboard = KeyboardWay.K14)).input
        assertEquals("pinyin_14jian", full.switchTo)
        assertEquals(listOf("pinyin_14jian"), full.enable)
        assertTrue(full.lines.toString(), "中文换成 14 键，用全拼打。" in full.lines)
        // 双拼 14 键：打开双拼和中文14键，14 键版本随两者自动出现，不单独打开。
        val double = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.DOUBLE_PINYIN,
            keyboard = KeyboardWay.K14)).input
        assertEquals("double_pinyin_flypy_14jian", double.switchTo)
        assertEquals("double_pinyin_flypy", double.showDoublePinyin)
        assertEquals(listOf("pinyin_14jian"), double.enable)
        assertTrue(double.lines.toString(), "中文换成 14 键，用小鹤双拼打。" in double.lines)
    }

    @Test fun nineKeysForDoublePinyinUsesItsNineKeyVersion() {
        val f = typingFacts()
        val input = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.DOUBLE_PINYIN,
            doublePinyin = "double_pinyin_ziranma", keyboard = KeyboardWay.T9)).input
        assertEquals("double_pinyin_ziranma_t9", input.switchTo)
        assertEquals(listOf("double_pinyin_ziranma_t9"), input.enable)
        assertTrue(input.lines.toString(), "中文换成九键，用自然码打。" in input.lines)
    }

    @Test fun fullPinyinOnNineKeysKeepsTheNineKeyModelWithoutAskingAgain() {
        val f = typingFacts().copy(installed = mapOf(CandidateModelCatalog.NGRAM to 203 * mb))
        val plan = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(keyboard = KeyboardWay.T9, t9 = false))
        assertEquals("t9_pinyin", plan.input.switchTo)
        assertEquals(CandidateModelCatalog.NGRAM, plan.t9Model)
        // 双拼九键不用九键整句组词模型：照“用九键打字吗”的回答。
        val double = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.DOUBLE_PINYIN,
            keyboard = KeyboardWay.T9, t9 = false))
        assertNull(double.t9Model)
    }

    @Test fun microsoftDoublePinyinOnlyOffersTwentySixKeys() {
        val f = typingFacts()
        val answers = SetupGuidePlanner.suggest(f).copy(typing = TypingWay.DOUBLE_PINYIN, doublePinyin = "double_pinyin_microsoft",
            keyboard = KeyboardWay.K14)
        assertEquals(listOf(KeyboardWay.QWERTY), SetupGuidePlanner.keyboards(f, answers))
        assertEquals(KeyboardWay.QWERTY, SetupGuidePlanner.keyboardOf(f, answers))
        val input = SetupGuidePlanner.plan(f, answers).input
        assertEquals("double_pinyin_microsoft", input.switchTo)
        assertTrue(input.enable.isEmpty())
    }

    @Test fun otherKeyboardsCanBeKept() {
        // 现在在 17 键：多一个“保持现在的17键”，默认选它；选双拼时只在方案栏打开，不换键盘。
        val f = typingFacts().copy(keyboard = KeyboardWay.KEEP, keyboardName = "17键")
        val answers = SetupGuidePlanner.suggest(f)
        assertEquals(KeyboardWay.KEEP, answers.keyboard)
        assertEquals(KeyboardWay.KEEP, SetupGuidePlanner.keyboards(f, answers).last())
        val input = SetupGuidePlanner.plan(f, answers.copy(typing = TypingWay.DOUBLE_PINYIN)).input
        assertNull(input.switchTo)
        assertTrue(input.lines.toString(), input.lines.any { it.startsWith("方案栏里打开小鹤双拼") })
        // 明确选了 26 键就切过去。
        assertEquals("rime_ice", SetupGuidePlanner.plan(f, answers.copy(keyboard = KeyboardWay.QWERTY)).input.switchTo)
    }

    @Test fun switchingToDoublePinyinTurnsOnKeyHintsOnlyForNewcomers() {
        val f = typingFacts().copy(canEnableDoublePinyinKeyHints = true)
        val toDouble = SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f).copy(typing = TypingWay.DOUBLE_PINYIN)).input
        assertTrue(toDouble.keyHintsOn)
        assertTrue(toDouble.lines.toString(), toDouble.lines.any { it.startsWith("打开双拼键位提示") })
        val corners = f.copy(canEnableDoublePinyinKeyHints = false)
        assertFalse(SetupGuidePlanner.plan(corners, SetupGuidePlanner.suggest(corners)
            .copy(typing = TypingWay.DOUBLE_PINYIN)).input.keyHintsOn)
        // 本来就用双拼（换一种也算）、已经开着、或不选双拼：不动。
        val already = f.copy(typing = TypingWay.DOUBLE_PINYIN, doublePinyin = "double_pinyin_flypy")
        assertFalse(SetupGuidePlanner.plan(already, SetupGuidePlanner.suggest(already).copy(doublePinyin = "double_pinyin_ziranma")).input.keyHintsOn)
        val on = f.copy(doublePinyinKeyHints = true)
        assertFalse(SetupGuidePlanner.plan(on, SetupGuidePlanner.suggest(on).copy(typing = TypingWay.DOUBLE_PINYIN)).input.keyHintsOn)
        assertFalse(SetupGuidePlanner.plan(f, SetupGuidePlanner.suggest(f)).input.keyHintsOn)
    }

    @Test fun sizeLineSaysWhatGetsDownloadedInsteadOfLookingLikeTidyingGrowsIt() {
        // 10-31 走查：「现在占 7 MB，整理后 270 MB」读起来像整理反而变大。
        assertEquals("模型现在占 7 MB。按推荐要下载 263 MB，设好后共占 270 MB。",
            SetupGuidePlanner.sizeSummary(now = 7 * mb, download = 263 * mb, unpack = 0, removed = 0))
        assertEquals("模型现在占 600 MB。按推荐要删掉 360 MB，设好后共占 240 MB，比现在少 360 MB。",
            SetupGuidePlanner.sizeSummary(now = 600 * mb, download = 0, unpack = 0, removed = 360 * mb))
        assertEquals("模型现在占 7 MB。按推荐要下载 19 MB、从安装包取出 228 MB，设好后共占 254 MB。",
            SetupGuidePlanner.sizeSummary(now = 7 * mb, download = 19 * mb, unpack = 228 * mb, removed = 0))
        assertEquals("模型现在占 7 MB，不用下载，也不用删。", SetupGuidePlanner.sizeSummary(7 * mb, 0, 0, 0))
    }

    @Test fun donePageNamesTheModelsBeingDownloaded() {
        assertEquals("语音、手写模型", SetupGuidePlanner.kindsText(listOf(SpeechModelCatalog.SENSEVOICE,
            SpeechModelCatalog.PARAFORMER, SetupGuidePlanner.HANDWRITING)))
        assertEquals("九键组词模型", SetupGuidePlanner.kindsText(listOf(CandidateModelCatalog.NGRAM)))
        assertEquals("模型", SetupGuidePlanner.kindsText(listOf("unknown")))
    }
}
