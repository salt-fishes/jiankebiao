# -*- coding: utf-8 -*-
"""对比真机 OCR 解析结果与桌面 pdfplumber 基准。

用法:
    python tools/compare_with_ground_truth.py [真机result.json] [基准ground_truth.json]

输出:
    - 课程名召回/精确（归一化后精确匹配）
    - 排课条目 (day, start, end) 匹配情况
    - 周次集合 Jaccard 相似度
    - 字段填充率（教师/场地/学分等）
"""
import json
import sys


def norm(s: str) -> str:
    return (s or "").replace(" ", "").replace("　", "").replace("（", "(").replace("）", ")")


def main() -> int:
    device_path = sys.argv[1] if len(sys.argv) > 1 else "_device_out/result.json"
    truth_path = sys.argv[2] if len(sys.argv) > 2 else "_device_out/ground_truth.json"

    with open(device_path, encoding="utf-8") as f:
        dev = json.load(f)
    with open(truth_path, encoding="utf-8") as f:
        truth = json.load(f)

    if "error" in dev:
        print("设备解析出错:", dev["error"])
        return 1

    dev_courses = {norm(c["name"]): c for c in dev["courses"]}
    truth_courses = {norm(c["name"]): c for c in truth["courses"]}

    print(f"设备课程数: {len(dev_courses)} | 基准课程数: {len(truth_courses)}")
    missing = sorted(set(truth_courses) - set(dev_courses))
    extra = sorted(set(dev_courses) - set(truth_courses))
    print("缺失课程:", missing or "无")
    print("多余课程:", extra or "无")

    # 条目对比：(course, day, start) 为键；基准中无节次的标题行条目跳过
    # （桌面脚本把"形势与政策3★"标题行单独算作条目，设备侧正确合并进 10-12 节块）
    dev_entries = {}
    for e in dev["entries"]:
        dev_entries.setdefault((norm(e["course"]), e["dayOfWeek"], e["startSection"]), []).append(e)
    truth_entries = {}
    for e in truth["entries"]:
        if e["startSection"] is None:
            continue
        truth_entries.setdefault((norm(e["course"]), e["dayOfWeek"], e["startSection"]), []).append(e)

    tp = 0
    fn = 0
    fp = 0
    week_jaccards = []
    for key, tlist in truth_entries.items():
        dlist = dev_entries.get(key, [])
        if dlist:
            tp += 1
            t_weeks = set(sum((e["weeks"] for e in tlist), []))
            d_weeks = set(sum((e["weeks"] for e in dlist), []))
            if t_weeks:
                week_jaccards.append(len(t_weeks & d_weeks) / len(t_weeks | d_weeks))
        else:
            fn += 1
            print(f"  缺失条目: day={key[1]} start={key[2]} {key[0]}")
    for key in dev_entries:
        if key not in truth_entries:
            fp += 1
            print(f"  多余条目: day={key[1]} start={key[2]} {key[0]}")

    print(f"\n条目: 命中 {tp} / 缺失 {fn} / 多余 {fp}")
    if week_jaccards:
        print(f"周次 Jaccard 均值: {sum(week_jaccards)/len(week_jaccards):.3f} (n={len(week_jaccards)})")

    # 字段填充率（对双方都有的课程，看设备侧字段是否与基准一致或非空）
    filled = {"teacher": 0, "room": 0, "credit": 0, "classNo": 0}
    common = set(dev_courses) & set(truth_courses)
    for name in common:
        dc = dev_courses[name]
        for fld in filled:
            if norm(dc.get(fld, "")):
                filled[fld] += 1
    print(f"共同课程 {len(common)} 门的字段填充: { {k: f'{v}/{len(common)}' for k, v in filled.items()} }")
    return 0


if __name__ == "__main__":
    sys.exit(main())
