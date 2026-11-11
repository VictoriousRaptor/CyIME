package com.kingzcheung.xime.settings

import android.content.Context
import kotlinx.coroutines.launch

object LanguagePreferences {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    const val KEY = "enabled_input_languages"
    /** 开关语言后的后台准备做完时改一次：键盘据此重读方案列表，语言键马上带上刚编译好的语言。 */
    const val SCHEMAS_READY_KEY = "language_schemas_ready_at"
    fun enabled(context: Context): Set<InputLanguage> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val stored = prefs.getStringSet(KEY, null)
        // Imported profiles without language metadata remain usable, without pretending to be Chinese.
        return (if (stored == null) setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH)
        else InputLanguage.entries.filterTo(mutableSetOf()) { it.id in stored } + InputLanguage.ENGLISH) + InputLanguage.UNSPECIFIED
    }
    fun initialize(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getStringSet(KEY, null)?.contains(InputLanguage.ENGLISH.id) != true) {
            prefs.edit().putStringSet(KEY, enabled(context).map { it.id }.toSet()).commit()
        }
    }
    /**
     * Turning a schema on in 方案管理 (or installing it from the market) also turns its language on: otherwise a
     * freshly deployed Japanese schema can never be reached, because the language key only cycles enabled
     * languages and switching to a schema of a disabled language is ignored (2026-10-19, tablet after 清除数据).
     */
    internal fun withSchemaLanguage(enabled: Set<InputLanguage>, schemaId: String): Set<InputLanguage> {
        val language = InputLanguage.forSchema(schemaId)
        return if (language in InputLanguage.supported && language !in enabled) enabled + language else enabled
    }

    /** Enable the schema's language without touching the schema list (the caller already changed it). */
    fun enableForSchema(context: Context, schemaId: String) {
        val current = enabled(context)
        val next = withSchemaLanguage(current, schemaId)
        if (next == current) return
        (next - current).forEach { InputModes.moveLanguageLast(context, it) }
        SettingsPreferences.getPrefsPublic(context).edit().putStringSet(KEY, next.map { it.id }.toSet()).commit()
    }

    /** The engine needs a real schema even when English is the only visible language. */
    internal fun nativeSchema(saved: String, available: List<String>, languages: Set<InputLanguage>): String =
        saved.takeIf { it in available && it != InputModes.ENGLISH && InputLanguage.forSchema(it) in languages }
            ?: available.firstOrNull { it.isNotBlank() && it != InputModes.ENGLISH && InputLanguage.forSchema(it) in languages }
            ?: available.firstOrNull { it.isNotBlank() && it != InputModes.ENGLISH }
            ?: "rime_ice"

    /** English always remains available; language switches never delete dictionaries. */
    fun save(context: Context, language: InputLanguage, enabled: Boolean) {
        require(language != InputLanguage.ENGLISH && language != InputLanguage.UNSPECIFIED)
        val before = this.enabled(context)
        val next = before.toMutableSet().apply { if (enabled) add(language) else remove(language) }
        if (enabled && language !in before) InputModes.moveLanguageLast(context, language)
        val schemasBefore = SchemaManager.getEnabledSchemas(context)
        // Bundled languages bring their own schemas; others only show what is already installed.
        val bundled = when (language) {
            InputLanguage.JAPANESE -> JapaneseSchemas.ids
            else -> LetterLanguage.of(language)?.let { listOf(it.schemaId) }
        }
        if (bundled != null) {
            val schemas = SchemaManager.getEnabledSchemas(context)
            SchemaManager.setEnabledSchemas(context, if (enabled) (schemas + bundled).distinct()
                else schemas.filterNot { InputLanguage.forSchema(it) == language })
        }
        // 补全词表是商店里的下载包：开启时就在后台下载，打字前多半已经下好。
        if (enabled) LetterLanguage.of(language)?.let { com.kingzcheung.xime.association.WordCompletion.download(context, it) }
        // 日语是插件：在这里打开日语也装上「日语」插件（随包带着），轻量版缺的词库和引擎开始下载。
        if (enabled && language == InputLanguage.JAPANESE) JapanesePlugin.onLanguageEnabled(context)
        // The English entry is virtual. Keep a real Rime schema as the engine fallback.
        val current = SettingsPreferences.getCurrentSchema(context)
        if (current == InputModes.ENGLISH || InputLanguage.forSchema(current) !in next) {
            val fallback = nativeSchema(current, SchemaManager.getEnabledSchemas(context), next)
            SettingsPreferences.setCurrentSchema(context, fallback)
        }
        // 只有启用的方案列表真的变了（开关日语、德语等自带方案的语言）才需要重新部署。
        // 开关中文、或方案本来就在列表里时，只改语言键轮换的语言，不让键盘重新「准备」一遍。
        val schemasChanged = SchemaManager.getEnabledSchemas(context) != schemasBefore
        if (schemasChanged) SettingsPreferences.setDeploymentDone(context, false)
        SettingsPreferences.getPrefsPublic(context).edit().putStringSet(KEY, next.map { it.id }.toSet()).commit()
        if (schemasChanged) scope.launch {
            com.kingzcheung.xime.rime.RimeConfigHelper.prepareAutomatically(context.applicationContext)
            // 键盘自己的那次刷新可能早于编译完成，读到的方案列表里还没有新语言：开了意大利语、准备完了，
            // 语言键还是只有中英，要关掉再开才出现（11-09）。准备完再通知键盘重读一次。
            SettingsPreferences.getPrefsPublic(context).edit().putLong(SCHEMAS_READY_KEY, System.currentTimeMillis()).apply()
        }
    }
}
