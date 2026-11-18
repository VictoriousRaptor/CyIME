package com.kingzcheung.xime.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.SpaceHoldAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「更多配置」和设置页是同一处设置（11-14 反馈：这边选了「退格直接删字」，按键与手势里的开关不跟着开）：
 * 这边写的，设置页那个开关读得到；设置页改的，这边打开时读得到。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SetupMoreSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun moreConfigWritesWhatSettingsPagesRead() {
        val from = SetupGuide.readMoreChoices(context)
        val to = from.copy(deleteSkipsAssociation = !from.deleteSkipsAssociation,
            clearBeforeCursorOnly = !from.clearBeforeCursorOnly, reverseSwipe = !from.reverseSwipe,
            cancelButton = !from.cancelButton, autoPunctuation = !from.autoPunctuation,
            spaceHold = if (from.spaceHold == SpaceHoldAction.REPEAT) SpaceHoldAction.CURSOR else SpaceHoldAction.REPEAT)
        SetupGuide.applyMore(context, from, to)
        val keys = KeyboardInputPreferences.read(context)
        assertEquals(to.deleteSkipsAssociation, SettingsPreferences.isDeleteSkipsAssociation(context))
        assertEquals(to.clearBeforeCursorOnly, keys.clearBeforeCursorOnly)
        assertEquals(to.reverseSwipe, keys.reverseSymbolSwipe)
        assertEquals(to.cancelButton, SettingsPreferences.shouldShowCandidateCancelButton(context))
        assertEquals(to.autoPunctuation, SettingsPreferences.isSttAutoPunctuation(context))
        assertEquals(to.spaceHold, keys.effectiveSpaceHold)
        assertEquals(to, SetupGuide.readMoreChoices(context))
    }

    @Test fun settingsPageChangesShowUpHere() {
        val before = SetupGuide.readMoreChoices(context)
        SettingsPreferences.setDeleteSkipsAssociation(context, !before.deleteSkipsAssociation)
        SettingsPreferences.setShowCandidateCancelButton(context, !before.cancelButton)
        KeyboardInputPreferences.read(context).copy(reverseSymbolSwipe = !before.reverseSwipe).save(context)
        val after = SetupGuide.readMoreChoices(context)
        assertEquals(!before.deleteSkipsAssociation, after.deleteSkipsAssociation)
        assertEquals(!before.cancelButton, after.cancelButton)
        assertEquals(!before.reverseSwipe, after.reverseSwipe)
    }
}
