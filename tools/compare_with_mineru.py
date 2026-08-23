# -*- coding: utf-8 -*-
"""对比真机解析结果与 MinerU markdown 表格基准。

MinerU 表格语义：
  - 列: 0=时间段, 1=节次, 2..8=星期一..星期日
  - rowspan 展开后，同列内"标题单元格(课程名+标记)"与其后"续行单元格"
    (属性/节次详情，不以课程块起始) 组成一个课程块（与 app 端 buildCellBlocks 一致）
  - 节次: 优先单元格文本中的 (N-M节)；否则取行跨度内的节次号
"""
import json
import re
import sys
from html.parser import HTMLParser

MINERU = r"C:\Users\15572\Documents\trae_projects\class\_device_out\mineru_full.txt"
DEVICE = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\15572\Documents\trae_projects\class\_device_out\result_pdfrenderer_bands4.json"

TR_RE = re.compile(r"<tr[^>]*>(.*?)</tr>", re.S)
TD_RE = re.compile(r"<td([^>]*)>(.*?)</td>", re.S)
# MinerU 单元格: 名称+标记+详情在同一行（如 "模拟电子线路★(1-2节)..." 或单独的 "形势与政策3★"）。
# 课程名不含 "/"（属性分隔符），以此排除 "(10-12节).../教学班:(...)..." 这类续行单元格。
MINERU_BLOCK_RE = re.compile(r"^([^/]+?)([★○●◇:])\s*(?:\(|$)")
SECTIONS_RE = re.compile(r"\((\d+)-(\d+)节\)")
WEEK_RANGES_RE = re.compile(r"(\d+\s*-\s*\d+|\d+)\s*周")


def parse_weeks(text: str):
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


def parse_mineru_grid(html: str):
    """rowspan 展开的完整网格 (list[list[str]])。"""
    rows_html = TR_RE.findall(html)
    grid = []
    active = []  # (col, remaining, text)
    ncols = 0
    for rh in rows_html:
        row = {}
        next_active = []
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


def extract_mineru_courses(grid):
    """返回 list[(day, start, end, weeks, 课程名)]，含同列续行合并。"""
    nrows = len(grid)
    section_by_row = [row[1].strip() for row in grid]
    courses = []
    for day_idx in range(7):
        col = 2 + day_idx
        i = 0
        while i < nrows:
            # 跳过 rowspan 展开产生的重复单元格
            if i > 0 and grid[i][col] == grid[i - 1][col]:
                i += 1
                continue
            text = grid[i][col].strip()
            if not text:
                i += 1
                continue
            first = text.split("\n", 1)[0]
            m = MINERU_BLOCK_RE.match(first)
            if not m:
                i += 1
                continue
            name = m.group(1)
            # 收集该块完整文本：标题单元格 + 后续同列非标题单元格（跳过 rowspan 重复）
            block_text = text
            start_row = i
            end_row = i
            j = i + 1
            while j < nrows:
                if j > 0 and grid[j][col] == grid[j - 1][col]:
                    j += 1
                    continue
                t2 = grid[j][col].strip()
                if not t2:
                    break
                f2 = t2.split("\n", 1)[0]
                if MINERU_BLOCK_RE.match(f2):
                    break
                block_text += "\n" + t2
                end_row = j
                j += 1
            # 节次
            sm = SECTIONS_RE.search(block_text)
            if sm:
                start, end = int(sm.group(1)), int(sm.group(2))
            else:
                secs = [int(section_by_row[r]) for r in range(start_row, end_row + 1)
                        if section_by_row[r].isdigit()]
                start, end = (secs[0], secs[-1]) if secs else (None, None)
            weeks = parse_weeks(",".join(WEEK_RANGES_RE.findall(block_text)))
            courses.append((day_idx + 1, start, end, weeks, name))
            i = j
    return courses


def main() -> int:
    html = open(MINERU, encoding="utf-8").read()
    grid = parse_mineru_grid(html)
    mineru = extract_mineru_courses(grid)
    print(f"MinerU 表格: {len(grid)} 行 x {len(grid[0])} 列, 课程块 {len(mineru)}")
    for e in mineru:
        ws = ",".join(map(str, e[3]))
        print(f"  day{e[0]} {e[1]}-{e[2]}节 weeks=[{ws}] {e[4]}")

    dev = json.load(open(DEVICE, encoding="utf-8"))
    dev_entries = [
        (e["dayOfWeek"], e["startSection"], e["endSection"], tuple(sorted(e["weeks"])), e["course"])
        for e in dev["entries"]
    ]
    print(f"\n设备条目: {len(dev_entries)}")

    def norm_name(s):
        return s.replace(" ", "").replace("　", "").replace("（", "(").replace("）", ")")

    mismatches = 0
    used_dev = [False] * len(dev_entries)  # 配对后标记已消耗的设备条目
    for e in mineru:
        day, ss, es, weeks, name = e
        # 找到第一个未消耗且 (day, start, 课程名) 相同的设备条目
        cand = next((i for i, d in enumerate(dev_entries)
                     if not used_dev[i] and d[0] == day and d[1] == ss
                     and norm_name(d[4]) == norm_name(name)), None)
        if cand is None:
            print(f"[MISS] MinerU 有而设备无: day{day} {ss}-{es}节 {name}")
            mismatches += 1
            continue
        used_dev[cand] = True
        d = dev_entries[cand]
        if d[2] != es:
            print(f"[DIFF] 节次不同: day{day} {name} MinerU={ss}-{es} 设备={d[1]}-{d[2]}")
            mismatches += 1
        if d[3] != tuple(weeks):
            mw = ",".join(map(str, sorted(weeks)))
            dw = ",".join(map(str, sorted(d[3])))
            print(f"[DIFF] 周次不同: day{day} {name} MinerU=[{mw}] 设备=[{dw}]")
            mismatches += 1
    for i, d in enumerate(dev_entries):
        if not used_dev[i]:
            day, ss, es, weeks, name = d
            print(f"[EXTRA] 设备有而 MinerU 无: day{day} {ss}-{es}节 {name}")
            mismatches += 1

    print(f"\n结论: MinerU 课程块 {len(mineru)}，设备条目 {len(dev_entries)}，差异 {mismatches} 处")
    return 0


if __name__ == "__main__":
    sys.exit(main())
