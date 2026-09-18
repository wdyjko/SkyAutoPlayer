#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 tools/build_user_guide.py 产出的 .docx（纯标准库，无需 Word）。

检查项：
  1. 每个 XML part 都必须是良构 XML；
  2. [Content_Types].xml 必须声明 document/styles/png；
  3. 每个 r:embed / r:id 都必须在 word/_rels/document.xml.rels 里有定义，
     且 relationship 的 Target 必须真实存在于包里（图片不能是空链接）；
  4. 每个 <w:drawing> 的 extent 必须是正数、单位是 EMU、
     且不超过可用正文宽度（5.9in）；
  5. 行内 Markdown 必须已被吃掉 —— 文字里不能残留 ** 或 ` ；
  6. 报出图片/表格/标题数量，便于和源 Markdown 对照。
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCX = os.path.join(ROOT, "使用说明.docx")

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
WP = "{http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing}"
CT = "{http://schemas.openxmlformats.org/package/2006/content-types}"
PR = "{http://schemas.openxmlformats.org/package/2006/relationships}"

EMU_PER_INCH = 914400

problems: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        problems.append(message)


def main() -> int:
    z = zipfile.ZipFile(DOCX)
    names = z.namelist()

    # 1. 全部 XML 良构
    xml_parts = [n for n in names if n.endswith((".xml", ".rels"))]
    for n in xml_parts:
        try:
            ET.fromstring(z.read(n))
        except ET.ParseError as exc:
            problems.append(f"{n} 不是良构 XML：{exc}")
    print(f"[1] XML 良构：{len(xml_parts)} 个 part 解析通过"
          f"{'（有错）' if problems else ''}")

    # 2. content types
    types = ET.fromstring(z.read("[Content_Types].xml"))
    defaults = {e.get("Extension").lower() for e in types.findall(f"{CT}Default")}
    overrides = {e.get("PartName") for e in types.findall(f"{CT}Override")}
    check("png" in defaults, "Content_Types 未声明 png")
    check("/word/document.xml" in overrides, "Content_Types 未声明 document.xml")
    check("/word/styles.xml" in overrides, "Content_Types 未声明 styles.xml")
    print(f"[2] Content_Types：defaults={sorted(defaults)}")

    # 3. rels 完整
    rels = ET.fromstring(z.read("word/_rels/document.xml.rels"))
    by_id = {}
    for rel in rels.findall(f"{PR}Relationship"):
        rid, target = rel.get("Id"), rel.get("Target")
        by_id[rid] = target
        if rel.get("Type", "").endswith("/image"):
            resolved = "word/" + target
            check(resolved in names, f"{rid} 的图片 Target 不在包里：{target}")
    print(f"[3] document.xml.rels：{len(by_id)} 条（图片 "
          f"{sum(1 for t in by_id.values() if 'media/' in t)} 张）")

    doc = ET.fromstring(z.read("word/document.xml"))
    body = doc.find(f"{W}body")

    # 4. 每个 drawing：referenced rid 已定义 + extent 合法
    drawings = doc.iter(f"{W}drawing")
    drawing_count = 0
    for d in drawings:
        drawing_count += 1
        inline = d.find(f"{WP}inline")
        check(inline is not None, "drawing 缺少 wp:inline")
        if inline is None:
            continue
        blip = inline.find(f".//{A}blip")
        embed = blip.get(f"{R}embed") if blip is not None else None
        check(embed in by_id, f"drawing 引用了未定义的 {embed}")
        ext = inline.find(f"{WP}extent")
        cx, cy = int(ext.get("cx")), int(ext.get("cy"))
        check(cx > 0 and cy > 0, f"extent 非正数：{cx}x{cy}")
        check(cx <= int(5.9 * EMU_PER_INCH) + 1000,
              f"图片宽度超出正文宽度：{cx / EMU_PER_INCH:.2f}in")
        check(cy <= int(8.6 * EMU_PER_INCH) + 1000,
              f"图片高度超出正文高度：{cy / EMU_PER_INCH:.2f}in")
        print(f"    drawing#{drawing_count}: {cx / EMU_PER_INCH:.2f}in x "
              f"{cy / EMU_PER_INCH:.2f}in  embed={embed}")

    # 5. 行内 Markdown 残留
    text = "".join(t.text or "" for t in doc.iter(f"{W}t"))
    for bad in ("**", "`"):
        if bad in text:
            problems.append(f"正文里残留了 Markdown 标记：{bad!r}")
    check("![" not in text, "正文里残留了 Markdown 图片语法")

    # 6. 统计
    h1 = sum(1 for s in doc.iter(f"{W}pStyle") if s.get(f"{W}val") == "Heading1")
    h2 = sum(1 for s in doc.iter(f"{W}pStyle") if s.get(f"{W}val") == "Heading2")
    tables = len(list(body.iter(f"{W}tbl")))
    print(f"[4] 结构：图片 {drawing_count} 张，表格 {tables} 个，"
          f"一级标题 {h1} 个，二级标题 {h2} 个，正文 {len(text)} 字")
    print(f"    首个标题：{text[:20]!r}")

    print()
    if problems:
        print("[FAIL] 校验未通过：")
        for p in problems:
            print("   -", p)
        return 1
    print("[OK] 校验全部通过：XML 良构、关系完整、图片尺寸合法、无 Markdown 残留")
    return 0


if __name__ == "__main__":
    sys.exit(main())
