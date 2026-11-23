"""Repeatable T9 algorithm replay on the computer; never builds or installs an application.

Run `python tools/ime_lab/t9_replay.py run --help` for engine/fixture inputs.
The default run uses the project's native engine on Linux/WSL; Android replay is explicit.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import csv
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import uuid
import zipfile

import summarize_daily_chat as summary

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
DEFAULT_FIXTURE = HERE / "fixtures/daily_chat_20261003.json"
PASSES = ["digits:continuous", "digits:paused"]
REMOTE_PATTERN = re.compile(r"/data/local/tmp/cyime-chat-audit-\d{8}T\d{6}-[0-9a-f]{8}\Z")
ABI_DIRECTORIES = {"arm64-v8a": "arm64", "armeabi-v7a": "arm", "x86_64": "x86_64", "x86": "x86"}


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def shell_command(arguments):
    """ADB's remote shell still needs quoting even with a local argument vector."""
    return shlex.join([str(item) for item in arguments])


def choose_device(output, requested=None):
    devices = [line.split()[:2] for line in output.splitlines()[1:] if line.strip()]
    available = [serial for serial, state in devices if state == "device"]
    if requested:
        if requested not in available:
            raise ValueError(f"Android device is not ready: {requested}")
        return requested
    if len(available) != 1:
        raise ValueError(f"Expected one ready Android device; found {len(available)}. Use --serial.")
    return available[0]


def pin_cpus(command, choice):
    """(what was pinned, taskset prefix) for android-run --cpus: little / big (the slowest / fastest cpufreq policy
    of the device) or a hex mask given as is.

    A budget phone runs on cores of the little class, so a replay pinned to the little cluster of a flagship shows
    what the engine costs there. The replay is one thread, so its per-key time is then CPU time on those cores.
    """
    if not choice:
        return None, []
    if choice in ("little", "big"):
        found = []
        for policy in command.shell("ls", "/sys/devices/system/cpu/cpufreq").split():
            if not re.fullmatch(r"policy\d+", policy):
                continue
            base = "/sys/devices/system/cpu/cpufreq/" + policy
            cpus = []
            for part in command.shell("cat", base + "/related_cpus").replace(",", " ").split():
                first, _, last = part.partition("-")
                cpus.extend(range(int(first), int(last or first) + 1))
            found.append((int(command.shell("cat", base + "/cpuinfo_max_freq").strip() or 0), cpus))
        if not found:
            raise ValueError("--cpus: no cpufreq policy on this device; give a hex mask")
        found.sort()
        khz, cpus = found[0] if choice == "little" else found[-1]
        mask = format(sum(1 << cpu for cpu in cpus), "x")
    else:
        mask = format(int(choice, 16), "x")
        khz = None
    # What the shell may really use of that mask (a device can list cores its shell cannot run on).
    match = re.search(r"^Cpus_allowed_list:\s*(\S+)", command.shell("taskset", mask, "cat", "/proc/self/status"), re.M)
    if not match:
        raise ValueError(f"--cpus: the shell cannot run on mask {mask}")
    return {"choice": choice, "mask": mask, "max_khz": khz, "cpus_allowed": match.group(1)}, ["taskset", mask]


def installed_apk(output):
    paths = [line.removeprefix("package:").strip() for line in output.splitlines() if line.startswith("package:")]
    if len(paths) != 1 or not paths[0].startswith("/data/app/") or not paths[0].endswith("/base.apk"):
        raise ValueError("This runner requires a monolithic installed APK; use --apk for a supplied monolithic artifact.")
    if any(part in (".", "..", "") for part in paths[0].split("/")[1:]):
        raise ValueError("Installed package returned a noncanonical APK path.")
    return paths[0]


def sdk_path(explicit=None):
    candidates = [explicit, os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    local = REPO / "local.properties"
    if local.exists():
        match = re.search(r"^sdk\.dir=(.*)$", local.read_text(encoding="utf-8"), re.M)
        if match:
            candidates.append(match[1].strip().replace("\\:", ":").replace("\\\\", "\\"))
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
    for value in candidates:
        if value and (Path(value) / "platform-tools").is_dir():
            return Path(value).resolve()
    raise ValueError("Android SDK not found; set --sdk or ANDROID_SDK_ROOT.")


def version_key(path):
    return tuple(int(part) for part in re.findall(r"\d+", path.name))


def sdk_tools(sdk, java_home=None):
    suffix = ".exe" if os.name == "nt" else ""
    jdk = Path(java_home or os.environ.get("JAVA_HOME", ""))
    javac = jdk / "bin" / ("javac" + suffix)
    if not javac.is_file():
        found = shutil.which("javac")
        if not found:
            raise ValueError("JDK javac not found; set --java-home or JAVA_HOME.")
        jdk = Path(found).resolve().parent.parent
    paths = {name: jdk / "bin" / (name + suffix) for name in ("java", "javac", "jar")}
    jars = sorted(sdk.glob("platforms/android-*/android.jar"), key=lambda p: version_key(p.parent))
    d8 = sorted(sdk.glob("build-tools/*/lib/d8.jar"), key=lambda p: version_key(p.parent.parent))
    if not jars or not d8 or any(not path.is_file() for path in paths.values()):
        raise ValueError("JDK and Android SDK platform/build-tools are incomplete.")
    return {**paths, "android": jars[-1], "d8": d8[-1]}


class Commands:
    def __init__(self, adb, log, serial=None):
        self.adb = str(adb)
        self.log = Path(log)
        self.serial = serial

    def run(self, args, timeout=1200):
        args = [str(arg) for arg in args]
        with self.log.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(args, ensure_ascii=False) + "\n")
        try:
            process = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            captured = error.stdout or b""
            if isinstance(captured, bytes):
                captured = captured.decode("utf-8", errors="replace")
            with self.log.open("a", encoding="utf-8") as stream:
                stream.write(captured + "\nCOMMAND TIMED OUT\n")
            raise
        output = process.stdout.decode("utf-8", errors="replace")
        with self.log.open("a", encoding="utf-8") as stream:
            stream.write(output + "\n")
        if process.returncode:
            raise RuntimeError(f"Command failed ({process.returncode}): {args[0]}\n{output[-3000:]}")
        return output

    def adb_run(self, *args):
        return self.run([self.adb, *(["-s", self.serial] if self.serial else []), *args])

    def shell(self, *args):
        return self.adb_run("shell", shell_command(args))


def compile_runner(command, tools, output):
    build = output / "runner"
    classes, dex = build / "classes", build / "dex"
    classes.mkdir(parents=True)
    dex.mkdir()
    source_dir = build / "src"
    source_dir.mkdir()
    sources = []
    for source in (HERE / "android_replay/ChatReplay.java", HERE / "android_replay/RimeEngine.java"):
        target = source_dir / source.name
        target.write_bytes(source.read_bytes())
        sources.append(target)
    command.run([tools["javac"], "-encoding", "UTF-8", "-source", "8", "-target", "8", "-cp", tools["android"],
                 "-d", classes, *sources])
    jar = build / "classes.jar"
    command.run([tools["jar"], "cf", jar, "-C", classes, "."])
    command.run([tools["java"], "-cp", tools["d8"], "com.android.tools.r8.D8", "--min-api", "28",
                 "--lib", tools["android"], "--output", dex, jar])
    return dex / "classes.dex"


def extract_libraries(apk, abi, directory):
    directory.mkdir()
    with zipfile.ZipFile(apk) as archive:
        if "assets/rime-bundled-manifest.tsv" not in archive.namelist():
            raise ValueError("APK has no bundled Rime manifest.")
        prefix = f"lib/{abi}/"
        for name in archive.namelist():
            if name.startswith(prefix) and name.endswith(".so"):
                leaf = name[len(prefix):]
                if not re.fullmatch(r"lib[A-Za-z0-9_.+-]+\.so", leaf):
                    raise ValueError(f"Invalid native-library ZIP entry: {name}")
                (directory / leaf).write_bytes(archive.read(name))
    if not (directory / "librime_jni.so").is_file():
        raise ValueError(f"APK has no T9 engine for device ABI {abi}.")


def remote_hash(command, path):
    value = command.shell("sha256sum", path).split()[0]
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Device returned an invalid SHA256.")
    return value


def snapshot_engine(command, args, remote, output, abi):
    if abi not in ABI_DIRECTORIES:
        raise ValueError(f"Unsupported Android ABI: {abi}")
    command.shell("mkdir", "-p", remote + "/lib")
    engine = {"source": "apk" if args.apk else "installed", "abi": abi}
    if args.apk:
        apk = args.apk.resolve()
        if not apk.is_file():
            raise ValueError(f"APK not found: {apk}")
        before = digest(apk)
        libraries = output / "engine-libraries"
        extract_libraries(apk, abi, libraries)
        if digest(apk) != before:
            raise ValueError("Supplied APK changed while extracting native libraries.")
        command.adb_run("push", str(apk), remote + "/base.apk")
        for path in sorted(libraries.glob("*.so")):
            library_hash = digest(path)
            command.adb_run("push", str(path), remote + "/lib/" + path.name)
            if digest(path) != library_hash or remote_hash(command, remote + "/lib/" + path.name) != library_hash:
                raise ValueError("Supplied native library changed during transfer.")
        if digest(apk) != before or remote_hash(command, remote + "/base.apk") != before:
            raise ValueError("Supplied APK changed during transfer.")
        engine.update(apk_path=str(apk), apk_sha256=before)
    else:
        source = installed_apk(command.shell("pm", "path", args.package))
        before = remote_hash(command, source)
        package_info = command.shell("dumpsys", "package", args.package)
        engine["package_info"] = [line.strip() for line in package_info.splitlines()
                                  if any(key in line for key in ("versionCode=", "versionName=", "lastUpdateTime="))]
        command.shell("cp", source, remote + "/base.apk")
        lib_dir = source.rsplit("/", 1)[0] + "/lib/" + ABI_DIRECTORIES[abi]
        names = command.shell("ls", lib_dir).splitlines()
        if "librime_jni.so" not in names:
            raise ValueError("Installed native libraries are unavailable; provide --apk instead.")
        for name in names:
            if not re.fullmatch(r"lib[A-Za-z0-9_.+-]+\.so", name):
                raise ValueError(f"Unexpected installed library filename: {name}")
            library_source = lib_dir + "/" + name
            library_hash = remote_hash(command, library_source)
            command.shell("cp", library_source, remote + "/lib/" + name)
            if remote_hash(command, library_source) != library_hash or remote_hash(command, remote + "/lib/" + name) != library_hash:
                raise ValueError("Installed native libraries changed during snapshot.")
        if (installed_apk(command.shell("pm", "path", args.package)) != source or
                remote_hash(command, source) != before or remote_hash(command, remote + "/base.apk") != before):
            raise ValueError("Installed APK changed during snapshot; rerun after the other installation completes.")
        engine.update(package=args.package, apk_path=source, apk_sha256=before)
    if args.library_dir:
        override = args.library_dir.resolve()
        if not (override / "librime_jni.so").is_file():
            raise ValueError("--library-dir must contain librime_jni.so compiled for the device ABI.")
        for path in sorted(override.glob("*.so")):
            if not re.fullmatch(r"lib[A-Za-z0-9_.+-]+\.so", path.name):
                raise ValueError("Invalid override library filename.")
            command.adb_run("push", str(path), remote + "/lib/" + path.name)
            if remote_hash(command, remote + "/lib/" + path.name) != digest(path):
                raise ValueError("Override library changed during transfer.")
        engine["library_override"] = str(override)
    engine["libraries"] = {name: remote_hash(command, remote + "/lib/" + name)
                            for name in command.shell("ls", remote + "/lib").splitlines()}
    engine.update(resources_sha256=engine["apk_sha256"], resource_type="apk")
    return engine


def compact_report(output, fixture, report, records, quality, user_state="fresh_empty"):
    chars = [r for r in records if r.get("mode") == "paused" and r.get("stage") in ("syllable_settled", "clause_settled")]
    immediate = {(r["id"], r["keys"]): r for r in records if r.get("mode") == "continuous" and r.get("stage") == "key_immediate"}
    cases = {c["id"]: c for c in fixture["cases"]}
    rows = []
    for item in chars:
        first = immediate[item["id"], item["keys"]]
        rows.append(dict(id=item["id"], role=cases[item["id"]].get("role", "body"),
                         text=item["target"], character_index=item["char_count"], expected=item["expected_prefix"],
                         keys=item["keys"], immediate=first["rows"][0]["text"] if first["rows"] else "",
                         immediate_rank=first["target_prefix_rank"],
                         settled=item["rows"][0]["text"] if item["rows"] else "", settled_rank=item["target_prefix_rank"]))
    with (output / "characters.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    metrics = report["passes"]["digits:paused"]["metrics"]
    message = "已知异常或回归约束失败" if quality["exit_code"] else "采样与已声明约束完成；不代表所有原文首选正确"
    details = json.dumps({"metrics": metrics, "quality": quality}, ensure_ascii=False, indent=2)
    table = "".join("<tr>" + "".join("<td>" + html.escape(str(r[k])) + "</td>" for k in
                                    ("id", "character_index", "expected", "immediate", "immediate_rank", "settled", "settled_rank")) + "</tr>" for r in rows)
    (output / "index.html").write_text("<!doctype html><html lang='zh-CN'><meta charset='utf-8'>"
        "<title>九键算法回放</title><style>body{font:15px/1.6 system-ui;margin:30px}table{border-collapse:collapse}"
        "td,th{border-bottom:1px solid #ddd;padding:7px;text-align:left}pre{white-space:pre-wrap}</style>"
        "<h1>九键算法回放</h1><p>" + message + "</p><p>语言自然度只检查已审阅确认的具体反例；未知首选仍待审阅。"
        + ("初始个人学习词库为空" if user_state == "fresh_empty" else "预置个人词库快照（" + html.escape(user_state) + "）")
        + "，不提交或学习。输入法界面与实际上屏未验收。</p>"
        "<p><a href='characters.csv'>逐字 CSV</a> · <a href='report/index.html'>完整逐键候选</a> · "
        "<a href='quality.json'>约束结果</a> · <a href='run.json'>引擎与输入身份</a></p><details><summary>统计与约束</summary><pre>"
        + html.escape(details) + "</pre></details><table><tr><th>用例</th><th>字序</th><th>原文前缀</th>"
        "<th>即时首选</th><th>目标名次</th><th>停顿首选</th><th>目标名次</th></tr>" + table + "</table></html>", encoding="utf-8")


def letters_latency(path):
    """Per-key process time (key_immediate, after the first case) and final first choices of a 26-key run."""
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    ready = next((r for r in rows if r.get("stage") == "ready"), {})
    keys = [r for r in rows if r.get("stage") == "key_immediate"]
    first = keys[0]["id"] if keys else None
    times = sorted(int(r["process_us"]) for r in keys if r["id"] != first)
    last = {}
    for r in keys:
        last[r["id"]] = r
    top1 = sum(1 for r in last.values() if r["rows"] and r["rows"][0]["text"] == r["target"])
    pick = lambda q: times[min(len(times) - 1, int(q * len(times)))] / 1000 if times else None
    return dict(settings=ready.get("settings", {}), keys=len(times), mean_ms=sum(times) / len(times) / 1000 if times else None,
                p50_ms=pick(0.5), p95_ms=pick(0.95), p99_ms=pick(0.99), cases=len(last), first_choice=top1)


def run_replay(args):
    from t9_fixture import validate_fixture
    from t9_replay_compare import evaluate_known_bad, compare_runs, write_comparison, combine_exit_codes
    fixture_path = args.fixture.resolve()
    fixture_bytes = fixture_path.read_bytes()
    original_fixture = json.loads(fixture_bytes.decode("utf-8-sig"))
    fixture = validate_fixture(original_fixture)
    output = (args.output or REPO / ".codex-artifacts/t9-replay" / datetime.now().strftime("%Y%m%d-%H%M%S")).resolve()
    if output.exists():
        raise ValueError(f"Output already exists; choose a new directory: {output}")
    sdk = sdk_path(args.sdk)
    toolchain = sdk_tools(sdk, args.java_home)
    adb = args.adb or sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    output.mkdir(parents=True)
    if fixture == original_fixture:
        (output / "fixture.json").write_bytes(fixture_bytes)
    else:
        (output / "input-fixture.json").write_bytes(fixture_bytes)
        save(output / "fixture.json", fixture)
    command = Commands(adb, output / "commands.log")
    remote = "/data/local/tmp/cyime-chat-audit-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8]
    for name, default in (("variant", "digits"), ("schema", "t9_pinyin"), ("schema_patch", ""), ("cpus", "")):
        if not hasattr(args, name):
            setattr(args, name, default)
    letters = args.variant.startswith("letters")
    if letters != (args.schema != "t9_pinyin"):
        raise ValueError("--variant letters goes with a 26-key --schema (rime_ice); digits with t9_pinyin")
    protocol = dict(key_delay_ms=args.key_delay_ms, row_limit=20, boundary_limit=100,
                    settle_timeout_ms=5000, modes=["continuous"] if letters else ["continuous", "paused"],
                    variant=args.variant, schema=args.schema, schema_patch=args.schema_patch, runtime="android",
                    **({"cpus": args.cpus} if args.cpus else {}))
    manifest = dict(format_version=1, status="running", fixture_sha256=digest(output / "fixture.json"),
                    protocol_settings=protocol, passes=PASSES, user_state="fresh_empty", personalization=False,
                    remote_root=remote, started_utc=datetime.now(timezone.utc).isoformat(),
                    input_fixture_sha256=hashlib.sha256(fixture_bytes).hexdigest(),
                    reading_provenance=fixture.get("pronunciation_review", fixture.get("reading_provenance", {})),
                    source_hashes={str(p.relative_to(REPO)): digest(p) for p in
                                   [HERE / "t9_replay.py", *sorted((HERE / "android_replay").glob("*.java"))]})
    save(output / "run.json", manifest)
    remote_created = False
    try:
        command.serial = choose_device(command.adb_run("devices"), args.serial)
        manifest["device"] = {"serial": command.serial,
                              "model": command.shell("getprop", "ro.product.model").strip(),
                              "android": command.shell("getprop", "ro.build.version.release").strip()}
        abi = command.shell("getprop", "ro.product.cpu.abi").strip()
        pinned, taskset = pin_cpus(command, args.cpus)
        if pinned:
            manifest["device"]["pinned"] = pinned
            print(f"Pinned to CPUs {pinned['cpus_allowed']} (mask {pinned['mask']})", flush=True)
        print("Compiling standalone diagnostic runner (no APK build/install)", flush=True)
        dex = compile_runner(command, toolchain, output)
        manifest["compiled_runner_sources"] = {p.name: digest(p) for p in (output / "runner/src").glob("*.java")}
        manifest["runner_dex_sha256"] = digest(dex)
        command.shell("mkdir", remote)
        remote_created = True
        manifest["engine"] = snapshot_engine(command, args, remote, output, abi)
        command.adb_run("push", str(dex), remote + "/replay.dex")
        command.adb_run("push", str(output / "fixture.json"), remote + "/fixture.json")
        print(f"Replaying {len(fixture['cases'])} cases on {command.serial}; immediate + paused", flush=True)
        command.shell("env", "CLASSPATH=" + remote + "/replay.dex", "LD_LIBRARY_PATH=" + remote + "/lib", *taskset,
                      "app_process", "/system/bin", "com.cyime.audit.ChatReplay", "--apk", remote + "/base.apk",
                      "--lib-dir", remote + "/lib", "--root", remote + "/run", "--corpus", remote + "/fixture.json",
                      "--output", remote + "/replay.jsonl", "--modes", ",".join(protocol["modes"]),
                      "--key-delay-ms", args.key_delay_ms, "--variant", args.variant, "--schema", args.schema,
                      *(["--schema-patch", args.schema_patch] if args.schema_patch else []))
        command.adb_run("pull", remote + "/replay.jsonl", str(output / "replay.jsonl"))
        command.adb_run("pull", remote + "/run/bundled-resources.json", str(output / "bundled-resources.json"))
        command.adb_run("pull", remote + f"/run/user/build/{args.schema}.schema.yaml", str(output / "deployed-schema.yaml"))
        if letters:
            # 26-key latency run: the T9 report does not apply; keep per-key timings and first choices.
            latency = letters_latency(output / "replay.jsonl")
            save(output / "latency.json", latency)
            manifest.update(status="complete", replay_sha256=digest(output / "replay.jsonl"),
                            deployed_schema_sha256=digest(output / "deployed-schema.yaml"),
                            finished_utc=datetime.now(timezone.utc).isoformat())
            save(output / "run.json", manifest)
            print(json.dumps({"output": str(output), "execution": "complete", **latency}, ensure_ascii=False), flush=True)
            return 0
        records, sources = summary.read_evidence([output / "replay.jsonl"])
        report, snapshots = summary.summarize(fixture, records, PASSES)
        report.update(fixture_sha256=manifest["fixture_sha256"], sources=sources)
        summary.export_report(output / "report", report, snapshots)
        issues = sum(len(p["execution_issues"]) for p in report["passes"].values())
        manifest.update(status="complete", replay_sha256=digest(output / "replay.jsonl"),
                        deployed_schema_sha256=digest(output / "deployed-schema.yaml"), execution_issues=issues,
                        finished_utc=datetime.now(timezone.utc).isoformat())
        save(output / "run.json", manifest)
        assertions = args.assertions
        default_assertions = HERE / "fixtures/daily_chat_20261003.known-bad.json"
        if assertions is None and manifest["fixture_sha256"] == digest(DEFAULT_FIXTURE) and default_assertions.exists():
            assertions = default_assertions
        quality = evaluate_known_bad(output, assertions) if assertions else {
            "assertion_count": 0, "violations": [], "language_quality": "unreviewed", "exit_code": 0}
        if args.baseline:
            comparison = compare_runs(args.baseline.resolve(), output, assertions)
            write_comparison(output / "comparison", comparison)
            quality["comparison"] = {"path": "comparison/comparison.json", "exit_code": comparison["exit_code"]}
            quality["exit_code"] = combine_exit_codes(quality["exit_code"], comparison["exit_code"])
        quality["exit_code"] = combine_exit_codes(quality["exit_code"], 1 if issues else 0)
        save(output / "quality.json", quality)
        compact_report(output, fixture, report, records, quality)
        print(json.dumps({"output": str(output), "execution": "complete", "quality_exit": quality["exit_code"],
                          "known_bad": len(quality.get("violations", []))}, ensure_ascii=False), flush=True)
        return quality["exit_code"]
    except Exception as error:
        manifest.update(status="failed", error=str(error), finished_utc=datetime.now(timezone.utc).isoformat())
        save(output / "run.json", manifest)
        if remote_created:
            try:
                command.adb_run("pull", remote + "/replay.jsonl", str(output / "replay.partial.jsonl"))
            except Exception:
                pass
        raise
    finally:
        if remote_created and args.keep_device:
            manifest["device_temporary_files"] = "retained_by_request"
            save(output / "run.json", manifest)
        if remote_created and not args.keep_device:
            if not REMOTE_PATTERN.fullmatch(remote):
                raise ValueError("Refusing cleanup outside this run's dedicated device directory.")
            try:
                command.shell("rm", "-rf", "--", remote)
                manifest["device_temporary_files"] = "removed"
            except Exception as error:
                manifest["device_temporary_files"] = "retained: " + str(error)
            save(output / "run.json", manifest)


def main(argv=None):
    values = list(sys.argv[1:] if argv is None else argv)
    if values and values[0] == "prepare":
        from t9_fixture import main as prepare_main
        return prepare_main(values)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    from t9_host import add_arguments, run_host
    host = sub.add_parser("run", help="Replay the project's native engine on this computer (Linux/WSL), without any device")
    add_arguments(host)
    run = sub.add_parser("android-run", help="Explicit optional device replay; never used by the default run")
    run.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    run.add_argument("--output", type=Path)
    run.add_argument("--apk", type=Path, help="Existing monolithic APK; loaded without installing it")
    run.add_argument("--library-dir", type=Path, help="Optional existing native-library build for the device ABI; no compilation or APK install")
    run.add_argument("--package", default="com.cyletix.cyime")
    run.add_argument("--serial")
    run.add_argument("--sdk", type=Path)
    run.add_argument("--adb", type=Path)
    run.add_argument("--java-home", type=Path)
    run.add_argument("--key-delay-ms", type=int, choices=range(0, 1001), default=35, metavar="0..1000")
    run.add_argument("--baseline", type=Path)
    run.add_argument("--assertions", type=Path)
    run.add_argument("--keep-device", action="store_true", help="Retain this run's isolated device resources for diagnostics")
    run.add_argument("--variant", choices=["digits", "letters", "letters14"], default="digits",
                     help="letters: type each case's full pinyin into a 26-key --schema; letters14: the same on the 14-key keyboard (--schema pinyin_14jian); latency and first choices only")
    run.add_argument("--schema", default="t9_pinyin", help="Rime schema to deploy and replay (rime_ice with --variant letters)")
    run.add_argument("--schema-patch", default="",
                     help='"key=value;..." written as <schema>.custom.yaml before deployment, e.g. '
                          '"translator/max_sentences=1;t9/sentence_rescore=false" to measure a setting off')
    run.add_argument("--cpus", default="",
                     help="pin the replay with taskset: little or big (slowest / fastest core cluster of the device) or a "
                          "hex mask; little shows the per-key cost on budget-phone class cores")
    compare = sub.add_parser("compare", help="Compare two complete stored runs without a device")
    compare.add_argument("baseline", type=Path)
    compare.add_argument("candidate", type=Path)
    compare.add_argument("--assertions", type=Path)
    compare.add_argument("--output", type=Path, required=True)
    compare.add_argument("--noise-baseline", type=Path, action="append", default=[],
                         help="Repeated run of the baseline engine (at least two, interleaved with the candidate "
                              "runs in one session); their spread is the latency noise band")
    compare.add_argument("--candidate-repeat", type=Path, action="append", default=[],
                         help="Repeated run of the candidate engine in the same timing session")
    compare.add_argument("--work-tolerance-percent", type=float, default=0.0,
                         help="Allowed growth of cost work counters (default 0)")
    compare.add_argument("--model-swap", action="store_true",
                         help="Only the word model differs: beam_insertions may grow by up to 0.01%% in total "
                              "and by up to 2 per key; every other rule unchanged")
    prepare = sub.add_parser("prepare", help="Prepare a new text/pinyin fixture; see t9_fixture.py prepare --help")
    prepare.add_argument("arguments", nargs=argparse.REMAINDER)
    args, extra = parser.parse_known_args(argv)
    try:
        if args.command == "prepare":
            from t9_fixture import main as prepare_main
            # Preserve option order by forwarding original argv after the subcommand.
            values = list(sys.argv[1:] if argv is None else argv)
            return prepare_main(values[values.index("prepare"):])
        if extra:
            parser.error("unrecognized arguments: " + " ".join(extra))
        if args.command == "compare":
            from t9_replay_compare import main as compare_main
            forwarded = ["--baseline", str(args.baseline), "--candidate", str(args.candidate), "--output-dir", str(args.output)]
            if args.assertions:
                forwarded.extend(["--assertions", str(args.assertions)])
            for flag, paths in (("--noise-baseline", args.noise_baseline), ("--candidate-repeat", args.candidate_repeat)):
                for path in paths:
                    forwarded.extend([flag, str(path)])
            forwarded.extend(["--work-tolerance-percent", str(args.work_tolerance_percent)])
            if args.model_swap:
                forwarded.append("--model-swap")
            return compare_main(forwarded)
        if args.command == "run":
            return run_host(args)
        return run_replay(args)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, summary.EvidenceError) as error:
        print(f"T9 replay failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
