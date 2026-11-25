package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 「导入词库」的格式说明：怎么导入、认哪几种文件、每种长什么样。内容按 ScelDictionary、TxtCellDictionary、
 * JsonCellDictionary 实际能读的写，改解析规则时这里一起改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CellDictionaryImportGuide(onDismiss: () -> Unit, onImport: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        modifier = Modifier.windowInsetsPadding(SheetOuterInsets),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        contentWindowInsets = sheetContentInsets) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 24.dp).padding(bottom = 16.dp).testTag("cell-dictionary-import-guide"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("导入词库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            GuideHeading("怎么导入")
            GuideText("1. 先把词库文件存到手机上：搜狗词库网站下载的、微信或 QQ 收到的文件、用数据线从电脑拷过来的都可以。")
            GuideText("2. 点「导入文件」，在文件选择器里找到它。")
            GuideText("3. 导入后自动打开，点底部「应用更改」生效。")

            GuideHeading("支持的格式")
            GuideFormat("搜狗细胞词库(.scel)", "搜狗词库网站下载的文件，直接导入。")
            GuideFormat("文本词库(.txt)",
                "每行一个词，词和拼音用空格、Tab 或逗号隔开。各输入法导出的文本一般可以直接导入。")
            GuideExample("你好 ni hao\nnihao 你好\n'shang'hai 上海\n计算机\tji suan ji\t100\nTeam Spirit")
            GuideText("没有汉字的行当作英文词，# 开头的行跳过。")
            GuideFormat("笔画词库(.txt)", "每行「词 笔画编码」，编码只用 h s p n z(横竖撇点折)，每字首笔、整串笔画或用 ' 隔开都行。" +
                "这些词只给笔画输入的分词打词用，拼音打不出来。")
            GuideExample("骆驼 zz\n猫娘 pzpshhsszhsh'zphnzhhzpn")
            GuideFormat("Rime 词典(.dict.yaml)", "每行「词⇥拼音⇥权重」。")
            GuideFormat("Gboard 个人词典(.zip)", "Gboard 导出的压缩包直接导入。")
            GuideFormat("微软拼音(.dctx)", "微软拼音导出的词库文件，声调去掉后导入。")
            GuideText("搜狗 .bin 和微软拼音 .dat 读不了，请先在原输入法里导出成文本。")
            GuideFormat("JSON(.json)", "CyIME 自己的格式，拼音按字用空格隔开：")
            GuideExample("{\"format\": \"cyime.dictionary.v1\", \"name\": \"我的词库\",\n" +
                " \"language\": \"zh\", \"scheme\": \"pinyin\",\n" +
                " \"entries\": [{\"word\": \"你好\", \"code\": \"ni hao\"}]}")

            GuideHeading("限制")
            GuideText("文本和 Rime 词典最多 200 万个词，搜狗词库和 JSON 最多 25 万个词。单字会跳过；形码只认笔画，五笔等形码词库不能从这里导入。")

            Button(onClick = { onDismiss(); onImport() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .heightIn(min = 48.dp)) { Text("选择文件导入") }
        }
    }
}

@Composable
private fun GuideHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun GuideText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GuideFormat(name: String, detail: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        GuideText(detail)
    }
}

/** 示例原样显示，等宽、不折行，长行横着滑。 */
@Composable
private fun GuideExample(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text.replace("\t", "    "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
    }
}
