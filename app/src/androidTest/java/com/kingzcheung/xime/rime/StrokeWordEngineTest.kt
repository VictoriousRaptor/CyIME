package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.SchemaManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeWordEngineTest {
    @Test fun biHuaCommitsWithFullStrokesAndWithAWildcardInEitherCharacter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        val previous = engine.getCurrentSchema()
        val ascii = engine.isAsciiMode()
        val enabled = SchemaManager.getEnabledSchemas(context)
        try {
            if ("stroke" !in enabled) SchemaManager.setEnabledSchemas(context, enabled + "stroke")
            if ("stroke" !in enabled || !SchemaManager.isSchemaCompiled(context, "stroke"))
                assertTrue(RimeConfigHelper.redeploy(context))
            assertTrue(engine.switchSchema("stroke"))
            engine.setOption("ascii_mode", false)
            fun typeAndCommit(input: String): String {
                for (key in input) engine.processQueuedKeyAndGetResult(key.code, 0)
                return engine.processQueuedKeyAndGetResult(' '.code, 0).committedText
            }
            assertEquals("笔画", typeAndCommit("phnphnphhz") + typeAndCommit("hszhshzs"))
            for (input in listOf("phnphnphhzhszhshzs", "phnphnphhz'hszhshzs",
                "ph*phnphhzhszhshzs", "phnphnphhz'hszh*hzs")) {
                assertEquals(input, "笔画", typeAndCommit(input))
                assertEquals("", engine.getInput())
            }
            // n 个分词号出 n+1 个字，分词号后面、中间没打笔画也算一个字（11-18 贡献者）。
            assertEquals("骆驼", typeAndCommit("zzhpzns'"))
            assertEquals("吉祥如意", typeAndCommit("hshszh'nzsnnphhhs''"))
        } finally {
            engine.clearQueuedComposition()
            if ("stroke" !in enabled) {
                SchemaManager.setEnabledSchemas(context, enabled)
                assertTrue(RimeConfigHelper.redeploy(context))
            }
            engine.switchSchema(previous)
            engine.setOption("ascii_mode", ascii)
        }
    }
}
