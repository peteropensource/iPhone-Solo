#!/usr/bin/env python3
"""Stamp SIMULATED HOME SCREEN onto a home-screen capture.

The label is drawn into the picture rather than laid over it, so that it folds, blurs and
dissolves with everything else. An overlay would float above a receding screen and give the
whole thing away.

Everything scales off the image width, so the iOS asset and the smaller web copy come out
looking like the same label rather than two guesses.

    python3 tools/label-sample.py <in> <out> [--y-fraction 0.63]
"""
import argparse
from PIL import Image, ImageDraw, ImageFont

TEXT = "SIMULATED HOME SCREEN"
FONT = "/System/Library/Fonts/HelveticaNeue.ttc"
FONT_INDEX = 1          # Bold
SS = 4                  # supersample, so the pill's curves survive the downsample

# All as a fraction of image width, measured on the 552px web copy that was tuned by eye.
FONT_FRAC     = 27 / 552
TRACKING_FRAC = 2.2 / 552
PAD_X_FRAC    = 20 / 552
PAD_Y_FRAC    = 11 / 552


def label(src: str, dst: str, y_fraction: float = 0.63) -> None:
    im = Image.open(src).convert("RGB")
    W, H = im.size
    layer = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    font = ImageFont.truetype(FONT, max(1, round(FONT_FRAC * W)) * SS, index=FONT_INDEX)
    tracking = TRACKING_FRAC * W * SS

    widths = [d.textlength(ch, font=font) for ch in TEXT]
    text_w = sum(widths) + tracking * (len(TEXT) - 1)
    ascent, descent = font.getmetrics()

    pad_x, pad_y = PAD_X_FRAC * W * SS, PAD_Y_FRAC * W * SS
    pill_w, pill_h = text_w + pad_x * 2, ascent + descent + pad_y * 2
    cx, top = W * SS / 2, y_fraction * H * SS
    box = (cx - pill_w / 2, top, cx + pill_w / 2, top + pill_h)

    # Dark slate on a pale wallpaper: maximum separation, and the shape reads as something
    # stamped on the picture rather than as part of it.
    d.rounded_rectangle(box, radius=pill_h / 2, fill=(14, 26, 32, 215))
    d.rounded_rectangle(box, radius=pill_h / 2, outline=(255, 255, 255, 70), width=max(1, round(1.2 * SS)))

    x = cx - text_w / 2
    y = top + pad_y - descent / 2
    for ch, cw in zip(TEXT, widths):
        d.text((x, y), ch, font=font, fill=(255, 255, 255, 245))
        x += cw + tracking

    layer = layer.resize((W, H), Image.LANCZOS)
    out = Image.alpha_composite(im.convert("RGBA"), layer).convert("RGB")
    if dst.lower().endswith(".png"):
        out.save(dst, optimize=True)
    else:
        out.save(dst, quality=88, optimize=True)
    print(f"{dst}: {W}x{H}, pill {pill_w/SS:.0f}x{pill_h/SS:.0f} at y={top/SS:.0f}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src"); ap.add_argument("dst")
    ap.add_argument("--y-fraction", type=float, default=0.63)
    a = ap.parse_args()
    label(a.src, a.dst, a.y_fraction)
