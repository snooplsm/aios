#!/usr/bin/env python3
"""Build the deterministic AIOS 1080x2424 boot animation from master artwork."""

from __future__ import annotations

import argparse
import io
import zipfile
from pathlib import Path

from PIL import Image


WIDTH = 1080
HEIGHT = 2424
FPS = 30
INTRO_FRAMES = 12


def render_master(path: Path, opacity: float) -> bytes:
    with Image.open(path) as source:
        source = source.convert("RGB")
        source.thumbnail((WIDTH, WIDTH), Image.Resampling.LANCZOS)
        layer = Image.new("RGB", (WIDTH, HEIGHT), "black")
        left = (WIDTH - source.width) // 2
        top = (HEIGHT - source.height) // 2
        layer.paste(source, (left, top))
        frame = Image.blend(Image.new("RGB", layer.size, "black"), layer, opacity)
        output = io.BytesIO()
        frame.save(output, format="PNG", optimize=True)
        return output.getvalue()


def build(emblem: Path, wordmark: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite {output}")
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr(
            "desc.txt",
            f"{WIDTH} {HEIGHT} {FPS}\np 1 0 part0\np 0 0 part1\n",
        )
        for index in range(INTRO_FRAMES):
            progress = (index + 1) / INTRO_FRAMES
            archive.writestr(
                f"part0/{index:05d}.png",
                render_master(emblem, progress * progress),
            )
        archive.writestr("part1/00000.png", render_master(wordmark, 1.0))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--emblem", type=Path, required=True)
    parser.add_argument("--wordmark", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    build(arguments.emblem, arguments.wordmark, arguments.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
