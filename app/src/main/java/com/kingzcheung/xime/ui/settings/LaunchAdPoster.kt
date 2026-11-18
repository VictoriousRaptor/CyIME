package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 启动广告彩蛋的那一页（用户 11-15，B 站评论「希望增加启动 5s 全屏广告」）：故意画得很丑、一看就像病毒弹窗的假广告。
 * 整页都是代码画的，没有图片；不用任何真实的品牌、产品名和人像。除了「跳过」，点哪里都只会让 [onPoke] 数一下。
 * 版面按 360×720 排好再整体缩放，手机、平板、横屏上错位的样子都一样。
 * [blink] 由外面每 600 ms 翻一次（不到每秒一下，远低于每秒三下的闪烁上限）。
 */
@Composable
fun LaunchAdPoster(secondsLeft: Int, blink: Boolean, poked: Int, onSkip: () -> Unit, onPoke: () -> Unit, modifier: Modifier = Modifier) {
    val red = Color(0xFFFF1500)
    val yellow = Color(0xFFFFF000)
    val navy = Color(0xFF0000B8)
    val green = Color(0xFF00D83A)
    val magenta = Color(0xFFFF00C8)
    val cyan = Color(0xFF00F0FF)
    BoxWithConstraints(modifier.fillMaxSize().testTag("launch-ad-egg").background(yellow)
        .drawBehind {
            // 放射状的红黄条纹，从偏上的地方散开。
            val center = Offset(size.width / 2f, size.height * .36f)
            val reach = size.width + size.height
            val wedges = 28
            val path = Path()
            for (index in 0 until wedges step 2) {
                val a = 2.0 * PI * index / wedges
                val b = 2.0 * PI * (index + 1) / wedges
                path.moveTo(center.x, center.y)
                path.lineTo(center.x + reach * cos(a).toFloat(), center.y + reach * sin(a).toFloat())
                path.lineTo(center.x + reach * cos(b).toFloat(), center.y + reach * sin(b).toFloat())
                path.close()
            }
            drawPath(path, Color(0xFFFF7A00))
        }
        .pointerInput(Unit) { detectTapGestures { onPoke() } }) {
        val fit = min(maxWidth.value / 360f, maxHeight.value / 720f)
        Box(Modifier.align(Alignment.Center).requiredSize(360.dp, 720.dp).scale(fit)) {
            // 顶上的通知条。
            Box(Modifier.fillMaxWidth().height(34.dp).background(navy), contentAlignment = Alignment.Center) {
                Text("★★★ 系统紧急通知 ★★★", color = if (blink) yellow else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            // 大标题：红字，后面一层黄色的硬影子。
            Box(Modifier.offset(18.dp, 44.dp).rotate(-7f)) {
                Text("恭喜您中奖了!!", color = navy, fontSize = 44.sp, fontWeight = FontWeight.Black, modifier = Modifier.offset(4.dp, 4.dp))
                Text("恭喜您中奖了!!", color = if (blink) red else magenta, fontSize = 44.sp, fontWeight = FontWeight.Black)
            }
            Text("您是第 1000000 位幸运用户", color = navy, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.offset(56.dp, 122.dp).rotate(3f).background(Color.White).padding(horizontal = 6.dp, vertical = 2.dp))
            // 奖品：一个十六角的红星。
            Box(Modifier.offset(6.dp, 158.dp).size(170.dp).drawBehind {
                val star = Path()
                val points = 16
                for (index in 0 until points * 2) {
                    val radius = if (index % 2 == 0) size.minDimension / 2f else size.minDimension * .38f
                    val angle = PI * index / points
                    val x = center.x + radius * cos(angle).toFloat()
                    val y = center.y + radius * sin(angle).toFloat()
                    if (index == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                drawPath(star, red)
            }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.rotate(-12f)) {
                    Text("一等奖", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("999999 元", color = yellow, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("外加拖拉机一台", color = Color.White, fontSize = 11.sp)
                }
            }
            // 「立即领取」：歪着，比旁边的东西都大，一明一暗换颜色。
            Box(Modifier.offset(166.dp, 190.dp).rotate(6f).size(186.dp, 78.dp).background(if (blink) green else magenta)
                .border(5.dp, if (blink) magenta else cyan), contentAlignment = Alignment.Center) {
                Text("立 即 领 取 >>", color = if (blink) Color.Black else yellow, fontSize = 25.sp, fontWeight = FontWeight.Black)
            }
            Text("仅剩 3 个名额   仅剩 3 个名额", color = red, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(150.dp, 284.dp).rotate(-3f).background(cyan).padding(horizontal = 4.dp))
            // 第一个假对话框：病毒警告。
            Column(Modifier.offset(26.dp, 330.dp).rotate(-2f).width(300.dp).background(Color(0xFFC0C0C0)).border(3.dp, Color.White)) {
                Row(Modifier.fillMaxWidth().background(navy).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("病毒警告.exe", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("×", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.background(Color(0xFFC0C0C0)).padding(horizontal = 6.dp))
                }
                Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp)) {
                    Text("您的手机已中毒！！！", color = if (blink) red else Color.Black, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text("发现 99+ 个病毒，内存只剩 1%，电池正在融化", color = Color.Black, fontSize = 13.sp)
                    // 扫描进度：一格一格的绿块，停在 87%。
                    Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(18.dp).background(Color.White).border(2.dp, Color(0xFF606060))
                        .padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(17) { Box(Modifier.width(11.dp).height(12.dp).background(Color(0xFF00A000))) }
                    }
                    Text("正在扫描… 87%", color = Color.Black, fontSize = 11.sp)
                    // 三个按钮大小不一、高低不齐。
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(58.dp)) {
                        Text("立即查杀", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.offset(0.dp, 6.dp).rotate(-4f).background(red).border(3.dp, yellow).padding(horizontal = 14.dp, vertical = 8.dp))
                        Text("立即查杀", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.offset(132.dp, 0.dp).background(green).padding(horizontal = 10.dp, vertical = 5.dp))
                        Text("取消", color = Color(0xFF909090), fontSize = 8.sp,
                            modifier = Modifier.offset(236.dp, 40.dp).border(1.dp, Color(0xFF909090)).padding(horizontal = 3.dp))
                    }
                }
            }
            // 第二个假对话框，压在第一个上面。
            Column(Modifier.offset(118.dp, 520.dp).rotate(5f).width(226.dp).background(Color.White).border(4.dp, magenta)
                .padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("检测到您的手机运行太快", color = navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("点击下方按钮一键减速", color = Color.Black, fontSize = 12.sp)
                Text("确定", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 6.dp).offset(38.dp, 0.dp).background(navy).padding(horizontal = 22.dp, vertical = 4.dp))
            }
            // 到处都是的假关闭钮。
            for ((x, y, size) in listOf(Triple(6, 40, 18), Triple(332, 150, 22), Triple(8, 498, 26), Triple(318, 486, 14))) {
                Box(Modifier.offset(x.dp, y.dp).size(size.dp).background(Color(0xFF404040), CircleShape).border(1.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("×", color = Color.White, fontSize = (size * .6f).sp, fontWeight = FontWeight.Bold)
                }
            }
            // 底下一行滚动字样；点过以后老实交代。
            Text(if (poked > 0) "点了 $poked 下，什么都不会发生" else "今日已有 9999 人领取！！！机不可失！！！",
                color = if (blink) magenta else navy, fontSize = 16.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.offset(0.dp, 626.dp).fillMaxWidth().background(cyan).padding(vertical = 5.dp).testTag("launch-ad-egg-ticker"))
            // 这一行是真话，字小但看得清。
            Text("彩蛋：这是画出来的假广告。CyIME 没有广告，这一页不联网、不收集任何东西。在「关于 CyIME」里可以关掉。",
                color = Color.White, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.offset(0.dp, 668.dp).fillMaxWidth().background(Color(0xD9000000)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        // 真的跳过：不跟着版面缩放，永远在右上角，随时能点。
        Text(if (secondsLeft > 0) "跳过 $secondsLeft" else "跳过", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).background(Color(0xCC000000), RoundedCornerShape(50))
                .clickable(onClick = onSkip).padding(horizontal = 14.dp, vertical = 7.dp).testTag("launch-ad-egg-skip"))
    }
}
