"""List dictionary words that are a common word plus a homophone of a sentence-final particle, and that chat
writes far less often than the word plus the particle itself.

生肖马 (shengxiao ma, a tencent.dict phrase) is one word, so Rime's sentence builder prefers it to 生效 + 吗 and
shengxiaoma came out 生肖马 on every keyboard (user 2026-11-15: 「生肖马->生效吗 这种词 虽然是个词 但是他完全不如生效吗
常用」). Same shape: 可以拉 / 可以啦, 谢谢妈 / 谢谢吗. A candidate T = P + X is

  * in a --check dictionary (the ones words may be dropped from) and in no --keep dictionary;
  * P is a word of two or three characters with a reading in some dictionary, weight >= --min-weight;
  * X is not itself a particle, but one of X's readings is a particle's reading (吗 ma, 吧 ba, 呢 ne ...).

Its particle forms are W + Y: W any word read like P (P itself included), Y a particle read like X. Both T and
the particle forms are counted as exact strings in the corpora (script statistics only, nothing else read or
kept). T is listed when its particle forms are written at least --ratio times as often as T (and at least
--min-count times). The report has every candidate with its counts so the cut can be checked by eye.

Usage (Android, generated rime_ice):
  python particle_homophone_words.py --chars RIME_ICE/cn_dicts/8105.dict.yaml \\
      --keep RIME_ICE/cn_dicts/base.dict.yaml --check RIME_ICE/cn_dicts/ext.dict.yaml RIME_ICE/cn_dicts/tencent.dict.yaml \\
      --corpus CHAT.txt [SUBTITLES.txt ...] --list OUT.txt --report OUT.tsv
The list is one word per line, in the format of app/data/cyime_removed_particle_homophones.txt.
Dictionaries are Rime tables: text, code, weight; a table without a code column (tencent: text, weight) only
supplies candidates.
"""
import argparse
from collections import Counter, defaultdict
from itertools import islice, product

# Sentence-final particles (app/data/sentence_final_particles.txt plus 了 and 么) and how they are read there.
PARTICLES = {"吗": "ma", "吧": "ba", "呢": "ne", "啊": "a", "呀": "ya", "啦": "la", "嘛": "ma", "了": "le", "么": "me",
             "哇": "wa", "呗": "bei", "哦": "o", "咯": "lo", "哟": "yo", "嘞": "lei", "喽": "lou"}


def rows(path):
    """(text, code or None, weight or None) for every data row after the YAML header."""
    body = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not body:
                body = line.strip() == "..."
                continue
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            text = fields[0]
            code = fields[1] if len(fields) >= 3 else None
            weight = fields[-1] if len(fields) >= 2 else ""
            yield text, code, int(weight) if weight.lstrip("-").isdigit() else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chars", nargs="+", required=True, help="single-character tables (readings of X)")
    parser.add_argument("--keep", nargs="+", default=[], help="tables whose words are never listed")
    parser.add_argument("--check", nargs="+", required=True, help="tables whose words may be listed")
    parser.add_argument("--corpus", nargs="+", required=True)
    parser.add_argument("--lines", type=int, default=0, help="lines read from each corpus (0: all)")
    parser.add_argument("--min-weight", type=int, default=1000, help="P must be at least this common")
    parser.add_argument("--ratio", type=float, default=3.0)
    parser.add_argument("--min-count", type=int, default=3)
    parser.add_argument("--list", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    char_readings = defaultdict(set)
    for path in args.chars:
        for text, code, _ in rows(path):
            if len(text) == 1 and code:
                char_readings[text].add(code)
    by_reading = defaultdict(set)     # "sheng xiao" -> {生效, 生肖, ...}
    readings = defaultdict(set)       # 生效 -> {"sheng xiao"}
    weight = Counter()
    kept = set()
    for path in args.keep + args.check:
        for text, code, w in rows(path):
            if code and 2 <= len(text) <= 3 and len(code.split()) == len(text):
                by_reading[code].add(text)
                readings[text].add(code)
            if w is not None:
                weight[text] = max(weight[text], w)
            if path in args.keep:
                kept.add(text)
    particle_by_reading = defaultdict(set)
    for p, r in PARTICLES.items():
        particle_by_reading[r].add(p)

    candidates = {}  # T -> set of particle forms
    for path in args.check:
        for text, _, _ in rows(path):
            if len(text) not in (3, 4) or text in kept or text in candidates:
                continue
            stem, last = text[:-1], text[-1]
            if last in PARTICLES or weight[stem] < args.min_weight or not readings.get(stem):
                continue
            ys = {p for r in char_readings.get(last, ()) for p in particle_by_reading.get(r, ())}
            if not ys:
                continue
            ws = {w for r in readings[stem] for w in by_reading[r]}
            candidates[text] = {w + y for w, y in product(ws, ys)}
    targets = set(candidates) | {f for forms in candidates.values() for f in forms}
    lengths = sorted({len(t) for t in targets})
    tails = {t[-1] for t in targets}

    counts = Counter()
    for path in args.corpus:
        with open(path, encoding="utf-8", errors="ignore") as corpus:
            for line in (islice(corpus, args.lines) if args.lines else corpus):
                for end, ch in enumerate(line, 1):
                    if ch not in tails:
                        continue
                    for n in lengths:
                        if end >= n and line[end - n:end] in targets:
                            counts[line[end - n:end]] += 1

    listed, report = [], []
    for text, forms in sorted(candidates.items()):
        own = counts[text]
        best = max(forms, key=lambda f: counts[f])
        total = sum(counts[f] for f in forms)
        drop = total >= args.min_count and total >= args.ratio * max(own, 1)
        if drop:
            listed.append(text)
        report.append((text, own, best, counts[best], total, weight[text[:-1]], "drop" if drop else "keep"))
    with open(args.list, "w", encoding="utf-8", newline="\n") as out:
        out.write("".join(t + "\n" for t in listed))
    with open(args.report, "w", encoding="utf-8", newline="\n") as out:
        out.write("word\tcount\tparticle_form\tform_count\tforms_total\tstem_weight\tdecision\n")
        out.write("".join("\t".join(map(str, r)) + "\n" for r in report))
    print(f"{len(candidates)} candidates, {len(listed)} listed")


if __name__ == "__main__":
    main()
