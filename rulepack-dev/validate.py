#!/usr/bin/env python3
"""简课表解析规则包校验器（纯标准库）。

用法：
    python validate.py rulepack.json [--sample sample.txt]

- 无 --sample：结构 / 安全上限 / 正则可编译 校验；
- 有 --sample：额外做指纹自检（anyOf 命中数 ≥ minHits）与节次/周次抽样提示。

注意：本脚本用 Python re 近似校验；应用端是 Java regex，个别方言差异以应用导入为准。
"""
import argparse
import json
import re
import sys

SCHEMA_VERSION = 1
MAX_PATTERN_LEN = 200
MAX_LIST_ITEMS = 24
MAX_WORD_LEN = 24
SEMANTIC_FIELDS = {
    "campus", "building", "room", "teacher",
    "classNo", "composition", "credit", "sections",
}

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def check_word(value, where: str, required: bool = True) -> None:
    if value is None or value == "":
        if required:
            err(f"{where}: 不能为空")
        return
    if not isinstance(value, str) or len(value) > MAX_WORD_LEN:
        err(f"{where}: 超长（≤{MAX_WORD_LEN} 字符）")


def compile_regex(pattern: str, where: str, groups: int | None = None):
    if len(pattern) > MAX_PATTERN_LEN:
        err(f"{where}: 正则超长（≤{MAX_PATTERN_LEN} 字符）")
        return None
    try:
        rx = re.compile(pattern)
    except re.error as e:
        err(f"{where}: 正则编译失败: {e}")
        return None
    if groups is not None and rx.groups < groups:
        err(f"{where}: 需要 ≥{groups} 个捕获组")
        return None
    # 灾难性回溯粗筛：嵌套量词
    if re.search(r"\([^)]*[+*][^)]*\)[+*{]", pattern) or "..*+ " in pattern:
        warn(f"{where}: 疑似嵌套量词，注意灾难性回溯风险")
    return rx


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("rulepack")
    ap.add_argument("--sample", help="课表样本文本（用于指纹自检；内容请用占位数据）")
    args = ap.parse_args()

    try:
        with open(args.rulepack, encoding="utf-8") as f:
            pack = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        print(f"[错误] 无法读取规则包: {e}")
        return 1

    # ---- 顶层 ----
    if pack.get("schemaVersion") != SCHEMA_VERSION:
        err(f"schemaVersion 必须为 {SCHEMA_VERSION}")
    rid = pack.get("id", "")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,40}", rid):
        err("id 只能含字母数字与 -_.（≤40 字符）")
    if rid in {"zfsoft", "icon-grid", "generic"}:
        err("id 与内置包重名")
    check_word(pack.get("name"), "name")

    # ---- match ----
    match = pack.get("match") or {}
    any_of = match.get("anyOf") or []
    if not any_of:
        err("match.anyOf 不能为空")
    if len(any_of) > MAX_LIST_ITEMS:
        err("match.anyOf 条目过多")
    for i, w in enumerate(any_of):
        check_word(w, f"match.anyOf[{i}]")
    min_hits = match.get("minHits", 1)

    # ---- table ----
    table = pack.get("table") or {}
    check_word(table.get("headerAnchor", ""), "table.headerAnchor", required=False)
    check_word(table.get("dayPattern", "星期"), "table.dayPattern", required=False)
    day_count = table.get("dayCount", 7)
    if not 1 <= day_count <= 7:
        err("table.dayCount 必须在 1..7")
    for key in ("labelWords", "legendWords"):
        words = table.get(key) or []
        if len(words) > MAX_LIST_ITEMS:
            err(f"table.{key} 条目过多")
        for i, w in enumerate(words):
            check_word(w, f"table.{key}[{i}]", required=False)

    # ---- blockStart ----
    bs = pack.get("blockStart") or {}
    for key in ("typeMarks", "markAliases"):
        m = bs.get(key) or {}
        if len(m) > MAX_LIST_ITEMS:
            err(f"blockStart.{key} 条目过多")
        for k, v in m.items():
            if len(k) != 1:
                err(f"blockStart.{key} key 必须单字符: {k!r}")
            check_word(v, f"blockStart.{key}[{k}]")

    # ---- fields ----
    fields = pack.get("fields") or {}
    for key in ("keyMap", "linePrefixes"):
        m = fields.get(key) or {}
        if len(m) > MAX_LIST_ITEMS:
            err(f"fields.{key} 条目过多")
        for k, v in m.items():
            check_word(k, f"fields.{key} key", required=False)
            if v not in SEMANTIC_FIELDS:
                err(f"fields.{key}[{k}]: 语义字段 {v!r} 不在白名单 {sorted(SEMANTIC_FIELDS)}")
    seps = fields.get("fieldSeparators", ":：·・")
    ends = fields.get("fieldEndChars", "/")
    if not (1 <= len(seps) <= 8):
        err("fields.fieldSeparators 长度需在 1..8")
    if not (1 <= len(ends) <= 8):
        err("fields.fieldEndChars 长度需在 1..8")

    sections_rx = None
    if str(fields.get("sectionsPattern", "")).strip():
        sections_rx = compile_regex(fields["sectionsPattern"], "fields.sectionsPattern", groups=2)
    weeks_rx = compile_regex(fields.get("weeksPattern", r"(\d+\s*-\s*\d+|\d+)\s*周"),
                             "fields.weeksPattern", groups=1)

    # ---- 指纹自检 ----
    if args.sample:
        try:
            with open(args.sample, encoding="utf-8", errors="replace") as f:
                text = f.read()
        except OSError as e:
            print(f"[错误] 无法读取样本: {e}")
            return 1
        hits = [w for w in any_of if w in text]
        print(f"[指纹] 命中 {len(hits)}/{len(any_of)}: {hits}（minHits={min_hits}）")
        if len(hits) < min_hits:
            err("指纹命中数不足——该包不会在应用内被选中")
        if sections_rx:
            found = sections_rx.findall(text)
            print(f"[节次] 抽到 {len(found)} 处，示例: {found[:5]}")
            if not found:
                warn("节次正则在样本中零命中——请核对写法")
        if weeks_rx:
            found = weeks_rx.findall(text)
            print(f"[周次] 抽到 {len(found)} 处，示例: {found[:5]}")
            if not found:
                warn("周次正则在样本中零命中——请核对写法")

    # ---- 结果 ----
    for w in warnings:
        print(f"[警告] {w}")
    if errors:
        for e in errors:
            print(f"[错误] {e}")
        print("校验失败")
        return 1
    print("校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
