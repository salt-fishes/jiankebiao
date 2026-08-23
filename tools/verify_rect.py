# -*- coding: utf-8 -*-
"""验证 rect 边界方案：用 pdfplumber 的 rect 边界重建单元格文本。"""
import re
import pdfplumber

PDF = r"c:\Users\15572\Documents\trae_projects\class\张三(2026-2027-1)课表.pdf"
BLOCK_RE = re.compile(r"^(.+?)([★○●◇:])\s*$")


def extract_cells(page):
    """用 rect 边界把字符归入单元格。返回 [(colIdx, text)]，colIdx 0..6（星期列）。"""
    rects = page.rects
    chars = [c for c in page.chars if not c["text"].isspace()]
    if not rects or not chars:
        return []

    # 列边界：所有 rect 的 x0/x1 去重
    col_bounds = sorted(set([round(r["x0"], 1) for r in rects] + [round(r["x1"], 1) for r in rects]))
    # 行边界：所有 rect 的 top/bottom 去重（y 向下，bottom 更大）
    row_bounds = sorted(set([round(r["top"], 1) for r in rects] + [round(r["bottom"], 1) for r in rects]))

    # 星期列 = 后 7 列（前 2 列是 时间段/节次）
    day_cols = list(zip(col_bounds[-8:-1], col_bounds[-1:]))  # 占位
    # 实际取第 3..9 列：col_bounds 有 10 个值（9 列边界）-> 每列 [b[i], b[i+1]]
    day_ranges = [(col_bounds[i], col_bounds[i + 1]) for i in range(2, len(col_bounds) - 1)]
    day_ranges = day_ranges[:7]

    cells = {i: [] for i in range(7)}
    for c in chars:
        x, y = c["x0"], c["top"]
        # 找列
        col = None
        for i, (lo, hi) in enumerate(day_ranges):
            if lo - 1 <= x <= hi + 1:
                col = i
                break
        if col is None:
            continue
        # 找行（y 落在哪个行区间）
        row = 0
        for j in range(len(row_bounds) - 1):
            if row_bounds[j] - 1 <= y <= row_bounds[j + 1] + 1:
                row = j
                break
        cells[col].append((row, y, x, c["text"]))

    # 每列按 (row, y, x) 排序拼接，再按行组织文本
    result = []
    for col in range(7):
        items = sorted(cells[col], key=lambda t: (t[0], t[1], t[2]))
        # 按 row 分组为文本块
        blocks = []
        for row, y, x, ch in items:
            if blocks and blocks[-1][0] == row:
                blocks[-1][1].append(ch)
            else:
                blocks.append((row, [ch]))
        for row, chs in blocks:
            text = "".join(chs)
            if text.strip():
                result.append((col, row, text))
    result.sort(key=lambda t: t[1])
    return [(c, t) for c, _, t in result]


def main():
    with pdfplumber.open(PDF) as pdf:
        standard = []
        for page in pdf.pages:
            for table in page.extract_tables():
                for row in table:
                    standard.append(row)

        cells_all = []
        for page in pdf.pages:
            cells_all.extend(extract_cells(page))

    print("=== rect 方案重建结果 ===")
    for col_idx, text in cells_all:
        print(f"[列{col_idx}] {text[:60].replace(chr(10), ' / ')}")

    names_table, names_rect = set(), set()
    for row in standard:
        for cell in row:
            if cell:
                for line in cell.split("\n"):
                    m = BLOCK_RE.match(line.strip())
                    if m:
                        names_table.add(m.group(1))
    for _, text in cells_all:
        for line in text.split("\n"):
            m = BLOCK_RE.match(line.strip())
            if m:
                names_rect.add(m.group(1))

    print(f"\n标准课程数: {len(names_table)} | rect 方案课程数: {len(names_rect)}")
    print("缺失:", names_table - names_rect or "无")
    print("多余:", names_rect - names_table or "无")


if __name__ == "__main__":
    main()
