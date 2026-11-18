#!/usr/bin/env python3
"""Taiwan words and Taiwan readings for the 注音 schema (cyime_zhuyin), as a pack of the rime_ice dictionary.

Source: McBopomofo (MIT) Source/Data, BPMFMappings.txt (phrase readings, from libtabe tsi.src) and phrase.occ
(Taiwan corpus counts). Phrases of two characters and more, plus single characters (BPMFBase.txt) that rime_ice has
no entry for at all, such as 妳 (#375: 妳們 could be typed, 妳 alone could not). A reading is added when rime_ice lacks that
word under that toneless reading: Taiwan-only words (健保, 立委) and Taiwan readings (垃圾 le se, 說服 shui fu).
Text is stored simplified, as in rime_ice; cyime_zhuyin turns every candidate into Taiwan forms with
opencc/cyime_s2tw.json, so a word is kept only when that conversion gives back McBopomofo's own spelling.
Weights are on rime_ice's scale, fitted on the words both tables share.

It also writes the tones each character can take (BPMFBase.txt), for lua/cyime_zhuyin_tone.lua.

Usage:
  python3 tools/zhuyin/build_cyime_tw.py --ice app/build/generated/chinese-assets/rime_ice \
      --mcbopomofo <McBopomofo>/Source/Data --out app/src/main/assets/rime_chinese/cyime_tw.dict.yaml \
      --tones-out app/src/main/assets/rime_chinese/lua/cyime_zhuyin_tones.lua
"""
import argparse
import collections
import math
import os
import re
import sys

import opencc
from pypinyin import lazy_pinyin, Style

INITIALS = dict(zip("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ", "b p m f d t n l g k h j q x zh ch sh r z c s".split()))
FINALS = dict(zip("ㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ", "a o e eh ai ei ao ou an en ang eng er".split()))


def pinyin(bopomofo):
    """One toneless rime_ice syllable for one bopomofo syllable, or None when it has no pinyin spelling."""
    b = re.sub("[ˊˇˋ˙ˉ]", "", bopomofo)
    i = m = f = ""
    if b and b[0] in INITIALS:
        i, b = INITIALS[b[0]], b[1:]
    if b and b[0] in "ㄧㄨㄩ":
        m, b = {"ㄧ": "i", "ㄨ": "u", "ㄩ": "v"}[b[0]], b[1:]
    if b and b[0] in FINALS:
        f, b = FINALS[b[0]], b[1:]
    if b:
        return None
    if not m and not f:
        return i + "i" if i in ("zh", "ch", "sh", "r", "z", "c", "s") else None
    if m == "i":
        r = {"": "i", "a": "ia", "o": "io", "eh": "ie", "ao": "iao", "ou": "iu", "an": "ian",
             "en": "in", "ang": "iang", "eng": "ing"}.get(f)
        if r is None:
            return None
        if not i:
            r = {"i": "yi", "ia": "ya", "io": "yo", "ie": "ye", "iao": "yao", "iu": "you", "ian": "yan",
                 "in": "yin", "iang": "yang", "ing": "ying"}[r]
        return i + r
    if m == "u":
        r = {"": "u", "a": "ua", "o": "uo", "ai": "uai", "ei": "ui", "an": "uan", "en": "un",
             "ang": "uang", "eng": "ong"}.get(f)
        if r is None:
            return None
        if not i:
            r = {"u": "wu", "ua": "wa", "uo": "wo", "uai": "wai", "ui": "wei", "uan": "wan", "un": "wen",
                 "uang": "wang", "ong": "weng"}[r]
        return i + r
    if m == "v":
        r = {"": "v", "eh": "ve", "an": "van", "en": "vn", "eng": "iong"}.get(f)
        if r is None:
            return None
        if not i:
            return {"v": "yu", "ve": "yue", "van": "yuan", "vn": "yun", "iong": "yong"}[r]
        if i in ("j", "q", "x"):
            return i + r.replace("v", "u")
        return i + r if r in ("v", "ve") else None
    if f == "eh" or (i and f == "er"):
        return None
    return i + f


# Particles whose other readings in BPMFMappings are tagging slips, not Taiwan usage (要的 yao di, 得上 dei shang, 都发 du fa).
PARTICLES = set("的得地都")


def particle_reading(text, code, known):
    """True when the code differs from every rime_ice reading of the word at a particle."""
    syllables = code.split()
    return bool(known) and all(any(c in PARTICLES and a != b for c, a, b in zip(text, syllables, other.split()))
                               for other in known)


def ice_tables(ice):
    text = open(os.path.join(ice, "rime_ice.dict.yaml"), encoding="utf-8").read()
    block = text.split("import_tables:", 1)[1].split("\n...", 1)[0]
    return re.findall(r"(?m)^  - ([^\s#]+)", block)


def load_ice(ice):
    """(text, reading) -> weight, first table wins as in Rime; text -> readings."""
    weights, readings = {}, collections.defaultdict(set)
    for name in ice_tables(ice):
        body = False
        for line in open(os.path.join(ice, name + ".dict.yaml"), encoding="utf-8"):
            if not body:
                body = line.rstrip() == "..."
                continue
            fields = line.rstrip("\n").split("\t")
            if not fields[0] or fields[0].startswith("#"):
                continue
            if len(fields) >= 2 and re.fullmatch(r"[a-z]+( [a-z]+)*", fields[1]):
                code, weight = fields[1], fields[2] if len(fields) > 2 else "0"
            elif len(fields) == 2 and fields[1].isdigit():
                # No code: Rime spells it from the character readings; the common reading stands in here.
                code, weight = " ".join(lazy_pinyin(fields[0], style=Style.NORMAL, v_to_u=False)).replace("ü", "v"), fields[1]
            else:
                continue
            code = code.replace("lue", "lve").replace("nue", "nve")
            key = (fields[0], code)
            if key not in weights:
                weights[key] = int(float(weight or 0))
                readings[fields[0]].add(code)
    return weights, readings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ice", required=True, help="generated rime_ice asset directory")
    parser.add_argument("--mcbopomofo", required=True, help="McBopomofo Source/Data directory")
    parser.add_argument("--out", required=True)
    parser.add_argument("--tones-out", required=True)
    parser.add_argument("--min-occ", type=int, default=30)
    args = parser.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    s2tw = opencc.OpenCC(os.path.join(here, "../../app/src/main/assets/rime_chinese/opencc/cyime_s2tw.json"))
    t2s = opencc.OpenCC("t2s.json")
    weights, readings = load_ice(args.ice)

    occ = {}
    for line in open(os.path.join(args.mcbopomofo, "phrase.occ"), encoding="utf-8"):
        fields = line.split()
        if len(fields) == 2 and fields[1].isdigit():
            occ[fields[0]] = int(fields[1])
    mappings = collections.defaultdict(list)
    for line in open(os.path.join(args.mcbopomofo, "BPMFMappings.txt"), encoding="utf-8"):
        fields = line.split()
        if len(fields) >= 3:
            mappings[fields[0]].append(fields[1:])

    han = re.compile(r"[㐀-鿿]+")
    shared, added = [], []
    for word, readings_bpmf in mappings.items():
        count = occ.get(word, 0)
        if len(word) < 2 or count < args.min_occ or not han.fullmatch(word):
            continue
        # 这次的、求的: news phrases ending in 的 are sentences Rime builds anyway, not words to add.
        if word.endswith("的") and t2s.convert(word) not in readings:
            continue
        simplified = t2s.convert(word)
        if s2tw.convert(simplified) != word:
            continue
        codes = []
        for reading in readings_bpmf:
            syllables = [pinyin(s) for s in reading]
            if None not in syllables and len(syllables) == len(word):
                code = " ".join(syllables)
                if code not in codes:
                    codes.append(code)
        # When rime_ice already has the word under one of McBopomofo's readings, the corpus count belongs mostly to
        # that reading (有的 is you de, rarely you di): the other readings are only made typeable, at a tenth.
        secondary = any((simplified, code) in weights for code in codes)
        for code in codes:
            if (simplified, code) in weights:
                shared.append((count, weights[(simplified, code)]))
            elif not particle_reading(simplified, code, readings.get(simplified, ())):
                added.append((simplified, code, count / 10 if secondary else count, simplified in readings))

    # Single characters rime_ice does not have at all (Taiwan-only forms: 妳 砲 吋 痺 簷 姪). Same corpus threshold and
    # the same s2tw round trip as words; characters rime_ice already has keep its readings (no Taiwan reading pack here).
    for line in open(os.path.join(args.mcbopomofo, "BPMFBase.txt"), encoding="utf-8"):
        fields = line.split()
        if len(fields) < 2 or len(fields[0]) != 1 or not han.fullmatch(fields[0]):
            continue
        char = fields[0]
        count = occ.get(char, 0)
        simplified = t2s.convert(char)
        if count < args.min_occ or simplified in readings or s2tw.convert(simplified) != char:
            continue
        code = pinyin(fields[1])
        if code is not None and not any(e[0] == simplified and e[1] == code for e in added):
            added.append((simplified, code, count, False))

    # rime_ice weight ~ a * occ^b on the shared words (least squares in log space).
    xs = [math.log(c) for c, w in shared if w > 0]
    ys = [math.log(w) for c, w in shared if w > 0]
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    a = my - b * mx

    lines = []
    for simplified, code, count, known in sorted(added, key=lambda e: (-e[2], e[0], e[1])):
        lines.append(f"{simplified}\t{code}\t{max(1, round(math.exp(a + b * math.log(count))))}")
    header = (
        "# Rime dictionary: Taiwan words and Taiwan readings for cyime_zhuyin (a pack of rime_ice).\n"
        "# Generated by tools/zhuyin/build_cyime_tw.py from McBopomofo (MIT) BPMFMappings.txt and phrase.occ;\n"
        f"# {sum(1 for e in added if not e[3])} words rime_ice lacks, {sum(1 for e in added if e[3])} extra readings of its words.\n"
        f"# weight = exp({a:.3f} + {b:.3f} * ln(occ)), fitted on {len(xs)} entries both tables share.\n"
        "---\nname: cyime_tw\nversion: \"2026-11-15\"\nsort: by_weight\n...\n"
    )
    with open(args.out, "w", encoding="utf-8") as out:
        out.write(header + "\n".join(lines) + "\n")
    tones = collections.defaultdict(set)
    for line in open(os.path.join(args.mcbopomofo, "BPMFBase.txt"), encoding="utf-8"):
        fields = line.split()
        if len(fields) >= 2 and len(fields[0]) == 1 and han.fullmatch(fields[0]):
            mark = fields[1][-1]
            tones[fields[0]].add({"ˊ": "2", "ˇ": "3", "ˋ": "4", "˙": "5"}.get(mark, "1"))
    groups = collections.defaultdict(list)
    for char, marks in tones.items():
        groups["".join(sorted(marks))].append(char)
    with open(args.tones_out, "w", encoding="utf-8") as out:
        out.write("-- Tones each character can take (1-4, 5 = neutral), from McBopomofo (MIT) BPMFBase.txt;\n"
                  "-- generated by tools/zhuyin/build_cyime_tw.py for cyime_zhuyin_tone.lua.\nreturn {\n")
        for key in sorted(groups):
            out.write(f'  ["{key}"] = "{"".join(sorted(groups[key]))}",\n')
        out.write("}\n")
    print(f"shared {len(shared)}, fit a={a:.3f} b={b:.3f}; added {len(added)} "
          f"({sum(1 for e in added if not e[3])} new words, {sum(1 for e in added if e[3])} new readings)", file=sys.stderr)


if __name__ == "__main__":
    main()
