"""Run fixtures/lexicon_regression.tsv against a Rime engine on this computer.

26-key (rime_ice) and 14-key (pinyin_14jian) rows use the Android data: the generated rime_ice directory of
the last build (app/build/generated/chinese-assets/rime_ice, so ext weights and schema patches are the
built ones) plus the 14-key schemas from assets. win26 rows use CyIME-Windows' data/rime with its pinyin
schema. Touch typing on the phone has neighbor correction, so 26/14-key rows run with a QWERTY neighbor
map. Nine-key rows need the joint decoder and word model, which only the WSL host replay
(tools/ime_lab/t9_replay.py) runs; they are listed as not run here.

The engine is any rime.dll built from this repository's librime (e.g. CyIME-Windows'
scripts/build-engine.ps1 output), or under Linux/WSL the host replay's librime_jni.so (tools/ime_lab/t9_replay.py
builds it from this checkout with every patch; give the .so file as --dll and put its directory on
LD_LIBRARY_PATH), which is reached through rime_get_api(). The data directory is a fresh copy under --work; no personal user dictionary
is read and nothing is committed, so nothing is learned.

Usage: python lexicon_regression.py --dll DIR --work DIR [--windows-data D:/GitHub/CyIME-Windows/data/rime]
                                    [--output result.json] [--baseline old-result.json] [--public-set DIR]
                                    [--set-label NAME] [--ab-replace "OLD=>NEW;..." --output-b result-b.json]
                                    [--learn learn.tsv ...]

--learn types each listed word's full pinyin and picks it once before the cases (as a user who wrote those
words before), so corrections are also checked against learned entries; without it the user dictionary is
empty, which hides the cases where a learned correction overtakes the typed reading.

A/B in one engine: --ab-replace deploys a second 26-key schema (rime_ice_b) with those text replacements
(e.g. a switch turned off) and types every 26-key case in both, alternating which goes first, so the two
reports share one process, one data build and the same machine load. Every key is timed (process the key
and read five candidates, as the candidate bar does); reports carry key_ms per case and the summary prints
the median and p95 per key.
"""
import argparse
import ctypes as C
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CASES = Path(__file__).resolve().parent / "fixtures" / "lexicon_regression.tsv"


def qwerty_neighbors():
    rows, offset = ["qwertyuiop", "asdfghjkl", "zxcvbnm"], [0, 0.5, 1.5]
    pos = {c: (r, i + offset[r]) for r, row in enumerate(rows) for i, c in enumerate(row)}
    return ";".join(c + ":" + "".join(d for d, (r2, x2) in pos.items()
                                      if d != c and abs(r2 - r) <= 1 and abs(x2 - x) <= 1.0)
                    for c, (r, x) in pos.items())


def load_cases():
    cases = []
    # CYIME_EXTRA_CASES: extra fixture files in the same format (e.g. a particle_phrases.py set), ';'-separated.
    extra = [Path(p) for p in os.environ.get("CYIME_EXTRA_CASES", "").split(";") if p]
    lines = [line for path in [CASES, *extra] for line in path.read_text(encoding="utf-8").splitlines()]
    for line in lines:
        if not line or line.startswith("#") or line.startswith("id\t"):
            continue
        f = line.split("\t")
        cases.append({"id": f[0], "keyboard": f[1], "input": f[2], "expect": f[3], "top_k": int(f[4] or 1),
                      "source": f[5], "date": f[6], "note": f[7] if len(f) > 7 else ""})
    # Shared public-corpus set (README in that directory); kept out of the repository. Same-message context
    # is fed to the engine only with CYIME_SEND_CONTEXT=1 (property cyime_left_context, as the app sets it).
    public = os.environ.get("CYIME_PUBLIC_SET")
    if public:
        for name in ("jianpin", "sentence"):
            for line in (Path(public) / f"{name}.jsonl").read_text(encoding="utf-8").splitlines():
                row = json.loads(line)
                # A sentence row may carry what is typed (a typo variant) in "keys"; otherwise its full pinyin.
                typed = row.get("keys") or "".join(row["pinyin"]).replace("ü", "v")
                label = os.environ.get("CYIME_SET_LABEL", "public")
                cases.append({"id": f"pub-{row['id']}", "keyboard": "26", "input": typed, "expect": row["text"],
                              "top_k": 1, "source": f"{label} {name}/{row['group']}", "date": "2026-10-18",
                              "note": "", "count": row.get("count", 1), "context": row.get("context")})
    return cases


def prepare_android(work):
    data = work / "android"
    if data.exists():
        shutil.rmtree(data)
    shutil.copytree(REPO / "app/build/generated/chinese-assets/rime_ice", data)
    for name in ("pinyin_14jian.schema.yaml", "cyime_k14_english.schema.yaml",
                 "cyime_particle_shortcuts.yaml", "cyime_punctuator.yaml"):
        if (REPO / "app/src/main/assets/rime_chinese" / name).exists():
            shutil.copy(REPO / "app/src/main/assets/rime_chinese" / name, data / name)
    if os.environ.get("CYIME_K14_RESCORE"):  # experiment: the 26-key sentence rescoring on the 14-key schema
        path = data / "pinyin_14jian.schema.yaml"
        text = path.read_text(encoding="utf-8").replace(
            chr(10) + "translator:" + chr(10),
            chr(10) + "translator:" + chr(10) + "  max_sentences: 4" + chr(10) + "  sentence_cutoff_threshold: 100" + chr(10), 1)
        path.write_text(text + chr(10) + "t9:" + chr(10) + "  sentence_rescore: true" + chr(10) + "  ngram_model: t9_ngram"
                        + chr(10) + "  ngram_sentence_start: false" + chr(10), encoding="utf-8")
    for item in filter(None, os.environ.get("CYIME_K14_REPLACE", "").split(";")):
        # experiments: "max_sentences: 4=>max_sentences: 8" replaces text in the copied 14-key schema
        old, new = item.split("=>", 1)
        path = data / "pinyin_14jian.schema.yaml"
        text = path.read_text(encoding="utf-8")
        if old not in text:
            raise ValueError(f"CYIME_K14_REPLACE: {old!r} not in the schema")
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
    k14_add = os.environ.get("CYIME_K14_TRANSLATOR_ADD")
    if k14_add:  # "enable_word_completion: true": extra lines at the top of the 14-key translator block
        path = data / "pinyin_14jian.schema.yaml"
        text = path.read_text(encoding="utf-8")
        path.write_text(text.replace(chr(10) + "translator:" + chr(10),
                                     chr(10) + "translator:" + chr(10) + "  " + k14_add + chr(10), 1), encoding="utf-8")
    for name in ("default.yaml", "symbols.yaml"):
        shutil.copy(REPO / "app/src/main/assets/rime" / name, data / name)
    text = (data / "default.yaml").read_text(encoding="utf-8")
    text = re.sub(r"schema_list:\n(  - schema: .*\n)+",
                  "schema_list:\n  - schema: rime_ice\n  - schema: pinyin_14jian\n", text)
    (data / "default.yaml").write_text(re.sub(r"page_size: \d+", "page_size: 10", text), encoding="utf-8")
    # Experiments: extra top-level YAML appended to the 26-key schema, and the word model it may name.
    extra = os.environ.get("CYIME_RIME_ICE_EXTRA")
    if extra:
        with open(data / "rime_ice.schema.yaml", "a", encoding="utf-8") as schema:
            schema.write(chr(10) + Path(extra).read_text(encoding="utf-8"))
    tencent_weights = os.environ.get("CYIME_TENCENT_WEIGHTS")
    if tencent_weights:  # experiments: replace tencent.dict.yaml weights from a text/weight gzip TSV
        import gzip
        with gzip.open(tencent_weights, "rt", encoding="utf-8") as rows:
            table = dict(row.rstrip(chr(10)).split(chr(9)) for row in rows)
        path = data / "cn_dicts" / "tencent.dict.yaml"
        lines = path.read_text(encoding="utf-8").split(chr(10))
        body = False
        for i, line in enumerate(lines):
            if line.strip() == "...":
                body = True
            fields = line.split(chr(9))
            if body and len(fields) == 2 and fields[0] in table:
                lines[i] = fields[0] + chr(9) + table[fields[0]]
        path.write_text(chr(10).join(lines), encoding="utf-8")
    base_weights = os.environ.get("CYIME_BASE_WEIGHTS")
    if base_weights:  # experiments: replace base.dict.yaml weights from a text/code/weight gzip TSV
        import gzip
        table = {}
        with gzip.open(base_weights, "rt", encoding="utf-8") as rows:
            for row in rows:
                text, code, weight = row.rstrip(chr(10)).split(chr(9))
                table[text + chr(9) + code] = weight
        path = data / "cn_dicts" / "base.dict.yaml"
        lines = path.read_text(encoding="utf-8").split(chr(10))
        body = False
        for i, line in enumerate(lines):
            if line.strip() == "...":
                body = True
            fields = line.split(chr(9))
            if body and len(fields) == 3 and fields[0] + chr(9) + fields[1] in table:
                lines[i] = fields[0] + chr(9) + fields[1] + chr(9) + table.pop(fields[0] + chr(9) + fields[1])
        if os.environ.get("CYIME_BASE_WEIGHTS_ADD"):  # rows the table has but base.dict.yaml lacks are appended
            lines += [key + chr(9) + weight for key, weight in table.items()]
        path.unlink()
        path.write_text(chr(10).join(lines), encoding="utf-8")
    char_weights = os.environ.get("CYIME_8105_WEIGHTS")
    if char_weights:  # experiments: replace 8105.dict.yaml (single character) weights, same format as base
        import gzip
        with gzip.open(char_weights, "rt", encoding="utf-8") as rows:
            table = {row.rsplit(chr(9), 1)[0]: row.rstrip(chr(10)).rsplit(chr(9), 1)[1] for row in rows}
        path = data / "cn_dicts" / "8105.dict.yaml"
        lines = path.read_text(encoding="utf-8").split(chr(10))
        body = False
        for i, line in enumerate(lines):
            if line.strip() == "...":
                body = True
            fields = line.split(chr(9))
            if body and len(fields) == 3 and fields[0] + chr(9) + fields[1] in table:
                lines[i] = fields[0] + chr(9) + fields[1] + chr(9) + table[fields[0] + chr(9) + fields[1]]
        path.unlink()
        path.write_text(chr(10).join(lines), encoding="utf-8")
    for item in filter(None, os.environ.get("CYIME_RIME_ICE_REPLACE", "").split(";")):
        # experiments: "max_sentences: 4=>max_sentences: 6" replaces text in the copied 26-key schema
        old, new = item.split("=>", 1)
        schema = data / "rime_ice.schema.yaml"
        text = schema.read_text(encoding="utf-8")
        if old not in text:
            raise ValueError(f"CYIME_RIME_ICE_REPLACE: {old!r} not in the schema")
        schema.write_text(text.replace(old, new, 1), encoding="utf-8")
    extra = os.environ.get("CYIME_TRANSLATOR_ADD")
    if extra:  # "sentence_position_initial_only: [把, 被]": extra lines at the top of the translator block
        schema = data / "rime_ice.schema.yaml"
        text = schema.read_text(encoding="utf-8")
        schema.write_text(text.replace(chr(10) + "translator:" + chr(10),
                                       chr(10) + "translator:" + chr(10) + "  " + extra + chr(10), 1), encoding="utf-8")
    overrides = os.environ.get("CYIME_T9_SET")
    if overrides:  # "joint_decoder=true,pool_budget=24": replace those keys in the schema's t9 block
        schema = data / "rime_ice.schema.yaml"
        text = schema.read_text(encoding="utf-8")
        head, block = text.split(chr(10) + "t9:" + chr(10), 1)
        for item in overrides.split(","):
            key, value = item.split("=", 1)
            block, count = re.subn(r"(?m)^  " + re.escape(key) + r": .*$", f"  {key}: {value}", block)
            if not count:
                block = f"  {key}: {value}" + chr(10) + block
        schema.write_text(head + chr(10) + "t9:" + chr(10) + block, encoding="utf-8")
    model = os.environ.get("CYIME_T9_NGRAM")
    if model:
        try:
            os.link(model, data / "t9_ngram.lm")
        except OSError:  # another file system (a WSL work directory, the model on D:)
            os.symlink(model, data / "t9_ngram.lm")
    for path in filter(None, os.environ.get("CYIME_EXTRA_FILES", "").split(";")):
        # experiments: extra schema/dictionary files copied into the data directory (e.g. a table another
        # translator reads); the 26-key schema names them through CYIME_RIME_ICE_REPLACE / _EXTRA
        shutil.copy(path, data / Path(path).name)
    ab = os.environ.get("CYIME_AB_REPLACE")
    if ab:  # second 26-key schema for an in-process A/B (the same replacement syntax as CYIME_RIME_ICE_REPLACE)
        text = (data / "rime_ice.schema.yaml").read_text(encoding="utf-8")
        for item in filter(None, ab.split(";")):
            old, new = item.split("=>", 1)
            if old not in text:
                raise ValueError(f"CYIME_AB_REPLACE: {old!r} not in the schema")
            text = text.replace(old, new, 1)
        text = re.sub(r"(?m)^(  schema_id: )rime_ice$", r"\1rime_ice_b", text, count=1)
        (data / "rime_ice_b.schema.yaml").write_text(text, encoding="utf-8")
        default = (data / "default.yaml").read_text(encoding="utf-8")
        (data / "default.yaml").write_text(default.replace("  - schema: rime_ice\n",
                                                           "  - schema: rime_ice\n  - schema: rime_ice_b\n", 1),
                                           encoding="utf-8")
    return data


def prepare_windows(work, source):
    data = work / "windows"
    if data.exists():
        shutil.rmtree(data)
    shutil.copytree(source, data)
    text = (data / "default.yaml").read_text(encoding="utf-8")
    (data / "default.yaml").write_text(re.sub(r"schema_list:\n(  - schema: .*\n)+",
                                              "schema_list:\n  - schema: cyime_pinyin_5\n", text), encoding="utf-8")
    return data


# RimeApi function pointers in rime_api.h order (after int data_size); only the ones used here are typed.
API_FUNCTIONS = (
    "setup set_notification_handler initialize finalize start_maintenance is_maintenance_mode "
    "join_maintenance_thread deployer_initialize prebuild deploy deploy_schema deploy_config_file sync_user_data "
    "create_session find_session destroy_session cleanup_stale_sessions cleanup_all_sessions process_key "
    "commit_composition clear_composition get_commit free_commit get_context free_context get_status free_status "
    "set_option get_option set_property get_property get_schema_list free_schema_list get_current_schema "
    "select_schema schema_open config_open config_close config_get_bool config_get_int config_get_double "
    "config_get_string config_get_cstring config_update_signature config_begin_map config_next config_end "
    "simulate_key_sequence register_module find_module run_task get_shared_data_dir get_user_data_dir "
    "get_sync_dir get_user_id get_user_data_sync_dir config_init config_load_string config_set_bool "
    "config_set_int config_set_double config_set_string config_get_item config_set_item config_clear "
    "config_create_list config_create_map config_list_size config_begin_list get_input get_caret_pos "
    "select_candidate get_version set_caret_pos select_candidate_on_current_page candidate_list_begin "
    "candidate_list_next candidate_list_end").split()


class RimeApiLibrary:
    """The Rime C API of a library that exports only rime_get_api() (the Android JNI build), with the
    Rime* names the Windows rime.dll exports."""

    def __init__(self, path):
        lib = C.CDLL(str(path))
        # Windows host build (host_replay/build_host_windows.py): a DLL exports only what is marked, so the API
        # comes through the host-only C entry point; the Linux .so exports rime_get_api itself.
        get_api = getattr(lib, "cyime_host_rime_get_api", None) or lib.rime_get_api
        get_api.restype = C.c_void_p
        base = get_api()
        for i, name in enumerate(API_FUNCTIONS):
            address = C.c_void_p.from_address(base + C.sizeof(C.c_void_p) * (i + 1)).value
            restype = C.c_size_t if name == "create_session" else C.c_int
            setattr(self, "Rime" + "".join(part.title() for part in name.split("_")),
                    C.CFUNCTYPE(restype)(address))
        self._lib = lib

    def initialize_candidate_policy(self, user_dir):
        """cyime::InitializeCandidatePolicy(const std::string&), which the app's JNI calls right after
        initialize() to register the cyime_candidate_policy filter rime_ice lists; without it Rime drops that
        filter. The argument is a libstdc++ std::string built in place (pointer, size, capacity), only read."""
        text = C.create_string_buffer(user_dir.encode())
        windows = getattr(self._lib, "cyime_host_initialize_candidate_policy", None)
        if windows is not None:
            windows.argtypes = [C.c_char_p]
            windows(text.value)
            return
        string = (C.c_size_t * 4)(C.addressof(text), len(text.value), len(text.value), 0)
        getattr(self._lib, "_ZN5cyime25InitializeCandidatePolicyERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE")(
            C.byref(string))


def load_engine(dll_dir):
    if str(dll_dir).endswith(".so"):
        return RimeApiLibrary(dll_dir)
    if str(dll_dir).endswith("librime_jni.dll"):  # Windows host build; onnxruntime.dll sits next to it
        os.add_dll_directory(str(Path(dll_dir).parent))
        return RimeApiLibrary(dll_dir)
    os.add_dll_directory(str(dll_dir))
    rime = C.CDLL(str(Path(dll_dir) / "rime.dll"))
    rime.RimeCreateSession.restype = C.c_size_t
    return rime


class Traits(C.Structure):
    _fields_ = [("data_size", C.c_int), ("shared_data_dir", C.c_char_p), ("user_data_dir", C.c_char_p),
                ("distribution_name", C.c_char_p), ("distribution_code_name", C.c_char_p),
                ("distribution_version", C.c_char_p), ("app_name", C.c_char_p), ("modules", C.c_void_p),
                ("min_log_level", C.c_int), ("log_dir", C.c_char_p), ("prebuilt_data_dir", C.c_char_p),
                ("staging_dir", C.c_char_p)]


class Cand(C.Structure):
    _fields_ = [("text", C.c_char_p), ("comment", C.c_char_p), ("reserved", C.c_void_p)]


class Iter(C.Structure):
    _fields_ = [("ptr", C.c_void_p), ("index", C.c_int), ("candidate", Cand)]


def run(dll_dir, data, jobs):
    """jobs: [(schema, neighbors, [cases], variant schema or None)]; one engine instance per data directory.

    Returns ({id: top}, {id: top in the variant}, {id: [ms per key]}, {id: [ms per key in the variant]})."""
    rime = load_engine(dll_dir)
    t = Traits()
    t.data_size = C.sizeof(Traits) - C.sizeof(C.c_int)
    t.shared_data_dir = t.user_data_dir = str(data).encode()
    t.distribution_name = t.distribution_code_name = b"lexicon-regression"
    t.distribution_version, t.app_name, t.min_log_level = b"1", b"rime.lexicon_regression", 2
    rime.RimeSetup(C.byref(t))
    rime.RimeInitialize(C.byref(t))
    if isinstance(rime, RimeApiLibrary):
        rime.initialize_candidate_policy(str(data))
    if rime.RimeStartMaintenance(True):
        rime.RimeJoinMaintenanceThread()

    def candidates(session, limit):
        it, top = Iter(), []
        if rime.RimeCandidateListBegin(session, C.byref(it)):
            while len(top) < limit and rime.RimeCandidateListNext(C.byref(it)):
                top.append(it.candidate.text.decode())
            rime.RimeCandidateListEnd(C.byref(it))
        return top

    def type_case(session, case):
        rime.RimeClearComposition(session)
        if os.environ.get("CYIME_SEND_CONTEXT"):
            # The app passes the committed text before the composition as chr(0x1E) + text; cases without
            # a known context are typed with an unknown one, as before.
            context = case.get("context")
            rime.RimeSetProperty(session, b"cyime_left_context",
                                 (chr(0x1E) + context).encode() if context is not None else b"")
        times = []
        for ch in case["input"]:
            start = time.perf_counter_ns()
            rime.RimeProcessKey(session, ord(ch), 0)
            candidates(session, 5)
            times.append((time.perf_counter_ns() - start) / 1e6)
        # CYIME_REPORT_TOP: keep more candidates per case (to tell a deep rank from a word never offered).
        return candidates(session, int(os.environ.get("CYIME_REPORT_TOP", "10"))), times

    def learn(schema, neighbors, rows):
        """Type each word's full pinyin and pick the word, as a user would; the user dictionary is shared by
        both variants (same dictionary name), so it is done once in a session of its own."""
        session = C.c_size_t(rime.RimeCreateSession())
        assert rime.RimeSelectSchema(session, schema.encode()), schema
        rime.RimeSetOption(session, b"ascii_mode", False)
        if neighbors:
            rime.RimeSetProperty(session, b"cyime_neighbors", neighbors.encode())
        learned = 0
        for typed, text in rows:
            rime.RimeClearComposition(session)
            for ch in typed:
                rime.RimeProcessKey(session, ord(ch), 0)
            found = candidates(session, 50)
            if text in found:
                rime.RimeSelectCandidate(session, C.c_size_t(found.index(text)))
                learned += 1
            rime.RimeClearComposition(session)
        rime.RimeDestroySession(session)
        print(f"learned {learned}/{len(rows)} words in {schema}", file=sys.stderr)

    results, results_b, times, times_b = {}, {}, {}, {}
    for schema, neighbors, cases, variant in jobs:
        learn_files = [p for p in os.environ.get("CYIME_LEARN", "").split(";") if p]
        if learn_files and schema == "rime_ice":
            rows = [line.split("\t") for path in learn_files
                    for line in Path(path).read_text(encoding="utf-8").splitlines() if line]
            commits = int(os.environ.get("CYIME_LEARN_COMMITS", "0") or 0)
            if commits and all(len(r) > 2 and r[2].strip().isdigit() for r in rows):
                # As used: each word picked in proportion to its count (at least once), in a shuffled order. Picked
                # once each in file order, the rarest word is the newest and leads every abbreviation it shares.
                total = sum(int(r[2]) for r in rows)
                sequence = [(r[0], r[1]) for r in rows for _ in range(max(1, round(commits * int(r[2]) / total)))]
                random.Random(0).shuffle(sequence)
                learn(schema, neighbors, sequence)
            else:
                learn(schema, neighbors, [tuple(r[:2]) for r in rows])
        sessions = []
        for name in (schema, variant) if variant else (schema,):
            session = C.c_size_t(rime.RimeCreateSession())
            assert rime.RimeSelectSchema(session, name.encode()), name
            rime.RimeSetOption(session, b"ascii_mode", False)
            if neighbors:
                rime.RimeSetProperty(session, b"cyime_neighbors", neighbors.encode())
            sessions.append(session)
        for i, case in enumerate(cases):
            # Alternate which variant types a case first, so neither always meets a warm cache.
            order = ([0, 1] if i % 2 == 0 else [1, 0]) if len(sessions) == 2 else [0]
            for which in order:
                top, keys = type_case(sessions[which], case)
                (results, results_b)[which][case["id"]] = top
                (times, times_b)[which][case["id"]] = keys
        for session in sessions:
            rime.RimeDestroySession(session)
    rime.RimeFinalize()
    return results, results_b, times, times_b


def passed(case, top):
    if case["expect"].startswith("!"):
        return bool(top) and top[0] != case["expect"][1:]
    return case["expect"] in top[:case["top_k"]]


def group(case):
    s = case["source"]
    return "user/found" if s in ("user", "found") or s.startswith("user ") else s


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dll", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--windows-data", type=Path, default=Path("D:/GitHub/CyIME-Windows/data/rime"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--public-set", type=Path, help="Directory with jianpin.jsonl / sentence.jsonl")
    parser.add_argument("--rime-ice-extra", type=Path, help="YAML appended to rime_ice.schema.yaml (experiments)")
    parser.add_argument("--t9-ngram", type=Path, help="Word model linked into the data directory as t9_ngram.lm")
    parser.add_argument("--base-weights", type=Path, help="gzip TSV text/code/weight replacing base.dict weights")
    parser.add_argument("--t9-set", help="Override keys of the 26-key t9 block, e.g. joint_decoder=true,pool_budget=24")
    parser.add_argument("--set-label", help="Group label for the --public-set rows (default: public)")
    parser.add_argument("--ab-replace", help="'OLD=>NEW;...' for a second 26-key schema typed in the same process")
    parser.add_argument("--output-b", type=Path, help="Report of the --ab-replace schema")
    parser.add_argument("--learn", type=Path, nargs="*", default=[],
                        help="TSV files (typed pinyin, word, ...) picked once each in the 26-key schema before "
                             "the cases, so they are typed with those words learned (personal_holdout.py learn.tsv)")
    parser.add_argument("--learn-commits", type=int, default=0,
                        help="With a count column in --learn: about this many picks in all, each word in proportion "
                             "to its count, shuffled (default: each word once, in file order)")
    args = parser.parse_args()
    if args.learn:
        os.environ["CYIME_LEARN"] = ";".join(map(str, args.learn))
    if args.learn_commits:
        os.environ["CYIME_LEARN_COMMITS"] = str(args.learn_commits)
    if args.set_label:
        os.environ["CYIME_SET_LABEL"] = args.set_label
    if args.ab_replace:
        if not args.output_b:
            parser.error("--ab-replace needs --output-b")
        os.environ["CYIME_AB_REPLACE"] = args.ab_replace
    if args.t9_set:
        os.environ["CYIME_T9_SET"] = args.t9_set
    if args.base_weights:
        os.environ["CYIME_BASE_WEIGHTS"] = str(args.base_weights)
    if args.rime_ice_extra:
        os.environ["CYIME_RIME_ICE_EXTRA"] = str(args.rime_ice_extra)
    if args.t9_ngram:
        os.environ["CYIME_T9_NGRAM"] = str(args.t9_ngram)
    if args.public_set:
        os.environ["CYIME_PUBLIC_SET"] = str(args.public_set)
    cases = load_cases()
    by_keyboard = defaultdict(list)
    for case in cases:
        by_keyboard[case["keyboard"]].append(case)
    args.work.mkdir(parents=True, exist_ok=True)
    # Separate processes: librime allows one setup per process.
    runs = [{}, {}, {}, {}]
    for name, keyboards in (("android", ("26", "14")), ("windows", ("win26",))):
        selected = [c for k in keyboards for c in by_keyboard.get(k, [])]
        if not selected:
            continue
        child = args.work / f"{name}.json"
        subprocess.run([sys.executable, __file__, "--child", name, str(args.dll), str(args.work),
                        str(args.windows_data), str(child)], check=True)
        for run_part, part in zip(runs, json.loads(child.read_text(encoding="utf-8"))):
            run_part.update(part)
    results, results_b, times, times_b = runs
    baseline = json.loads(args.baseline.read_text(encoding="utf-8")) if args.baseline else None
    write_report(cases, results, times, baseline, args.output)
    if args.ab_replace:
        # B is listed against A: FIXED/BROKE lines are what the replacement changes.
        print(f"--- B ({args.ab_replace}) against A")
        b_cases = [c for c in cases if c["id"] in results_b]
        a_report = {"cases": [{**c, "status": "pass" if passed(c, results.get(c["id"], [])) else "fail",
                               "top": results.get(c["id"], [])} for c in b_cases]}
        write_report(b_cases, results_b, times_b, a_report, args.output_b)


def percentile(values, q):
    values = sorted(values)
    return values[min(len(values) - 1, int(q * len(values)))] if values else float("nan")


def write_report(cases, results, times, baseline, output):
    report = {"cases": [], "summary": {}}
    stats = defaultdict(lambda: [0, 0])
    old = {c["id"]: c for c in baseline["cases"]} if baseline else {}
    changes = []
    for case in cases:
        if case["keyboard"] == "9":
            report["cases"].append({**case, "status": "not-run"})
            continue
        top = results.get(case["id"], [])
        ok = passed(case, top)
        key = (case["keyboard"], group(case))
        stats[key][0] += ok
        stats[key][1] += 1
        report["cases"].append({**case, "status": "pass" if ok else "fail", "top": top,
                                "key_ms": [round(t, 4) for t in times.get(case["id"], [])]})
        if case["id"] in old and old[case["id"]].get("status") in ("pass", "fail") and \
                old[case["id"]]["status"] != ("pass" if ok else "fail"):
            changes.append(f"{'FIXED' if ok else 'BROKE'} {case['id']} {case['input']} -> {case['expect']} | "
                           f"{old[case['id']].get('top', [])[:3]} -> {top[:3]}")
    weighted = defaultdict(lambda: [0, 0])
    for item in report["cases"]:
        if item.get("count", 1) > 1 or item["source"].startswith("public jianpin"):
            weighted[item["source"]][0] += item["count"] * (item["status"] == "pass")
            weighted[item["source"]][1] += item["count"]
    for source, (good, total) in sorted(weighted.items()):
        print(f"       {source + ' (by occurrences)':55} {100 * good / total:5.1f}%")
    nine = sum(1 for c in cases if c["keyboard"] == "9")
    for (keyboard, source), (good, total) in sorted(stats.items()):
        if total:
            report["summary"][f"{keyboard} {source}"] = f"{good}/{total}"
            print(f"{keyboard:6} {source:55} {good:4}/{total:<4} {100 * good / total:5.1f}%")
    for keyboard in sorted({c["keyboard"] for c in cases if c["keyboard"] != "9"}):
        keys = [t for c in cases if c["keyboard"] == keyboard for t in times.get(c["id"], [])]
        if keys:
            report["summary"][f"{keyboard} key_ms"] = {"keys": len(keys), "median": percentile(keys, 0.5),
                                                       "p95": percentile(keys, 0.95)}
            print(f"{keyboard:6} per key: {len(keys)} keys, median {percentile(keys, 0.5):.3f} ms, "
                  f"p95 {percentile(keys, 0.95):.3f} ms")
    print(f"nine-key rows not run here: {nine} (use t9_replay.py)")
    for line in changes:
        print(line)
    if output:
        output.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")


def child():
    _, _, name, dll, work, windows_data, out = sys.argv
    work = Path(work)
    by_keyboard = defaultdict(list)
    for case in load_cases():
        by_keyboard[case["keyboard"]].append(case)
    neighbors = qwerty_neighbors()
    if name == "android":
        data = prepare_android(work)
        variant = "rime_ice_b" if os.environ.get("CYIME_AB_REPLACE") else None
        jobs = [("rime_ice", neighbors, by_keyboard["26"], variant), ("pinyin_14jian", "", by_keyboard["14"], None)]
    else:
        data = prepare_windows(work, Path(windows_data))
        jobs = [("cyime_pinyin_5", neighbors, by_keyboard["win26"], None)]
    Path(out).write_text(json.dumps(run(Path(dll), data, jobs), ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--child":
        child()
    else:
        main()
