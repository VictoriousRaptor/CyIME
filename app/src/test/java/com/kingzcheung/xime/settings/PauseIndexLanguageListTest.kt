package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用户 11-19：「语言与方案 › 导入方案的语言」列了五个「双拼停顿纠错索引」。它们是 CyIME 给每个双拼方案生成的依赖，
 * 不是导入的方案，不给选语言；用户自己导入、名字不相干的方案照常列出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PauseIndexLanguageListTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun pauseIndexesAreAppProvidedButImportedSchemesAreNot() {
        for (base in listOf("double_pinyin_flypy", "double_pinyin_mspy", "double_pinyin", "double_pinyin_abc",
            DoublePinyinScheme.CUSTOM_PREFIX + "0".repeat(32))) {
            assertTrue(base, SchemaLanguageChoice.isAppProvided(app, DoublePinyinScheme.pauseId(base)))
        }
        assertFalse(SchemaLanguageChoice.isAppProvided(app, "my_imported_scheme"))
        assertFalse(SchemaLanguageChoice.isAppProvided(app, "wanxiang"))
    }
}
