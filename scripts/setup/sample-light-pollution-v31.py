#!/usr/bin/env python3
import argparse
import csv
import math
import sys
from pathlib import Path

from osgeo import gdal

gdal.UseExceptions()


def parse_args():
    parser = argparse.ArgumentParser(
        description="Sample a nighttime-light raster at Wayward H3 tile points."
    )
    parser.add_argument("raster_path")
    parser.add_argument("points_csv")
    parser.add_argument("output_csv")
    parser.add_argument("--reference-max", type=float, default=100.0)
    parser.add_argument("--progress-interval", type=int, default=10000)
    parser.add_argument("--max-window-pixels", type=int, default=250_000_000)
    return parser.parse_args()


def invert_geotransform(geotransform):
    inverted = gdal.InvGeoTransform(geotransform)
    if inverted is None:
        raise RuntimeError("Raster geotransform is not invertible.")
    if isinstance(inverted, tuple) and len(inverted) == 2 and isinstance(inverted[1], tuple):
        ok, transform = inverted
        if not ok:
            raise RuntimeError("Raster geotransform is not invertible.")
        return transform
    return inverted


def to_darkness(raw_value, reference_max):
    if raw_value is None:
        return 0.5
    try:
        value = float(raw_value)
    except (TypeError, ValueError):
        return 0.5
    if math.isnan(value) or math.isinf(value):
        return 0.5
    brightness = max(0.0, value)
    return max(0.0, min(1.0, 1.0 - (brightness / reference_max)))


def read_points(points_csv, inv_gt, raster_width, raster_height):
    points = []
    min_x = raster_width
    min_y = raster_height
    max_x = -1
    max_y = -1

    with open(points_csv, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            lon = float(row["lon"])
            lat = float(row["lat"])
            pixel_x, pixel_y = gdal.ApplyGeoTransform(inv_gt, lon, lat)
            x = int(math.floor(pixel_x))
            y = int(math.floor(pixel_y))
            in_bounds = 0 <= x < raster_width and 0 <= y < raster_height
            points.append((row["h3_index"], x, y, in_bounds))
            if in_bounds:
                min_x = min(min_x, x)
                min_y = min(min_y, y)
                max_x = max(max_x, x)
                max_y = max(max_y, y)

    return points, min_x, min_y, max_x, max_y


def format_optional(value):
    if value is None:
        return ""
    return f"{float(value):.8f}"


def sample_from_window(band, points, bounds, nodata_value, args):
    min_x, min_y, max_x, max_y = bounds
    xoff = max(0, min_x - 1)
    yoff = max(0, min_y - 1)
    xsize = max_x - xoff + 2
    ysize = max_y - yoff + 2
    total_pixels = xsize * ysize

    if total_pixels > args.max_window_pixels:
        print(
            f"Raster window is {total_pixels:,} pixels; falling back to per-point reads.",
            flush=True,
        )
        return None

    print(
        f"Reading raster window x={xoff} y={yoff} width={xsize} height={ysize} "
        f"({total_pixels:,} pixels)...",
        flush=True,
    )
    array = band.ReadAsArray(xoff, yoff, xsize, ysize)
    if array is None:
        print("Window read returned no data; falling back to per-point reads.", flush=True)
        return None

    def read_value(x, y):
        if x < xoff or y < yoff or x >= xoff + xsize or y >= yoff + ysize:
            return None
        value = array[y - yoff, x - xoff]
        if nodata_value is not None and float(value) == float(nodata_value):
            return None
        return value

    return read_value


def sample_per_point(band, nodata_value):
    def read_value(x, y):
        array = band.ReadAsArray(x, y, 1, 1)
        if array is None:
            return None
        value = array[0, 0]
        if nodata_value is not None and float(value) == float(nodata_value):
            return None
        return value

    return read_value


def main():
    args = parse_args()
    if args.reference_max <= 0:
        raise RuntimeError("--reference-max must be greater than 0.")

    raster_path = Path(args.raster_path)
    points_csv = Path(args.points_csv)
    output_csv = Path(args.output_csv)

    dataset = gdal.Open(str(raster_path), gdal.GA_ReadOnly)
    if dataset is None:
        raise RuntimeError(f"Unable to open raster: {raster_path}")

    band = dataset.GetRasterBand(1)
    if band is None:
        raise RuntimeError(f"Raster has no band 1: {raster_path}")

    inv_gt = invert_geotransform(dataset.GetGeoTransform())
    width = dataset.RasterXSize
    height = dataset.RasterYSize
    nodata_value = band.GetNoDataValue()

    print(f"Raster size: {width}x{height}", flush=True)
    print(f"Reading point list: {points_csv}", flush=True)
    points, min_x, min_y, max_x, max_y = read_points(points_csv, inv_gt, width, height)
    print(f"Points loaded: {len(points):,}", flush=True)

    if not points:
        with open(output_csv, "w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["h3_index", "raw_value", "darkness_score"])
        return 0

    if max_x >= 0 and max_y >= 0:
        read_value = sample_from_window(
            band, points, (min_x, min_y, max_x, max_y), nodata_value, args
        )
    else:
        read_value = None

    if read_value is None:
        read_value = sample_per_point(band, nodata_value)

    changed = 0
    output_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(output_csv, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["h3_index", "raw_value", "darkness_score"])
        for index, (h3_index, x, y, in_bounds) in enumerate(points, start=1):
            raw_value = read_value(x, y) if in_bounds else None
            darkness = to_darkness(raw_value, args.reference_max)
            if darkness != 0.5:
                changed += 1
            writer.writerow([h3_index, format_optional(raw_value), f"{darkness:.8f}"])
            if args.progress_interval > 0 and index % args.progress_interval == 0:
                print(
                    f"Sampled {index:,}/{len(points):,} points; "
                    f"changed darkness={changed:,}",
                    flush=True,
                )

    print(
        f"Sampling complete. points={len(points):,} changed_darkness={changed:,} "
        f"output={output_csv}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr, flush=True)
        raise SystemExit(1)
