# -*- coding: utf-8 -*-
"""生成 MinerU 基准 vs 设备结果 的逐条对照表（差异=0 时也输出全表）。"""
import json
import re
import sys

MINERU = r"C:\Users\15572\Documents\trae_projects\class\_device_out\mineru_full.txt"
DEVICE = r"C:\Users\15572\Documents\trae_projects\class\_device_out\result_pdfrenderer_bands4.json"
OUT = r"C:\Users\15572\Documents\trae_projects\class\_device_out\mineru_vs_device.md"

TR_RE = re.compile(r"<tr[^>]*>(.*?)</tr>", re.S)
TD_RE = re.compile(r"<td([^>]*)>(.*?)</td>", re.S)
MINERU_BLOCK_RE = re.compile(r"^([^/]+?)([★○●◇:])\s*(?:\(|$)")
SECTIONS_RE = re.compile(r"\((\d+)-(\d+)节\)")
WEEK_RANGES_RE = re.compile(r"(\d+\s*-\s*\d+|\d+)\s*周")


def parse_weeks(text):
    weeks = set()
    for part in re.split(r"[;；,，]", text.replace("周", " ")):
        m = re.match(r"^\s*(\d+)\s*[-–—~至]\s*(\d+)\s*$", part)
        if m:
            weeks.update(range(int(m.group(1)), int(m.group(2)) + 1))
        else:
            m = re.match(r"^\s*(\d+)\s*$", part)
            if m:
                weeks.add(int(m.group(1)))
    return sorted(weeks)


def parse_mineru_grid(html):
    rows_html = TR_RE.findall(html)
    grid, active, ncols = [], [], 0
    for rh in rows_html:
        row, next_active = {}, []
        for (c, rem, text) in active:
            row[c] = text
            if rem > 1:
                next_active.append((c, rem - 1, text))
        col = 0
        for m in TD_RE.finditer(rh):
            while col in row:
                col += 1
            attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
            rs = int(attrs.get("rowspan", "1"))
            row[col] = m.group(2)
            if rs > 1:
                next_active.append((col, rs - 1, m.group(2)))
            col += 1
        active = sorted(next_active)
        ncols = max(ncols, max(row.keys(), default=-1) + 1)
        grid.append(row)
    return [[row.get(c, "") for c in range(ncols)] for row in grid]


def extract_mineru(grid):
    nrows = len(grid)
    sec = [row[1].strip() for row in grid]
    out = []
    for day_idx in range(7):
        col = 2 + day_idx
        i = 0
        while i < nrows:
            if i > 0 and grid[i][col] == grid[i - 1][col]:
                i += 1
                continue
            text = grid[i][col].strip()
            if not text:
                i += 1
                continue
            m = MINERU_BLOCK_RE.match(text.split("\n", 1)[0])
            if not m:
                i += 1
                continue
            name = m.group(1)
            block = text
            start_row = i
            end_row = i
            j = i + 1
            while j < nrows:
                if grid[j][col] == grid[j - 1][col]:
                    j += 1
                    continue
                t2 = grid[j][col].strip()
                if not t2:
                    break
                if MINERU_BLOCK_RE.match(t2.split("\n", 1)[0]):
                    break
                block += "\n" + t2
                end_row = j
                j += 1
            sm = SECTIONS_RE.search(block)
            if sm:
                s, e = int(sm.group(1)), int(sm.group(2))
            else:
                secs = [int(sec[r]) for r in range(start_row, end_row + 1) if sec[r].isdigit()]
                s, e = (secs[0], secs[-1]) if secs else (None, None)
            out.append((day_idx + 1, s, e, parse_weeks(",".join(WEEK_RANGES_RE.findall(block))), name))
            i = j
    return out


def norm(s):
    return s.replace(" ", "").replace("　", "").replace("（", "(").replace("）", ")")


def main():
    html = open(MINERU, encoding="utf-8").read()
    mineru = extract_mineru(parse_mineru_grid(html))
    dev = json.load(open(DEVICE, encoding="utf-8"))
    dev_entries = [(e["dayOfWeek"], e["startSection"], e["endSection"],
                    tuple(sorted(e["weeks"])), e["course"]) for e in dev["entries"]]

    used = [False] * len(dev_entries)
    lines = ["# MinerU 基准 vs 真机解析 逐条对照", "", "| # | 星期 | 节次 | 课程 | 周次(MinerU) | 周次(设备) | 周次一致 |", "|---|------|------|------|------|------|------|"]
    mism = 0
    for idx, e in enumerate(mineru):
        day, ss, es, weeks, name = e
        cand = next((i for i, d in enumerate(dev_entries)
                     if not used[i] and d[0] == day and d[1] == ss and norm(d[4]) == norm(name)), None)
        if cand is None:
            lines.append(f"| {idx+1} | 周{day} | {ss}-{es} | {name} | {weeks} | ❌缺失 | ❌ |")
            mism += 1
            continue
        used[cand] = True
        d = dev_entries[cand]
        ok = "✅" if d[3] == tuple(weeks) and d[2] == es else "❌"
        if d[3] != tuple(weeks) or d[2] != es:
            mism += 1
        mw = ",".join(map(str, weeks))
        dw = ",".join(map(str, d[3]))
        week_ok = "✅" if d[3] == tuple(weeks) else "❌"
        lines.append(f"| {idx+1} | 周{day} | {ss}-{es} | {name} | {mw} | {dw} | {week_ok} |")
    for i, d in enumerate(dev_entries):
        if not used[i]:
            lines.append(f"| - | 周{d[0]} | {d[1]}-{d[2]} | {d[4]} | - | {d[3]} | ❌设备多余 |")
            mism += 1
    lines.append("")
    lines.append(f"**总计**: MinerU {len(mineru)} 条 / 设备 {len(dev_entries)} 条 / 差异 **{mism}** 处")
    open(OUT, "w", encoding="utf-8").write("\n".join(lines))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
