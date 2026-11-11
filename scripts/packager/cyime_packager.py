#!/usr/bin/env python3
"""CyIME 打包器：命令行接口与图形界面共用的核心。Python 3.10+，只用标准库。

构建、校验、安装、发布沿用 scripts/auto-deliver.py 的同一套检查（签名、版本、哈希、
不降级、不卸载、不覆盖已发布文件），在其上增加可选项：正式版/调试版、离线模型、
九键词组模型、内置词库、版本升号，以及向资源 Release（默认 models）上传文件。

  python scripts/packager/cyime_packager.py info
  python scripts/packager/cyime_packager.py build --preset 装到我的设备 --dry-run
  python scripts/packager/cyime_packager.py build --debug --t9-model none --serial TABLET_SERIAL
  python scripts/packager/cyime_packager.py upload D:/CyIME-Data/models/x.lm --name t9_ngram-v4.lm --dry-run
"""
from __future__ import annotations

import argparse
import contextlib
import ctypes
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from types import SimpleNamespace

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
_spec = importlib.util.spec_from_file_location("auto_deliver", ROOT / "scripts/auto-deliver.py")
ad = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ad)

DeliveryError = ad.DeliveryError
CONFIG_PATH = HERE / "config.json"
T9_TARGET = "app/src/main/assets/rime_chinese/t9_ngram.lm"
# Private gloss add-on (entries chosen from the user's own messages): bundled only into 装到我的设备, never committed, never public.
GLOSS_TARGET = "app/src/main/assets/gloss/cyime_gloss-private.tsv"
# CyLM files (config cylm: target name under rime_chinese -> source path): only 装到我的设备 until the user approves more.
CYLM_DIR = "app/src/main/assets/rime_chinese"
PUBLIC_FORBIDDEN_ASSETS = ("assets/gloss/cyime_gloss-private.tsv",)
PUBLIC_FORBIDDEN_PREFIXES = ("assets/rime_chinese/cylm_",)
PUBLIC_FORBIDDEN_REFERENCES = ("cyime_gloss-v9",)
# The bundled-model (full) edition is the elder package; docs/development/elder-pack-spec.md is its checklist.
# 智能联想 11-02 起只用内置 n-gram 联想表，不再带联想模型。
ELDER_MODELS = {"ochwpro", "paraformer-zh-en-int8",
                "sensevoice-multilingual-int8", "speaker-campplus-zh"}
ELDER_FORBIDDEN = re.compile(r"^assets/.*(rime_japanese/|jaroomaji|japanese|zipformer|_ja[-_./]|kana)", re.I)


def elder_violations(names: list[str], t9_sha256: str | None, best_sha256: str | None = None) -> list[str]:
    """What in an elder APK breaks the spec: Japanese assets, models outside the fixed set, a non-public T9 model."""
    problems = sorted({n for n in names if ELDER_FORBIDDEN.search(n)})[:10]
    models = {n.split("/")[2] for n in names if n.startswith("assets/bundled-models/") and n.count("/") >= 3}
    if models != ELDER_MODELS:
        problems.append(f"内置模型应为 {sorted(ELDER_MODELS)}，实际 {sorted(models)}")
    if not t9_sha256 or t9_sha256 != best_sha256:
        problems.append("九键模型不是模型总览里的当前最佳私人模型（best_private）")
    return problems


def ime_label_ok(manifest_tree: str) -> bool:
    """The input method service carries the literal label "CyIME", so Settings never falls back to its class name."""
    part = manifest_tree.split('XimeInputMethodService"', 1)
    if len(part) < 2:
        return False
    service = manifest_tree[:len(part[0])].rsplit("E: service", 1)[-1] + part[1].split("E: ", 1)[0]
    return bool(re.search(r'android:label\([^)]*\)="CyIME"', service))


def friend_violations(names: list[str], t9_sha256: str | None, public_sha256: str | None,
                      private_sha256: str | None, manifest_tree: str) -> list[str]:
    """Friend package (docs/development/friend-pack-spec.md): normal app, all models bundled, public T9 model only."""
    problems = []
    if not any(n.startswith("assets/rime_japanese/") for n in names):
        problems.append("日语资源缺失（朋友包保留日语，只是默认不开）")
    # 轻量版的日语词库和转换引擎是插件下载的；朋友包带模型，要在包里（不联网也能用日语）。
    if "assets/azookey/dictionary.zip" not in names or not any(n.endswith("/libCyAzooKey.so") for n in names):
        problems.append("日语词库或转换引擎不在包里（朋友包要带，不能靠下载）")
    models = {n.split("/")[2] for n in names if n.startswith("assets/bundled-models/") and n.count("/") >= 3}
    if models != ELDER_MODELS:
        problems.append(f"内置模型应为 {sorted(ELDER_MODELS)}，实际 {sorted(models)}")
    if not t9_sha256 or t9_sha256 != public_sha256:
        problems.append("九键模型不是模型总览里的当前最佳公开模型（best_public）")
    if t9_sha256 and t9_sha256 == private_sha256:
        problems.append("朋友包不能带私人模型")
    # The separate caption launcher must ship disabled (android:enabled=false) so no extra home icon appears.
    caption = manifest_tree.split("CaptionLauncher", 1)
    if len(caption) < 2 or not re.search(r"android:enabled\([^)]*\)=\(type 0x12\)0x0\b", caption[1].split("E: ", 1)[0]):
        problems.append("字幕桌面图标默认没有关闭")
    return problems
DICT_DIR = "app/src/main/assets/cell_dictionaries"
PRIVATE_DIRS = (".codex-artifacts", ".git")
ASSET_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

# The packager's own files do not change the app, so editing them must not force a rebuild.
_source_input = ad.source_input
ad.source_input = lambda name: _source_input(name) and not name.startswith("scripts/packager/")


def say(text=""):
    print(text, flush=True)


def load_config(root: Path = ROOT) -> dict:
    config = ad.read_json(CONFIG_PATH)
    config.setdefault("modelsDir", "D:/CyIME-Data/models")
    config.setdefault("resourceTag", "models")
    config.setdefault("presets", {})
    return config


def human(size: int) -> str:
    return f"{size / 1024 / 1024:.1f} MB"


# ---------------------------------------------------------------- 版本

def read_version(root: Path = ROOT) -> tuple[str, int]:
    return ad.parse_version((root / "app/build.gradle.kts").read_text(encoding="utf-8-sig"))


def bumped(version: str, code: int, kind: str) -> tuple[str, int]:
    major, minor, patch = map(int, version.split("."))
    if kind == "patch":
        return f"{major}.{minor}.{patch + 1}", code + 1
    if kind == "minor":
        return f"{major}.{minor + 1}.0", code + 1
    raise ValueError(kind)


def write_version(root: Path, version: str, code: int):
    path = root / "app/build.gradle.kts"
    text = path.read_text(encoding="utf-8-sig")
    ad.parse_version(text)  # refuse ambiguous files before editing
    text = re.sub(r'^(\s*versionName\s*=\s*)"[^"\r\n]+"(\s*)$', lambda m: f'{m[1]}"{version}"{m[2]}', text, count=1, flags=re.M)
    text = re.sub(r'^(\s*versionCode\s*=\s*)\d+(\s*)$', lambda m: f"{m[1]}{code}{m[2]}", text, count=1, flags=re.M)
    if ad.parse_version(text) != (version, code):
        raise DeliveryError("改版本号失败：app/build.gradle.kts 格式和预期不同。")
    path.write_text(text, encoding="utf-8")


def delivered_versions(root: Path = ROOT) -> dict[str, str]:
    """versionCode -> versionName already installed on a device, from both delivery tools."""
    versions = {}
    for name in (".gradle/auto-deliver/state.json", ".gradle/packager/state.json"):
        path = root / name
        if not path.is_file():
            continue
        state = ad.read_json(path)
        versions.update(state.get("deliveredVersions", {}))
        installed = state.get("installed", {})
        for key, build in state.get("builds", {}).items():
            if installed.get("sha256") and build.get("sha256") == installed["sha256"]:
                version, code = key.split("/")[:2]
                versions[code] = version
    return versions


def built_variants(root: Path, code: int) -> dict[str, str]:
    """Release packages already produced for this versionCode: option set -> APK SHA256.
    A package that was only built (for example handed to someone) still uses up its version."""
    path = root / ".gradle/packager/state.json"
    state = ad.read_json(path) if path.is_file() else {}
    return state.get("outputs", {}).get(str(code), {})


def needs_bump(root: Path = ROOT, variant: str | None = None) -> bool:
    """True when the current version was installed on a device, or already produced a different package.
    Rebuilding the same options under an undelivered version keeps it (a failed or repeated build)."""
    version, code = read_version(root)
    versions = delivered_versions(root)
    if versions and code <= max(map(int, versions)):
        return True
    return any(v != variant for v in built_variants(root, code))


# ---------------------------------------------------------------- 资源清单

def list_models(config: dict) -> list[dict]:
    directory = Path(config["modelsDir"])
    if not directory.is_dir():
        return []
    return [{"name": p.name, "path": str(p), "size": p.stat().st_size}
            for p in sorted(directory.glob("*.lm"))]


def list_dicts(root: Path = ROOT) -> list[dict]:
    catalog = root / "app/src/main/java/com/kingzcheung/xime/settings/CellDictionaryCatalog.kt"
    names = dict(re.findall(r'BuiltinCellDictionary\("([^"]+)",\s*"([^"]+)"', catalog.read_text(encoding="utf-8"))) \
        if catalog.is_file() else {}
    result = []
    for path in sorted((root / DICT_DIR).glob("cell_*.dict.yaml")):
        dict_id = path.name[len("cell_"):-len(".dict.yaml")]
        result.append({"id": dict_id, "name": names.get(dict_id, dict_id), "file": path.name, "size": path.stat().st_size})
    return result


def offline_catalog(root: Path = ROOT) -> list[dict]:
    """Offline models the app can bundle, read by the same parser the build uses."""
    spec = importlib.util.spec_from_file_location("prepare_bundled_models", root / "scripts/prepare-bundled-models.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return [{"id": m["id"], "name": m["name"], "size": sum(f["size"] for f in m["files"])} for m in module.catalog()]


# Compressed share of each part inside the APK: ONNX 0.90 (1.12.24 offline build), n-gram 0.552 (1.12.23).
RATIO = {"onnx": 0.90, "t9": 0.552, "dict": 0.4}


def inspect_input_data(archive) -> dict:
    """Read the finished APK. A selected model in a preset does not establish what was delivered."""
    names = set(archive.namelist())
    def asset_hash(name):
        if name not in names:
            return None
        with archive.open(name) as stream:
            sha = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                sha.update(block)
            return sha.hexdigest()
    base = "assets/association/lccc-base-v1.bin"
    manifest = "assets/association/lccc-base-v1.manifest.json"
    meta = json.loads(archive.read(manifest)) if manifest in names else {}
    return {
        "t9Sha256": asset_hash("assets/rime_chinese/t9_ngram.lm"),
        "privateGlossSha256": asset_hash("assets/gloss/cyime_gloss-private.tsv"),
        "cylmSha256": {n.rsplit("/", 1)[-1]: asset_hash(n) for n in sorted(names)
                       if n.startswith("assets/rime_chinese/cylm_") and not n.endswith("/")},
        "associationSha256": asset_hash(base),
        "associationManifestSha256": meta.get("sha256"),
        "associationSources": meta.get("source", []),
        "builtinCellDictionaries": sorted(n.split("/")[-1] for n in names
                                         if n.startswith("assets/cell_dictionaries/") and n.endswith(".dict.yaml")),
        "learningFiles": sorted(n for n in names if n.startswith("assets/") and
                                (n.endswith(("/user_ngram_cache.json", ".learning.json")) or "/learning-profiles/" in n)),
    }


def check_input_data(data, t9_sha, gloss_sha, cylm=None):
    if data["t9Sha256"] != t9_sha:
        raise DeliveryError("包内九键模型与选择的文件不一致。")
    if data["privateGlossSha256"] != gloss_sha:
        raise DeliveryError("包内私人释义与所选配置不一致。")
    if data["cylmSha256"] != (cylm or {}):
        raise DeliveryError("包内 CyLM 选词模型与所选配置不一致。")
    if not data["associationSha256"] or data["associationSha256"] != data["associationManifestSha256"]:
        raise DeliveryError("包内中文联想表缺失或与联想表清单不一致。")
    if data["learningFiles"]:
        raise DeliveryError("安装包中发现本应存放在设备上的个人学习文件。")


def size_estimate(root: Path = ROOT) -> dict:
    """Bytes inside the newest built APK, split so the GUI can add the parts it selects."""
    import zipfile
    receipts = sorted((root / "app/build/outputs/apk/editions").glob("*/*/release/latest.json"),
                      key=lambda f: f.stat().st_mtime, reverse=True)
    for receipt in receipts:
        try:
            apk = Path(ad.read_json(receipt)["files"][0]["path"])
            with zipfile.ZipFile(apk) as archive:
                entries = archive.infolist()
        except (OSError, KeyError, IndexError, ValueError, zipfile.BadZipFile):
            continue
        base, dicts, t9 = 0, {}, None
        for e in entries:
            name = e.filename
            if name == "assets/rime_chinese/t9_ngram.lm":
                t9 = e.compress_size / max(e.file_size, 1)
            elif name.startswith("assets/bundled-models/"):
                continue
            elif name.startswith("assets/cell_dictionaries/cell_"):
                dicts[name.rsplit("/", 1)[1][len("cell_"):-len(".dict.yaml")]] = e.compress_size
            else:
                base += e.compress_size
        return {"base": base, "dicts": dicts, "ratios": dict(RATIO, t9=t9 or RATIO["t9"]), "from": apk.name}
    return {"base": 113 * 1024 * 1024, "dicts": {}, "ratios": RATIO, "from": None}


def model_status(config: dict) -> dict:
    """当前最佳 / 已淘汰 T9 models, mirrored from /mnt/project-files/模型总览.md (D:/CyIME-Data/models/model-status.json)."""
    path = Path(config.get("modelStatus") or Path(config["modelsDir"]) / "model-status.json")
    return ad.read_json(path).get("t9", {}) if path.is_file() else {}


def resolve_model(value: str | None, config: dict) -> Path | None:
    if value in (None, "", "none"):
        return None
    status = model_status(config)
    if value.startswith("@"):
        # Presets name the current best model, never a fixed file, so packaging follows 模型总览 without asking.
        if not status.get(value[1:]):
            raise DeliveryError(f"模型总览里没有 {value[1:]}：请先更新 model-status.json（与 模型总览.md 同步）")
        value = status[value[1:]]
    path = Path(value)
    if not path.is_absolute():
        path = Path(config["modelsDir"]) / value
    if not path.is_file():
        raise DeliveryError(f"找不到九键模型文件：{path}")
    if path.name in set(status.get("retired", [])):
        raise DeliveryError(f"九键模型 {path.name} 在模型总览里已淘汰，不能再打包")
    return path.resolve()


def sdk_adb(root: Path = ROOT) -> str | None:
    candidates = []
    prop = root / "local.properties"
    if prop.exists():
        m = re.search(r"^sdk\.dir\s*=\s*(.+)$", prop.read_text(encoding="utf-8-sig"), re.M)
        if m:
            candidates.append(m[1].strip().replace("\\:", ":").replace("\\\\", "\\"))
    candidates += [p for p in (os.getenv("ANDROID_SDK_ROOT"), os.getenv("ANDROID_HOME")) if p]
    if os.getenv("LOCALAPPDATA"):
        candidates.append(str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
    for c in candidates:
        adb = Path(c) / "platform-tools/adb.exe"
        if adb.is_file():
            return str(adb)
    return shutil.which("adb.exe") or shutil.which("adb")


def list_devices(root: Path = ROOT) -> list[dict]:
    adb = sdk_adb(root)
    if not adb:
        return []
    try:
        out = subprocess.run([adb, "devices", "-l"], capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            model = next((p.split(":", 1)[1] for p in parts[2:] if p.startswith("model:")), "")
            devices.append({"serial": parts[0], "state": parts[1], "model": model})
    return devices


# ---------------------------------------------------------------- 构建选项

class BuildOptions(SimpleNamespace):
    """Everything the GUI can choose. Field names match the CLI flags."""
    DEFAULTS = dict(build_type="release", offline_models=False, offline_ids=(), t9_model=None, exclude_dicts=(),
                    install=True, push_banks=False, serial="", rebuild=False, bump="none", publish=False,
                    latest=False, prerelease=False, keep_draft=False, notes="", yes=False, dry_run=False, check=False,
                    audience="",  # "elder" / "friend": a bundled-model package checked against its spec
                    dev_tools=False,  # developer-only features (handwriting training); only 装到我的设备
                    private_gloss=False,  # bundle the private gloss add-on (config privateGloss); only 装到我的设备
                    cylm=False)  # bundle the CyLM files (config cylm); only 装到我的设备

    def __init__(self, **values):
        merged = dict(self.DEFAULTS)
        merged.update({k: v for k, v in values.items() if v is not None or k == "t9_model"})
        super().__init__(**merged)
        self.exclude_dicts = tuple(sorted(set(self.exclude_dicts)))
        # Choosing individual offline models implies an offline edition; none chosen means all of them.
        self.offline_ids = tuple(sorted(set(self.offline_ids or ())))
        if self.offline_ids:
            self.offline_models = True


def apply_preset(name: str, config: dict) -> dict:
    name = config.get("presetAliases", {}).get(name, name)  # old names keep working
    preset = config["presets"].get(name)
    if preset is None:
        raise DeliveryError(f"没有这个预设：{name}（可用：{'、'.join(config['presets'])}）")
    return dict(preset)


class Staging:
    """Put the chosen T9 model where the Rime manifest looks for it, and remove it afterwards."""

    def __init__(self, root: Path, model: Path | None, work: Path):
        self.root, self.model, self.work = root, model, work
        self.target = root / T9_TARGET
        self.marker = work / "staged.json"
        self.created = False
        self.sha256 = ad.digest(model) if model else None

    def cleanup_stale(self):
        """Remove a model this tool staged before an interrupted run; never someone else's file."""
        if not self.marker.is_file():
            return
        record = ad.read_json(self.marker)
        if self.target.is_file() and self.target.stat().st_size == record.get("size") and ad.digest(self.target) == record.get("sha256"):
            self.target.unlink()
            say("[STAGE] 已移除上次中断时留下的临时九键模型。")
        self.marker.unlink()

    def __enter__(self):
        self.cleanup_stale()
        if self.model is None:
            if self.target.exists():
                raise DeliveryError(f"{T9_TARGET} 已存在（不是打包器放的）。选“不带九键模型”前请先确认谁在用并移走它。")
            return self
        if self.target.exists():
            if ad.digest(self.target) != self.sha256:
                raise DeliveryError(f"{T9_TARGET} 已有另一份模型（不是打包器放的），没有覆盖。")
            say("[STAGE] 源码目录里已有同一份九键模型，直接使用，打包后保留原样。")
            return self
        self.work.mkdir(parents=True, exist_ok=True)
        ad.write_json(self.marker, {"sha256": self.sha256, "size": self.model.stat().st_size})
        try:
            os.link(self.model, self.target)  # same volume: no extra disk space
        except OSError:
            shutil.copyfile(self.model, self.target)
        self.created = True
        say(f"[STAGE] 九键模型 {self.model.name} 已临时放入 {T9_TARGET}")
        return self

    def __exit__(self, *exc):
        if self.created:
            with contextlib.suppress(FileNotFoundError):
                self.target.unlink()
            self.marker.unlink(missing_ok=True)
            say("[STAGE] 临时九键模型已移除，源码目录恢复原样。")
        return False


class GlossStaging:
    """Put the private gloss add-on under assets for 装到我的设备 only, and remove it afterwards (same rules as the T9 model)."""

    def __init__(self, root: Path, source: Path | None, work: Path):
        self.root, self.source, self.work = root, source, work
        self.target = root / GLOSS_TARGET
        self.marker = work / "staged-gloss.json"
        self.created = False
        self.sha256 = ad.digest(source) if source else None

    def cleanup_stale(self):
        if not self.marker.is_file():
            return
        record = ad.read_json(self.marker)
        if self.target.is_file() and self.target.stat().st_size == record.get("size") and ad.digest(self.target) == record.get("sha256"):
            self.target.unlink()
            say("[STAGE] 已移除上次中断时留下的临时私人释义包。")
        self.marker.unlink()

    def __enter__(self):
        self.cleanup_stale()
        if self.source is None:
            if self.target.exists():
                raise DeliveryError(f"{GLOSS_TARGET} 已存在（不是打包器放的），这个包不该带私人释义，请先移走它。")
            return self
        if self.target.exists():
            if ad.digest(self.target) != self.sha256:
                raise DeliveryError(f"{GLOSS_TARGET} 已有另一份文件（不是打包器放的），没有覆盖。")
            say("[STAGE] 源码目录里已有同一份私人释义包，直接使用，打包后保留原样。")
            return self
        self.work.mkdir(parents=True, exist_ok=True)
        self.created_dir = not self.target.parent.exists()
        self.target.parent.mkdir(parents=True, exist_ok=True)
        ad.write_json(self.marker, {"sha256": self.sha256, "size": self.source.stat().st_size})
        shutil.copyfile(self.source, self.target)
        self.created = True
        say(f"[STAGE] 私人释义包 {self.source.name} 已临时放入 {GLOSS_TARGET}")
        return self

    def __exit__(self, *exc):
        if self.created:
            with contextlib.suppress(FileNotFoundError):
                self.target.unlink()
            self.marker.unlink(missing_ok=True)
            if getattr(self, "created_dir", False):
                with contextlib.suppress(OSError):
                    self.target.parent.rmdir()  # only the empty assets/gloss directory this run created
            say("[STAGE] 临时私人释义包已移除，源码目录恢复原样。")
        return False


class CylmStaging:
    """Put the CyLM files under rime_chinese for 装到我的设备 only, and remove them afterwards (same rules as the T9 model)."""

    def __init__(self, root: Path, files: dict[str, Path], work: Path):
        self.root, self.files, self.work = root, files, work
        self.marker = work / "staged-cylm.json"
        self.created: list[Path] = []
        self.sha256 = {name: ad.digest(src) for name, src in files.items()}

    def cleanup_stale(self):
        if not self.marker.is_file():
            return
        for name, record in ad.read_json(self.marker).items():
            target = self.root / CYLM_DIR / name
            if target.is_file() and target.stat().st_size == record.get("size") and ad.digest(target) == record.get("sha256"):
                target.unlink()
                say(f"[STAGE] 已移除上次中断时留下的临时 CyLM 文件 {name}。")
        self.marker.unlink()

    def __enter__(self):
        self.cleanup_stale()
        stray = sorted(p.name for p in (self.root / CYLM_DIR).glob("cylm_*") if p.name not in self.files)
        if stray or (not self.files and any((self.root / CYLM_DIR).glob("cylm_*"))):
            raise DeliveryError(f"{CYLM_DIR} 里有不是打包器放的 CyLM 文件：{'、'.join(stray)}，请先确认谁在用并移走。")
        if not self.files:
            return self
        self.work.mkdir(parents=True, exist_ok=True)
        ad.write_json(self.marker, {name: {"sha256": self.sha256[name], "size": src.stat().st_size}
                                    for name, src in self.files.items()})
        for name, src in self.files.items():
            target = self.root / CYLM_DIR / name
            if target.exists():
                if ad.digest(target) != self.sha256[name]:
                    raise DeliveryError(f"{CYLM_DIR}/{name} 已有另一份文件（不是打包器放的），没有覆盖。")
                continue
            try:
                os.link(src, target)  # same volume: no extra disk space
            except OSError:
                shutil.copyfile(src, target)
            self.created.append(target)
        say("[STAGE] CyLM 文件已临时放入 " + CYLM_DIR + "：" + "、".join(self.files))
        return self

    def __exit__(self, *exc):
        if self.files:
            for target in self.created:
                with contextlib.suppress(FileNotFoundError):
                    target.unlink()
            self.marker.unlink(missing_ok=True)
            say("[STAGE] 临时 CyLM 文件已移除，源码目录恢复原样。")
        return False


def public_violations(names: list[str], texts: dict[str, bytes]) -> list[str]:
    """What must never be in a package that leaves the user's own devices: the private gloss, the CyLM files, or any reference to the retired v9 gloss."""
    problems = [f"包内含私人文件 {n}" for n in names if n in PUBLIC_FORBIDDEN_ASSETS or n.startswith(PUBLIC_FORBIDDEN_PREFIXES)]
    for n in names:
        if any(k in n for k in PUBLIC_FORBIDDEN_REFERENCES):
            problems.append(f"包内含已淘汰文件 {n}")
    for n, data in texts.items():
        for k in PUBLIC_FORBIDDEN_REFERENCES:
            if k.encode() in data:
                problems.append(f"{n} 引用了 {k}")
    return problems


class Packager(ad.Delivery):
    def __init__(self, root: Path, opts: BuildOptions, runner=None, config=None):
        self.p = opts
        self.config = config or load_config(root)
        self.kind = opts.build_type
        self.edition = "full" if opts.offline_models else "standard"
        self.model = resolve_model(opts.t9_model, self.config)
        known = {d["id"] for d in list_dicts(root)}
        unknown = set(opts.exclude_dicts) - known
        if unknown:
            raise DeliveryError("没有这些内置词库：" + "、".join(sorted(unknown)))
        self.offline = offline_catalog(root) if opts.offline_models else []
        unknown = set(opts.offline_ids) - {m["id"] for m in self.offline}
        if unknown:
            raise DeliveryError("没有这些离线模型：" + "、".join(sorted(unknown)) +
                                "（可用：" + "、".join(m["id"] for m in self.offline) + "）")
        ns = SimpleNamespace(serial=opts.serial, publish=opts.publish, build_only=not opts.install,
                             check_only=opts.check, install_only=False, full=opts.offline_models,
                             both=False, rebuild=opts.rebuild)
        super().__init__(root, ns, runner)
        self.work = self.root / ".gradle/packager"
        self.state_path = self.work / "state.json"
        self.state = ad.read_json(self.state_path) if self.state_path.is_file() else {"builds": {}}
        self.state.setdefault("builds", {})
        self.staging = Staging(self.root, self.model, self.work)
        self.gloss = None
        if opts.private_gloss:
            if opts.audience or opts.publish:
                raise DeliveryError("私人释义包只能进“装到我的设备”，老人包、朋友包和公开版都不带。")
            self.gloss = Path(self.config.get("privateGloss") or "")
            if not self.gloss.is_file():
                raise DeliveryError(f"找不到私人释义包：{self.gloss}（config.json 的 privateGloss）")
        self.gloss_staging = GlossStaging(self.root, self.gloss, self.work)
        self.cylm: dict[str, Path] = {}
        if opts.cylm:
            if opts.audience or opts.publish:
                raise DeliveryError("CyLM 模型文件只能进“装到我的设备”，老人包、朋友包和公开版都不带。")
            self.cylm = {name: Path(src) for name, src in (self.config.get("cylm") or {}).items()}
            missing = [str(src) for src in self.cylm.values() if not src.is_file()]
            if not self.cylm or missing:
                raise DeliveryError("找不到 CyLM 文件（config.json 的 cylm）：" + ("、".join(missing) or "没有配置"))
        self.cylm_staging = CylmStaging(self.root, self.cylm, self.work)
        if opts.publish and self.kind != "release":
            raise DeliveryError("调试版不能发布到 GitHub Release。")

    # -- identity --------------------------------------------------------
    def variant(self) -> dict:
        return {"buildType": self.kind, "offlineModels": self.p.offline_models,
                "t9Model": {"name": self.model.name, "sha256": self.staging.sha256} if self.model else None,
                "excludedDicts": list(self.p.exclude_dicts),
                # Only present for a partial offline edition, so earlier build records stay valid.
                **({"offlineIds": list(self.p.offline_ids)} if self.p.offline_ids else {}),
                **({"audience": self.p.audience} if self.p.audience else {}),
                **({"devTools": True} if self.p.dev_tools else {}),
                **({"privateGloss": self.gloss_staging.sha256} if self.gloss else {}),
                **({"cylm": self.cylm_staging.sha256} if self.cylm else {})}

    def variant_id(self) -> str:
        return hashlib.sha256(json.dumps(self.variant(), sort_keys=True).encode()).hexdigest()[:12]

    def fingerprint(self, include_submodule_changes=True):
        base = super().fingerprint(include_submodule_changes)
        return hashlib.sha256((base + self.variant_id()).encode()).hexdigest()

    def delivered_versions(self):
        return delivered_versions(self.root)

    def receipt_path(self, edition):
        return self.root / f"app/build/outputs/apk/editions/{self.version}/{edition}/{self.kind}/latest.json"

    def require_committed_sources(self):
        # The staged model is a build input, not a source change; it is removed after the run.
        target = T9_TARGET
        changes = self.git("diff", "--name-only", "--ignore-submodules=dirty", "HEAD", "--", *ad.SOURCE_PATHS)
        new = self.git("ls-files", "--others", "--exclude-standard", "--", *ad.SOURCE_PATHS)
        paths = [p for p in (changes + "\n" + new).splitlines() if p and ad.source_input(p) and p != target]
        if paths:
            raise DeliveryError("发布需要先提交源码改动（本地构建/安装不需要）：\n" + "\n".join(paths[:15]))

    # -- artifacts -------------------------------------------------------
    def load_artifact(self, edition, require_source=True):
        receipt = ad.read_json(self.receipt_path(edition))
        expected = {"version": self.version, "versionCode": self.code, "buildType": self.kind,
                    "bundledModels": edition == "full"}
        if any(receipt.get(k) != v for k, v in expected.items()):
            raise DeliveryError(f"{edition} 构建回执和本次选项不符，拒绝使用。")
        if receipt.get("packager", {}).get("variant") != self.variant_id():
            raise DeliveryError("构建回执是另一组打包选项生成的。")
        if require_source and receipt.get("sourceFingerprint") != self.fingerprint():
            raise DeliveryError("构建回执无法证明包含当前源码。")
        files = receipt.get("files", [])
        if len(files) != 1:
            raise DeliveryError("每次只应产出一个 ARM64 安装包。")
        apk = Path(files[0]["path"]).resolve()
        if self.root not in apk.parents or not apk.name.endswith("-arm64-v8a.apk"):
            raise DeliveryError("安装包不在本仓库内或架构不对。")
        sha = ad.digest(apk)
        if sha != files[0]["sha256"].lower():
            raise DeliveryError("安装包哈希和构建回执不一致。")
        return {"path": str(apk), "sha256": sha, "edition": edition}

    def build(self, edition):
        key = f"{self.version}/{self.code}/{self.variant_id()}"
        before = self.fingerprint()
        cached = self.state["builds"].get(key, {})
        if not self.p.rebuild and cached.get("fingerprint") == before:
            try:
                result = self.load_artifact(edition)
                if result["sha256"] == cached.get("sha256"):
                    say("[REUSE] 源码和选项都没变，直接用上次校验过的安装包。")
                    return result
            except (OSError, ValueError, DeliveryError):
                pass
        if self.kind == "release" and str(self.code) in self.delivered_versions():
            raise DeliveryError(f"版本 {self.version}/{self.code} 已经装到过设备上，内容变了要先升版本号"
                                "（加 --bump patch，或在界面里勾选升版本）。")
        others = {v for v in built_variants(self.root, self.code) if v != self.variant_id()}
        if self.kind == "release" and others:
            # One version, one release: the lite and full packages of the same commit share the version number
            # (AGENTS.md: 普通版／内置版共用版本). Anything built from other source still needs a new number.
            same_commit = set()
            for receipt in (self.root / f"app/build/outputs/apk/editions/{self.version}").glob(f"*/{self.kind}/latest.json"):
                data = ad.read_json(receipt)
                if data.get("sourceCommit") == self.commit and data.get("versionCode") == self.code:
                    same_commit.add((data.get("packager") or {}).get("variant"))
            foreign = others - same_commit
            if foreign:
                raise DeliveryError(f"版本 {self.version}/{self.code} 已经打过另一种内容的正式包（不是同一提交的轻量/完整版），"
                                    "两个包不能同一个版本号；请升版本号（--bump auto 或 patch）。")
            say(f"[VARIANT] 同一提交 {self.commit[:8]} 的另一版安装包已打过，轻量版与完整版共用 {self.version}/{self.code}。")
        from storage_guard import check_storage
        check_storage(self.root)
        self.stage(f"BUILD-{self.kind.upper()}-{edition.upper()}")
        if os.name == "nt":
            class MemoryStatus(ctypes.Structure):
                _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
                    (x, ctypes.c_ulonglong) for x in ("total", "available", "totalPage", "availablePage",
                                                      "totalVirtual", "availableVirtual", "extended")]
            memory = MemoryStatus()
            memory.length = ctypes.sizeof(memory)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)) and memory.available < 3 * 1024**3:
                raise DeliveryError("可用内存不足 3 GiB，没有开始构建；也没有结束任何已有进程。")
        main_before = self.fingerprint(False)
        self.script("build-apk.ps1", self.build_parameters())
        if self.kind == "release":
            # Record the package as soon as it exists: even if a later check stops, the APK may be used.
            produced = ad.read_json(self.receipt_path(edition))["files"][0]["sha256"]
            self.state.setdefault("outputs", {}).setdefault(str(self.code), {})[self.variant_id()] = produced
            ad.write_json(self.state_path, self.state)
        if self.git("rev-parse", "HEAD") != self.commit or self.fingerprint(False) != main_before:
            raise DeliveryError("构建期间源码有变动，拒绝安装或发布这个不确定的包。")
        fingerprint = self.fingerprint()
        receipt = ad.read_json(self.receipt_path(edition))
        receipt["sourceFingerprint"] = fingerprint
        receipt["packager"] = {"variant": self.variant_id(), **self.variant()}
        ad.write_json(self.receipt_path(edition), receipt)
        result = self.load_artifact(edition)
        self.state["builds"][key] = {"fingerprint": fingerprint, "sha256": result["sha256"], **self.variant()}
        ad.write_json(self.state_path, self.state)
        return result

    def gradle_arguments(self) -> list[str]:
        args = ad.GRADLE_ARGS + ["-I", str(self.root / "scripts/phone-release-abi.gradle"),
                                 "-I", str(HERE / "packager.gradle"),
                                 "-PpythonExecutable=" + sys.executable]
        if self.p.offline_ids:
            args.append("-PbundledModelIds=" + ",".join(self.p.offline_ids))
        if self.p.exclude_dicts:
            args.append("-PcyimeIgnoreAssets=" + ":".join(f"cell_{i}.dict.yaml" for i in self.p.exclude_dicts))
        if self.p.audience == "elder":
            args.append("-PelderEdition=true")
        if self.p.dev_tools:
            if self.p.audience or self.p.publish:
                raise DeliveryError("开发者功能（手写训练）只能出现在“装到我的设备”，老人包、朋友包和公开版都不带。")
            args.append("-PdevTools=true")
        return args

    def build_parameters(self) -> str:
        parameters = f"-BuildType {self.kind.capitalize()} -GradleArguments @(" + ",".join(ad.ps_quote(x) for x in self.gradle_arguments()) + ")"
        if self.p.offline_models:
            parameters += " -BundleModels"
        return parameters

    def verify_apk(self, artifact):
        self.stage("VERIFY")
        path = artifact["path"]
        if ad.digest(Path(path)) != artifact["sha256"]:
            raise DeliveryError("安装包在构建后被改动。")
        command = f"& {ad.ps_quote(self.signer)} verify --verbose --min-sdk-version 23 --print-certs {ad.ps_quote(path)}; exit $LASTEXITCODE"
        ad.check_signature_schemes(self.r.run([self.ps, "-NoProfile", "-NonInteractive", "-Command", command]))
        badging = self.r.run([self.aapt, "dump", "badging", path])
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        if not match or match.groups() != (ad.PACKAGE, str(self.code), self.version):
            raise DeliveryError("安装包里的包名/版本和源码不一致。")
        if self.kind == "release" and "application-debuggable" in badging:
            raise DeliveryError("正式版不应是可调试包。")
        manifest = self.r.run([self.aapt, "dump", "xmltree", path, "AndroidManifest.xml"])
        if not ime_label_ok(manifest):
            raise DeliveryError("输入法服务没有写死的名字“CyIME”，系统设置可能显示成类名。")
        import zipfile
        with zipfile.ZipFile(path) as apk:
            names = apk.namelist()
            data = inspect_input_data(apk)
            check_input_data(data, self.staging.sha256 if self.model else None,
                             self.gloss_staging.sha256 if self.gloss else None, self.cylm_staging.sha256)
            # Text assets that could point at a gloss file: catalogs, manifests, yaml/json/tsv/txt under assets.
            texts = {n: apk.read(n) for n in names
                     if n.startswith("assets/") and n.rsplit(".", 1)[-1] in ("json", "yaml", "yml", "txt", "tsv", "md", "list")
                     and not n.startswith("assets/gloss/")}
        if not self.gloss and not self.cylm:
            problems = public_violations(names, texts)
            if problems:
                raise DeliveryError("安装包带了不能离开本机的内容：" + "；".join(problems))
            say("[VERIFY] 公开内容检查通过：无私人释义包，无 cyime_gloss-v9 引用。")
        if self.p.audience in ("elder", "friend"):
            t9 = self.staging.sha256 if self.model else None
            if self.p.audience == "elder":
                best = resolve_model("@best_private", self.config)
                problems = elder_violations(names, t9, ad.digest(best) if best else None)
                spec, ok = "elder-pack-spec.md", "老人包检查通过：无日语资源，内置模型齐全，九键为模型总览的当前最佳私人模型。"
            else:
                public, private = (resolve_model(k, self.config) for k in ("@best_public", "@best_private"))
                problems = friend_violations(names, t9, ad.digest(public) if public else None,
                                             ad.digest(private) if private else None,
                                             self.r.run([self.aapt, "dump", "xmltree", path, "AndroidManifest.xml"]))
                spec, ok = "friend-pack-spec.md", "朋友包检查通过：日语资源在、默认不开，字幕桌面图标默认关，内置模型齐全，九键为公开当前最佳模型。"
            if problems:
                raise DeliveryError(f"安装包不符合 docs/development/{spec}：" + "；".join(problems))
            say("[VERIFY] " + ok)

        receipt_path = self.receipt_path(self.edition)
        receipt = ad.read_json(receipt_path)
        receipt["inputData"] = data
        ad.write_json(receipt_path, receipt)
        say(f"[VERIFY] 九键模型：{self.model.name if self.model else '无'}；私人释义：{'有' if self.gloss else '无'}；"
            f"中文联想：公共基础表，分类词库 {len(data['builtinCellDictionaries'])} 个。"
            "本机学习和导入的个人联想方案保存在设备上，可在「智能联想 › 用户学习数据」看实际条数。")

    def push_banks(self) -> str:
        """Copy the external handwriting-training banks to this device. Never fails the install:
        they stay outside every APK, and a missing folder or a failed push is only reported."""
        conf = self.config.get("handwritingBanks", {})
        source, target = Path(conf.get("dir", "")), conf.get("deviceDir", "")
        files = sorted(source.glob("*.tsv")) if conf and source.is_dir() else []
        if not files:
            return f"手写训练题库未放置：{source} 不存在或没有 .tsv 文件。"
        self.stage("BANKS")
        try:
            self.r.run([self.adb, "-s", self.serial, "shell", "mkdir", "-p", target])
            for f in files:
                self.r.run([self.adb, "-s", self.serial, "push", str(f), f"{target}/{f.name}"], timeout=600)
        except (DeliveryError, OSError, subprocess.SubprocessError) as exc:
            return f"手写训练题库未放置：推送失败（{str(exc).splitlines()[0]}）。安装不受影响。"
        return f"手写训练题库已放到设备 {target}：" + "、".join(f.name for f in files)

    # -- publication -----------------------------------------------------
    def publish(self, artifacts):
        self.stage("PUBLISH")
        self.publication_gate([a["edition"] for a in artifacts])
        self.check_remote_tag()
        info = self.release_info()
        if info is None:
            notes = Path(self.p.notes) if self.p.notes else self.root / f"docs/{self.version}.md"
            if not notes.is_file():
                notes = self.work / "release-notes.md"
                history = self.git("log", "-20", "--format=- %s")
                notes.write_text(f"# CyIME {self.version}\n\nSource: `{self.commit}`\n\n"
                                 "Recent commits (not a test/acceptance claim):\n\n" + history + "\n", encoding="utf-8")
            self.r.run(["gh", "release", "create", self.version, "--repo", self.repo, "--target", self.commit,
                        "--title", f"CyIME {self.version}", "--notes-file", str(notes), "--draft"], timeout=180)
            info = self.release_info()
            if info is None:
                raise DeliveryError("创建了 Release 但查不到。")
        self.verify_release_target(info)
        for artifact in artifacts:
            name = Path(artifact["path"]).name
            match = next((a for a in info.get("assets", []) if a["name"] == name), None)
            if match:
                if not self.remote_asset_matches(match, artifact):
                    raise DeliveryError(f"Release 上已有不同的 {name}，不会覆盖已发布文件。")
            elif info.get("draft"):
                self.r.run(["gh", "release", "upload", self.version, artifact["path"], "--repo", self.repo], timeout=1800)
            else:
                raise DeliveryError(f"已公开的 Release 缺少 {name}，拒绝修改已完成的发布。")
        info = self.release_info()
        for artifact in artifacts:
            name = Path(artifact["path"]).name
            item = next((a for a in info["assets"] if a["name"] == name), None)
            if not item or not self.remote_asset_matches(item, artifact):
                raise DeliveryError(f"上传后校验失败：{name}")
        if info.get("draft") and self.p.keep_draft:
            # One release per version: the lite and full packages share the tag, so the first build leaves
            # the draft open and the second build (without --keep-draft) publishes it with both assets.
            self.state["published"] = {"tag": self.version, "commit": self.commit, "url": info["html_url"], "draft": True}
            ad.write_json(self.state_path, self.state)
            say(f"[PUBLISH] 草稿 {self.version} 已上传本包，保持草稿等另一版安装包；之后不带 --keep-draft 再发布即公开。")
            say(info["html_url"])
            return
        if info.get("draft"):
            flags = ["--draft=false", "--latest" if self.p.latest else "--latest=false"]
            if self.p.prerelease:
                flags.append("--prerelease")
            self.r.run(["gh", "release", "edit", self.version, "--repo", self.repo, *flags])
        final = self.release_info()
        if not final or final.get("draft"):
            raise DeliveryError("Release 还没公开；重新运行同一命令可继续。")
        self.state["published"] = {"tag": self.version, "commit": self.commit, "url": final["html_url"]}
        ad.write_json(self.state_path, self.state)
        say(final["html_url"])
        if not self.p.prerelease:
            self.mirror_to_modelscope()

    def mirror_to_modelscope(self):
        """国内连不上 GitHub：公开后把同一版安装包和版本清单传到魔搭，App 的检查更新国内先查那里。
        GitHub 已经发布完，这一步失败只提示，之后单独运行同一条命令补传。"""
        command = [sys.executable, "-B", "scripts/store/sync_modelscope.py", "app", self.version]
        self.stage("MODELSCOPE")
        try:
            out = self.r.run(command, timeout=3600)
        except (DeliveryError, OSError, subprocess.SubprocessError) as exc:
            say(f"[MODELSCOPE] 没传上魔搭（GitHub 已发布）：{str(exc).splitlines()[0]}\n"
                f"  之后运行：py -3 -B scripts\\store\\sync_modelscope.py app {self.version}")
            return
        say(out.splitlines()[-1] if out else "[MODELSCOPE] 已传到魔搭")

    # -- run -------------------------------------------------------------
    def plan(self) -> list[str]:
        p = self.p
        lines = [f"版本：{self.version} / {self.code}",
                 f"构建：{'正式版 Release' if self.kind == 'release' else '调试版 Debug'}，"
                 f"{'带离线模型' if p.offline_models else '轻量版（不带离线模型）'}，ARM64"]
        if p.offline_models:
            chosen = [m for m in self.offline if not p.offline_ids or m["id"] in p.offline_ids]
            lines.append("离线模型：" + "、".join(f"{m['name']}（{human(m['size'])}）" for m in chosen))
        if self.model:
            lines.append(f"九键模型：{self.model.name}（{human(self.model.stat().st_size)}，SHA256 {self.staging.sha256[:16]}…）"
                         f"临时放入 {T9_TARGET}，结束后移除")
        else:
            lines.append("九键模型：不带（九键回到旧算法，用户可在模型管理里下载）")
        if self.gloss:
            lines.append(f"私人释义包：{self.gloss.name}（{human(self.gloss.stat().st_size)}，SHA256 {self.gloss_staging.sha256[:16]}…）"
                         f"临时放入 {GLOSS_TARGET}，结束后移除")
        if self.cylm:
            lines.append("CyLM：" + "、".join(f"{src.name} → {name}（{human(src.stat().st_size)}）" for name, src in self.cylm.items())
                         + f" 临时放入 {CYLM_DIR}，结束后移除")
        dicts = list_dicts(self.root)
        kept = [d["name"] for d in dicts if d["id"] not in p.exclude_dicts]
        dropped = [d["name"] for d in dicts if d["id"] in p.exclude_dicts]
        lines.append("内置词库：" + ("、".join(kept) or "无") + (f"；去掉 {'、'.join(dropped)}" if dropped else ""))
        lines.append("Gradle：build-apk.ps1 " + self.build_parameters())
        if p.install:
            lines.append(f"安装：{p.serial or '唯一已授权设备'}（adb install -r，保留数据，不降级不卸载）")
            if p.push_banks:
                lines.append("手写训练题库：安装后推到设备（不进安装包；找不到或推送失败只提示，不影响安装）")
        else:
            lines.append("安装：不安装，只出包")
        if p.publish:
            lines.append(f"发布：GitHub Release {self.version}（{'设为 Latest' if p.latest else '不设为 Latest'}"
                         f"{'，预发布' if p.prerelease else ''}）；需要源码已提交并推送"
                         f"{'' if p.prerelease else '；公开后同一版安装包传到魔搭'}")
        return lines

    def execute(self):
        say(f"[SOURCE] {self.root}\n[VERSION] {self.version} / {self.code}")
        for line in self.plan():
            say("[PLAN] " + line)
        if self.p.dry_run:
            say("\n干跑结束：没有构建、安装、发布或修改任何文件。")
            return
        self.preflight()
        if self.p.check:
            self.dependencies(prepare=False)
            say(f"[CHECKED] Java、SDK、签名配置、原生源码齐全。设备：{self.serial or '无'}。")
            return
        self.check_delivered_version()
        self.dependencies()
        with self.staging, self.gloss_staging, self.cylm_staging:
            if self.p.publish:
                self.check_remote_tag()
                existing = self.release_info()
                if existing:
                    self.verify_release_target(existing)
            artifact = self.build(self.edition)
            self.verify_apk(artifact)
            key = f"{self.version}/{self.code}/{self.variant_id()}"
            if self.state["builds"][key]["fingerprint"] != self.fingerprint():
                raise DeliveryError("构建后源码有变动，停止安装和发布。")
            banks = None
            if self.p.install and self.serial:
                self.install(artifact)
                if self.p.push_banks:
                    banks = self.push_banks()
            if self.p.publish:
                self.publish([artifact])
        pending = self.p.install and not self.serial
        self.stage("INSTALL-PENDING" if pending else "DONE")
        say(artifact["path"])
        say(f"SHA256 {artifact['sha256']}  {Path(artifact['path']).stat().st_size} 字节")
        if self.p.install and self.serial:
            say(f"已安装 {self.version}/{self.code} 到 {self.serial}，数据保留。")
            if banks:
                say(banks)
        elif pending:
            say("安装包已就绪，但没有已授权设备；连上设备后重新运行同样的命令即可直接安装。")
        say("功能验收：本程序不做真机功能验收。")


# ---------------------------------------------------------------- 资源上传

class ResourceUploader:
    def __init__(self, root: Path, tag: str, files: list[Path], names: list[str], replace: bool,
                 yes: bool, dry_run: bool, title: str = "", runner=None):
        self.root, self.tag, self.replace, self.yes, self.dry_run = root, tag, replace, yes, dry_run
        self.title = title or "CyIME 模型文件"
        self.r = runner or ad.Runner(root)
        if names and len(names) != len(files):
            raise DeliveryError("--name 的个数要和文件个数一致。")
        self.items = []
        for i, file in enumerate(files):
            path = Path(file).resolve()
            if not path.is_file():
                raise DeliveryError(f"找不到文件：{path}")
            for private in PRIVATE_DIRS:
                if (self.root / private).resolve() in path.parents:
                    raise DeliveryError(f"拒绝上传 {private} 里的文件（私人数据）。")
            name = names[i] if names else path.name
            if not ASSET_NAME.fullmatch(name):
                raise DeliveryError(f"文件名只能用英文字母、数字、点、横线、下划线：{name}")
            self.items.append({"path": path, "name": name, "size": path.stat().st_size, "sha256": ad.digest(path)})
        self.repo = ""

    def repo_name(self):
        remote = self.r.run(["git", "remote", "get-url", "origin"])
        match = re.fullmatch(r"(?:https://github\.com/|git@github\.com:)([^/]+/[^/]+?)(?:\.git)?/?", remote)
        if not match:
            raise DeliveryError("origin 不是 github.com 仓库。")
        return match[1]

    def release(self):
        raw = self.r.run(["gh", "api", "--paginate", f"repos/{self.repo}/releases?per_page=100"], timeout=180)
        decoder = json.JSONDecoder()
        while raw.strip():
            raw = raw.lstrip()
            page, used = decoder.raw_decode(raw)
            for release in page:
                if release["tag_name"] == self.tag:
                    return release
            raw = raw[used:]
        return None

    def run(self):
        for item in self.items:
            say(f"[FILE] {item['name']}  {human(item['size'])}  SHA256 {item['sha256']}  ← {item['path']}")
        if not shutil.which("gh"):
            raise DeliveryError("需要 GitHub CLI（gh）并已登录。")
        self.r.run(["gh", "auth", "status"])
        self.repo = self.repo_name()
        info = self.release()
        actions = []
        if info is None:
            actions.append(f"新建资源 Release “{self.tag}”（不设为 Latest）")
        for item in self.items:
            remote = next((a for a in (info or {}).get("assets", []) if a["name"] == item["name"]), None)
            if remote is None:
                item["action"] = "upload"
                actions.append(f"上传 {item['name']}")
            elif (remote.get("digest") or "").lower() == "sha256:" + item["sha256"]:
                item["action"] = "same"
                actions.append(f"{item['name']} 已是同一文件，跳过")
            elif self.replace:
                item["action"] = "replace"
                actions.append(f"替换 {item['name']}（旧文件会被覆盖，已下载的用户校验会失败，需同时更新目录里的哈希）")
            else:
                raise DeliveryError(f"{self.tag} 上已有不同的 {item['name']}。要覆盖请加 --replace，或换一个文件名。")
        for action in actions:
            say("[PLAN] " + action)
        if self.dry_run:
            say("\n干跑结束：没有上传或修改任何东西。")
            return
        if any(i["action"] != "same" for i in self.items) or info is None:
            if not self.yes:
                raise DeliveryError("上传到 GitHub 所有人可见；确认后加 --yes 再运行（界面里会弹窗确认）。")
        if info is None:
            notes = "CyIME 应用下载用的模型和资源文件。应用本身请看 Latest 版本。"
            self.r.run(["gh", "release", "create", self.tag, "--repo", self.repo, "--title", self.title,
                        "--notes", notes, "--latest=false"], timeout=180)
        staging = self.root / ".gradle/packager/upload"
        for item in self.items:
            if item["action"] == "same":
                continue
            source = item["path"]
            if source.name != item["name"]:
                staging.mkdir(parents=True, exist_ok=True)
                renamed = staging / item["name"]
                renamed.unlink(missing_ok=True)
                try:
                    os.link(source, renamed)
                except OSError:
                    shutil.copyfile(source, renamed)
                source = renamed
            try:
                args = ["gh", "release", "upload", self.tag, str(source), "--repo", self.repo]
                if item["action"] == "replace":
                    args.append("--clobber")
                say(f"[UPLOAD] {item['name']} …")
                self.r.run(args, timeout=7200, live=True)
            finally:
                if source != item["path"]:
                    source.unlink(missing_ok=True)
        info = self.release()
        for item in self.items:
            remote = next((a for a in info.get("assets", []) if a["name"] == item["name"]), None)
            if not remote or (remote.get("digest") or "").lower() != "sha256:" + item["sha256"]:
                raise DeliveryError(f"上传后校验失败：{item['name']}")
            url = f"https://github.com/{self.repo}/releases/download/{self.tag}/{item['name']}"
            say(f"[DONE] {url}\n       sha256 {item['sha256']}  size {item['size']}L")
        say("商店目录或模型清单若引用这些文件，请把上面的地址、哈希、大小填进去。")


# ---------------------------------------------------------------- 命令行

def info(root: Path = ROOT) -> dict:
    config = load_config(root)
    version, code = read_version(root)
    versions = delivered_versions(root)
    last = max(map(int, versions)) if versions else None
    return {"version": version, "versionCode": code,
            "lastDelivered": {"version": versions[str(last)], "versionCode": last} if last else None,
            "needsBump": needs_bump(root),
            "patch": bumped(version, code, "patch"), "minor": bumped(version, code, "minor"),
            "models": list_models(config), "dicts": list_dicts(root), "devices": list_devices(root),
            "offline": [dict(m, note=config.get("modelNotes", {}).get(m["id"], "")) for m in offline_catalog(root)],
            "sizes": size_estimate(root),
            "presets": config["presets"], "resourceTag": config["resourceTag"],
            "gh": bool(shutil.which("gh"))}


def build_options_from_args(a, config) -> BuildOptions:
    values = apply_preset(a.preset, config) if a.preset else {}
    for key in BuildOptions.DEFAULTS:
        value = getattr(a, key, None)
        if value not in (None, False, [], ()):
            values[key] = value
    if a.t9_model is not None:
        values["t9_model"] = a.t9_model
    if a.no_install:
        values["install"] = False
    if getattr(a, "push_banks", None) is False:
        values["push_banks"] = False
    if getattr(a, "cylm", None) is False:
        values["cylm"] = False
    if a.offline_models is False:
        values["offline_models"] = False
    if a.not_latest:
        values["latest"] = False
    return BuildOptions(**values)


def run_build(a) -> int:
    config = load_config()
    opts = build_options_from_args(a, config)
    if opts.bump == "auto":
        variant = Packager(ROOT, opts, ad.Runner(ROOT), config).variant_id()
        opts.bump = "patch" if needs_bump(ROOT, variant) else "none"
    planned = None
    if opts.bump in ("patch", "minor"):
        version, code = read_version()
        new = planned = bumped(version, code, opts.bump)
        if opts.dry_run or opts.check:
            say(f"[PLAN] 升版本：{version}/{code} → {new[0]}/{new[1]}（干跑不改文件）")
        else:
            write_version(ROOT, *new)
            say(f"[VERSION] 已升版本：{version}/{code} → {new[0]}/{new[1]}（app/build.gradle.kts）")
    if opts.publish and not (opts.yes or opts.dry_run or opts.check):
        raise DeliveryError("发布 GitHub Release 所有人可见；确认后加 --yes 再运行（界面里会弹窗确认）。")
    runner = ad.Runner(ROOT)
    if opts.dry_run:
        packager = Packager(ROOT, opts, runner, config)
        if planned:
            packager.version, packager.code = planned
        packager.execute()
        return 0
    common = Path(runner.run(["git", "rev-parse", "--git-common-dir"]))
    if not common.is_absolute():
        common = ROOT / common
    # Same lock as auto.cmd: the two tools never build at the same time.
    with ad.delivery_lock(common / "cyime-auto-delivery.lock"):
        packager = Packager(ROOT, opts, runner, config)
        try:
            packager.execute()
        except Exception as exc:
            packager.state["error"] = str(exc)
            ad.write_json(packager.state_path, packager.state)
            raise
    return 0


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        with contextlib.suppress(AttributeError):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="CyIME 打包器（命令行）。不带参数运行 packager.cmd 打开图形界面。")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("info", help="以 JSON 列出版本、设备、九键模型、内置词库、预设")

    inspect = sub.add_parser("inspect-apk", help="读取既有 APK 内的词库和联想数据，不构建、不安装")
    inspect.add_argument("apk", type=Path)
    b = sub.add_parser("build", help="构建（并安装/发布）")
    b.add_argument("--preset", help="先套用 config.json 里的预设，再用下面的参数覆盖")
    t = b.add_mutually_exclusive_group()
    t.add_argument("--release", dest="build_type", action="store_const", const="release", help="正式版（默认）")
    t.add_argument("--debug", dest="build_type", action="store_const", const="debug", help="调试版")
    o = b.add_mutually_exclusive_group()
    o.add_argument("--offline-models", dest="offline_models", action="store_true", default=None,
                   help="带可选离线模型（手写、语音等）；公共联想表也在轻量包中")
    o.add_argument("--no-offline-models", dest="offline_models", action="store_false")
    b.add_argument("--offline-model", dest="offline_ids", action="append", default=[], metavar="ID",
                   help="只放这些离线模型（可重复，ID 见 info 的 offline）；不写则放全部")
    b.add_argument("--t9-model", help="九键词组模型：模型目录里的文件名、完整路径、@best_private／@best_public，或 none")
    b.add_argument("--audience", choices=["elder", "friend"], help="按老人包／朋友包要求构建并检查（一般由预设给出）")
    b.add_argument("--exclude-dict", dest="exclude_dicts", action="append", default=[], metavar="ID",
                   help="去掉某个内置词库（可重复），ID 见 info")
    b.add_argument("--no-install", action="store_true", help="只出包，不安装")
    pb = b.add_mutually_exclusive_group()
    pb.add_argument("--private-gloss", dest="private_gloss", action="store_true", default=None,
                    help="内置私人释义补充包（config.json 的 privateGloss；只用于“装到我的设备”）")
    pb.add_argument("--no-private-gloss", dest="private_gloss", action="store_false")
    cy = b.add_mutually_exclusive_group()
    cy.add_argument("--cylm", dest="cylm", action="store_true", default=None,
                    help="内置 CyLM 选词模型文件（config.json 的 cylm；只用于“装到我的设备”）")
    cy.add_argument("--no-cylm", dest="cylm", action="store_false")
    pb.add_argument("--push-banks", dest="push_banks", action="store_true", default=None,
                    help="安装后把手写训练题库推到设备（“装到我的设备”默认开）")
    pb.add_argument("--no-push-banks", dest="push_banks", action="store_false")
    b.add_argument("--serial", help="安装到指定设备")
    b.add_argument("--rebuild", action="store_true", default=None, help="不复用上次的包")
    b.add_argument("--bump", choices=["none", "auto", "patch", "minor"],
                   help="升版本号；auto=当前版本已装到过设备时升 PATCH")
    b.add_argument("--publish", action="store_true", default=None, help="发布 GitHub Release（需 --yes）")
    lt = b.add_mutually_exclusive_group()
    lt.add_argument("--latest", action="store_true", default=None, help="发布时设为 Latest")
    lt.add_argument("--not-latest", action="store_true", help="发布时不设为 Latest（默认）")
    b.add_argument("--prerelease", action="store_true", default=None)
    b.add_argument("--keep-draft", dest="keep_draft", action="store_true", default=None,
                   help="发布时保持草稿：同一版本的轻量版和完整版共用一个 Release，先发的包用它，后发的包不带它即公开")
    b.add_argument("--notes", help="Release 说明文件（默认 docs/<版本>.md）")
    b.add_argument("--yes", action="store_true", default=None, help="确认对外发布")
    b.add_argument("--dry-run", action="store_true", default=None, help="只列出计划，不做任何改动")
    b.add_argument("--check", action="store_true", default=None, help="只检查环境")

    u = sub.add_parser("upload", help="上传资源文件到资源 Release（默认 models）")
    u.add_argument("files", nargs="+", type=Path)
    u.add_argument("--tag", help="资源 Release 标签（默认 config.json 的 resourceTag）")
    u.add_argument("--name", action="append", default=[], help="上传后的文件名（按顺序对应每个文件）")
    u.add_argument("--replace", action="store_true", help="同名但内容不同时覆盖")
    u.add_argument("--yes", action="store_true", help="确认对外上传")
    u.add_argument("--dry-run", action="store_true")
    a = parser.parse_args(argv)
    try:
        if a.command == "inspect-apk":
            import zipfile
            if not zipfile.is_zipfile(a.apk):
                raise DeliveryError("请选择有效的 APK 文件。")
            with zipfile.ZipFile(a.apk) as archive:
                print(json.dumps(inspect_input_data(archive), ensure_ascii=False, indent=2))
            return 0
        if a.command == "info":
            print(json.dumps(info(), ensure_ascii=False, indent=2))
            return 0
        if a.command == "build":
            return run_build(a)
        tag = a.tag or load_config()["resourceTag"]
        ResourceUploader(ROOT, tag, a.files, a.name, a.replace, a.yes, a.dry_run).run()
        return 0
    except (DeliveryError, OSError, ValueError, subprocess.SubprocessError) as exc:
        print(f"\nSTOP: {exc}\n没有执行清理、卸载、强制覆盖或自动提交。", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
