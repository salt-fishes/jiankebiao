# -*- coding: utf-8 -*-
"""二进制安全地拉取设备上应用私有目录的文件（避免 PowerShell/adb shell 编码损坏）。

用法:
    python tools/pull_device_files.py [文件名...]
    不带参数则拉取全部已知文件到 _device_out/
"""
import os
import subprocess
import sys

ADB = r"C:\Users\15572\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV = "REMOVED-DEVICE-SERIAL"
PKG = "com.example.composeapp"
OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_device_out")

FILES = ["ocr_debug.txt", "result.json", "page_0.png", "page_1.png"]


def pull(name: str) -> int:
    """返回拉取字节数。exec-out + run-as cat 全程二进制流，无 CRLF/编码转换。"""
    data = subprocess.check_output(
        [ADB, "-s", DEV, "exec-out", "run-as", PKG, "cat", "files/" + name],
        stderr=subprocess.DEVNULL,
    )
    out = os.path.join(OUT_DIR, name)
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(out, "wb") as f:
        f.write(data)
    return len(data)


def main() -> None:
    names = sys.argv[1:] or FILES
    for n in names:
        try:
            size = pull(n)
            print(f"OK  {n}: {size} bytes -> {os.path.join(OUT_DIR, n)}")
        except subprocess.CalledProcessError as e:
            print(f"ERR {n}: adb exit {e.returncode}")


if __name__ == "__main__":
    main()
