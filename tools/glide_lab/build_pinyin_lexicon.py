"""生成中文滑行词表 app/src/main/assets/glide_pinyin.txt。

来源是仓库里的 rime-ice 词频（app/data/rime_ice_8105_weights.tsv.gz、rime_ice_base_weights.tsv.gz）：
同一拼音的各个词频相加，按总频率从高到低取前 LIMIT 个编码，再补上所有单音节，
保证词表外的词也能按音节逐个划出。每行一个编码，音节之间用空格分开（双拼按音节换码要用）。
glide_pinyin_extra.txt 里的词（雾凇词频偏低的口语，如牛逼 niu bi 在第 68007 名）不占 30000 的名额，
另外补进来，排在第 FLOOR_RANK 名的位置；原有编码一个不少，顺序不变。

同时生成 glide_pinyin_words.txt，和 glide_pinyin.txt 一行对一行：这个编码最常用的词（补充词的编码用补充词本身，
生成词库时会删掉的词 cyime_removed_words 不用）。九键滑行的备选拿它当候选栏里显示和上屏的字（#214）。

用法：python -B tools/glide_lab/build_pinyin_lexicon.py
"""
import collections
import gzip
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCES = ["app/data/rime_ice_8105_weights.tsv.gz", "app/data/rime_ice_base_weights.tsv.gz"]
OUTPUT = ROOT / "app/src/main/assets/glide_pinyin.txt"
WORDS = ROOT / "app/src/main/assets/glide_pinyin_words.txt"
REMOVED = ROOT / "app/data/cyime_removed_words.txt.gz"
EXTRA = ROOT / "tools/glide_lab/glide_pinyin_extra.txt"
LIMIT = 30000
FLOOR_RANK = 10000
MAX_SYLLABLES = 4
CODE = re.compile(r"^[a-z]+( [a-z]+)*$")


def extra_words() -> dict[str, str | None]:
    """补充词 → 指定的拼音（没写 =拼音 时为空，按词库里最常用的读法）。"""
    words: dict[str, str | None] = {}
    for line in EXTRA.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            continue
        for item in line.split():
            word, _, code = item.partition("=")
            words[word] = code.replace("'", " ") or None
    return words


def main() -> None:
    weights: collections.Counter[str] = collections.Counter()
    # 词 → (这个词最常用的读法, 词频)，给补充词查拼音。
    readings: dict[str, tuple[str, int]] = {}
    # 编码 → (最常用的词, 词频)，给九键滑行的备选当显示的字。
    with gzip.open(REMOVED, "rt", encoding="utf-8") as lines:
        removed = {line.strip() for line in lines}
    labels: dict[str, tuple[str, int]] = {}
    for source in SOURCES:
        with gzip.open(ROOT / source, "rt", encoding="utf-8") as lines:
            for line in lines:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3 or not CODE.match(parts[1]):
                    continue
                if len(parts[1].split(" ")) > MAX_SYLLABLES:
                    continue
                weights[parts[1]] += int(parts[2])
                if parts[0] not in readings or int(parts[2]) > readings[parts[0]][1]:
                    readings[parts[0]] = (parts[1], int(parts[2]))
                if parts[0] not in removed and (parts[1] not in labels or int(parts[2]) > labels[parts[1]][1]):
                    labels[parts[1]] = (parts[0], int(parts[2]))
    ranked = [code for code, _ in sorted(weights.items(), key=lambda item: (-item[1], item[0]))]
    extra = {word: code or readings.get(word, ("",))[0] for word, code in extra_words().items()}
    missing = [word for word, code in extra.items() if code not in weights]
    if missing:
        raise SystemExit(f"glide_pinyin_extra.txt 里这些词雾凇词库没有（或不是这个读法）：{' '.join(missing)}")
    floor = weights[ranked[FLOOR_RANK]]
    boosted = dict(weights)
    for code in extra.values():
        boosted[code] = max(boosted[code], floor)
    extra = set(extra.values())
    chosen = sorted(set(ranked[:LIMIT]) | extra, key=lambda code: (-boosted[code], code))
    picked = set(chosen)
    chosen += [code for code in ranked[LIMIT:] if " " not in code and code not in picked]
    OUTPUT.write_text("\n".join(chosen) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(chosen)} codes -> {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size} bytes)")
    for word, code in extra_words().items():
        labels[code or readings[word][0]] = (word, 0)
    WORDS.write_text("\n".join(labels.get(code, ("",))[0] for code in chosen) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(chosen)} words -> {WORDS.relative_to(ROOT)} ({WORDS.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
