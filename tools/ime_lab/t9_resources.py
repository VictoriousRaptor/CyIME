"""Snapshot existing project Rime resources for host replay, without an APK build."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile


def resource_files(repo, t9_ngram=None):
    repo = Path(repo)
    base = repo / "app/src/main/assets"
    generated = repo / "app/build/generated"
    roots = [("rime_ice", generated / "chinese-assets/rime_ice"),
             ("rime_chinese", base / "rime_chinese"),
             ("rime_chinese", generated / "t9-grammar/rime_chinese"),
             # Optional word n-gram model (tools/ime_lab/ngram); absent until built.
             ("rime_chinese", generated / "t9-ngram/rime_chinese")]
    required = [base / "default.custom.yaml", base / "rime/default.yaml",
                roots[0][1] / "rime_ice.dict.yaml", roots[0][1] / "cn_dicts/base.dict.yaml",
                roots[0][1] / "cyime_t9_english.dict.yaml",
                roots[1][1] / "t9_pinyin.schema.yaml", roots[1][1] / "t9_sentence.onnx",
                roots[1][1] / "t9_sentence.vocab", roots[2][1] / "wanxiang-lts-zh-hans.gram"]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise ValueError("Prepared Rime resources missing (no APK build will be started): " + ", ".join(missing))
    files = {}
    for path in sorted((base / "rime").rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(base / "rime")
        if any(part.startswith(".") for part in relative.parts):
            continue
        files["assets/rime/" + relative.as_posix()] = (path, None)
    files["assets/default.custom.yaml"] = (base / "default.custom.yaml", None)
    # Same Chinese resource precedence and exclusions as tasks-rime-manifest.gradle.kts.
    # Japanese resources are unrelated to the sole deployed t9_pinyin schema.
    targets = {}
    for name, root in roots:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            if (relative.endswith(".md") or relative.startswith("LICENSE") or relative.endswith(".custom.yaml")
                    or (name == "rime_ice" and relative in ("pinyin_qwjrtk.schema.yaml", "pinyin_cyletix10.schema.yaml",
                                                         "pinyin_colemak.schema.yaml", "pinyin_typewise.schema.yaml"))):
                continue
            targets[relative] = ("assets/" + name + "/" + relative, path)
    if t9_ngram is not None:
        # Model A/B (2026-11-11, private p4 vs public v9): the given word model replaces the built one.
        t9_ngram = Path(t9_ngram)
        if not t9_ngram.is_file():
            raise ValueError("Word model not found: " + str(t9_ngram))
        targets["t9_ngram.lm"] = ("assets/rime_chinese/t9_ngram.lm", t9_ngram)
    for destination, (entry, path) in targets.items():
        files[entry] = (path, destination)
    return files


def file_digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def snapshot_resources(repo, output, t9_ngram=None):
    """Write a deterministic ZIP from exact bytes read, recording every source hash."""
    output = Path(output)
    sidecar = output.with_suffix(".json")
    if sidecar == output:
        raise ValueError("Resource ZIP and JSON sidecar must have different paths")
    for path in (output, sidecar):
        if path.exists():
            raise ValueError("Resource snapshot already exists: " + str(path))
    files = resource_files(repo, t9_ngram)
    records, manifest, captured = [], [], []
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for entry, (path, destination) in sorted(files.items()):
            before = path.stat()
            value = hashlib.sha256()
            count = 0
            with path.open("rb") as source, archive.open(zipfile.ZipInfo(entry), "w", force_zip64=True) as target:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    value.update(block)
                    count += len(block)
                    target.write(block)
            after = path.stat()
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or count != before.st_size:
                raise ValueError("Resource changed during snapshot: " + str(path))
            captured.append((path, after.st_size, after.st_mtime_ns))
            item = dict(source=str(path.relative_to(repo)) if path.is_relative_to(repo) else str(path), entry=entry, destination=destination,
                        bytes=count, sha256=value.hexdigest())
            records.append(item)
            if destination is not None:
                manifest.append((destination, f"{item['sha256']}\t{count}\t{entry[len('assets/'):] }\t{destination}"))
        archive.writestr(zipfile.ZipInfo("assets/rime-bundled-manifest.tsv"),
                         "\n".join(line for _, line in sorted(manifest)) + "\n")
        # An earlier source can change while a later large dictionary is copied.
        for path, size, modified in captured:
            current = path.stat()
            if (current.st_size, current.st_mtime_ns) != (size, modified):
                raise ValueError("Resource changed during snapshot: " + str(path))
    result = dict(format_version=1, source="project_existing_resources", sha256=file_digest(output), files=records)
    with sidecar.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return result
