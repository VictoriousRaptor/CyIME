"""Build the production Rime/T9 JNI stack natively on Windows x64 (no WSL, no Android).

Same source snapshot and production CMake prefix as build_host.py; differences are only the toolchain:
the Android NDK's clang compiles for x86_64-pc-windows-msvc (it accepts the production GCC-style flags),
Visual Studio Build Tools provide the MSVC headers, libraries and linker, the JDK supplies jni.h, and the
ONNX Runtime DLL comes from the official PyPI wheel with an import library generated from its exports.
Build stdout ends with one JSON object; progress/logs use stderr.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_host as linux  # noqa: E402  (snapshot, sha, save, download, run)

ORT_VERSION = "1.28.0"
VCVARS = Path(r"C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat")
VS_CMAKE = Path(r"C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\Common7\IDE\CommonExtensions"
                r"\Microsoft\CMake")


def msvc_environment():
    """Environment after vcvars64.bat (INCLUDE, LIB, PATH with link.exe, lib.exe, dumpbin.exe)."""
    if not VCVARS.is_file():
        raise ValueError("Visual Studio 2019 Build Tools (vcvars64.bat) not found")
    output = subprocess.check_output(f'cmd /d /s /c ""{VCVARS}" >nul && set"', text=True, shell=False)
    env = {}
    for line in output.splitlines():
        key, sep, value = line.partition("=")
        if sep:
            env[key] = value
    if "INCLUDE" not in env or "LIB" not in env:
        raise ValueError("vcvars64.bat did not set INCLUDE/LIB")
    return env


def ndk_bin():
    sdk = Path(os.environ.get("ANDROID_HOME") or Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    versions = sorted((sdk / "ndk").glob("*/toolchains/llvm/prebuilt/windows-x86_64/bin"),
                      key=lambda p: [int(x) for x in p.parts[-6].split(".")])
    if not versions:
        raise ValueError("Android NDK (clang) not found under " + str(sdk / "ndk"))
    return versions[-1]


def vs_tool(name, env):
    found = shutil.which(name, path=env.get("PATH") or env.get("Path"))
    if not found:
        raise ValueError(name + " not found in the Visual Studio environment")
    return found


def onnxruntime(cache, env):
    meta = cache / f"onnxruntime-{ORT_VERSION}.pypi.json"
    linux.download(f"https://pypi.org/pypi/onnxruntime/{ORT_VERSION}/json", meta)
    entries = [e for e in json.loads(meta.read_text(encoding="utf-8"))["urls"]
               if e["filename"].endswith("win_amd64.whl")]
    if not entries:
        raise ValueError("No onnxruntime win_amd64 wheel for " + ORT_VERSION)
    entry = sorted(entries, key=lambda e: e["filename"])[-1]
    archive = cache / entry["filename"]
    linux.download(entry["url"], archive, entry["digests"]["sha256"])
    destination = cache / f"onnxruntime-{ORT_VERSION}-win"
    dll = destination / "onnxruntime.dll"
    implib = destination / "onnxruntime.lib"
    if not implib.is_file():
        destination.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as zipped:
            member = next(n for n in zipped.namelist() if n.endswith("/capi/onnxruntime.dll"))
            with zipped.open(member) as source, dll.open("wb") as output:
                shutil.copyfileobj(source, output)
        exports = subprocess.check_output([vs_tool("dumpbin", env), "/exports", str(dll)], text=True, env=env)
        names = re.findall(r"^\s+\d+\s+[0-9A-F]+\s+[0-9A-F]{8}\s+(\S+)", exports, re.M)
        if "OrtGetApiBase" not in names:
            raise ValueError("onnxruntime.dll does not export OrtGetApiBase")
        definition = destination / "onnxruntime.def"
        definition.write_text("LIBRARY onnxruntime\nEXPORTS\n" + "".join(f"  {n}\n" for n in names), encoding="ascii")
        subprocess.check_call([vs_tool("lib", env), "/nologo", "/machine:x64", f"/def:{definition}", f"/out:{implib}"], env=env)
    return dll, implib, {"version": ORT_VERSION, "source": entry["url"], "wheel_sha256": linux.sha(archive),
                         "dll_sha256": linux.sha(dll)}


def pinned_cmake(cache):
    """The pinned CMake of build_host.py (VERSIONS), from its official Windows wheel (VS2019 ships 3.20)."""
    version = linux.VERSIONS["cmake"]
    meta = cache / f"cmake-{version}.pypi.json"
    linux.download(f"https://pypi.org/pypi/cmake/{version}/json", meta)
    entries = [e for e in json.loads(meta.read_text(encoding="utf-8"))["urls"] if e["filename"].endswith("win_amd64.whl")]
    if not entries:
        raise ValueError("No cmake win_amd64 wheel for " + version)
    entry = sorted(entries, key=lambda e: e["filename"])[-1]
    archive = cache / entry["filename"]
    linux.download(entry["url"], archive, entry["digests"]["sha256"])
    destination = cache / f"cmake-{version}-win"
    marker = destination / ".complete"
    if not marker.exists():
        with zipfile.ZipFile(archive) as zipped:
            for info in zipped.infolist():
                target = (destination / info.filename).resolve()
                if not target.is_relative_to(destination.resolve()):
                    raise ValueError("Unsafe wheel member: " + info.filename)
                if info.is_dir():
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with zipped.open(info) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
        marker.write_text(entry["digests"]["sha256"], encoding="ascii")
    return next(destination.glob("cmake/data/bin/cmake.exe")), {"version": version, "wheel_sha256": linux.sha(archive)}


def build(args):
    if os.name != "nt":
        raise ValueError("This backend requires Windows x64")
    repo, cache = args.repo.resolve(), args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    env = msvc_environment()
    bin_dir = ndk_bin()
    cmake, cmake_identity = pinned_cmake(cache / "dependencies")
    ninja = VS_CMAKE / "Ninja/ninja.exe"
    jdk = Path(os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot")
    java, javac = jdk / "bin/java.exe", jdk / "bin/javac.exe"
    for tool in (cmake, ninja, java, javac, bin_dir / "clang.exe", bin_dir / "clang++.exe", bin_dir / "llvm-ar.exe"):
        if not tool.is_file():
            raise ValueError("Missing tool: " + str(tool))
    dll, implib, ort_identity = onnxruntime(cache / "dependencies", env)
    source, identity, files = linux.snapshot(repo, cache)
    production = (source / "CMakeLists.txt").read_text(encoding="utf-8")
    original = source / "CMakeLists.production.txt"
    if original.exists():
        production = original.read_text(encoding="utf-8")
    else:
        original.write_text(production, encoding="utf-8")
    anchor = "include(Rime)"
    if production.count(anchor) != 1:
        raise ValueError("Production CMake layout changed; review host adaptation")
    # The Linux host build silently found Boost headers under /usr/include; Windows has none. The vendored Boost
    # headers are merged into one directory (150+ separate include paths overflow Windows command lines).
    merged = source / "host-boost-include"
    if not (merged / ".complete").exists():
        for library in sorted((source / "boost/libs").iterdir()):
            headers = library / "include"
            if headers.is_dir():
                shutil.copytree(headers, merged, dirs_exist_ok=True)
        (merged / ".complete").write_text("merged from boost/libs/*/include\n", encoding="utf-8")
    boost_includes = 'include_directories(SYSTEM "${CMAKE_SOURCE_DIR}/host-boost-include")\n'
    # host-adapters/win holds Windows shims for POSIX-only headers the production plugins include (dlfcn.h).
    # The block goes right after project(): the production prefix already adds glog/leveldb before include(Rime).
    windows_block = (boost_includes +
                     'include_directories(SYSTEM "${HOST_ADAPTER_DIR}/win")\n' +
                     # The MSVC runtime marks POSIX names and fopen deprecated; the production code builds with -Werror.
                     'add_compile_definitions(_CRT_SECURE_NO_WARNINGS _CRT_NONSTDC_NO_DEPRECATE)\n')
    head = production[:production.index(anchor)]
    project = re.search(r"^project\([^)]*\)\s*\n", head, re.M)
    if not project:
        raise ValueError("Production CMake has no project() line; review host adaptation")
    prefix = (head[:project.end()] + windows_block + head[project.end():] +
              'list(PREPEND CMAKE_MODULE_PATH "${HOST_ADAPTER_DIR}")\n' + anchor)
    tail = (HERE / "host_tail_windows.cmake").read_text(encoding="utf-8")
    (source / "CMakeLists.txt").write_text(prefix + "\n" + tail, encoding="utf-8")
    # librime's WIN32 branch includes ${CMAKE_SOURCE_DIR}/cmake/AddRCInfo.cmake (DLL version resource); the snapshot
    # root is the jni tree, so give it a no-op: rime_jni.dll needs no version resource.
    (source / "cmake/AddRCInfo.cmake").write_text("# host replay: no Windows version resource" + chr(10), encoding="utf-8")
    adapter_names = ("build_host.py", "build_host_windows.py", "host_tail_windows.cmake", "android/log.h", "win/dlfcn.h", "win/posix_compat.h", "win/unistd.h", "win/sys/mman.h", "win/unwind_stub.c", "win/host_exports.cc",
                     "FindBoost.cmake", "host_replay_jni.cc")
    adapter_hashes = {name: linux.sha(HERE / name) for name in adapter_names}
    toolchain = {"cmake": cmake_identity, "clang": linux.sha(bin_dir / "clang++.exe"), "ndk": bin_dir.parts[-6], "msvc": env.get("VCToolsVersion", "")}
    build_id = "win-" + hashlib.sha256(json.dumps({"source": identity, "adapters": adapter_hashes, "toolchain": toolchain,
                                                   "ort": ort_identity}, sort_keys=True).encode()).hexdigest()[:16]
    directory = cache / "builds" / build_id
    directory.mkdir(parents=True, exist_ok=True)
    adapters = directory / "host-adapters"
    for name in adapter_names:
        target = adapters / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(HERE / name, target)
    linux.save(directory / "source-files.json", files)
    posix = lambda p: str(p).replace("\\", "/")
    configure = [cmake, "-S", source, "-B", directory, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
                 "-DCMAKE_MAKE_PROGRAM=" + posix(ninja),
                 "-DCMAKE_C_COMPILER=" + posix(bin_dir / "clang.exe"), "-DCMAKE_CXX_COMPILER=" + posix(bin_dir / "clang++.exe"),
                 "-DCMAKE_C_COMPILER_TARGET=x86_64-pc-windows-msvc", "-DCMAKE_CXX_COMPILER_TARGET=x86_64-pc-windows-msvc",
                 "-DCMAKE_AR=" + posix(bin_dir / "llvm-ar.exe"), "-DCMAKE_RC_COMPILER=" + posix(bin_dir / "llvm-rc.exe"),
                 "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded",
                 # NDK clang defaults to lld-link, which the NDK does not ship under that name: link with MSVC link.exe.
                 "-DCMAKE_LINKER_TYPE=MSVC", "-DCMAKE_NINJA_FORCE_RESPONSE_FILE=ON",  # 150+ Boost include paths overflow CreateProcess
                 "-DHOST_ADAPTER_DIR=" + posix(adapters), "-DHOST_JNI_INCLUDE=" + posix(jdk / "include"),
                 "-DHOST_ORT_DIR=" + posix(dll.parent), "-DHOST_ORT_LIBRARY=" + posix(implib),
                 "-DBUILD_TESTING=OFF", "-DBUILD_TEST=OFF", "-DCYIME_T9_WORK_COUNTERS=ON"]
    compile_args = [cmake, "--build", directory, "--target", "rime_jni", "--parallel", "2"]
    manifest = {"format_version": 1, "status": "building", "backend": "windows-x64-production-jni",
                "source_repo": str(repo), "source_root": str(source), "source_sha256": identity,
                "source_files": "source-files.json", "source_files_sha256": linux.sha(directory / "source-files.json"),
                "source_file_count": len(files), "adapter_hashes": adapter_hashes, "toolchain": toolchain,
                "dependencies": {"onnxruntime": ort_identity}, "configure": list(map(str, configure)),
                "compile": list(map(str, compile_args)), "started_utc": datetime.now(timezone.utc).isoformat()}
    manifest_path = directory / "build-manifest.json"
    linux.save(manifest_path, manifest)
    try:
        with (directory / "build.log").open("w", encoding="utf-8") as log:
            linux.run(configure, stdout=log, stderr=subprocess.STDOUT, env=env)
            linux.run(compile_args, stdout=log, stderr=subprocess.STDOUT, env=env)
        library = directory / "lib/librime_jni.dll"
        shutil.copyfile(dll, library.parent / dll.name)
        manifest.update(status="complete", library=str(library), library_sha256=linux.sha(library),
                        library_hashes={p.name: linux.sha(p) for p in sorted(library.parent.glob("*.dll"))},
                        finished_utc=datetime.now(timezone.utc).isoformat())
        linux.save(manifest_path, manifest)
        return {"library_dir": str(library.parent), "library": str(library), "manifest": str(manifest_path),
                "java": str(java), "javac": str(javac)}
    except Exception as error:
        manifest.update(status="failed", error=str(error), finished_utc=datetime.now(timezone.utc).isoformat())
        linux.save(manifest_path, manifest)
        print("Build failure log: " + str(directory / "build.log"), file=sys.stderr)
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(build(args), sort_keys=True), flush=True)
        return 0
    except Exception as error:
        print("HOST BUILD FAILED: " + str(error), file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
