"""Which code the keyboard's CPU goes to, by thread and function, sampled on the device with simpleperf.

energy_bench.py says how much CPU each state costs and which thread spends it. This says which functions: it records
the call stack of every thread of CyIME's processes about a thousand times per second of CPU while a scenario runs

  type        keyboard up, the same pinyin words as energy_bench at a fixed pace (cleared, nothing committed)
  idle_shown  keyboard up, nobody touches it
  hidden_focused  keyboard hidden with the text field still focused

and writes text reports to --out/<scenario>/ (made by simpleperf on the device; names are readable because the
package is not obfuscated):

  threads.txt          share of the samples per thread (samples are CPU time: a sleeping thread has none)
  self.txt             functions by their own time, per thread and library, largest first
  tree-<thread>.txt    for each thread with at least --thread-share percent: functions by the time spent in them
                       including what they call (the Children column), largest first; read it top down to see
                       which part of a key press or a frame is expensive
  callers-<thread>.txt the same thread's call graph for the entries above 2 percent (who calls what)
  profile.json         device, switches that were on, seconds, keys, sample count

Needs a 装到我的设备 build made with the profileable flag (app/build.gradle.kts, 2026-11-22): adb may sample only
apps that allow it, public packages do not, and the script then stops with simpleperf's message. Sampling slows the
keyboard a little, so latency is not to be measured during a profile run, and CPU totals come from energy_bench.

Run it under the guarded runner, after reading <notes>/Project/Agent状态同步.md:
  python -I scripts/guarded-device-run.py --serial TABLET_SERIAL -- \\
    python tools/ime_lab/cpu_profile.py --serial TABLET_SERIAL \\
      --layouts tools/demo_typer/layouts/sm-x800.json --layout cn26 \\
      --host-cmd "am start -n md.obsidian/.MainActivity" --host-window md.obsidian \\
      --interval 100 --out D:/CyIME-Data/eval/cpu-profile-<date>
The text field should be another app's: CyIME's own test field lives in the keyboard's process and its redrawing
would be counted as the keyboard's.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import energy_bench as eb  # noqa: E402  (scenarios, typing, device info)
import perf_cost_bench as pcb  # noqa: E402

PACKAGE = pcb.PACKAGE
REMOTE = "/data/local/tmp/cyime-perf.data"


def adb(serial, *args, check=True):
    return pcb.adb(serial, *args, check=check)


def start_recording(serial, seconds, frequency):
    """simpleperf samples every thread of the app's processes for `seconds`; returns the adb process."""
    command = (f"simpleperf record --app {PACKAGE} -e cpu-clock:u -f {int(frequency)} -g "
               f"--duration {int(seconds)} -o {REMOTE}")
    return subprocess.Popen(["adb", "-s", serial, "shell", command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace")


def finish_recording(serial, recorder, seconds):
    try:
        output, _ = recorder.communicate(timeout=seconds + 180)
    except subprocess.TimeoutExpired:
        recorder.kill()
        raise SystemExit("simpleperf did not finish; nothing recorded")
    listed = adb(serial, "shell", f"ls -l {REMOTE}", check=False)
    if recorder.returncode != 0 or REMOTE not in listed:
        raise SystemExit("simpleperf could not record CyIME (a package without the profileable flag cannot be sampled "
                         f"from adb; it needs a 装到我的设备 build made after 2026-11-22):\n{output.strip()}")
    return output


def report(serial, *options):
    """One text report of the recording, made on the device. Thread names may hold spaces, so every option is quoted."""
    command = "simpleperf report -i " + REMOTE + " " + " ".join(shlex.quote(option) for option in options)
    return adb(serial, "shell", command + " 2>&1", check=False)


def thread_shares(threads_report):
    """[(percent, samples, thread name)] from a `--sort comm -n` report, largest first."""
    rows = []
    for line in threads_report.splitlines():
        match = re.match(r"\s*([\d.]+)%\s+(\d+)\s+(\S.*?)\s*$", line)
        if match:
            rows.append((float(match.group(1)), int(match.group(2)), match.group(3)))
    return sorted(rows, reverse=True)


def file_name(thread):
    return re.sub(r"[^A-Za-z0-9._-]+", "_", thread).strip("_") or "thread"


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write(text)


def profile(args, name, action, seconds, keys_typed, info):
    out_dir = os.path.join(args.out, name)
    os.makedirs(out_dir, exist_ok=True)
    adb(args.serial, "shell", f"rm -f {REMOTE}", check=False)
    recorder = start_recording(args.serial, seconds + 4, args.frequency)
    time.sleep(2)  # the recorder attaches before the scenario starts
    started = time.time()
    action()
    elapsed = time.time() - started
    finish_recording(args.serial, recorder, seconds + 4)
    threads = report(args.serial, "--sort", "comm", "-n")
    write(os.path.join(out_dir, "threads.txt"), threads)
    write(os.path.join(out_dir, "self.txt"),
          report(args.serial, "--sort", "comm,dso,symbol", "-n", "--percent-limit", "0.2"))
    shares = thread_shares(threads)
    busy = [thread for percent, _, thread in shares if percent >= args.thread_share]
    for thread in busy:
        write(os.path.join(out_dir, f"tree-{file_name(thread)}.txt"),
              report(args.serial, "--children", "--sort", "symbol", "--comms", thread, "-n", "--percent-limit", "0.5"))
        write(os.path.join(out_dir, f"callers-{file_name(thread)}.txt"),
              report(args.serial, "--children", "--sort", "symbol", "--comms", thread, "-n", "--percent-limit", "2",
                     "-g", "caller"))
    if args.keep_data:
        adb(args.serial, "pull", REMOTE, os.path.join(out_dir, "perf.data"), check=False)
    adb(args.serial, "shell", f"rm -f {REMOTE}", check=False)
    result = {"scenario": name, "seconds": round(elapsed, 1), "keys": keys_typed, "frequency": args.frequency,
              "samples": sum(samples for _, samples, _ in shares),
              "threads": [{"thread": thread, "percent": percent, "samples": samples} for percent, samples, thread in shares[:20]],
              **info}
    write(os.path.join(out_dir, "profile.json"), json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({k: result[k] for k in ("scenario", "seconds", "keys", "samples")}, ensure_ascii=False),
          " ".join(f"{t['thread']}={t['percent']}%" for t in result["threads"][:6]), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--layouts", required=True)
    parser.add_argument("--layout", default="cn26")
    parser.add_argument("--host-cmd", required=True)
    parser.add_argument("--host-window", required=True)
    parser.add_argument("--scenarios", default="type,idle_shown")
    parser.add_argument("--words", default=pcb.WORDS)
    parser.add_argument("--scheme", default="pinyin", choices=["pinyin", "flypy"])
    parser.add_argument("--rounds", type=int, default=4, help="typing rounds (4 ≈ 600 keys)")
    parser.add_argument("--interval", type=int, default=100, help="ms between key downs")
    parser.add_argument("--hold", type=int, default=40)
    parser.add_argument("--idle", type=int, default=30, help="seconds for idle_shown and hidden_focused")
    parser.add_argument("--frequency", type=int, default=1000, help="samples per second of CPU time per thread")
    parser.add_argument("--thread-share", type=float, default=3.0,
                        help="threads with at least this percent of the samples get their own tree and callers report")
    parser.add_argument("--keep-data", action="store_true", help="also pull the recording (perf.data, tens of MB)")
    parser.add_argument("--cool", type=int, default=10)
    parser.add_argument("--dex", default="D:/CyIME-Data/demo-video/cyime-inject.dex")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    if pcb.guarded.current_ime(args.serial) != pcb.guarded.IME:
        raise SystemExit("CyIME is not the default keyboard")
    adb(args.serial, "push", "-q", args.dex, pcb.INJECT_DEX)
    keys = pcb.keys_for(args.layouts, args.layout)
    switches = eb.switch_states(args.serial)
    info = {"device": eb.device_info(args.serial), "switchesOn": sorted(k for k, on in (switches or {}).items() if on),
            "switchesKnown": switches is not None, "layout": args.layout, "intervalMs": args.interval}
    try:
        for name in filter(None, args.scenarios.split(",")):
            eb.bring_up_field(args, PACKAGE)
            if name == "type":
                action, count, seconds = eb.typing_action(args, keys, PACKAGE)
                profile(args, name, action, int(seconds) + 1, count, info)
            elif name == "idle_shown":
                profile(args, name, lambda: time.sleep(args.idle), args.idle, 0, info)
            elif name == "hidden_focused":
                adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(2)
                if eb.keyboard_shown(args.serial):
                    raise SystemExit("keyboard did not hide on BACK")
                profile(args, name, lambda: time.sleep(args.idle), args.idle, 0, info)
            else:
                raise SystemExit(f"unknown scenario {name}")
            time.sleep(args.cool)
    finally:
        adb(args.serial, "shell", f"rm -f {REMOTE}", check=False)
        if pcb.guarded.current_ime(args.serial) != pcb.guarded.IME and not pcb.guarded.select_ime(args.serial):
            print("WARNING: CyIME is not the default keyboard again; set it by hand", file=sys.stderr)


if __name__ == "__main__":
    main()
