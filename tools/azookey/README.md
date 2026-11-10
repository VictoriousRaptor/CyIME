# azooKey 日语转换库

日语 26 键和九宫格的假名汉字转换由 azooKey（AzooKeyKanaKanjiConverter）完成。Rime 仍然管输入串、分段、候选栏、部分选词和回车上屏；
`app/src/main/jni/cyime_azookey/azookey_translator.cc` 是一个 Rime 翻译器，运行时 `dlopen("libCyAzooKey.so")` 调本目录的 C 接口。

- 上游：https://github.com/azooKey/AzooKeyKanaKanjiConverter ，提交 `25a6346bf76931c1cf2214015b808875ea82f4bf`（MIT）。
- 词典：https://github.com/azooKey/azooKey_dictionary_storage ，提交 `4d418525b090cf49c219819d05a7e3cc2a4346eb` 的 `Dictionary/`（Apache-2.0），
  由 `app/build-logic/tasks-japanese.gradle.kts` 构建时下载、按内容哈希校验，打成 `azookey/dictionary.zip` 放进 APK；
  手机上 `JapaneseSchemas.installAzooKeyDictionary` 解压到 `rime/azookey/dictionary`，学习记录在 `rime/azookey/memory`。
- 补充词：azooKey 词典缺的 Mozc 开源词典名词（约 40 万条：人名如 暁山、地名、机构名、普通名词，不收动词形容词和纯假名词）
  由 `build-dictionary.sh` 并进词典的 `louds/`，打成 `azookey-louds-mozc-<哈希前 8 位>.zip`；构建时用它替换上游 `louds/`
  （`charID.chid` 除外），整份词典的内容哈希记在 `azooKeyMergedDictionaryHash`。
- `azookey-lite.patch`：去掉 Foundation/ICU（换成 FoundationEssentials 和 `Sources/FoundationLite` 小垫片），不带 Zenzai 神经模型；
  arm64 库从 64 MB 降到 18 MB（压缩后约 6.3 MB）。另把一处调试 `print` 改成 `debug`。
- `CyAzooKey/`：C 接口（`cyakkc_open/query/learn/end/clear_learning/free`），并在启动时把助词は/へ/を按读音 wa/e/o 再登记一份。

## 构建

需要 Swift 6.2.0（必须正好这个版本）和 swift-6.2-RELEASE-android-24-0.1 安卓 SDK：

```sh
sh tools/azookey/build-android.sh   # 输出 work/out/<abi>/libCyAzooKey.so 和 SHA-256
```

可用 `ABIS=arm64-v8a JOBS=2` 只编译 ARM64 并限制并发。fork 的 Release 工作流会先从这些固定源码构建库和补充词典，
再由 `scripts/ci-native-properties.py` 检查源码提交、ELF 架构及输出哈希，生成本次构建的 Gradle 校验参数。
词典 ZIP 因压缩器版本产生的字节差异允许单独记录；合并后词典的内容哈希仍须与仓库固定值一致。

更新库后把 SHA-256 写进 `tasks-japanese.gradle.kts` 的 `azooKeyLibraryHashes`，文件按 `libCyAzooKey-<哈希前 8 位>-<abi>.so`
上传到 GitHub `models` 发布和魔搭 `Cyletix/cyime-model`；构建时只认哈希一致的文件。
也可以把同名库直接放进 `app/build/generated/azookey-jniLibs/<abi>/libCyAzooKey.so`，哈希一致就不再下载。

补充词典（同一套 Swift 6.2.0，编电脑版）：

```sh
sh tools/azookey/build-dictionary.sh   # 输出 work/azookey-louds-mozc-<哈希前 8 位>.zip 和两个哈希
```

把打印的两个哈希写进 `tasks-japanese.gradle.kts` 的 `azooKeyLoudsZipHash`、`azooKeyMergedDictionaryHash`，zip 和库一样放进本地目录或上传。
`azookey-lite.patch` 带的 `SuppTool` 能把词典 `louds/` 导出成 TSV、再从 TSV 建回；原样导出再建回与上游文件逐字节相同。
权重：两边都有的词按 Mozc 词性 id 取 azooKey 最常见的 (lcid, rcid, mid)，按 Mozc 成本线性拟合 azooKey 分值，再减 1，排在同频的原有词后面。

32 位 x86 没有 Swift 目标：该 ABI 不带库，日语只显示罗马字，不转换。

改了库或词典后，用电脑版库重跑 `readings.py` 更新 `app/src/test/resources/azookey_readings.tsv`。
测试与对比结果见 `docs/development/japanese-azookey-20261102.md`。
