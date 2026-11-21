# Local Rime/T9 engine

The normal entry point is `python tools/ime_lab/t9_replay.py run`. Windows uses
WSL; Linux runs directly. No Android device, emulator, APK build or installation
is involved. See [the fixed workflow](../../../docs/development/t9-algorithm-regression.md).

`build_host.py --repo PATH --cache PATH` builds the existing production JNI,
candidate policy, patched librime and Lua/octagram/predict/T9 plugins for Linux
x86_64. It copies the native source into a private cache before the production
CMake patch/copy steps execute. Windows CRLF is normalized in that copy for Git
patch checks; the source tree is unchanged. `host_tail.cmake` replaces only the
Android application/JNI linking tail. `android/log.h` routes logs to stderr and
`FindBoost.cmake` keeps the vendored Boost targets instead of a system version.

The helper downloads pinned official CMake/Ninja wheels, the Linux ONNX Runtime
1.28 library and JNI headers into its cache, without a global package install.
It checks the ONNX C API against the production headers and records dependency,
source, adapter and library hashes. A Java runtime containing the compiler
module is sufficient; the helper provides a local `javac` launcher for that
module. Native compilation is limited to two jobs. No source algorithm is
reimplemented in Python, and the model is never disabled to make a run pass.

The host build configures `-DCYIME_T9_WORK_COUNTERS=ON` (Android leaves it off,
so its generated sources are unchanged) and links `host_replay_jni.cc`, which
exposes the per-key work counters from `librime-t9/src/t9_lookup_trace.h` and
restores a preset `*.userdb.txt` snapshot through librime's `UserDictManager`
before the session opens. `ChatReplay --host --user-dict FILE` uses both; an
engine library without these entry points still replays, without `work` rows.
`t9_host.py` runs the replay root under `<cache>/replay-roots/` (Linux
filesystem, so timing is not dominated by drvfs page-in) and moves it to
`native-run` afterwards; `--engine-from RUN` replays an earlier run's cached
engine and resources so baseline and candidate runs can alternate in one timing
session.

`ChatReplayHostChecks.java` exercises host-only path/flag guards. The replay
itself is shared with `../android_replay/ChatReplay.java` and invoked with
`--host --resources ZIP`; every run requires a new data directory. Resource ZIPs
contain the existing project dictionaries, schemas, grammar, sentence model and
vocabulary, with per-file SHA256 manifests. They are test inputs, not APKs.

For settings that regenerate spelling rules, pass the app's complete `t9_pinyin.custom.yaml` to
`t9_replay.py run --schema-patch-file FILE`. The host deploys it in the run's private directory
and saves its SHA256 and a copy alongside the deployed schema.

Build logs and artifacts live in the private cache, and each result directory
retains build/source manifests as well as raw replay evidence. A successful
build or replay does not claim that candidate quality passed; check quality and
comparison exit codes separately.

`MergedLetterProbe.java` additionally exercises full/double pinyin syllable
selection against the production JNI and deployed prisms. Compile it together
with `../android_replay/RimeEngine.java`; arguments are the built `library_dir`
and a separate test user-resource directory with `rime_ice`, `pinyin_14jian`,
`double_pinyin_flypy`, and `double_pinyin_flypy_14jian` enabled. Do not point it at
an installed application's data. It confirms a partial Chinese candidate to
check prefix preservation, so this is distinct from the no-selection T9 corpus
protocol. It never commits a full input or adds training phrases.
Syllables are chosen one after another as on the nine-key rail: after a choice
the native options move to the next syllable, the rail's delete key takes back
the last choice (`nativeUndoPinyinSyllable`), and confirming a word that ends on
a chosen boundary keeps the choices after it.

Pass `--single-key` after the two directory arguments to check a single `as` tap and exact-letter reselection in the two bundled 14-key schemes. The probe prints Chinese candidates and the native syllable options separately and checks both letter families, the composition caret and the absence of a confirmed prefix.

`MergedWordLearningProbe LIB USER learn` needs a freshly deployed, disposable 14-key user directory.
It selects two words through the real merged-key input path. Run again in a new process with `reopen`
to verify that the choices persist, an explicit final letter is respected and the literal alternative can be selected again.

The probe covers grouped/exact letters, real `lo`/`luo` shared codes, `xian`
versus `xi+an`, a chosen `ni` followed by `n`, reselecting the first unconfirmed
syllable, raw/caret/confirmed-text preservation, stale selections, suffix edits,
explicit delimiters, caret movement and clear/schema invalidation. Candidate
pronunciation checks use actual Chinese dictionary syllable IDs, not displayed
labels or another dictionary's English word IDs. This verifies one unconfirmed
syllable constraint; it does not claim nine-key-style chaining of several
syllable choices before selecting Chinese text.

`T9PartialClearProbe.java` (same arguments as `MergedLetterProbe`, run on a copy of a deployed replay
root's `user` directory) checks that ending a T9 composition after a partial choice starts the next
code fresh: 9447426926, choose 至少, clear, then 64 must read `64`. It also prints what a Rime-only
clear leaves, which is the 2026-10-23 report (old digits came back after Enter).

`T9FlickSentenceProbe.java` (same arguments) types a whole sentence as flicked letters (`womenquchifan`, the
letters go to Rime because the T9 digit buffer is empty), chooses 我们 and expects `quchifan` to remain, then
deletes the rest and checks that one more backspace brings back `women`. 2026-11-03 report: choosing the first
word committed only that word and the rest of the sentence vanished. It then types `wo`, taps 6 and flicks e, n:
the letters must stay (remaining digits `96636`, 我们 takes all of it); a tap used to replace the flicked letters.

`CustomPhraseReloadProbe.java` (same arguments, on a copy whose `default.custom.yaml` lists
`rime_ice`) adds a phrase to `custom_phrase.txt` while a session is open and reports which step makes
it appear. 2026-10-23: nothing until the schema is selected again; maintenance is not needed.

`com.cyime.audit.WeightExperiment LIB USER CASES OUTPUT SETTINGS [SCHEMA]` accepts an already
deployed, isolated nine-key scheme. The default is `t9_pinyin`. Use an empty JSON object for
`SETTINGS` when no numerical schema overrides are expected. The [phrase recall cases](../fixtures/phrase_recall_20261101.json)
cover both full pinyin and `double_pinyin_flypy_t9`; run once for each schema, with different output
paths. It records the literal digits and immediate and settled menus; a missing target or a timeout on a
case with `top_k` returns failure after writing the results. The transposition is synthetic; the user's
keystrokes were not recorded.

For independent full-pinyin coverage, generate the paired cases with
`python tools/ime_lab/typo_bench.py make-t9 OUT.json`, run them through the same probe, and pass the cases and its JSONL output to
`python tools/ime_lab/typo_bench.py score-t9 OUT.json RUN.jsonl`. Each sentence has one omitted
digit, one transposition, and two single-digit replacements at the same position. One replacement
shares an edge on the standard 123/456/789 pad and the other does not. Each rate uses only sentences
that ranked first when spelled correctly. This
endpoint diagnostic measures candidate recall; the fixed replay supplies the quality and latency gates.

`SpellingPauseProbe LIB USER NEIGHBORS` checks interior transpositions on the real letter and digit
key paths. On nine keys it also selects a shared-edge correction and checks that a diagonal tap, a
far tap, crossing a row boundary and two separate substitutions do not promote the target. Use an isolated user directory with the bundled
`rime_ice` and `t9_pinyin` schemes and the [nine-key trial patch](../../../docs/config_examples/t9_pinyin_spelling/t9_pinyin.custom.yaml)
already deployed. Use `typo_bench.py neighbors` for the last argument. The probe checks
the two protected candidates, rejection with a moved caret, the 26-key commit and
consumption of all original digits on nine keys. It selects candidates and belongs in
a disposable user directory separate from any no-learning corpus run.

`CaretCandidateProbe.java` (same arguments, on a copy whose `default.custom.yaml` lists `t9_pinyin` and
`rime_ice`) moves the code caret inside xu'yao'jia'ge to just after yao, the way the pinyin editor does,
and checks that 需要 is first rather than the whole 需要价格, and that choosing it leaves jia'ge with 价格
offered. 2026-10-30: both nine-key (9892654243, caret 5) and full pinyin pass on the engine; the
nine-key bug was the editor never moving the engine caret.

`ChaosInputProbe.java` (library dir, a deployed user copy listing `t9_pinyin`, `rime_ice` and
`pinyin_14jian`, seed, rounds) types seeded random sequences: half-typed codes, partial choices,
deletes and new keys, capitals/digits/punctuation inside pinyin, separators, space, the app's clear
or Enter followed by new keys. It fails on a thrown error, a code left after a clear, new keys that
join an old code, or backspace that cannot empty the code. It drives the engine the way the app
calls it, not the Kotlin keyboard code. 2026-10-23, seed 20261023 × 150 rounds: 15690 steps, none.

`LayoutSyncProbe.java` (library dir, a fresh user directory listing `rime_ice`, `t9_pinyin` and
`pinyin_14jian`, mode `all` or `rank`) checks that nine and 14 keys keep up with 26 keys. `all`: a
nine-key flick letter (`nativeT9FixLetter`, what a flick sends while a code is open) joins the code and
fixes that position instead of clearing it, and more digits continue the same code; custom phrases reach
nine keys and 14 keys through the keyed copies the app writes (`custom_phrase_t9`: digits,
`custom_phrase_k14`: pair capitals plus the letters); a candidate in `cyime-hidden-candidates.txt` stays
hidden on 14 keys; and the must-pass ranking list typed with pair capitals. `rank`: only the ranking list
and the hidden-candidate line, for comparing a 14-key schema before and after a change. 2026-10-30, without
the grammar file: the four flick cases, all 12 phrase lookups and the hidden check pass; the 11 ranking
words are first both with the old schema (no filters) and with `cyime_candidate_policy` + `uniquifier`, the
only difference being that the hidden 童话 is no longer offered.

`NineKeyFuzzyProbe.java` (library dir, a disposable deployed user directory with `t9_pinyin` and
`double_pinyin_flypy_t9`; compile with `LayoutSyncProbe.java` and the replay's `RimeEngine.java`) chooses
rail readings and then a word on full-pinyin and Xiaohe double-pinyin nine keys and checks what is left.
It includes two rail choices before the word (4942 → gx, hc → 挂号 must leave nothing; the rail holds
double-pinyin keys while the candidate comment is full pinyin).

`SharedShortcutProbe.java` checks shared symbol shortcuts on full and double pinyin with nine, 26
and 14 keys. Set `CYIME_PHRASE_PROBE_DIR` to an empty scratch directory when running
`PersonalDictManagerTest`, then copy its exported tables and patches into a disposable user directory
containing the bundled schemas (enable all six schemas). Compile the probe with `LayoutSyncProbe.java`
and the replay's `RimeEngine.java`; arguments are the host library directory and disposable user directory.
It checks every same-code candidate and selection, supplementary Unicode, backspacing and retyping,
weight order, and coexistence with the double-pinyin schema's private table.

`WordTypoProbe.java` (library dir, a freshly deployed user directory listing only `rime_ice`, the schema id, a
case file from `../word_typo_bench.py make`, the output file and the neighbour map from `../typo_bench.py
neighbors`) types common words and everyday clauses with one slip each and records where the intended text lands
while typing and after the pause refinement. Score with `word_typo_bench.py score`. A copy of the user directory
with `t9_ngram.lm` added measures the word-model configuration.

`FuzzyExactFirstProbe.java` (library dir, a disposable copy of a deployed `rime_ice` user directory, the
neighbour map from `../typo_bench.py neighbors`, optionally a file for the first words without fuzzy pinyin) types
every single syllable of the bundled 8105 dictionary twice: as deployed, then with every fuzzy group the app offers
written into `rime_ice.custom.yaml` (the probe writes and deploys it). A first word spelled by the typed letters
must stay first with the groups on, and ne/neng must still offer 了/冷 in the first three. 2026-11-05: 1.15.63
loses 88 of 412 (ne → 了, neng → 冷, si → 是, zong → 中); with the fix 411 of 411 stay, and the first words
without fuzzy pinyin are the same as 1.15.58's for all 413 syllables.

`CorrectionFirstProbe.java` (library dir, a deployed `rime_ice` user directory, the neighbour map) checks the
2026-11-05 rule after the pause: a corrected whole word goes first when it is 2000 times more common than the typed
reading's word (chengong → 成功; 50 times until 2026-11-08), not below that (jinian → 几年, aniu → 阿牛,
genshou → 跟手, not 分手; keyifuzhi → 可以复制); words the user has learned are never compared, and a typed reading that is a
sentence keeps first place (jiucup → 就粗平), and a single syllable keeps its typed character (ao → 奥, den → 扽).
Must-pass words included. It also checks that a shorter word breaking
the input where the first word does comes second (kejishuip → 科技水平, 科技).

`T9MixedLearnProbe.java` runs the same cases on 9, 14 and 26 keys: English and mixed words (fuck, B站) must
be among the candidates, and a word typed once by picking segments must come first for the same keys, alone
and inside a longer input, also after other commits. `LayoutTopOneProbe.java` writes the first candidate of
the fixture sentences on all three layouts, with or without a preset user dictionary, so two engines can be
compared. `T9PreeditSyllableProbe.java` checks the nine-key code bar after every key. All three use a
disposable user directory, never an installed application's data.
