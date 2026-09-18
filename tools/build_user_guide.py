#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""《使用说明.md》的两个可选维护动作。**默认什么都不做**，必须显式给参数。

    python tools\\build_user_guide.py --embed    # 把相对路径图片换成 <img>+base64 内嵌
                                                 # （源码会变长、纯文本看是一串 base64）
    python tools\\build_user_guide.py --docx     # 从 md 生成 Word，产物固定为
                                                 # tools/generated-使用说明.docx

**当前仓库的 使用说明.md 用的是相对路径写法**（图片在 使用说明-图片/），
所以默认运行这个脚本不会、也不应该改动任何东西。

两条硬性约束（都是踩过坑之后加的）：

1. **永不写 使用说明.docx**。那份文档是手工排版的成果，自动覆盖会毁掉人工编辑。
   要生成 docx 只能走 `--docx`，且产物强制写到 tools/generated-使用说明.docx。
   （历史事故：一次 `build_user_guide.py` 直接覆盖了手工编辑的 使用说明.docx。）
2. **不默认内嵌图片**。相对路径 -> base64 不可逆地改变源码可读性，必须是显式选择。
   （历史事故：脚本默认内嵌，把 6 KB 的 md 变成 1525 KB 的一串 base64。）

为什么不用 python-docx：本机没有该依赖且不联网安装，.docx 本身就是一个
ZIP + OOXML，直接按规范拼装最稳，零第三方依赖（只用标准库）。
"""

from __future__ import annotations

import base64
import os
import re
import struct
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 源和产物是同一个文件：md 原地升级成自包含版本。幂等 —— 已经内嵌过的不会再动它。
MD_PATH = os.path.join(ROOT, "使用说明.md")
# 人工维护的文档，脚本**永不**写入这个路径。
USER_DOCX = os.path.join(ROOT, "使用说明.docx")
# 显式 --docx 时的产物路径：刻意换个名字，避免和人工版本撞车。
GEN_DOCX = os.path.join(ROOT, "tools", "generated-使用说明.docx")

NOTE_PREFIX = "> ⚠️ 本文件图片已内嵌（base64），单文件即可分享 / 预览"

# md 里内嵌图片的统一写法：<img alt="..." src="data:image/png;base64,...">
IMG_TAG = re.compile(
    r'<img\s+[^>]*?src="(data:image/png;base64,[A-Za-z0-9+/=]+)"[^>]*?>',
    re.IGNORECASE,
)
MD_IMG = re.compile(r"!\[([^\]]*)\]\((data:image/png;base64,[A-Za-z0-9+/=]+)\)")

# 只用于「回缩」显示：1440x3200 的竖屏截图铺满宽度会几千像素高，没法看。
DISPLAY_MAX_WIDTH = 260

EMU_PER_INCH = 914400
EMU_PER_PX_160DPI = EMU_PER_INCH // 160      # 图片无 DPI 信息，按 160dpi 折算物理尺寸
CONTENT_WIDTH_EMU = int(5.9 * EMU_PER_INCH)  # A4 去 2.5cm 页边距后的可用宽度
CONTENT_HEIGHT_EMU = int(8.6 * EMU_PER_INCH)  # 可用高度，留出正文与题注的余地

# ─────────────────────────────────────────────────────────────────────────────
# 图片信息
# ─────────────────────────────────────────────────────────────────────────────


def png_size(data: bytes) -> tuple[int, int]:
    """从 PNG 头读宽高（IHDR），不依赖 Pillow。

    直接吃 bytes 而不是路径：图片现在来自 md 内嵌的 base64，磁盘上可能已经没有
    那个 .png 文件了 —— 这正是本次要保证的性质。
    """
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("不是标准 PNG")
    return struct.unpack(">II", data[16:24])


def scaled_size(px: tuple[int, int]) -> tuple[int, int]:
    """把像素尺寸折成 EMU，并保证宽、高都装得进正文框。

    竖屏截图是 1440x3200：按 160dpi 折算有 9in 宽 / 20in 高，只按宽度收敛会
    得到一张 13in 高的图，在 Word 里会直接溢出页面（实测校验器报出来的问题）。
    因此宽度和高度两个上限都要取，且必须**同时**作用于同一比例。
    """
    cx, cy = px[0] * EMU_PER_PX_160DPI, px[1] * EMU_PER_PX_160DPI
    ratio = min(CONTENT_WIDTH_EMU / cx, CONTENT_HEIGHT_EMU / cy, 1.0)
    return max(1, int(cx * ratio)), max(1, int(cy * ratio))


# ─────────────────────────────────────────────────────────────────────────────
# 行内 Markdown → OOXML run
# ─────────────────────────────────────────────────────────────────────────────

INLINE = re.compile(r"(\*\*.+?\*\*|`[^`]+`)")


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def runs(text: str, *, size_half_pt: int | None = None, color: str | None = None,
         mono: bool = False, bold: bool = False) -> str:
    """把一段行内 Markdown 转成若干 <w:r>。支持 **粗体** 与 `等宽`。"""
    out: list[str] = []
    for part in INLINE.split(text):
        if not part:
            continue
        is_code = len(part) >= 2 and part.startswith("`") and part.endswith("`")
        is_bold = len(part) >= 4 and part.startswith("**") and part.endswith("**")
        props: list[str] = []
        if mono or is_code:
            props.append('<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:eastAsia="微软雅黑"/>')
        if bold or is_bold:
            props.append("<w:b/>")
        if size_half_pt:
            props.append(f'<w:sz w:val="{size_half_pt}"/><w:szCs w:val="{size_half_pt}"/>')
        if color:
            props.append(f'<w:color w:val="{color}"/>')
        body = part
        if is_bold:
            body = part[2:-2]
        elif is_code:
            body = part[1:-1]
        rpr = f"<w:rPr>{''.join(props)}</w:rPr>" if props else ""
        out.append(
            f'<w:r>{rpr}<w:t xml:space="preserve">{esc(body)}</w:t></w:r>'
        )
    return "".join(out)


# ─────────────────────────────────────────────────────────────────────────────
# 块级元素
# ─────────────────────────────────────────────────────────────────────────────

def para(text: str = "", *, style: str | None = None, align: str | None = None,
         spacing: str | None = None, indent: str | None = None,
         size_half_pt: int | None = None, color: str | None = None) -> str:
    ppr: list[str] = []
    if style:
        ppr.append(f'<w:pStyle w:val="{style}"/>')
    if spacing:
        ppr.append(spacing)
    if indent:
        ppr.append(indent)
    if align:
        ppr.append(f'<w:jc w:val="{align}"/>')
    ppr_xml = f"<w:pPr>{''.join(ppr)}</w:pPr>" if ppr else ""
    return f"<w:p>{ppr_xml}{runs(text, size_half_pt=size_half_pt, color=color)}</w:p>"


def heading(text: str, level: int) -> str:
    style = "Heading1" if level == 1 else "Heading2"
    return para(text, style=style)


def bullet(text: str) -> str:
    return (
        '<w:p><w:pPr><w:pStyle w:val="ListParagraph"/>'
        '<w:ind w:left="420" w:hanging="240"/></w:pPr>'
        f'{runs("•  " + text)}</w:p>'
    )


def table(header: list[str], rows: list[list[str]]) -> str:
    cols = max([len(header)] + [len(r) for r in rows]) if (header or rows) else 0
    if cols == 0:
        return ""
    width = CONTENT_WIDTH_EMU // cols
    grid = "".join(f'<w:gridCol w:w="{width // 635}"/>' for _ in range(cols))
    borders = (
        "<w:tblBorders>"
        + "".join(
            f'<w:{side} w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/>'
            for side in ("top", "left", "bottom", "right", "insideH", "insideV")
        )
        + "</w:tblBorders>"
    )
    tbl_pr = (
        "<w:tblPr>"
        '<w:tblW w:w="5000" w:type="pct"/>'
        '<w:tblLayout w:type="fixed"/>'
        f"{borders}"
        '<w:tblCellMar>'
        '<w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>'
        '<w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/>'
        "</w:tblCellMar>"
        "</w:tblPr>"
    )

    def cell(text: str, bold: bool, shade: bool) -> str:
        tc_pr = "<w:tcPr>" + (
            '<w:shd w:val="clear" w:color="auto" w:fill="F1F3F4"/>' if shade else ""
        ) + "</w:tcPr>"
        content = runs(text, size_half_pt=20, bold=bold)
        p = (
            '<w:p><w:pPr><w:spacing w:before="20" w:after="20"/>'
            '<w:jc w:val="left"/></w:pPr>' + content + "</w:p>"
        )
        return f"<w:tc>{tc_pr}{p}</w:tc>"

    body = ""
    if header:
        body += "<w:tr>" + "".join(cell(c, True, True) for c in header) + "</w:tr>"
    for row in rows:
        padded = list(row) + [""] * (cols - len(row))
        body += "<w:tr>" + "".join(cell(c, False, False) for c in padded) + "</w:tr>"
    return f"<w:tbl>{tbl_pr}<w:tblGrid>{grid}</w:tblGrid>{body}</w:tbl>" + para("")


def image(drawing_id: int, rel_id: str, name: str, px: tuple[int, int]) -> str:
    cx, cy = scaled_size(px)
    return (
        "<w:p><w:pPr><w:jc w:val=\"center\"/>"
        '<w:spacing w:before="120" w:after="40"/></w:pPr><w:r><w:drawing>'
        '<wp:inline distT="0" distB="0" distL="0" distR="0">'
        f'<wp:extent cx="{cx}" cy="{cy}"/>'
        f'<wp:docPr id="{drawing_id}" name="{esc(name)}" descr="{esc(name)}"/>'
        "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
        "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
        "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
        f'<pic:nvPicPr><pic:cNvPr id="{drawing_id}" name="{esc(name)}"/>'
        '<pic:cNvPicPr><a:picLocks noChangeAspect="1"/></pic:cNvPicPr></pic:nvPicPr>'
        f'<pic:blipFill><a:blip r:embed="{rel_id}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
        "<pic:spPr>"
        f'<a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>'
        "</pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    )


def caption(text: str) -> str:
    return para(text, align="center", spacing='<w:spacing w:after="160"/>',
                size_half_pt=18, color="80868B")


# ─────────────────────────────────────────────────────────────────────────────
# OOXML 包装
# ─────────────────────────────────────────────────────────────────────────────

DOCUMENT_HEAD = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">'
    "<w:body>"
)

SECT_PR = (
    "<w:sectPr>"
    '<w:pgSz w:w="11906" w:h="16838"/>'
    '<w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" '
    'w:header="851" w:footer="992" w:gutter="0"/>'
    "</w:sectPr>"
)

STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="微软雅黑" w:cs="Calibri"/>
<w:sz w:val="21"/><w:szCs w:val="21"/>
</w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault>
</w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/>
<w:pPr><w:spacing w:before="0" w:after="60"/></w:pPr>
<w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b/><w:color w:val="1A1A1A"/><w:sz w:val="44"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/>
<w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="0"/>
<w:spacing w:before="360" w:after="140"/><w:pBdr>
<w:bottom w:val="single" w:sz="8" w:space="4" w:color="34A853"/></w:pBdr></w:pPr>
<w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b/><w:color w:val="188038"/><w:sz w:val="30"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/>
<w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="1"/><w:spacing w:before="240" w:after="100"/></w:pPr>
<w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b/><w:color w:val="202124"/><w:sz w:val="24"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/>
<w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/></w:pPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/></w:style>
</w:styles>
"""

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="png" ContentType="image/png"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>
"""

ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>
"""

APP_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
<Application>Sky Auto Player build_user_guide.py</Application>
</Properties>
"""


def core_xml(title: str, created: str, modified: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>{esc(title)}</dc:title>
<dc:creator>Sky Auto Player</dc:creator>
<cp:lastModifiedBy>Sky Auto Player</cp:lastModifiedBy>
<dcterms:created xsi:type="dcterms:W3CDTF">{created}</dcterms:created>
<dcterms:modified xsi:type="dcterms:W3CDTF">{modified}</dcterms:modified>
</cp:coreProperties>
"""


# ─────────────────────────────────────────────────────────────────────────────
# Markdown：把本地图片换成内嵌 base64
# ─────────────────────────────────────────────────────────────────────────────

IMG_MD = re.compile(r"!\[([^\]]*)\]\(([^)]+)\)")


def alt_of(tag: str) -> str:
    """取 <img> 标签里的 alt 文本（题注 / 无障碍说明用）。"""
    m = re.search(r'alt="([^"]*)"', tag, re.IGNORECASE)
    return m.group(1) if m else ""


def display_size(data: bytes) -> tuple[int, int]:
    """内嵌图在 md 里声明的显示尺寸：只用于回缩，绝不改数据本身。"""
    w, h = png_size(data)
    if w > DISPLAY_MAX_WIDTH:
        h = max(1, round(h * DISPLAY_MAX_WIDTH / w))
        w = DISPLAY_MAX_WIDTH
    return w, h


def img_tag(alt: str, uri: str) -> str:
    w, h = display_size(base64.b64decode(uri.split(",", 1)[1]))
    return f'<img alt="{alt}" width="{w}" height="{h}" src="{uri}">'


def to_img_tag(match: re.Match[str]) -> str:
    """把 ![](data:...) 统一改写成 <img> 标签（已内嵌的图）。"""
    return img_tag(match.group(1), match.group(2))


def embed_images(md: str) -> tuple[str, int]:
    """把本地图片引用换成 <img> 内嵌标签；已经内嵌过的（![]() 或 <img>）不再动。"""
    cached: dict[str, str] = {}

    def repl(match: re.Match[str]) -> str:
        alt, path = match.group(1), match.group(2)
        if path.startswith(("http://", "https://", "data:")):
            return match.group(0)
        full = os.path.join(ROOT, path)
        if not os.path.isfile(full):
            raise FileNotFoundError(f"md 里引用的图片不存在：{path}")
        if full not in cached:
            with open(full, "rb") as fh:
                data = fh.read()
            cached[full] = "data:image/png;base64," + base64.b64encode(data).decode("ascii")
        return img_tag(alt, cached[full])

    result, count = IMG_MD.subn(repl, md)
    # 顺手把历史上写下的 ![](data:...) 也统一成 <img>，保证只有一种写法。
    result, restyled = MD_IMG.subn(to_img_tag, result)
    return result, count + restyled


# ─────────────────────────────────────────────────────────────────────────────
# Markdown → 文档块
# ─────────────────────────────────────────────────────────────────────────────

HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
IMAGE_ONLY = re.compile(r"^!\[([^\]]*)\]\(([^)]+)\)\s*$")
TABLE_ROW = re.compile(r"^\|(.+)\|\s*$")
RULE = re.compile(r"^-{3,}$")


def split_cells(line: str) -> list[str]:
    inner = line.strip().strip("|")
    return [c.strip() for c in inner.split("|")]


def is_separator(line: str) -> bool:
    return bool(re.fullmatch(r"\|[\s:|-]+\|", line.strip()))


def parse_blocks(md_lines: list[str]) -> list[tuple[str, object]]:
    blocks: list[tuple[str, object]] = []
    i = 0
    while i < len(md_lines):
        raw = md_lines[i]
        line = raw.rstrip()
        stripped = line.strip()

        if not stripped:
            i += 1
            continue
        if RULE.match(stripped):
            i += 1
            continue

        m = IMAGE_ONLY.match(stripped)
        if m:
            blocks.append(("image", (m.group(1), m.group(2))))
            i += 1
            continue

        m = IMG_TAG.match(stripped)
        if m:
            # <img alt="..." src="data:..."> —— md 现在的内嵌写法
            blocks.append(("image", (alt_of(stripped), m.group(1))))
            i += 1
            continue

        m = HEADING.match(stripped)
        if m:
            level = len(m.group(1))
            blocks.append(("title" if level == 1 else "heading", m.group(2).strip()))
            i += 1
            continue

        if stripped.startswith("|") and i + 1 < len(md_lines) and is_separator(md_lines[i + 1]):
            header = split_cells(stripped)
            i += 2
            rows: list[list[str]] = []
            while i < len(md_lines) and md_lines[i].strip().startswith("|"):
                if not is_separator(md_lines[i]):
                    rows.append(split_cells(md_lines[i]))
                i += 1
            blocks.append(("table", (header, rows)))
            continue

        if stripped.startswith(">"):
            quote: list[str] = []
            while i < len(md_lines) and md_lines[i].strip().startswith(">"):
                quote.append(md_lines[i].strip()[1:].strip())
                i += 1
            blocks.append(("quote", " ".join(q for q in quote if q)))
            continue

        if stripped.startswith("- "):
            while i < len(md_lines) and md_lines[i].strip().startswith("- "):
                blocks.append(("bullet", md_lines[i].strip()[2:].strip()))
                i += 1
            continue

        para_lines = [stripped]
        i += 1
        while i < len(md_lines):
            nxt = md_lines[i].strip()
            if (not nxt or RULE.match(nxt) or HEADING.match(nxt) or nxt.startswith(("|", ">", "- "))
                    or IMAGE_ONLY.match(nxt) or IMG_TAG.match(nxt)):
                break
            para_lines.append(nxt)
            i += 1
        blocks.append(("para", " ".join(para_lines)))
    return blocks


def build_ooxml(md_lines: list[str]) -> tuple[str, list[tuple[str, bytes]], list[str]]:
    blocks = parse_blocks(md_lines)
    parts: list[str] = []
    media: list[tuple[str, bytes]] = []       # (zip 内路径, 内容)
    rels: list[str] = []
    drawing_id = 1
    image_index = 0
    captions = {
        # 键是 md 里的 alt 文本；图片已内嵌后拿不到原始文件名，只能按 alt 配题注。
        "悬浮控制条": "控制条浮在光遇之上的实际效果（横屏）",
        "曲库页": "曲库页",
        "校准页": "校准页",
        "设置页": "设置页",
        "无障碍未连接": "演奏页：无障碍服务未连接",
        "无障碍已连接": "演奏页：无障碍服务已连接",
    }

    for kind, payload in blocks:
        if kind == "title":
            parts.append(para(str(payload), style="Title"))
        elif kind == "heading":
            text = str(payload)
            parts.append(heading(text, 1 if re.match(r"^[一二三四五六七八九十]+、", text) else 2))
        elif kind == "para":
            parts.append(para(str(payload)))
        elif kind == "quote":
            parts.append(para(str(payload), indent='<w:ind w:left="360"/>',
                              size_half_pt=19, color="5F6368"))
        elif kind == "bullet":
            parts.append(bullet(str(payload)))
        elif kind == "table":
            header, rows = payload  # type: ignore[misc]
            parts.append(table(list(header), [list(r) for r in rows]))  # type: ignore[arg-type]
        elif kind == "image":
            alt, path = payload  # type: ignore[misc]
            # path 现在是内嵌的 data URI；只有还没内嵌过（刚加的新图）才会是文件路径。
            if path.startswith("data:image/png;base64,"):
                data = base64.b64decode(path.split(",", 1)[1])
                name = alt
            else:
                full = os.path.join(ROOT, path)
                if not os.path.isfile(full):
                    raise FileNotFoundError(f"md 里引用的图片不存在：{path}")
                with open(full, "rb") as fh:
                    data = fh.read()
                name = os.path.basename(path)
            image_index += 1
            part_name = f"image{image_index}.png"
            media.append((f"word/media/{part_name}", data))
            rel_id = f"rIdImg{image_index}"
            rels.append(
                f'<Relationship Id="{rel_id}" '
                'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" '
                f'Target="media/{part_name}"/>'
            )
            # 无障碍那两张图在 md 里各自成段（并排塞表格会让 Word 丢图），
            # 因此这里每张图独立成段并给出题注，顺序与 md 一致。
            parts.append(image(drawing_id, rel_id, name, png_size(data)))
            drawing_id += 1
            note = captions.get(name, alt)
            parts.append(caption(f"图 {image_index}　{note}"))

    document = DOCUMENT_HEAD + "".join(parts) + SECT_PR + "</w:body></w:document>"
    return document, media, rels


def write_docx(document: str, media: list[tuple[str, bytes]], rels: list[str],
               *, title: str, created: str, modified: str) -> None:
    doc_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rIdStyles" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" '
        'Target="styles.xml"/>'
        + "".join(rels)
        + "</Relationships>"
    )
    with zipfile.ZipFile(DOCX_PATH, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CONTENT_TYPES)
        z.writestr("_rels/.rels", ROOT_RELS)
        z.writestr("word/document.xml", document)
        z.writestr("word/styles.xml", STYLES)
        z.writestr("word/_rels/document.xml.rels", doc_rels)
        z.writestr("docProps/core.xml", core_xml(title, created, modified))
        z.writestr("docProps/app.xml", APP_XML)
        for name, data in media:
            z.writestr(name, data)


# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    import datetime

    now = datetime.datetime.now().astimezone()
    stamp = now.strftime("%Y-%m-%d %H:%M")
    created = now.strftime("%Y-%m-%dT%H:%M:%SZ")

    want_embed = "--embed" in sys.argv
    want_docx = "--docx" in sys.argv

    if not want_embed and not want_docx:
        # 默认什么都不做：md 现在就是相对路径写法，任何「自动整理」都可能把它变样。
        text = open(MD_PATH, encoding="utf-8").read()
        embedded_count = text.count("data:image/png;base64")
        local_count = len(re.findall(r"!\[[^\]]*\]\((?!data:|https?://)[^)]+\)", text))
        print("没有指定动作，未改动任何文件。可用参数：")
        print("  --embed  把 使用说明.md 的图片换成 <img>+base64 内嵌（会失去相对路径可读性）")
        print("  --docx   从 使用说明.md 生成 "
              f"{os.path.relpath(GEN_DOCX, ROOT)}（不写 {os.path.basename(USER_DOCX)}）")
        print(f"\n当前 {os.path.relpath(MD_PATH, ROOT)}："
              f"{os.path.getsize(MD_PATH) / 1024:.1f} KB，"
              f"内嵌 base64 {embedded_count} 处，相对路径图片 {local_count} 处")
        print(f"{os.path.basename(USER_DOCX)} 存在：{os.path.isfile(USER_DOCX)}（脚本永不写入它）")
        return

    with open(MD_PATH, "r", encoding="utf-8") as fh:
        source = fh.read()

    # 幂等：上一次写进去的提示块先摘掉，否则每跑一次就多一坨。
    source = re.sub(r"^(?:> [^\n]*\n)+\n?", "", source, count=1)
    # 源文件里的 HTML 注释是给维护者看的，不进产物
    source = re.sub(r"<!--.*?-->\s*", "", source, flags=re.S)

    if want_embed:
        # ① 原地把图片引用换成 <img> + base64，让 md 变成自包含文件（显式选择，非默认）
        embedded, count = embed_images(source)
        note = (
            f"{NOTE_PREFIX}。\n"
            f"> 生成时间：{stamp}（`python tools\\build_user_guide.py --embed`）\n\n"
        )
        with open(MD_PATH, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(note + embedded)
        remaining = len(re.findall(r"!?\[[^\]]*\]\((?!data:|https?://)", embedded))
        print(f"[--embed] 图片引用 -> 内嵌 <img>：{count} 张；剩余相对路径引用：{remaining} 处")
        print(f"[--embed] 已重写 {os.path.relpath(MD_PATH, ROOT)}"
              f"（{os.path.getsize(MD_PATH) / 1024:.0f} KB）")
        if remaining:
            raise SystemExit(f"仍有 {remaining} 处本地图片引用未内嵌，脚本行为不符合预期")
        source = embedded

    # ② docx 只在显式要求时生成，且**永不写 使用说明.docx**（那是人工维护的文档）。
    if want_docx:
        md_lines = [l for l in source.splitlines() if not l.startswith("> ")]
        document, media, rels = build_ooxml(md_lines)
        write_docx(document, media, rels, title="Sky Auto Player 使用说明",
                   created=created, modified=created)
        print(f"[--docx] 已生成 {os.path.relpath(GEN_DOCX, ROOT)}"
              f"（{os.path.getsize(GEN_DOCX) / 1024:.0f} KB，{len(media)} 张图已嵌入）")
        print(f"[--docx] {os.path.basename(USER_DOCX)} 未被触碰"
              f"（存在：{os.path.isfile(USER_DOCX)}）")


if __name__ == "__main__":
    main()
