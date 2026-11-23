"""How much CPU, frames, memory (and, unplugged, battery current) CyIME costs in the states users are in.

perf_cost_bench.py measures key-to-frame latency per switch. Latency is not energy: a key that waits 16 ms for
vsync costs nothing, a background animation that never delays a key costs battery all the time, and the glass blur
is drawn by SurfaceFlinger, which battery pages charge to the system, not to CyIME. This script measures what the
battery actually pays for, per scenario:

  type            keyboard up, the same pinyin words as perf_cost_bench at a fixed pace (cleared, nothing committed)
  idle_shown      keyboard up, nobody touches it (should be ~0 CPU and ~0 frames)
  hidden_focused  keyboard hidden with the text field still focused (background wake-ups only)
  screen_off      screen off for --screen-off seconds (needs --screen-off > 0; wakes and unlocks the screen after)

For each scenario:
  - CPU ms of every CyIME process (main, :asr, :inference), from /proc/<pid>/stat, plus the uid total from
    `dumpsys batterystats --checkin` (cumulative, so a process that died mid-run is still counted; no --reset,
    the device's own battery history is left alone)
  - the same for each thread of the main process (threadsMs, from /proc/<pid>/task/*/stat, threads of one name added
    up): tells the engine thread from the UI thread, RenderThread, the runtime's JIT and GC threads
  - SurfaceFlinger CPU ms (where the window blur and extra composition go)
  - frames CyIME rendered and janky frames (`dumpsys gfxinfo`), and for the frames of this scenario that the device
    still holds (the last 120 of each window, `dumpsys gfxinfo framestats`) where each frame's time went
    (framePhases, per window, median and p90 ms): inputMs = handling the touch, animationMs = animation callbacks
    (Compose recomposes here), layoutMs = measure and layout, drawMs = recording the drawing, renderMs = RenderThread
    until the frame is done. A window that drew nothing in the scenario is not listed
  - PSS, swapped PSS and RSS of every CyIME process at the end (memory pages count the swapped part separately;
    a process "using 150 MB" may have 125 MB of it in zram)
  - with --current and the device NOT charging (adb over Wi-Fi): median battery current in mA, sampled each second.
    That is the only number here that is real energy; the rest are its causes. On USB it is skipped.
Typing is reported per 100 keys, idle states per minute, so runs of different length compare; cpuPercent is the
keyboard's processes together in percent of one core over the scenario (what a monitor app shows), framesPerSecond the
frames it drew. energy.json also keeps the device (model, Android version, refresh rate) and which switches were on.

--pin little keeps the whole keyboard (every thread of its main process) on the slowest core cluster while it is
measured, so a flagship stands in for a budget phone, whose cores are of that class: CPU ms are then ms on those
cores. It needs a 装到我的设备 build with the dev op cpu_pin; the pin is set before every scenario and read again
after it (pinEscaped: true marks a scenario in which the system moved a thread off those cores, e.g. when it moved
the process to another cpuset: discard it). All cores are allowed again at the end. Compare pinned runs only with
pinned runs.

--off glass,key_animation,key_glow,key_gap_light switches those effects off for the run (the ids perf_state lists),
restarts the keyboard so it starts clean, and puts them back at the end: the same scenarios with and without them give
what the effects cost in CPU and frames. The guarded runner restores every setting afterwards as well.

--other-ime runs the typing scenario once more with another keyboard (Samsung Keyboard, Gboard...) on the same
field and words, so "CyIME uses X" can be said against a reference. That keyboard needs its own key coordinates
(--other-layouts, demo_typer format). CyIME is selected again at the end.

Run it under the guarded runner with a 装到我的设备 build, after reading <notes>/Project/Agent状态同步.md:
  python -I scripts/guarded-device-run.py --serial TABLET_SERIAL -- \\
    python tools/ime_lab/energy_bench.py --serial TABLET_SERIAL \\
      --layouts tools/demo_typer/layouts/sm-x800.json --layout cn26 \\
      --host-cmd "am start -n md.obsidian/.MainActivity" --host-window md.obsidian \\
      --out D:/CyIME-Data/eval/energy-<date>
Read-only on the app: it does not change any CyIME setting. Run the same command before and after a change.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import perf_cost_bench as pcb  # noqa: E402  (key maps, touch scripts, guarded adb helpers)

PACKAGE = pcb.PACKAGE
# Whose processes are counted: CyIME, or the --other-ime keyboard while that one is measured.
TARGET = {"package": PACKAGE}
CLK_TCK = 100  # Android kernels: USER_HZ 100
CURRENT_NODE = "/sys/class/power_supply/battery/current_now"


def adb(serial, *args, check=True):
    return pcb.adb(serial, *args, check=check)


def uid_of(serial):
    out = adb(serial, "shell", "cmd", "package", "list", "packages", "--user", "0", "-U", PACKAGE)
    # --user 0: on a device with a Secure Folder (Samsung user 150) the plain listing is refused for the shell.
    match = re.search(rf"package:{re.escape(PACKAGE)} uid:(\d+)", out)
    if not match:
        raise SystemExit(f"{PACKAGE} not installed")
    return int(match.group(1))


def processes(serial):
    """{pid: name} of every process of the keyboard under test (CyIME: main, :asr, :inference)."""
    out = adb(serial, "shell", "ps", "-A", "-o", "PID,NAME", check=False)
    found = {}
    for line in out.splitlines()[1:]:
        parts = line.split()
        package = TARGET["package"]
        if len(parts) == 2 and (parts[1] == package or parts[1].startswith(package + ":")):
            found[int(parts[0])] = parts[1]
    return found


def proc_cpu_ms(serial, pids):
    """{pid: utime+stime ms}; a pid that is gone is left out."""
    if not pids:
        return {}
    cmd = "; ".join(f"echo {pid} $(cat /proc/{pid}/stat 2>/dev/null)" for pid in pids)
    result = {}
    for line in adb(serial, "shell", cmd, check=False).splitlines():
        pid, _, stat = line.partition(" ")
        # comm may hold spaces: fields after the closing parenthesis, utime/stime are the 12th/13th of those
        tail = stat.rpartition(")")[2].split()
        if len(tail) > 12:
            result[int(pid)] = (int(tail[11]) + int(tail[12])) * 1000 // CLK_TCK
    return result


def thread_cpu_ms(serial, pid):
    """{thread name: utime+stime ms} of one process; threads of the same name are added up (binder:1234_5 and
    binder:1234_6 are both "binder", DefaultDispatcher workers one row). Empty when the process is gone."""
    out = adb(serial, "shell", f"cat /proc/{pid}/task/*/stat 2>/dev/null", check=False)
    result = {}
    for line in out.splitlines():
        head, _, tail = line.rpartition(")")
        fields = tail.split()
        if "(" not in head or len(fields) <= 12:
            continue
        name = re.sub(r"^(?i:(binder)):\d+_[0-9a-fA-F]+$", r"\1", head.partition("(")[2])
        name = re.sub(r"([-_:#.]?\d+)+$", "", name).strip() or "?"
        result[name] = result.get(name, 0) + (int(fields[11]) + int(fields[12])) * 1000 // CLK_TCK
    return result


def cluster_masks(serial, choice):
    """(mask to pin to, mask of every core, max kHz of the chosen cluster or None) for --pin: little / big are the
    slowest / fastest cpufreq policy of the device, anything else a hex mask given as is."""
    clusters = []
    for policy in adb(serial, "shell", "ls", "/sys/devices/system/cpu/cpufreq", check=False).split():
        if not re.fullmatch(r"policy\d+", policy):
            continue
        base = "/sys/devices/system/cpu/cpufreq/" + policy
        cpus = []
        for part in adb(serial, "shell", "cat", base + "/related_cpus").replace(",", " ").split():
            first, _, last = part.partition("-")
            cpus.extend(range(int(first), int(last or first) + 1))
        clusters.append((int(adb(serial, "shell", "cat", base + "/cpuinfo_max_freq").strip() or 0), cpus))
    if not clusters:
        raise SystemExit("--pin: no cpufreq policy on this device")
    clusters.sort()
    everything = sum(1 << cpu for _, cpus in clusters for cpu in cpus)
    if choice in ("little", "big"):
        khz, cpus = clusters[0] if choice == "little" else clusters[-1]
        return sum(1 << cpu for cpu in cpus), everything, khz
    return int(choice, 16), everything, None


def keyboard_affinity(serial, mask=None):
    """Mask of the cores the keyboard's threads may run on; with `mask`, pins them to it first (dev op cpu_pin)."""
    answer = pcb.dev_op(serial, "cpu_pin", "--es", "mask", format(mask, "x")) if mask else pcb.dev_op(serial, "cpu_pin_state")
    match = re.search(r"\bmask=([0-9a-f]+)", answer)
    if not match:
        raise SystemExit(f"cpu_pin: {answer!r} (--pin needs a build with the dev op cpu_pin)")
    return int(match.group(1), 16)


def surfaceflinger_ms(serial):
    pid = adb(serial, "shell", "pidof", "surfaceflinger", check=False).split()
    return next(iter(proc_cpu_ms(serial, [int(pid[0])]).values()), None) if pid else None


def batterystats_cpu_ms(serial, uid):
    """Cumulative user+system CPU ms of the uid since the last full charge (checkin 'cpu' line)."""
    out = adb(serial, "shell", "dumpsys", "batterystats", "--checkin", check=False)
    match = re.search(rf"^9,{uid},l,cpu,(\d+),(\d+)", out, re.M)
    return int(match.group(1)) + int(match.group(2)) if match else None


def gfx_reset(serial):
    adb(serial, "shell", "dumpsys", "gfxinfo", TARGET["package"], "reset", check=False)


def gfx_read(serial):
    out = adb(serial, "shell", "dumpsys", "gfxinfo", TARGET["package"], check=False)
    def num(pattern):
        match = re.search(pattern, out)
        return int(match.group(1)) if match else None
    return {"frames": num(r"Total frames rendered:\s*(\d+)"), "janky": num(r"Janky frames:\s*(\d+)"),
            "p90Ms": num(r"90th percentile:\s*(\d+)ms")}


FRAME_PHASES = (("inputMs", "HandleInputStart", "AnimationStart"),
                ("animationMs", "AnimationStart", "PerformTraversalsStart"),
                ("layoutMs", "PerformTraversalsStart", "DrawStart"), ("drawMs", "DrawStart", "SyncQueued"),
                ("renderMs", "SyncStart", "FrameCompleted"), ("totalMs", "IntendedVsync", "FrameCompleted"))


def frame_rows(serial):
    """{window: [{column: ns}]} of the frames the device still holds (120 per window), from gfxinfo framestats.
    Columns differ between Android versions, so rows are read by the header's names."""
    out = adb(serial, "shell", "dumpsys", "gfxinfo", TARGET["package"], "framestats", check=False)
    windows, window, header = {}, None, None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Window:"):
            window, header = line[len("Window:"):].strip() or "?", None
        elif line.startswith("Flags,"):
            header = line.rstrip(",").split(",")
        elif window and header and re.fullmatch(r"[\d,]+", line):
            values = line.rstrip(",").split(",")
            if len(values) == len(header):
                windows.setdefault(window, []).append(dict(zip(header, map(int, values))))
    return windows


def last_vsync(rows_by_window):
    return max((row.get("IntendedVsync", 0) for rows in rows_by_window.values() for row in rows), default=0)


def frame_phases(rows_by_window, after_vsync):
    """{window: {frames, <phase>: {median, p90}}} for the frames newer than after_vsync. A frame that was skipped or
    has a timestamp missing (0) is left out of the phase it would distort."""
    result = {}
    for window, rows in rows_by_window.items():
        rows = [row for row in rows if row.get("IntendedVsync", 0) > after_vsync]
        if not rows:
            continue
        entry = {"frames": len(rows)}
        for name, start, end in FRAME_PHASES:
            spans = sorted((row[end] - row[start]) / 1e6 for row in rows
                           if row.get(start, 0) > 0 and row.get(end, 0) >= row[start])
            if spans:
                entry[name] = {"median": round(statistics.median(spans), 2),
                               "p90": round(spans[min(len(spans) - 1, int(len(spans) * 0.9))], 2)}
        result[window] = entry
    return result


def device_info(serial):
    """What the numbers were measured on: they do not compare across devices or refresh rates."""
    def prop(name):
        return adb(serial, "shell", "getprop", name, check=False).strip()
    display = adb(serial, "shell", "dumpsys", "display", check=False)
    rate = re.search(r"(?:mActiveRenderFrameRate|renderFrameRate)[ =]([\d.]+)", display) or re.search(r"fps=([\d.]+)", display)
    return {"model": prop("ro.product.model"), "android": prop("ro.build.version.release"), "sdk": prop("ro.build.version.sdk"),
            "soc": prop("ro.soc.model") or prop("ro.board.platform"), "refreshHz": float(rate.group(1)) if rate else None,
            "batteryTempC": pcb.battery_temp(serial)}


def switch_states(serial):
    """{id: on} of the registered switches (dev op perf_state), or None on a build without the dev tools."""
    try:
        return pcb.state(serial)
    except SystemExit:
        return None


def memory(serial):
    """{process: {pssMb, swapPssMb, rssMb}} from dumpsys meminfo per pid."""
    result = {}
    for pid, name in processes(serial).items():
        out = adb(serial, "shell", "dumpsys", "meminfo", str(pid), check=False)
        def mb(pattern):
            match = re.search(pattern, out)
            return round(int(match.group(1)) / 1024, 1) if match else None
        result[name] = {"pssMb": mb(r"TOTAL PSS:\s+(\d+)"), "swapPssMb": mb(r"TOTAL SWAP PSS:\s+(\d+)"),
                        "rssMb": mb(r"TOTAL RSS:\s+(\d+)")}
    return result


def unplugged(serial):
    out = adb(serial, "shell", "dumpsys", "battery", check=False)
    return all(f"{kind} powered: false" in out for kind in ("AC", "USB", "Wireless"))


class CurrentSampler:
    """Samples the battery current node once a second in a background adb shell; median |mA| at stop()."""

    def __init__(self, serial, seconds):
        self.proc = subprocess.Popen(
            ["adb", "-s", serial, "shell", f"i=0; while [ $i -lt {int(seconds) + 5} ]; do cat {CURRENT_NODE}; sleep 1; i=$((i+1)); done"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)

    def stop(self):
        self.proc.terminate()
        out, _ = self.proc.communicate()
        values = [abs(int(v)) for v in out.split() if re.fullmatch(r"-?\d+", v)]
        if not values:
            return None
        median = statistics.median(values)
        # Most devices report µA, some mA: anything above 20 A must be µA.
        return round(median / 1000 if median > 20000 else median, 1)


def keyboard_shown(serial):
    return "mInputShown=true" in adb(serial, "shell", "dumpsys", "input_method", check=False)


def bring_up_field(args, ime_package):
    adb(args.serial, "shell", args.host_cmd)
    for _ in range(20):
        time.sleep(2)
        focus = adb(args.serial, "shell", "dumpsys", "window", check=False)
        if args.host_window in focus and keyboard_shown(args.serial) and \
                ime_package in adb(args.serial, "shell", "settings", "get", "secure", "default_input_method"):
            return
    raise SystemExit("keyboard did not come up in the host field (tap the field once and rerun)")


def play(serial, text, host_window, ime_package):
    """Same as perf_cost_bench.play, but the guard accepts the keyboard under test (CyIME or --other-ime)."""
    with tempfile.TemporaryDirectory() as local:
        path = os.path.join(local, "script.txt")
        with open(path, "w", encoding="utf-8", newline="\n") as out:
            out.write(text)
        adb(serial, "push", "-q", path, pcb.REMOTE_SCRIPT)
    guard = (f"dumpsys window | grep mCurrentFocus | grep -q {host_window} && "
             f"settings get secure default_input_method | grep -q {ime_package} && "
             "dumpsys input_method | grep -q mInputShown=true || { echo NOGUARD; exit 0; }; ")
    out = adb(serial, "shell", f"{guard}CLASSPATH={pcb.INJECT_DEX} app_process / Inject {pcb.REMOTE_SCRIPT} >/dev/null; "
                               f"rm -f {pcb.REMOTE_SCRIPT}")
    if "NOGUARD" in out:
        raise SystemExit(f"stopped: {host_window} is not in front with the keyboard under test up")


def run_scenario(args, uid, name, action, seconds_hint, keys_typed=0):
    """Wraps one scenario with before/after counters; action() does the work."""
    pids = processes(args.serial)
    pin = args.pin_mask if TARGET["package"] == PACKAGE else None
    if pin and keyboard_affinity(args.serial, pin) != pin:
        raise SystemExit(f"--pin: the keyboard's threads could not be kept on mask {pin:x}")
    main_pid = next((pid for pid, process in pids.items() if process == TARGET["package"]), None)
    threads0 = thread_cpu_ms(args.serial, main_pid) if main_pid else {}
    cpu0, sf0, bs0 = proc_cpu_ms(args.serial, pids), surfaceflinger_ms(args.serial), batterystats_cpu_ms(args.serial, uid)
    gfx_reset(args.serial)
    vsync0 = last_vsync(frame_rows(args.serial))
    sampler = CurrentSampler(args.serial, seconds_hint) if args.current_ok else None
    started = time.time()
    action()
    elapsed = time.time() - started
    current = sampler.stop() if sampler else None
    gfx = gfx_read(args.serial)
    phases = frame_phases(frame_rows(args.serial), vsync0)
    cpu1, sf1, bs1 = proc_cpu_ms(args.serial, pids), surfaceflinger_ms(args.serial), batterystats_cpu_ms(args.serial, uid)
    threads1 = thread_cpu_ms(args.serial, main_pid) if main_pid else {}
    per_thread = {name: ms - threads0.get(name, 0) for name, ms in threads1.items()}
    after = keyboard_affinity(args.serial) if pin else None
    per_process = {pids[p]: cpu1[p] - cpu0[p] for p in cpu1 if p in cpu0}
    result = {
        "scenario": name, "seconds": round(elapsed, 1), "keys": keys_typed,
        "cpuMs": per_process, "cpuMsTotal": sum(per_process.values()),
        # Largest first; threads that used no CPU in the scenario are left out. The clock ticks in 10 ms.
        "threadsMs": dict(sorted(((n, ms) for n, ms in per_thread.items() if ms > 0), key=lambda item: -item[1])[:16]),
        "uidCpuMs": (bs1 - bs0) if bs0 is not None and bs1 is not None else None,
        "surfaceflingerMs": (sf1 - sf0) if sf0 is not None and sf1 is not None else None,
        **({"pinnedMask": format(pin, "x"), "pinEscaped": after != pin} if pin else {}),
        **gfx, "framePhases": phases, "currentMa": current, "memory": memory(args.serial),
        "restarted": sorted(set(processes(args.serial).values()) ^ set(pids.values())),
    }
    total = result["uidCpuMs"] if result["uidCpuMs"] is not None else result["cpuMsTotal"]
    if keys_typed:
        result["cpuMsPer100Keys"] = round(total * 100 / keys_typed, 1)
    else:
        result["cpuMsPerMinute"] = round(total * 60 / max(elapsed, 1), 1)
    result["cpuPercent"] = round(result["cpuMsTotal"] / max(elapsed, 1) / 10, 1)
    if result.get("frames") is not None:
        result["framesPerSecond"] = round(result["frames"] / max(elapsed, 1), 1)
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return result


def typing_action(args, keys, ime_package):
    words = pcb.key_sequences(args.words, args.scheme)
    text = pcb.script(keys, words, args.interval, args.hold, args.rounds)
    count = sum(len(w) for w in words) * args.rounds
    return (lambda: play(args.serial, text, args.host_window, ime_package)), count, \
        count * args.interval / 1000 + len(words) * args.rounds * 0.45


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--layouts", required=True)
    parser.add_argument("--layout", default="cn26")
    parser.add_argument("--host-cmd", required=True)
    parser.add_argument("--host-window", required=True)
    parser.add_argument("--scenarios", default="type,idle_shown,hidden_focused")
    parser.add_argument("--words", default=pcb.WORDS)
    parser.add_argument("--scheme", default="pinyin", choices=["pinyin", "flypy"])
    parser.add_argument("--rounds", type=int, default=6, help="typing rounds (6 ≈ 900 keys)")
    parser.add_argument("--interval", type=int, default=160, help="ms between key downs (about 6 keys/s)")
    parser.add_argument("--hold", type=int, default=40)
    parser.add_argument("--idle", type=int, default=60, help="seconds for idle_shown and hidden_focused")
    parser.add_argument("--screen-off", type=int, default=0, help="seconds for screen_off (0 = skip)")
    parser.add_argument("--current", action="store_true", help="sample battery current (only used when unplugged)")
    parser.add_argument("--other-ime", default="", help="IME id to compare on the typing scenario, e.g. "
                        "com.samsung.android.honeyboard/.service.HoneyBoardService")
    parser.add_argument("--other-layouts", default="")
    parser.add_argument("--other-layout", default="cn26")
    parser.add_argument("--off", default="", help="switches (perf_state ids, comma-separated) turned off for this run and "
                        "put back at the end, e.g. glass,key_animation,key_glow,key_gap_light")
    parser.add_argument("--pin", default="", help="keep the whole keyboard on these cores while measuring: little or big "
                        "(slowest / fastest core cluster) or a hex mask; little = budget-phone class cores. Needs the "
                        "dev op cpu_pin (装到我的设备 build)")
    parser.add_argument("--cool", type=int, default=20)
    parser.add_argument("--dex", default="D:/CyIME-Data/demo-video/cyime-inject.dex")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    args.current_ok = args.current and unplugged(args.serial)
    if args.current and not args.current_ok:
        print("charging or on USB power: battery current skipped (use adb over Wi-Fi, unplugged)", file=sys.stderr)
    if pcb.guarded.current_ime(args.serial) != pcb.guarded.IME:
        raise SystemExit("CyIME is not the default keyboard")
    adb(args.serial, "push", "-q", args.dex, pcb.INJECT_DEX)
    uid = uid_of(args.serial)
    keys = pcb.keys_for(args.layouts, args.layout)
    args.pin_mask, every_core, pin_khz = cluster_masks(args.serial, args.pin) if args.pin else (None, None, None)
    put_back = {}
    if args.off:
        before = pcb.state(args.serial)
        wanted = {item: False for item in filter(None, args.off.split(","))}
        unknown = sorted(set(wanted) - set(before))
        if unknown:
            raise SystemExit(f"--off: unknown switch {', '.join(unknown)} (perf_state lists them)")
        put_back = {item: True for item in wanted if before[item]}
        pcb.apply_config(args.serial, wanted, dict(before))
        pcb.restart_ime(args)
    if args.pin_mask:
        print(f"keyboard pinned to mask {args.pin_mask:x}" + (f" (cluster max {pin_khz} kHz)" if pin_khz else ""), file=sys.stderr)
    device, switches = device_info(args.serial), switch_states(args.serial)
    results = []
    try:
        for name in filter(None, args.scenarios.split(",")):
            bring_up_field(args, PACKAGE)
            if name == "type":
                action, count, seconds = typing_action(args, keys, PACKAGE)
                results.append(run_scenario(args, uid, name, action, seconds, count))
            elif name == "idle_shown":
                results.append(run_scenario(args, uid, name, lambda: time.sleep(args.idle), args.idle))
            elif name == "hidden_focused":
                adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(2)
                if keyboard_shown(args.serial):
                    raise SystemExit("keyboard did not hide on BACK")
                results.append(run_scenario(args, uid, name, lambda: time.sleep(args.idle), args.idle))
            elif name == "screen_off" and args.screen_off > 0:
                def off():
                    adb(args.serial, "shell", "input", "keyevent", "KEYCODE_SLEEP")
                    time.sleep(args.screen_off)
                    adb(args.serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
                    adb(args.serial, "shell", "wm", "dismiss-keyguard", check=False)
                results.append(run_scenario(args, uid, name, off, args.screen_off))
            else:
                raise SystemExit(f"unknown scenario {name}")
            time.sleep(args.cool)
        if args.other_ime:
            if not args.other_layouts:
                raise SystemExit("--other-ime needs --other-layouts (that keyboard's key coordinates)")
            other_package = args.other_ime.split("/")[0]
            adb(args.serial, "shell", "ime", "enable", args.other_ime, check=False)
            adb(args.serial, "shell", "ime", "set", args.other_ime)
            bring_up_field(args, other_package)
            other_keys = pcb.keys_for(args.other_layouts, args.other_layout)
            action, count, seconds = typing_action(args, other_keys, other_package)
            # The other keyboard's own CPU: count its processes instead of CyIME's.
            other_uid = re.search(r"uid:(\d+)", adb(args.serial, "shell", "cmd", "package", "list", "packages", "--user", "0", "-U",
                                                     other_package))
            if not other_uid:
                raise SystemExit(f"{other_package} not installed")
            TARGET["package"] = other_package
            try:
                results.append(run_scenario(args, int(other_uid.group(1)), "type:" + other_package, action, seconds, count))
            finally:
                TARGET["package"] = PACKAGE
    finally:
        if put_back:
            try:
                pcb.apply_config(args.serial, put_back, pcb.state(args.serial))
            except SystemExit as error:
                print(f"WARNING: switches not put back ({', '.join(put_back)}): {error}", file=sys.stderr)
        if args.pin_mask:
            # All cores again (a restarted keyboard process has no pin anyway); never fails the run.
            try:
                keyboard_affinity(args.serial, every_core)
            except SystemExit as error:
                print(f"WARNING: could not lift the pin: {error}", file=sys.stderr)
        if pcb.guarded.current_ime(args.serial) != pcb.guarded.IME and not pcb.guarded.select_ime(args.serial):
            print("WARNING: CyIME is not the default keyboard again; set it by hand", file=sys.stderr)
        with open(os.path.join(args.out, "energy.json"), "w", encoding="utf-8") as out:
            json.dump({"package": PACKAGE, "args": {k: (format(v, "x") if k == "pin_mask" and v else v)
                                                    for k, v in vars(args).items() if k != "dex"},
                       "device": device, "switchesOn": sorted(k for k, on in (switches or {}).items() if on),
                       "switchesKnown": switches is not None, "results": results}, out, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
