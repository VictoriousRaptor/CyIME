package com.kingzcheung.xime.ui.keyboard

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.DoublePinyinSchemes
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.LayoutKind
import com.kingzcheung.xime.settings.NineKeyFuzzyReadings
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberNineKeyFuzzyReadings(schema: String, profile: InputProfile): NineKeyFuzzyReadings? {
    if (profile.layout.kind != LayoutKind.T9 || profile.language != InputLanguage.CHINESE ||
        profile.scheme != InputScheme.PINYIN && profile.scheme != InputScheme.DOUBLE_PINYIN) return null
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    var revision by remember(prefs) { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SpellingPatches.FUZZY_KEY || key == SpellingPatches.FUZZY_DOUBLE_PINYIN_KEY ||
                key == SpellingPatches.REVISION) revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val scheme = if (profile.scheme == InputScheme.DOUBLE_PINYIN) DoublePinyinSchemes.find(schema) ?: return null else null
    return key(schema, scheme, revision) {
        produceState<NineKeyFuzzyReadings?>(null) {
            value = withContext(Dispatchers.Default) {
                val groups = SpellingPatches.fuzzy(context)
                // 双拼九键的拼音栏全靠这里按方案的两个键算，没开模糊音也要有（只列精确读音）。
                if (scheme != null) return@withContext NineKeyFuzzyReadings(scheme,
                    if (SpellingPatches.fuzzyDoublePinyin(context)) groups else emptySet())
                if (groups.isEmpty()) return@withContext null
                NineKeyFuzzyReadings(null, groups)
            }
        }.value
    }
}
