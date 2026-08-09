#!/usr/bin/env python3
"""Generate an ignored, platform-resigned runtime-provider pack for AIOS."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DIGEST_PATTERN = re.compile(r"[0-9a-f]{64}")
COORDINATE_PATTERN = re.compile(r"[^:\s]+:[^:\s]+:[^:\s]+")


class PackError(ValueError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PackError(f"{path}: {exc}") from exc
    if not isinstance(value, dict):
        raise PackError(f"{path}: root must be an object")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def module_suffix(runtime: str) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9_]{0,63}", runtime):
        raise PackError(f"unsafe runtime ID: {runtime}")
    return runtime


def find_provider(catalog: dict[str, Any], runtime: str) -> dict[str, Any]:
    if catalog.get("schema_version") != 1:
        raise PackError("unsupported runtime catalog schema")
    matches = [item for item in catalog.get("providers", [])
               if item.get("runtime") == runtime]
    if len(matches) != 1:
        raise PackError(f"runtime must have exactly one catalog provider: {runtime}")
    return matches[0]


def validate_apk(path: Path, provider: dict[str, Any]) -> Path:
    resolved = path.resolve(strict=True)
    if not resolved.is_file() or resolved.suffix.lower() != ".apk":
        raise PackError("runtime provider must be an APK")
    try:
        with zipfile.ZipFile(resolved) as archive:
            names = set(archive.namelist())
            for notice in provider.get("required_apk_entries", []):
                entry_path = notice["path"]
                if entry_path not in names:
                    raise PackError(f"runtime provider APK lacks required notice: {entry_path}")
                payload = archive.read(entry_path)
                if len(payload) != notice["size_bytes"]:
                    raise PackError(f"runtime provider notice size mismatch: {entry_path}")
                if hashlib.sha256(payload).hexdigest() != notice["sha256"]:
                    raise PackError(f"runtime provider notice digest mismatch: {entry_path}")
    except zipfile.BadZipFile as exc:
        raise PackError("runtime provider is not a valid APK/ZIP") from exc
    required = {"AndroidManifest.xml", "classes.dex"}
    if not required.issubset(names):
        raise PackError("runtime provider APK lacks manifest or classes.dex")
    if not any(name.startswith("lib/arm64-v8a/") and name.endswith(".so")
               for name in names):
        raise PackError("runtime provider APK lacks arm64 native libraries")
    return resolved


def validate_provenance(
        provider: dict[str, Any], provenance: dict[str, Any]) -> list[dict[str, Any]]:
    if provenance.get("schema_version") != 1:
        raise PackError("unsupported runtime provenance schema")
    exact = {
        "runtime": provider["runtime"],
        "provider_package": provider["package"],
        "provider_service": provider["service_class"],
        "implementation_version": provider["implementation_version"],
        "source_repository": provider["source_repository"],
        "source_revision": provider["source_revision"],
    }
    for key, expected in exact.items():
        if provenance.get(key) != expected:
            raise PackError(f"runtime provenance mismatch for {key}")
    if not provenance.get("reproducible_build_command"):
        raise PackError("runtime provenance needs a reproducible build command")
    lock_digest = provenance.get("dependency_verification_sha256")
    if not isinstance(lock_digest, str) or not DIGEST_PATTERN.fullmatch(lock_digest):
        raise PackError("dependency verification file digest is required")

    resolved = provenance.get("resolved_dependencies")
    if not isinstance(resolved, list) or not resolved:
        raise PackError("resolved runtime dependencies are required")
    by_coordinate: dict[str, dict[str, Any]] = {}
    for item in resolved:
        coordinate = item.get("coordinate")
        digest = item.get("sha256")
        size = item.get("size_bytes")
        if (not isinstance(coordinate, str)
                or not COORDINATE_PATTERN.fullmatch(coordinate)
                or coordinate.endswith("-SNAPSHOT")):
            raise PackError(f"invalid resolved dependency: {coordinate!r}")
        if coordinate in by_coordinate:
            raise PackError(f"duplicate resolved dependency: {coordinate}")
        if not isinstance(digest, str) or not DIGEST_PATTERN.fullmatch(digest):
            raise PackError(f"missing dependency digest: {coordinate}")
        if not isinstance(size, int) or size <= 0:
            raise PackError(f"missing dependency size: {coordinate}")
        by_coordinate[coordinate] = item

    primary = provider.get("maven_artifact") or provider.get("binary_artifact")
    if primary is not None:
        primary_resolved = by_coordinate.get(primary["coordinate"])
        if (primary_resolved is None
                or primary_resolved["sha256"] != primary["sha256"]
                or primary_resolved["size_bytes"] != primary["size_bytes"]):
            raise PackError("primary Maven artifact does not match the catalog lock")
    elif not isinstance(provider.get("source_build"), dict):
        raise PackError("provider lacks a binary lock or native source-build lock")
    missing = set(provider["direct_dependencies"]) - by_coordinate.keys()
    if missing:
        raise PackError(f"runtime dependency closure misses direct dependencies: {sorted(missing)}")
    return resolved


def verify_generated_pack(output: Path) -> dict[str, Any]:
    manifest = read_json(output / "runtime_artifacts.json")
    if manifest.get("schema_version") != 1:
        raise PackError("unsupported generated runtime-artifact schema")
    artifact = manifest.get("provider_apk")
    if not isinstance(artifact, dict):
        raise PackError("generated runtime provider record is absent")
    relative = Path(artifact.get("relative_path", ""))
    if relative.parent != Path("runtime") or relative.suffix != ".apk":
        raise PackError("unsafe generated runtime-provider path")
    path = (output / "assets" / relative.name).resolve(strict=True)
    assets = (output / "assets").resolve(strict=True)
    if assets not in path.parents:
        raise PackError("generated runtime provider escapes the asset directory")
    if path.stat().st_size != artifact.get("size_bytes"):
        raise PackError("generated runtime provider size mismatch")
    if sha256(path) != artifact.get("sha256"):
        raise PackError("generated runtime provider digest mismatch")
    return manifest


def generate(
        catalog_path: Path,
        runtime: str,
        apk_path: Path,
        provenance_path: Path,
        output: Path,
) -> dict[str, Any]:
    catalog = read_json(catalog_path)
    provider = find_provider(catalog, runtime)
    provenance = read_json(provenance_path)
    dependencies = validate_provenance(provider, provenance)
    apk = validate_apk(apk_path, provider)
    suffix = module_suffix(runtime)
    if output.exists() and any(output.iterdir()):
        raise PackError(f"output directory must be absent or empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    assets = output / "assets"
    assets.mkdir()
    destination_name = f"aios-runtime-{suffix}.apk"
    destination = assets / destination_name
    shutil.copyfile(apk, destination)

    manifest = {
        "schema_version": 1,
        "runtime": runtime,
        "provider_package": provider["package"],
        "provider_service": provider["service_class"],
        "implementation_version": provider["implementation_version"],
        "source_repository": provider["source_repository"],
        "source_revision": provider["source_revision"],
        "license_spdx": provider["license_spdx"],
        "license_url": provider["license_url"],
        "dependency_verification_sha256": provenance["dependency_verification_sha256"],
        "resolved_dependencies": dependencies,
        "provider_apk": {
            "relative_path": f"runtime/{destination_name}",
            "size_bytes": destination.stat().st_size,
            "sha256": sha256(destination),
        },
    }
    (output / "runtime_artifacts.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    app_module = f"AiosRuntimeProvider_{suffix}"
    manifest_module = f"aios_runtime_artifacts_{suffix}"
    (output / "Android.bp").write_text(
        "android_app_import {\n"
        f"    name: \"{app_module}\",\n"
        f"    apk: \"assets/{destination_name}\",\n"
        "    certificate: \"platform\",\n"
        "    privileged: true,\n"
        "    product_specific: true,\n"
        "}\n\n"
        "prebuilt_etc {\n"
        f"    name: \"{manifest_module}\",\n"
        "    src: \"runtime_artifacts.json\",\n"
        f"    filename: \"runtime_artifacts-{suffix}.json\",\n"
        "    sub_dir: \"aios\",\n"
        "    product_specific: true,\n"
        "}\n",
        encoding="utf-8",
    )
    (output / "aios_runtime_pack.mk").write_text(
        "PRODUCT_PACKAGES += \\\n"
        f"    {app_module} \\\n"
        f"    {manifest_module}\n",
        encoding="utf-8",
    )
    return verify_generated_pack(output)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path,
                        default=ROOT / "config" / "runtime_catalog.json")
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--provenance", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        output = args.output or ROOT / "generated" / "runtimepack" / args.runtime
        manifest = generate(
            args.catalog, args.runtime, args.apk, args.provenance, output)
    except (OSError, PackError) as exc:
        print(f"runtime pack generation failed: {exc}", file=sys.stderr)
        return 1
    print("generated verified runtime provider "
          f"{manifest['runtime']} at {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
