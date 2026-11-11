package com.kingzcheung.xime.ui.render

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class GlideCandidatePreviewRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun glideCandidatePreviewDisplaysEngineCandidatesWithoutChangingTheInputState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = spy(KeyboardViewModel(app))
        var selections = 0
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = { selections++ })
        doReturn(mock<T9InputController>()).whenever(vm).bindT9Controller(callbacks)
        val original = mutableStateOf(com.kingzcheung.xime.service.CandidateState())
        val preview = mutableStateOf<com.kingzcheung.xime.rime.RimeProcessResult?>(
            com.kingzcheung.xime.rime.RimeProcessResult(true, "", "nihao", "ni hao",
                arrayOf(com.kingzcheung.xime.rime.RimeCandidate("你好", "ni hao")), false, false, false))
        rule.setContent { MaterialTheme {
            KeyboardView(vm, KeyboardUiState(), callbacks, Modifier.width(360.dp).height(300.dp),
                candidateState = original, glideRimePreview = preview, inputSessionManagedByHost = true)
        } }
        rule.onNodeWithText("你好").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(0, selections); assertEquals("", original.value.inputText); preview.value = null }
        rule.onNodeWithText("你好").assertDoesNotExist()
    }

}
