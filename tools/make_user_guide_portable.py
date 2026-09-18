#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 使用说明.md 从「图片内嵌」改回「相对路径引用」。

为什么做这件事：内嵌（base64）虽然删掉图片文件也不裂，但源码里会出现
一长串 base64 字符，纯文本打开时看着像乱码。改回相对路径后源码干净，
代价是**图片文件必须和 md 在一起**。

为了把「图片丢了」的风险降到最低，图片集中放在与 md 同级的
`使用说明-图片/` 目录里，而不是散落在工程根目录：

    使用说明.md
    使用说明-图片/
      ├─ 使用说明-曲库页.png
      └─ ...

md 里写成 `![曲库页](使用说明-图片/使用说明-曲库页.png)`。

文件名取自内嵌数据与根目录原图的 sha256 比对结果，**不重新编码、不压缩**，
所以切回来的图与最初那 6 张逐字节一致。

用法：
    python tools\\make_user_guide_portable.py
"""

from __future__ import annotations

import base64
import hashlib
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MD_PATH = os.path.join(ROOT, "使用说明.md")
IMG_DIR_NAME = "使用说明-图片"
IMG_DIR = os.path.join(ROOT, IMG_DIR_NAME)

IMG_TAG = re.compile(
    r'<img\s+[^>]*?alt="([^"]*)"[^>]*?src="(data:image/png;base64,([A-Za-z0-9+/=]+))"[^>]*?>',
    re.IGNORECASE,
)
MD_DATA_IMG = re.compile(r"!\[([^\]]*)\]\((data:image/png;base64,[A-Za-z0-9+/=]+)\)")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    with open(MD_PATH, encoding="utf-8") as fh:
        md = fh.read()

    # 根目录现存的原图：用 sha256 反查文件名，保证切回来的就是最初那几张
    originals: dict[str, str] = {}
    for name in os.listdir(ROOT):
        if name.startswith("使用说明-") and name.endswith(".png"):
            with open(os.path.join(ROOT, name), "rb") as fh:
                originals[sha(fh.read())] = name

    os.makedirs(IMG_DIR, exist_ok=True)
    written: list[str] = []
    unresolved: list[str] = []

    def convert(alt: str, b64: str) -> str:
        data = base64.b64decode(b64)
        digest = sha(data)
        name = originals.get(digest)
        if name is None:
            # 根目录没有对应原图（比如原图已被删）：按 alt 命名写出去，数据本身仍是原图
            name = f"{alt or 'image'}.png"
            unresolved.append(name)
        target = os.path.join(IMG_DIR, name)
        # 只在内容不一致时写入，避免无意义地动文件时间戳
        if not (os.path.isfile(target) and sha(open(target, "rb").read()) == digest):
            with open(target, "wb") as fh:
                fh.write(data)
        written.append(name)
        return f"![{alt}]({IMG_DIR_NAME}/{name})"

    md, n_tags = IMG_TAG.subn(lambda m: convert(m.group(1), m.group(3)), md)
    md, n_md = MD_DATA_IMG.subn(lambda m: convert(m.group(1), m.group(2).split(",", 1)[1]), md)

    # 去掉脚本之前写进去的顶部提示块
    md = re.sub(r"^(?:> [^\n]*\n)+\n?", "", md, count=1)

    with open(MD_PATH, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(md)

    print(f"内嵌图片 -> 相对路径：{n_tags + n_md} 张")
    for name in written:
        print(f"   {IMG_DIR_NAME}/{name}")
    if unresolved:
        print(f"   注意：以下图片在根目录找不到同名原图，已按 alt 命名：{'、'.join(unresolved)}")
    print(f"\n已重写 {os.path.relpath(MD_PATH, ROOT)}"
          f"（{os.path.getsize(MD_PATH) / 1024:.0f} KB）")

    # 自检：md 里不能残留 base64，引用的每个文件必须真实存在
    text = open(MD_PATH, encoding="utf-8").read()
    left = text.count("base64")
    refs = re.findall(r"!\[[^\]]*\]\(([^)]+)\)", text)
    missing = [r for r in refs if not os.path.isfile(os.path.join(ROOT, r))]
    print(f"自检：残留 base64 = {left}；图片引用 = {len(refs)} 处；缺失文件 = {len(missing)}")
    if left or missing:
        for m in missing:
            print("   缺失:", m)
        return 1
    print("[OK] 已切回相对路径写法，且每个引用都能在磁盘上找到文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
