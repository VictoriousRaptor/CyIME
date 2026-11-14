"""Build the OpenCC tables that ship in assets/rime_chinese/opencc.

librime compiles OpenCC but its data (configs and .ocd2 tables) was never bundled,
so the 简/繁 switch converted nothing. This copies the needed upstream configs,
the matching .ocd2 tables built from the same OpenCC submodule, and adds
CyimeTWPhrases: Taiwan's everyday forms where s2tw picks another one
(臺灣 -> 台灣, 計劃 -> 計畫, 瞭解 -> 了解).

The Taiwan forms come from McBopomofo's phrase counts (Source/Data/phrase.occ,
commit 18e2060dd5d0b511753ad79a8fe0d112e944f1af). A word W replaces the s2tw
form X of its simplified text only when W is at least three times as common as X
in those counts, so a form Taiwan also uses is never rewritten.

Usage (Linux):
  cmake -S app/src/main/jni/librime/deps/opencc -B /tmp/occ -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_DOCUMENTATION=OFF -DENABLE_GTEST=OFF -DBUILD_PYTHON=OFF
  ninja -C /tmp/occ Dictionaries
  python3 tools/opencc/build_cyime_opencc.py --build /tmp/occ --occ phrase.occ
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/jni/librime/deps/opencc/data/config"
OUT = ROOT / "app/src/main/assets/rime_chinese/opencc"
PHRASES = Path(__file__).resolve().parent / "cyime_tw_phrases.txt"
CONFIGS = ("s2t.json", "s2tw.json", "s2hk.json")
TOP = 60000
# Single characters Taiwan writes differently from s2tw everywhere, counted in phrase.occ
# (臺 0 vs 台 53731; 汙 255 vs 污 1621).
CHARACTERS = {"臺": "台", "汙": "污"}
# Counted from news text, these pairs are both in everyday Taiwan use, or the simplified
# text means two different words (自制 self-control / 自製 self-made, 海里 nautical mile /
# 海裡 in the sea); OpenCC's own choice stays.
KEEP = {"吃茶", "名錶", "碼錶", "水錶", "吸菸者", "紙菸", "禁菸", "鼻菸", "鼻菸壺", "菸斗", "菸灰", "菸頭",
        "自制", "海里", "格里", "里加", "市裡", "相沖", "幹了", "整出", "几几", "鬱郁"}


def convert(build: Path, config: str, lines: list[str]) -> list[str]:
    tool = build / "src/tools/opencc"
    with tempfile.TemporaryDirectory() as work:
        work = Path(work)
        shutil.copy(SOURCE / config, work / config)
        for table in (build / "data").glob("*.ocd2"):
            shutil.copy(table, work / table.name)
        (work / "in.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        subprocess.run([tool, "-c", work / config, "-i", work / "in.txt", "-o", work / "out.txt"], check=True)
        result = (work / "out.txt").read_text(encoding="utf-8").split("\n")[:len(lines)]
    if len(result) != len(lines):
        raise ValueError("OpenCC changed the line count")
    return result


def taiwan_phrases(build: Path, occ_path: Path) -> dict[str, str]:
    occ = {}
    for line in occ_path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            occ[parts[0]] = int(parts[1])
    words = [w for w, c in sorted(occ.items(), key=lambda item: -item[1])[:TOP] if len(w) >= 2 and c > 0]
    simplified = convert(build, "t2s.json", words)
    back = convert(build, "s2tw.json", simplified)
    chosen: dict[str, tuple[int, str]] = {}
    for word, form in zip(words, back):
        if form == word or form in KEEP or occ.get(form, 0) * 3 > occ[word]:
            continue
        if "".join(CHARACTERS.get(c, c) for c in form) == word:
            continue  # the character table already covers it
        if form not in chosen or chosen[form][0] < occ[word]:
            chosen[form] = (occ[word], word)
    table = dict(CHARACTERS)
    table.update({form: word for form, (_, word) in chosen.items()})
    return table


SURNAMES = "CyimeSurnames.txt"
USER_KEEP = "CyimeUserKeep.txt"


def write_cyime_configs(config: dict) -> None:
    """cyime_s2tw.json plus the surname table (余先生, 姓范, 余秋雨: tools/opencc/build_cyime_surnames.py), and
    cyime_s2tw_user.json for the user's own phrases: same, but 余 with no phrase around it stays as written."""
    for group in (config["segmentation"]["dict"]["dicts"], config["conversion_chain"][0]["dict"]["dicts"][0]["dicts"]):
        group.append({"type": "text", "file": SURNAMES})
    (OUT / "cyime_s2tw.json").write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    user = json.loads(json.dumps(config))
    user["name"] = "Simplified Chinese to Traditional Chinese (Taiwan everyday forms, CyIME, user phrases)"
    user["conversion_chain"][0]["dict"]["dicts"].insert(1, {"type": "text", "file": USER_KEEP})
    (OUT / "cyime_s2tw_user.json").write_text(json.dumps(user, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build", type=Path, required=True, help="OpenCC CMake build dir with Dictionaries built")
    parser.add_argument("--occ", type=Path, required=True, help="McBopomofo Source/Data/phrase.occ")
    args = parser.parse_args()
    table = taiwan_phrases(args.build, args.occ)
    PHRASES.write_text("".join(f"{k}\t{v}\n" for k, v in sorted(table.items())), encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    needed = set()
    for config in CONFIGS:
        shutil.copy(SOURCE / config, OUT / config)
        needed |= {m for m in (SOURCE / config).read_text(encoding="utf-8").split('"') if m.endswith(".ocd2")}
    for name in sorted(needed):
        shutil.copy(args.build / "data" / name, OUT / name)
    subprocess.run([args.build / "src/tools/opencc_dict", "-i", PHRASES, "-o", OUT / "CyimeTWPhrases.ocd2",
                    "-f", "text", "-t", "ocd2"], check=True)
    config = json.loads((SOURCE / "s2tw.json").read_text(encoding="utf-8"))
    config["name"] = "Simplified Chinese to Traditional Chinese (Taiwan everyday forms, CyIME)"
    config["conversion_chain"].append({"dict": {"type": "ocd2", "file": "CyimeTWPhrases.ocd2"}})
    write_cyime_configs(config)
    print(f"{len(table)} Taiwan forms, {len(needed) + 1} tables in {OUT}")


if __name__ == "__main__":
    main()
