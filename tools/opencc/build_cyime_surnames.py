"""Build assets/rime_chinese/opencc/CyimeSurnames.txt: simplified -> traditional phrases where a surname keeps its form.

OpenCC turns 余 into 餘 (and 范 into 範, 于 into 於, 游 into 遊, 郁 into 鬱) unless a phrase says otherwise, so
繁體輸出 wrote 餘秋雨, 餘先生, 姓範 (user 2026-11-12: 余宛如、余琪 came out 餘宛如、餘琪). The table adds:
1. rime_ice words with 余 that only OpenCC's character default turned into 餘, except those where 余 means
   remaining / more than (YU_MEANS_REMAINDER): surnames (余秋雨, 余华), places (余家村) and classical 余 'I'.
2. surname + two-character title (余先生, 范老师, 游医生, 郁女士) and 姓X; 于 only as 姓于, because 于老师 is also
   归功于老师.
3. number + 余 (十万余, 3000余, 两万余), so 3000余老师 is never cut as 3000 + 余老师.
The user's own phrases go through cyime_s2tw_user.json, where 余 without a phrase stays as written.

Usage (uv, any OS):
  uv run --with opencc python tools/opencc/build_cyime_surnames.py --rime app/src/main/assets/rime
Check the diff of CyimeSurnames.txt, then run the unit tests.
"""
from __future__ import annotations

import argparse
import json
import shutil
import tempfile
from pathlib import Path

import opencc

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/rime_chinese/opencc"
TABLE = OUT / "CyimeSurnames.txt"

YU_MEANS_REMAINDER = set("""余条 余亿元 求余 余料 筛余 互余 边角余料 余怒未消 余电 下余 余怒 积余 赘余 余痕 余万字 流星余迹
齿牙余慧 余坪 余犯 大数求余 余函数 余窑 余式定理 损余处理 两个余 余甘子 余因子 二十五味余甘子丸 余因式 余式 余米
拾人余唾 一枕黑甜余 余腥残秽 余色 余衰喜入春 叶动承余洒 尽是补天余 池台竹树三亩余 种之秋雨余 终南望余雪 荒城鲁殿余
补余律 遗声余价 遗芬余荣 酒余茶后 酒余饭饱 雨余钟鼓更清新 鸟散余花落 余甘子喉片 姜桂余辛 何事吟余忽惆怅
香稻啄余鹦鹉粒 人乞祭余骄妾妇 余荡 余""".split())
TITLES = """先生 女士 小姐 老师 同学 经理 医生 教授 校长 主任 太太 老板 律师 博士 院长 局长 书记 市长 县长 部长
主席 委员 警官 阿姨 叔叔 伯伯 奶奶 爷爷 总裁 总监 师傅 大哥 大姐 老先生 董事长 总经理 同志 氏家族""".split()


def converter(work: Path, keep: str = "") -> opencc.OpenCC:
    """cyime_s2tw.json without the surname table; [keep] characters stay as they are when no phrase covers them."""
    config = json.loads((OUT / "cyime_s2tw.json").read_text(encoding="utf-8"))
    for group in (config["segmentation"]["dict"]["dicts"], config["conversion_chain"][0]["dict"]["dicts"][0]["dicts"]):
        group[:] = [d for d in group if d.get("file") != TABLE.name]
    if keep:
        (work / f"keep{ord(keep[0])}.txt").write_text("".join(f"{c}\t{c}\n" for c in keep), encoding="utf-8")
        config["conversion_chain"][0]["dict"]["dicts"].insert(1, {"type": "text", "file": f"keep{ord(keep[0])}.txt"})
    path = work / f"config{ord(keep[0]) if keep else 0}.json"
    path.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
    return opencc.OpenCC(str(path))


def rime_words(rime: Path) -> list[str]:
    words = set()
    for dict_file in (rime / "cn_dicts").glob("*.dict.yaml"):
        body = dict_file.read_text(encoding="utf-8").split("\n...\n", 1)[-1]
        for line in body.splitlines():
            word = line.split("\t", 1)[0]
            if "余" in word and not word.startswith("#"):
                words.add(word)
    return sorted(words)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rime", type=Path, required=True, help="rime_ice checkout (has cn_dicts/)")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        for f in OUT.iterdir():
            if f.is_file():
                shutil.copy(f, work / f.name)
        plain = converter(work)
        keep_yu = converter(work, "余")
        entries: dict[str, str] = {}
        for word in rime_words(args.rime):
            if word not in YU_MEANS_REMAINDER and plain.convert(word) != keep_yu.convert(word):
                entries[word] = keep_yu.convert(word)
        for surname in "余范游郁":
            keep = keep_yu if surname == "余" else converter(work, surname)
            for title in TITLES:
                entries[surname + title] = keep.convert(surname + title)
        for word in ("老余", "余姓", "余氏"):
            entries[word] = keep_yu.convert(word)
        for surname in "余范游郁于党种朴":
            entries["姓" + surname] = converter(work, surname).convert("姓" + surname)
        numbers = [*"0123456789", "万", "亿", "十万", "百万", "千万", "十亿", "百亿", "千亿", "万亿",
                   *(p + n for p in "上两几数多好成近约逾" for n in "万亿")]
        changed = {k: v for k, v in entries.items() if plain.convert(k) != v}
        changed.update({n + "余": plain.convert(n + "余") for n in numbers})
    TABLE.write_text("".join(f"{k}\t{v}\n" for k, v in sorted(changed.items())), encoding="utf-8")
    print(f"{len(changed)} phrases in {TABLE}")


if __name__ == "__main__":
    main()
