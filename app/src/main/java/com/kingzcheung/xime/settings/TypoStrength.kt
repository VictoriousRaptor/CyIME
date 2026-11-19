package com.kingzcheung.xime.settings

import android.content.Context

/**
 * 纠错强度（用户 11-16「我需要这样一个面板 自由调整纠错的强弱」；同一天转来的评论「不要把纠错的词组放到前面」）。
 * 开关还是原来那几个（[SpellingPatches.Typo]、邻键纠错），这里只管开着时纠得多狠：
 * - [Lead]：26 键全拼、双拼的邻键纠错，纠正出来的词句什么时候排到照打出来的读法前面；
 * - [Digit]：九键停一下后的数字纠错，纠正出来的词句要比现在的第一条常见多少才给。
 * 多键、漏键、顺序三种调门槛实测几乎不变（11-16 云端），只有开关，没有强度。
 * 选的档位写进方案补丁（[SpellingPatches.sync]），和出厂方案里的值一样时不写。每档的实测效果见 [TypoEffects]。
 */
object TypoStrength {
    /**
     * [bias] 写进方案的 t9/correction_lead_bias（自然对数）：加在「纠正的词要比照打的词常见 2000 倍」「纠正的句子每个纠正的字
     * 一万倍」「最后一个字 20 倍」这三道门槛上；2.3 就是各再乘 10。只作用在打字时的候选上，停一下后的漏键补查不受影响。
     */
    enum class Lead(val id: String, val title: String, val bias: Double) {
        /** 照打出来的字母读得通时，纠正的从不排第一（评论要的）；读不通时纠正的本来就是唯一的读法，仍排第一。 */
        NEVER("never", "不抢第一", 99.0),
        CAREFUL_MORE("careful_more", "很保守", 4.6),
        CAREFUL("careful", "保守", 2.3),
        STANDARD("standard", "标准", 0.0),
        EAGER("eager", "积极", -2.3);

        companion object {
            fun of(id: String?): Lead = entries.firstOrNull { it.id == id } ?: LEAD_DEFAULT
        }
    }

    /** [margin] 写进九键方案的 t9/digit_edit_margin（自然对数）：越大越少给，没打错时也越不会多出一条拼凑的句子。 */
    enum class Digit(val id: String, val title: String, val margin: Double) {
        STRICT("strict", "少给", 6.0),
        STANDARD("standard", "标准", 3.0),
        LOOSE("loose", "多给", 1.5);

        companion object {
            fun of(id: String?): Digit = entries.firstOrNull { it.id == id } ?: DIGIT_DEFAULT
        }
    }

    /** 出厂方案里写的就是这两档（tasks-chinese.gradle.kts 的 correction_lead_bias、t9_pinyin.schema.yaml 的 digit_edit_margin）。 */
    val LEAD_DEFAULT = Lead.CAREFUL
    val DIGIT_DEFAULT = Digit.STANDARD

    const val LEAD_KEY = "typo_lead_strength"
    const val DIGIT_KEY = "typo_t9_strength"

    fun lead(context: Context): Lead = Lead.of(SettingsPreferences.getPrefsPublic(context).getString(LEAD_KEY, null))

    fun setLead(context: Context, value: Lead) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(LEAD_KEY, value.id).apply()
    }

    fun digit(context: Context): Digit = Digit.of(SettingsPreferences.getPrefsPublic(context).getString(DIGIT_KEY, null))

    fun setDigit(context: Context, value: Digit) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(DIGIT_KEY, value.id).apply()
    }
}
