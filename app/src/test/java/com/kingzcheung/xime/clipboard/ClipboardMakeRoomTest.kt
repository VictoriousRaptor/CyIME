package com.kingzcheung.xime.clipboard

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 用户 11-12：数量上限「不限」、保留「不过期」，却提示「剪贴板已满」不再保存。满了先删最早的历史。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ClipboardMakeRoomTest {
    private val database = Room.inMemoryDatabaseBuilder<ClipboardDatabase>(
        ApplicationProvider.getApplicationContext<Context>(),
    ).setDriver(AndroidSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    private val dao = database.clipboardDao()

    @After fun close() = database.close()

    private suspend fun texts() = dao.observeAll().first().map { it.text }.toSet()

    @Test fun theOldestHistoryGoesFirstAndFixedOrGroupedOrReceivedStay() = runBlocking {
        dao.insertPinnedText("固定的0123456789", "", 0)
        dao.upsert("分组的0123456789", 1)
        dao.setGrouped(listOf(dao.findSameText("分组的0123456789")!!.id), true)
        dao.addReceived("电脑来的012345678", 2, "", ClipboardCapacity.DEVICE_COUNT, ClipboardCapacity.DEVICE_BYTES)
        repeat(5) { dao.upsert("历史$it-23456789", 10L + it) }
        val stored = dao.storedBytes()
        // Room for 20 more bytes than there is: the two oldest history records (16 bytes each) go.
        assertTrue(dao.makeRoom(20, stored))
        val left = texts()
        assertFalse("历史0-23456789" in left)
        assertFalse("历史1-23456789" in left)
        assertTrue("历史2-23456789" in left)
        assertTrue(left.containsAll(listOf("固定的0123456789", "分组的0123456789", "电脑来的012345678")))
        assertEquals(stored - 32, dao.storedBytes())
    }

    @Test fun nothingGoesWhenFixedRecordsAloneFillTheRoom() = runBlocking {
        dao.insertPinnedText("固定的0123456789", "", 0)
        dao.upsert("历史", 1)
        // The room holds only the fixed record: removing the history is not enough for 4 more bytes.
        assertFalse(dao.makeRoom(4, 19))
        assertEquals(setOf("固定的0123456789", "历史"), texts())
    }

    @Test fun theRecordBeingEditedIsNotPushedOut() = runBlocking {
        dao.upsert("最早的那条", 1)
        dao.upsert("后来的", 2)
        val edited = dao.findSameText("最早的那条")!!.id
        assertTrue(dao.makeRoom(3, dao.storedBytes(), keep = edited))
        assertEquals(setOf("最早的那条"), texts())
    }
}
