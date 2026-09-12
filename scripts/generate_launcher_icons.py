"""Render legacy launcher assets from the approved SVG (resvg-py 0.5.0).

Adaptive layers use the same paths as Android vectors; legacy viewBox crops the
108dp adaptive canvas to the central 72dp launcher mask. No fonts are required.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
import resvg_py

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/branding/yarumo-coach/logo-v3.svg"
root = ET.fromstring(SOURCE.read_text())
# The source group already translates by (-177, -124.5). With the outer
# (25.2, 25.2) translation this matches vector offset (13.872, 17.232).
mark = ET.tostring(root.find("{http://www.w3.org/2000/svg}g"), encoding="unicode")


def render(size, round_icon=False, store=False):
    shape = ('<circle cx="54" cy="54" r="36" fill="#121212"/>' if round_icon else
             f'<rect x="18" y="18" width="72" height="72" rx="{0 if store else 16}" fill="#121212"/>')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">'
           f'{shape}<g transform="translate(25.2 25.2) scale(.064)">{mark}</g></svg>')
    return resvg_py.svg_to_bytes(svg_string=svg, skip_system_fonts=True)


for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    folder = ROOT / f"app/src/main/res/mipmap-{density}"
    for rounded in (False, True):
        (folder / f'ic_launcher{"_round" if rounded else ""}.png').write_bytes(render(size, rounded))
(ROOT / "docs/branding/yarumo-coach/app-icon-source.png").write_bytes(render(512, store=True))
