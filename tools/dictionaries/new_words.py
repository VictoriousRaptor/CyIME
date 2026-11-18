#!/usr/bin/env python3
"""新词（游戏名、网络流行语、新产品名）补进主词库：从候选表算出 app/data/cyime_new_words.tsv。

用法（仓库根目录，Python 用 uv）：
  uv run --with pypinyin python tools/dictionaries/new_words.py 候选表... [--block 不要的词.txt]
输出 app/data/cyime_new_words.tsv（整份重写），逐条说明打印出来（或 --report 写到文件，不要放进仓库）。
构建时（tasks-chinese.gradle.kts）和 scripts/dict/publish_word_updates.py（在线下发）都会读这份表。

游戏榜自动取候选（App Store 中国区游戏免费榜、畅销榜各前 200，取名字开头的汉字，去掉「-」「：」后面的广告语）：
  python tools/dictionaries/new_words.py --fetch-app-store D:/CyIME-Data/new_words/appstore-<日期>.txt

候选表每行一个词，`#` 开头是注释；也可以写 `词<TAB>拼音` 或 `词<TAB>拼音<TAB>权重`（权重只在要压过同音常用词时写，
比如 原神 压过 元神）。tools/dict_contribute/aggregate.py 的输出（词、拼音、人数、次数、new）也能直接当候选表。

去噪：
- 只收 2～8 个汉字；`--block` 里的词、cyime_removed_names.txt 里的人名去掉。
- 词库里已经排在同音第一、或权重已经够的，不再写。
- 拼音：词库里有这个词就用词库的读音；没有用 pypinyin，有多音字的在报告里标「多音」，要人看一眼，读错的在候选表里写拼音。

权重（只一个公式，不加特例）：同音（全拼相同）最常用的别的词 top；
  top < 300（同音的都是生僻词）给 3000；否则给 min(3000, top / 2)，排在常用词后面，选一次以后靠个人学习往前提。
  词库里本来权重更高的保持不变（构建只往上提，不往下压）。候选表里写了权重的照写。
"""
from __future__ import annotations

import argparse
import gzip
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "app/data"
OUT = DATA / "cyime_new_words.tsv"
HAN = re.compile(r"[一-鿿]{2,8}")
CODE = re.compile(r"[a-z]{1,8}( [a-z]{1,8})*")
BASE = 3000
RARE = 300


def pinyin(word: str) -> tuple[str, bool]:
    from pypinyin import Style, lazy_pinyin, pinyin as heteronyms
    code = " ".join(lazy_pinyin(word, style=Style.NORMAL)).replace("ü", "v")
    multi = any(len(set(r)) > 1 for r in heteronyms(word, style=Style.NORMAL, heteronym=True))
    return code, multi


def load_dict() -> tuple[dict[str, tuple[str, int]], dict[str, list[tuple[int, str]]], set[str]]:
    """词 → (词库读音, 权重)；全拼 → [(权重, 词)]。tencent 没有读音列，用 pypinyin 算（约 20 秒）。"""
    from pypinyin import Style, lazy_pinyin
    removed = {l.strip() for l in gzip.open(DATA / "cyime_removed_words.txt.gz", "rt", encoding="utf-8")}
    words: dict[str, tuple[str, int]] = {}
    by_code: dict[str, list[tuple[int, str]]] = {}

    def add(word: str, code: str, weight: int) -> None:
        if word not in words or words[word][1] < weight:
            words[word] = (code, weight)
        by_code.setdefault(code, []).append((weight, word))

    for name in ("base", "ext"):
        for line in gzip.open(DATA / f"rime_ice_{name}_weights.tsv.gz", "rt", encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            if len(f) == 3 and f[2].isdigit():
                add(f[0], f[1], int(f[2]))
    for line in gzip.open(DATA / "rime_ice_tencent_weights.tsv.gz", "rt", encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) == 2 and f[1].isdigit() and f[0] not in removed and f[0] not in words:
            add(f[0], " ".join(lazy_pinyin(f[0], style=Style.NORMAL)).replace("ü", "v"), int(f[1]))
    return words, by_code, removed


def read_candidates(paths: list[Path], block: set[str]) -> dict[str, tuple[str | None, int | None]]:
    out: dict[str, tuple[str | None, int | None]] = {}
    for path in paths:
        for n, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            f = [x.strip() for x in line.split("\t")]
            word = f[0]
            if not HAN.fullmatch(word) or word in block:
                continue
            code = f[1] if len(f) > 1 and f[1] else None
            if code is not None and (not CODE.fullmatch(code) or code.count(" ") + 1 != len(word)):
                sys.exit(f"{path} 第 {n} 行：拼音要每个字一个音节、小写、空格分开：{line}")
            # 只有三列的候选表第三列是权重；aggregate.py 的五列输出第三列是人数，不当权重。
            weight = int(f[2]) if len(f) == 3 and f[2].isdigit() else None
            out[word] = (code or out.get(word, (None, None))[0], weight or out.get(word, (None, None))[1])
    return out


APP_STORE = "https://itunes.apple.com/cn/rss/{chart}/limit=200/genre=6014/json"
LEADING_HAN = re.compile(r"^[一-鿿]{2,8}")


def fetch_app_store(out: Path) -> None:
    """App Store 中国区游戏榜。名字常带广告语（王者荣耀-5v5英雄公平对战手游），只留开头连着的汉字。"""
    import json
    import urllib.request
    names: list[str] = []
    for chart in ("topfreeapplications", "topgrossingapplications"):
        request = urllib.request.Request(APP_STORE.format(chart=chart), headers={"User-Agent": "cyime-new-words"})
        feed = json.loads(urllib.request.urlopen(request, timeout=60).read().decode("utf-8"))["feed"]
        for entry in feed.get("entry", []):
            match = LEADING_HAN.match(entry["im:name"]["label"].strip())
            if match and match.group() not in names:
                names.append(match.group())
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("# App Store 中国区游戏免费榜、畅销榜（自动取，用前看一遍，不是游戏名的删掉）\n" + "\n".join(names) + "\n",
                   encoding="utf-8", newline="\n")
    print(f"{len(names)} 个名字写到 {out}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("candidates", nargs="*", type=Path)
    ap.add_argument("--fetch-app-store", type=Path, metavar="OUT", help="只取 App Store 游戏榜写成候选表")
    ap.add_argument("--block", type=Path, action="append", default=[], help="不要的词，每行一个")
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--report", type=Path, help="逐条说明写到这个文件；不给就打印")
    args = ap.parse_args()
    if args.fetch_app_store:
        return fetch_app_store(args.fetch_app_store)
    if not args.candidates:
        ap.error("要给候选表")
    block = {l.strip() for p in args.block for l in p.read_text(encoding="utf-8-sig").splitlines()
             if l.strip() and not l.startswith("#")}
    names = DATA / "cyime_removed_names.txt"
    if names.exists():
        block |= {l.strip().split("\t")[0] for l in names.read_text(encoding="utf-8").splitlines()
                  if l.strip() and not l.startswith("#")}
    candidates = read_candidates(args.candidates, block)
    words, by_code, removed = load_dict()
    rows, report = [], []
    for word, (given_code, given_weight) in sorted(candidates.items()):
        own_code, own = words.get(word, (None, 0))
        multi = False
        if given_code:
            code = given_code
        elif own_code:
            code = own_code
        else:
            code, multi = pinyin(word)
        top_weight, top_word = max(((w, x) for w, x in by_code.get(code, []) if x != word), default=(0, ""))
        floor = given_weight or (BASE if top_weight < RARE else min(BASE, top_weight // 2))
        notes = [n for n, on in (("多音", multi), ("曾因语料里没有删掉", word in removed)) if on]
        line = f"{word}\t{code}\t词库 {own}\t同音最高 {top_word or '-'} {top_weight}\t{'、'.join(notes)}"
        if own >= floor:
            report.append("不用写（权重已够）\t" + line)
            continue
        rows.append(f"{word}\t{code}\t{max(floor, 1)}")
        report.append(f"写 {max(floor, 1)}\t" + line)
    header = ("# 新词：游戏名、网络流行语、新产品名。由 tools/dictionaries/new_words.py 从候选表生成，请勿手改。\n"
              "# 构建时和 cyime_domain_terms.tsv 一样只往上提权重；publish_word_updates.py 在线下发。词<TAB>拼音<TAB>权重\n")
    args.out.write_text(header + "\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    if args.report:
        args.report.write_text("\n".join(report) + "\n", encoding="utf-8", newline="\n")
    else:
        print("\n".join(report))
    print(f"候选 {len(candidates)}，写入 {len(rows)}")


if __name__ == "__main__":
    main()
