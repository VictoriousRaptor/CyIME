<h1 align="center">CyIME</h1>

<p align="center">
  <a href="README.en.md">English</a> · <a href="README.zh-TW.md">繁體中文</a>
</p>

**CyIME** 是 Cyletix 开发的 Android 输入法，基于 [Rime](https://rime.im/) 引擎。中文拼音、双拼、五笔、笔画、仓颉、注音，日语、英文、西班牙语，手写、语音、拍照识字都在本机完成，另有给听障人士用的实时字幕。

## 想做什么

我想做出世界上最好用的开源输入法：最好的词库、最好的算法、最好的性能优化、最全面的多模态辅助输入、最好看的主题、音效、震动，最合理的排布，最全面的输入法，以及仅离线就能使用所有完整的服务。

## 亮点

- **不联网也能全部用**：词库、手写、语音识别、拍照识字、实时字幕都在手机上运行，完整版装上就能离线用。
- **自己训练的九键模型**：九键用 CyIME 自己训练的词组模型，长句一口气打完，逐键选词。
- **一个键盘，多种打法**：全拼 26 键、九键、14 键；六种内置双拼，还能自建方案；五笔、笔画、仓颉、注音；日语、英文、西班牙语。
- **手写一次写好几个字**：中文、英文、日语连写，数学公式直接识别成 LaTeX，支持 S Pen。
- **给听障人士的实时字幕**：把周围的话转成字幕，自动区分说话人，通话时也能用，有人叫你名字时振动。
- **想怎么放就怎么放**：固定、悬浮、分体三种放法，悬浮键盘能拖动、缩放、旋转；多套配色、玻璃效果、自己的图片做背景。

## 下载与安装

要求 Android 9.0（API 28）及以上，安装包只有 ARM64。

1. 在 [Releases](https://github.com/Cyletix/CyIME/releases) 或[魔搭](https://www.modelscope.cn/models/Cyletix/cyime-model/files)（国内，`app/<版本>/` 文件夹）下载安装包，两个包源码相同，只差带不带离线模型：
   - **轻量版** `CyIME-<版本>-arm64-v8a.apk`：不带语音、手写、联想模型，需要时在「扩展 › 模型」下载。
   - **完整版** `CyIME-<版本>-full-arm64-v8a.apk`：内置 SenseVoice 语音、手写、声纹、联想模型和九键词组模型，装上就能离线用。边说边出字的 Paraformer 不内置，需要时在应用里下载。
2. 安装后在系统设置里启用 **CyIME**，并把它设为当前输入法。
3. 以后在「设置 › 关于 › 检查更新」更新，会下载和现在同一种（轻量／完整）的安装包交给系统安装，国内先从魔搭下载。

**安装失败时，先看系统安装器弹窗的原文。**

- **「安装包缺乏开发者证书」或「未包含任何证书」**　华为、三星、vivo 上都有人遇到过。安装包本身签名没问题，原因还在查。用户试过管用的办法有两个：删掉已下载的安装包，重新下载再装，可能要多试一两次；或者用 MT 管理器打开安装包安装。[官方发布文件](https://github.com/Cyletix/CyIME/releases/latest)
- **「不允许此来源安装应用」**　在手机设置里搜索「安装未知应用」，只给实际打开官方 APK 的浏览器或文件管理器开启安装权限。[vivo 安装说明](https://kefu.vivo.com.cn/robot/imgmsgData/e61815a2c963482ebbaf71a4adebb160/index_1.html)
- **「与已安装应用签名不一致」**　保留旧版和词库，先核对旧版的下载渠道。覆盖更新要求签名兼容，卸载会清除应用数据。[Android 应用签名说明](https://developer.android.com/studio/publish/app-signing)

如果官方包仍被拦截，请附上**系统安装失败弹窗**、机型和系统版本、APK 文件名和下载来源，并注明是首次安装还是覆盖更新。[反馈安装问题](https://github.com/Cyletix/CyIME/issues)

> 带谷歌服务的手机（三星、海外版机型等）从 2027 年起安装未在谷歌登记的应用要先开一次「高级安装」，国行手机和 adb 安装不受影响，见 [谷歌开发者验证与安装](docs/android-developer-verification.md)。

> 从旧版 `com.kingzcheung.xime` 迁移：安装身份已独立为 `com.cyletix.cyime`，系统当作新应用，可以并存，旧版数据不会自动继承。

## 功能

下面写「默认关」的功能要在设置里自己打开。

**中文**

- 全拼 26 键、九键、14 键，词库用雾凇拼音；九键可下载词组模型和语法增强模型，长句逐键选词。
- 双拼：内置小鹤、首道、自然码、智能ABC、拼音加加、微软双拼，可以复制修改或新建自己的方案；每个双拼方案都有 14 键版本，小鹤、首道和自建方案另有九键版本（默认关）。
- 笔画、仓颉五代、仓颉速成、注音大千键盘随包，五笔 86、五笔 98 在「扩展 › 方案」下载（约 2 MB），都在「语言与方案」里启用。
- 繁体输出（按台湾用字，默认关）、模糊音（默认关）、生僻字（默认关）、中英混输（默认关）、中文滑行输入（默认关）。
- 候选可显示拼音或中英释义；26 键能纠正多打、少打的字母，纠错候选不抢第一位。
- 词库页：自己的短语、导入 TXT／搜狗／QQ／Rime 词库、删掉的候选、查词；带动漫、游戏、编程、数学等分类词库（默认关）。
- 选词用固定测试集检查：题目主要取自公开的短信和对话语料，只用来打分，不参与训练和调参。
- 改词库、解码或排序时，首选率和每键耗时都和上一版对比，任一项超出允许范围就不合入。

**其他语言**

- 英文：整词补全、滑行输入。
- 日语：罗马字 26 键和假名键盘，词库来自 rime-jaroomaji。
- 西班牙语：带 ñ、长按出重音字母、词表补全（默认关）。

**手写、语音、识字**

- 手写：中文、英文、日语（假名和汉字）、数学公式（识别成 LaTeX）；中文、英文、日语一次可以写好几个字，支持 S Pen。
- 语音：本地离线识别，新装默认 SenseVoice（带标点、整句定稿）；下载 Paraformer 后边说边出字；另可下载 Zipformer、X-ASR、日语模型。长按空格按住说话。也可以填自己的在线识别接口，测试通过才会用。
- 拍照识字：在剪贴板里拍照或选图，离线识别出文字。

**字幕**

- 把周围的话实时转成字幕，支持中文、英文、日语；自动分说话人，可以给说话人起名字。
- 后台浮窗、通话字幕、字幕记录（可搜索、分享，默认保留 3 天）、有人叫你名字时振动。桌面有单独的「字幕」图标。

**键盘与外观**

- 固定、悬浮、分体三种放法；悬浮键盘可以拖动、缩放、旋转；平板固定键盘可以从顶边把手拉成悬浮。
- 字母排列：QWERTY、Colemak、QWJRTK、Cyletix10、Typewise 风格蜂窝六边形，也可以自己排。
- 多套配色主题、玻璃效果、自己的图片做背景，按键形状可选圆角、方形、直角。
- 工具栏和菜单可以拖动排列；按键音、振动、滑动手势可调。
- 实体键盘：浮动候选栏、快捷键切换语言。

**剪贴板与同步**

- 剪贴板历史、固定、搜索、自定义分组，导出 TXT／Markdown／JSON；短信验证码在候选栏提示。
- 和 Windows 版 CyIME（还没公开）扫码配对后，两边剪贴板互相同步。
- 本地备份与恢复。

**设置**

- 设置搜索（认同义词、错别字、繁体字）、按设备推荐配置、性能档位。
- 扩展页可以下载输入方案和模型，也可以在插件市场按需安装 AI 翻译、帮写、智能回复和输入统计，或从文件导入 Lua 插件（`.xipk`）。

## 构建

```bash
# 克隆项目（含子模块）
git clone --recursive https://github.com/Cyletix/CyIME.git

# 已克隆过则初始化子模块
git submodule update --init --recursive

# 调试包 / 发布包
./gradlew assembleDebug
./gradlew assembleRelease

# 离线包（高敏感环境）：同发布包，但移除 INTERNET 权限，系统层面禁止联网
./gradlew assembleOffline
```

需要 JDK 17 和 NDK `29.0.14206865`。构建时会联网下载雾凇拼音、日语词库、笔画、仓颉码表和 sherpa-onnx 运行库，按记录的 SHA-256 校验后打进安装包。日常出包用仓库根目录的 `packager.cmd`（双击打开图形界面，带参数是命令行），预设见 `scripts/packager/config.json`；版本规则见 [打包与版本规则](docs/packaging.md)。

离线包 `assembleOffline` 在发布包基础上经 `app/src/offline/AndroidManifest.xml` 移除 `INTERNET` 权限，安装后由系统禁止一切联网：模型/词库下载、扩展商店、检查更新、在线语音、样本贡献、剪贴板同步均不可用，请配合完整版离线模型内置包使用，适合高敏感环境。

技术栈：Kotlin · Jetpack Compose · Material 3 · librime（JNI / C++）· ONNX Runtime · sherpa-onnx · OpenCV

源码包名 `com.kingzcheung.xime`、插件标识、`xime.yaml` / `xime.custom.yaml` 和扩展商店协议沿用早期命名，安装身份是 `com.cyletix.cyime`。取舍见 [命名与身份记录](docs/cyime-identity.md)。

## 相关项目

- 作者自己的前作：[Cyletix-Keyboard](https://github.com/Cyletix/Cyletix-Keyboard)（2024 年的键盘布局研究，Cyletix10 布局出自这里）、ASRInput（电脑上的语音字幕，字幕逐字出现的节奏沿用它）。
- 社区 Linux 版：[tuxKOH/CyIME 的 linux-desktop-preview 分支](https://github.com/tuxKOH/CyIME/tree/feat/linux-desktop-preview)（IBus 拼音 + 电脑剪贴板，tuxKOH 维护）。欢迎 Linux 版本的魔改，但由于本人精力有限，暂时不会维护 Linux 版本。

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)：先提 Issue，再提最小修改的 PR。

## 支持作者

CyIME 输入法是我空闲时间制作的一款输入法, 遵循GPL协议, 所以此项目永久免费开源, 并且此项目的任何fork项目也同样必须开源.

如果它帮到了你, 愿意的话可以请我喝杯咖啡, 用来支付agent开销 模型训练和个人测试的时间开销, 感谢支持, 不打赏也完全没关系, 欢迎反馈问题, 同样是很大的支持

我的[爱发电](https://afdian.com/a/cyletix)账号目前尚未通过认证。想支持我的话，也可以用支付宝扫描下方的个人收款码。

<img src="docs/images/alipay.jpg" alt="作者的支付宝个人收款码" width="240">

海外也可以通过 [Ko-fi](https://ko-fi.com/cyletix) 支持我。

## 鸣谢

CyIME 用到了下面这些项目、数据和模型，感谢作者们的工作。安装包里的许可证全文和版权声明见 `app/src/main/assets/NOTICE.txt`、`app/src/main/assets/licenses/` 和各方案目录下的 NOTICE 文件；应用里在「设置 › 关于 › 鸣谢」和「开源许可证」。

### 输入引擎

| 项目 | 作者 | 用在哪里 | 许可证 |
| --- | --- | --- | --- |
| [librime](https://github.com/rime/librime)（经 [ximeiorg/librime](https://github.com/ximeiorg/librime)） | 佛振（lotem）及 Rime 社区 | 输入引擎 | BSD-3-Clause |
| [librime-lua](https://github.com/hchunhui/librime-lua) | hchunhui | 方案里的 Lua 脚本 | BSD-3-Clause |
| [librime-octagram](https://github.com/lotem/librime-octagram) | 佛振（lotem） | 九键语法模型 | GPL-3.0 |
| [librime-predict](https://github.com/rime/librime-predict) | Rime | 联想 | BSD-3-Clause |
| [OpenCC](https://github.com/BYVoid/OpenCC) | BYVoid（Carbo Kuo） | 简繁转换 | Apache-2.0 |
| [Boost](https://www.boost.org)、[glog](https://github.com/google/glog)、[yaml-cpp](https://github.com/jbeder/yaml-cpp)、[LevelDB](https://github.com/google/leveldb)、[marisa-trie](https://github.com/s-yata/marisa-trie)、[Snappy](https://github.com/google/snappy) | 各自作者 | librime 依赖 | BSL-1.0、BSD-3-Clause、MIT、BSD-3-Clause、BSD-2-Clause 或 LGPL-2.1、BSD-3-Clause |

### 词库与码表

| 项目 | 作者 | 用在哪里 | 许可证 |
| --- | --- | --- | --- |
| [rime-wubi](https://github.com/kingzcheung/rime-wubi)（fork 自 [KyleBing/rime-wubi86-jidian](https://github.com/KyleBing/rime-wubi86-jidian)） | kingzcheung；KyleBing | 内置五笔 86 方案和手写的五笔反查 | Apache-2.0 |
| [雾凇拼音](https://github.com/iDvel/rime-ice) | iDvel（Dvel） | 全拼、小鹤双拼、九键、14 键的词库和方案 | GPL-3.0 |
| [万象语言模型](https://github.com/amzxyz/RIME-LMDG) | amzxyz | 九键语法增强模型（可下载） | CC BY 4.0 |
| [rime-stroke](https://github.com/rime/rime-stroke) | 四季的風、雪齋、Kunki Chou、宋天 | 笔画方案 | LGPL-3.0 |
| [Cangjie5](https://github.com/Jackchows/Cangjie5) | Jackchows；倉頡之友·馬來西亞 | 仓颉五代码表 | MIT |
| [rime-cangjie](https://github.com/rime/rime-cangjie)、[rime-bopomofo](https://github.com/rime/rime-bopomofo) | Rime | 仓颉、注音方案的写法参照 | LGPL-3.0 |
| [小麦注音](https://github.com/openvanilla/McBopomofo) | OpenVanilla | 台湾词、读音和词频（注音、繁体输出、仓颉排序） | MIT |
| [rime-jaroomaji](https://github.com/lazyfoxchan/rime-jaroomaji) | lazy fox chan | 日语方案和词库 | Apache-2.0；词库各自保留原许可：Mozc（BSD 风格）、JMdict / KANJIDIC2（EDRDG，CC BY-SA 4.0） |
| [搜狗细胞词库](https://pinyin.sogou.com/dict/) | 搜狗输入法及词库作者 | 应用内下载的分类词库，文件直接从搜狗下载 | 版权归原作者 |
| [rime-wubi98](https://github.com/cz-archive/rime-wubi98) | cz-archive | 五笔 98（应用内下载） | GPL-3.0 |
| [ECDICT](https://github.com/skywind3000/ECDICT) | skywind3000（Linwei） | 候选释义：英→中 | MIT |
| [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict) | MDBG | 候选释义：中→英 | CC BY-SA 4.0 |
| [opus-mt-zh-en](https://huggingface.co/Helsinki-NLP/opus-mt-zh-en) | Helsinki-NLP | 释义里常见短语的参考译文（在电脑上离线生成） | CC BY 4.0 |
| [FrequencyWords](https://github.com/hermitdave/FrequencyWords) | Hermit Dave | 西班牙语补全词表 | 代码 MIT；词表 CC BY-SA 4.0 |
| [Make Me a Hanzi](https://github.com/skishore/makemeahanzi) | Shaunak Kishore | 手写生僻字模板的笔顺、部件拆分（在电脑上离线生成特征） | 笔画数据 Arphic Public License；字典 LGPL-3.0 |
| [AnimCJK](https://github.com/parsimonhi/animCJK) | parsimonhi | 手写生僻字模板的笔顺（在电脑上离线生成特征） | graphics 文件 Arphic Public License |
| [cjkvi-ids](https://github.com/cjkvi/cjkvi-ids) | CJKVI；数据源自 CHISE | 手写生僻字按结构用部件拼笔顺（在电脑上离线生成特征） | GPL-2.0 |
| [ChaiPUA 字体](https://github.com/tansongchen/input) | 谭淞宸（tansongchen） | 显示五笔字根等扩展区字符 | 字体内未标明 |

### 语料（训练 CyIME 自己的模型）

| 语料 | 作者 | 用在哪里 | 许可证 |
| --- | --- | --- | --- |
| [LCCC](https://github.com/thu-coai/CDial-GPT)（Large-scale Cleaned Chinese Conversation） | 清华大学 CoAI 小组 | 九键词组模型、联想底表、26 键常用词词频 | MIT |
| [OpenSubtitles](https://opus.nlpl.eu/OpenSubtitles/corpus/version/OpenSubtitles)（OPUS） | opensubtitles.org；OPUS | 九键词组模型的字幕口语部分 | 未标明 |
| [Natural Language Corpus Data](https://github.com/norvig/pytudes) | Peter Norvig | 英文滑行的词频和词对频率 | MIT |
| [Cornell Movie-Dialogs Corpus](https://www.cs.cornell.edu/~cristian/Cornell_Movie-Dialogs_Corpus.html) | Cristian Danescu-Niculescu-Mizil、Lillian Lee | 英文滑行的口语词对 | 研究用公开数据 |
| [MathWriting](https://github.com/google-research/google-research/tree/master/mathwriting) | Google | 训练公式手写模型 | CC BY-NC-SA 4.0 |

### 语音、识字与推理

| 项目 | 作者 | 用在哪里 | 许可证 |
| --- | --- | --- | --- |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | k2-fsa（新一代 Kaldi） | 语音识别、断句、声纹运行库，以及各语音模型的 ONNX 导出 | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Microsoft | 手写、联想、识字等模型推理 | MIT |
| [Silero VAD](https://github.com/snakers4/silero-vad) | Silero | 语音断句 | MIT |
| [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) | 阿里巴巴 FunAudioLLM | 语音识别（多语言、整句定稿） | FunASR 模型许可（需署名、保留模型名） |
| [Paraformer](https://github.com/modelscope/FunASR) | 阿里巴巴达摩院 FunASR | 中英流式语音识别 | Apache-2.0 |
| [Zipformer 中文](https://github.com/k2-fsa/sherpa-onnx) | k2-fsa | 中文流式语音识别 | Apache-2.0 |
| [X-ASR zh-en](https://huggingface.co/GilgameshWind/X-ASR-zh-en) | GilgameshWind | 中文整句定稿（可下载） | Apache-2.0 |
| [ReazonSpeech k2 v2](https://huggingface.co/reazon-research/reazonspeech-k2-v2) | Reazon Research | 日语语音识别 | Apache-2.0 |
| [3D-Speaker CAM++](https://github.com/modelscope/3D-Speaker) | 阿里巴巴 3D-Speaker | 字幕区分说话人 | Apache-2.0 |
| [PaddleOCR PP-OCRv5](https://github.com/PaddlePaddle/PaddleOCR)、[RapidOCR](https://github.com/RapidAI/RapidOCR) | PaddlePaddle；RapidAI | 拍照识字模型及其 ONNX 版本 | Apache-2.0 |
| [slimt](https://github.com/jerinphilip/slimt) | Jerin Philip；David Ventura | 离线翻译引擎 | GPL-2.0 |
| [Firefox 翻译模型](https://github.com/mozilla/translations) | Mozilla | 离线翻译（中英互译，可下载） | MPL-2.0 |
| [OpenCV](https://github.com/opencv/opencv) | OpenCV | 拍照识字的图像处理 | Apache-2.0 |
| [KaTeX](https://github.com/KaTeX/KaTeX) | KaTeX 作者 | 显示手写公式 | MIT |
| [UER-py](https://github.com/dbiir/UER-py) 中文 RoBERTa | UER 团队 | 九键整句打分（已停用，词表仍在包里） | Apache-2.0 |
| [ochwpro](https://www.modelscope.cn/models/bikeand/ochwpro)、[predictive-text-small](https://www.modelscope.cn/models/bikeand/predictive-text-small)、[predictive-text-base](https://www.modelscope.cn/models/bikeand/predictive-text-base) | bikeand（ModelScope） | 中文手写识别；智能联想 | ochwpro MIT；small Apache-2.0；base CC BY-NC-SA 4.0 |

### Android 库

[Kotlin](https://kotlinlang.org)、[Jetpack Compose 与 AndroidX](https://developer.android.com/jetpack/androidx)（含 CameraX、Room）、[Material Components](https://github.com/material-components/material-components-android)、[OkHttp](https://github.com/square/okhttp)、[Coil](https://github.com/coil-kt/coil)、[kaml](https://github.com/charleskorn/kaml)、[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)、[ZXing](https://github.com/zxing/zxing)（配对二维码）、[exp4j](https://github.com/fasseg/exp4j)（计算器）、[Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) 均为 Apache-2.0；[LuaJ](https://github.com/luaj/luaj)（插件运行时）MIT；[sora-editor](https://github.com/Rosemoe/sora-editor)（方案文件编辑器）LGPL-2.1。

### 设计参考

CyIME 的交互、外观和键位参考过下面这些产品和方案：

- [三星键盘](https://www.samsung.com/)：键盘整体的交互和外观。
- [Gboard](https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin)：键盘交互和外观，比如大屏工具栏样式。
- [Typewise](https://www.typewise.app/)：蜂窝六边形布局。
- [Colemak](https://colemak.com/)（Shai Coleman）：Colemak 字母排列。
- [首道双拼](https://sspai.com/post/108949)：首道双拼键位。
- 小鹤双拼（[小鹤音形](https://flypy.cc/)）、自然码、智能ABC、拼音加加、微软双拼：内置双拼键位。
- [同文输入法](https://github.com/osfans/trime)、[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)：Android 上 Rime 输入法的做法，用来对照。

## 许可证

[GPLv3](LICENSE)。CyIME 自 2026-09-21 起基于 [Xime](https://github.com/ximeiorg/Xime)（GPL-3.0）独立开发，源文件中原有的版权声明照常保留。第三方组件、数据和模型按各自的许可证分发，见上面的鸣谢和 `app/src/main/assets/NOTICE.txt`。注意智能联想 base 模型（CC BY-NC-SA 4.0）和公式手写模型（训练数据 MathWriting 为 CC BY-NC-SA 4.0）不能用于商业用途。
