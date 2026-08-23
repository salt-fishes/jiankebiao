# -*- coding: utf-8 -*-
"""真机端到端测试驱动：推送 PDF → 清理旧结果 → 启动 App 触发解析 → 轮询完成 → 拉取产物。

流程：
  1. adb push PDF 到 /data/local/tmp（run-as 可读）
  2. run-as 删除旧 result.json / ocr_debug.txt，复制 PDF 进 files/
  3. am start 启动 MainActivity（其 LaunchedEffect 检测到 PDF 无结果时自动启动前台服务）
  4. 轮询 files/result.json 与 ocr_debug.txt 的 mtime（最多 max_wait 秒）
  5. 二进制拉取 result.json / ocr_debug.txt / page_*.png 到 _device_out/

用法:
    python tools/run_device_test.py [--wait 600] [--tag run1]
"""
import argparse
import os
import subprocess
import sys
import time

ADB = r"C:\Users\15572\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV = "REMOVED-DEVICE-SERIAL"
PKG = "com.example.composeapp"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "_device_out")
PDF_LOCAL = os.path.join(ROOT, "张三(2026-2027-1)课表.pdf")


def sh(*args: str, timeout: int = 60) -> bytes:
    return subprocess.check_output([ADB, "-s", DEV, *args], timeout=timeout, stderr=subprocess.STDOUT)


def zh_octal(name: str) -> str:
    """把中文文件名转成 printf 八进制转义，规避 Windows adb 传参 GBK/UTF-8 编码错乱。"""
    return "".join(f"\\{b:03o}" for b in name.encode("utf-8"))


def run_as(*args: str, timeout: int = 60) -> bytes:
    return sh("shell", "run-as", PKG, *args, timeout=timeout)


def run_as_sh(cmd: str, timeout: int = 60) -> bytes:
    """在设备 sh 中执行（命令字符串全 ASCII，中文名用 $(printf '\\ooo...') 生成）。"""
    return run_as("sh", "-c", cmd, timeout=timeout)


def pull(name: str) -> int:
    data = subprocess.check_output(
        [ADB, "-s", DEV, "exec-out", "run-as", PKG, "cat", "files/" + name],
        stderr=subprocess.DEVNULL,
    )
    out = os.path.join(OUT_DIR, name)
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(out, "wb") as f:
        f.write(data)
    return len(data)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--wait", type=int, default=600, help="轮询超时秒数")
    ap.add_argument("--tag", type=str, default="", help="产物加后缀 tag")
    ap.add_argument("--bands", type=int, default=4, help="分区数（1=整页识别）")
    ap.add_argument("--overlap", type=int, default=40, help="分区重叠像素")
    ap.add_argument("--scale", type=float, default=2.0, help="渲染缩放")
    args = ap.parse_args()

    # 1. 推送 PDF（可读权限 644）
    sh("push", PDF_LOCAL, "/data/local/tmp/kb.pdf")
    sh("shell", "chmod", "644", "/data/local/tmp/kb.pdf")

    # 2. 清理旧产物并复制 PDF 为 ASCII 文件名（规避中文文件名编码错乱）
    run_as("rm", "-f", "files/result.json", "files/ocr_debug.txt",
           "files/page_0.png", "files/page_1.png")
    run_as("cp", "/data/local/tmp/kb.pdf", "files/kb.pdf")
    out = run_as("stat", "-c", "%s", "files/kb.pdf").decode().strip()
    print("设备 PDF 就位:", out, "bytes")

    # 3. force-stop 后启动 App 并传入 pdf_path/band 参数（ASCII 路径），
    #    MainActivity 冷启动时读取 extra，检测到文件存在即强制重新解析
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    pdf_device_path = "/data/user/0/com.example.composeapp/files/kb.pdf"
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity",
       "--es", "pdf_path", pdf_device_path,
       "--ei", "band_count", str(args.bands),
       "--ei", "band_overlap", str(args.overlap),
       "--ef", "render_scale", str(args.scale))

    # 4. 轮询等待 result.json 出现（服务写完即完成）
    t0 = time.time()
    last_mtime = -1
    while time.time() - t0 < args.wait:
        try:
            out = run_as("stat", "-c", "%Y", "files/result.json", timeout=10).decode().strip()
            mtime = int(out)
        except Exception:
            mtime = -1
        # result.json 可能不存在；ocr_debug.txt 会在解析早期出现
        try:
            dbg = run_as("stat", "-c", "%Y", "files/ocr_debug.txt", timeout=10).decode().strip()
            dbg_mtime = int(dbg)
        except Exception:
            dbg_mtime = -1
        if mtime > 0:
            print(f"[{time.time()-t0:5.1f}s] result.json 已生成")
            break
        if dbg_mtime > 0:
            print(f"[{time.time()-t0:5.1f}s] 解析进行中（ocr_debug.txt 已更新）…")
        time.sleep(5)
    else:
        print("超时：result.json 未生成，检查设备状态")
        return 2

    # 5. 二进制拉取产物
    print("拉取产物…")
    for f in ["result.json", "ocr_debug.txt", "page_0.png", "page_1.png"]:
        try:
            size = pull(f)
            print(f"  OK {f}: {size} bytes")
        except Exception as e:
            print(f"  SKIP {f}: {e}")

    # 6. 可选 tag 归档
    if args.tag:
        for f in ["result.json", "ocr_debug.txt"]:
            src = os.path.join(OUT_DIR, f)
            if os.path.exists(src):
                dst = os.path.join(OUT_DIR, f"{os.path.splitext(f)[0]}_{args.tag}{os.path.splitext(f)[1]}")
                with open(src, "rb") as r, open(dst, "wb") as w:
                    w.write(r.read())
                print(f"  tagged -> {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
