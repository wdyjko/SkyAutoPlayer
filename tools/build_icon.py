#!/usr/bin/env python3
"""W16: builds the adaptive launcher icon from the source screenshot.

Idempotent: every constant that matters (crop box, safe-area sizes, palette,
blur) lives at the top, and re-running overwrites the same outputs. Change the
source image and re-run; nothing else needs editing.

Run from the project root:
    python tools/build_icon.py
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFilter

# ── constants ────────────────────────────────────────────────────────────────

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(PROJECT_ROOT, "tools", "_icon", "icon-source.jpg")
RES_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "res")

# W16.2 方案 1: keep both characters whole, then pad out to a square with the
# image's own sky/ground (mirror padding). The watermark sits at
# x 2215-2355 / y 1025-1041, far outside this box.
CROP_BOX = (380, 40, 1610, 1080)          # 1230 x 1040
# Subject (both characters) bounding box measured inside the crop.
SUBJECT_BOX = (28, 144, 1204, 1040)       # 1176 x 896

CANVAS_DP = 108                           # adaptive-icon canvas
SUBJECT_DP = 64                           # subject width on that canvas
BACKGROUND_BLUR = 0.05                    # blur radius, fraction of canvas
BACKGROUND_DIM = 0.78                     # brightness multiplier for the backdrop

DENSITY_SCALE = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
LEGACY_DP = 48                            # legacy launcher bitmap size
LEGACY_SCALE = dict(DENSITY_SCALE)

ADAPTIVE_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def log(message):
    print(message)


def load_square_source():
    """Crop the source and mirror-pad it into a square canvas."""
    src = Image.open(SOURCE).convert("RGB")
    log(f"source      : {SOURCE} {src.size[0]}x{src.size[1]}")

    crop = src.crop(CROP_BOX)
    side = max(crop.size)
    square = Image.new("RGB", (side, side))
    top_pad = (side - crop.size[1]) // 2
    # Mirror the outermost rows so the padding is made of the image's own sky
    # and ground instead of a hard colour block.
    top_strip = crop.crop((0, 0, crop.size[0], top_pad)).transpose(Image.FLIP_TOP_BOTTOM)
    bottom_h = side - crop.size[1] - top_pad
    bottom_strip = crop.crop(
        (0, crop.size[1] - bottom_h, crop.size[0], crop.size[1])
    ).transpose(Image.FLIP_TOP_BOTTOM)
    square.paste(top_strip, (0, 0))
    square.paste(crop, (0, top_pad))
    square.paste(bottom_strip, (0, top_pad + crop.size[1]))
    log(f"square      : crop={crop.size[0]}x{crop.size[1]} -> {side}x{side} (mirror padded)")

    subject_w = SUBJECT_BOX[2] - SUBJECT_BOX[0]
    image_ratio = side / float(subject_w)
    return square, image_ratio


def scaled(size, ratio):
    return max(1, int(round(size * ratio)))


def build_foreground(square, image_ratio, size_px):
    """Sharp subject, centred, on a transparent canvas."""
    image_dp = SUBJECT_DP * image_ratio
    image_px = scaled(image_dp, size_px / float(CANVAS_DP))
    layer = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
    art = square.resize((image_px, image_px), Image.LANCZOS).convert("RGBA")
    offset = (size_px - image_px) // 2
    layer.paste(art, (offset, offset))
    return layer


def build_background(square, size_px):
    """Full-bleed blurred backdrop so the mask never shows a hard frame."""
    radius = max(1.0, size_px * BACKGROUND_BLUR)
    blurred = square.resize((size_px, size_px), Image.LANCZOS).filter(
        ImageFilter.GaussianBlur(radius)
    )
    return blurred.point(lambda v: int(v * BACKGROUND_DIM)).convert("RGBA")


def build_legacy(square, image_ratio, size_px, circular):
    """Flat fallback bitmap for launchers that predate adaptive icons."""
    background = build_background(square, size_px).convert("RGB")
    foreground = build_foreground(square, image_ratio, size_px)
    flat = background.convert("RGBA")
    flat.alpha_composite(foreground)
    if circular:
        mask = Image.new("L", (size_px, size_px), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size_px - 1, size_px - 1), fill=255)
        flat.putalpha(mask)
    return flat


def write(image, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path)
    log(f"  wrote {os.path.relpath(path, PROJECT_ROOT)}  {image.size[0]}x{image.size[1]}")


def main():
    if not os.path.isfile(SOURCE):
        log(f"missing source image: {SOURCE}")
        log("pull it first: adb pull /sdcard/Download/vivo互传/图标.jpg tools/_icon/icon-source.jpg")
        return 1

    square, image_ratio = load_square_source()
    log(f"subject     : {SUBJECT_BOX[2] - SUBJECT_BOX[0]}x{SUBJECT_BOX[3] - SUBJECT_BOX[1]} px"
        f" -> {SUBJECT_DP}dp wide on a {CANVAS_DP}dp canvas")

    for density, scale in DENSITY_SCALE.items():
        canvas_px = scaled(CANVAS_DP, scale)
        folder = os.path.join(RES_DIR, f"mipmap-{density}")
        write(build_foreground(square, image_ratio, canvas_px),
              os.path.join(folder, "ic_launcher_foreground.png"))
        write(build_background(square, canvas_px),
              os.path.join(folder, "ic_launcher_background.png"))

    for density, scale in LEGACY_SCALE.items():
        legacy_px = scaled(LEGACY_DP, scale)
        folder = os.path.join(RES_DIR, f"mipmap-{density}")
        write(build_legacy(square, image_ratio, legacy_px, circular=False),
              os.path.join(folder, "ic_launcher.png"))
        write(build_legacy(square, image_ratio, legacy_px, circular=True),
              os.path.join(folder, "ic_launcher_round.png"))

    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(RES_DIR, "mipmap-anydpi-v26", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(ADAPTIVE_TEMPLATE)
        log(f"  wrote {os.path.relpath(path, PROJECT_ROOT)}")

    log("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
