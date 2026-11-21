"""生成拼音标调用的词表：只收「逐字查读音会标错调」的词。

    uv run --with pypinyin==0.55.0 python tools/pinyin_tones/build_tone_words.py

逐字查读音用 app/src/main/assets/handwriting/hanzi_pinyin.tsv（手写读音表，多音字按常用排）：
用户打的音节先把不同拼法的读音分开（xing/hang），剩下同一拼法不同声调的（为 wèi/wéi、得 dé/de）只能按词定。
词：pypinyin 0.55.0 自带的词语表，加 assets/glide_pinyin_words.txt 里的常用词；读音按 pypinyin 按词标的结果，
一、不按词典标本调（yī、bù），不标变调。运行时不在词表里的字用读音表里同拼法最常用的带调读音；读音表里最常用的是轻声的字
（喜欢的欢、消息的息）只在两字词的后字标轻声，这些词收进词表。和 PinyinTones 运行时的规则一致。
写 app/src/main/assets/pinyin/tone_words.tsv：每行「词<TAB>读音 读音 …」。
"""
import pathlib
import unicodedata

from pypinyin import Style, pinyin
from pypinyin.constants import PHRASES_DICT

ROOT = pathlib.Path(__file__).resolve().parents[2]
CHARS = ROOT / "app/src/main/assets/handwriting/hanzi_pinyin.tsv"
# 读音表排错了的单字（单字行也收进词表，运行时同样最先查）。
CHAR_FIXES = {"啥": "shá"}
# 单用时就读轻声的虚词（助词、语气词、词尾）：逐字查时照读音表最常用的读音；和 PinyinTones.NEUTRAL_PARTICLES 一致。
NEUTRAL_PARTICLES = "的地得了着过吗呢吧啊啦嘛呀哇哪么们呗喽咯罢哩噢"
WORDS = ROOT / "app/src/main/assets/glide_pinyin_words.txt"
OUT = ROOT / "app/src/main/assets/pinyin/tone_words.tsv"


TONE_MARKS = "\u0304\u0301\u030c\u0300"


def base(reading: str) -> str:
    """去掉声调后的拼法，ü 记作 v；和 PinyinTones.spelling 一致。"""
    plain = "".join(c for c in unicodedata.normalize("NFD", reading) if c not in TONE_MARKS)
    return unicodedata.normalize("NFC", plain).lower().replace("ü", "v")


def main() -> None:
    table = {}
    for line in CHARS.read_text(encoding="utf-8").splitlines():
        char, _, readings = line.partition("\t")
        table[char] = readings.split("/")

    def toned(reading: str) -> bool:
        return any(c in TONE_MARKS for c in unicodedata.normalize("NFD", reading))

    def by_char(char: str, spelled: str):
        """运行时逐字查到的读音：同拼法里最常用的带调读音，没有才用轻声；虚词照最常用的。"""
        fix = CHAR_FIXES.get(char)
        if fix and base(fix) == spelled:
            return fix
        same = [r for r in table.get(char, []) if base(r) == spelled]
        if char not in NEUTRAL_PARTICLES:
            same = [r for r in same if toned(r)] or same
        return same[0] if same else None

    def neutral(char: str, spelled: str) -> bool:
        """读音表里这个字同拼法最常用的是轻声（欢 huan、息 xi、友 you）。"""
        same = [r for r in table.get(char, []) if base(r) == spelled]
        return bool(same) and not toned(same[0])

    words = {w for w in PHRASES_DICT if len(w) >= 2}
    words |= {w for w in WORDS.read_text(encoding="utf-8").split() if len(w) >= 2}
    targets = {}
    for word in sorted(words):
        if not all("\u4e00" <= c <= "\u9fff" for c in word):
            continue
        tones = [r[0] for r in pinyin(word, style=Style.TONE, heteronym=False)]
        if len(tones) != len(word):
            continue
        tones = ["yī" if c == "一" and base(t) == "yi" else "bù" if c == "不" and base(t) == "bu" else t
                 for c, t in zip(word, tones)]
        # 标调后长度要和拼法一样（逐字母换），ê、m̀ 这类没有合成字的不收。
        if any(len(unicodedata.normalize("NFC", t)) != len(base(t)) for t in tones):
            continue
        runtime = [by_char(c, base(t)) for c, t in zip(word, tones)]
        # 只信 pypinyin 词语表管到的字；没被哪个词管到的字它也是逐字取默认读音（说得对的得给成 dé），用运行时的读音。
        covered = [False] * len(word)
        for i in range(len(word)):
            for j in range(i + 2, len(word) + 1):
                if word[i:j] in PHRASES_DICT:
                    covered[i:j] = [True] * (j - i)
        tones = [t if covered[i] or r is None else r for i, (t, r) in enumerate(zip(tones, runtime))]
        # 虚词在词头就不是虚词了（得到 dé、了解 liǎo、着急 zháo）：用带调的读音，收进词表。
        if not covered[0] and word[0] in NEUTRAL_PARTICLES:
            first = [r for r in table.get(word[0], []) if base(r) == base(tones[0]) and toned(r)]
            if first:
                tones[0] = first[0]
        # 两字词的后字读音表说多读轻声（喜欢、消息、朋友）：标轻声；三字以上（国务院）按词标调。
        if len(word) == 2 and neutral(word[1], base(tones[1])):
            tones[1] = base(tones[1]).replace("v", "ü")
        targets[word] = [unicodedata.normalize("NFC", t) for t in tones]

    # 收词：按运行时的做法（词表最长匹配，其余逐字）标一遍，和目标不一样的词收进词表。收了词会改变包含它的长词的结果，
    # （说得对 里先匹配上 得对），所以反复几遍直到不再变。
    table_words: dict[str, list[str]] = {}

    def simulate(word: str, spelled: list[str]) -> list[str]:
        longest = max(map(len, table_words), default=0)
        result: list = [None] * len(word)
        i = 0
        while i < len(word):
            step = 1
            for length in range(min(longest, len(word) - i), 0, -1):
                hit = table_words.get(word[i:i + length])
                if hit:
                    result[i:i + length] = hit
                    step = length
                    break
            i += step
        return [r if r is not None and base(r) == s else by_char(c, s) for c, r, s in zip(word, result, spelled)]

    table_words.update({c: [r] for c, r in CHAR_FIXES.items()})
    for _ in range(5):
        added = 0
        for word, tones in targets.items():
            if table_words.get(word) == tones:
                continue
            if simulate(word, [base(t) for t in tones]) != tones:
                table_words[word] = tones
                added += 1
        if added == 0:
            break
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join(f"{w}\t{' '.join(t)}\n" for w, t in sorted(table_words.items())), encoding="utf-8")
    print(f"{len(table_words)} words -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
