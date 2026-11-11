package com.kingzcheung.xime.ui.keyboard

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.CandidateCommentMode

@Composable
internal fun rememberCandidateTextSize(): Int {
    val context = LocalContext.current
    val preferences = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    var size by remember(preferences) { mutableStateOf(SettingsPreferences.getCandidateTextSize(context)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "candidate_text_size") size = SettingsPreferences.getCandidateTextSize(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return size
}

/** Use the candidate viewport, not the device width: a tablet can host a narrow floating keyboard. */
internal fun candidateCommentsVisible(
    widthDp: Float,
    fontScale: Float,
    textSizeSp: Float,
    mode: CandidateCommentMode,
): Boolean {
    if (widthDp <= 0 || mode == CandidateCommentMode.HIDE) return false
    if (mode == CandidateCommentMode.SHOW) return true
    val textScale = (fontScale * textSizeSp / 19f).coerceAtLeast(1f)
    return widthDp >= 480f * textScale
}

/** 纠错标出的读音也是注释：注释不显示（隐藏，或「自动」时候选栏太窄）就不显示，免得只有纠错候选冒出拼音。 */
internal fun shownCorrections(corrections: Map<String, String>, commentsShown: Boolean): Map<String, String> =
    if (commentsShown) corrections else emptyMap()

/** Keep the existing explicit hide preference, including changes while the IME is visible. */
@Composable
internal fun rememberCandidateCommentsAllowed(): CandidateCommentMode {
    val context = LocalContext.current
    val preferences = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    var allowed by remember(preferences) { mutableStateOf(SettingsPreferences.candidateCommentMode(context)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SettingsPreferences.KEY_SHOW_CANDIDATE_COMMENTS || key == SettingsPreferences.KEY_CANDIDATE_COMMENT_MODE) {
                allowed = SettingsPreferences.candidateCommentMode(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return allowed
}

@Composable
internal fun rememberCorrectionMarksVisible(): Boolean {
    val context = LocalContext.current
    val preferences = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    var visible by remember(preferences) { mutableStateOf(SettingsPreferences.showCorrectionMarks(context)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SettingsPreferences.KEY_SHOW_CORRECTION_MARKS)
                visible = SettingsPreferences.showCorrectionMarks(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible
}

@Composable
internal fun rememberDoublePinyinKeysShown(): Boolean {
    val context = LocalContext.current
    val preferences = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    var keysShown by remember(preferences) { mutableStateOf(com.kingzcheung.xime.settings.SpellingPatches.doublePinyinRawPreedit(context)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == com.kingzcheung.xime.settings.SpellingPatches.DOUBLE_PINYIN_RAW_PREEDIT_KEY)
                keysShown = com.kingzcheung.xime.settings.SpellingPatches.doublePinyinRawPreedit(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return keysShown
}
