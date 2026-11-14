"""生成贡献页随机出题的题库 app/src/main/assets/handwriting/contribute_pool.tsv。

11-12 用户：原来手写和滑行贡献每人都是同样几个字词，几十个人交上来全一样，没用。现在应用按每人的随机编号
从这份题库里打乱出题（见 ContributePromptDeck.kt），每人不同、几千道写不完。

每行 `类型<TAB>文字<TAB>附加`：
- char：常用字（常用度前 COMMON 个、GB2312 里的简体字），附加为空；
- rare：较少用的字（GB2312 里常用度接下来的 RARE 个，有读音的，偏旁部件不要），附加是第一个读音，写在题目下方；
- word：常用两字词（两个字都是 char），附加是拼音（空格分开、不带声调），手写的词题和滑行的中文、九键题共用。

来源：hanzi_frequency.tsv（字的常用度）、hanzi_pinyin.tsv（读音）、rime/pinyin_simp.dict.yaml（词和权重）。
去掉 BLOCK 里的不雅词。用法：python tools/handwriting_training/build_contribute_pool.py
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
COMMON = 3000
RARE = 3000
WORDS = 3000
BLOCK = set("傻逼 操你 妈逼 他妈 尼玛 屁眼 婊子 鸡巴 阴茎 阴道 做爱 性交 强奸 卖淫 嫖娼 吸毒".split())
# GB2312 里收的偏旁部件，单独当字写没意义
COMPONENTS = set("亻冫冖讠卩阝廴匚刂勹厶亠丷丶丿乀乛乚亅冂凵彐彳犭纟饣忄扌氵灬礻衤钅疒宀艹辶覀罒癶耂肀攵夂夊彡丨丬爿廾尢屮巛虍")
HAN2 = re.compile(r"^[一-鿿]{2}$")
PINYIN = re.compile(r"^[a-z]+ [a-z]+$")


def gb2312(ch: str) -> bool:
    try:
        ch.encode("gb2312")
        return True
    except UnicodeEncodeError:
        return False


def chars(assets: pathlib.Path) -> list[str]:
    out = []
    for line in (assets / "handwriting/hanzi_frequency.tsv").read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) == 2 and len(parts[0]) == 1 and gb2312(parts[0]) and parts[0] not in COMPONENTS:
            out.append(parts[0])
    return out


def readings(assets: pathlib.Path) -> dict[str, str]:
    out = {}
    for line in (assets / "handwriting/hanzi_pinyin.tsv").read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and parts[1].strip():
            out[parts[0]] = parts[1].split("/")[0].strip()
    return out


def words(assets: pathlib.Path, common: set[str], count: int) -> list[tuple[str, str]]:
    best: dict[str, tuple[int, str]] = {}
    in_body = False
    for line in (assets / "rime/pinyin_simp.dict.yaml").read_text(encoding="utf-8").splitlines():
        if line == "...":
            in_body = True
            continue
        if not in_body:
            continue
        parts = line.split("\t")
        if len(parts) < 3 or not HAN2.match(parts[0]) or not PINYIN.match(parts[1]) or not parts[2].isdigit():
            continue
        word, pinyin, weight = parts[0], parts[1], int(parts[2])
        if word in BLOCK or any(ch not in common for ch in word):
            continue
        if word not in best or weight > best[word][0]:  # 多音词取常用读音
            best[word] = (weight, pinyin)
    ranked = sorted(best.items(), key=lambda kv: (-kv[1][0], kv[0]))
    return [(w, p) for w, (_, p) in ranked[:count]]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--assets", default=str(ASSETS), help="读入的 assets 目录")
    ap.add_argument("--out", default=str(ASSETS / "handwriting/contribute_pool.tsv"))
    args = ap.parse_args()
    assets = pathlib.Path(args.assets)
    ranked = chars(assets)
    reading = readings(assets)
    common = ranked[:COMMON]
    rare = [ch for ch in ranked[COMMON:] if reading.get(ch)][:RARE]
    rows = [f"char\t{ch}\t" for ch in common]
    rows += [f"rare\t{ch}\t{reading[ch]}" for ch in rare]
    rows += [f"word\t{w}\t{p}" for w, p in words(assets, set(common), WORDS)]
    header = "# 贡献页随机出题的题库：类型\t文字\t附加（rare 是读音，word 是拼音）。" \
             "由 tools/handwriting_training/build_contribute_pool.py 生成。\n"
    pathlib.Path(args.out).write_text(header + "\n".join(rows) + "\n", encoding="utf-8")
    print(f"常用字 {len(common)}、少用字 {len(rare)}、词 {len(rows) - len(common) - len(rare)} → {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
