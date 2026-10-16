#!/usr/bin/env python3
"""Render original geometric trace artwork for pipeline/debug playback, not detector accuracy."""
import argparse
import bisect
import csv
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.output.resolve() == args.trace.resolve() or args.output.exists():
        parser.error("Output must be a new file; existing files are never replaced")
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        raise SystemExit("NOT_RUN: renderer needs Pillow; trace evaluation does not")
    frames = {}
    with args.trace.open() as handle:
        for row in csv.DictReader(handle):
            time = int(row["time_us"])
            if row["record"] == "frame":
                frames[time] = {}
            elif row["record"] == "detection":
                frames[time][row["id"]] = tuple(float(row[k]) for k in ("left", "top", "right", "bottom"))
    times = sorted(frames)
    if len(times) < 2:
        parser.error("At least two frames are required")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    width, height, fps = 640, 360, 30
    command = ["ffmpeg", "-nostdin", "-v", "error", "-n", "-f", "rawvideo", "-pixel_format", "rgb24",
               "-video_size", f"{width}x{height}", "-framerate", str(fps), "-i", "pipe:0", "-an",
               "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p", str(args.output)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    try:
        for index in range(int((times[-1] - times[0]) * fps / 1_000_000) + 1):
            time = times[0] + index * 1_000_000 // fps
            left = max(0, bisect.bisect_right(times, time) - 1)
            right = min(left + 1, len(times) - 1)
            weight = 0 if left == right else (time - times[left]) / (times[right] - times[left])
            image = Image.new("RGB", (width, height), (11, 13, 18))
            draw = ImageDraw.Draw(image)
            for identity, a in frames[times[left]].items():
                b = frames[times[right]].get(identity, a)
                coords = [int((x + (y - x) * weight) * (width if n % 2 == 0 else height))
                          for n, (x, y) in enumerate(zip(a, b))]
                color = (197, 167, 113) if identity == "target" else (100, 170, 225)
                draw.rectangle(coords, outline=color, width=3)
                draw.text((coords[0], coords[1] + 4), identity, fill=color)
            draw.text((12, 12), "SYNTHETIC BOX TRACE - NOT REAL PEOPLE / DETECTOR VALIDATION", fill=(242, 243, 246))
            draw.text((12, height - 22), f"source PTS: {time / 1_000_000:.3f}s", fill=(169, 174, 184))
            process.stdin.write(image.tobytes())
    finally:
        process.stdin.close()
    if process.wait() != 0:
        raise SystemExit("FAIL: ffmpeg rendering failed")
    print(f"Created original synthetic geometric fixture: {args.output}")


if __name__ == "__main__":
    main()
