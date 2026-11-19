#!/usr/bin/env python3
"""列出 Kotlin 源码里显示给用户的中文字面量，和 app/src/main/assets/i18n/en.tsv 对照。

界面英文用「中文原文当键」的做法（gettext 式，见 ui/i18n/UiTranslations.kt）：
设置页照旧写中文，显示时 [com.kingzcheung.xime.ui.i18n.Text] 按 en.tsv 换成英文。
这个脚本把源码里的中文字面量按运行时的样子还原出来（转义、字符串模板写成 {0}{1}、
相邻的 "a" + "b" 拼起来、trimIndent），再报告哪些还没有英文。

用法：
  python tools/i18n/extract_ui_strings.py --missing        # 列出还没翻译的（新加的中文设置文字）
  python tools/i18n/extract_ui_strings.py --dump out.tsv   # 全部字面量（键、文件:行）
只读源码，不改文件。
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import textwrap

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "kingzcheung", "xime")
TABLE = os.path.join(ROOT, "app", "src", "main", "assets", "i18n", "en.tsv")

# 设置 app 里能显示出来的文字所在目录。键盘本身（ui/keyboard、ui/menubar）不翻：键上的「空格」「中」是中文键盘的一部分。
SCAN_DIRS = [
    "ui/settings", "ui/caption", "ui/common", "settings", "model", "viewmodel", "speech", "clipboard",
    "ocr", "handwriting", "contacts", "stats", "platform", "update", "plugin", "rime", "gloss",
    "association", "translate", "security", "service",
]
SCAN_FILES = ["CustomLayoutActivity.kt", "SettingsActivity.kt", "MainActivity.kt"]

CJK = re.compile(r"[\u3400-\u9fff]")
# 这些调用里的中文不显示给用户。
SKIP_CALL = re.compile(
    r"(?:\bLog\.[vdiwe]|\blog[A-Z]?\w*|\bthrow\b|\berror|\brequire(?:NotNull)?|\bcheck(?:NotNull)?|Exception|"
    r"\bRegex|\bprintln|\bassert\w*|\bTimber\.\w+|settingsSearchAnchor|searchKey\s*=|anchor\s*=)\s*\(?[^()\"]*$"
)


class Lit:
    __slots__ = ("value", "line", "start", "end", "raw")

    def __init__(self, value: str, line: int, start: int, end: int, raw: bool):
        self.value, self.line, self.start, self.end, self.raw = value, line, start, end, raw


ESC = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", '"': '"', "'": "'", "$": "$"}


def _skip_expr(src: str, i: int, lits: list, line_of) -> int:
    """i 指向 ${ 之后；跳到匹配的 }，途中的字符串字面量也收进来（如 ${if (a) "开" else "关"}）。"""
    depth = 1
    while i < len(src) and depth:
        c = src[i]
        if c == '"':
            i = _read_string(src, i, lits, line_of)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        i += 1
    return i


def _read_string(src: str, i: int, lits: list, line_of) -> int:
    """i 指向开头的引号，读完返回结束后的位置，字面量（模板槽写成 {n}）加入 lits。"""
    start = i
    raw = src.startswith('"""', i)
    i += 3 if raw else 1
    out = []
    slot = 0
    while i < len(src):
        c = src[i]
        if raw and src.startswith('"""', i):
            while src.startswith('""""', i):  # """" 结尾时前面的引号属于内容
                out.append('"')
                i += 1
            i += 3
            break
        if not raw and c == '"':
            i += 1
            break
        if not raw and c == "\\":
            n = src[i + 1]
            if n == "u":
                out.append(chr(int(src[i + 2:i + 6], 16)))
                i += 6
            else:
                out.append(ESC.get(n, n))
                i += 2
            continue
        if c == "$" and i + 1 < len(src):
            n = src[i + 1]
            if n == "{":
                i = _skip_expr(src, i + 2, lits, line_of)
                out.append("{%d}" % slot)
                slot += 1
                continue
            if n.isalpha() or n == "_":
                j = i + 1
                while j < len(src) and (src[j].isalnum() or src[j] == "_"):
                    j += 1
                out.append("{%d}" % slot)
                slot += 1
                i = j
                continue
        out.append(c)
        i += 1
    value = "".join(out)
    tail = src[i:i + 20]
    if raw and tail.startswith(".trimIndent()"):
        value = textwrap.dedent(value).strip("\n")
        lines = value.split("\n")
        value = "\n".join(lines)
    elif raw and tail.startswith(".trimMargin()"):
        value = "\n".join(re.sub(r"^\s*\|", "", l) for l in value.strip("\n").split("\n"))
    lits.append(Lit(value, line_of(start), start, i, raw))
    return i


def scan(src: str) -> list[Lit]:
    nl = [m.start() for m in re.finditer("\n", src)]

    def line_of(pos: int) -> int:
        lo, hi = 0, len(nl)
        while lo < hi:
            mid = (lo + hi) // 2
            if nl[mid] < pos:
                lo = mid + 1
            else:
                hi = mid
        return lo + 1

    lits: list[Lit] = []
    i = 0
    while i < len(src):
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = len(src) if j < 0 else j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = len(src) if j < 0 else j + 2
            continue
        if c == "'":  # 字符字面量
            j = i + 1
            while j < len(src) and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            i = j + 1
            continue
        if c == '"':
            i = _read_string(src, i, lits, line_of)
            continue
        i += 1
    return lits


def join_concat(src: str, lits: list[Lit]) -> list[Lit]:
    """"a" + "b"（中间只有空白和 +）拼成一条，运行时显示的是整句。"""
    out: list[Lit] = []
    for lit in sorted(lits, key=lambda l: l.start):
        if out and re.fullmatch(r"\s*\+\s*", src[out[-1].end:lit.start]):
            prev = out[-1]
            # 第二段的模板槽号接着第一段往后排
            shift = len(re.findall(r"\{\d+\}", prev.value))
            val = re.sub(r"\{(\d+)\}", lambda m: "{%d}" % (int(m.group(1)) + shift), lit.value)
            out[-1] = Lit(prev.value + val, prev.line, prev.start, lit.end, prev.raw)
            out.append(lit)  # 单独一段也留着（有时分开用）
            continue
        out.append(lit)
    return out


def shown(path: str, src: str, lit: Lit) -> bool:
    if not CJK.search(lit.value):
        return False
    # 单个字（键名、符号表、汉字示例）和正则不是界面文字。
    if len(lit.value.strip()) <= 1 or re.search(r"\(\?[:=!<]|\\[sdwb]|\[\^", lit.value):
        return False
    line_start = src.rfind("\n", 0, lit.start) + 1
    ctx = src[line_start:lit.start]
    if not ctx.strip():  # 字面量单独一行：看上一行
        prev = src.rfind("\n", 0, line_start - 1) + 1
        ctx = src[prev:line_start - 1] + " "
    if SKIP_CALL.search(ctx):
        return False
    # 设置搜索的同义词串（「其他 更多 备份 性能」）只用来匹配，不显示。
    if path.endswith("SettingsSearch.kt") and len(lit.value.split(" ")) >= 3 and not re.search(r"[，。：？！、]", lit.value):
        return False
    return True


def collect() -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    files = []
    for d in SCAN_DIRS:
        for base, _, names in os.walk(os.path.join(SRC, d)):
            files += [os.path.join(base, n) for n in names if n.endswith(".kt")]
    files += [os.path.join(SRC, f) for f in SCAN_FILES if os.path.exists(os.path.join(SRC, f))]
    for path in sorted(set(files)):
        src = open(path, encoding="utf-8").read()
        for lit in join_concat(src, scan(src)):
            if shown(path, src, lit):
                found.setdefault(lit.value, []).append("%s:%d" % (os.path.relpath(path, SRC), lit.line))
    return found


def esc(s: str) -> str:
    s = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    return "\\" + s if s.startswith("#") else s  # 行首 # 是注释，原文以 # 开头时写成 \#


def load_table(path: str = TABLE) -> dict[str, str]:
    table: dict[str, str] = {}
    if not os.path.exists(path):
        return table
    for raw in open(path, encoding="utf-8"):
        raw = raw.rstrip("\n")
        if not raw or raw.startswith("#") or "\t" not in raw:
            continue
        k, v = raw.split("\t", 1)
        table[unesc(k)] = unesc(v)
    return table


def unesc(s: str) -> str:
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump")
    ap.add_argument("--missing", action="store_true")
    a = ap.parse_args()
    found = collect()
    if a.dump:
        with open(a.dump, "w", encoding="utf-8") as f:
            for k in sorted(found, key=lambda k: found[k][0]):
                f.write("%s\t%s\n" % (esc(k), found[k][0]))
    if a.missing:
        table = load_table()
        miss = [k for k in found if k not in table]
        for k in sorted(miss, key=lambda k: found[k][0]):
            print("%s\t%s" % (found[k][0], esc(k)))
        print("共 %d 条，缺英文 %d 条" % (len(found), len(miss)), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
