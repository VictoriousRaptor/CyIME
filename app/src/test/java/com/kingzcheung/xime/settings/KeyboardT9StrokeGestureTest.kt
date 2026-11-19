package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 九键（keyboard.t9.keys）/ 笔画（keyboard.stroke.keys）手势配置测试：
 * 1. YAML 解析（字符串简写 / 对象格式 / 中文键名）
 * 2. swipeHandlerFor 分发（COMMIT→直接上屏、编辑动作→onGestureAction、NONE/空→无手势）
 * 3. 内置 xime.yaml 默认绑定与前端接线约定一致（守护资产与解析/布局不脱节）
 */
class KeyboardT9StrokeGestureTest {

    // ── YAML 解析 ──

    @Test
    fun `t9 keys 字符串简写解析为 commit 上滑`() {
        val keys = KeysConfigHelper.parseKeyboardYamlSection(
            """
            keyboard:
              t9:
                keys:
                  "2": { swipe_up: "2" }
            """.trimIndent(),
            "t9",
        )
        val swipe = keys!!["2"]!!.swipeUp!!
        assertEquals("2", swipe.label)
        assertEquals(GestureAction.COMMIT, swipe.action)
        assertEquals("2", swipe.value)
    }

    @Test
    fun `t9 keys 对象格式下滑绑定编辑动作`() {
        val keys = KeysConfigHelper.parseKeyboardYamlSection(
            """
            keyboard:
              t9:
                keys:
                  "5": { swipe_up: "5", swipe_down: { label: "复制", action: "copy" } }
            """.trimIndent(),
            "t9",
        )
        val kc = keys!!["5"]!!
        assertEquals(GestureAction.COMMIT, kc.swipeUp!!.action)
        assertEquals("复制", kc.swipeDown!!.label)
        assertEquals(GestureAction.COPY, kc.swipeDown!!.action)
    }

    @Test
    fun `t9 keys 段落在 side_symbols 之外互不干扰`() {
        val keys = KeysConfigHelper.parseKeyboardYamlSection(
            """
            keyboard:
              t9:
                side_symbols:
                  - "，"
                keys:
                  "9": { swipe_down: { label: "剪贴板", action: "switch_route", value: "clipboard" } }
            """.trimIndent(),
            "t9",
        )
        assertEquals("clipboard", keys!!["9"]!!.swipeDown!!.value)
    }

    @Test
    fun `stroke keys 支持中文键名`() {
        val keys = KeysConfigHelper.parseKeyboardYamlSection(
            """
            keyboard:
              stroke:
                keys:
                  "一": { swipe_up: "1" }
                  "分词": { swipe_up: "7", swipe_down: { label: "粘贴", action: "paste" } }
            """.trimIndent(),
            "stroke",
        )
        assertEquals("1", keys!!["一"]!!.swipeUp!!.value)
        assertEquals(GestureAction.PASTE, keys["分词"]!!.swipeDown!!.action)
    }

    @Test
    fun `缺失 section 返回 null 不抛异常`() {
        val keys = KeysConfigHelper.parseKeyboardYamlSection(
            """
            keyboard:
              t9:
                side_symbols: ["，"]
            """.trimIndent(),
            "stroke",
        )
        assertNull(keys)
    }

    // ── swipeHandlerFor 分发 ──

    @Test
    fun `swipeHandlerFor COMMIT 走直接上屏`() {
        var committed: String? = null
        var dispatched: Pair<GestureAction, String>? = null
        val handler = swipeHandlerFor(
            GestureDef(label = "5", action = GestureAction.COMMIT, value = "5"),
            onCommitText = { committed = it },
            onGestureAction = { action, value -> dispatched = action to value },
        )
        handler!!.invoke()
        assertEquals("5", committed)
        assertNull(dispatched)
    }

    @Test
    fun `swipeHandlerFor 编辑动作走 onGestureAction 且 value 空时回退 label`() {
        var committed: String? = null
        var dispatched: Pair<GestureAction, String>? = null
        val handler = swipeHandlerFor(
            GestureDef(label = "复制", action = GestureAction.COPY),
            onCommitText = { committed = it },
            onGestureAction = { action, value -> dispatched = action to value },
        )
        handler!!.invoke()
        assertEquals(GestureAction.COPY to "复制", dispatched)
        assertNull(committed)
    }

    @Test
    fun `swipeHandlerFor value 优先于 label`() {
        var dispatched: Pair<GestureAction, String>? = null
        swipeHandlerFor(
            GestureDef(label = "剪贴板", action = GestureAction.SWITCH_ROUTE, value = "clipboard"),
            onCommitText = {},
            onGestureAction = { action, value -> dispatched = action to value },
        )!!.invoke()
        assertEquals(GestureAction.SWITCH_ROUTE to "clipboard", dispatched)
    }

    @Test
    fun `swipeHandlerFor 空定义 NONE 与 action null 均视为未绑定`() {
        assertNull(swipeHandlerFor(null, {}, null))
        assertNull(swipeHandlerFor(GestureDef(action = GestureAction.NONE), {}, null))
        assertNull(swipeHandlerFor(GestureDef(label = "x", action = null), {}, null))
    }

    // ── 内置默认绑定（守护 xime.yaml 资产与解析/前端约定不脱节） ──

    /** 定位仓库内文件（单测 workingDir 可能是模块目录或仓库根目录，逐级向上查找）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("file not found: $rel")
    }

    private fun loadAssetSection(section: String): Map<String, KeyGestureConfig> {
        val text = repoFile("src/main/assets/xime.yaml").readText()
        return KeysConfigHelper.parseKeyboardYamlSection(text, section) ?: emptyMap()
    }

    @Test
    fun `内置 t9 keys 上滑 1-9 均为 commit 键面数字`() {
        val keys = loadAssetSection("t9")
        for (d in '1'..'9') {
            val id = d.toString()
            val swipe = keys[id]?.swipeUp ?: error("t9.keys 缺少 $id 的上滑绑定")
            assertEquals(GestureAction.COMMIT, swipe.action)
            assertEquals(id, swipe.value)
            // 默认数字键同时显示键面提示和上滑预览。
            assertEquals("t9.keys $id 上滑 display", DisplayMode.BOTH, swipe.display)
        }
    }

    @Test
    fun `内置 t9 keys 默认不绑定下滑动作`() {
        val keys = loadAssetSection("t9")
        // 内置默认仅上滑输数字；下滑全部留空，由用户在 xime.custom.yaml 按键级配置
        for (d in '1'..'9') {
            assertNull("t9.keys $d 下滑应留空供自定义", keys[d.toString()]?.swipeDown)
        }
    }

    @Test
    fun `内置 stroke keys 上滑覆盖全部笔画与扩展键`() {
        val keys = loadAssetSection("stroke")
        val expected = mapOf(
            "一" to "1", "丨" to "2", "丿" to "3", "丶" to "4", "乛" to "5",
            "*" to "6", "分词" to "7", "，" to "8", "。" to "9",
        )
        expected.forEach { (id, digit) ->
            val swipe = keys[id]?.swipeUp ?: error("stroke.keys 缺少 $id 的上滑绑定")
            assertEquals(GestureAction.COMMIT, swipe.action)
            assertEquals(digit, swipe.value)
            // 内置默认显式 display: "key"：仅键面提示，无滑动气泡
            assertEquals("stroke.keys $id 上滑 display", DisplayMode.KEY, swipe.display)
        }
    }

    @Test
    fun `内置九键侧栏只放最常用标点且括号只留一对`() {
        // 用户 10-30：左栏只有四个、滑不动，要能滑；10-31：默认不需要这么多括号，放最常用的就行。
        val text = repoFile("src/main/assets/xime.yaml").readText()
        val expected = listOf("，", "。", "？", "！", "…", "～", "、", "：", "“", "”", "；", "（", "）")
        assertEquals(expected, KeysConfigHelper.parseKeyboardT9YamlPartial(text)?.sideSymbols)
        assertEquals(expected, KeysConfigHelper.DEFAULT_T9_SIDE_SYMBOLS)
        // 都在常用符号面板里，四格之外仍可滑动
        assertTrue(com.kingzcheung.xime.keyboard.commonSymbolsFor("中").containsAll(expected))
        assertTrue(expected.size > 4)
        val brackets = listOf("（", "）", "《", "》", "【", "】", "‘", "’", "「", "」")
        assertEquals(listOf("（", "）"), expected.filter { it in brackets })
    }

    @Test
    fun `内置 stroke side_symbols 和九键侧栏同一套，前四个是逗号句号问号叹号`() {
        // 11-18 贡献者：笔画左栏要像九键一样能上下滑，默认前四个 ，。？！。
        val text = repoFile("src/main/assets/xime.yaml").readText()
        val partial = KeysConfigHelper.parseKeyboardStrokeYamlPartial(text)
        assertEquals(KeysConfigHelper.DEFAULT_T9_SIDE_SYMBOLS, partial?.sideSymbols)
        assertEquals(KeysConfigHelper.DEFAULT_T9_SIDE_SYMBOLS, KeysConfigHelper.DEFAULT_STROKE_SIDE_SYMBOLS)
        assertEquals(listOf("，", "。", "？", "！"), partial?.sideSymbols?.take(4))
    }

    @Test
    fun `stroke side_symbols 支持同路径覆盖解析`() {
        val yaml = """
            keyboard:
              stroke:
                side_symbols:
                  - "，"
                  - "。"
        """.trimIndent()
        val partial = KeysConfigHelper.parseKeyboardStrokeYamlPartial(yaml)
        assertEquals(listOf("，", "。"), partial?.sideSymbols)
        // 合并语义：custom → builtIn → 代码默认值
        val merged = KeysConfigHelper.mergeStrokeConfigs(partial, null)
        assertEquals(listOf("，", "。"), merged.sideSymbols)
    }
}
