"""Build a completion word list for a letter-by-letter language (es, de, fr, it, ru, vi, ms).

Frequencies come from FrequencyWords (OpenSubtitles 2018, pinned commit). Subtitles carry names,
English and typos, so only words the LibreOffice spelling dictionary accepts are kept;
a word it only accepts capitalized (Madrid, Haus) is stored capitalized.

    pip install spylls
    npm pack dictionary-de@3.0.0 && tar xzf dictionary-de-3.0.0.tgz     # es: dictionary-es@4.0.0, it: dictionary-it@2.0.0
    python tools/wordlists/build_words.py de package/index tools/wordlists/cyime-words-de-v1.txt

Vietnamese and Malay need no spelling dictionary (pass - for it):
- vi: a word is kept when it is one valid Vietnamese syllable (same initials, vowel groups and finals as
  VietnameseTelex.kt), and an unaccented word also must not be more frequent in the English subtitles than here
  (va, dan, can as English); the tone moves to the place the keyboard types it (hoà → hòa) and the two spellings merge.
- ms: names and English words in the Malay subtitles are dropped when they are at least a tenth as frequent in the
  English subtitles, Indonesian spellings when they are 4 times as frequent in the Indonesian subtitles
  (karena, uang); the keyboard fills short lists with English completions anyway. Film names (katniss, sauron)
  are dropped when a word seen fewer than 300 times here is also in 3 or more of the en, es, fr, de, it, pt, nl lists. The particles Malaysians type
  in chat (lah, mah, meh, lor, leh, kot) are always kept.
Both keep only words seen at least 5 times (below that the subtitles are mostly typos and names).

    python tools/wordlists/build_words.py vi - tools/wordlists/cyime-words-vi-v1.txt

Output: one word per line, most frequent first. The lists ship as market downloads, not in the APK:
upload the file unchanged to the GitHub Release `models` and ModelScope Cyletix/cyime-model, then put its
sha256 and size into BuiltinModelCatalog (WordListCatalogTest checks they match this folder).
"""
import re
import sys
import unicodedata
import urllib.request

SOURCE = ("https://raw.githubusercontent.com/hermitdave/FrequencyWords/"
          "525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/{0}/{0}_50k.txt")
LIMIT = 40000
LETTERS = {
    "es": "a-záéíóúüñ",
    "de": "a-zäöüß",
    "fr": "a-zàâæçéèêëîïôœùûüÿ",
    "it": "a-zàèéìíîòóùú",
    "ru": "а-яё",
    "vi": "a-zàáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ",
    "ms": "a-z",
}
# Single letters that are words; the rest are subtitle noise.
SINGLE = {
    "es": {"a", "y", "o", "e", "u"},
    "de": set(),
    "fr": {"a", "à", "y"},
    "it": {"a", "e", "è", "i", "o"},
    "ru": {"и", "в", "с", "к", "у", "о", "а", "я"},
    "vi": {"à", "á", "ạ", "ả", "ở", "ừ", "ư", "ơ", "ô", "ồ", "ê", "ề", "ạ", "ý", "ấy"},
    "ms": set(),
}
# Words always kept (Malaysian chat particles; some are rare in subtitles or also English).
KEEP = {"ms": {"lah", "mah", "meh", "lor", "leh", "kot"}}
MIN_COUNT = 5
ENGLISH_SHARE = {"vi": 0.5, "ms": 0.1}
NAME_LISTS = ["en", "es", "fr", "de", "it", "pt", "nl"]

TONES = "\u0301\u0300\u0309\u0303\u0323"  # sắc huyền hỏi ngã nặng, the keys s f r x j
VOWELS = "aăâeêioôơuưy"
INITIALS = {"", "b", "c", "ch", "d", "đ", "g", "gh", "gi", "h", "k", "kh", "l", "m", "n", "ng", "ngh", "nh", "p", "ph",
            "qu", "r", "s", "t", "th", "tr", "v", "x"}
FINALS = {"", "c", "ch", "m", "n", "ng", "nh", "p", "t"}
OPEN = {"a", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y", "ai", "ao", "au", "âu", "ay", "ây", "eo", "êu", "ia", "iu", "oa",
        "oe", "oi", "ôi", "ơi", "ua", "uê", "ui", "uơ", "ưa", "ưi", "ưu", "uy", "iêu", "yêu", "oai", "oay", "oeo", "uây",
        "uôi", "ươi", "ươu", "uya", "uyu"}
CLOSED = {"a", "ă", "â", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y", "iê", "yê", "uô", "ươ", "oa", "oă", "oe", "oo", "uâ",
          "uê", "uy", "uyê"}


def vietnamese(word):
    """(letters without tone, tone index or -1, tone position) of one valid syllable, or None."""
    letters, tone = [], -1
    for char in word:
        parts = unicodedata.normalize("NFD", char)
        marks = [c for c in parts if c in TONES]
        if len(marks) > 1 or marks and tone >= 0:
            return None
        if marks:
            tone = TONES.index(marks[0])
        letters.append(unicodedata.normalize("NFC", "".join(c for c in parts if c not in TONES)))
    text = "".join(letters)
    start = next((i for i, c in enumerate(text) if c in VOWELS), -1)
    if start < 0:
        return None
    if start == 1 and len(text) > 2 and text[2] in VOWELS and text[:2] in ("qu", "gi"):
        start = 2
    end = start
    while end < len(text) and text[end] in VOWELS:
        end += 1
    initial, vowels, final = text[:start], text[start:end], text[end:]
    if initial not in INITIALS or final not in FINALS or vowels not in (CLOSED if final else OPEN):
        return None
    if final in ("c", "ch", "p", "t") and tone not in (0, 4):
        return None
    front = vowels[0] in "eêiy"
    if initial in ("gh", "ngh", "k") and not front or initial in ("c", "g", "ng") and front:
        return None
    shaped = [i for i in range(start, end) if text[i] in "âăêôơư"]
    position = (shaped[-1] if shaped else start if end - start == 1 else end - 1 if final
                else start + 1 if end - start == 3 else start)
    return text, tone, position


def vietnamese_form(word):
    """The word as the Telex keyboard types it (tone on the old-style place), or None."""
    parsed = vietnamese(word)
    if parsed is None:
        return None
    text, tone, position = parsed
    if tone < 0:
        return text
    return "".join(unicodedata.normalize("NFC", c + TONES[tone]) if i == position else c for i, c in enumerate(text))


def frequencies(language):
    lines = urllib.request.urlopen(SOURCE.format(language)).read().decode("utf-8").splitlines()
    counts = {}
    for line in lines:
        word, count = line.split()
        word = unicodedata.normalize("NFC", word)
        counts[word] = counts.get(word, 0) + int(count)
    return counts


def per_million(counts):
    total = sum(counts.values())
    return {word: count * 1e6 / total for word, count in counts.items()}


def build_without_dictionary(language, output):
    pattern = re.compile(f"^[{LETTERS[language]}]+$")
    own = frequencies(language)
    own_rate = per_million(own)
    english = per_million(frequencies("en"))
    indonesian = per_million(frequencies("id")) if language == "ms" else {}
    elsewhere = {}
    for other in NAME_LISTS if language == "ms" else []:
        for word in frequencies(other):
            elsewhere[word] = elsewhere.get(word, 0) + 1
    merged = {}
    for word, count in own.items():
        keep = word in KEEP.get(language, ())
        if not pattern.match(word) or (len(word) == 1 and word not in SINGLE[language]) or count < MIN_COUNT and not keep:
            continue
        if word.isascii() and english.get(word, 0) >= own_rate[word] * ENGLISH_SHARE[language] and not keep:
            continue
        if language == "vi":
            form = vietnamese_form(word)
            if form is None or len(form) == 1 and form not in SINGLE[language]:
                continue
        else:
            if (indonesian.get(word, 0) >= own_rate[word] * 4 or elsewhere.get(word, 0) >= 3 and count < 300) and not keep:
                continue
            form = word
        merged[form] = merged.get(form, 0) + count
    for word in KEEP.get(language, ()):
        merged.setdefault(word, MIN_COUNT)
    words = sorted(merged, key=lambda w: (-merged[w], w))[:LIMIT]
    with open(output, "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(words) + "\n")
    print(f"{len(words)} words -> {output}")



def main(language: str, dictionary_base: str, output: str) -> None:
    if language in ("vi", "ms"):
        build_without_dictionary(language, output)
        return
    from spylls.hunspell import Dictionary

    pattern = re.compile(f"^[{LETTERS[language]}]+$")
    speller = Dictionary.from_files(dictionary_base)
    lines = urllib.request.urlopen(SOURCE.format(language)).read().decode("utf-8").splitlines()
    seen, words = set(), []
    for line in lines:
        word, _count = line.split()
        if not pattern.match(word) or (len(word) == 1 and word not in SINGLE[language]):
            continue
        form = word if speller.lookup(word) else word.capitalize() if speller.lookup(word.capitalize()) else None
        if form is None or form in seen:
            continue
        seen.add(form)
        words.append(form)
        if len(words) >= LIMIT:
            break
    with open(output, "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(words) + "\n")
    print(f"{len(words)} words -> {output}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
