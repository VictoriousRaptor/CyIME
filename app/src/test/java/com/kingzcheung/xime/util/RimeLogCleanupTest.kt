package com.kingzcheung.xime.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RimeLogCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `认得 glog 的文件名，只留最新 10 个`() {
        val dir = tmp.newFolder("logs")
        val logs = (1..13).map { i ->
            File(dir, "rime.kime.localhost.u0_a321.log.WARNING.20261117-1900%02d.%d".format(i, 1000 + i)).apply {
                writeText("w"); setLastModified(1_000_000L * i)
            }
        }
        val link = File(dir, "rime.kime.WARNING").apply { writeText("latest") }
        val other = File(dir, "notes.txt").apply { writeText("x") }
        val deleted = FileLogger.rimeLogsToDelete(dir.listFiles()!!.toList()).toSet()
        assertEquals(logs.take(3).toSet(), deleted)
        assertEquals(false, link in deleted || other in deleted)
    }

    @Test fun `旧的 log 结尾文件照样清，超过 5 MB 的删掉`() {
        val dir = tmp.newFolder("logs")
        val big = File(dir, "rime.log").apply { writeBytes(ByteArray(5 * 1024 * 1024 + 1)) }
        val small = File(dir, "rime.kime.localhost.u0_a321.log.ERROR.20261117-190000.1").apply { writeText("e") }
        assertEquals(listOf(big), FileLogger.rimeLogsToDelete(listOf(big, small)))
    }
}
