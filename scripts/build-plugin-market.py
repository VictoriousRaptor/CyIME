"""Regenerate the optional CyIME catalog and its versioned XIPKs (requires PyYAML)."""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
import hashlib
import yaml

ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / "store/plugins"
PUBLIC = "https://raw.githubusercontent.com/Cyletix/CyIME/main/store/plugins/packages"
PLUGINS = ("ai-reply", "ai-rewrite", "translate", "caption", "japanese")


def package(name):
    source = ROOT / "plugins" / name
    manifest = yaml.safe_load((source / "manifest.yaml").read_text(encoding="utf-8"))
    version = manifest["version"]
    path = STORE / "packages" / f"{name}-{version}.xipk"
    with ZipFile(path, "w") as archive:
        for file in sorted(source.rglob("*")):
            if not file.is_file() or file.name.startswith("."):
                continue
            entry = ZipInfo(file.relative_to(source).as_posix())
            entry.create_system = 3
            data = file.read_bytes()
            if file.suffix in {".lua", ".yaml"}:
                data = data.replace(b"\r\n", b"\n")
            archive.writestr(entry, data, compress_type=ZIP_DEFLATED)
    return {
        "id": manifest["id"], "name": manifest["name"], "description": manifest["description"],
        "pluginType": manifest["type"], "appVersion": ">=" + manifest["minHostVersion"],
        "currentVersion": version, "versions": [{
            "version": version, "downloadUrl": [{
                "url": f"{PUBLIC}/{path.name}", "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }],
        }],
    }


if __name__ == "__main__":
    (STORE / "packages").mkdir(parents=True, exist_ok=True)
    catalog = {"index_version": 1, "plugins": [package(name) for name in PLUGINS]}
    (STORE / "index.yaml").write_text(
        yaml.safe_dump(catalog, allow_unicode=True, sort_keys=False, width=120),
        encoding="utf-8", newline="\n",
    )
