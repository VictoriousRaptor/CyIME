"""按键落点统计的报告：手指按下去，出来的是不是想按的那个键。

输入是「装到我的设备」的包用开发者广播 op=touch_stats 导出的目录（touch_stats.txt、typing_stats.txt、device.txt）。
里面只有计数（每个键的落点格、没出字的原因、几根手指交叠、删掉重打的键对等），没有打过的任何文字。
输出一份 HTML（落点图和各种比例）和同名的 .json（给别的脚本用）。

用法：
  python -I tools/ime_lab/touch_stats_report.py --dir <导出目录> [--base <更早的一次导出>] [--out <报告.html>]
  python -I tools/ime_lab/touch_stats_report.py --serial <设备号> --pull <放到电脑上的目录> [--out <报告.html>]

--pull 先让输入法导出一次（只读统计，不改任何设置、不点屏幕），再 adb pull 回来。用 adb 前先看
<notes>/Project/Agent状态同步.md 并登记设备。
--base 给了以后只算两次导出之间新增的计数（比如改了触区以后的这一段）。

报告里几个词的意思：
  点按      手指按下抬起、出了这个键的字。
  提前出字  这个键还按着，后按的键先抬了手，所以它先出字（按按下的先后）。没有这条规则的版本里，这些就是顺序打反的。
  紧接着删  点按出字以后下一个键就是删除键。
  按成>想按 删掉以后重打成了另一个键（或者删掉以后按了空格），或者选的纠错候选把这个键改成了另一个键。
            相邻的键才算「按偏」的迹象；不相邻的多半是改主意、拼错，不算。
  没出字    手指按在键上，抬起时什么都没输入，按原因分开。
落点格：每个键（含它管的键缝）横竖各分 10 格，左上角是第 0 行第 0 列。

哪些地方会不准：
  按键记在最近一次上屏的输入方式上，刚换方案、换布局后的头几下记在上一种里。
  「删掉重打」里有改主意的，所以分开看相邻和不相邻。没被删掉、没被改掉的不一定都按对了。
  双拼、九键没有「纠错候选改了哪个键」（纠错标记不是按键上的字母）。
  字母和 Shift、删除、数字行、候选栏之间的误触没有记；空格只记了落点和「出字后紧接着删」。
"""
import argparse
import html
import json
import os
import re
import statistics
import subprocess
import sys

PACKAGE = "com.cyletix.cyime"
RECEIVER = PACKAGE + "/com.kingzcheung.xime.service.DevLearningReceiver"
ACTION = PACKAGE + ".DEV_LEARNING"

# 和 app/src/main/java/com/kingzcheung/xime/stats/TouchStatsData.kt 里的下标一一对应。
OUTCOMES = ["（不用）", "点按", "换手（同一键两根手指）", "提前出字", "长按", "滑动出了动作", "滑到别的键松手",
            "没出字：手指被上层拿走（滑行、移光标）", "没出字：横着拖远了", "没出字：滑过阈值没有动作",
            "没出字：留给滑行的键上拖远了", "没出字：手指不见了或手势被取消"]
TAP, HANDOVER, EARLY = 1, 2, 3
TAPS = (TAP, HANDOVER, EARLY)
NONE = range(7, 12)
COUNTS = ["KEY_DOWNS", "CHAR_PRESSES", "DELETES", "REGRETS", "RETYPED", "RETYPED_SAME", "CORRECTED_PICKS",
          "CORRECTED_UNMATCHED", "SWAPS", "SWAPS_FROM_ORDER", "LOST_TAPS", "EARLY_SPACE", "EARLY_REPEAT", "DELETE_OTHER",
          "SPACE_REGRETS"]
KINDS = ["字母等", "空格", "删除", "其他键"]
UP_SIDES = ["还在键里", "出了左边", "出了右边", "出了上边", "出了下边"]
UP_OF_SIDE = {"左": 1, "右": 2, "上": 3, "下": 4}
UP_UNKNOWN = 5
ARRAYS = {"counts": len(COUNTS), "outcomes": len(OUTCOMES), "inversions": 16, "overlap": 4, "upside": 5,
          "gap": 25, "samegap": 21, "samegapnear": 21, "lagdown": 26, "lagup": 26}
GAP_STEP_MS, GAP_BINS, SAME_STEP_MS, LAG_STEP_MS = 25, 25, 10, 4
TRAVEL_STEP_DP, TRAVEL_BINS, TIME_STEP_MS, TIME_BINS = 4, 21, 25, 21
FATE_TAP, FATE_REGRET, FATE_CHANGED = 0, 1, 2
# 离上一次按下隔多久，报告里合成这几档（gap 的格，左闭右开）。
GAP_GROUPS = [("100 毫秒以内", 0, 4), ("100–150 毫秒", 4, 6), ("150–200 毫秒", 6, 8), ("200–300 毫秒", 8, 12),
              ("300–600 毫秒", 12, 24), ("更久，或键盘出来后的第一下", 24, 25)]
DROP_REASONS = {
    "key_not_ready": "按键时键盘还没准备好", "key_pending_commit": "上一次上屏还没被输入框接受",
    "touch_not_ready": "触摸时键盘还没准备好", "touch_before_ready": "键盘刚出来那一下之前的触摸",
    "t9_no_ticket": "九键按键时键盘还没准备好", "glide_pending_commit": "滑行时上一次上屏还没被接受",
    "glide_word_pending_commit": "滑行出词时上一次上屏还没被接受",
}
QWERTY = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
LETTERS = set("abcdefghijklmnopqrstuvwxyz")
LEFT_HAND = set("qwertasdfgzxcv")
ROW_NAMES = ["第一排", "第二排", "第三排"]
MIN_CELL = 5      # 一格里点按少于这么多次，不算这一格的「被删掉或改掉的比例」
SMALL_SAMPLE = 3000


# ---------------------------------------------------------------- 读文件

def unescape(name):
    return re.sub(r"%([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), name)


def sparse(text):
    out = {}
    for item in text.split(","):
        if ":" in item:
            i, n = item.split(":", 1)
            if i.isdigit() and n.isdigit() and int(n) > 0:
                out[int(i)] = out.get(int(i), 0) + int(n)
    return out


def new_board(board_id):
    b = {"id": board_id, "tt": {}, "drifttap": {}, "driftnone": {}, "fate": {}, "keys": {}, "retyped": {}, "corrected": {},
         "dropped": {}}
    for name, size in ARRAYS.items():
        b[name] = [0] * size
    return b


KEY_LAYERS = ("land", "regret", "changed", "lost")


def new_key():
    return {"size": [0, 0.0, 0.0, 0.0, 0.0], "land": {}, "regret": {}, "changed": {}, "lost": {}}


def parse(text):
    """touch_stats.txt → (说明行, {输入方式: 计数})。读不懂的行跳过。"""
    meta, boards, cur = {}, {}, None
    for line in text.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key in ("v", "grid", "last"):
            meta[key] = value
        elif key == "b":
            cur = boards.setdefault(value, new_board(value)) if value else None
        elif cur is None:
            continue
        elif key in ARRAYS:
            numbers = [int(x) if x.lstrip("-").isdigit() else 0 for x in value.split(",")]
            cur[key] = (numbers + [0] * ARRAYS[key])[:ARRAYS[key]]
        elif key in ("drifttap", "driftnone", "fate"):
            cur[key] = sparse(value)
        elif key.startswith("tt/") and key[3:].isdigit():
            cur["tt"][int(key[3:])] = sparse(value)
        elif key.startswith("dropped/") and value.isdigit():
            cur["dropped"][unescape(key[8:])] = int(value)
        elif key.startswith("retyped/"):
            cur["retyped"][unescape(key[8:])] = sparse(value)
        elif key.startswith("corrected/"):
            cur["corrected"][unescape(key[10:])] = sparse(value)
        elif key.startswith("k/") and "/" in key[2:]:
            name, field = key[2:].rsplit("/", 1)
            k = cur["keys"].setdefault(unescape(name), new_key())
            if field == "size":
                parts = value.split(",")
                try:
                    k["size"] = [int(parts[0])] + [float(x) for x in parts[1:5]]
                except (ValueError, IndexError):
                    pass
            elif field in KEY_LAYERS:
                k[field] = sparse(value)
    return meta, boards


def minus_sparse(new, old):
    return {i: n - old.get(i, 0) for i, n in new.items() if n - old.get(i, 0) > 0}


def minus(new, old):
    """两次导出之间新增的计数。计数只增不减；键的最窄、最宽取新的那次（分不开）。"""
    out = {}
    for board_id, b in new.items():
        o = old.get(board_id)
        if o is None:
            out[board_id] = b
            continue
        d = new_board(board_id)
        for name in ARRAYS:
            d[name] = [max(0, x - y) for x, y in zip(b[name], o[name])]
        for name in ("drifttap", "driftnone", "fate"):
            d[name] = minus_sparse(b[name], o[name])
        d["tt"] = {i: minus_sparse(s, o["tt"].get(i, {})) for i, s in b["tt"].items()}
        d["dropped"] = {r: n - o["dropped"].get(r, 0) for r, n in b["dropped"].items() if n - o["dropped"].get(r, 0) > 0}
        for name in ("retyped", "corrected"):
            d[name] = {p: minus_sparse(s, o[name].get(p, {})) for p, s in b[name].items()}
            d[name] = {p: s for p, s in d[name].items() if s}
        for key, k in b["keys"].items():
            ok = o["keys"].get(key, new_key())
            size = [k["size"][0] - ok["size"][0], k["size"][1] - ok["size"][1], k["size"][2] - ok["size"][2], k["size"][3], k["size"][4]]
            if size[0] <= 0:
                continue
            d["keys"][key] = {"size": size}
            for layer in KEY_LAYERS:
                d["keys"][key][layer] = minus_sparse(k[layer], ok[layer])
        out[board_id] = d
    return out


def read_props(path):
    out = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if "=" in line:
                    k, v = line.rstrip("\n").split("=", 1)
                    out[k] = v
    return out


# ---------------------------------------------------------------- 算

def total(s):
    return sum(s.values())


def pct(part, whole, digits=2):
    return "—" if whole <= 0 else f"{100.0 * part / whole:.{digits}f}%"


def percentile(hist, step, q):
    """直方图的分位数：落在哪一格，报这一格的上沿（最后一格是「更久」，报成 ≥ 它的下沿）。"""
    n = sum(hist)
    if n == 0:
        return None
    need, seen = q * n, 0
    for i, c in enumerate(hist):
        seen += c
        if seen >= need:
            return f"≥{i * step}" if i == len(hist) - 1 else f"<{(i + 1) * step}"
    return None


def key_size(k):
    n = k["size"][0]
    return (k["size"][1] / n, k["size"][2] / n) if n > 0 else None


def layout_of(board_id):
    parts = board_id.split("/")
    return parts[3] if len(parts) > 3 and parts[0] == "kb" else ""


def geometry(board):
    """26 键字母排的位置（dp）：每个键的格子左上角、宽高，和键帽中心在格子里的位置。别的布局返回 None。

    格子在一排里一个挨一个铺满。第二排两头的 a、l 管着两边的空，格子比别的键宽，键帽靠里；
    第三排两边是 Shift 和删除键（按一样宽算），字母居中。
    """
    if layout_of(board["id"]) != "qwerty":
        return None
    sizes = {c: key_size(board["keys"][c]) for row in QWERTY for c in row if c in board["keys"] and key_size(board["keys"][c])}
    if len(sizes) < 8:
        return None
    std_w = statistics.median(w for w, _ in sizes.values())
    std_h = statistics.median(h for _, h in sizes.values())
    geo, full = {}, None
    for r, row in enumerate(QWERTY):
        widths = [sizes.get(c, (std_w, std_h))[0] for c in row]
        if full is None:
            full = sum(widths)
        x = (full - sum(widths)) / 2.0
        for c, w in zip(row, widths):
            extra = w - std_w
            if extra > std_w * 0.2 and c == row[0]:
                cx = extra + std_w / 2.0
            elif extra > std_w * 0.2 and c == row[-1]:
                cx = std_w / 2.0
            else:
                cx = w / 2.0
            geo[c] = {"x": x, "y": r * std_h, "w": w, "h": std_h, "cx": cx, "cy": std_h / 2.0, "row": r}
            x += w
    return {"keys": geo, "std_w": std_w, "std_h": std_h, "width": full, "height": std_h * len(QWERTY)}


def neighbour(geo, typed, intended):
    """想按的键在按成的键的哪一边：左、右、上、下（斜着挨着的算上、下）；不相邻返回 None。空格在第三排字母的下边。"""
    k = geo["keys"]
    if typed not in k:
        return None
    if intended == "space":
        return "下" if k[typed]["row"] == len(QWERTY) - 1 else None
    if intended not in k:
        return None
    a, b = k[typed], k[intended]
    dx = ((b["x"] + b["cx"]) - (a["x"] + a["cx"])) / geo["std_w"]
    dy = ((b["y"] + b["cy"]) - (a["y"] + a["cy"])) / geo["std_h"]
    if abs(dy) < 0.5 and 0.5 < abs(dx) <= 1.5:
        return "右" if dx > 0 else "左"
    if 0.5 <= abs(dy) <= 1.5 and abs(dx) <= 1.25:
        return "下" if dy > 0 else "上"
    return None


def cell_xy(cell, grid):
    return cell % grid, cell // grid


def split_pair(indexed, grid):
    """「按成>想按」的一行：落点下标 → ({落点格: 次数}, [抬手时手指在键的哪一边 0..5 各几次])。"""
    cells, ups = {}, [0] * (UP_UNKNOWN + 1)
    for index, n in indexed.items():
        cell, up = index % (grid * grid), min(UP_UNKNOWN, index // (grid * grid))
        cells[cell] = cells.get(cell, 0) + n
        ups[up] += n
    return cells, ups


def toward(cells, side, grid, band):
    """落点里靠 [side] 那一边 [band] 格以内的次数。"""
    n = 0
    for cell, c in cells.items():
        col, row = cell_xy(cell, grid)
        if (side == "左" and col < band) or (side == "右" and col >= grid - band) or \
                (side == "上" and row < band) or (side == "下" and row >= grid - band):
            n += c
    return n


def mean_point(cells, grid):
    n = total(cells)
    if n == 0:
        return None
    x = sum(((cell % grid) + 0.5) / grid * c for cell, c in cells.items()) / n
    y = sum(((cell // grid) + 0.5) / grid * c for cell, c in cells.items()) / n
    return x, y


def pairs_table(board, geo, grid):
    """「按成>想按」的键对：删掉重打的和纠错候选改的，各自多少次、相邻不相邻、落点靠不靠想按的那一边、抬手时到没到那一边。"""
    rows = {}
    for source in ("retyped", "corrected"):
        for pair, indexed in board[source].items():
            typed, _, intended = pair.partition(">")
            cells, ups = split_pair(indexed, grid)
            row = rows.setdefault(pair, {"typed": typed, "intended": intended, "retyped": 0, "corrected": 0, "cells": {},
                                         "ups": [0] * (UP_UNKNOWN + 1)})
            row[source] += total(cells)
            for cell, c in cells.items():
                row["cells"][cell] = row["cells"].get(cell, 0) + c
            row["ups"] = [a + b for a, b in zip(row["ups"], ups)]
    out = []
    for pair, row in rows.items():
        n = row["retyped"] + row["corrected"]
        side = neighbour(geo, row["typed"], row["intended"]) if geo and row["intended"] else None
        row.update(n=n, side=side, pair=pair, mean=mean_point(row["cells"], grid))
        row["toward_half"] = toward(row["cells"], side, grid, grid // 2) if side else None
        row["toward_edge"] = toward(row["cells"], side, grid, max(1, grid * 3 // 10)) if side else None
        row["up_toward"] = row["ups"][UP_OF_SIDE[side]] if side else None
        out.append(row)
    out.sort(key=lambda r: (-r["n"], r["pair"]))
    return out


def fixed_cells(board, grid):
    """每个键上，选纠错候选改掉的那些点按落在哪：{键: {落点格: 次数}}。"""
    out = {}
    for pair, indexed in board["corrected"].items():
        typed = pair.partition(">")[0]
        cells, _ = split_pair(indexed, grid)
        target = out.setdefault(typed, {})
        for cell, c in cells.items():
            target[cell] = target.get(cell, 0) + c
    return out


def taken_back(key, fixed, cell):
    """这一格里事后被删掉或改掉的点按：紧接着删的、后来删掉重打成别的键的、选纠错候选改掉的（三样不重复）。"""
    return key["regret"].get(cell, 0) + key["changed"].get(cell, 0) + fixed.get(cell, 0)


def key_at(geo, row, x):
    for name, k in geo["keys"].items():
        if k["row"] == row and k["x"] <= x < k["x"] + k["w"]:
            return name
    return None


def boundary_moves(board, geo, pairs, grid):
    """
    如果把字母键之间的分界挪一点，会怎样：救回多少次按偏（落在挪过去的那一条里、事后改成了隔壁那个键的），
    误伤多少次（落在那一条里、出了字、事后没被删掉也没被改掉的）。挪多少按键宽、键高的十分之几算。
    是估算：没被删掉的不一定都按对了，被改的也不一定都是按偏；落点只精确到一格。
    """
    k = geo["keys"]
    fixed = fixed_cells(board, grid)
    wrong = {}   # (按成的键, 格) → {想按的键: 次数}
    for row in pairs:
        if row["side"] is None or row["intended"] not in k:
            continue
        for cell, c in row["cells"].items():
            bucket = wrong.setdefault((row["typed"], cell), {})
            bucket[row["intended"]] = bucket.get(row["intended"], 0) + c

    def one(names, in_band, target_of):
        rescued = broken = 0
        for name in names:
            key = board["keys"].get(name)
            if key is None or name not in k:
                continue
            g = k[name]
            for cell in key["land"]:
                col, r = cell_xy(cell, grid)
                if not in_band(col, r):
                    continue
                target = target_of(g, col)
                if target is None:
                    continue       # 那一边不是字母键
                rescued += wrong.get((name, cell), {}).get(target, 0)
                broken += max(0, key["land"][cell] - taken_back(key, fixed.get(name, {}), cell))
        return rescued, broken

    out = []
    for upper in range(len(QWERTY) - 1):
        lower = upper + 1
        label = f"{ROW_NAMES[upper]}和{ROW_NAMES[lower]}之间"
        for s in (1, 2, 3):
            # 往下挪：下面一排每个键最上面的一条归上面一排。
            rescued, broken = one(QWERTY[lower], lambda col, r: r < s,
                                  lambda g, col: key_at(geo, upper, g["x"] + (col + 0.5) / grid * g["w"]))
            out.append({"boundary": label, "move": "往下挪", "tenths": s, "rescued": rescued, "broken": broken})
        for s in (1, 2, 3):
            rescued, broken = one(QWERTY[upper], lambda col, r: r >= grid - s,
                                  lambda g, col: key_at(geo, lower, g["x"] + (col + 0.5) / grid * g["w"]))
            out.append({"boundary": label, "move": "往上挪", "tenths": s, "rescued": rescued, "broken": broken})
    for r, row in enumerate(QWERTY):
        label = f"{ROW_NAMES[r]}左右相邻的键之间"
        for s in (1, 2, 3):
            # 往右挪：每个键最左边的一条归它左边的键。
            rescued, broken = one(row, lambda col, rr: col < s, lambda g, col: key_at(geo, g["row"], g["x"] - 0.01))
            out.append({"boundary": label, "move": "往右挪", "tenths": s, "rescued": rescued, "broken": broken})
        for s in (1, 2, 3):
            rescued, broken = one(row, lambda col, rr: col >= grid - s, lambda g, col: key_at(geo, g["row"], g["x"] + g["w"] + 0.01))
            out.append({"boundary": label, "move": "往左挪", "tenths": s, "rescued": rescued, "broken": broken})
    for m in out:
        m["net"] = m["rescued"] - m["broken"]
    return out


def offsets(board, geo, grid, names):
    """这些键上点按的平均落点离键帽中心多远（dp；往右、往下为正）。"""
    n = sx = sy = 0.0
    for c in names:
        key, g = board["keys"].get(c), geo["keys"].get(c)
        if key is None or g is None:
            continue
        for cell, count in key["land"].items():
            col, r = cell_xy(cell, grid)
            sx += ((col + 0.5) / grid * g["w"] - g["cx"]) * count
            sy += ((r + 0.5) / grid * g["h"] - g["cy"]) * count
            n += count
    return {"taps": int(n), "dx": sx / n if n else None, "dy": sy / n if n else None}


def bias(board, geo, grid):
    out = {"rows": [dict(offsets(board, geo, grid, row), name=f"{ROW_NAMES[r]} {row}") for r, row in enumerate(QWERTY)],
           "hands": [dict(offsets(board, geo, grid, [c for row in QWERTY for c in row if c in LEFT_HAND]), name="左半边 qwert asdfg zxcv"),
                     dict(offsets(board, geo, grid, [c for row in QWERTY for c in row if c not in LEFT_HAND]), name="右半边 yuiop hjkl bnm")],
           "all": dict(offsets(board, geo, grid, [c for row in QWERTY for c in row]), name="全部字母"),
           "keys": {c: offsets(board, geo, grid, [c]) for row in QWERTY for c in row}}
    return out


def edge_share(board, grid):
    """字母键上落在外圈一格（10%）里的点按，和落在里面的，各有多少紧接着被删掉。"""
    edge = [0, 0]
    inner = [0, 0]
    for name, key in board["keys"].items():
        if name not in LETTERS:
            continue
        for cell, c in key["land"].items():
            col, r = cell_xy(cell, grid)
            bucket = edge if col in (0, grid - 1) or r in (0, grid - 1) else inner
            bucket[0] += c
            bucket[1] += key["regret"].get(cell, 0)
    return {"edge_taps": edge[0], "edge_regrets": edge[1], "inner_taps": inner[0], "inner_regrets": inner[1]}


def fate(board):
    """字母键点按按「交叠没交叠 × 离上一次按下隔多久」分开：各有多少次、多少紧接着被删、多少事后改成了别的键。"""
    def count(what, overlapped, lo, hi):
        return sum(board["fate"].get((what * 2 + overlapped) * GAP_BINS + i, 0) for i in range(lo, hi))
    rows = []
    for overlapped in (0, 1):
        for label, lo, hi in GAP_GROUPS + [("合计", 0, GAP_BINS)]:
            rows.append({"overlapped": bool(overlapped), "gap": label, "taps": count(FATE_TAP, overlapped, lo, hi),
                         "regrets": count(FATE_REGRET, overlapped, lo, hi), "changed": count(FATE_CHANGED, overlapped, lo, hi)})
    return rows


def space_rows(board, grid):
    """空格键的落点按高度分成 [grid] 条（最上面一条是第 0 条）：每条几次，其中出字后紧接着删的几次。"""
    key = board["keys"].get("space")
    if key is None:
        return None
    rows = [[0, 0] for _ in range(grid)]
    for cell, c in key["land"].items():
        rows[cell // grid][0] += c
    for cell, c in key["regret"].items():
        rows[cell // grid][1] += c
    return rows


def travel(board, outcomes, q):
    """这些结果的按下里，手指从按下到抬起挪了多远的分位数（dp，一格 4dp）。"""
    hist = [0] * TRAVEL_BINS
    for o in outcomes:
        for index, n in board["tt"].get(o, {}).items():
            hist[min(TRAVEL_BINS - 1, index // TIME_BINS)] += n
    return percentile(hist, TRAVEL_STEP_DP, q)


def lost_shape(board):
    """没出字的按下：按了多久 × 手指挪了多远。"""
    times = [("100 毫秒以内", 0, 4), ("100–200 毫秒", 4, 8), ("200–300 毫秒", 8, 12), ("更久", 12, TIME_BINS)]
    moves = [("不到 4dp", 0, 1), ("4–12dp", 1, 3), ("12–20dp", 3, 5), ("20–40dp", 5, 10), ("更远", 10, TRAVEL_BINS)]
    table = [[0] * len(moves) for _ in times]
    for o in NONE:
        for index, n in board["tt"].get(o, {}).items():
            t, m = index % TIME_BINS, index // TIME_BINS
            ti = next(i for i, (_, lo, hi) in enumerate(times) if lo <= t < hi)
            mi = next(i for i, (_, lo, hi) in enumerate(moves) if lo <= min(m, TRAVEL_BINS - 1) < hi)
            table[ti][mi] += n
    return {"times": [t[0] for t in times], "moves": [m[0] for m in moves], "table": table}


def summarize(board, grid):
    c = dict(zip(COUNTS, board["counts"]))
    o = board["outcomes"]
    taps = sum(o[i] for i in TAPS)
    lost = sum(o[i] for i in NONE)
    presses = sum(o)
    letter_taps = sum(total(k["land"]) for name, k in board["keys"].items() if name in LETTERS)
    letter_lost = sum(total(k["lost"]) for name, k in board["keys"].items() if name in LETTERS)
    geo = geometry(board)
    pairs = pairs_table(board, geo, grid)
    adjacent = [p for p in pairs if p["side"]]
    up_out = sum(board["upside"][1:5])
    summary = {
        "id": board["id"], "key_downs": c["KEY_DOWNS"], "presses": presses, "taps": taps,
        "letter_taps": letter_taps, "letter_lost": letter_lost,
        "early": o[EARLY], "early_space": c["EARLY_SPACE"], "early_repeat": c["EARLY_REPEAT"],
        "lost": lost, "lost_like_taps": c["LOST_TAPS"], "lost_by_reason": {OUTCOMES[i]: o[i] for i in NONE if o[i]},
        "long": o[4], "swipe": o[5], "slide": o[6],
        "inversions": {f"{KINDS[i // 4]}排在后按的{KINDS[i % 4]}后面": n for i, n in enumerate(board["inversions"]) if n},
        "deletes": c["DELETES"], "regrets": c["REGRETS"], "retyped": c["RETYPED"], "retyped_same": c["RETYPED_SAME"],
        "delete_other": c["DELETE_OTHER"], "space_regrets": c["SPACE_REGRETS"],
        "corrected_picks": c["CORRECTED_PICKS"], "corrected_unmatched": c["CORRECTED_UNMATCHED"],
        "swaps": c["SWAPS"], "swaps_from_order": c["SWAPS_FROM_ORDER"],
        "adjacent_wrong": sum(p["n"] for p in adjacent), "adjacent_retyped": sum(p["retyped"] for p in adjacent),
        "adjacent_corrected": sum(p["corrected"] for p in adjacent),
        "adjacent_toward_edge": sum(p["toward_edge"] for p in adjacent),
        "adjacent_up_toward": sum(p["up_toward"] for p in adjacent),
        "other_wrong": sum(p["n"] for p in pairs if not p["side"] and p["intended"]),
        "extra_letters": sum(p["n"] for p in pairs if not p["intended"]),
        "pairs": {p["pair"]: {"n": p["n"], "retyped": p["retyped"], "corrected": p["corrected"], "side": p["side"],
                              "toward_half": p["toward_half"], "toward_edge": p["toward_edge"], "up_toward": p["up_toward"]}
                  for p in pairs},
        "overlap": board["overlap"], "up_side": dict(zip(UP_SIDES, board["upside"])), "up_out": up_out,
        "dropped": board["dropped"], "edge": edge_share(board, grid), "fate": fate(board),
        "space": space_rows(board, grid), "lost_shape": lost_shape(board),
        "tap_travel_dp": {q: travel(board, TAPS, v) for q, v in (("p50", .5), ("p90", .9), ("p99", .99))},
        "lag_down_ms": {q: percentile(board["lagdown"], LAG_STEP_MS, v) for q, v in (("p50", .5), ("p90", .9), ("p99", .99))},
        "lag_up_ms": {q: percentile(board["lagup"], LAG_STEP_MS, v) for q, v in (("p50", .5), ("p90", .9), ("p99", .99))},
        "gap_ms": {q: percentile(board["gap"], GAP_STEP_MS, v) for q, v in (("p10", .1), ("p50", .5))},
        "same_key_within_50ms": sum(board["samegap"][:5]), "same_key_within_50ms_near": sum(board["samegapnear"][:5]),
        "same_key_within_100ms": sum(board["samegap"][:10]), "same_key_within_100ms_near": sum(board["samegapnear"][:10]),
    }
    if geo:
        summary["bias"] = bias(board, geo, grid)
        summary["boundary_moves"] = boundary_moves(board, geo, pairs, grid)
        summary["key_cell_dp"] = [geo["std_w"], geo["std_h"]]
        bottom = QWERTY[-1]
        band = max(1, grid * 3 // 10)
        summary["bottom_row_low"] = {
            "taps": sum(toward(board["keys"][k]["land"], "下", grid, band) for k in bottom if k in board["keys"]),
            "regrets": sum(toward(board["keys"][k]["regret"], "下", grid, band) for k in bottom if k in board["keys"]),
            "then_space": sum(toward(p["cells"], "下", grid, band) for p in pairs if p["intended"] == "space" and p["typed"] in bottom),
        }
    return summary, geo, pairs


# ---------------------------------------------------------------- 画

def esc(text):
    return html.escape(str(text))


def table(header, rows, right=()):
    out = ["<table><tr>" + "".join(f"<th>{esc(h)}</th>" for h in header) + "</tr>"]
    for row in rows:
        out.append("<tr>" + "".join(f"<td class='{'r' if i in right else ''}'>{esc(v)}</td>" for i, v in enumerate(row)) + "</tr>")
    out.append("</table>")
    return "".join(out)


def heat(board, geo, grid, layer, colour, title):
    """字母排的一张格子图。layer: land（每个键自己和自己比）、lost（全盘比）、back（被删掉或改掉的比例）。"""
    pad = 6
    scale = min(3.2, 1040.0 / geo["width"])     # 竖屏一个 dp 画 2.9 像素左右；横屏键盘宽，缩到一页放得下
    width, height = geo["width"] * scale + pad * 2, geo["height"] * scale + pad * 2
    parts = [f"<figure><figcaption>{esc(title)}</figcaption>",
             f"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 {width:.0f} {height:.0f}' width='{width:.0f}' height='{height:.0f}'>",
             f"<rect width='{width:.0f}' height='{height:.0f}' fill='#fff'/>"]
    fixed = fixed_cells(board, grid)
    labels = []     # 键名最后画，免得被旁边键的格子和圈盖住
    board_max = max([n for name in geo["keys"] for n in board["keys"].get(name, new_key())["lost"].values()] or [1])
    for name, g in geo["keys"].items():
        key = board["keys"].get(name, new_key())
        x0, y0 = pad + g["x"] * scale, pad + g["y"] * scale
        cw, ch = g["w"] * scale / grid, g["h"] * scale / grid
        key_max = max(key["land"].values() or [1])
        for cell in set(key["land"]) | set(key["lost"]):
            col, r = cell_xy(cell, grid)
            if layer == "land":
                v = key["land"].get(cell, 0) / key_max
            elif layer == "lost":
                v = key["lost"].get(cell, 0) / board_max
            else:
                n = key["land"].get(cell, 0)
                back = taken_back(key, fixed.get(name, {}), cell)
                v = min(1.0, back / n / 0.3) if n >= MIN_CELL else 0.0
                if back > 0 and n < MIN_CELL:
                    # 点按太少算不出比例的格子（多半在键的边上）：画一个圈，免得正好把按偏的地方漏掉。
                    parts.append(f"<circle cx='{x0 + (col + 0.5) * cw:.1f}' cy='{y0 + (r + 0.5) * ch:.1f}' "
                                 f"r='{min(cw, ch) * 0.36:.1f}' fill='none' stroke='{colour}' "
                                 f"stroke-width='{1.0 if back == 1 else 1.8}'/>")
            if v > 0:
                parts.append(f"<rect x='{x0 + col * cw:.1f}' y='{y0 + r * ch:.1f}' width='{cw:.1f}' height='{ch:.1f}' "
                             f"fill='{colour}' fill-opacity='{0.08 + 0.92 * v:.2f}'/>")
        parts.append(f"<rect x='{x0:.1f}' y='{y0:.1f}' width='{g['w'] * scale:.1f}' height='{g['h'] * scale:.1f}' fill='none' stroke='#999' stroke-width='0.6'/>")
        cx, cy = x0 + g["cx"] * scale, y0 + g["cy"] * scale
        parts.append(f"<path d='M{cx - 4:.1f} {cy:.1f}H{cx + 4:.1f}M{cx:.1f} {cy - 4:.1f}V{cy + 4:.1f}' stroke='#000' stroke-width='0.8'/>")
        mean = mean_point(key["land"], grid) if layer == "land" else None
        if mean:
            parts.append(f"<circle cx='{x0 + mean[0] * g['w'] * scale:.1f}' cy='{y0 + mean[1] * g['h'] * scale:.1f}' r='2.6' fill='#d00'/>")
        labels.append(f"<text x='{x0 + 3:.1f}' y='{y0 + 11:.1f}' font-size='10' fill='#333' stroke='#fff' stroke-width='2.5' paint-order='stroke'>"
                      f"{esc(name)} {total(key['lost']) if layer == 'lost' else total(key['land'])}</text>")
    parts.extend(labels)
    parts.append("</svg></figure>")
    return "".join(parts)


def board_section(board, grid, mm_per_dp):
    s, geo, pairs = summarize(board, grid)
    presses, taps, letters = s["presses"], s["taps"], s["letter_taps"]
    out = [f"<h2>{esc(board['id'])}</h2>"]
    if presses < SMALL_SAMPLE:
        out.append(f"<p class='warn'>这种输入方式只记了 {presses} 次有落点的按下，比例只能粗看。</p>")
    wrong = s["adjacent_wrong"]
    inversions = sum(board["inversions"])
    out.append("<h3>各占多少</h3>" + table(["", "次数", "比例", "说明"], [
        ["有落点的键按下（字母、数字、标点）", presses, "", f"所有键合计按下 {s['key_downs']} 次"],
        ["其中点按出字", taps, pct(taps, presses), f"其中字母键 {letters} 次"],
        ["按偏的迹象：事后改成了相邻的键", wrong, f"占字母点按 {pct(wrong, letters)}",
         f"删掉重打 {s['adjacent_retyped']} 次，选纠错候选 {s['adjacent_corrected']} 次；"
         f"落点在靠想按那边 30% 里的 {s['adjacent_toward_edge']} 次"],
        ["点按后紧接着删掉", s["regrets"], f"占字母点按 {pct(s['regrets'], letters)}", "原因不分：按偏、拼错、改主意都在里面"],
        ["没出字（漏键）", s["lost"], f"占按下 {pct(s['lost'], presses)}",
         f"其中时间短、手指几乎没动、像点按的 {s['lost_like_taps']} 次；字母键上 {s['letter_lost']} 次"],
        ["提前出字", s["early"], f"占按下 {pct(s['early'], presses)}", "按着没抬、后按的键先抬手；没有按下先后这条规则时，这些会顺序打反"],
        ["现在出字顺序仍然反了", inversions, f"占按下 {pct(inversions, presses)}", "；".join(f"{k} {v} 次" for k, v in s["inversions"].items())],
        ["长按 / 滑动 / 滑到别的键", f"{s['long']} / {s['swipe']} / {s['slide']}", "", ""],
    ], right=(1, 2)))
    if s["lost_by_reason"]:
        out.append("<h3>没出字的原因</h3>" + table(["原因", "次数", "占按下"],
                   [[k, v, pct(v, presses)] for k, v in sorted(s["lost_by_reason"].items(), key=lambda kv: -kv[1])], right=(1, 2)))
        shape = s["lost_shape"]
        out.append(table(["按了多久 ＼ 手指挪了多远"] + shape["moves"],
                         [[t] + row for t, row in zip(shape["times"], shape["table"])], right=tuple(range(1, 6))))
    if s["dropped"]:
        out.append("<h3>输入法没接的输入</h3>" + table(["原因", "次数"],
                   [[DROP_REASONS.get(k, k), v] for k, v in sorted(s["dropped"].items(), key=lambda kv: -kv[1])], right=(1,)))
    out.append("<h3>删除和重打</h3>" + table(["", "次数", "比例"], [
        ["删除键按下", s["deletes"], f"占所有键 {pct(s['deletes'], s['key_downs'])}"],
        ["点按后紧接着删掉", s["regrets"], f"占字母点按 {pct(s['regrets'], letters)}"],
        ["删掉后重打成别的键（含删掉后按空格）", s["retyped"], f"其中相邻键 {s['adjacent_retyped']} 次"],
        ["删掉后重打的还是同一个键", s["retyped_same"], ""],
        ["删除键这一下不是照常删一个字（拖动、连删）", s["delete_other"], "之后不对「删掉重打」"],
        ["选了纠错候选（对上了按键）", s["corrected_picks"], f"对不上的 {s['corrected_unmatched']} 次没有记落点"],
        ["纠错候选里的颠倒", s["swaps"], f"其中两下出字顺序和按下顺序本来就反的 {s['swaps_from_order']} 次"],
        ["空格出字后紧接着删掉", s["space_regrets"], ""],
    ], right=(1,)))
    e = s["edge"]
    out.append("<h3>字母键：落在键的边上和落在中间</h3>" + table(["", "点按", "紧接着被删掉", "比例"], [
        ["外圈一格（键宽、键高的 10%）", e["edge_taps"], e["edge_regrets"], pct(e["edge_regrets"], e["edge_taps"])],
        ["里面", e["inner_taps"], e["inner_regrets"], pct(e["inner_regrets"], e["inner_taps"])],
    ], right=(1, 2, 3)))
    rows = []
    for overlapped in (False, True):
        for f in s["fate"]:
            if f["overlapped"] == overlapped and f["taps"]:
                rows.append(["别的键还按着" if overlapped else "只有这一根手指", f["gap"], f["taps"], f["regrets"], pct(f["regrets"], f["taps"]),
                             f["changed"], pct(f["changed"], f["taps"])])
    if rows:
        out.append("<h3>按错是不是集中在打得快、两指交叠的时候</h3>" + table(
            ["按着的时候", "离上一次按下", "字母点按", "紧接着被删", "比例", "事后改成别的键", "比例"], rows, right=(2, 3, 4, 5, 6)))
    if pairs:
        rows = []
        for p in pairs[:40]:
            mean = p["mean"]
            known_up = p["n"] - p["ups"][UP_UNKNOWN]
            rows.append([p["typed"], {"": "（多打的）", "space": "空格"}.get(p["intended"], p["intended"]), p["n"], p["retyped"], p["corrected"],
                         f"想按的在{p['side']}边" if p["side"] else ("不相邻" if p["intended"] else ""),
                         pct(p["toward_half"], p["n"], 0) if p["side"] else "", pct(p["toward_edge"], p["n"], 0) if p["side"] else "",
                         f"{p['up_toward']} / {known_up}" if p["side"] else "",
                         f"横 {mean[0] * 100:.0f}% 竖 {mean[1] * 100:.0f}%" if mean else ""])
        out.append("<h3>按成 &gt; 想按（最多的 40 对）</h3>" + table(
            ["按成", "想按", "次数", "删掉重打", "纠错候选", "位置关系", "落点在靠想按那半边", "落点在靠想按那边的 30% 里",
             "抬手时已经到了想按那边", "平均落点"], rows, right=(2, 3, 4, 6, 7, 8)))
    up = board["upside"]
    out.append("<h3>按下的点和抬手的点</h3>" + table(["点按抬手时手指在哪", "次数", "比例"],
               [[n, c, pct(c, sum(up))] for n, c in zip(UP_SIDES, up)], right=(1, 2)) +
               f"<p>现在按下的那个键算数。抬手时已经出了键的点按有 {s['up_out']} 次：其中事后改成了那一边相邻键的 {s['adjacent_up_toward']} 次"
               f"（改成按抬手点算能救回），其余的改成按抬手点算会变成别的键。点按时手指挪动：一半 {s['tap_travel_dp']['p50']}dp，"
               f"九成 {s['tap_travel_dp']['p90']}dp，百分之九十九 {s['tap_travel_dp']['p99']}dp。</p>")
    if geo:
        mm = (lambda dp: "—" if dp is None else f"{dp:+.1f}dp（{dp * mm_per_dp:+.2f}mm）") if mm_per_dp else \
             (lambda dp: "—" if dp is None else f"{dp:+.1f}dp")
        b = s["bias"]
        out.append("<h3>落点离键帽中心</h3>" + table(["", "点按", "横向（正是偏右）", "纵向（正是偏下）"],
                   [[x["name"], x["taps"], mm(x["dx"]), mm(x["dy"])] for x in b["rows"] + b["hands"] + [b["all"]]], right=(1,)))
        out.append(table(["键", "点按", "横向", "纵向"],
                         [[c, k["taps"], mm(k["dx"]), mm(k["dy"])] for c, k in b["keys"].items() if k["taps"]], right=(1,)))
        out.append(f"<p>键的格子（含键缝）：宽 {geo['std_w']:.1f}dp，高 {geo['std_h']:.1f}dp。</p>")
        out.append(heat(board, geo, grid, "land", "#1565c0", "点按的落点（每个键自己比；十字是键帽中心，红点是平均落点；键名后面是次数）"))
        out.append(heat(board, geo, grid, "back", "#c62828",
                        f"事后被删掉或改掉的点按落在哪（紧接着删、删掉重打成别的键、选纠错候选改掉）。"
                        f"一格里点按满 {MIN_CELL} 次的按比例上色，最深是 30% 以上；不满 {MIN_CELL} 次的画圈，粗圈是两次以上"))
        out.append(heat(board, geo, grid, "lost", "#ef6c00", "没出字的按下落在哪（键名后面是次数）"))
        out.append("<h3>如果挪字母键之间的分界（估算）</h3><p>救回：落在挪过去的那一条里、事后改成了隔壁那个键的。"
                   "误伤：落在那一条里、出了字、事后没被删掉也没被改掉的。挪多少按键宽、键高的十分之几算。</p>" +
                   table(["哪条分界", "怎么挪", "挪多少", "救回", "误伤", "净"],
                         [[m["boundary"], m["move"], f"{m['tenths'] * 10}%", m["rescued"], m["broken"], f"{m['net']:+d}"]
                          for m in s["boundary_moves"]], right=(3, 4, 5)))
    if s["space"]:
        rows = [[f"第 {i + 1} 条" + ("（最上）" if i == 0 else "（最下）" if i == grid - 1 else ""), n, r, pct(r, n)]
                for i, (n, r) in enumerate(s["space"])]
        text = ""
        if geo:
            low = s["bottom_row_low"]
            text = (f"<p>第三排字母落在最下面 30% 的点按 {low['taps']} 次，其中紧接着被删的 {low['regrets']} 次，"
                    f"删掉以后按了空格的 {low['then_space']} 次。</p>")
        out.append("<h3>空格和第三排字母之间</h3>" + table(["空格键上的落点（从上到下分 10 条）", "出字", "紧接着被删", "比例"], rows, right=(1, 2, 3)) + text)
    overlap = board["overlap"]
    out.append("<h3>几根手指交叠</h3>" + table(["一个键按下时别的键还按着几个", "次数", "比例"],
               [[f"{i}{' 个以上' if i == 3 else ' 个'}", n, pct(n, sum(overlap))] for i, n in enumerate(overlap)], right=(1, 2)) +
               f"<p>空格提前出字 {s['early_space']} 次，删除键这类提前出字 {s['early_repeat']} 次。</p>")
    out.append("<h3>同一个键很快又按下</h3>" + table(["抬手到再按下", "次数", "其中两下落点不到 12dp"], [
        ["50 毫秒以内", s["same_key_within_50ms"], s["same_key_within_50ms_near"]],
        ["100 毫秒以内", s["same_key_within_100ms"], s["same_key_within_100ms_near"]],
        ["全部", sum(board["samegap"]), sum(board["samegapnear"])]], right=(1, 2)) +
        "<p>正常的双击（小鹤 uu、英文 ll）也在里面；间隔极短、落点又几乎重合的才像一下按成了两下。</p>")
    out.append("<h3>时间</h3>" + table(["", "一半", "九成", "百分之九十九"], [
        ["手指按下到输入法处理（毫秒）", s["lag_down_ms"]["p50"], s["lag_down_ms"]["p90"], s["lag_down_ms"]["p99"]],
        ["手指抬起到输入法处理（毫秒）", s["lag_up_ms"]["p50"], s["lag_up_ms"]["p90"], s["lag_up_ms"]["p99"]]]) +
        f"<p>相邻两次按下的间隔：最快的一成 {s['gap_ms']['p10']} 毫秒，一半 {s['gap_ms']['p50']} 毫秒。</p>")
    return "".join(out), s


def typing_stats_section(props):
    """导出里带的「打字统计」（设置页那份）：纠错候选改过的错键、漏键、多键、颠倒各多少次。"""
    if not props:
        return "", {}
    total_row = [int(x) if x.isdigit() else 0 for x in props.get("total", "").split(",")]
    fixes = [int(x) if x.isdigit() else 0 for x in props.get("fixes", "").split(",")]
    total_row += [0] * (11 - len(total_row))
    fixes += [0] * (4 - len(fixes))
    pairs = sorted(sparse(props.get("pairs", "")).items(), key=lambda kv: -kv[1])[:20]
    chars, keys, deletes, picks, corrections = total_row[0], total_row[2], total_row[3], total_row[4], total_row[7]
    data = {"chars": chars, "keys": keys, "deletes": deletes, "picks": picks, "corrected_picks": corrections,
            "fixes": dict(zip(["错键", "漏键", "多键", "颠倒"], fixes))}
    body = "<h2>打字统计页那份（装包以来的累计）</h2>" + table(["", "次数", "比例"], [
        ["上屏的字", chars, ""], ["屏幕键盘按键", keys, ""], ["其中删除键", deletes, pct(deletes, keys)],
        ["选候选", picks, ""], ["其中选了纠错候选", corrections, pct(corrections, picks)],
        ["错键", fixes[0], ""], ["漏键", fixes[1], ""], ["多键", fixes[2], ""], ["颠倒", fixes[3], ""]], right=(1, 2))
    if pairs:
        body += table(["按成", "想按", "次数"], [[chr(97 + i // 26), chr(97 + i % 26) if i // 26 != i % 26 else "（多打的）", n] for i, n in pairs], right=(2,))
    return body, data


CSS = """body{font:14px/1.6 -apple-system,'Microsoft YaHei',sans-serif;margin:24px;color:#222;max-width:1100px}
h1{font-size:22px}h2{font-size:18px;margin-top:36px;border-bottom:1px solid #ccc}h3{font-size:15px;margin:20px 0 6px}
table{border-collapse:collapse;margin:6px 0}th,td{border:1px solid #ddd;padding:3px 9px;text-align:left}th{background:#f5f5f5}
td.r{text-align:right;font-variant-numeric:tabular-nums}.warn{color:#b71c1c}figure{margin:12px 0}figcaption{color:#555;margin-bottom:4px}"""


def build(directory, base, out):
    with open(os.path.join(directory, "touch_stats.txt"), encoding="utf-8") as f:
        meta, boards = parse(f.read())
    grid = int(meta.get("grid", "10") or 10)
    since = ""
    if base:
        with open(os.path.join(base, "touch_stats.txt"), encoding="utf-8") as f:
            base_meta, base_boards = parse(f.read())
        if base_meta.get("grid", meta.get("grid")) != meta.get("grid"):
            sys.exit("两次导出的落点格数不一样，不能相减")
        boards = minus(boards, base_boards)
        since = f"只算 {os.path.basename(os.path.normpath(base))} 之后新增的。"
    device = read_props(os.path.join(directory, "device.txt"))
    try:
        mm_per_dp = float(device["density"]) * 25.4 / ((float(device["xdpi"]) + float(device["ydpi"])) / 2.0)
    except (KeyError, ValueError, ZeroDivisionError):
        mm_per_dp = None
    ordered = sorted((b for b in boards.values() if sum(b["outcomes"]) or sum(b["counts"]) or b["dropped"]),
                     key=lambda b: (-sum(b["outcomes"]), b["id"]))
    sections, summaries = [], []
    for board in ordered:
        body, summary = board_section(board, grid, mm_per_dp)
        sections.append(body)
        summaries.append(summary)
    typing_body, typing = typing_stats_section(read_props(os.path.join(directory, "typing_stats.txt")) if not base else {})
    head = (f"<h1>按键落点统计</h1><p>设备 {esc(device.get('model', '?'))}，版本 {esc(device.get('version', '?'))}"
            f"（{esc(device.get('version_code', '?'))}）。{esc(since)}只有计数，没有打过的任何文字。"
            f"横屏的单独一段（名字后面带 /land）；名字是 ? 的一段是还没上过屏时按的。</p>")
    if not ordered:
        head += "<p class='warn'>还没有记到按键（要「装到我的设备」的包，「打字统计」开着，不在无痕里）。</p>"
    page = f"<!doctype html><html lang='zh'><meta charset='utf-8'><title>按键落点统计</title><style>{CSS}</style>{head}{''.join(sections)}{typing_body}</html>"
    with open(out, "w", encoding="utf-8") as f:
        f.write(page)
    with open(os.path.splitext(out)[0] + ".json", "w", encoding="utf-8") as f:
        json.dump({"device": device, "base": base, "boards": summaries, "typing_stats": typing}, f, ensure_ascii=False, indent=1)
    return summaries


def pull(serial, target):
    """让输入法导出一次并拉回电脑，返回电脑上的目录。"""
    adb = ["adb"] + (["-s", serial] if serial else [])
    done = subprocess.run(adb + ["shell", "am", "broadcast", "-n", RECEIVER, "-a", ACTION, "--es", "op", "touch_stats"],
                          capture_output=True, text=True, timeout=60)
    found = re.search(r"touch_stats: (\S+) boards=", done.stdout)
    if not found:
        sys.exit("导出没成功：" + (done.stdout.strip() or done.stderr.strip()))
    remote = found.group(1)
    os.makedirs(target, exist_ok=True)
    subprocess.run(adb + ["pull", remote, target], check=True, timeout=120)
    return os.path.join(target, os.path.basename(remote))


def main():
    ap = argparse.ArgumentParser(description="按键落点统计报告")
    ap.add_argument("--dir", help="op=touch_stats 导出、已经拉到电脑上的目录")
    ap.add_argument("--base", help="更早的一次导出；只算它之后新增的")
    ap.add_argument("--serial", help="设备号（配合 --pull）")
    ap.add_argument("--pull", metavar="DIR", help="先让输入法导出，再 adb pull 到这个目录下")
    ap.add_argument("--out", help="报告文件，默认放在导出目录里的 report.html")
    args = ap.parse_args()
    directory = pull(args.serial, args.pull) if args.pull else args.dir
    if not directory or not os.path.isfile(os.path.join(directory, "touch_stats.txt")):
        sys.exit("要 --dir <导出目录>（里面有 touch_stats.txt），或者 --pull <目录>")
    out = args.out or os.path.join(directory, "report.html")
    for s in build(directory, args.base, out):
        print(f"{s['id']}: 有落点的按下 {s['presses']}，字母点按 {s['letter_taps']}，事后改成相邻键 {s['adjacent_wrong']}，"
              f"没出字 {s['lost']}（像点按的 {s['lost_like_taps']}），提前出字 {s['early']}，仍然反了 {sum(s['inversions'].values())}")
    print("报告：" + out)


if __name__ == "__main__":
    main()
