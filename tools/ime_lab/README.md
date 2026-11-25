# 输入质量离线工具

候选注释回归：`host_replay/CandidateCommentProbe.java` 使用同源 JNI，依次检查 26 键拼音、14 键拼音、两种小鹤双拼布局和五笔编码。与 `android_replay/RimeEngine.java` 一起编译；运行参数依次为主机构建的 `.so` 目录、**隔离测试资源目录**。资源必须先经当前 `prepareChineseDictionaries` 生成，使用副本，并在副本的 schema_list 启用上述五种方案；测试会部署该目录，禁止指向个人词库。验证的是最终经过过滤器的候选注释，不以 UI 开关或生成配置代替结果。本轮复现和证据见 [设置接线复查](../../docs/development/settings-connection-audit-20261004.md)。


- `ime_lab.py evaluate cases.json samples.jsonl output.json`：精确输入校验、TopK、类型验证和缺失项；延迟按用例与设备/构建/场景元数据分组。first-query 不等于进程冷启动。不能将引擎+Top100耗时称为端到端延迟。
- `neighbors.alternatives(text, keys)`：使用每个布局实际测得的按键矩形，限定一次替换、有距离惩罚、原输入首位、最多16个候选；当前仅离线实验，不改变正在使用的键盘。


真实引擎采样：`InputQualityProbeTest` 在独立目录部署随包词典，不导入个人 userdb。输出保存在应用 files/ime-lab/samples-*.jsonl，含实际类型、分数、global index及匹配范围。T9-014 没有原始按键，保持缺失。

## 按需持续获取词库（Cline / DeepSeek）

`lexicon_source.py` 支持易错词、多音字语境、易误读词及任意领域词汇。这里的“持续”是可重复、可增量执行：不会创建定时任务或无限循环。每次默认 1 批、32 条，最多显式指定 20 批、每批 128 条；超时/失败立即停止，不切换其他供应商。若一批没有新词，也停止剩余批次，避免重复消耗。

初次准备独立环境（Windows）：

```powershell
python -m venv D:/CyIME-Data/lexicon-generation/.venv
& D:/CyIME-Data/lexicon-generation/.venv/Scripts/python.exe -m pip install -r tools/ime_lab/lexicon-requirements.txt
```

从仓库目录运行；下面把解释器存为普通变量，方便反复调用：

```powershell
$lexiconPython = 'D:/CyIME-Data/lexicon-generation/.venv/Scripts/python.exe'
& $lexiconPython tools/ime_lab/lexicon_source.py collect --collection zh_reading --topic '常用中文多音字与易误读词，覆盖行、长、重、乐、差、处、数、得' --kind mixed --count 32 --batches 2
& $lexiconPython tools/ime_lab/lexicon_source.py collect --collection zh_confusable --topic '常见错别字词语、成语，正确形式及常见误写' --kind confusable --count 32
& $lexiconPython tools/ime_lab/lexicon_source.py collect --collection computer --topic '计算机、软件开发与人工智能常用中文术语' --kind word --count 32
& $lexiconPython tools/ime_lab/lexicon_source.py status
& $lexiconPython tools/ime_lab/lexicon_source.py export --collection zh_reading --title '常用多音字与易误读词'
```

同一个 `collection` 再次运行会增量去重；同词不同读音分开保存；同一词码可属于多个主题。后续批次提示中带该集合最近 500 个已有词，数据库仍会对全部词码去重。生成不是“真实新词发现”或“频率测量”，可能重复、遗漏或给错读音，因此不使用模型自述置信度，也不按重复生成次数抬高候选权重。

默认使用已在本机完成真实采集的 `deepseek-v4-pro`，可用 `--model` 指定账户支持的其他 DeepSeek 模型；本轮 `deepseek-flash` 请求多次超时，没有产出可用数据。默认只读取现有 Cline 的 DeepSeek 凭据并放入本次子进程环境，不把密钥放在命令行、提示词或本仓库文件中，不修改系统环境和原 Cline 配置。`--credentials env` 改为使用已有 `DEEPSEEK_API_KEY`；`--cline-data` 指定现有 Cline 数据目录。每次使用独立空工作目录及 Cline 状态，禁用工具自动批准，系统提示禁止文件、终端、浏览器和子代理操作；生成不读取仓库或聊天语料。Windows 复用系统 Cline 的 Node 启动器，保留其证书处理，且不经过命令字符串拼接。

默认数据放在 `D:/CyIME-Data/lexicon-generation`，可用顶层 `--root` 指定其他位置：

- `lexicon.sqlite3`：`runs` 保存模型、主题、时间、状态及提示/回复哈希；`entries` 按词语＋带调拼音去重；`memberships` 保存集合归属；`observations` 保留每批原条目、错误与模型声称的误写/误读；`reviews` 记录显式审校原因。
- `runs/<批次>/`：完整提示、Cline NDJSON 事件、错误日志、完整回复与入库计数。只提取成功完成事件的最终回复，不拼接流式增量或中间推理，不把超时半截 JSON“修复”成词库。失败批次保留，不伪装成已获取数据。
- `exports/<集合>/`：开放格式 `*.cyime-dict.json`、Rime `*.dict.yaml`、`manifest.json`（来源、批次、条目 ID 和文件哈希）及 `needs-review.json`。

回复协议见 [lexicon-reply.schema.json](lexicon-reply.schema.json)。生成器必须输出 `cyime.lexicon.v1` 的完整 JSON，每项包含正确词语、数字声调拼音、类型、释义、例句和独立的误写/误读列表。脚本另检查合法音节、每字一个音节、例句包含原词、长度及控制字符。原始模型回复不能直接导入 App。

词组存在于固定版本的 [pypinyin](https://github.com/mozillazg/python-pinyin) 词组库、且完整带调读音与首选读法一致，才标为 `reference_match`；不会因为每个字分别有某个读音，就把组合当成正确词音。未知词、异读或不一致的读音进入 `needs_review`，不导出。这个检查是独立读音参考，并非权威正字审校；释义和“易错”关系仍属于模型提议。默认补充权重为 100，不自动安装或启用。

需要人工/后续证据审校时：

```powershell
& $lexiconPython tools/ime_lab/lexicon_source.py review --id 123 --decision approve --reason '已对照指定词典核对，记录依据与语境'
& $lexiconPython tools/ime_lab/lexicon_source.py review --id 456 --decision reject --reason '模型把异义读音误用于此词'
```

重新采集不会推翻已有审校；审校后重新导出更新文件。若已无合格条目，导出会移除该集合上一次的 JSON/YAML 产物，只留下审校清单与零条目记录，避免误用旧文件。误写和误读仅存为独立研究数据，永远不会随候选词典导出，也未接入自动纠错。正常导出仅包含 `reference_match` 或明确 `approved` 的正确词码，保留 `v` 表示 ü、去声调后按词码再次去重。

把导出的 `*.cyime-dict.json` 传到手机，在“设置 → 词库管理 → 分类词库 → 导入词库（SCEL / JSON）”导入，选用后应用。App 校验格式版本、中文/拼音声明、词码及文件大小，仍使用现有词库独立开关，不自动改语言、布局或个人学习。新内容再次导出后作为新的本地词库导入，可关闭旧集合；当前不自动推送或替换手机上已启用的旧版本。

2026-11-22 起不再调用 DeepSeek：由 Claude 线程按上面的提示和 [lexicon-reply.schema.json](lexicon-reply.schema.json) 直接写回复，用下面的 `ingest` 入库（`--source` 写实际生成者），待审校条目同样逐条 `review` 写明依据；不使用、不保存任何 API 密钥。`collect` 保留，不再默认使用。

离线处理已经保存的模型回复：

```powershell
& $lexiconPython tools/ime_lab/lexicon_source.py ingest --collection zh_reading --reply D:/somewhere/reply.json --source '实际供应商/模型/批次标识'
```

针对性测试：`& $lexiconPython -m unittest discover -s tools/ime_lab -p test_lexicon_source.py -v`。无 pypinyin 的通用测试环境会跳过真实参考库用例，其余使用明确标注的假参考数据；假数据不能作为真实模型采集成果。

### 首批实际采集记录（2026-10-02）

使用本机 Cline 3.0.61，通过 DeepSeek `deepseek-v4-pro` 完成 4 批真实请求，共 64 个唯一词音条目，格式无效条目 0；之前 3 批 Flash 超时记录保留为失败。数据库在 `D:/CyIME-Data/lexicon-generation/lexicon.sqlite3`，没有发送私人聊天语料。

| 集合 | 已获取 | 读音参考匹配并导出 | 待审校 |
|---|---:|---:|---:|
| `zh_reading` 多音字与易误读词 | 24 | 18 | 6 |
| `zh_confusable` 易错词与成语 | 24 | 12 | 12 |
| `computer` 计算机常用词 | 16 | 1 | 15 |

例如“银行 / 行走”“长大 / 长短”“音乐 / 快乐”保留各自词组读音；“川流不息”“迫不及待”等导出规范词形。33 条待审校中，32 条在当前词组参考库中缺少完整记录，另 1 条“馄饨”的轻声与参考不一致；待审校不等于判错。专业新词自动通过率受参考覆盖限制，需要补充可追溯读音参考或逐项审校，不能单凭模型自评批量放行。

13 项 Python 测试通过（包括实际 pypinyin 参考检查）；第一轮 17 项 Android 词库 JVM 测试及设备测试代码编译通过，新增真实导出用例后 4 项 JSON 导入测试全部通过（零跳过），三份文件的全部 31 个词码经生产导入器转换后与 Python 导出的 Rime 词典一致。回执 `.codex-artifacts/generated-lexicon-validation.json`。仅验证采集、转换和导入处理，未打包、未安装；手机上候选排序、界面和 T9 性能未验收。

## 九键本机算法回归

后续九键选词测试统一使用 `python tools/ime_lab/t9_replay.py run --output <新结果目录>`。默认直接在电脑本地编译／复用现有 Rime、T9、候选策略和模型，不连接平板或手机，不打包安装。支持 `prepare` 从文本与拼音建立夹具，`--baseline` / `compare` 比较每键首选、目标名次和已审定异常；详细口径、退出码和复现条件见 [固定回归流程](../../docs/development/t9-algorithm-regression.md)。

调权与评分位置研究见[异常首选修复分析](../../docs/development/t9-weight-repair-study-2026-10-03.md)。`analyze_t9_weights.py` 对已保存候选做权重敏感性扫描；`probe_t9_scoring.py prepare/analyze` 配合 `host_replay/ScoreProbe.cc` 比较同一模型的四字评分位置；`host_replay/WeightExperiment.java` 用真实引擎检查独立配置副本的段末候选。这些诊断不替代完整逐键回归，也不自动修改生产配置。

### 全拼纠错与个人学习回归

`host_replay/PinyinCorrectionProbe.java` 使用同一生产 JNI，检查 `nawentishi` 等准确输入不会被邻键纠错挤走，同时检查正常学习词及 `nihso` 的纠错候选仍存在。每个按键都核对实际引擎输入，记录逐键候选，失败返回非零。

在 Linux/WSL 中，与 `android_replay/RimeEngine.java` 一起用 javac 编译，运行：

```text
LD_LIBRARY_PATH=<同源引擎库目录> java -cp <class目录> PinyinCorrectionProbe <同源引擎库目录> <隔离用户目录> [learn或check] [已部署的共享资源目录]
```

资源目录须包含同源 `rime_ice` 及生成资源，可复用本机测试准备的隔离目录，**不能指向个人实际使用的词库**。未指定共享目录时从用户目录读取资源；指定共享目录后可用空白用户目录检查全新学习状态。`learn` 会模拟提交 30 次 `bawentishi`，以重现个人学习与纠错的竞争；`check` 或省略时只回放/取消，不提交学习。应先在空白学习状态运行，再在合成学习状态运行；比较旧/新引擎时须使用相同的合成学习快照，不能把重新学习产生的权重变化混进算法对照。扩展句子检查只约束开启纠错不能破坏这些准确拼音的首选，不证明任意句子的语言质量合格。
