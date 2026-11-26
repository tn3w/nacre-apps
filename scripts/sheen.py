#!/usr/bin/env python3
import argparse
import re
from pathlib import Path

SIZE = 256
PADDING = 12
DRIFT = 55
TONES = {"black": ("#000", "#fff"), "white": ("#fff", "#000")}

FILTER = """<filter id="sheen" filterUnits="userSpaceOnUse" x="-{drift}" y="-{drift}"
  width="{size}" height="{size}" color-interpolation-filters="sRGB">
  <feTurbulence type="fractalNoise" baseFrequency="0.0025 0.011" seed="7" result="flow"/>
  <feColorMatrix in="flow" type="matrix"
    values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  2.4 0 0 0 -0.7" result="ripple"/>
  <feComponentTransfer in="ripple" result="environment">
    <feFuncA type="table" tableValues="0 0 0 0.05 0.25 0.7 1 0.35 0 0 0.1 0.45 0.15 0 0"/>
  </feComponentTransfer>
  <feGaussianBlur in="SourceAlpha" stdDeviation="12" result="depth"/>
  <feColorMatrix in="depth" type="matrix"
    values="0 0 0 1 -0.5  0 0 0 1 -0.5  0 0 0 1 -0.5  0 0 0 0 1" result="normals"/>
  <feDisplacementMap in="environment" in2="normals" scale="-110" xChannelSelector="R"
    yChannelSelector="G" result="reflection"/>
  <feComponentTransfer in="depth" result="falloff">
    <feFuncA type="table" tableValues="1 1 1 1 1 0.95 0.7 0.35 0.1"/>
  </feComponentTransfer>
  <feComposite in="reflection" in2="falloff" operator="arithmetic" k1="1" result="bands"/>
  <feGaussianBlur in="bands" stdDeviation="1" result="silk"/>
  <feComponentTransfer in="silk" result="glow">
    <feFuncA type="table" tableValues="0 0.8 0.8"/>
  </feComponentTransfer>
  <feMorphology in="SourceAlpha" operator="erode" radius="4" result="inner"/>
  <feGaussianBlur in="inner" stdDeviation="1.5" result="safe"/>
  <feComposite in="glow" in2="safe" operator="in" result="mask"/>
  <feFlood flood-color="{light}"/>
  <feComposite in2="mask" operator="in" result="light"/>
  <feMerge><feMergeNode in="SourceGraphic"/><feMergeNode in="light"/></feMerge>
</filter>"""


ENVIRONMENT = FILTER.split('  <feGaussianBlur in="SourceAlpha"')[0] + """<feFlood flood-color="#fff"/>
  <feComposite in2="environment" operator="in"/>
</filter>"""


def read_glyph(path):
    text = path.read_text()
    view_box = [float(value) for value in re.search(r'viewBox="([^"]+)"', text)[1].split()]
    body = re.search(r"<svg[^>]*>(.*)</svg>", text, re.S)[1]
    return view_box, re.sub(r'\sfill="[^"]*"', "", body).strip()


def render(view_box, body, color, light, effect=FILTER):
    min_x, min_y, width, height = view_box
    scale = (SIZE - 2 * PADDING) / max(width, height)
    shift_x = (SIZE - width * scale) / 2 - min_x * scale - DRIFT
    shift_y = (SIZE - height * scale) / 2 - min_y * scale - DRIFT
    transform = f"translate({shift_x:g} {shift_y:g}) scale({scale:g})"
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {SIZE} {SIZE}">\n'
        f"<defs>{effect.format(size=SIZE, drift=DRIFT, light=light)}</defs>\n"
        f'<g transform="translate({DRIFT} {DRIFT})" filter="url(#sheen)">'
        f'<g fill="{color}" transform="{transform}">'
        f"{body}</g></g>\n</svg>\n"
    )


def collect(sources):
    for source in sources:
        yield from sorted(source.glob("*.svg")) if source.is_dir() else [source]


def main():
    parser = argparse.ArgumentParser(description="Apply the nacre sheen to glyphs.")
    parser.add_argument("sources", nargs="*", type=Path, default=[Path("icons")])
    parser.add_argument("-o", "--out", type=Path, default=Path("build/icons"))
    parser.add_argument("--environment", type=Path, help="write the reflection texture")
    arguments = parser.parse_args()

    if arguments.environment:
        square = '<rect width="24" height="24"/>'
        svg = render([0, 0, 24, 24], square, "#000", "#fff", ENVIRONMENT)
        arguments.environment.write_text(svg)
        return

    for path in collect(arguments.sources):
        view_box, body = read_glyph(path)
        for tone, (color, light) in TONES.items():
            target = arguments.out / tone / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(render(view_box, body, color, light))
            print(target)


if __name__ == "__main__":
    main()
