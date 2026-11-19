package com.kingzcheung.xime.clipboard.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #360：电脑推来时手机被冻住会漏，打开键盘时按这条规则补一次。 */
class MeshCatchUpTest {
    @Test fun screenOnOrNetworkChangeAlwaysCatchesUp() {
        assertTrue(meshCatchUpDue(eventDue = true, failing = true, lastContactAt = 0L, now = 1_000L))
    }

    @Test fun aQuietMinuteOnAWorkingConnectionCatchesUp() {
        assertTrue(meshCatchUpDue(false, failing = false, lastContactAt = 0L, now = MESH_QUIET_CATCH_UP_MS))
        assertTrue(meshCatchUpDue(false, failing = false, lastContactAt = Long.MIN_VALUE, now = 5L))
    }

    @Test fun recentContactDoesNotAskAgain() {
        assertFalse(meshCatchUpDue(false, failing = false, lastContactAt = 0L, now = MESH_QUIET_CATCH_UP_MS - 1))
    }

    @Test fun aPeerThatWasNotReachableWaitsForScreenOnOrANetworkChange() {
        assertFalse(meshCatchUpDue(false, failing = true, lastContactAt = 0L, now = 10 * MESH_QUIET_CATCH_UP_MS))
    }
}
