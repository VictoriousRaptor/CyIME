package com.kingzcheung.xime.platform

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android 9（API 28）的正则是 ICU 63，不认 Java 专有的 \p{IsHan}、\p{InCJK…} 写法，编译时抛
 * PatternSyntaxException。写在 object 的初始化里时整个类加载失败，vivo Y5s 上选一个候选输入法就闪退。
 * 文字属性一律写 \p{script=Han} 这种 ICU 和 JVM 都认的形式。
 */
class RegexPropertySyntaxTest {
    @Test
    fun noJavaOnlyUnicodePropertyNames() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val javaOnly = Regex("""\\p\{(Is|In)[A-Z]""")
        val offenders = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { file -> file.readLines().mapIndexedNotNull { index, line ->
                if (javaOnly.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}" else null } }
            .toList()
        assertTrue("Android 9 的 ICU 正则不认 \\p{IsXxx}/\\p{InXxx}，改成 \\p{script=Xxx}：$offenders", offenders.isEmpty())
    }

    @Test
    fun scriptPropertyStillMatchesHan() {
        assertTrue(Regex("[\\p{script=Han}，。]{2,240}").matches("火锅。"))
        assertTrue(!Regex("[\\p{script=Han}]+").matches("huo"))
    }
}
