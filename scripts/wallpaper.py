#!/usr/bin/env python3
import argparse
from functools import partial
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

WIDTH, HEIGHT = 1224, 2720
INK = 0.25
TONES = {"dark": (0, 1), "light": (1, 0)}
LENS_Y = 186.5


def grid():
    y, x = np.mgrid[0:HEIGHT, 0:WIDTH].astype(float)
    return (x - WIDTH / 2) / HEIGHT, (y - HEIGHT / 2) / HEIGHT


def noise(rng, x, y, frequency, octaves=3, stretch=1.0, count=12):
    total = np.zeros_like(x)
    for octave in range(octaves):
        for _ in range(count):
            angle = rng.uniform(0, np.pi)
            wave = 2 * np.pi * frequency * 2**octave * rng.uniform(0.7, 1.3)
            ray = np.cos(angle) * x * stretch + np.sin(angle) * y
            total += np.sin(wave * ray + rng.uniform(0, 2 * np.pi)) / 2**octave
    return total / np.sqrt(count / 2)


def blur(values, radius):
    frequency_y = np.fft.fftfreq(values.shape[0])[:, None]
    frequency_x = np.fft.rfftfreq(values.shape[1])[None]
    kernel = np.exp(-2 * (np.pi * radius) ** 2 * (frequency_x**2 + frequency_y**2))
    return np.fft.irfft2(np.fft.rfft2(values) * kernel, values.shape)


def slopes(height):
    slope_y, slope_x = np.gradient(height, 1 / HEIGHT)
    return slope_x, slope_y


def swirl(rng, x, y, strength, frequency):
    for _ in range(2):
        x, y = (x + strength * noise(rng, x, y, frequency, octaves=2),
                y + strength * noise(rng, x, y, frequency, octaves=2))
    return x, y


def stripes(phase, count):
    return (1 + np.cos(2 * np.pi * count * phase)) / 2


def grating(rng, x, y):
    flow_x, flow_y = swirl(rng, x, y, 0.03, 0.8)
    phase = noise(rng, flow_x, flow_y, 0.3, octaves=2)
    drift = phase + 0.06 * noise(rng, x, y, 0.5, octaves=1)
    lines = stripes(phase, 22) * stripes(drift, 22)
    slope_x, slope_y = slopes(phase)
    glint = stripes((np.arctan2(slope_y, slope_x) - 0.8 + 3 * y) / (2 * np.pi), 1) ** 3
    return blur(lines**1.5 * glint, 0.8) * 0.95


def contour(rng, x, y):
    flow_x, flow_y = swirl(rng, x, y, 0.03, 0.8)
    field = 28 * noise(rng, flow_x, flow_y, 0.35, octaves=2)
    slope_x, slope_y = slopes(field)
    spacing = np.hypot(slope_x, slope_y) / HEIGHT + 1e-6
    lines = np.exp(-((np.abs(field - np.round(field)) / spacing / 1.8) ** 2))
    glint = stripes((np.arctan2(slope_y, slope_x) + 2 * y) / (2 * np.pi), 1) ** 2
    return lines * (0.3 + 0.65 * glint)


def nacre(rng, x, y):
    flow_x, flow_y = swirl(rng, x, y, 0.05, 0.6)
    waviness = noise(rng, flow_x, flow_y, 0.4, octaves=2, stretch=0.4)
    layers = 3 * flow_y + 0.5 * waviness
    shimmer = stripes(0.8 * layers + 0.9 * noise(rng, flow_x, flow_y, 0.5), 1) ** 2
    return blur(stripes(layers, 40) ** 4 * shimmer * 0.9 + 0.12 * shimmer, 0.6)


def phone(scale):
    unit = 2 * scale
    canvas = Image.new("L", (round(424 * unit) + 40, round(910 * unit) + 40))
    planes = Image.new("L", canvas.size)
    draw = ImageDraw.Draw(canvas)
    flat = ImageDraw.Draw(planes)
    line = round(2.4 * unit)

    def at(*points):
        origin = (293, 45)
        return [round((value - origin[index % 2]) * unit) + 20
                for index, value in enumerate(points)]

    def box(left, top, right, bottom, radius=0, fill=None):
        corners = at(left, top, right, bottom)
        draw.rounded_rectangle(corners, radius * unit, fill, 255, line)

    def ring(center_x, center_y, radius, fill=None):
        left, top = center_x - radius, center_y - radius
        box(left, top, center_x + radius, center_y + radius, radius, fill)

    def plane(level, left, top, right, bottom, radius=0):
        flat.rounded_rectangle(at(left, top, right, bottom), radius * unit, level)

    plane(40, 293, 45, 717, 955, 55)
    plane(90, 313, 65, 697, 345, 10)
    plane(70, 313, 350, 697, 940, 35)
    plane(140, 338, 105, 655, 268, 80)
    plane(200, 366, 131, 627, 242, 56)
    plane(120, 397, 149.5, 519, 223.5, 37)
    plane(120, 522, 149.5, 596, 223.5, 37)
    for center_x, radius in [(432, 30), (492, 18), (559, 31)]:
        left, right = center_x - radius, center_x + radius
        plane(60, left, LENS_Y - radius, right, LENS_Y + radius, radius)
    plane(160, 673, 140, 695, 296, 3)

    box(293, 45, 717, 955, 55)
    box(305, 57, 705, 943, 45)
    for button in [(286, 240, 293, 290), (286, 302, 293, 355), (286, 402, 293, 455),
                   (717, 272, 722, 300)]:
        box(*button, 3)

    draw.line(at(313, 348, 697, 348), 255, line)
    draw.line(at(313, 128, 435, 128, 435, 65), 255, line)
    box(398, 77, 410, 83, 3)
    box(338, 105, 655, 268, 80, fill=0)
    box(352, 118, 641, 255, 70)
    box(366, 131, 627, 242, 56)
    box(397, 149.5, 519, 223.5, 37)
    ring(559, LENS_Y, 37)
    lenses = [(432, 30), (432, 14), (492, 18), (492, 7), (559, 31), (559, 15)]
    for center_x, radius in lenses:
        ring(center_x, LENS_Y, radius)
    ring(535, 104, 21, fill=0)
    ring(535, 104, 9)

    for index in range(8):
        top = 140 + index * 19.5
        box(673, top, 695, top + 16, 2, fill=255 if index == 7 else None)
    for pad in [(413, 317, 475, 338), (523, 317, 583, 338), (588, 317, 620, 338)]:
        box(*pad, 6)

    screws = [(333, 95), (548, 76), (667, 93), (322, 218), (355, 330), (497, 330),
              (651, 330), (476, 857), (689, 858), (328, 908), (645, 925), (708, 520)]
    for center_x, center_y in screws:
        ring(center_x, center_y, 6)
        ring(center_x, center_y, 1.5, fill=255)
    holes = [(608, 272), (624, 254), (343, 268), (331, 292), (373, 302), (452, 78),
             (463, 78)]
    for hole in holes:
        ring(*hole, 2, fill=255)

    plate = Image.new("L", canvas.size)
    shape = ImageDraw.Draw(plate)
    shape.rectangle(at(335, 375, 655, 545), 255)
    shape.rectangle(at(435, 375, 655, 922), 255)
    shape.polygon(at(335, 520, 435, 555, 435, 640), 255)
    plate = plate.filter(ImageFilter.GaussianBlur(16 * unit))
    plate = plate.point(lambda value: 255 * (value > 128))
    inner = plate.filter(ImageFilter.MinFilter(line | 1))
    canvas.paste(255, mask=Image.fromarray(np.asarray(plate) - np.asarray(inner)))
    planes.paste(120, mask=plate)
    plane(90, 510, 588, 605, 830, 14)
    plane(150, 335, 850, 410, 895, 6)

    box(483, 455, 585, 490, 17)
    box(370, 405, 386, 437, 8)
    box(510, 588, 605, 830, 14)
    box(668, 360, 680, 385, 6)
    box(452, 665, 461, 775, 4)
    box(655, 665, 664, 775, 4)
    draw.line(at(553, 860, 600, 860), 255, line)
    for mark_x, mark_y in [(621, 432), (650, 518), (622, 597), (650, 597)]:
        draw.line(at(mark_x, mark_y, mark_x, mark_y + 8), 255, line)
    for index in range(4):
        box(382 + index * 10, 813, 387 + index * 10, 826, 2)
    box(335, 850, 410, 895, 6)
    ring(400, 858, 4)

    body = Image.new("L", canvas.size)
    ImageDraw.Draw(body).rounded_rectangle(at(293, 45, 717, 955), 55 * unit, 255)
    size = canvas.width // 2, canvas.height // 2
    layers = [np.asarray(layer.resize(size, Image.LANCZOS)) / 255
              for layer in (canvas, planes, body)]
    return [place(layer) for layer in layers]


def place(layer):
    full = np.zeros((HEIGHT, WIDTH))
    top = (HEIGHT - layer.shape[0]) // 2
    left = (WIDTH - layer.shape[1]) // 2
    full[top:top + layer.shape[0], left:left + layer.shape[1]] = layer
    return full


def reflect(rng, x, y, scene):
    rows, columns = np.indices(scene.shape)
    surface = round((INK + 0.5) * HEIGHT)
    depth = np.clip(rows - surface, 0, None)
    ripple = noise(rng, x, y * 6, 1.2, octaves=2, stretch=0.3) * (4 + depth * 0.04)
    source_rows = np.clip(surface - depth + ripple.astype(int), 0, HEIGHT - 1)
    source_columns = np.clip(columns + (ripple * 0.5).astype(int), 0, WIDTH - 1)
    mirror = scene[source_rows, source_columns] * 0.35 * np.exp(-depth / (0.08 * HEIGHT))
    return np.where(rows > surface, mirror, scene)


def emerge(rng, x, y, environment):
    lines, planes, body = phone(WIDTH * 0.66 / 424)
    planes = blur(planes, 3)
    edges = 0.3 * blur(lines, 2.5) + 0.1 * blur(lines, 8)
    height = 0.5 * blur(body, 40) + edges + 0.3 * planes
    slope_x, slope_y = slopes(height)
    shift_x = 0.0009 * slope_x - 0.02 * planes
    shift_y = 0.0009 * slope_y + 0.05 * planes
    light = environment(rng, x - shift_x, y - shift_y)
    curve = np.clip(np.hypot(slope_x, slope_y) / 70, 0, 1)
    glare = np.clip(-slope_y / 90, 0, 1) ** 2
    lift = 0.3 + 0.7 * np.clip((INK - y) / 0.35, 0, 1) ** 1.5
    scene = np.maximum(light * (0.65 + 0.6 * curve), 0.75 * glare) * body * lift
    return reflect(rng, x, y, np.where(y < INK, scene, 0))


BACKGROUNDS = {
    "grating": grating,
    "contour": contour,
    "nacre": nacre,
}
DESIGNS = BACKGROUNDS | {
    f"phone-{name}": partial(emerge, environment=environment)
    for name, environment in BACKGROUNDS.items()
}


def main():
    parser = argparse.ArgumentParser(description="Render nacre sheen wallpapers.")
    parser.add_argument("designs", nargs="*", default=[*DESIGNS])
    parser.add_argument("-s", "--seed", type=int, default=7)
    parser.add_argument("-o", "--out", type=Path, default=Path("wallpapers"))
    parser.add_argument("-t", "--tones", nargs="*", default=[*TONES])
    arguments = parser.parse_args()

    arguments.out.mkdir(parents=True, exist_ok=True)
    x, y = grid()
    for name in arguments.designs:
        rng = np.random.default_rng(arguments.seed)
        values = DESIGNS[name](rng, x, y) + rng.uniform(-0.5, 0.5, x.shape) / 255
        for tone in arguments.tones:
            background, ink = TONES[tone]
            shade = background + (ink - background) * np.clip(values, 0, 1)
            target = arguments.out / f"{name}-{arguments.seed}-{tone}.png"
            Image.fromarray(np.uint8(shade * 255)).save(target)
            print(target)


if __name__ == "__main__":
    main()
