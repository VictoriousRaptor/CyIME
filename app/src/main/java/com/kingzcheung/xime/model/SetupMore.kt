package com.kingzcheung.xime.model

import com.kingzcheung.xime.ui.keyboard.CornerInputChoice
import com.kingzcheung.xime.ui.keyboard.SpaceHoldAction
import kotlin.math.abs

/**
 * 「更多配置」：按设备推荐配置之后可以跳过的一页，问大家最常问的几项（角标滑动还是长按、上下滑方向、滑行输入、
 * 候选栏取消按钮、长按空格、删除键、字大小、声音振动、玻璃效果、语音标点；前四项用户 11-12 加，说问的人最多）。每题先选着现在的值，只改用户点过的项，不动默认值。读写设置在 [SetupGuide.readMore]、[SetupGuide.applyMore]。
 */
enum class TextSizeChoice(val title: String, val candidateSp: Int, val keyScale: Float) {
    STANDARD("标准", 19, 1.2f),
    LARGE("大字", 22, 1.4f),
    /** 自己调过、和上面两档都不一样：只用来显示「保持现在的」，不会写进设置。 */
    CUSTOM("保持现在的", 0, 0f),
}

enum class FeedbackChoice(val title: String, val sound: Boolean, val vibration: Boolean) {
    SOUND_AND_VIBRATION("有声音也振动", true, true),
    VIBRATION_ONLY("只振动", false, true),
    /** 只在现在就是这样时列出。 */
    SOUND_ONLY("只有声音", true, false),
    NONE("都关掉", false, false),
}

/** 26 键滑行：英文、中文两个开关（设置页「滑行输入」里的前两行）合成一题。 */
enum class GlideChoice(val title: String, val english: Boolean, val chinese: Boolean) {
    OFF("不用滑行", false, false),
    ENGLISH("只英文滑行", true, false),
    /** 只在现在就是这样时列出。 */
    CHINESE("只中文滑行", false, true),
    BOTH("中文、英文都滑行", true, true);

    companion object {
        fun of(english: Boolean, chinese: Boolean): GlideChoice = entries.first { it.english == english && it.chinese == chinese }
    }
}

data class SetupMoreChoices(
    val spaceHold: SpaceHoldAction,
    val deleteSkipsAssociation: Boolean,
    val clearBeforeCursorOnly: Boolean,
    val textSize: TextSizeChoice,
    val feedback: FeedbackChoice,
    val glass: Boolean,
    val autoPunctuation: Boolean,
    /** 按键角标：滑动、滑动＋字母气泡、长按（设置页「按键角标的符号和数字」三张卡）。 */
    val corner: CornerInputChoice = CornerInputChoice.SWIPE,
    /** 「反转上下滑方向」：默认关是下滑出上角标、上滑出下角标。 */
    val reverseSwipe: Boolean = false,
    val glide: GlideChoice = GlideChoice.OFF,
    /** 「候选栏取消按钮」：打字时候选栏最左边的左箭头，默认开。 */
    val cancelButton: Boolean = true,
)

/** 现在的设置，以及决定哪些题要问、怎么说的情况。 */
data class SetupMoreFacts(
    val current: SetupMoreChoices,
    val sttEnabled: Boolean = true,
    /** 智能联想关着就没有联想词，不问删除键第一下做什么。 */
    val predictionEnabled: Boolean = true,
    /** 反转了上下滑：上滑清空、下滑撤销。 */
    val reverseSwipe: Boolean = false,
    /** 现在的候选字号和按键字号（百分比），字大小是「保持现在的」时写出来。 */
    val candidateSp: Int = TextSizeChoice.STANDARD.candidateSp,
    val keyScalePercent: Int = 120,
    /** 「性能与省电」里实测的玻璃效果代价；没测过为 null，不写估计值。 */
    val glassCost: String? = null,
    /** 删除键单独反向开着：清空、撤回的方向和角标相反。 */
    val reverseDeleteSwipe: Boolean = false,
    /** 滑行被「键盘面板滑动光标」锁住时那句锁的说明（同设置页）；没锁为 null。 */
    val glideLock: String? = null,
)

object SetupMore {
    /** 滑行列哪几项：「只中文滑行」只在现在就是它时列出。 */
    fun glideChoices(current: GlideChoice): List<GlideChoice> =
        GlideChoice.entries.filter { it != GlideChoice.CHINESE || current == GlideChoice.CHINESE }

    /** 开着 26 键滑行时字母键的滑动都给了滑行，角标只能长按（同 [com.kingzcheung.xime.ui.keyboard.InputGestureLocks.glideCornerLock]）。 */
    fun cornerLockedByGlide(glide: GlideChoice): Boolean = glide != GlideChoice.OFF

    /** 实际生效的角标方式：开了滑行就是长按，写设置时也照这个算，不另写一个和键盘行为对不上的值。 */
    fun effectiveCorner(choices: SetupMoreChoices): CornerInputChoice =
        if (cornerLockedByGlide(choices.glide)) CornerInputChoice.LONG_PRESS else choices.corner

    fun textSizeOf(candidateSp: Int, keyScale: Float): TextSizeChoice = TextSizeChoice.entries.firstOrNull {
        it != TextSizeChoice.CUSTOM && it.candidateSp == candidateSp && abs(it.keyScale - keyScale) < 0.01f
    } ?: TextSizeChoice.CUSTOM

    fun feedbackOf(sound: Boolean, vibration: Boolean): FeedbackChoice =
        FeedbackChoice.entries.first { it.sound == sound && it.vibration == vibration }

    /** 声音振动列哪几项：「只有声音」只在现在就是它时列出。 */
    fun feedbackChoices(current: FeedbackChoice): List<FeedbackChoice> =
        FeedbackChoice.entries.filter { it != FeedbackChoice.SOUND_ONLY || current == FeedbackChoice.SOUND_ONLY }

    /** 字大小列哪几项：自己调过的多一项「保持现在的」。 */
    fun textSizeChoices(current: TextSizeChoice): List<TextSizeChoice> =
        TextSizeChoice.entries.filter { it != TextSizeChoice.CUSTOM || current == TextSizeChoice.CUSTOM }

    /** 从 [from] 改到 [to] 要改的项，白话一句一项；没改的不列。完成页照这个写，也只改这些。 */
    fun changes(from: SetupMoreChoices, to: SetupMoreChoices): List<String> = buildList {
        if (to.glide != from.glide) add(when (to.glide) {
            GlideChoice.OFF -> "关掉了滑行输入。"
            else -> "滑行输入改成「${to.glide.title}」。"
        })
        val corner = effectiveCorner(to)
        if (corner != effectiveCorner(from)) add(
            if (cornerLockedByGlide(to.glide)) "按键角标改成「长按输入」(开着滑行，字母键的滑动用来滑词)。"
            else "按键角标改成「${corner.label}」。")
        if (to.reverseSwipe != from.reverseSwipe)
            add(if (to.reverseSwipe) "打开了「反转上下滑方向」：上滑出上角标、下滑出下角标，删除键也反过来。"
                else "关掉了「反转上下滑方向」：下滑出上角标、上滑出下角标。")
        if (to.cancelButton != from.cancelButton)
            add(if (to.cancelButton) "打开了「候选栏取消按钮」：打字时候选栏最左边有左箭头，点它取消输入。"
                else "关掉了「候选栏取消按钮」：候选词从最左边开始。")
        if (to.spaceHold != from.spaceHold) add("长按空格改成「${to.spaceHold.label}」。")
        if (to.deleteSkipsAssociation != from.deleteSkipsAssociation)
            add(if (to.deleteSkipsAssociation) "打开了「退格直接删字」：有联想词时第一下删除键就删字。"
                else "关掉了「退格直接删字」：有联想词时第一下删除键先收起联想。")
        if (to.clearBeforeCursorOnly != from.clearBeforeCursorOnly)
            add(if (to.clearBeforeCursorOnly) "打开了「只清空光标前的内容」。" else "删除键清空改回清整个输入框。")
        if (to.textSize != from.textSize && to.textSize != TextSizeChoice.CUSTOM)
            add("字大小改成${to.textSize.title}：候选字 ${to.textSize.candidateSp}，按键字号 ${(to.textSize.keyScale * 100).toInt()}%。")
        if (to.feedback != from.feedback) add("按键声音和振动改成「${to.feedback.title}」。")
        if (to.glass != from.glass) add(if (to.glass) "打开了「玻璃效果」。" else "关掉了「玻璃效果」。")
        if (to.autoPunctuation != from.autoPunctuation)
            add(if (to.autoPunctuation) "语音输入打开了「自动加标点」。" else "语音输入关掉了「自动加标点」。")
    }
}
