package com.kingzcheung.xime.ui.caption

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.platform.AndroidPlatform

/**
 * Full-screen live captions for a hard-of-hearing reader: one row per speaker, newest speech at
 * the right. Opened from its own home-screen icon, the quick-settings tile or the keyboard menu.
 */
class CaptionActivity : ComponentActivity() {
    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, CaptionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var controller: CaptionController
    private var permissionAsked = false
    /** 退出 was pressed: captions stop instead of floating on. */
    private var exitRequested = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) controller.start()
        else com.kingzcheung.xime.ui.i18n.UiToast.makeText(this, "没有麦克风权限就听不到，请在手机设置里允许", Toast.LENGTH_LONG).show()
    }

    /**
     * Glass themes: like the keyboard over another app, the page shows what is behind it (the
     * wallpaper), blurred with the keyboard's own blur radius. Without this the "glass" panels sat
     * on a flat backstop and looked solid grey.
     */
    private fun applyGlass() {
        val glass = com.kingzcheung.xime.settings.FrostedGlassPreferences.read(this)
        if (!glass.enabled) return
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        val radius = (glass.blurRadiusDp * resources.displayMetrics.density).toInt()
        if (radius <= 0) return
        if (AndroidPlatform.windowBlur && windowManager.isCrossWindowBlurEnabled) window.setBackgroundBlurRadius(radius)
        else if (com.kingzcheung.xime.ui.theme.SamsungWindowBlur.isAvailable)
            window.decorView.post { com.kingzcheung.xime.ui.theme.SamsungWindowBlur.apply(window.decorView, radius, 0f) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Full screen without the status bar; the navigation bar stays so "back" is always visible
        // (hiding it made the swipe that reveals it land on the bottom buttons). Never dims while open.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyGlass()
        // Captions floating from an earlier page carry on here instead of starting over.
        controller = CaptionFloat.giveBack() ?: CaptionController(this)
        CaptionFloat.pageAlive = true
        setContent { com.kingzcheung.xime.ui.i18n.ProvideUiTranslations { CaptionScreen(controller, onExit = { exitRequested = true; finish() }) }}
    }

    override fun onStart() {
        super.onStart()
        CaptionFloat.giveBack()
        controller.refreshCallCaption()
        if (PermissionHelper.hasRecordAudioPermission(this)) {
            if (controller.status == CaptionController.Status.Idle || controller.resumeOnForeground) {
                controller.resumeOnForeground = false
                controller.start()
            }
        } else if (!permissionAsked) {
            permissionAsked = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        val active = controller.status == CaptionController.Status.Listening || controller.status == CaptionController.Status.Starting ||
            controller.status == CaptionController.Status.Held
        // 「后台浮窗」 on: the captions go on in a bar over other apps (needs the call caption service).
        if (active && !exitRequested && CaptionFloat.takeOver(this, controller)) { super.onStop(); return }
        // Background: release the microphone; resume on return.
        if (active) {
            controller.resumeOnForeground = true
            controller.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        CaptionFloat.pageAlive = false
        // Still floating (the page was swiped away): the bar owns the captions now.
        if (CaptionFloat.controller !== controller) controller.release()
        super.onDestroy()
    }
}
