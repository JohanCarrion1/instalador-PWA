#!/usr/bin/env python3
"""Prepara la plantilla :pwa para una PWA concreta.

Lee la página y su manifest (nombre, ícono, colores) y escribe:
  pwa/src/main/res/values/pwa.xml
  pwa/src/main/res/drawable-nodpi/ic_launcher_fg.png
Imprime (y escribe en $GITHUB_OUTPUT) el ID de paquete, el nombre y el nombre de archivo del APK.
"""
import argparse
import io
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from html import unescape
from pathlib import Path
from xml.sax.saxutils import escape

from PIL import Image, ImageDraw, ImageFont

UA = ("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/126.0.0.0 Mobile Safari/537.36")
ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "pwa" / "src" / "main" / "res"
SIZE = 432  # lienzo del ícono adaptativo (108dp x 4)
DEFAULT_COLOR = "#202124"


def get(url, limit=8_000_000):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "es,en;q=0.8"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.geturl(), r.read(limit)


def attr(tag, name):
    m = re.search(r"\b%s\s*=\s*(?:\"([^\"]*)\"|'([^']*)')" % name, tag, re.I)
    if not m:
        return None
    return unescape(m.group(1) if m.group(1) is not None else m.group(2))


def first_int(sizes):
    m = re.search(r"\d+", sizes or "")
    return int(m.group()) if m else 0


def hex_color(value):
    """Acepta #rgb / #rrggbb (ignora alfa). Devuelve '#RRGGBB' o None."""
    if not value:
        return None
    v = value.strip().lstrip("#")
    if re.fullmatch(r"[0-9a-fA-F]{3}", v):
        v = "".join(c * 2 for c in v)
    if re.fullmatch(r"[0-9a-fA-F]{6}([0-9a-fA-F]{2})?", v):
        return "#" + v[:6].upper()
    return None


def luminance(hexcol):
    r, g, b = (int(hexcol[i:i + 2], 16) for i in (1, 3, 5))
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255


def package_for(host):
    parts = []
    for p in reversed(host.lower().split(".")):
        p = re.sub(r"[^a-z0-9_]", "_", p)
        if not p:
            continue
        if not p[0].isalpha():
            p = "x" + p
        parts.append(p)
    return "pwa." + ".".join(parts)


def load_icon(url):
    try:
        _, data = get(url)
        return Image.open(io.BytesIO(data)).convert("RGBA")
    except Exception:
        return None


def letter_font(px):
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                 "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, px)
    try:
        return ImageFont.load_default(size=px)
    except TypeError:
        return ImageFont.load_default()


def compose(img, maskable, manifest_bg, theme, name):
    """Devuelve (foreground RGBA 432x432, color de fondo '#RRGGBB')."""
    fg = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    if img is None:
        bg = theme
        draw = ImageDraw.Draw(fg)
        letter = (name.strip()[:1] or "?").upper()
        font = letter_font(int(SIZE * 0.42))
        color = (0, 0, 0, 255) if luminance(bg) > 0.6 else (255, 255, 255, 255)
        draw.text((SIZE / 2, SIZE / 2), letter, font=font, fill=color, anchor="mm")
        return fg, bg

    r, g, b, a = img.getpixel((1, 1))
    if a > 200:
        bg = "#%02X%02X%02X" % (r, g, b)
    else:
        bg = manifest_bg or "#FFFFFF"

    target = SIZE if maskable else int(SIZE * 0.62)
    icon = img.copy()
    icon.thumbnail((target, target), Image.LANCZOS)
    fg.paste(icon, ((SIZE - icon.width) // 2, (SIZE - icon.height) // 2), icon)
    return fg, bg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--name", default="")
    ap.add_argument("--package", default="")
    args = ap.parse_args()

    page_url = args.url.strip()
    if "://" not in page_url:
        page_url = "https://" + page_url
    final_url, body = get(page_url)
    html = body.decode("utf-8", "replace")

    links = re.findall(r"<link\b[^>]*>", html, re.I)
    metas = re.findall(r"<meta\b[^>]*>", html, re.I)

    def rels(tag):
        return (attr(tag, "rel") or "").lower().split()

    name, start_url, theme, manifest_bg = None, final_url, None, None
    candidates = []  # (url, size, maskable)

    mref = next((attr(t, "href") for t in links if "manifest" in rels(t) and attr(t, "href")), None)
    if mref:
        try:
            murl = urllib.parse.urljoin(final_url, mref)
            _, mbody = get(murl)
            m = json.loads(mbody.decode("utf-8-sig", "replace"))
            name = (m.get("short_name") or m.get("name") or "").strip() or None
            if m.get("start_url"):
                start_url = urllib.parse.urljoin(murl, m["start_url"])
            theme = hex_color(m.get("theme_color"))
            manifest_bg = hex_color(m.get("background_color"))
            for ic in m.get("icons") or []:
                src = ic.get("src")
                if not src or "svg" in (ic.get("type") or "") or src.split("?")[0].lower().endswith(".svg"):
                    continue
                candidates.append((urllib.parse.urljoin(murl, src), first_int(ic.get("sizes")),
                                   "maskable" in (ic.get("purpose") or "")))
        except Exception as e:
            print(f"aviso: no se pudo leer el manifest ({e})", file=sys.stderr)

    if not theme:
        for t in metas:
            if (attr(t, "name") or "").lower() == "theme-color":
                theme = hex_color(attr(t, "content"))
                if theme:
                    break
    theme = theme or DEFAULT_COLOR

    if not name:
        m = re.search(r"<title[^>]*>(.*?)</title>", html, re.I | re.S)
        name = unescape(m.group(1)).strip() if m else ""
        name = name or urllib.parse.urlparse(final_url).hostname or "PWA"

    for t in links:
        r = rels(t)
        href = attr(t, "href")
        if href and ("icon" in r or "apple-touch-icon" in r):
            if "svg" in (attr(t, "type") or "") or href.split("?")[0].lower().endswith(".svg"):
                continue
            size = first_int(attr(t, "sizes"))
            if size == 0 and "apple-touch-icon" in r:
                size = 180
            candidates.append((urllib.parse.urljoin(final_url, href), size, False))
    candidates.append((urllib.parse.urljoin(final_url, "/favicon.ico"), 0, False))

    candidates.sort(key=lambda c: (not c[2], -c[1]))
    icon, maskable = None, False
    for url, _, mask in candidates:
        icon = load_icon(url)
        if icon is not None:
            maskable = mask
            print(f"ícono: {url} (maskable={mask})")
            break
    if icon is None:
        print("aviso: sin ícono utilizable, se genera uno con la inicial", file=sys.stderr)

    final_name = args.name.strip() or name
    fg, bg = compose(icon, maskable, manifest_bg, theme, final_name)

    host = urllib.parse.urlparse(start_url).hostname or "app"
    package = args.package.strip() or package_for(host)
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+", package):
        sys.exit(f"ID de paquete inválido: {package}")

    def xml_text(s):
        s = escape(s).replace("'", "\\'").replace('"', '\\"')
        return "\\" + s if s[:1] in "@?" else s

    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "values" / "pwa.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <string name="app_name">{xml_text(final_name)}</string>\n'
        f'    <string name="pwa_url">{xml_text(start_url)}</string>\n'
        f'    <color name="pwa_theme">{theme}</color>\n'
        f'    <color name="pwa_icon_bg">{bg}</color>\n'
        '</resources>\n', encoding="utf-8")
    (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    fg.save(RES / "drawable-nodpi" / "ic_launcher_fg.png")

    apk_name = re.sub(r"[^A-Za-z0-9._-]+", "-", final_name).strip("-") or "pwa"
    print(f"nombre={final_name}\nurl={start_url}\npaquete={package}\ntema={theme} fondo-icono={bg}")

    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"package={package}\nname={final_name}\napkname={apk_name}\n")


if __name__ == "__main__":
    main()
