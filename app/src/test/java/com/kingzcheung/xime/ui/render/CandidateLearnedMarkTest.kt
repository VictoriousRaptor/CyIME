package com.kingzcheung.xime.ui.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.CandidateItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 自造词的编辑角标（用户 11-19：太宽了，不要影响正常候选间的宽度）：有没有角标候选一样宽，角标在字的右上角外面、候选自己的范围里。
 * 设了 CYIME_RENDER_OUT=<目录> 时出图 candidate-learned-mark.png。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w600dp-h400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateLearnedMarkTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun markTakesNoWidthAndStaysInsideTheCandidate() {
        rule.setContent {
            Column(Modifier.background(Color.White).padding(8.dp).testTag("strip")) {
                Row {
                    CandidateItem("测试", 0, {}, Color.Black, learned = true, fontSize = 20.sp, modifier = Modifier.testTag("learned"))
                    Spacer(Modifier.width(4.dp))
                    CandidateItem("测试", 1, {}, Color.Black, fontSize = 20.sp, modifier = Modifier.testTag("plain"))
                    Spacer(Modifier.width(4.dp))
                    CandidateItem("你好", 2, {}, Color.Black, comment = "hello", learned = true, fontSize = 20.sp, modifier = Modifier.testTag("learned-comment"))
                }
            }
        }
        val learned = rule.onNodeWithTag("learned").fetchSemanticsNode().boundsInRoot
        val plain = rule.onNodeWithTag("plain").fetchSemanticsNode().boundsInRoot
        assertEquals("角标不加宽候选", plain.width, learned.width, 0.5f)
        val mark = rule.onNodeWithTag("candidate-learned:0", true).fetchSemanticsNode().boundsInRoot
        assertTrue("角标在候选范围里：$mark / $learned", mark.right <= learned.right + 0.5f && mark.left >= learned.left)
        assertTrue("角标在上半截", mark.bottom <= learned.center.y)
        val markWithComment = rule.onNodeWithTag("candidate-learned:2", true).fetchSemanticsNode().boundsInRoot
        val comment = rule.onNodeWithTag("learned-comment").fetchSemanticsNode().boundsInRoot
        assertTrue(markWithComment.right <= comment.right)
        System.getenv("CYIME_RENDER_OUT")?.let { out ->
            val root = rule.activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(Canvas(full)) }
            val box = rule.onNodeWithTag("strip").fetchSemanticsNode().boundsInWindow
            val bmp = Bitmap.createBitmap(full, box.left.toInt(), box.top.toInt(), box.width.toInt(), box.height.toInt())
            File(out, "candidate-learned-mark.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
