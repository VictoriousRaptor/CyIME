# 光标放大镜审查与修正（2026-11-11）

## 范围与行为契约

- 分支 `codex/space-cursor-magnifier`，基线 `30c5660`；包括 `85324f5`、`6247cc2` 和本轮开始前已有的放大镜毛玻璃、底部留白及面板手势修改。
- 不修改或提交独立的 `tools/image-input-probe/`，不修改本机 AGENTS 覆盖文件，不向上游仓库写入。
- 关闭放大镜时不读取预览、不请求放大镜布局信息、不轮询、不创建文字测量缓存或窗口；预编辑栏不额外跟踪坐标。保留原有移光标行为。
- 输入视图结束或输入法窗口隐藏时，停止放大镜跟踪和读取、移除 Popup 与模糊窗口；迟到结果不能重新显示，重新展开不能恢复上次手势。
- 仅显示实际光标所在布局行，包含无换行符的软折行；最多取光标两侧各四个 Unicode 字符，保留代理对。不能用字号或行宽估算代替真实行边界。
- 维持宽度设置的现有 10–480 dp 范围，本轮不提前调整用户计划后续修改的上限。

## 审查结论（修改前）

| 问题 | 证据与影响 | 最小修正方向 |
| --- | --- | --- |
| 关闭后预编辑栏仍维护坐标 | `KeyboardView` 始终传入 `onCaretBounds`；`PreeditEditorBar` 无条件保存 `LayoutCoordinates`，重组与布局时计算并分配坐标对象 | 仅在确实需要预览时启用回调、effect 和定位 modifier |
| 弹出式预编辑栏坐标原点错误 | Popup 内 `localToRoot` 加上 Popup 外 `LocalView` 的屏幕原点；已有位置测试仅覆盖 inline | 改用布局坐标直接转换到屏幕，补 Popup 情形 |
| 隐藏生命周期未直接控制组合 | `onFinishInputView` 只停止服务跟踪，`onWindowHidden` 没有放大镜专用清理；不能依赖隐藏必然销毁 Compose 或必然结束语音会话 | 输入视图可见状态直接约束放大镜组合，并在隐藏回调清空跟踪与临时几何信息 |
| 只识别显式换行 | `CursorMagnifierText.from` 搜索换行符，不知道编辑器的实际布局行 | 内部使用 `Layout` 行偏移，外部使用真实宿主布局信息；预编辑栏使用自己的实际单行布局 |
| 预览窗口过大且选区读取不受限 | 每侧 64 UTF-16 单元；`getSurroundingText` 包含完整选区，旧协议先 `getSelectedText` 判空；每 80 ms 重复读取 | 缩小到每侧四字符，复用已有选区/拖动快照，避免为预览反复获取长选区 |

主体窗口复用及 `DisposableEffect` 清理结构可保留；服务中的少量布尔值、时间戳和回调不值得引入新管理层。现有毛玻璃窗口类、移光标和设置框架继续复用，避免无关重构。

## 分工与验证标准

1. 子代理 A：预编辑栏的按需坐标跟踪、Popup 定位和 KeyboardView 可见性门控，包含相关渲染回归。
2. 子代理 B：外部实际布局行解析与小范围读取，补软折行、布局信息缺失及长选区测试。
3. 子代理 C：每侧四字符裁剪及文本边界回归。
4. 主代理：服务生命周期、内部编辑器真实布局、已有拖动快照集成，独立复核、统一运行检查、查看渲染、按功能提交并推送用户 fork。

并行修改按文件/代码区域分工；只运行一个构建或测试进程，不由子代理提交或推送。用户本轮明确要求多子代理修改、测试、提交和推送，实施阶段遵循此授权。

验证必须区分代码路径、实际测试、渲染与设备验收。重点包括：关闭后不启动读取或几何请求；保持手势时隐藏视图立即清理且不再读取；隐藏后迟到回复、重新显示和会话切换；软折行行首/行尾及反向选择；每侧四字符与 emoji；内嵌/Popup 预编辑栏；已有手势、设置与毛玻璃生命周期回归。

## API 依据与边界

- [InputConnection.getSurroundingText](https://developer.android.com/reference/android/view/inputmethod/InputConnection#getSurroundingText(int,int,int)) 返回前后文本及整个选区，前后请求长度不能限制选区载荷。
- [InputConnection.requestTextBoundsInfo](https://developer.android.com/reference/android/view/inputmethod/InputConnection#requestTextBoundsInfo(android.graphics.RectF,java.util.concurrent.Executor,java.util.function.Consumer)) 从 Android 14 提供，区域使用屏幕坐标；请求应限定到光标附近，避免整篇布局及 IPC 成本。
- [TextBoundsInfo.Builder.setLineSegmentFinder](https://developer.android.com/reference/android/view/inputmethod/TextBoundsInfo.Builder#setLineSegmentFinder(android.text.SegmentFinder)) 的行分段应同时包含编辑器软折行。可用时使用真实行偏移，不能把缺失几何信息当成已经验证了当前行。

## 实施与检查回执

### 实际修正

- 关闭/隐藏时，底部留白和面板手势专用状态、预编辑坐标状态、定位 modifier 和 effect 不进入组合；原有空格移光标覆盖层保持原逻辑。
- 服务在 `onFinishInputView`、`onWindowHidden`、`onFinishInput` 和销毁时同步禁止放大镜跟踪，清空行与锚点引用，UI 随后移除 Popup 和模糊窗口。可见性代数记录被同一帧合并的隐藏/显示回调，旧手势和旧读取不会在重显后复活。
- 预编辑坐标跟踪移入实际内容组合，使用 Popup 或内嵌栏自己的 `LocalView` 屏幕原点。仓库实际原生宿主仅做平移/透明度过渡；Compose 内部的悬浮旋转由 `localToRoot` 计入，不引入新的矩阵管理层。
- 内部 `EditText` 直接读取 `Layout` 的实际行起止偏移；外部 API 34 在光标中心 2×2 像素区域请求行分段，160 ms 超时且可取消；较旧宿主仅在字符坐标实际证实同一行时显示相应片段。
- 仅实际显示的单行预编辑条可直接使用预编辑文本；正文内的预编辑经过正常宿主布局读取，不能绕过软折行限制。
- 布局请求要求坐标选区包含当前活动光标，避免快速跨行时把新光标配到旧行。只在手势期间缓存行范围和宿主锚点信息，新锚点到达即失效，隐藏时清空。
- 预览按码点最多取每侧四字符；宿主每侧请求八个 UTF-16 单元，兼顾代理对。长选区首先复用现有移光标快照，不另建全文缓存；缺失快照时在请求前拒绝超过 16 单元的选区，不再调用 `getSelectedText` 仅做判空。
- 现有毛玻璃、手势、设置和 10–480 dp 范围保留；未修改按键布局、候选排序、引擎或版本号。

### 检查过程

验证沿用 `.codex-artifacts/symbol-cycle/verify.init.gradle`，运行 `:app:testDebugUnitTest -x :app:preBuild --offline --no-daemon --console=plain`，不构建原生引擎或 APK。过滤范围包含所有 CursorMagnifier/PreeditMagnifier 测试，以及偏好往返、搜索、EditorCursor、实体键盘锚点、行宽估计、光标步长/手势、平台 API 门禁和玻璃材质回归。

- 首轮应用及测试编译通过，262 项中两项失败。API 30 用例错误地调用不存在的 `getSurroundingText` 来验证“未调用”；已改为验证两个已存在的有界读取及无额外交互。另一项 Popup 移动断言用于进一步定位原点问题。
- 随后新增快速跨软折行旧坐标、同帧隐藏重显和正文内预编辑路径回归。新增可见性代数时遗漏 Compose import，编译检查发现后立即补齐，日志保留为 `epoch-import-compile.log`。
- 定向检查 50 项，仅 Popup 移动断言仍失败。进一步记录证实窗口是 300×44，随父锚点从 y=254 移至 y=294；Compose/Robolectric 的 `boundsInWindow` 却额外减去了该偏移。最终使用实际所属 View 原点的实现，并以独立的 `positionInRoot + owner screen origin`、窗口参数和真实 40 像素移动共同验证，保留非零窗口偏移断言。
- 全部过程日志、原始失败 XML、最终结果、源码清单和渲染位于 `.codex-artifacts/magnifier-review/`，最终检查结果如下。

### 最终结果

- 最终应用及测试 Kotlin 编译通过，27 个测试类共 266 项，失败、错误、跳过均为 0。入口与过滤范围沿用上述完整针对性回归；日志 `final-tests.log`，原始 XML `final-test-results/`，汇总 `final-test-summary.json`。
- 已检查最终渲染 `soft-wrapped-current-line.png`：没有换行符的文本实际折行，胶囊仅显示第二布局行中光标附近的 `KL|MNOP`，没有拼接前后行；行内最大每侧四字符。
- 已查看 `host-inline-preedit.png`、`preedit-caret.png`、`glass-space-hold.png` 和深浅色玻璃胶囊渲染。正文内预编辑经过宿主 reader 的接线回归通过；内嵌及 Popup 预编辑栏的非零原点、40 像素移动和按需停用坐标跟踪检查通过。
- 完整回归同时覆盖长按空格、底部留白、26/14/9 键、数字与符号面板、悬浮键盘、密码框、设置关闭、宽度归整、会话切换、迟到回复、模糊窗口复用/清理和系统模糊不可用回退。
- 最终空间检查：C: 可用约 265.10 GiB，仓库生成物约 2.7 GiB，满足 100 GiB 保留空间、5 GiB 任务余量和 160 GiB 生成物上限。没有新增算法实验、模型或 APK；失败日志及测试证据保留。
- 本轮放大镜相关源代码、回归测试和本报告一并按同一功能提交，目标为用户 fork 的 `codex/space-cursor-magnifier`；独立图片输入探针未纳入。提交及远端一致性回执另存 `.codex-artifacts/magnifier-review/delivery.json`。

### 验证边界

- 没有真实行排版信息的外部输入框隐藏预览，不按换行符或估算字号伪装软折行结果；旧宿主可只显示有实际字符坐标支持的片段。
- 已发出的同步宿主文本读取不能由协程取消立即中断；隐藏后不再发起后续读取，也不接纳其返回结果。异步布局等待会取消，超时或迟到的回调不能恢复窗口。
- 本轮验证应用/测试 Kotlin 编译、宿主单元测试、真实 Compose 与系统窗口管理模拟及渲染；未运行真机堆内存/CPU/GPU 采样，真机及用户未验收。
- 遵守当前批次明确「打包」后再生成 APK 的约定，本轮不打包、不安装，版本保持原值。
