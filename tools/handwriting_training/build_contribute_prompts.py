"""生成「贡献手写样本」页的题目 app/src/main/assets/handwriting/contribute_prompts.tsv。

每行：类型<TAB>文字<TAB>提示。类型 char（常用字）、rare（生僻字，提示为读音）、word（两字词）。页面按文件顺序出题。
- 常用字 100 个：从字频表前 3000 个字里挑，**缺数据、当前模型容易认错的排前面**：
    1. 已收样本数：contrib/samples.jsonl 里每个字收到几份（--samples）。还没有样本的字一律排在有样本的字前面。
    2. 错误率：用当前内置模型按 App 逻辑认（连笔多取点、常用度、生僻字模板）。
       每个字拿 Make Me a Hanzi 的标准笔顺做 6 份轻微变形（补点、抖动、缩放、±5° 旋转）当“合成笔迹”，
       错误率一半看 App 首选认错的比例，一半看 1 − 模型给正确字的平均概率（形近字多、模型拿不准的字高）；
       收到过真实样本的字，再把真实样本按 App 逻辑认错的比例按份数合进去。
    3. 同一档里按 错误率 × log(字频) 从高到低，错误率一样时常用的在前。
    4. 写出来像一个常用 20 倍以上的字（曰/日、幺/么、恁/您）的不出题：微调时不训这种字，训了会把常用字抢走。
  样本收得越多，已收的字往后排、新的易错字补上来；重新运行即按最新样本和模型更新。
- 生僻字 20 个：手写模型字表外、靠生僻字模板认的字为主，外加几个常见于人名的字；
- 两字词 30 个：用来测一次写几个字时的切分和组合。

用法（电脑上，需要 numpy、onnxruntime，模型取 .gradle/bundled-model-cache/ochwpro）：
    python tools/handwriting_training/build_contribute_prompts.py
    python tools/handwriting_training/build_contribute_prompts.py --out <别处.tsv>   # 只看结果，不改 App
contrib_ingest.py 每次收到新样本后会算一份 contrib/next_prompts.tsv，并在报告里写出和 App 里题目的差别。
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import random
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

ASSETS = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/handwriting"
SAMPLES = pathlib.Path("D:/CyIME-Data/handwriting_training/contrib/samples.jsonl")
GRAPHICS = pathlib.Path("D:/CyIME-Data/datasets/makemeahanzi/graphics.txt")
RARE = "犇垚燚叕圐圙瀺槻龘鱻骉䴓婍翀嫑孖丼彧玥淼"
WORDS = ("我们 中国 时候 已经 知道 现在 什么 没有 可以 因为 所以 但是 觉得 还是 银行 "
         "手机 电脑 输入 快递 谢谢 明天 晚上 工作 学习 朋友 问题 喜欢 东西 地方 应该").split()
CHAR_COUNT = 100
POOL = 3000
VARIANTS = 6
PRIOR = 2  # 合成笔迹的错误率按几份真实样本算
LOOKALIKE = 20  # 认错成的那个字常用这么多倍以上，就当作形近罕用字，不出题


def frequency() -> list[tuple[str, int]]:
    rows = []
    for line in (ASSETS / "hanzi_frequency.tsv").read_text(encoding="utf-8").splitlines():
        p = line.split("\t")
        if len(p) == 2 and p[1].strip().isdigit():
            rows.append((p[0], int(p[1])))
    return rows


def real_samples(path: pathlib.Path) -> dict[str, list]:
    """每个字收到的真实样本（只算常用字题和字表内的单字）。"""
    import contrib_ingest as ci
    out = collections.defaultdict(list)
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            o = json.loads(line)
            if o.get("kind") in ("char", "rare") and len(o.get("label", "")) == 1:
                st = ci.strokes_of(o)
                if st:
                    out[o["label"]].append(st)
    return out


def medians(chars: set[str]) -> dict[str, list]:
    got = {}
    with open(GRAPHICS, encoding="utf-8") as f:
        for line in f:
            g = json.loads(line)
            if g.get("character") in chars:
                # graphics.txt 坐标 y 向上（1024 方格，基线偏移 900），翻转成 y 向下
                got[g["character"]] = [[(float(x), 900.0 - float(y)) for x, y in m] for m in g["medians"]]
    return got


def mild(strokes, rnd: random.Random, dense: bool):
    """标准笔顺中线 → 像手写的笔迹：一半变体按 12 单位一点补密（中线每笔只有几个点），再轻微抖动、缩放、旋转。
    幅度比训练增强小（±5°），只为估计这个字容不容易和形近字混，不模拟乱写。"""
    if dense:
        out = []
        for s in strokes:
            pts = [s[0]]
            for (x0, y0), (x1, y1) in zip(s, s[1:]):
                n = max(1, int(math.hypot(x1 - x0, y1 - y0) / 12))
                pts += [(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n) for k in range(1, n + 1)]
            out.append(pts)
        strokes = out
    allp = [p for s in strokes for p in s]
    cx = sum(p[0] for p in allp) / len(allp)
    cy = sum(p[1] for p in allp) / len(allp)
    span = max(max(p[0] for p in allp) - min(p[0] for p in allp), max(p[1] for p in allp) - min(p[1] for p in allp), 1.0)
    sx, sy = rnd.uniform(0.9, 1.1), rnd.uniform(0.9, 1.1)
    a = math.radians(rnd.uniform(-5, 5))
    ca, sa = math.cos(a), math.sin(a)
    res = []
    for s in strokes:
        new = []
        for x, y in s:
            x = (x + rnd.uniform(-1, 1) * span * 0.01 - cx) * sx
            y = (y + rnd.uniform(-1, 1) * span * 0.01 - cy) * sy
            new.append((x * ca - y * sa + cx, x * sa + y * ca + cy))
        res.append(new)
    return res


def rank_chars(model: pathlib.Path, samples: pathlib.Path) -> list[dict]:
    """给字频前 POOL 个字打分，返回排好序的 [{char, n, err, score}]（最该收的在前）。"""
    import contrib_ingest as ci
    rp = ci.Replay(model, ASSETS)
    counts = dict(frequency())
    freq = [(c, n) for c, n in frequency() if c in rp.vocab][:POOL]
    real = real_samples(samples)
    shapes = medians({c for c, _ in freq})
    idx = {ch: k for k, ch in enumerate(rp.chars) if ch}
    rows = []
    for i, (c, count) in enumerate(freq):
        if c not in shapes and c not in real:
            continue
        rnd = random.Random(f"{c}:{i}")
        syn = [mild(shapes[c], rnd, k % 2 == 1) for k in range(VARIANTS)] if c in shapes else []
        # 一半看 App 最后首选对不对（常用度重排能救回来的字不算难），一半看模型给正确字的概率还差多少（比对错稳定）
        tops = [(rp.recognize(s)[:1] or [""])[0] for s in syn]
        syn_err = sum(0.5 * (t != c) + 0.5 * (1.0 - float(rp.probs(s, True)[idx[c]]))
                      for s, t in zip(syn, tops)) / len(syn) if syn else 0.5
        got = real.get(c, [])
        got_tops = [(rp.recognize(s)[:1] or [""])[0] for s in got]
        real_miss = sum(t != c for t in got_tops)
        # 写出来本来就像一个常用得多（≥20 倍）的字（曰/日、幺/么、恁/您）：这种字训练时不用（会把常用字抢走），也就不出题
        wrong = [t for t in tops + got_tops if t and t != c]
        if wrong:
            w = max(set(wrong), key=wrong.count)
            if wrong.count(w) >= 0.3 * len(tops + got_tops) and counts.get(w, 0) >= LOOKALIKE * max(count, 1):
                continue
        err = (real_miss + PRIOR * syn_err) / (len(got) + PRIOR)
        rows.append({"char": c, "n": len(got), "err": err, "score": err * math.log(max(count, 10)), "rank": i})
    # 没样本的在前；同一档按 错误率×log(字频)，再按字频
    rows.sort(key=lambda r: (r["n"] > 0, -r["score"], r["rank"]))
    return rows


def render(chars: list[str], model_sha: str) -> str:
    readings = {}
    for line in (ASSETS / "hanzi_pinyin.tsv").read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            readings[parts[0]] = parts[1]
    rows = ["# 贡献手写样本的题目：类型\t文字\t提示。由 tools/handwriting_training/build_contribute_prompts.py 生成；"
            f"常用字按缺数据、易认错排序（模型 {model_sha[:12]}）。"]
    rows += [f"char\t{c}\t按平时的速度写" for c in chars]
    rows += [f"rare\t{c}\t{readings[c].split('/')[0]}" for c in RARE]
    rows += [f"word\t{w}\t像平时手写输入那样写" for w in WORDS]
    return "\n".join(rows) + "\n"


def build(model: pathlib.Path, samples: pathlib.Path, out: pathlib.Path) -> list[dict]:
    import contrib_ingest as ci
    ranked = rank_chars(model, samples)
    chars = [r["char"] for r in ranked[:CHAR_COUNT]]
    assert len(set(chars)) == CHAR_COUNT
    out.write_text(render(chars, ci.sha256(model / "ochwpro.onnx")), encoding="utf-8")
    return ranked


def prompt_chars(path: pathlib.Path) -> list[str]:
    if not path.is_file():
        return []
    return [p[1] for p in (l.split("\t") for l in path.read_text(encoding="utf-8").splitlines())
            if len(p) >= 2 and p[0] == "char"]


def main() -> int:
    import contrib_ingest as ci
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--model", default=str(ci.MODEL), help="内置 ochwpro 目录（ochwpro.onnx + char_index.json）")
    ap.add_argument("--samples", default=str(SAMPLES), help="已收的贡献样本（contrib_ingest.py 合并的 samples.jsonl）")
    ap.add_argument("--out", default=str(ASSETS / "contribute_prompts.tsv"))
    args = ap.parse_args()
    ranked = build(pathlib.Path(args.model), pathlib.Path(args.samples), pathlib.Path(args.out))
    top = ranked[:CHAR_COUNT]
    print(f"{CHAR_COUNT} 个常用字（还没样本 {sum(r['n'] == 0 for r in top)} 个）+ {len(RARE)} 生僻字 + {len(WORDS)} 两字词 → {args.out}")
    print("前 20：" + " ".join(f"{r['char']}{r['err']:.0%}" for r in top[:20]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
