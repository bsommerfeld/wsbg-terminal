#!/usr/bin/env python3
"""Builds the two Icon Composer documents in .assets/icons and every icon file
cut from them:

    AppIcon.icon      the terminal - two hands holding the diamond
    UpdateIcon.icon   the updater  - the same hands holding two circling arrows

An .icon document (open it with Icon Composer.app from Xcode) is a plate plus
one layer per glyph part; macOS turns it into the Liquid-Glass icon - squircle,
refraction, specular rim, drop shadow. Everything below is cut from that one
render, so the Dock, the Windows taskbar, the Linux launcher and an installer
all show the same artwork.

The look: the warm dark plate of the terminal frame, and the glyphs cast in
the marble orb - its storm, as fx-orb renders it, poured into the hands and
the stone. The shapes are white masks in .assets/icons/glyphs; this script
pours the storm into them and writes the documents' layers and icon.json, so
the documents are output: open them in Icon Composer to look, change the
look here.

    python3 .script/build-icons.py

Needs macOS 26+ with Xcode (the system icon renderer and `actool` live there),
Pillow, NumPy, SciPy and rsvg-convert (brew install librsvg). Nothing here runs in CI - the generated files are committed.

Written per document:
    .assets/icons/<Name>.icns       macOS bundle icon (jpackage --icon)
    .assets/icons/<Name>.ico        Windows (jpackage --icon)
    .assets/icons/<Name>.png        Linux (jpackage --icon)
    .assets/icons/dock/<Name>.png   the Dock icon of a plain `java` start
                                    (-Xdock:icon); shipped under images/
And for the terminal only, its window icons (taskbar on Windows and Linux)
and the startup intro's glyph - hands and diamond as one white mask, which the
intro pours the live storm into, and the glass sheen laid over it:
    fx-terminal/.../icon/AppIcon-<size>.png
    fx-terminal/.../intro/intro-glyph.png
    fx-terminal/.../intro/intro-sheen.png
"""

import io
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
    from scipy import ndimage
except ImportError:
    sys.exit("Pillow/NumPy/SciPy fehlen - installiere sie mit: pip3 install pillow numpy scipy")

ROOT = Path(__file__).resolve().parent.parent
ICONS = ROOT / ".assets/icons"
DOCUMENTS = ("AppIcon", "UpdateIcon")
WINDOW_ICONS = ROOT / "fx-terminal/src/main/resources/de/bsommerfeld/wsbg/terminal/icon"
WINDOW_SIZES = [16, 24, 32, 48, 64, 128, 256, 512]
INTRO = ROOT / "fx-terminal/src/main/resources/de/bsommerfeld/wsbg/terminal/intro"
RENDER = 1024               # the canvas Icon Composer works in
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
PLATE_MARGIN = 35 / 1024    # air around the plate where the OS draws none
ICNS_SIZES = [("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64),
              ("128x128", 128), ("128x128@2x", 256), ("256x256", 256),
              ("256x256@2x", 512), ("512x512", 512), ("512x512@2x", 1024)]

# The plate does not fill the canvas: macOS keeps a margin for the drop
# shadow. Measured per render - the box of fully opaque pixels, since only the
# shadow outside it is translucent.
OPAQUE = 250


# --- the orb, poured into the glyphs --------------------------------------
#
# The glyph layers are not drawn by hand: the shapes in .assets/icons/glyphs
# are white masks, and the fill is the marble orb's storm - the same noise
# texture, the same warp and cloud fields, the same colours as orb.frag,
# frozen at one moment and laid flat across the canvas.
ORB = ROOT / "fx-orb/src/main/resources/de/bsommerfeld/wsbg/orb"
NOISE_SIZE = 256

# OrbPalette.HERBST, the palette the terminal gives the orb: the storm's
# gradient deep to light at the stops orb.frag's palette() uses, and the cloud.
HERBST = [(0x23, 0x1c, 0x18), (0x5a, 0x40, 0x32), (0x9a, 0x6a, 0x3e), (0xe0, 0xa4, 0x58)]
HERBST_STOPS = [0.0, 0.45, 0.70, 1.0]
HERBST_CLOUD = (0xf4, 0xe6, 0xd0)

# orb.frag's constants.
SWIRL, FLOW_SPEED, CLOUD_SPEED = 0.12, 0.035, 0.09

STORM_TIME = 30.0   # which moment of the storm - picked by eye
STORM_SPAN = 1.0    # noise-texture widths across the canvas; the orb shows about this much
# The storm's deepest tone is the plate's colour; poured unchanged, whole
# stretches of a glyph vanish into the plate. Starting the gradient a quarter
# of the way up keeps every part of the outline readable.
STORM_FLOOR = 0.25

# Per document: its layers as (asset, glyph source, layer name) - the held
# glyph first, then the hands (compose() relies on that order).
LAYERS = {
    "AppIcon": [("diamond.png", "diamond.svg", "Diamant"), ("hands.png", "hands.svg", "Haende")],
    "UpdateIcon": [("update.png", "update.svg", "Update"), ("hands.png", "hands.svg", "Haende")],
}


def orb_noise():
    """The orb's four noise fields (base, swirl u/v, clouds), as the renderer
    uploads them: 16-bit, native byte order."""
    raw = np.fromfile(ORB / "orb-noise.rgba16", dtype="<u2")
    return raw.reshape(NOISE_SIZE, NOISE_SIZE, 4) / 65535.0


def sample(noise, u, v):
    """texture() with GL_LINEAR and GL_REPEAT, plus orb.frag's half-texel offset."""
    x = (u + 0.5 / NOISE_SIZE) * NOISE_SIZE - 0.5
    y = (v + 0.5 / NOISE_SIZE) * NOISE_SIZE - 0.5
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    fx, fy = (x - x0)[..., None], (y - y0)[..., None]
    x0, y0 = x0 % NOISE_SIZE, y0 % NOISE_SIZE
    x1, y1 = (x0 + 1) % NOISE_SIZE, (y0 + 1) % NOISE_SIZE
    top = noise[y0, x0] * (1 - fx) + noise[y0, x1] * fx
    bottom = noise[y1, x0] * (1 - fx) + noise[y1, x1] * fx
    return top * (1 - fy) + bottom * fy


def storm():
    """orb.frag's stormAt() on a flat sheet instead of a sphere: the base
    tone swirled by the two warp fields, clouds drifting over it, mixed in
    linear light and saturated the way the shader saturates."""
    noise, t = orb_noise(), STORM_TIME
    y, x = np.mgrid[0:RENDER, 0:RENDER] / RENDER
    lon, lat = (x - 0.5) * STORM_SPAN, (y - 0.5) * STORM_SPAN

    wu = sample(noise, lon * 0.8 + t * 0.02, lat * 0.8 - t * 0.013)[..., 1] - 0.5
    wv = sample(noise, lon * 0.8 - t * 0.017, lat * 0.8 + t * 0.021)[..., 2] - 0.5
    base = sample(noise, lon + wu * SWIRL * 2 + t * FLOW_SPEED, (lat + wv * SWIRL) * 1.6)[..., 0]
    clouds = sample(noise, lon * 1.2 + wu * SWIRL * 3 + t * CLOUD_SPEED, lat * 1.8 + wv * SWIRL * 2)[..., 3]
    edge = np.clip((clouds - 0.48) / 0.30, 0, 1)
    cloud = (edge * edge * (3 - 2 * edge))[..., None]      # smoothstep(0.48, 0.78)

    base = STORM_FLOOR + (1 - STORM_FLOOR) * base
    stops = np.array(HERBST) / 255
    tone = np.stack([np.interp(base, HERBST_STOPS, stops[:, c]) for c in range(3)], axis=-1)
    linear = tone ** 2.2 * (1 - cloud * 0.9) + (np.array(HERBST_CLOUD) / 255) ** 2.2 * cloud * 0.9
    luma = (linear[..., 0] * 0.2126 + linear[..., 1] * 0.7152 + linear[..., 2] * 0.0722)[..., None]
    linear = np.maximum(luma + (linear - luma) * 1.35, 0)
    return np.clip(linear ** (1 / 2.2), 0, 1)


def glyph_mask(source, scale=1):
    """The glyph's shape as an alpha channel on the canvas, RENDER * scale wide."""
    rendered = subprocess.run(["rsvg-convert", "-z", str(scale), str(ICONS / "glyphs" / source)],
                              capture_output=True)
    if rendered.returncode != 0:
        sys.exit(f"rsvg-convert scheiterte an {source} (brew install librsvg):\n"
                 f"{rendered.stderr.decode()}")
    image = Image.open(io.BytesIO(rendered.stdout))
    if image.size != (RENDER * scale, RENDER * scale):
        sys.exit(f"{source} ist {image.size}, erwartet {RENDER * scale}x{RENDER * scale}")
    return np.asarray(image.convert("RGBA"))[..., 3]


def pour(document, fill):
    """Writes the document's layer images: each glyph mask, filled with the
    storm. One storm for all layers, so it runs on across the gap between
    stone and hands as if both were cut from the same marble."""
    colour = (fill * 255).round().astype(np.uint8)
    for asset, source, _ in LAYERS[document.stem]:
        Image.fromarray(np.dstack([colour, glyph_mask(source)])).save(document / "Assets" / asset)


# The intro draws the glyph up to 460 logical pixels wide, 920 device pixels
# on a Retina screen; from the 1024 canvas the glyph would be ~680 and get
# blown up soft. Twice the canvas keeps its edges sharp.
INTRO_SCALE = 2


def intro_glyph():
    """The intro's three images, all cut to the same box:

    intro-glyph   hands and diamond as one white mask - the intro lays the
                  storm under it, so only the alpha matters; one mask for both
                  keeps them exactly where the icon has them
    intro-sheen   the icon's glass on that mask
    intro-silhouette  the same, but solid: the diamond with its facets filled -
                  the outline the intro's wave runs out from
    """
    hands, diamond = (glyph_mask(source, INTRO_SCALE) for source in ("hands.svg", "diamond.svg"))
    solid_diamond = ndimage.binary_fill_holes(diamond > 127)
    glyph = Image.fromarray(np.maximum(hands, diamond))
    silhouette = Image.fromarray(np.maximum(np.maximum(hands, diamond), solid_diamond * 255).astype(np.uint8))

    pad = 4 * INTRO_SCALE
    left, top, right, bottom = glyph.getbbox()
    box = (left - pad, top - pad, right + pad, bottom + pad)

    def white(alpha):
        image = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
        image.putalpha(alpha)
        return image.crop(box)

    save(white(glyph), INTRO / "intro-glyph.png")
    save(sheen(glyph.crop(box)), INTRO / "intro-sheen.png")
    save(white(silhouette), INTRO / "intro-silhouette.png")


# The glass the icon's layers are made of, for the intro: macOS lights the
# icon's glyphs from the upper left, so their edges catch light there and
# fall into shade on the far side. The intro has no system renderer, so the
# same thing is baked from the shape - a rounded bevel, lit from where the
# icon is lit.
SHEEN_BEVEL = 4 * INTRO_SCALE   # the bevel's width: blur sigma in image pixels
SHEEN_LIGHT = (-0.55, -0.83)
SHEEN_HIGHLIGHT, SHEEN_SHADE = 0.75, 0.35
SHEEN_HIGHLIGHT_INK, SHEEN_SHADE_INK = (255, 248, 236), (20, 14, 10)


def sheen(mask):
    """Highlight and shade along the glyph's edges, as one overlay. The
    blurred mask is a height field - high inside, falling off at the edge -
    and its slope says which way an edge faces."""
    inside = np.asarray(mask, dtype=np.float64) / 255
    height = np.asarray(mask.filter(ImageFilter.GaussianBlur(SHEEN_BEVEL)), dtype=np.float64) / 255
    dy, dx = np.gradient(height)
    slope = np.hypot(dx, dy)
    light = np.array(SHEEN_LIGHT) / np.hypot(*SHEEN_LIGHT)
    # The slope points inwards; an edge faces the light where it points away from it.
    facing = -(dx * light[0] + dy * light[1]) / (slope + 1e-9)
    edge = np.clip(slope / (slope.max() + 1e-9) * 1.6, 0, 1) * inside
    lit = np.clip(facing, 0, 1) ** 1.5 * edge * SHEEN_HIGHLIGHT
    shaded = np.clip(-facing, 0, 1) ** 1.5 * edge * SHEEN_SHADE

    ink = np.where((lit >= shaded)[..., None], SHEEN_HIGHLIGHT_INK, SHEEN_SHADE_INK)
    alpha = np.clip(lit + shaded, 0, 1) * 255
    return Image.fromarray(np.dstack([ink, alpha]).round().astype(np.uint8))


def compose(document):
    """icon.json: the warm dark plate of the terminal frame and one glass
    group per glyph layer, lit by the system (specular). The held glyph -
    stone or arrows - casts its shadow in its own colour, so it glows warm on
    the plate; the hands cast a neutral one and sit a step below it."""
    def colour(hex_colour):
        channels = (int(hex_colour[i:i + 2], 16) / 255 for i in (1, 3, 5))
        return "extended-srgb:" + ",".join(f"{c:.4f}" for c in channels) + ",1.0000"

    def group(asset, name, shadow):
        return {"layers": [{"image-name": asset, "name": name, "glass": True}],
                "shadow": shadow,
                "specular": True,
                "translucency": {"enabled": False, "value": 0.5}}

    (held, _, held_name), (hands, _, hands_name) = LAYERS[document.stem]
    icon = {"fill": {"linear-gradient": [colour("#443d38"), colour("#211d1a")]},
            "groups": [group(held, held_name, {"kind": "layer-color", "opacity": 0.7}),
                       group(hands, hands_name, {"kind": "neutral", "opacity": 0.45})],
            "supported-platforms": {"circles": ["watchOS"], "squares": "shared"}}
    (document / "icon.json").write_text(json.dumps(icon, indent=2) + "\n")


def call(cmd):
    # /usr/bin/python3 IS Xcode's python once Xcode is selected, and that one
    # exports an SDKROOT pointing at the Command Line Tools SDK - which then
    # loses against the Xcode toolchain ("this SDK is not supported by the
    # compiler"). The tools find their own SDK when nobody insists.
    env = {k: v for k, v in os.environ.items() if k != "SDKROOT"}
    return subprocess.run(cmd, capture_output=True, text=True, env=env)


def run(cmd):
    result = call(cmd)
    if result.returncode != 0:
        sys.exit(f"{cmd[0]} scheiterte:\n{result.stdout}{result.stderr}")
    return result


def validate(document, workdir):
    """actool is the only thing that reports a broken icon.json - Icon
    Composer itself just refuses to open the document."""
    out = workdir / f"actool-{document.stem}"
    out.mkdir()
    result = call(
        ["xcrun", "actool", "--output-format", "human-readable-text", "--notices",
         "--warnings", "--app-icon", document.stem, "--compile", str(out),
         "--platform", "macosx", "--minimum-deployment-target", "26.0",
         "--target-device", "mac", "--output-partial-info-plist",
         str(out / "partial.plist"), str(document)])
    problems = [line for line in (result.stdout + result.stderr).splitlines()
                if "error" in line.lower()]
    if problems:
        sys.exit(f"{document.name}/icon.json ist kaputt:\n  " + "\n  ".join(problems))


def render(helper, document, workdir):
    """Renders a .icon document into an RGBA image. Through a fresh copy:
    QuickLook caches thumbnails per path and happily serves a stale one after
    the layers underneath have changed."""
    copy = workdir / f"render-{document.stem}" / document.name
    shutil.copytree(document, copy)
    out = workdir / f"{document.stem}.png"
    result = run([str(helper), str(copy), str(out), str(RENDER)])
    if result.stdout.strip() != f"{RENDER}x{RENDER}":
        sys.exit(f"Render lieferte {result.stdout.strip()}, erwartet {RENDER}x{RENDER}")
    return Image.open(out).convert("RGBA")


def flat(full):
    """The plate alone with a slim margin, for Windows and Linux, which draw
    no spacing of their own. The canvas follows the plate: blowing its ~870 px
    up to a round 1024 would soften the glass edges."""
    alpha = full.split()[3].point(lambda v: 255 if v >= OPAQUE else 0)
    box = alpha.getbbox()
    if box is None:
        sys.exit("Im Render ist keine Platte zu finden")
    plate = full.crop(box)
    side = round(plate.width / (1 - 2 * PLATE_MARGIN))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.alpha_composite(plate, ((side - plate.width) // 2, (side - plate.height) // 2))
    return canvas


def save(image, path, size=None):
    if size:
        image = image.resize((size, size), Image.LANCZOS)
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    print(f"  {path.relative_to(ROOT)}  {image.width}x{image.height}")


def write(name, full, workdir):
    # macOS: the render as it is, shadow margin included - the Dock expects it
    iconset = workdir / f"{name}.iconset"
    iconset.mkdir()
    for label, size in ICNS_SIZES:
        full.resize((size, size), Image.LANCZOS).save(iconset / f"icon_{label}.png")
    icns = ICONS / f"{name}.icns"
    run(["iconutil", "-c", "icns", str(iconset), "-o", str(icns)])
    print(f"  {icns.relative_to(ROOT)}  {len(ICNS_SIZES)} Größen")
    save(full, ICONS / "dock" / f"{name}.png", 512)

    # Windows and Linux: the plate with its slim margin
    plate = flat(full)
    plate.save(ICONS / f"{name}.ico", sizes=[(s, s) for s in ICO_SIZES])
    print(f"  {(ICONS / f'{name}.ico').relative_to(ROOT)}  {len(ICO_SIZES)} Größen")
    save(plate, ICONS / f"{name}.png", 512)
    return plate


def main():
    fill = storm()
    with tempfile.TemporaryDirectory() as tmp:
        workdir = Path(tmp)
        helper = workdir / "icon-render"
        run(["xcrun", "swiftc", "-O", str(ROOT / ".script/icon-render.swift"), "-o", str(helper)])

        for name in DOCUMENTS:
            document = ICONS / f"{name}.icon"
            if not document.is_dir():
                sys.exit(f"{document} fehlt")
            print(f"{name}:")
            (document / "Assets").mkdir(parents=True, exist_ok=True)
            pour(document, fill)
            compose(document)
            validate(document, workdir)
            plate = write(name, render(helper, document, workdir), workdir)
            if name == "AppIcon":
                # The stage takes several sizes and each platform picks the
                # one closest to what it draws - a 256 scaled down to 16 by
                # the OS turns to mush.
                for size in WINDOW_SIZES:
                    save(plate, WINDOW_ICONS / f"AppIcon-{size}.png", size)
    intro_glyph()


if __name__ == "__main__":
    main()
