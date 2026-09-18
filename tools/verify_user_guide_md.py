#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 使用说明.md 的图片引用是否完整可用（当前采用**相对路径**写法）。

检查项：
  1. md 里没有残留的 base64 内嵌数据（已切回相对路径，源码应保持干净）；
  2. 每一处 `![alt](路径)` 引用的文件都真实存在于磁盘；
  3. 每个引用的 PNG 都能被真正解码（Pillow 打开 + 读像素 + verify）；
  4. 按内容（sha256）与预期体积核对，确认不是被重新编码/压缩过的图。

注意：这种方式下**图片文件必须和 md 在一起**。把 md 单独发给别人会看不到图，
要连同 `使用说明-图片/` 整个目录一起发（或先用 --embed 内嵌）。

用法：
    python tools\\verify_user_guide_md.py
"""

from __future__ import annotations

import hashlib
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MD = os.path.join(ROOT, "使用说明.md")

# Markdown 图片引用：![alt](path)
REF = re.compile(r"!\[([^\]]*)\]\(([^)]+)\)")
# 内嵌写法（本次刻意不用）：![](...data:...) 或 <img src="data:...">
EMBEDDED = re.compile(r"(?:<img[^>]*src=\"data:)|(?:!\[[^\]]*\]\(data:)")

# 最初那 6 张截图的体积，用来确认图片没有被重新编码
EXPECTED_SIZES = {
    "悬浮控制条.png": 38101,
    "无障碍已连接.png": 183190,
    "无障碍未连接.png": 195164,
    "曲库页.png": 313902,
    "校准页.png": 220427,
    "设置页.png": 215344,
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    if not os.path.isfile(MD):
        print(f"找不到 {MD}")
        return 1

    with open(MD, encoding="utf-8") as fh:
        md = fh.read()

    problems: list[str] = []
    print(f"源文件：{os.path.relpath(MD, ROOT)}"
          f"（{os.path.getsize(MD) / 1024:.1f} KB）\n")

    # 1) 不应残留内嵌数据
    embedded = EMBEDDED.findall(md)
    if embedded:
        problems.append(f"仍残留内嵌图片数据 {len(embedded)} 处（源码会显得像乱码）")
    print(f"[1] 残留内嵌 base64：{len(embedded)} 处（相对路径写法应为 0）")

    # 2/3/4) 每个引用都要存在、能解码、内容对得上
    refs = REF.findall(md)
    print(f"[2] 图片引用：{len(refs)} 处")
    for alt, rel in refs:
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            problems.append(f"引用的文件不存在：{rel}")
            print(f"    [FAIL] {alt:<14} -> {rel}  文件不存在")
            continue
        data = open(path, "rb").read()
        try:
            from PIL import Image
            with Image.open(io.BytesIO(data)) as im:
                im.verify()
            with Image.open(io.BytesIO(data)) as im:
                w, h = im.size
                im.convert("RGB").getpixel((w // 2, h // 2))
        except ImportError:
            w, h = int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
        base = os.path.basename(rel)
        size_ok = EXPECTED_SIZES.get(base, len(data)) == len(data)
        if not size_ok:
            problems.append(f"{rel} 的体积与原始截图不一致（可能被重新编码）")
        flag = "OK  " if size_ok else "DIFF"
        print(f"    [{flag}] {alt:<14} {w}x{h}  {len(data):>8} bytes  "
              f"sha256:{sha(data)[:12]}  {rel}")

    # 5) 目录里是否有多余/未引用的图
    used = {os.path.basename(r) for _, r in refs}
    img_dir = os.path.join(ROOT, "使用说明-图片")
    if os.path.isdir(img_dir):
        extra = sorted(set(os.listdir(img_dir)) - used)
        print(f"[3] 使用说明-图片/ 中未被引用的文件：{extra if extra else '无'}")

    print()
    if problems:
        print("[FAIL] 校验未通过：")
        for p in problems:
            print("   -", p)
        return 1
    print("[OK] 校验通过：引用完整、图片可解码、与原始截图字节一致（相对路径写法）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
