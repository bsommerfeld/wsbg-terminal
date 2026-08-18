#!/usr/bin/env python3
"""Regenerates every icon asset in the repo from ONE source: AppIcon.icon.

`.assets/icons/AppIcon.icon` is an Icon Composer document (open it with
Icon Composer.app from Xcode) - a plate colour plus one layer per glyph
component. macOS turns that into the Liquid-Glass icon: squircle, refraction,
specular rim, drop shadow. Everything below is derived from that render, so
the Dock icon, the Windows taskbar, the Linux launcher, the installer splash
and the startup intro all show the SAME artwork.

    python3 .script/build-icons.py              # re-export everything
    python3 .script/build-icons.py --relayer    # … after re-cutting the layers

Needs macOS 26+ with Xcode installed (the system icon renderer and `actool`
live there) plus Pillow and NumPy. Nothing here runs in CI - the generated
files are committed.

How the layers get out of the plate: the renderer has no transparent-plate
mode, so each layer is rendered twice, once on a black plate and once on a
white one, and the alpha falls out of the difference (C_white - C_black is
exactly the background that shows through). That keeps the glass shading AND
the drop shadow, which is what the intro animates.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import numpy as np
    from PIL import Image, ImageFilter
except ImportError:
    sys.exit("Pillow/NumPy fehlen - installiere sie mit: pip3 install pillow numpy")

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / ".assets/icons/AppIcon.icon"
RENDER = 1024               # the icon canvas Icon Composer works in
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
PLATE_MARGIN = 35 / 1024    # the slim margin the old icon kept around its plate
ICNS_SIZES = [("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64),
              ("128x128", 128), ("128x128@2x", 256), ("256x256", 256),
              ("256x256@2x", 512), ("512x512", 512), ("512x512@2x", 1024)]

# The plate does not fill the canvas: macOS keeps a margin for the drop
# shadow. Measured per render rather than hardcoded - it is the box of fully
# opaque pixels, since only the shadow outside it is translucent.
OPAQUE = 250


def call(cmd, **kw):
    # /usr/bin/python3 IS Xcode's python once Xcode is selected, and that one
    # exports an SDKROOT pointing at the Command Line Tools SDK - which then
    # loses against the Xcode toolchain ("this SDK is not supported by the
    # compiler"). The tools find their own SDK when nobody insists.
    env = {k: v for k, v in os.environ.items() if k != "SDKROOT"}
    return subprocess.run(cmd, capture_output=True, text=True, env=env, **kw)


def run(cmd, **kw):
    result = call(cmd, **kw)
    if result.returncode != 0:
        sys.exit(f"{cmd[0]} scheiterte:\n{result.stdout}{result.stderr}")
    return result


def build_helper(workdir):
    """Compiles the QuickLook render helper (see icon-render.swift)."""
    binary = workdir / "icon-render"
    run(["xcrun", "swiftc", "-O", str(ROOT / ".script/icon-render.swift"), "-o", str(binary)])
    return binary


def validate(icon_dir, workdir):
    """actool is the only thing that reports a broken icon.json - Icon
    Composer itself just refuses to open the document."""
    out = workdir / "actool-out"
    out.mkdir(exist_ok=True)
    result = call(
        ["xcrun", "actool", "--output-format", "human-readable-text", "--notices",
         "--warnings", "--app-icon", icon_dir.stem, "--compile", str(out),
         "--platform", "macosx", "--minimum-deployment-target", "26.0",
         "--target-device", "mac", "--output-partial-info-plist",
         str(out / "partial.plist"), str(icon_dir)])
    problems = [l for l in (result.stdout + result.stderr).splitlines()
                if "error" in l.lower()]
    if problems:
        sys.exit("icon.json ist kaputt:\n  " + "\n  ".join(problems))


def render(helper, icon_dir, workdir, tag):
    """Renders a .icon document into an RGBA image."""
    out = workdir / f"{tag}.png"
    result = run([str(helper), str(icon_dir), str(out), str(RENDER)])
    if result.stdout.strip() != f"{RENDER}x{RENDER}":
        sys.exit(f"Render lieferte {result.stdout.strip()}, erwartet {RENDER}x{RENDER}")
    return Image.open(out).convert("RGBA")


def variant(workdir, tag, keep=None, fill=None):
    """A copy of the source document with only `keep`'s layers left and,
    optionally, a flat plate colour. Every variant gets its own directory:
    QuickLook caches thumbnails per file, so reusing a path serves stale
    renders."""
    dst = workdir / tag / "AppIcon.icon"
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(SOURCE, dst)
    doc = json.loads((dst / "icon.json").read_text())
    if keep is not None:
        doc["groups"] = [g for g in doc["groups"]
                         if any(l["image-name"].startswith(keep) for l in g["layers"])]
        if not doc["groups"]:
            sys.exit(f"Kein Layer heißt {keep}* - Assets und icon.json driften auseinander")
    if fill is not None:
        colour = f"extended-srgb:{fill},{fill},{fill},1.0"
        doc["fill"] = {"linear-gradient": [colour, colour]}
        doc.pop("fill-specializations", None)
    (dst / "icon.json").write_text(json.dumps(doc, indent=2))
    return dst


def plate_box(image):
    """The squircle itself, without the shadow around it."""
    opaque = image.split()[3].point(lambda v: 255 if v >= OPAQUE else 0)
    box = opaque.getbbox()
    if box is None:
        sys.exit("Im Render ist keine Platte zu finden")
    return box


def matte(black, white, plate):
    """Alpha from the two plate renders; everything outside the plate is
    dropped (there both renders are empty, which the difference would read as
    fully opaque)."""
    b = np.asarray(black.convert("RGB"), dtype=np.float32)
    w = np.asarray(white.convert("RGB"), dtype=np.float32)
    inside = np.asarray(plate.split()[3], dtype=np.uint8) >= OPAQUE
    alpha = np.clip(1.0 - ((w - b) / 255.0).mean(axis=2), 0.0, 1.0)
    alpha[~inside] = 0.0
    alpha[alpha <= 0.004] = 0.0     # renderer noise on the empty plate
    # The black render is the premultiplied foreground.
    colour = np.clip(b / np.maximum(alpha, 0.004)[..., None], 0, 255)
    rgba = np.dstack([colour.astype(np.uint8), (alpha * 255).round().astype(np.uint8)])
    return Image.fromarray(rgba)


def crop_plate(image, box, inset=8):
    """Cuts the render down to the plate - full bleed, no shadow margin. The
    inset drops the plate's own antialiased rim, which the matte reads as a
    thin ring."""
    left, top, right, bottom = box
    return image.crop((left + inset, top + inset, right - inset, bottom - inset))


def drop_rim(image, plate, erode=44):
    """Clears the plate's own edge bevel out of a cut-out layer. That bevel is
    a ~35 px ring of half-lit glass along the border, and it belongs to the
    PLATE, not to the glyph - but the two-render difference cannot tell them
    apart. Eroding the plate mask follows the squircle, which a straight border
    band does not: at the corners the ring reaches much further inward. The
    glyph keeps a good 70 px of air to the edge, so nothing of it is at risk."""
    mask = plate.split()[3].point(lambda v: 255 if v >= OPAQUE else 0)
    for _ in range(erode // 4):
        mask = mask.filter(ImageFilter.MinFilter(9))    # 4 px per pass
    rgba = np.array(image)
    rgba[..., 3] = (rgba[..., 3] * (np.asarray(mask) // 255)).astype(np.uint8)
    return Image.fromarray(rgba)


def stone_mask(box):
    """A hard white silhouette of the diamond layer, in export coordinates.

    The exported stone carries its own drop shadow in the alpha channel. Good
    for the intro's drop, useless as a mask: a light sweep masked with it runs
    over the shadow too and smears. The layer image in the document knows the
    shape and nothing else - scaled and placed exactly like the render does."""
    alpha = Image.open(SOURCE / "Assets/diamond.png").convert("RGBA").split()[3]
    canvas = Image.new("L", (RENDER, RENDER), 0)
    canvas.paste(alpha.resize((box[2] - box[0], box[3] - box[1]), Image.LANCZOS),
                 (box[0], box[1]))
    silhouette = Image.new("RGBA", (RENDER, RENDER), (255, 255, 255, 0))
    silhouette.putalpha(canvas)
    return crop_plate(silhouette, box)


def content_box(image, threshold=6, pad=6):
    """The glyph's own box - alpha above the shadow's tail, plus a little air
    so the soft edge is not clipped."""
    alpha = np.asarray(image.split()[3])
    ys, xs = np.nonzero(alpha > threshold)
    if not len(xs):
        sys.exit("Layer ist leer")
    return (max(0, int(xs.min()) - pad), max(0, int(ys.min()) - pad),
            min(image.width, int(xs.max()) + 1 + pad),
            min(image.height, int(ys.max()) + 1 + pad))


def save(image, path, size=None):
    if size:
        image = image.resize((size, size), Image.LANCZOS)
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    print(f"  {path.relative_to(ROOT)}  {image.width}x{image.height}")


# --- authoring step ------------------------------------------------------
#
# The layer images inside AppIcon.icon are the source of truth and are edited
# in Icon Composer. This recipe only exists so the way they were cut from the
# old flat icon (.assets/icons/legacy) is not lost. It changes NOTHING about
# the artwork: same colours, same size, same spacing - the two components are
# only separated so each can be its own glass layer. The numbers are measured
# off legacy/AppIcon-fullbleed.png, where the glyph is 65.3 % of the plate
# wide and sits 2 % below its centre. Run with --relayer.
LEGACY = ROOT / ".assets/icons/legacy"
GLYPH_W = 669           # 65.3 % of the 1024 canvas, as in the old icon
GLYPH_Y = 21            # the mark hangs slightly low, as it always did
TONE = 0.85             # see below


def dim(image, factor):
    """Takes light out of a layer, alpha untouched.

    Glass is lit: the renderer lays its own gradient and specular highlights
    over every layer, which pushed the glyph about 19 % brighter than the old
    flat icon - the artwork looked recoloured when only the material had
    changed. Dimming the layer by the same amount hands that light back to the
    glass and puts the finished icon at the old brightness."""
    rgba = np.array(image).astype(np.float32)
    rgba[..., :3] *= factor
    return Image.fromarray(rgba.clip(0, 255).astype(np.uint8))


def relayer():
    """Rebuilds AppIcon.icon/Assets from the old flat icon."""
    for name in ("hands", "diamond"):
        layer = dim(Image.open(LEGACY / f"glyph-{name}.png").convert("RGBA"), TONE)
        size = (GLYPH_W, round(layer.height * GLYPH_W / layer.width))
        layer = layer.resize(size, Image.LANCZOS)
        # Both components come off ONE shared canvas and are placed by that
        # canvas, never by their own bounds - that is what keeps the stone in
        # the hands.
        canvas = Image.new("RGBA", (RENDER, RENDER), (0, 0, 0, 0))
        canvas.alpha_composite(layer, ((RENDER - size[0]) // 2,
                                       (RENDER - size[1]) // 2 + GLYPH_Y))
        canvas.save(SOURCE / "Assets" / f"{name}.png")
        print(f"  {name}.png  {size[0]}x{size[1]}")


def main():
    if not SOURCE.is_dir():
        sys.exit(f"{SOURCE} fehlt")
    if "--relayer" in sys.argv:
        print("Layer aus dem alten Logo neu schneiden …")
        relayer()
    with tempfile.TemporaryDirectory() as tmp:
        workdir = Path(tmp)
        print("Icon Composer document prüfen …")
        validate(SOURCE, workdir)
        helper = build_helper(workdir)

        print("Rendern …")
        # Through a copy, like every other render: QuickLook caches thumbnails
        # per document path and happily serves a stale one after the layers
        # underneath have changed - the plate colour updates, the artwork does
        # not. A path it has never seen cannot be cached.
        full = render(helper, variant(workdir, "full"), workdir, "full")
        box = plate_box(full)
        # No inset here: this crop decides how big the glyph ends up on
        # Windows and Linux, and it has to land on the old icon's proportions
        # exactly. The inset exists for the cut-out layers, not for the plate.
        fullbleed = crop_plate(full, box, inset=0)

        # Per-layer cut-outs for the intro and the installer splash.
        layers = {}
        for tag, keep in (("hands", "hands"), ("diamond", "diamond"), ("glyph", None)):
            black = render(helper, variant(workdir, tag + "-b", keep, "0"), workdir, tag + "-b")
            white = render(helper, variant(workdir, tag + "-w", keep, "1"), workdir, tag + "-w")
            cut = drop_rim(matte(black, white, black), black)
            layers[tag] = crop_plate(cut, box)

        print("Schreiben …")
        # --- macOS: plate with its shadow margin, the Dock expects that ---
        iconset = workdir / "AppIcon.iconset"
        iconset.mkdir()
        for name, size in ICNS_SIZES:
            full.resize((size, size), Image.LANCZOS).save(iconset / f"icon_{name}.png")
        icns = ROOT / ".assets/icons/AppIcon.icns"
        run(["iconutil", "-c", "icns", str(iconset), "-o", str(icns)])
        print(f"  {icns.relative_to(ROOT)}  {len(ICNS_SIZES)} Größen")

        # --- Windows/Linux: the plate, with the same slim margin the old
        #     icon carried; those two draw no spacing of their own.
        #     The plate is pasted at the size it was rendered at - blowing its
        #     870 px up to a round 1024 canvas is a 10 % upscale, and it shows:
        #     the glass edges and the thin facet lines go soft. The canvas
        #     follows the plate instead. ---
        inner = fullbleed.width
        side = round(inner / (1 - 2 * PLATE_MARGIN))
        flat = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        flat.alpha_composite(fullbleed, ((side - inner) // 2, (side - inner) // 2))
        save(flat, ROOT / ".assets/icons/AppIcon-fullbleed.png")
        flat.save(ROOT / ".assets/icons/AppIcon.ico",
                  sizes=[(s, s) for s in ICO_SIZES])
        print(f"  .assets/icons/AppIcon.ico  {len(ICO_SIZES)} Größen")
        save(full, ROOT / ".assets/icons/AppIcon.png")
        save(full, ROOT / ".assets/icons/icon_1024.png")

        # --- window icons (Swing, both apps) ---
        for module in ("launcher", "terminal"):
            save(flat, ROOT / module / "src/main/resources/images/app-icon.png")

        # --- installer splash: the glyph alone, no plate ---
        glyph = layers["glyph"]
        save(glyph.crop(content_box(glyph)),
             ROOT / "launcher/src/main/resources/images/logo-glyph.png")

        # --- startup intro: the two components on ONE shared canvas, so the
        #     CSS can animate them apart and they still line up ---
        hands, diamond = layers["hands"], layers["diamond"]
        union = merge_box(content_box(hands), content_box(diamond))
        intro = ROOT / "terminal/src/main/resources/web/icons"
        for tag, image in (("hands", hands), ("diamond", diamond)):
            save(image.crop(union), intro / f"intro-{tag}.png")
        save(stone_mask(box).crop(union), intro / "intro-diamond-mask.png")
        report_intro_geometry(hands.crop(union), diamond.crop(union))


def merge_box(a, b):
    return (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))


def solid_bottom(image):
    """Lowest row that still carries the shape itself rather than its shadow."""
    alpha = np.asarray(image.split()[3])
    rows = np.nonzero(alpha.max(axis=1) > 140)[0]
    return int(rows[-1]) if len(rows) else image.height


def report_intro_geometry(hands, diamond):
    """css/intro.css pins its landing points to the glyph inside these files -
    print them so a redesign does not silently drift out of the animation."""
    w, h = hands.size
    print("\ncss/intro.css - Werte für die Landepunkte:")
    print(f"  aspect-ratio      {w} / {h}")
    print(f"  Hände unten       {solid_bottom(hands) / h:.1%}")
    print(f"  Diamantspitze     {solid_bottom(diamond) / h:.1%}")


if __name__ == "__main__":
    main()
