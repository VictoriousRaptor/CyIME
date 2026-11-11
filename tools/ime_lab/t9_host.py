"""Run the project's T9 engine on this computer (Linux directly, Windows via WSL)."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import uuid

import summarize_daily_chat as summary
from t9_fixture import validate_fixture
from t9_replay import DEFAULT_FIXTURE, HERE, REPO, PASSES, compact_report, save
from t9_resources import snapshot_resources, file_digest
from t9_replay_compare import evaluate_known_bad, compare_runs, write_comparison, load_run, combine_exit_codes


def linux_path(path):
    """Only translate an actual Windows drive path, without invoking a shell."""
    value = str(Path(path).resolve())
    if len(value) < 3 or value[1:3] != ":\\" or not value[0].isalpha():
        raise ValueError("WSL replay requires a local Windows drive path: " + value)
    return "/mnt/" + value[0].lower() + "/" + value[3:].replace("\\", "/")


def find_json_jar(explicit=None):
    if explicit:
        result = Path(explicit).resolve()
        if not result.is_file():
            raise ValueError("JSON jar not found: " + str(result))
        return result
    matches = sorted((Path.home() / ".gradle/caches/modules-2/files-2.1/org.json/json").glob("*/*/json-*.jar"))
    if not matches:
        raise ValueError("org.json JAR not cached; supply --json-jar with an existing org.json library.")
    return matches[-1]


class HostCommands:
    def __init__(self, path):
        self.log = Path(path)

    def run(self, args, timeout=3600):
        args = [str(arg) for arg in args]
        with self.log.open("a", encoding="utf-8") as log:
            log.write(json.dumps(args, ensure_ascii=False) + "\n")
            log.flush()
            # Direct file output retains native progress and failures, even after interruption.
            result = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"Host command failed ({result.returncode}); see {self.log}")


def inspect_user_dict(path):
    """Validate a Rime userdb snapshot (*.userdb.txt) before any engine work."""
    path = Path(path).resolve()
    if not path.is_file():
        raise ValueError("User dictionary snapshot not found: " + str(path))
    metadata, entries, deleted, keys = {}, 0, 0, set()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.startswith("#@"):
            key, _, value = line[2:].partition("\t")
            metadata[key] = value
        elif line and not line.startswith("#"):
            fields = line.split("\t")
            commits = re.match(r"c=(-?\d+)(?:\s|$)", fields[2]) if len(fields) == 3 else None
            if len(fields) != 3 or not fields[0].strip() or not fields[1] or not commits:
                raise ValueError(f"{path}:{number}: expected 'code<TAB>text<TAB>c=.. d=.. t=..'")
            if (fields[0].strip(), fields[1]) in keys:
                raise ValueError(f"{path}:{number}: duplicate user dictionary entry")
            keys.add((fields[0].strip(), fields[1]))
            # librime keeps c<0 entries as deletions; the runner counts only live (c>=0) entries after restore.
            if int(commits[1]) >= 0:
                entries += 1
            else:
                deleted += 1
    name = metadata.get("/db_name", "").removesuffix(".userdb")
    if metadata.get("/db_type") != "userdb" or not name or not entries:
        raise ValueError("Not a nonempty Rime userdb snapshot with /db_name and /db_type userdb: " + str(path))
    if "/tick" in metadata and not re.fullmatch(r"\d+", metadata["/tick"]):
        raise ValueError("User dictionary snapshot has a non-numeric /tick: " + str(path))
    return path, {"db_name": name, "entries": entries, "deleted_entries": deleted, "tick": metadata.get("/tick")}


def filesystem_type(path):
    """Filesystem type of `path` from /proc/self/mounts; "unknown" where that is unavailable."""
    if os.name == "nt":  # Windows native replay: the volume's own filesystem name (NTFS on a local disk)
        import ctypes
        anchor = Path(path).resolve().anchor
        name = ctypes.create_unicode_buffer(64)
        if ctypes.windll.kernel32.GetVolumeInformationW(anchor, None, 0, None, None, None, name, len(name)):
            return name.value.lower()
        return "unknown"
    try:
        mounts = Path("/proc/self/mounts").read_text(encoding="utf-8").splitlines()
    except OSError:
        return "unknown"
    target, best, kind = str(path), None, "unknown"
    for line in mounts:
        fields = line.split()
        if len(fields) < 3:
            continue
        point = fields[1].replace("\\040", " ")
        if (target == point or target.startswith(point.rstrip("/") + "/")) and (best is None or len(point) >= len(best)):
            best, kind = point, fields[2]
    return kind


# Host engine library: librime_jni.so from build_host.py (Linux), librime_jni.dll from build_host_windows.py.
NATIVE_GLOB = "*.dll" if os.name == "nt" else "*.so"
NATIVE_LIBRARY = "librime_jni.dll" if os.name == "nt" else "librime_jni.so"
# Windows runs natively (no WSL, user rule 2026-11-10); --backend wsl remains only for one-off cross-checks.
DEFAULT_WINDOWS_CACHE = Path(r"D:\CyIME-Data\cache\cyime-t9-host-win")


def reuse_engine(source, output, cache):
    """Use the engine and resources of an earlier complete host run, so that baseline and candidate
    runs can alternate in one timing session after the source has changed. Nothing is built."""
    source = Path(source).resolve()
    manifest = json.loads((source / "run.json").read_text(encoding="utf-8"))
    engine = manifest.get("engine") if isinstance(manifest, dict) else None
    if (not isinstance(engine, dict) or manifest.get("status") != "complete" or
            engine.get("source") != "host-source-build"):
        raise ValueError("--engine-from must be a complete host replay run: " + str(source))
    for name, expected in (("engine-build.json", engine.get("build_manifest_sha256")),
                           ("resources.zip", engine.get("resources_sha256"))):
        if not (source / name).is_file() or file_digest(source / name) != expected:
            raise ValueError(f"{source / name} differs from that run's run.json")
    build_manifest = json.loads((source / "engine-build.json").read_text(encoding="utf-8"))
    if file_digest(source / "source-files.json") != build_manifest.get("source_files_sha256"):
        raise ValueError(f"{source / 'source-files.json'} differs from its build manifest")
    library_dir = Path(build_manifest["library"]).parent
    libraries = {p.name: file_digest(p) for p in sorted(library_dir.glob(NATIVE_GLOB))}
    if libraries != engine.get("libraries"):
        raise ValueError("The cached engine of --engine-from no longer matches its recorded libraries: " + str(library_dir))
    # The earlier run's build description names its Java tools; build_host.py keeps the javac launcher
    # under <cache>/dependencies.
    described = {}
    if (source / "build-result.txt").is_file():
        lines = (source / "build-result.txt").read_text(encoding="utf-8").strip().splitlines()
        described = json.loads(lines[-1]) if lines else {}
    java = described.get("java") or shutil.which("java")
    javac = next((Path(path) for path in (described.get("javac"), cache / "dependencies" / "javac")
                  if path and Path(path).is_file()), None)
    if not java or javac is None:
        raise ValueError("Java or the cached javac launcher is missing; run once without --engine-from")
    for name in ("engine-build.json", "source-files.json", "resources.zip"):
        # Timing/seeded repeats read the verified archive without changing it.
        # A full copy per repeat would exhaust the replay disk budget.
        if name == "resources.zip":
            try:
                os.link(source / name, output / name)
                continue
            except OSError:
                pass  # Cross-filesystem outputs still need a private copy.
        shutil.copyfile(source / name, output / name)
    build = {"library_dir": str(library_dir), "java": java, "javac": str(javac)}
    (output / "build-result.txt").write_text(json.dumps(build, sort_keys=True) + "\n", encoding="utf-8")
    return build, library_dir, libraries, {"sha256": engine["resources_sha256"]}


def run_host(args):
    # Check both Windows backing disks and the Linux cache before allocating outputs.
    import importlib.util
    spec = importlib.util.spec_from_file_location("cyime_storage_guard", REPO / "scripts/storage_guard.py")
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    guard.check_storage(REPO)
    output = (args.output or REPO / ".codex-artifacts" /
              ("t9-host-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f"))).resolve()
    if output.exists():
        raise ValueError("Output already exists; choose a new directory: " + str(output))
    fixture_bytes = args.fixture.read_bytes()
    original = json.loads(fixture_bytes.decode("utf-8-sig"))
    fixture = validate_fixture(original)
    schema_patch_file = getattr(args, "schema_patch_file", None)
    if schema_patch_file is not None and not schema_patch_file.is_file():
        raise ValueError("Schema patch does not exist: " + str(schema_patch_file))
    user_dict = getattr(args, "user_dict", None)
    if user_dict is not None:
        user_dict, user_dict_info = inspect_user_dict(user_dict)
    jar = find_json_jar(args.json_jar)
    if os.name == "nt" and getattr(args, "backend", "native") == "wsl":
        command = ["wsl.exe", "-d", args.wsl_distro, "--", "python3", linux_path(HERE / "t9_host.py"),
                   "--fixture", linux_path(args.fixture), "--output", linux_path(output),
                   "--json-jar", linux_path(jar), "--key-delay-ms", str(args.key_delay_ms)]
        for key in ("baseline", "assertions", "user_dict", "engine_from", "schema_patch_file"):
            if getattr(args, key, None):
                command.extend(["--" + key.replace("_", "-"), linux_path(getattr(args, key))])
        if args.host_cache:
            command.extend(["--host-cache", args.host_cache])
        return subprocess.run(command).returncode
    if platform.system() not in ("Linux", "Windows"):
        raise ValueError("Host replay supports Linux and Windows (native); no device fallback is allowed.")
    output.mkdir(parents=True)
    if fixture == original:
        (output / "fixture.json").write_bytes(fixture_bytes)
    else:
        (output / "input-fixture.json").write_bytes(fixture_bytes)
        save(output / "fixture.json", fixture)
    protocol = dict(key_delay_ms=args.key_delay_ms, row_limit=20, boundary_limit=100,
                    settle_timeout_ms=5000, modes=["continuous", "paused"], variant="digits", runtime="host")
    manifest = dict(format_version=1, status="running", fixture_sha256=file_digest(output / "fixture.json"),
                    input_fixture_sha256=hashlib.sha256(fixture_bytes).hexdigest(), passes=PASSES,
                    protocol_settings=protocol, user_state="fresh_empty", personalization=False,
                    started_utc=datetime.now(timezone.utc).isoformat(),
                    host=dict(system=platform.system(), architecture=platform.machine(), release=platform.release()),
                    reading_provenance=fixture.get("pronunciation_review", fixture.get("reading_provenance", {})))
    if user_dict is not None:
        # Never mixed with the empty-user protocol: comparisons require equal user_state and snapshot.
        shutil.copyfile(user_dict, output / "user-dict.userdb.txt")
        manifest.update(user_state="preset_user_dict", user_dict_snapshot=dict(
            user_dict_info, source=str(user_dict), file="user-dict.userdb.txt",
            sha256=file_digest(output / "user-dict.userdb.txt")))
    if schema_patch_file is not None:
        patch = output / "schema.custom.yaml"
        shutil.copyfile(schema_patch_file, patch)
        manifest["schema_patch_sha256"] = file_digest(patch)
    save(output / "run.json", manifest)
    commands = HostCommands(output / "commands.log")
    try:
        cache = (Path(args.host_cache).expanduser() if args.host_cache else
                 DEFAULT_WINDOWS_CACHE if os.name == "nt" else Path.home() / ".cache/cyime-t9-host")
        resources = output / "resources.zip"
        engine_from = getattr(args, "engine_from", None)
        if engine_from is not None:
            print("Reusing the engine and resources of an earlier run; nothing is built.", flush=True)
            build, library_dir, libraries, resource_info = reuse_engine(engine_from, output, cache)
        else:
            print("Building/reusing the project T9 engine on this computer; no Android device is used.", flush=True)
            builder = "host_replay/build_host_windows.py" if os.name == "nt" else "host_replay/build_host.py"
            build_command = [sys.executable, str(HERE / builder), "--repo", str(REPO), "--cache", str(cache)]
            with (output / "build.log").open("w", encoding="utf-8") as log:
                built = subprocess.run(build_command, stdout=subprocess.PIPE, stderr=log, timeout=3600)
            (output / "build-result.txt").write_bytes(built.stdout)
            if built.returncode:
                raise RuntimeError("Host engine build failed; see build.log and build-result.txt")
            build_lines = built.stdout.decode("utf-8").strip().splitlines()
            if not build_lines:
                raise ValueError("Host engine build returned no artifact description")
            build = json.loads(build_lines[-1])
            if not isinstance(build, dict) or any(not isinstance(build.get(key), str) for key in
                                                  ("library_dir", "manifest", "java", "javac")):
                raise ValueError("Host engine build returned an invalid artifact description")
            library_dir = Path(build["library_dir"])
            libraries = {p.name: file_digest(p) for p in sorted(library_dir.glob(NATIVE_GLOB))}
            if NATIVE_LIBRARY not in libraries:
                raise ValueError("Host build returned no " + NATIVE_LIBRARY)
            shutil.copyfile(build["manifest"], output / "engine-build.json")
            build_manifest = json.loads((output / "engine-build.json").read_text(encoding="utf-8"))
            if (not isinstance(build_manifest, dict) or build_manifest.get("status") != "complete" or
                    build_manifest.get("library_sha256") != libraries[NATIVE_LIBRARY]):
                raise ValueError("Host library differs from its completed build manifest")
            source_files = Path(build["manifest"]).parent / "source-files.json"
            if file_digest(source_files) != build_manifest.get("source_files_sha256"):
                raise ValueError("Host build source inventory differs from its fingerprint")
            shutil.copyfile(source_files, output / "source-files.json")
            print("Snapshotting the existing dictionaries, configuration and scoring model.", flush=True)
            resource_info = snapshot_resources(REPO, resources)
        manifest["engine"] = dict(source="host-source-build", resource_type="resource_zip",
                                  resources_sha256=resource_info["sha256"], apk_sha256=resource_info["sha256"],
                                  libraries=libraries, build_manifest_sha256=file_digest(output / "engine-build.json"))
        if engine_from is not None:
            manifest["engine"]["reused_from"] = str(Path(engine_from).resolve())
        save(output / "run.json", manifest)
        source_dir, classes = output / "runner/src", output / "runner/classes"
        source_dir.mkdir(parents=True)
        classes.mkdir()
        sources = []
        for source in (HERE / "android_replay/ChatReplay.java", HERE / "android_replay/RimeEngine.java"):
            target = source_dir / source.name
            target.write_bytes(source.read_bytes())
            sources.append(target)
        manifest["compiled_runner_sources"] = {p.name: file_digest(p) for p in sources}
        manifest["json_jar_sha256"] = file_digest(jar)
        commands.run([build.get("javac", "javac"), "-encoding", "UTF-8", "--release", "8", "-cp", jar,
                      "-d", classes, *sources])
        # The engine reads its deployed dictionaries from the replay root. A mounted Windows drive (9p/drvfs)
        # pages them in slowly and unevenly, so the root lives on the Linux filesystem during the replay
        # and is moved into the result afterwards.
        live_root = cache / "replay-roots" / (output.name + "-" + uuid.uuid4().hex[:8])
        live_root.parent.mkdir(parents=True, exist_ok=True)
        root = output / "native-run"
        manifest["replay_root"] = dict(path=str(live_root), filesystem=filesystem_type(live_root.parent),
                                       kept_as="native-run")
        save(output / "run.json", manifest)
        print(f"Replaying {len(fixture['cases'])} cases locally; immediate and settled candidates.", flush=True)
        try:
            commands.run([build.get("java", "java"), "-cp", str(classes) + os.pathsep + str(jar),
                          "com.cyime.audit.ChatReplay", "--host", "--resources", resources,
                          "--lib-dir", library_dir, "--root", live_root, "--corpus", output / "fixture.json",
                          "--output", output / "replay.jsonl", "--modes", "continuous,paused",
                          "--key-delay-ms", args.key_delay_ms,
                          *(["--schema-patch-file", output / "schema.custom.yaml"] if schema_patch_file else []),
                          *(["--user-dict", output / "user-dict.userdb.txt"] if user_dict is not None else [])])
        finally:
            if live_root.exists():
                shutil.move(str(live_root), str(root))
        if {p.name: file_digest(p) for p in sorted(library_dir.glob(NATIVE_GLOB))} != libraries:
            raise ValueError("Host engine libraries changed during replay")
        shutil.copyfile(root / "user/build/t9_pinyin.schema.yaml", output / "deployed-schema.yaml")
        records, sources = summary.read_evidence([output / "replay.jsonl"])
        report, snapshots = summary.summarize(fixture, records, PASSES)
        report.update(fixture_sha256=manifest["fixture_sha256"], sources=sources)
        summary.export_report(output / "report", report, snapshots)
        issues = sum(len(p["execution_issues"]) for p in report["passes"].values())
        manifest.update(status="complete", replay_sha256=file_digest(output / "replay.jsonl"),
                        deployed_schema_sha256=file_digest(output / "deployed-schema.yaml"), execution_issues=issues,
                        finished_utc=datetime.now(timezone.utc).isoformat())
        save(output / "run.json", manifest)
        load_run(output)  # Cross-check executed engine/resource/corpus hashes before reporting success.
        quality = evaluate_known_bad(output, args.assertions)
        if args.baseline:
            try:
                comparison = compare_runs(args.baseline.resolve(), output, args.assertions)
            except (ValueError, OSError, KeyError, TypeError) as error:
                comparison = dict(status="invalid", exit_code=2, error=str(error))
            write_comparison(output / "comparison", comparison)
            quality["comparison"] = dict(path="comparison/comparison.json", exit_code=comparison["exit_code"])
            quality["exit_code"] = combine_exit_codes(quality["exit_code"], comparison["exit_code"])
        quality["exit_code"] = combine_exit_codes(quality["exit_code"], 1 if issues else 0)
        save(output / "quality.json", quality)
        compact_report(output, fixture, report, records, quality, manifest["user_state"])
        print(json.dumps(dict(output=str(output), execution="complete", quality_exit=quality["exit_code"],
                              known_bad=len(quality.get("violations", []))), ensure_ascii=False), flush=True)
        return quality["exit_code"]
    except Exception as error:
        manifest.update(status="failed", error=str(error), finished_utc=datetime.now(timezone.utc).isoformat())
        save(output / "run.json", manifest)
        raise


def add_arguments(parser):
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--schema-patch-file", type=Path,
                        help="Deploy this settings-generated t9_pinyin.custom.yaml in the isolated user directory")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--assertions", type=Path)
    parser.add_argument("--key-delay-ms", type=int, choices=range(0, 1001), default=35, metavar="0..1000")
    parser.add_argument("--wsl-distro", default="Ubuntu-26")
    parser.add_argument("--backend", choices=("native", "wsl"), default="native",
                        help="Windows: build and replay natively (default) or, for one-off cross-checks only, in WSL")
    parser.add_argument("--host-cache", help="Linux cache path for host source snapshots, dependencies and engine builds")
    parser.add_argument("--json-jar", type=Path, help="Existing org.json JAR (normally found in Gradle cache)")
    parser.add_argument("--user-dict", type=Path,
                        help="Rime userdb snapshot (*.userdb.txt) merged into the fresh user directory before "
                             "replay; recorded as user_state=preset_user_dict and never compared with empty-user runs")
    parser.add_argument("--engine-from", type=Path,
                        help="Replay the engine and resources of an earlier complete host run instead of building "
                             "the current source; used to interleave baseline and candidate runs in one timing session")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    add_arguments(parser)
    args = parser.parse_args(argv)
    try:
        return run_host(args)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        print("T9 host replay failed: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
