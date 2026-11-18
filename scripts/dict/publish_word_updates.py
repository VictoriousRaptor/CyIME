"""词库在线更新：把你维护的词发给所有用户，不用发新版 App。

在电脑上、仓库根目录运行（Python 3.10+）：
  py -3 -B scripts\\dict\\publish_word_updates.py            # 生成 → 提交 → 推到 GitHub → 传到魔搭 → 核对
  py -3 -B scripts\\dict\\publish_word_updates.py --dry-run  # 只显示会改什么，不写文件
  py -3 -B scripts\\dict\\publish_word_updates.py --local    # 只写文件，打包进安装包测试，不提交、不上传

你平时只改这两张表（每行 词<TAB>拼音<TAB>权重，拼音用空格分音节，和以前一样）：
  app/data/cyime_domain_terms.tsv   输入法用语、新词
  app/data/cyime_user_fixes.tsv     必过词（排序要对的词）
游戏名、网络流行语等新词在 app/data/cyime_new_words.tsv，由 tools/dictionaries/new_words.py 从候选表生成，不手改；
三张表一起下发。
内置分类词库（明日方舟等）出了新词，加到 app/data/cell_updates/builtin_<id>.tsv（每行 词<TAB>拼音，
例如 builtin_arknights.tsv；id 和 CellDictionaryCatalog.kt 里的 builtin_ 名字一样），文件里有的就是在线下发的全部补词。
脚本把这些表合成 app/src/main/assets/cell_dictionaries/word_updates.json（main 和 dictionaries 两部分），版本号 revision 加 1；
没有 .tsv 的分类词库，json 里原有的补词原样保留。下一个安装包也自带这份文件；Windows 版发版前运行
CyIME-Windows 的 scripts/import-cell-dictionaries.py，同一批补词也进电脑的内置词库。

App 每天最多查一次这个文件（键盘收起后），版本号更大就下载、校验格式，然后重新部署。
在线更新只能加词、把词往前提，不能删词，也不能把词往后压；删词仍要发新版。

一次性准备（只做一次）：
  py -3 -m pip install -U modelscope
  在 https://modelscope.cn/my/myaccesstoken 新建访问令牌，存成用户环境变量 MODELSCOPE_API_TOKEN

线路和 App 一致（KeysConfigHelper.CYIME_STORE_BASE_URLS 去掉末尾 store/ 再接文件路径）：
  海外  GitHub main（jsDelivr / raw.githubusercontent.com），脚本推送后清 jsDelivr 缓存
  国内  魔搭 Cyletix/cyime-model 仓库里同一路径
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REL = "app/src/main/assets/cell_dictionaries/word_updates.json"
TARGET = ROOT / REL
TABLES = [ROOT / "app/data/cyime_domain_terms.tsv", ROOT / "app/data/cyime_user_fixes.tsv",
          ROOT / "app/data/cyime_new_words.tsv"]
CELL_DIR = ROOT / "app/data/cell_updates"
CATALOG = ROOT / "app/src/main/java/com/kingzcheung/xime/settings/CellDictionaryCatalog.kt"
FORMAT = "cyime.cell-words.v1"
REPO_ID = "Cyletix/cyime-model"
CHECK_URLS = [
    "https://raw.githubusercontent.com/Cyletix/CyIME/main/" + REL,
    "https://cdn.jsdelivr.net/gh/Cyletix/CyIME@main/" + REL,
    f"https://www.modelscope.cn/models/{REPO_ID}/resolve/master/" + REL,
]
PURGE = "https://purge.jsdelivr.net/gh/Cyletix/CyIME@main/" + REL
# 与 App 的检查一致（CellWordUpdates.parse / CellMainWord.validate）。
WORD = re.compile(r"[㐀-䶿一-鿿]{1,32}")
CELL_WORD = re.compile(r"[㐀-䶿一-鿿]{2,32}")  # CellPinyinWord.validate
CODE = re.compile(r"[a-z]{1,8}( [a-z]{1,8})*")
MAX_WEIGHT = 10_000_000
MAX_ENTRIES = 4096
MAX_BYTES = 512 * 1024


def read_main() -> list[dict]:
    """两张表合并：同一个词同一个拼音取较大的权重；顺序按文字、拼音排，结果稳定。"""
    rows: dict[tuple[str, str], int] = {}
    for table in TABLES:
        for n, line in enumerate(table.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("\t")
            where = f"{table.relative_to(ROOT).as_posix()} 第 {n} 行"
            if len(fields) != 3:
                sys.exit(f"{where}：要三列（词、拼音、权重），用 Tab 分开")
            word, code, weight = fields[0].strip(), fields[1].strip(), fields[2].strip()
            if not WORD.fullmatch(word) or not CODE.fullmatch(code) or code.count(" ") + 1 != len(word):
                sys.exit(f"{where}：词或拼音不对（只收汉字；拼音小写、每个字一个音节、空格分开）：{line}")
            if not weight.isdigit() or not 1 <= int(weight) <= MAX_WEIGHT:
                sys.exit(f"{where}：权重要在 1 到 {MAX_WEIGHT} 之间：{line}")
            key = (word, code)
            rows[key] = max(rows.get(key, 0), int(weight))
    return [{"word": w, "code": c, "weight": v} for (w, c), v in sorted(rows.items())]


def cell_tables() -> list[Path]:
    return sorted(CELL_DIR.glob("builtin_*.tsv")) if CELL_DIR.is_dir() else []


def read_dictionaries(old: dict) -> dict:
    """每张 builtin_<id>.tsv 就是这个分类词库在线补词的全部；没有表的保留 json 里原有的。"""
    result = {k: v for k, v in old.items()}
    catalog = CATALOG.read_text(encoding="utf-8")
    for table in cell_tables():
        id = table.stem
        if not re.fullmatch(r"builtin_[a-z0-9]+", id) or f'"{id}"' not in catalog:
            sys.exit(f"{table.relative_to(ROOT).as_posix()}：{id} 不是内置词库（CellDictionaryCatalog.kt 里没有）")
        rows: dict[tuple[str, str], None] = {}
        for n, line in enumerate(table.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("\t")
            where = f"{table.relative_to(ROOT).as_posix()} 第 {n} 行"
            if len(fields) != 2:
                sys.exit(f"{where}：要两列（词、拼音），用 Tab 分开")
            word, code = fields[0].strip(), fields[1].strip()
            if not CELL_WORD.fullmatch(word) or not CODE.fullmatch(code) or code.count(" ") + 1 != len(word):
                sys.exit(f"{where}：词或拼音不对（2 到 32 个汉字；拼音小写、每个字一个音节、空格分开）：{line}")
            rows[(word, code)] = None
        result[id] = [{"word": w, "code": c} for w, c in sorted(rows)]
    return result


def render(data: dict) -> bytes:
    return (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def build() -> tuple[bytes, dict, dict]:
    old = json.loads(TARGET.read_text(encoding="utf-8"))
    if old.get("format") != FORMAT:
        sys.exit(f"{REL} 的 format 不是 {FORMAT}，先确认 App 的格式有没有改")
    main = read_main()
    new = {"format": FORMAT, "revision": old["revision"], "dictionaries": read_dictionaries(old.get("dictionaries", {})), "main": main}
    if new != old:
        new["revision"] = int(old["revision"]) + 1
    data = render(new)
    total = len(main) + sum(len(v) for v in new["dictionaries"].values())
    if total > MAX_ENTRIES or len(data) > MAX_BYTES:
        sys.exit(f"一共 {total} 条、{len(data):,} 字节，超过 App 的上限（{MAX_ENTRIES} 条、512 KiB）")
    return data, old, new


def diff(old: dict, new: dict) -> list[str]:
    before = {(r["word"], r["code"]): r["weight"] for r in old.get("main", [])}
    after = {(r["word"], r["code"]): r["weight"] for r in new["main"]}
    lines = [f"+ {w} {c} {after[(w, c)]}" for w, c in sorted(after.keys() - before.keys())]
    lines += [f"- {w} {c}（不再在线下发；已经打进安装包的那份仍在）" for w, c in sorted(before.keys() - after.keys())]
    lines += [f"~ {w} {c} {before[(w, c)]} → {after[(w, c)]}" for w, c in sorted(after.keys() & before.keys())
              if before[(w, c)] != after[(w, c)]]
    for id in sorted(set(old.get("dictionaries", {})) | set(new["dictionaries"])):
        was = {(r["word"], r["code"]) for r in old.get("dictionaries", {}).get(id, [])}
        now = {(r["word"], r["code"]) for r in new["dictionaries"].get(id, [])}
        lines += [f"+ {id} {w} {c}" for w, c in sorted(now - was)]
        lines += [f"- {id} {w} {c}（不再在线下发；已经打进安装包的那份仍在）" for w, c in sorted(was - now)]
    return lines


def git(*args: str, check: bool = True) -> str:
    out = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if check and out.returncode != 0:
        sys.exit(f"git {' '.join(args)} 失败：\n{out.stderr.strip()}")
    return out.stdout.strip()


def push(revision: int) -> None:
    if git("rev-parse", "--abbrev-ref", "HEAD") != "main":
        sys.exit("当前不在 main 分支，没有提交。切回 main 再运行。")
    git("fetch", "origin", "main")
    ahead = git("rev-list", "--count", "origin/main..HEAD")
    behind = git("rev-list", "--count", "HEAD..origin/main")
    if ahead != "0" or behind != "0":
        # 只推这一次的词库提交，不把还没打算公开的本地提交一起推出去。
        sys.exit(f"本地 main 和 GitHub 不一致（本地多 {ahead} 个提交、少 {behind} 个），没有提交。"
                 "先同步，或用 --no-push 只传魔搭。")
    paths = [REL] + [t.relative_to(ROOT).as_posix() for t in TABLES + cell_tables()]
    git("add", "--", *paths)
    git("commit", "-m", f"词库在线更新：revision {revision}", "--", *paths)
    git("push", "origin", "main")
    try:
        urllib.request.urlopen(urllib.request.Request(PURGE, headers={"User-Agent": "cyime-word-updates"}), timeout=60).read()
    except Exception as e:  # noqa: BLE001
        print("jsDelivr 缓存没清掉（最多 12 小时后自己更新）：", e)


def upload_modelscope(data: bytes, revision: int) -> None:
    token = os.environ.get("MODELSCOPE_API_TOKEN", "").strip()
    if not token:
        sys.exit("缺少环境变量 MODELSCOPE_API_TOKEN（魔搭 → 账号设置 → 访问令牌），GitHub 已更新，魔搭没传")
    from modelscope.hub.api import HubApi
    api = HubApi()
    api.login(token)
    with tempfile.TemporaryDirectory() as tmp:
        staged = Path(tmp) / REL
        staged.parent.mkdir(parents=True)
        staged.write_bytes(data)
        api.upload_folder(repo_id=REPO_ID, folder_path=tmp, path_in_repo="",
                          commit_message=f"word updates revision {revision}", repo_type="model")


def verify(data: bytes, urls: list[str]) -> bool:
    want = hashlib.sha256(data).hexdigest()
    ok = True
    for url in urls:
        got = None
        for _ in range(3):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "cyime-word-updates", "Cache-Control": "no-cache"})
                got = hashlib.sha256(urllib.request.urlopen(req, timeout=60).read()).hexdigest()
                if got == want:
                    break
            except Exception as e:  # noqa: BLE001
                got = f"下载失败 {e}"
            time.sleep(10)
        print("ok " if got == want else "未同步", url)
        ok &= got == want
    return ok


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="只生成并显示变化，不写文件、不提交、不上传")
    parser.add_argument("--local", action="store_true", help="只写文件（打包进安装包测试用），不提交、不上传")
    parser.add_argument("--no-push", action="store_true", help="不提交、不推 GitHub，只写文件并传魔搭")
    args = parser.parse_args()
    data, old, new = build()
    changes = diff(old, new)
    if new["revision"] == old["revision"]:
        print(f"词表没有变化，仍是 revision {old['revision']}。")
        if args.dry_run:
            return
    else:
        cells = sum(len(v) for v in new["dictionaries"].values())
        print(f"revision {old['revision']} → {new['revision']}，主词库 {len(new['main'])} 条，分类词库补词 {cells} 条：")
        print("\n".join(changes) if changes else "（只有分类词库或格式变化）")
    if args.dry_run:
        print("--dry-run：没有写文件。")
        return
    if TARGET.read_bytes() != data:
        TARGET.write_bytes(data)
    if args.local:
        print(f"已写入 {REL}（revision {new['revision']}），没有提交、没有上传。")
        return
    if not args.no_push and new["revision"] != old["revision"]:
        push(new["revision"])
    upload_modelscope(data, new["revision"])
    if not verify(data, CHECK_URLS[2:] if args.no_push else CHECK_URLS):
        sys.exit("有线路还没拿到新文件，过几分钟用同样的命令再跑一次（没有变化时只重传、重新核对）。")
    print(f"已发布 revision {new['revision']}。用户的键盘收起后一天内会自动更新。")


if __name__ == "__main__":
    main()
