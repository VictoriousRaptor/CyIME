package com.kingzcheung.xime.settings

/** Language menus and settings resolve only real entries, preserving their backend IDs and order. */
object InputProfileSelection {
    fun preferred(entries: List<SchemaInfo>, language: InputLanguage, rememberedId: String?,
        currentId: String? = null): SchemaInfo? {
        val group = entries.filter { it.profile.language == language && it.profile.mode == InputMode.KEYBOARD }
        return group.firstOrNull { it.schemaId == rememberedId }
            ?: group.firstOrNull { it.schemaId == currentId } ?: group.firstOrNull()
    }

    fun changeScheme(entries: List<SchemaInfo>, current: SchemaInfo, scheme: InputScheme): SchemaInfo? {
        val group = entries.filter { it.profile.language == current.profile.language &&
            it.profile.scheme == scheme && it.profile.mode == InputMode.KEYBOARD }
        return group.firstOrNull { it.schemaId == current.schemaId }
            ?: group.firstOrNull { it.profile.layout.id == current.profile.layout.id } ?: group.firstOrNull()
    }

    fun schemes(entries: List<SchemaInfo>, language: InputLanguage): List<InputScheme> = entries
        .filter { it.profile.language == language && it.profile.mode == InputMode.KEYBOARD }
        .map { it.profile.scheme }.distinct()

    /** Concrete installed choices, retaining variant names rather than a generic "双拼" option. */
    fun schemeEntries(entries: List<SchemaInfo>, current: SchemaInfo): List<SchemaInfo> =
        schemes(entries, current.profile.language).flatMap { scheme ->
            val group = entries.filter { it.profile.language == current.profile.language &&
                it.profile.scheme == scheme && it.profile.mode == InputMode.KEYBOARD }
            val compatible = group.filter { it.profile.layout.id == current.profile.layout.id }
            if (compatible.isNotEmpty()) compatible else {
                val fallbackLayout = changeScheme(entries, current, scheme)?.profile?.layout?.id
                group.filter { it.profile.layout.id == fallbackLayout }
            }
        }.distinctBy { it.schemaId }

    fun layouts(entries: List<SchemaInfo>, current: SchemaInfo): List<InputLayout> = entries
        .filter { it.profile.language == current.profile.language && it.profile.scheme == current.profile.scheme &&
            it.profile.mode == InputMode.KEYBOARD }
        .flatMap { layoutsOf(it, entries) }.distinctBy { it.id }

    /**
     * Layouts one entry can be typed on. Chinese entries carry theirs in the schema; English and
     * Japanese romaji offer their own 26 keys plus every letter layout the Chinese entries offer.
     */
    fun layoutsOf(entry: SchemaInfo, entries: List<SchemaInfo>): List<InputLayout> {
        val base = entry.baseProfile
        // 法语：自己的 26 键和 AZERTY 两个布局（xime.yaml 里各一个 section）。
        val sections = LetterLayouts.sectionChoices(base.language)
        if (sections.isNotEmpty() && base.layout.kind == LayoutKind.ALPHABETIC) {
            val own = KeysConfigHelper.ownSectionForSchema(entry.schemaId) ?: LetterLanguage.ofSchema(entry.schemaId)?.keyboard
            return (listOfNotNull(own) + sections).distinct().map { InputProfiles.resolveLayout(it) }
        }
        if (base.layout.kind != LayoutKind.ALPHABETIC || !LetterLayouts.appliesTo(base.language, base.scheme))
            return listOf(entry.profile.layout)
        return listOf(base.layout) + LetterLayouts.choices(entries, base.language).map(InputProfiles::customLayout)
    }

    fun offers(entry: SchemaInfo, layoutId: String, entries: List<SchemaInfo>): Boolean =
        layoutsOf(entry, entries).any { it.id == layoutId }

    fun variants(entries: List<SchemaInfo>, current: SchemaInfo): List<SchemaInfo> = entries.filter {
        it.profile.mode == InputMode.KEYBOARD && it.profile.language == current.profile.language &&
            it.profile.scheme == current.profile.scheme && it.profile.layout.id == current.profile.layout.id
    }

    /** 同一方案：编码相同且是同一变体（小鹤双拼的 26 键和 14 键条目算同一个，微软双拼不算）。 */
    fun sameScheme(a: SchemaInfo, b: SchemaInfo): Boolean = a.profile.scheme == b.profile.scheme &&
        (a.schemeLabel == b.schemeLabel || variantId(a) == variantId(b))

    private fun variantId(entry: SchemaInfo): String = CyimeInputDefaults.companionNeeds(entry.schemaId)
        ?.firstOrNull { InputProfiles.describe(it).scheme == entry.profile.scheme } ?: entry.schemaId

    /** 换到某布局后用的条目；[keepsScheme] 为假表示这个布局没有当前方案，只能换成它有的方案。 */
    data class LayoutSwitch(val entry: SchemaInfo, val keepsScheme: Boolean)

    /** 点布局即切换：优先保持当前方案（含变体），没有时取同类编码，再没有取排在最前的方案。 */
    fun switchLayout(entries: List<SchemaInfo>, current: SchemaInfo, layoutId: String,
        all: List<SchemaInfo> = entries): LayoutSwitch? {
        val offering = entries.filter { it.profile.mode == InputMode.KEYBOARD &&
            it.profile.language == current.profile.language && offers(it, layoutId, all) }
        (offering.firstOrNull { it.schemaId == current.schemaId } ?: offering.firstOrNull { sameScheme(it, current) })
            ?.let { return LayoutSwitch(it, true) }
        return (offering.firstOrNull { it.profile.scheme == current.profile.scheme } ?: offering.firstOrNull())
            ?.let { LayoutSwitch(it, false) }
    }

    /** 当前语言所有方案提供的布局；没有当前方案的布局由界面标灰，点了换方案并提示。 */
    fun languageLayouts(entries: List<SchemaInfo>, current: SchemaInfo): List<InputLayout> = layoutRow(entries
        .filter { it.profile.language == current.profile.language && it.profile.mode == InputMode.KEYBOARD }, entries)

    /**
     * 布局栏的顺序：每个条目自己的布局按条目顺序排（中文、日语一样是 26 键、九键……），
     * 英文、日语罗马字、双拼借用的字母排列排在最后，不插到九键前面。
     */
    fun layoutRow(group: List<SchemaInfo>, all: List<SchemaInfo>): List<InputLayout> {
        val offered = group.map { layoutsOf(it, all) }
        return (offered.map { it.first() } + offered.flatten()).distinctBy { it.id }
    }

    /**
     * 自建的合并键布局只有 CyIME 给它生成过的方案（中文26键的副本、内置和自建双拼、写明双拼的导入方案的布局版本），
     * 说一句原因（#160：用户以为自己方案写错了）。
     */
    fun switchHint(layout: InputLayout, current: SchemaInfo, target: SchemaInfo,
        mergedCustom: Boolean = CustomKeyboardLayouts.find(layout.id)?.mergedGroups?.isNotEmpty() == true): String =
        "${layout.displayName}没有${current.schemeLabel}，已换成${target.schemeLabel}" +
            if (mergedCustom) "\n$MERGED_LAYOUT_NOTE" else ""

    /** 合并键布局能用哪些方案。 */
    const val MERGED_LAYOUT_NOTE = "合并键布局只能用中文26键和双拼方案"

    /**
     * 灰着的布局卡片上直接写的原因（用户 11-22：选了五笔点别的布局像被弹回去，以为坏了）。
     * 第二行是点了会怎样；卡片矮时只显示第一行。
     */
    fun unavailableNote(current: SchemaInfo, target: SchemaInfo): String =
        "不能用${current.schemeLabel}\n点击切换${target.schemeLabel}"

    /** 布局一行里每张灰卡片的原因，键是布局 ID。 */
    fun unavailableNotes(current: SchemaInfo, switches: Map<String, LayoutSwitch?>): Map<String, String> =
        switches.mapNotNull { (id, switch) -> switch?.takeIf { !it.keepsScheme }?.let { id to unavailableNote(current, it.entry) } }.toMap()

    /** Changing shape cannot silently change language or encoding. Missing combinations stay unavailable. */
    fun changeLayout(entries: List<SchemaInfo>, current: SchemaInfo, layoutId: String): SchemaInfo? = entries
        .filter { it.profile.mode == InputMode.KEYBOARD && it.profile.language == current.profile.language &&
            it.profile.scheme == current.profile.scheme && offers(it, layoutId, entries) }
        .let { group -> group.firstOrNull { it.schemaId == current.schemaId } ?: group.firstOrNull() }
}
