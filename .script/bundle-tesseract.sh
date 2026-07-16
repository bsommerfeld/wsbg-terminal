#!/bin/bash
# Builds a self-contained macOS Tesseract bundle from the local Homebrew install:
#   <out>/lib/       all dylibs of the closure, install names rewritten to @loader_path
#   <out>/tessdata/  eng + osd (from brew) + deu (downloaded from tesseract-ocr/tessdata)
# Every rewritten dylib is ad-hoc re-signed (mandatory on Apple Silicon).
set -euo pipefail

OUT="${1:?usage: bundle-tesseract.sh <output-dir>}"
rm -rf "$OUT"
mkdir -p "$OUT/lib" "$OUT/tessdata"

python3 - "$OUT" <<'EOF'
import os, subprocess, sys, shutil

out = sys.argv[1]
libdir = os.path.join(out, "lib")

def deps(path):
    """All /opt/homebrew and @rpath install-name entries of a dylib (as written)."""
    o = subprocess.run(["otool", "-L", path], capture_output=True, text=True).stdout
    return [l.strip().split(" (")[0] for l in o.splitlines()[1:]
            if l.strip().startswith(("/opt/homebrew", "@rpath/"))]

def resolve(dep):
    """Real file behind a dep entry; @rpath ones live in /opt/homebrew/lib."""
    if dep.startswith("@rpath/"):
        dep = "/opt/homebrew/lib/" + dep[len("@rpath/"):]
    return os.path.realpath(dep)

# --- 1. collect the /opt/homebrew closure (BFS) --------------------------------
seen = {}   # leaf -> realpath
queue = [os.path.realpath("/opt/homebrew/lib/libtesseract.dylib")]
while queue:
    lib = queue.pop(0)
    leaf = os.path.basename(lib)
    if leaf in seen:
        continue
    seen[leaf] = lib
    dst = os.path.join(libdir, leaf)
    shutil.copy2(lib, dst)
    os.chmod(dst, 0o755)
    for dep in deps(lib):
        real = resolve(dep)
        if os.path.basename(real) not in seen:
            queue.append(real)
print(f"closure: {len(seen)} dylibs")

# --- 2. rewrite install names to @loader_path, ad-hoc re-sign ------------------
for leaf in sorted(seen):
    f = os.path.join(libdir, leaf)
    subprocess.run(["install_name_tool", "-id", f"@loader_path/{leaf}", f],
                   capture_output=True)
    for dep in deps(f):
        depleaf = os.path.basename(resolve(dep))
        subprocess.run(["install_name_tool", "-change", dep,
                        f"@loader_path/{depleaf}", f], capture_output=True)
    r = subprocess.run(["codesign", "--force", "-s", "-", f], capture_output=True, text=True)
    if r.returncode != 0:
        print(f"SIGN FAILED: {leaf}: {r.stderr.strip()}"); sys.exit(1)

# JNA asks for "libtesseract.dylib" (unversioned) — provide that leaf name.
if not os.path.exists(os.path.join(libdir, "libtesseract.dylib")):
    versioned = [l for l in seen if l.startswith("libtesseract.")][0]
    shutil.copy2(os.path.join(libdir, versioned),
                 os.path.join(libdir, "libtesseract.dylib"))

# --- 3. verify: no /opt/homebrew reference may survive -------------------------
bad = False
for leaf in os.listdir(libdir):
    if deps(os.path.join(libdir, leaf)):
        print(f"LEAK: {leaf} still references /opt/homebrew"); bad = True
sys.exit(1 if bad else print("verify OK: bundle is self-contained") or 0)
EOF

# --- 4. runtime proof: the whole chain must dlopen from the bundle alone --------
python3 -c "import ctypes, sys; ctypes.CDLL(sys.argv[1])" "$OUT/lib/libtesseract.dylib"
echo "load OK: libtesseract dlopens self-contained"

# --- 5. traineddata --------------------------------------------------------------
# eng+osd from the brew install; deu pinned to the tessdata release tag so the
# bundle is reproducible (main moves, tags don't).
cp /opt/homebrew/share/tessdata/eng.traineddata "$OUT/tessdata/"
cp /opt/homebrew/share/tessdata/osd.traineddata "$OUT/tessdata/"
curl -fL --retry 3 -o "$OUT/tessdata/deu.traineddata" \
    "https://github.com/tesseract-ocr/tessdata/raw/4.1.0/deu.traineddata"

du -sh "$OUT" "$OUT/lib" "$OUT/tessdata"
ls "$OUT/lib"
