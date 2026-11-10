#!/usr/bin/env python3
"""Connect the CI source-built native libraries to Gradle's checksum checks."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def pinned_commit(path: Path, expected: str) -> str:
    actual = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    if actual != expected:
        raise ValueError(f"Unexpected source revision in {path.name}: {actual}")
    return actual


def main() -> None:
    work = ROOT / ".codex-artifacts/ci-native"
    azookey = work / "azookey"
    translate = work / "translate"
    revisions = {
        "azookey": pinned_commit(azookey / "akkc-lite", "25a6346bf76931c1cf2214015b808875ea82f4bf"),
        "dictionary": pinned_commit(
            azookey / "akkc-lite/Sources/KanaKanjiConverterModuleWithDefaultDictionary/azooKey_dictionary_storage",
            "4d418525b090cf49c219819d05a7e3cc2a4346eb"),
        "slimt": pinned_commit(translate / "slimt", "fdc0f98706b193bf17cb419f2fe08bf1d9913607"),
    }
    libraries = {
        "azookey": azookey / "out/arm64-v8a/libCyAzooKey.so",
        "translate": translate / "out/arm64-v8a/libcytranslate.so",
    }
    hashes = {}
    for name, path in libraries.items():
        with path.open("rb") as stream:
            header = stream.read(20)
        if header[:6] != b"\x7fELF\x02\x01" or header[16:20] != b"\x03\x00\xb7\x00":
            raise ValueError(f"Not an ARM64 ELF shared library: {path}")
        hashes[name] = sha256(path)
    dictionaries = list(azookey.glob("azookey-louds-mozc-*.zip"))
    if len(dictionaries) != 1:
        raise ValueError("Expected one source-built Mozc supplement archive")
    dictionary = dictionaries[0]
    dictionary_hash = sha256(dictionary)
    if dictionary.name != f"azookey-louds-mozc-{dictionary_hash[:8]}.zip":
        raise ValueError("Source-built dictionary filename checksum mismatch")
    shutil.copyfile(dictionary, azookey / "out" / dictionary.name)
    properties = {
        "cyime.nativeAbis": "arm64-v8a",
        "cyime.azookeyLibDir": (azookey / "out").as_posix(),
        "cyime.translateLibDir": (translate / "out").as_posix(),
        "cyime.azookeyLibrarySha256.arm64-v8a": hashes["azookey"],
        "cyime.translateLibrarySha256.arm64-v8a": hashes["translate"],
        "cyime.azookeyLoudsZipSha256": dictionary_hash,
    }
    # These per-run checksums describe new source builds, not replacement downloads.
    # prepareJapaneseDictionaries still verifies the original pinned merged content hash.
    with (ROOT / "gradle.properties").open("a", encoding="utf-8") as output:
        output.write("\n# Runtime dependencies built from pinned sources in this CI run.\n")
        output.writelines(f"{key}={value}\n" for key, value in properties.items())
    manifest = {
        "source_commits": revisions, "abi": "arm64-v8a",
        "library_sha256": hashes, "dictionary_archive_sha256": dictionary_hash,
    }
    output = ROOT / "build/aosp-release"
    output.mkdir(parents=True, exist_ok=True)
    (output / "native-source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
