package com.kingzcheung.xime.settings

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * 纠错开关和强度每一种组合的实测效果（用户 11-16「这个面板必须要精确的计算出最终的效果」）。
 *
 * 表在 `assets/typo_effects.json`，是同源引擎逐键打出来的：每种错法各造一批（常用词、日常短句），看想打的词排第几；
 * 另拿一批没打错的少见词句，和完全不纠错时比，看第一个候选被改掉多少。26 键全拼的每一种设置都单独测过一遍
 * （邻键纠错开着时 5 档强度 × 顺序、多键、漏键各开或关，邻键纠错关着时再 8 种，见 [stateKey]）；九键测了三档强度。
 * 设置页只照表显示，不估算、不相加：表里没有的组合不显示数字。纠错的门槛或词库改了以后要重测再换这张表。
 */
object TypoEffects {
    /** 目标词的名次占比（百分数）：[wordFirst] 词排第一，[wordTop3] 词进前三（停一下以后），句子同理。 */
    @Serializable
    data class Rate(val wordFirst: Double = 0.0, val wordTop3: Double = 0.0, val sentenceFirst: Double = 0.0, val sentenceTop3: Double = 0.0)

    /** [cleanBroken]：没打错的 [Board.cleanCases] 条里，不纠错时第一个候选是对的、这种设置下被改成别的词的条数。[kinds] 的键见 [Kind.id]。 */
    @Serializable
    data class State(val cleanBroken: Int = 0, val kinds: Map<String, Rate> = emptyMap())

    @Serializable
    data class Board(val cleanCases: Int = 0, val states: Map<String, State> = emptyMap())

    @Serializable
    data class Table(val version: Int = 1, val measuredAt: String = "", val fullPinyin: Board = Board(), val nineKey: Board = Board())

    /** 测过的错法。九键只有 [NEIGHBOR]、[OMISSION]、[SWAP] 三种。 */
    enum class Kind(val id: String, val title: String) {
        NEIGHBOR("sub", "按到旁边的键"),
        DOUBLED("double", "同一个键多按一下"),
        EXTRA("extra", "多碰到旁边的键"),
        OMISSION("omit", "少打一个字母"),
        SWAP("swapin", "两个字母打反"),
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Table? = runCatching { json.decodeFromString(Table.serializer(), text) }.getOrNull()

    private const val ASSET = "typo_effects.json"
    @Volatile private var cached: Table? = null

    /** 随安装包的表；读不到为 null（设置页就不显示数字）。 */
    fun table(context: Context): Table? = cached ?: runCatching {
        context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
    }.getOrNull()?.also { cached = it }

    /** 邻键纠错关着：键盘不把键位交给引擎，强度不起作用。 */
    const val NO_NEIGHBOR = "nomap"

    /**
     * 26 键全拼一种设置在表里的名字：邻键纠错开着是 `lead:<强度>`，关着是 [NO_NEIGHBOR]；后面按顺序、多键、漏键的次序，
     * 关着的各接 `-swap`、`-extra`、`-omit`（如 `lead:careful-swap-omit`）。
     */
    fun stateKey(neighbor: Boolean, lead: TypoStrength.Lead, typos: Set<SpellingPatches.Typo>): String =
        (if (neighbor) "lead:${lead.id}" else NO_NEIGHBOR) +
            (if (SpellingPatches.Typo.TRANSPOSITION in typos) "" else "-swap") +
            (if (SpellingPatches.Typo.EXTRA in typos) "" else "-extra") +
            (if (SpellingPatches.Typo.OMISSION in typos) "" else "-omit")

    fun digitState(digit: TypoStrength.Digit) = "digit:${digit.id}"

    private fun percent(value: Double) = "${value.roundToInt()}%"

    /** 「词 56% 排第一、96% 进前三，句 82% 排第一、94% 进前三」；[first] 为假时只写进前三（停一下才补出来的那几种）。 */
    fun rateText(rate: Rate, first: Boolean): String =
        if (first) "词 ${percent(rate.wordFirst)} 排第一、${percent(rate.wordTop3)} 进前三，句 ${percent(rate.sentenceFirst)} 排第一、${percent(rate.sentenceTop3)} 进前三"
        else "词 ${percent(rate.wordTop3)}、句 ${percent(rate.sentenceTop3)} 进前三"

    /** 「没打错的 5638 条少见词句里，12 条的第一个候选被改掉」。 */
    fun cleanText(board: Board, state: State): String =
        if (state.cleanBroken == 0) "没打错的 ${board.cleanCases} 条少见词句，第一个候选都没被改掉。"
        else "没打错的 ${board.cleanCases} 条少见词句里，${state.cleanBroken} 条的第一个候选被改掉。"

    /** 邻键强度某一档下面那句（别的开关按现在的）；表里没有这种组合为 null。 */
    fun leadText(board: Board, lead: TypoStrength.Lead, typos: Set<SpellingPatches.Typo>): String? {
        val state = board.states[stateKey(true, lead, typos)] ?: return null
        val rate = state.kinds[Kind.NEIGHBOR.id] ?: return null
        return "实测：按到旁边一个键，${rateText(rate, first = true)}。${cleanText(board, state)}"
    }

    /** 顺序、多键、漏键开关下面那句：别的设置按现在的，这一个开着和关掉各是多少（都是进前三，停一下以后）。 */
    fun typoText(board: Board, typo: SpellingPatches.Typo, neighbor: Boolean, lead: TypoStrength.Lead, typos: Set<SpellingPatches.Typo>): String? {
        val kinds = when (typo) {
            SpellingPatches.Typo.TRANSPOSITION -> listOf(Kind.SWAP)
            SpellingPatches.Typo.EXTRA -> listOf(Kind.DOUBLED, Kind.EXTRA)
            SpellingPatches.Typo.OMISSION -> listOf(Kind.OMISSION)
        }
        val on = board.states[stateKey(neighbor, lead, typos + typo)] ?: return null
        val off = board.states[stateKey(neighbor, lead, typos - typo)] ?: return null
        val parts = kinds.mapNotNull { kind ->
            val a = on.kinds[kind.id] ?: return@mapNotNull null
            val b = off.kinds[kind.id] ?: return@mapNotNull null
            "${kind.title}，开着${rateText(a, first = false)}，关掉 ${percent(b.wordTop3)}、${percent(b.sentenceTop3)}"
        }
        return if (parts.isEmpty()) null else "实测：" + parts.joinToString("；") + "。"
    }

    /** 九键强度某一档下面那句（都是停一下以后进前三）。 */
    fun digitText(board: Board, digit: TypoStrength.Digit): String? {
        val state = board.states[digitState(digit)] ?: return null
        val parts = listOf(Kind.NEIGHBOR to "按到上下左右的键", Kind.OMISSION to "少按一个", Kind.SWAP to "两个按反").mapNotNull { (kind, title) ->
            state.kinds[kind.id]?.let { "$title ${rateText(it, first = false)}" }
        }
        if (parts.isEmpty()) return null
        val clean = if (state.cleanBroken == 0) "没打错的 ${board.cleanCases} 条，前三个候选都没变。"
            else "没打错的 ${board.cleanCases} 条里，${state.cleanBroken} 条的前三个候选变了。"
        return "实测(停一下以后)：" + parts.joinToString("；") + "。" + clean
    }

    /**
     * 「按现在的设置」那几行：现在这种组合测出来的数，每种错法一行，最后一行是没打错被改掉多少。
     * 表里没有这种组合、或缺哪种错法，就返回空表（不拿别的组合顶替）。
     */
    fun summary(board: Board, neighbor: Boolean, lead: TypoStrength.Lead, typos: Set<SpellingPatches.Typo>): List<String> {
        val state = board.states[stateKey(neighbor, lead, typos)] ?: return emptyList()
        val lines = Kind.entries.map { kind ->
            val rate = state.kinds[kind.id] ?: return emptyList()
            "${kind.title}：${rateText(rate, first = kind == Kind.NEIGHBOR)}"
        }
        return lines + cleanText(board, state)
    }
}
