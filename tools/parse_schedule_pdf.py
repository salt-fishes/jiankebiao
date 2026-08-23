# -*- coding: utf-8 -*-
"""课程表 PDF 解析工具 v2：提取并结构化课程数据。

解析 PDF 课表（含合并单元格、多课程单元格），输出：
- courses: 课程基础信息（名称/类型/学分/教师/地点/教学班等）
- entries: 排课条目（星期/节次范围/周次展开列表）

用法:
    python tools/parse_schedule_pdf.py <pdf路径> <输出json路径>
"""
import json
import re
import sys
from typing import List, Optional

import pdfplumber

# 课程名后的标记符号 -> 课程类型（与 PDF 图例一致）
TYPE_MARK = {
    ":": "集中实践",
    "★": "讲课",
    "○": "实验",
    "●": "上机",
    "◇": "实践",
}

# 星期列索引（表格列 2~8 对应星期一~星期日）
DAY_COLS = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
# 节次列索引
SECTION_COL = 1


def normalize(text: Optional[str]) -> str:
    """去除单元格空白与首尾换行。"""
    if not text:
        return ""
    return text.replace("\u3000", " ").strip()


def parse_weeks(text: str) -> List[int]:
    """解析周次描述 '1-3周,5-15周' / '9周,15周' / '3-15周' -> 展开的周列表。"""
    weeks: List[int] = []
    for part in re.split(r"[;；,，]", text.replace("周", " ").strip()):
        m = re.match(r"^\s*(\d+)\s*[-–—~至]\s*(\d+)\s*$", part)
        if m:
            weeks.extend(range(int(m.group(1)), int(m.group(2)) + 1))
        else:
            m = re.match(r"^\s*(\d+)\s*$", part)
            if m:
                weeks.append(int(m.group(1)))
    return sorted(set(weeks))


def parse_sections(text: str):
    """解析 '(1-2节)' -> (start, end)；解析 '(10-12节)' 同理。"""
    m = re.search(r"\((\d+)-(\d+)节\)", text)
    if m:
        return int(m.group(1)), int(m.group(2))
    return None


class CellParser:
    """把一个课程单元格文本解析为多条课程。"""

    # 课程块起始行：课程名 + 类型标记（如 "大学物理A2★"）
    _BLOCK_RE = re.compile(r"^(.+?)([★○●◇:])\s*$")

    # 属性行：key:value
    _FIELD_RE = re.compile(r"^(校区|楼号|场地|教师|教学班|教学班组成|选课备注|学分)\s*[:：]\s*(.*)$")

    def parse(self, text: str) -> List[dict]:
        lines = normalize(text).split("\n")
        blocks: List[List[str]] = []
        for line in lines:
            if not line:
                continue
            if self._BLOCK_RE.match(line):
                blocks.append([line])
            elif blocks:
                blocks[-1].append(line)
            else:
                # 无课程名的属性（正常应不会出现，兜底并入上一块）
                if blocks:
                    blocks[-1].append(line)
        return [self._parse_block(b) for b in blocks]

    def _parse_block(self, block: List[str]) -> dict:
        title = block[0]
        m = self._BLOCK_RE.match(title)
        name = m.group(1).strip()
        mark = m.group(2)
        body = "\n".join(block[1:])

        # 节次范围
        sections = parse_sections(body)
        start_section = sections[0] if sections else None
        end_section = sections[1] if sections else None

        fields = {"校区": "", "楼号": "", "场地": "", "教师": "",
                  "教学班": "", "教学班组成": "", "选课备注": "", "学分": ""}
        weeks_text = ""
        for line in block[1:]:
            fm = self._FIELD_RE.match(line.strip())
            if fm:
                fields[fm.group(1)] = fm.group(2).strip()
            else:
                # 周次行（含 "1-3周,5-15周" 或 "9周,15周"）
                if "周" in line:
                    weeks_text = line.strip()

        weeks = parse_weeks(weeks_text) if weeks_text else []
        return {
            "name": name,
            "type": TYPE_MARK.get(mark, ""),
            "sections": [start_section, end_section] if start_section else [],
            "weeks": weeks,
            "campus": fields["校区"],
            "building": fields["楼号"],
            "room": fields["场地"],
            "teacher": fields["教师"],
            "classNo": fields["教学班"],
            "composition": fields["教学班组成"],
            "credit": fields["学分"],
        }


def parse_pdf(pdf_path: str) -> dict:
    """提取表格并解析为结构化课程数据。"""
    result = {"meta": {}, "courses": [], "entries": []}
    seen_courses = {}

    with pdfplumber.open(pdf_path) as pdf:
        parser = CellParser()
        # 跨表格共享合并状态：表格 1 晚上 10 节与表格 2 的 11 节是同一课程块的续行
        merged = [None] * 7  # merged[day_idx] = 最近课程块在 cells 中的索引
        cells = []           # 待解析的 (day_idx, text) 列表
        for page in pdf.pages:
            for table in page.extract_tables():
                for row in table:
                    if len(row) < 9:
                        continue
                    day_text = normalize(row[2])
                    if day_text == "时间段":
                        continue
                    for day_idx in range(7):
                        cell = normalize(row[2 + day_idx])
                        if not cell:
                            continue
                        first_line = cell.split("\n", 1)[0]
                        if CellParser._BLOCK_RE.match(first_line):
                            merged[day_idx] = len(cells)
                            cells.append((day_idx, cell))
                        elif merged[day_idx] is not None:
                            idx = merged[day_idx]
                            cells[idx] = (day_idx, cells[idx][1] + "\n" + cell)
                for day_idx, cell in cells:
                    for course in parser.parse(cell):
                        # 课程去重（按 名称+类型+学分 视为同一课程）
                        key = (course["name"], course["type"], course["credit"])
                        if key not in seen_courses:
                            seen_courses[key] = len(result["courses"])
                            result["courses"].append(
                                {k: course[k] for k in
                                 ("name", "type", "credit", "teacher", "campus",
                                  "building", "room", "classNo", "composition")}
                            )
                        result["entries"].append({
                            "course": course["name"],
                            "dayOfWeek": day_idx + 1,  # 1=星期一
                            "startSection": course["sections"][0] if course["sections"] else None,
                            "endSection": course["sections"][1] if course["sections"] else None,
                            "weeks": course["weeks"],
                        })
    return result


def main() -> None:
    if len(sys.argv) != 3:
        print("usage: parse_schedule_pdf.py <pdf> <out.json>", file=sys.stderr)
        sys.exit(1)
    data = parse_pdf(sys.argv[1])
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print(f"OK: {len(data['courses'])} courses, {len(data['entries'])} entries -> {sys.argv[2]}")


if __name__ == "__main__":
    main()
