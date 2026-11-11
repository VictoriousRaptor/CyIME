"""收集用户贡献的手写样本并回放，生成报告。可以反复运行：只处理新文件，旧的不动。

做的事：
1. 在 --watch 目录（默认 <inbox>、下载目录、contrib/inbox）里找 cyime_handwriting_contribute_*.jsonl；
   按内容哈希判断是否收过，新的复制到 contrib/raw/（不移动、不删除原文件）。
2. 只收「贡献手写样本」页的样本（format=cyime-handwriting-training、source=contribute），按样本 id 去重，
   合并到 contrib/samples.jsonl。坏行、别的格式跳过并记数。
3. 按 App 的中文手写逻辑回放全部样本（内置 ochwpro、每笔 8 点 / 连笔多取点、常用度重排、生僻字模板），
   写 contrib/REPORT.md（总体、按书写人、认错的字）和 contrib/history.csv（每次运行一行）。
4. 按最新样本和模型重排贡献页题目（build_contribute_prompts.py），写 contrib/next_prompts.tsv，报告末尾写出变化。

用法（电脑上，需要 numpy、onnxruntime）：
    python tools/handwriting_training/contrib_ingest.py
    python tools/handwriting_training/contrib_ingest.py --watch <inbox> --no-replay
定时运行：scripts/register-handwriting-contrib-task.ps1（每天一次，没开机就开机后补跑，后台运行不弹窗口）。

样本是用户的笔迹，只放 D:/CyIME-Data，不进仓库、不上传。
"""
from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import glob
import hashlib
import json
import math
import os
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/handwriting"
DATA = pathlib.Path("D:/CyIME-Data/handwriting_training/contrib")
MODEL = ROOT / ".gradle/bundled-model-cache/ochwpro"
MODEL_SHA = "8a6095f9ccd222fa391d4e98e2795f478d3d88daaa62d4844677fac18c7acb02"  # ochwpro v1.2
PATTERN = "cyime_handwriting_contribute_*.jsonl"
FORMAT = "cyime-handwriting-training"
FIXED_LEN = 200


def default_watch() -> list[pathlib.Path]:
    dirs = [*([pathlib.Path(os.environ["CYIME_CONTRIB_INBOX"])] if os.environ.get("CYIME_CONTRIB_INBOX") else []), pathlib.Path.home() / "Downloads", DATA / "inbox"]
    return [d for d in dirs if d.is_dir()]


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


# ---------- 收集 ----------

def collect(watch: list[pathlib.Path], data: pathlib.Path) -> list[pathlib.Path]:
    raw = data / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    known = {p.name.split("_", 1)[0] for p in raw.glob("*.jsonl")}
    new = []
    for d in watch:
        for f in sorted(d.rglob(PATTERN) if d.name == "inbox" else d.glob(PATTERN)):
            digest = sha256(f)[:16]
            if digest in known:
                continue
            target = raw / f"{digest}_{f.name}"
            shutil.copy2(f, target)
            known.add(digest)
            new.append(target)
    return new


def valid(o) -> bool:
    if not isinstance(o, dict) or o.get("format") != FORMAT or o.get("source") != "contribute":
        return False
    label, strokes = o.get("label"), o.get("strokes")
    if not isinstance(label, str) or not 0 < len(label) <= 8 or not isinstance(strokes, list) or not strokes:
        return False
    for s in strokes:
        pts = s.get("points") if isinstance(s, dict) else None
        if not isinstance(pts, list) or not all(isinstance(p, list) and len(p) == 3 for p in pts):
            return False
    return bool(o.get("id"))


def merge(data: pathlib.Path) -> tuple[list[dict], int]:
    """raw/ 下所有文件合并、按 id 去重，重写 samples.jsonl；返回（样本，跳过的行数）。"""
    seen, out, skipped = set(), [], 0
    for f in sorted((data / "raw").glob("*.jsonl")):
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            if not line.strip():
                continue
            try:
                o = json.loads(line)
            except ValueError:
                skipped += 1
                continue
            if not valid(o):
                skipped += 1
                continue
            if o["id"] in seen:
                continue
            seen.add(o["id"])
            o["_file"] = f.name
            out.append(o)
    tmp = data / "samples.jsonl.tmp"
    with open(tmp, "w", encoding="utf-8") as w:
        for o in out:
            w.write(json.dumps({k: v for k, v in o.items() if k != "_file"}, ensure_ascii=False) + "\n")
    os.replace(tmp, data / "samples.jsonl")
    return out, skipped


# ---------- 回放（与 HandwritingInference / HandwritingCandidatePolicy / HanziRareTemplates 一致）----------

def sample(st, k):
    if len(st) <= k:
        return st
    step = (len(st) - 1) / (k - 1)
    return [st[min(max(int(math.floor(i * step + 0.5)), 0), len(st) - 1)] for i in range(k)]


def corner_count(st, eps):
    keep = {0, len(st) - 1}
    stack = [(0, len(st) - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        ax, ay = st[a]
        bx, by = st[b]
        vx, vy = bx - ax, by - ay
        length = math.hypot(vx, vy) or 1.0
        best, at = -1.0, -1
        for i in range(a + 1, b):
            d = abs(vx * (st[i][1] - ay) - vy * (st[i][0] - ax)) / length
            if d > best:
                best, at = d, i
        if best > eps:
            keep.add(at)
            stack += [(a, at), (at, b)]
    return len(keep)


def simplify(strokes, cursive):
    plain = [sample(s, 8) for s in strokes]
    if not cursive:
        return plain
    pts = [p for s in strokes for p in s]
    size = max(max(p[0] for p in pts) - min(p[0] for p in pts), max(p[1] for p in pts) - min(p[1] for p in pts), 1.0)
    out = []
    for s in strokes:
        n = corner_count(s, 0.05 * size) if len(s) > 2 else len(s)
        out.append(sample(s, 8) if n <= 5 else sample(s, min(40, 8 * math.ceil((n - 1) / 4))))
    return out if sum(len(s) for s in out) <= FIXED_LEN else plain


class Replay:
    def __init__(self, model_dir: pathlib.Path, assets: pathlib.Path):
        import numpy as np
        import onnxruntime as ort
        self.np = np
        if sha256(model_dir / "ochwpro.onnx") != MODEL_SHA:
            raise SystemExit(f"{model_dir / 'ochwpro.onnx'} 不是内置模型（哈希不对）")
        self.chars = json.loads((model_dir / "char_index.json").read_text(encoding="utf-8-sig"))["chars"]
        self.vocab = set(self.chars)
        self.sess = ort.InferenceSession(str(model_dir / "ochwpro.onnx"), providers=["CPUExecutionProvider"])
        self.freq = {}
        for line in (assets / "hanzi_frequency.tsv").read_text(encoding="utf-8").splitlines():
            p = line.split("\t")
            if len(p) == 2 and p[1].strip().isdigit():
                self.freq[p[0]] = int(p[1])
        meta = json.loads((assets / "hanzi_rare_och.json").read_text(encoding="utf-8"))
        n, count = meta["n"], meta["count"]
        rec = np.frombuffer((assets / "hanzi_rare_och.bin").read_bytes(),
                            dtype=np.dtype([("l", "<u2"), ("cv", [("c", "<u2"), ("v", "u1")], n)]), count=count)
        self.rare_labels = meta["labels"]
        self.t_lab = rec["l"].astype(int)
        self.t_c = rec["cv"]["c"].astype(int)
        self.t_v = rec["cv"]["v"].astype(np.float32)
        self.buckets = collections.defaultdict(list)
        for t in range(count):
            self.buckets[self.t_c[t, 0]].append(t)

    def probs(self, strokes, cursive):
        np = self.np
        pts, pen = [], []
        for s in simplify(strokes, cursive):
            for p in s:
                pts.append(p)
                pen.append(1.0)
            if pen:
                pen[-1] = 0.0
        a = np.asarray(pts, np.float32)
        mn = a.min(0)
        rng = np.maximum(a.max(0) - mn, 1.0)
        d = np.diff(a, axis=0, prepend=a[:1])
        t = min(len(a), FIXED_LEN)
        x = np.zeros((1, FIXED_LEN, 5), np.float32)
        m = np.zeros((1, FIXED_LEN), bool)
        x[0, :t, :2] = ((a - mn) / rng)[:t]
        x[0, :t, 2:4] = (d / rng)[:t]
        x[0, :t, 4] = np.asarray(pen, np.float32)[:t]
        m[0, :t] = True
        lo = self.sess.run(None, {"input": x, "mask": m})[0][0]
        p = np.exp(lo - lo.max())
        return p / p.sum()

    @staticmethod
    def is_han(c):
        return len(c) == 1 and (0x3400 <= ord(c) <= 0x9FFF or 0x20000 <= ord(c) <= 0x3FFFF
                                                     or 0xF900 <= ord(c) <= 0xFAFF)

    def prefer(self, c):
        if len(c) < 2:
            return c
        cut = max(s for _, s in c) * 0.5
        close = [x for x in c if x[1] >= cut]
        rest = [x for x in c if x[1] < cut]
        order = sorted(close, key=lambda x: -(math.log(max(x[1], 1e-12)) + 0.3 * math.log(max(self.freq.get(x[0], 0), 20)))) + rest
        return [(o[0], c[i][1]) for i, o in enumerate(order)]

    def rare(self, top):
        np = self.np
        q = np.zeros(max(len(self.chars), int(self.t_c.max()) + 1), np.float32)
        for c, p in top[:320]:
            q[c] = math.sqrt(p)
        norm = max(float(np.linalg.norm(q)), 1e-9)
        best = {}
        for c, _ in top[:32]:
            for t in self.buckets.get(c, []):
                s = float((q[self.t_c[t]] * self.t_v[t]).sum() / (255 * norm))
                if s >= 0.6 and s > best.get(self.t_lab[t], -1):
                    best[self.t_lab[t]] = s
        return [(self.rare_labels[k], v) for k, v in sorted(best.items(), key=lambda x: -x[1])[:8]]

    def recognize(self, strokes, cursive=True, topk=10):
        np = self.np
        p = self.probs(strokes, cursive)
        idx = np.argsort(-p)
        top = [(int(i), float(p[i])) for i in idx[:320]]
        han = []
        for i, s in top[:topk * 5]:
            ch = self.chars[i]
            if ch and self.is_han(ch) and ch not in [h[0] for h in han]:
                han.append((ch, s))
        ranked = self.prefer(han)
        known = {h[0] for h in han}
        mass = min(max(sum(s for _, s in top), 1e-6), 1)
        add = [(c, 0.5 * s * s * mass) for c, s in self.rare(top) if c not in known][:4]
        if not add:
            return [h[0] for h in ranked][:topk]
        added = {a[0] for a in add}
        strong = []
        for c, _ in self.prefer(sorted(han + add, key=lambda x: -x[1])):
            if c not in added:
                break
            strong.append(c)
        weak = [a[0] for a in add if a[0] not in strong]
        order = list(dict.fromkeys(strong + [r[0] for r in ranked[:3]] + weak + [r[0] for r in ranked[3:]]))
        return order[:topk]


def strokes_of(o):
    return [[(float(x), float(y)) for x, y, _ in s["points"]] for s in o["strokes"] if s["points"]]


def evaluate(rp: Replay, samples: list[dict]):
    rows = []
    for o in samples:
        if o.get("kind") not in ("char", "rare") or len(o["label"]) != 1:
            continue
        s = strokes_of(o)
        rows.append({"writer": o.get("writer", "?"), "kind": o["kind"], "label": o["label"], "strokes": len(s),
                     "in_model": o["label"] in rp.vocab, "plain": rp.recognize(s, cursive=False)[:5],
                     "app": rp.recognize(s, cursive=True)[:5]})
    return rows


# ---------- 报告 ----------

def pct(a, b):
    return f"{a}/{b}（{100 * a / b:.1f}%）" if b else "—"


def report(data: pathlib.Path, samples, skipped, new_files, rows):
    now = dt.datetime.now().strftime("%Y-%m-%d %H:%M")
    writers = collections.Counter(o.get("writer", "?") for o in samples)
    kinds = collections.Counter(o.get("kind", "?") for o in samples)
    lines = [f"# 贡献手写样本（{now} 自动生成）", "",
             f"共 {len(samples)} 条，{len(writers)} 人；常用字 {kinds['char']}、生僻字 {kinds['rare']}、两字词 {kinds['word']}。"
             f"本次新收 {len(new_files)} 个文件；格式不对跳过 {skipped} 行。", ""]
    if rows:
        lines += ["## 回放（内置模型 + 连笔多取点 + 常用度 + 生僻字模板）", "",
                  "| 类别 | 条数 | 首选 | 前五 | 每笔 8 点时首选 |", "|---|---|---|---|---|"]
        groups = [("常用字", [r for r in rows if r["kind"] == "char"]),
                  ("生僻字·字表外", [r for r in rows if r["kind"] == "rare" and not r["in_model"]]),
                  ("生僻字·字表内", [r for r in rows if r["kind"] == "rare" and r["in_model"]])]
        for name, g in groups:
            if g:
                lines.append(f"| {name} | {len(g)} | {pct(sum(r['app'][:1] == [r['label']] for r in g), len(g))} | "
                             f"{pct(sum(r['label'] in r['app'] for r in g), len(g))} | "
                             f"{pct(sum(r['plain'][:1] == [r['label']] for r in g), len(g))} |")
        lines += ["", "## 按书写人（常用字）", "", "| 书写人 | 条数 | 首选 | 前五 |", "|---|---|---|---|"]
        for w, _ in writers.most_common():
            g = [r for r in rows if r["writer"] == w and r["kind"] == "char"]
            if g:
                lines.append(f"| {w[:8]} | {len(g)} | {pct(sum(r['app'][:1] == [r['label']] for r in g), len(g))} | "
                             f"{pct(sum(r['label'] in r['app'] for r in g), len(g))} |")
        miss = collections.Counter(r["label"] for r in rows if r["app"][:1] != [r["label"]])
        lines += ["", "## 认错的字（出现次数多的在前）", ""]
        for label, n in miss.most_common(60):
            ex = next(r for r in rows if r["label"] == label and r["app"][:1] != [label])
            lines.append(f"- {label} ×{n}：{ex['strokes']} 笔 → {''.join(ex['app'])}" + ("" if label in ex["app"] else "（前五没有）"))
    (data / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    hist = data / "history.csv"
    first = not hist.exists()
    with open(hist, "a", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if first:
            w.writerow(["time", "samples", "writers", "new_files", "char_top1", "char_top5", "rare_out_top5"])
        ch = [r for r in rows if r["kind"] == "char"]
        ro = [r for r in rows if r["kind"] == "rare" and not r["in_model"]]
        w.writerow([now, len(samples), len(writers), len(new_files),
                    sum(r["app"][:1] == [r["label"]] for r in ch), sum(r["label"] in r["app"] for r in ch),
                    sum(r["label"] in r["app"] for r in ro)])


def next_prompts(data: pathlib.Path, model: pathlib.Path, assets: pathlib.Path) -> None:
    """按最新样本重排贡献页的常用字题目（缺数据、易认错的在前），写 next_prompts.tsv，并在报告末尾写出与 App 里题目的差别。"""
    import build_contribute_prompts as bcp
    nxt = data / "next_prompts.tsv"
    ranked = bcp.build(model, data / "samples.jsonl", nxt)
    old, new = bcp.prompt_chars(assets / "contribute_prompts.tsv"), bcp.prompt_chars(nxt)
    fresh = [c for c in new if c not in old]
    lines = ["", "## 下一版贡献题目（缺数据、易认错的在前）", "",
             f"常用字 {len(new)} 个，其中还没有样本的 {sum(r['n'] == 0 for r in ranked[:len(new)])} 个；"
             f"和 App 里现在的题目比，换了 {len(fresh)} 个字。",
             "前 20：" + " ".join(f"{r['char']}（{r['err']:.0%}）" for r in ranked[:20]), ""]
    if fresh:
        lines.append(f"要让 App 用新题目：把 {nxt} 复制成 app/src/main/assets/handwriting/contribute_prompts.tsv，"
                     "或在仓库里运行 python tools/handwriting_training/build_contribute_prompts.py，再打包。")
    with open(data / "REPORT.md", "a", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--watch", nargs="*", help="找样本文件的目录，默认 <inbox>、下载目录、contrib/inbox")
    ap.add_argument("--data", default=str(DATA))
    ap.add_argument("--model", default=str(MODEL), help="内置 ochwpro 目录（ochwpro.onnx + char_index.json）")
    ap.add_argument("--assets", default=str(ASSETS))
    ap.add_argument("--no-replay", action="store_true", help="只收集合并，不回放")
    ap.add_argument("--force", action="store_true", help="没有新文件也重新回放")
    ap.add_argument("--log", help="输出追加到这个文件（计划任务用 pythonw 运行，没有控制台）")
    args = ap.parse_args()
    if args.log:
        sys.stdout = sys.stderr = open(args.log, "a", encoding="utf-8", buffering=1)
        print(f"--- {dt.datetime.now():%Y-%m-%d %H:%M}")
    data = pathlib.Path(args.data)
    (data / "inbox").mkdir(parents=True, exist_ok=True)
    watch = [pathlib.Path(d) for d in args.watch] if args.watch else default_watch()
    new = collect(watch, data)
    samples, skipped = merge(data)
    print(f"新文件 {len(new)} 个；共 {len(samples)} 条样本")
    if not new and not args.force and (data / "REPORT.md").exists():
        return 0
    rows = [] if args.no_replay else evaluate(Replay(pathlib.Path(args.model), pathlib.Path(args.assets)), samples)
    report(data, samples, skipped, new, rows)
    if not args.no_replay:
        next_prompts(data, pathlib.Path(args.model), pathlib.Path(args.assets))
    print(f"报告：{data / 'REPORT.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
