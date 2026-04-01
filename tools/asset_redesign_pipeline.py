#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import re
import shutil
from dataclasses import dataclass
from datetime import datetime, UTC
from pathlib import Path

from PIL import Image, ImageChops, ImageEnhance, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parent.parent
EXTRACTED_DIR = ROOT / "extracted_images"
REDESIGNED_DIR = ROOT / "redesigned_assets"
ORIGINALS_DIR = REDESIGNED_DIR / "originals"
OUTPUT_DIR = REDESIGNED_DIR / "output"
MANIFEST_DIR = REDESIGNED_DIR / "manifests"
REPORT_DIR = REDESIGNED_DIR / "reports"
TILES_RC = ROOT / "src" / "main" / "resources" / "graphics" / "tiles.rc"
SPRITE_KIND_JAVA = ROOT / "src" / "main" / "java" / "micropolisj" / "engine" / "SpriteKind.java"
PIPELINE_VERSION = "neo_simcity_3d_v1"
TILE_SIZE = 16
UPSCALE_FACTOR = 4
GRAPHIC_TARGETS = {"graphics", "sprites"}
SPRITE_RE = re.compile(r"obj(?P<object_id>\d+)-(?P<frame>\d+)\.png$")
SPRITE_KIND_RE = re.compile(r"^\s*[A-Z]+\((?P<object_id>\d+),\s*(?P<num_frames>\d+)\)")
TILE_LAYER_RE = re.compile(r"([A-Za-z0-9_]+)@(\d+),(\d+)")


@dataclass(frozen=True)
class StyleSpec:
    saturation: float
    contrast: float
    brightness: float
    sharpness: float
    texture_sigma: float
    ao_strength: int
    highlight_strength: int
    shadow_strength: int
    specular_strength: int
    blur_radius: float


STYLE_SPECS = {
    "terrain": StyleSpec(1.18, 1.08, 1.03, 1.18, 9.0, 88, 60, 44, 20, 0.5),
    "infrastructure": StyleSpec(1.14, 1.18, 1.02, 1.28, 7.5, 92, 68, 58, 32, 0.35),
    "structure": StyleSpec(1.17, 1.16, 1.03, 1.24, 8.5, 96, 74, 62, 26, 0.45),
    "smoke": StyleSpec(1.08, 1.12, 1.05, 1.06, 10.0, 64, 36, 30, 8, 0.8),
    "animation": StyleSpec(1.16, 1.16, 1.04, 1.20, 8.5, 82, 54, 44, 18, 0.45),
    "sprite": StyleSpec(1.16, 1.18, 1.03, 1.24, 8.0, 96, 76, 64, 22, 0.55),
    "default": StyleSpec(1.15, 1.12, 1.03, 1.20, 8.0, 88, 64, 50, 18, 0.45),
}


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def ensure_dirs() -> None:
    for directory in [REDESIGNED_DIR, ORIGINALS_DIR, OUTPUT_DIR, MANIFEST_DIR, REPORT_DIR]:
        directory.mkdir(parents=True, exist_ok=True)
    for subdir in ["graphics", "sprites", "icons"]:
        (OUTPUT_DIR / subdir).mkdir(parents=True, exist_ok=True)
        (ORIGINALS_DIR / subdir).mkdir(parents=True, exist_ok=True)


def load_rgba(path: Path) -> Image.Image:
    with Image.open(path) as img:
        return img.convert("RGBA")


def parse_tiles_rc() -> dict[str, dict]:
    sheets: dict[str, dict] = {}
    for raw_line in TILES_RC.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue

        matches = TILE_LAYER_RE.findall(line)
        for sheet_name, x_str, y_str in matches:
            entry = sheets.setdefault(
                sheet_name,
                {"offsets": [], "used_cells": set(), "tile_refs": 0},
            )
            x = int(x_str)
            y = int(y_str)
            entry["offsets"].append([x, y])
            entry["used_cells"].add((x // TILE_SIZE, y // TILE_SIZE))
            entry["tile_refs"] += 1

    normalized: dict[str, dict] = {}
    for name, info in sheets.items():
        normalized[name] = {
            "offsets": sorted(info["offsets"]),
            "used_cells": sorted([list(item) for item in info["used_cells"]]),
            "tile_refs": info["tile_refs"],
        }
    return normalized


def parse_sprite_kinds() -> dict[int, int]:
    sprite_counts: dict[int, int] = {}
    for line in SPRITE_KIND_JAVA.read_text(encoding="utf-8").splitlines():
        match = SPRITE_KIND_RE.match(line)
        if match:
            sprite_counts[int(match.group("object_id"))] = int(match.group("num_frames"))
    return sprite_counts


def classify_graphics_asset(name: str) -> str:
    if name == "terrain":
        return "terrain"
    if name in {"roads", "roadwire", "rails", "wires", "traffic"}:
        return "infrastructure"
    if "smoke" in name:
        return "smoke"
    if name == "misc_animation":
        return "animation"
    return "structure"


def graphic_prompt(name: str, category: str) -> str:
    descriptions = {
        "terrain": "terrain, water, forest and disaster terrain cells",
        "infrastructure": "roads, rails, wires and layered traffic infrastructure",
        "structure": "civic and city-building atlas tiles",
        "smoke": "volumetric smoke and industrial plume animation frames",
        "animation": "animated utility and environmental effects",
    }
    return (
        "Modernize this SimCity-compatible sprite sheet into polished late-90s city-builder art "
        f"with stronger pseudo-3D depth, clean silhouettes, north-west lighting, readable shading, "
        f"and preserved 16x16 atlas alignment. Focus on {descriptions.get(category, 'city-building tiles')}."
    )


def sprite_prompt(object_id: int, frames: list[Path]) -> str:
    frame_count = len(frames)
    return (
        "Modernize this SimCity-compatible sprite animation into a richer late-90s city-builder look "
        "with more volume, cleaner material separation, north-west lighting and consistent animation silhouettes. "
        f"Keep exact frame count ({frame_count}) and canvas size for object series obj{object_id}."
    )


def build_manifest() -> dict:
    ensure_dirs()

    sheet_usage = parse_tiles_rc()
    sprite_counts = parse_sprite_kinds()

    graphics_entries = []
    for source_path in sorted((EXTRACTED_DIR / "graphics").glob("*.png")):
        image = load_rgba(source_path)
        name = source_path.stem
        category = classify_graphics_asset(name)
        graphics_entries.append(
            {
                "asset_type": "graphics",
                "name": name,
                "category": category,
                "source_path": rel(source_path),
                "original_copy_path": rel(ORIGINALS_DIR / "graphics" / source_path.name),
                "target_path": rel(OUTPUT_DIR / "graphics" / source_path.name),
                "size": list(image.size),
                "mode": image.mode,
                "cell_size": TILE_SIZE,
                "tile_refs": sheet_usage.get(name, {}).get("tile_refs", 0),
                "used_cells": sheet_usage.get(name, {}).get("used_cells", []),
                "prompt": graphic_prompt(name, category),
            }
        )

    sprite_frames: dict[int, list[Path]] = {}
    for source_path in sorted((EXTRACTED_DIR / "sprites").glob("obj*-*.png")):
        match = SPRITE_RE.match(source_path.name)
        if not match:
            continue
        object_id = int(match.group("object_id"))
        sprite_frames.setdefault(object_id, []).append(source_path)

    sprite_entries = []
    for object_id, frame_paths in sorted(sprite_frames.items()):
        expected_frames = sprite_counts.get(object_id)
        for frame_path in sorted(frame_paths):
            match = SPRITE_RE.match(frame_path.name)
            assert match is not None
            image = load_rgba(frame_path)
            sprite_entries.append(
                {
                    "asset_type": "sprite",
                    "object_id": object_id,
                    "frame": int(match.group("frame")),
                    "source_path": rel(frame_path),
                    "original_copy_path": rel(ORIGINALS_DIR / "sprites" / frame_path.name),
                    "target_path": rel(OUTPUT_DIR / "sprites" / frame_path.name),
                    "size": list(image.size),
                    "mode": image.mode,
                    "expected_frames": expected_frames,
                    "prompt": sprite_prompt(object_id, frame_paths),
                }
            )

    icon_entries = []
    for source_path in sorted((EXTRACTED_DIR / "icons").glob("*.png")):
        image = load_rgba(source_path)
        icon_entries.append(
            {
                "asset_type": "icon",
                "name": source_path.stem,
                "source_path": rel(source_path),
                "original_copy_path": rel(ORIGINALS_DIR / "icons" / source_path.name),
                "target_path": rel(OUTPUT_DIR / "icons" / source_path.name),
                "size": list(image.size),
                "mode": image.mode,
                "prompt": "Icons are copied unchanged in this first pass to keep the runtime footprint stable.",
            }
        )

    manifest = {
        "pipeline_version": PIPELINE_VERSION,
        "generated_at": datetime.now(UTC).isoformat(),
        "style_direction": "modern SimCity-inspired pseudo-3D refresh with preserved compatibility",
        "sources": {
            "tiles_rc": rel(TILES_RC),
            "sprite_kind_java": rel(SPRITE_KIND_JAVA),
            "extracted_images": rel(EXTRACTED_DIR),
        },
        "graphics": graphics_entries,
        "sprites": sprite_entries,
        "icons": icon_entries,
    }

    manifest_path = MANIFEST_DIR / "asset_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def duplicate_assets() -> None:
    ensure_dirs()
    for name in ["graphics", "sprites", "icons"]:
        source_dir = EXTRACTED_DIR / name
        dest_dir = ORIGINALS_DIR / name
        if dest_dir.exists():
            shutil.rmtree(dest_dir)
        shutil.copytree(source_dir, dest_dir)


def seeded_rng(seed_text: str) -> random.Random:
    seed = int(hashlib.sha256(seed_text.encode("utf-8")).hexdigest()[:16], 16)
    return random.Random(seed)


def make_noise(size: tuple[int, int], sigma: float, rng: random.Random) -> Image.Image:
    base = Image.effect_noise(size, sigma).convert("L")
    jitter = rng.randint(2, 12)
    return ImageEnhance.Contrast(base).enhance(1.0 + jitter / 20.0)


def make_directional_mask(size: tuple[int, int], exponent: float = 1.0) -> Image.Image:
    width, height = size
    mask = Image.new("L", size, 0)
    pixels = mask.load()
    for y in range(height):
        for x in range(width):
            diagonal = 1.0 - ((x / max(width - 1, 1)) + (y / max(height - 1, 1))) / 2.0
            value = int(max(0.0, min(1.0, diagonal ** exponent)) * 255)
            pixels[x, y] = value
    return mask


def tint_image(base: Image.Image, color: tuple[int, int, int], amount: int) -> Image.Image:
    overlay = Image.new("RGBA", base.size, (*color, amount))
    return Image.alpha_composite(base, overlay)


def apply_masked_tint(base: Image.Image, mask: Image.Image, color: tuple[int, int, int], amount: int) -> Image.Image:
    tinted = Image.new("RGBA", base.size, (*color, 0))
    tinted.putalpha(ImageEnhance.Brightness(mask).enhance(amount / 255.0))
    return Image.alpha_composite(base, tinted)


def alpha_shift(alpha: Image.Image, dx: int, dy: int) -> Image.Image:
    shifted = Image.new("L", alpha.size, 0)
    shifted.paste(alpha, (dx, dy))
    return shifted


def stylize_pixel_art(image: Image.Image, asset_key: str, spec: StyleSpec, alpha_sensitive: bool = True) -> Image.Image:
    original_size = image.size
    upscale_size = (original_size[0] * UPSCALE_FACTOR, original_size[1] * UPSCALE_FACTOR)
    rng = seeded_rng(asset_key)

    base = image.resize(upscale_size, Image.Resampling.NEAREST)
    alpha = base.getchannel("A")
    opaque_mask = alpha if alpha_sensitive else Image.new("L", base.size, 255)

    shaded = ImageEnhance.Color(base).enhance(spec.saturation)
    shaded = ImageEnhance.Contrast(shaded).enhance(spec.contrast)
    shaded = ImageEnhance.Brightness(shaded).enhance(spec.brightness)

    directional = make_directional_mask(base.size, exponent=0.9)
    reverse_directional = ImageOps.invert(directional)
    shaded = apply_masked_tint(shaded, directional, (244, 236, 215), spec.highlight_strength)
    shaded = apply_masked_tint(shaded, reverse_directional, (38, 44, 62), spec.shadow_strength)

    alpha_blur = opaque_mask.filter(ImageFilter.GaussianBlur(radius=spec.blur_radius))
    inset_shadow = ImageChops.subtract(alpha_blur, alpha_shift(alpha_blur, -2, -2))
    shaded = apply_masked_tint(shaded, inset_shadow, (0, 0, 0), spec.ao_strength)

    highlight_edge = ImageChops.subtract(alpha_shift(opaque_mask, 1, 1), opaque_mask)
    shadow_edge = ImageChops.subtract(alpha_shift(opaque_mask, -1, -1), opaque_mask)
    shaded = apply_masked_tint(shaded, highlight_edge.filter(ImageFilter.GaussianBlur(0.6)), (255, 248, 220), spec.highlight_strength + 20)
    shaded = apply_masked_tint(shaded, shadow_edge.filter(ImageFilter.GaussianBlur(0.8)), (18, 24, 40), spec.shadow_strength + 18)

    noise = make_noise(base.size, spec.texture_sigma, rng)
    noise_rgb = Image.merge("RGBA", (noise, noise, noise, opaque_mask))
    shaded = Image.blend(shaded, noise_rgb, 0.10)

    luma = shaded.convert("L")
    specular_mask = ImageChops.lighter(ImageEnhance.Contrast(luma).enhance(1.3), directional)
    shaded = apply_masked_tint(shaded, specular_mask, (255, 255, 240), spec.specular_strength)

    shaded = ImageEnhance.Sharpness(shaded).enhance(spec.sharpness)
    shaded.putalpha(alpha)
    return shaded.resize(original_size, Image.Resampling.LANCZOS)


def stylize_graphic_sheet(source_path: Path, category: str) -> Image.Image:
    image = load_rgba(source_path)
    spec = STYLE_SPECS.get(category, STYLE_SPECS["default"])
    output = Image.new("RGBA", image.size, (0, 0, 0, 0))

    for y in range(0, image.height, TILE_SIZE):
        for x in range(0, image.width, TILE_SIZE):
            cell = image.crop((x, y, min(x + TILE_SIZE, image.width), min(y + TILE_SIZE, image.height)))
            if cell.getbbox() is None:
                continue

            styled = stylize_pixel_art(cell, f"{source_path.stem}:{x}:{y}", spec, alpha_sensitive=True)
            if category == "smoke":
                alpha = styled.getchannel("A").filter(ImageFilter.GaussianBlur(radius=0.5))
                styled.putalpha(alpha)
            output.paste(styled, (x, y), styled)

    return output


def make_shadow(alpha: Image.Image, blur_radius: float, offset: tuple[int, int]) -> Image.Image:
    shadow = alpha_shift(alpha, offset[0], offset[1]).filter(ImageFilter.GaussianBlur(radius=blur_radius))
    shadow = ImageEnhance.Brightness(shadow).enhance(0.65)
    return shadow


def stylize_sprite(source_path: Path) -> Image.Image:
    image = load_rgba(source_path)
    spec = STYLE_SPECS["sprite"]
    stylized = stylize_pixel_art(image, source_path.stem, spec, alpha_sensitive=True)
    alpha = stylized.getchannel("A")
    shadow_mask = make_shadow(alpha, blur_radius=1.2, offset=(3, 3))

    shadow = Image.new("RGBA", stylized.size, (16, 20, 32, 0))
    shadow.putalpha(shadow_mask)
    composited = Image.alpha_composite(shadow, stylized)

    rim_mask = ImageChops.subtract(alpha_shift(alpha, 1, 1), alpha).filter(ImageFilter.GaussianBlur(radius=0.7))
    composited = apply_masked_tint(composited, rim_mask, (248, 240, 220), 58)
    composited.putalpha(composited.getchannel("A"))
    return composited


def render_assets(manifest: dict) -> dict:
    ensure_dirs()

    for icon_entry in manifest["icons"]:
        src = ROOT / icon_entry["source_path"]
        dst = ROOT / icon_entry["target_path"]
        shutil.copy2(src, dst)

    render_summary = {"graphics": [], "sprites": []}

    for entry in manifest["graphics"]:
        src = ROOT / entry["source_path"]
        dst = ROOT / entry["target_path"]
        rendered = stylize_graphic_sheet(src, entry["category"])
        rendered.save(dst)
        render_summary["graphics"].append(
            {
                "name": entry["name"],
                "source": entry["source_path"],
                "target": entry["target_path"],
                "size": entry["size"],
            }
        )

    for entry in manifest["sprites"]:
        src = ROOT / entry["source_path"]
        dst = ROOT / entry["target_path"]
        rendered = stylize_sprite(src)
        rendered.save(dst)
        render_summary["sprites"].append(
            {
                "name": Path(entry["source_path"]).name,
                "source": entry["source_path"],
                "target": entry["target_path"],
                "size": entry["size"],
            }
        )

    render_report_path = REPORT_DIR / "render_report.json"
    render_report_path.write_text(json.dumps(render_summary, indent=2), encoding="utf-8")
    return render_summary


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_outputs(manifest: dict) -> dict:
    results = {
        "pipeline_version": PIPELINE_VERSION,
        "validated_at": datetime.now(UTC).isoformat(),
        "graphics": [],
        "sprites": [],
        "icons": [],
        "summary": {},
    }
    failures: list[str] = []

    for collection_name in ["graphics", "sprites", "icons"]:
        for entry in manifest[collection_name]:
            src = ROOT / entry["source_path"]
            dst = ROOT / entry["target_path"]
            if not dst.exists():
                failures.append(f"Missing output: {entry['target_path']}")
                continue

            with Image.open(src) as src_img, Image.open(dst) as dst_img:
                src_rgba = src_img.convert("RGBA")
                dst_rgba = dst_img.convert("RGBA")
                record = {
                    "source_path": entry["source_path"],
                    "target_path": entry["target_path"],
                    "source_size": list(src_rgba.size),
                    "target_size": list(dst_rgba.size),
                    "source_mode": src_rgba.mode,
                    "target_mode": dst_rgba.mode,
                    "changed": file_digest(src) != file_digest(dst),
                }

                if src_rgba.size != dst_rgba.size:
                    failures.append(f"Size mismatch: {entry['target_path']}")
                if collection_name == "graphics":
                    if dst_rgba.width % TILE_SIZE != 0 or dst_rgba.height % TILE_SIZE != 0:
                        failures.append(f"Tile atlas misaligned: {entry['target_path']}")
                if collection_name == "sprites":
                    source_alpha_pixels = sum(src_rgba.getchannel("A").histogram()[1:])
                    target_alpha_pixels = sum(dst_rgba.getchannel("A").histogram()[1:])
                    if source_alpha_pixels > 0 and target_alpha_pixels == 0:
                        failures.append(f"Sprite lost alpha silhouette: {entry['target_path']}")

                results[collection_name].append(record)

    sprite_expectations = parse_sprite_kinds()
    actual_counts: dict[int, int] = {}
    for record in results["sprites"]:
        name = Path(record["target_path"]).name
        match = SPRITE_RE.match(name)
        if match:
            object_id = int(match.group("object_id"))
            actual_counts[object_id] = actual_counts.get(object_id, 0) + 1
    for object_id, expected_count in sprite_expectations.items():
        actual_count = actual_counts.get(object_id, 0)
        if actual_count != expected_count:
            failures.append(f"Sprite frame count mismatch for obj{object_id}: expected {expected_count}, got {actual_count}")

    changed_graphics = sum(1 for record in results["graphics"] if record["changed"])
    changed_sprites = sum(1 for record in results["sprites"] if record["changed"])
    results["summary"] = {
        "graphics_checked": len(results["graphics"]),
        "sprites_checked": len(results["sprites"]),
        "icons_checked": len(results["icons"]),
        "graphics_changed": changed_graphics,
        "sprites_changed": changed_sprites,
        "failures": failures,
    }

    report_path = REPORT_DIR / "validation_report.json"
    report_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
    return results


def build_preview_strip(entries: list[tuple[Path, Path]], title: str, destination: Path, tile_height: int = 96) -> None:
    if not entries:
        return

    margin = 16
    label_band = 28
    rows = []
    for before_path, after_path in entries:
        before = load_rgba(before_path)
        after = load_rgba(after_path)

        before = ImageOps.contain(before, (tile_height, tile_height))
        after = ImageOps.contain(after, (tile_height, tile_height))

        row_width = before.width + after.width + margin * 3
        row = Image.new("RGBA", (row_width, tile_height + label_band), (20, 24, 32, 255))
        row.paste(before, (margin, label_band))
        row.paste(after, (before.width + margin * 2, label_band))
        rows.append(row)

    canvas_width = max(row.width for row in rows)
    canvas_height = sum(row.height for row in rows) + label_band
    canvas = Image.new("RGBA", (canvas_width, canvas_height), (13, 17, 24, 255))

    y_cursor = label_band
    for row in rows:
        canvas.paste(row, (0, y_cursor))
        y_cursor += row.height

    canvas.save(destination)


def build_previews(manifest: dict) -> None:
    graphic_samples = []
    for name in ["terrain", "roads", "res_zones", "ind_zones", "airport", "misc_animation"]:
        source = EXTRACTED_DIR / "graphics" / f"{name}.png"
        target = OUTPUT_DIR / "graphics" / f"{name}.png"
        if source.exists() and target.exists():
            graphic_samples.append((source, target))

    sprite_samples = []
    for name in ["obj1-0.png", "obj2-0.png", "obj3-0.png", "obj5-0.png", "obj7-0.png"]:
        source = EXTRACTED_DIR / "sprites" / name
        target = OUTPUT_DIR / "sprites" / name
        if source.exists() and target.exists():
            sprite_samples.append((source, target))

    build_preview_strip(graphic_samples, "graphics", REPORT_DIR / "graphics_preview.png", tile_height=120)
    build_preview_strip(sprite_samples, "sprites", REPORT_DIR / "sprites_preview.png", tile_height=120)


def run_pipeline() -> dict:
    duplicate_assets()
    manifest = build_manifest()
    render_assets(manifest)
    validation = validate_outputs(manifest)
    build_previews(manifest)
    return validation


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build a compatible modernized asset line for SimCity assets.")
    parser.add_argument(
        "command",
        choices=["manifest", "duplicate", "render", "validate", "run"],
        help="Pipeline step to execute.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    ensure_dirs()

    if args.command == "manifest":
        manifest = build_manifest()
        print(json.dumps({"graphics": len(manifest["graphics"]), "sprites": len(manifest["sprites"]), "icons": len(manifest["icons"])}, indent=2))
        return

    if args.command == "duplicate":
        duplicate_assets()
        print(f"Duplicated extracted assets to {rel(ORIGINALS_DIR)}")
        return

    if args.command == "render":
        manifest_path = MANIFEST_DIR / "asset_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else build_manifest()
        render_assets(manifest)
        print(f"Rendered updated graphics and sprites into {rel(OUTPUT_DIR)}")
        return

    if args.command == "validate":
        manifest_path = MANIFEST_DIR / "asset_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else build_manifest()
        validation = validate_outputs(manifest)
        print(json.dumps(validation["summary"], indent=2))
        return

    validation = run_pipeline()
    print(json.dumps(validation["summary"], indent=2))


if __name__ == "__main__":
    main()
