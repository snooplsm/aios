#!/usr/bin/env python3
"""Generate an ignored, locally licensed Soong model pack for AIOS."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import tarfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO, Iterable


ROOT = Path(__file__).resolve().parents[1]
ALLOWED_SUFFIXES = {".bin", ".gguf", ".litertlm", ".onnx", ".tflite"}
MODEL_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{0,127}")
DIGEST_PATTERN = re.compile(r"[0-9a-f]{64}")


class PackError(ValueError):
    pass


@dataclass(frozen=True)
class Source:
    model_id: str
    backend: str | None
    path: Path


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PackError(f"{path}: {exc}") from exc
    if not isinstance(value, dict):
        raise PackError(f"{path}: root must be an object")
    return value


def parse_source(value: str) -> Source:
    if "=" not in value:
        raise PackError("model source must use MODEL_ID[:BACKEND]=/absolute/path")
    model_selector, raw_path = value.split("=", 1)
    model_id, separator, backend = model_selector.partition(":")
    path = Path(raw_path)
    if not model_id or not path.is_absolute():
        raise PackError("model source must use MODEL_ID[:BACKEND]=/absolute/path")
    return Source(model_id=model_id, backend=backend if separator else None, path=path)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return sha256_stream(stream)


def sha256_stream(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    while block := stream.read(1024 * 1024):
        digest.update(block)
    return digest.hexdigest()


def module_name(model_id: str) -> str:
    value = "aios_model_" + re.sub(r"[^a-zA-Z0-9_]", "_", model_id)
    if not re.fullmatch(r"[a-zA-Z0-9_]+", value):
        raise PackError(f"cannot create Soong module name for {model_id}")
    return value


def bundle_members(model: dict[str, Any], source: Path) -> list[dict[str, Any]]:
    bundle = model.get("reference_bundle")
    if not isinstance(bundle, dict):
        raise PackError(f"{model['id']}: reference bundle is absent")
    if bundle.get("source_format") != "tar_bz2" or not source.name.endswith(".tar.bz2"):
        raise PackError(f"{model['id']}: source must be the pinned tar.bz2 bundle")
    if source.stat().st_size != bundle.get("size_bytes") or sha256(source) != bundle.get("sha256"):
        raise PackError(f"reference bundle digest or size mismatch for {model['id']}")
    root = bundle.get("archive_root")
    members = bundle.get("members")
    if not isinstance(root, str) or not root or "/" in root or "\\" in root:
        raise PackError(f"{model['id']}: unsafe archive root")
    if not isinstance(members, list) or not members:
        raise PackError(f"{model['id']}: bundle members are required")
    seen: set[str] = set()
    try:
        with tarfile.open(source, mode="r:bz2") as archive:
            by_name = {item.name.rstrip("/"): item for item in archive.getmembers()}
            for member in members:
                path = member.get("path")
                pure = PurePosixPath(path) if isinstance(path, str) else None
                if (pure is None or pure.parent != PurePosixPath(".")
                        or pure.name in {"", ".", ".."} or path in seen):
                    raise PackError(f"{model['id']}: unsafe or duplicate bundle member")
                seen.add(path)
                expected_size = member.get("size_bytes")
                expected_digest = member.get("sha256")
                if (not isinstance(expected_size, int) or expected_size <= 0
                        or not isinstance(expected_digest, str)
                        or not DIGEST_PATTERN.fullmatch(expected_digest)):
                    raise PackError(f"{model['id']}: incomplete bundle member lock: {path}")
                archive_path = f"{root}/{path}"
                info = by_name.get(archive_path)
                if info is None or not info.isfile() or info.issym() or info.islnk():
                    raise PackError(f"{model['id']}: missing regular bundle member: {path}")
                extracted = archive.extractfile(info)
                if extracted is None or info.size != expected_size:
                    raise PackError(f"{model['id']}: bundle member size mismatch: {path}")
                with extracted:
                    actual_digest = sha256_stream(extracted)
                if actual_digest != expected_digest:
                    raise PackError(f"{model['id']}: bundle member digest mismatch: {path}")
    except (tarfile.TarError, OSError) as exc:
        raise PackError(f"{model['id']}: cannot read reference bundle: {exc}") from exc
    return members


def validate_inputs(
        catalog: dict[str, Any], acceptance: dict[str, Any], sources: Iterable[Source]
) -> list[tuple[dict[str, Any], Source]]:
    if catalog.get("schema_version") != 1:
        raise PackError("unsupported model catalog schema")
    if acceptance.get("schema_version") != 1:
        raise PackError("unsupported license-acceptance schema")

    models = {item["id"]: item for item in catalog.get("models", [])}
    accepted_values = acceptance.get("accepted", [])
    accepted = {item["model_id"]: item for item in accepted_values}
    if len(accepted) != len(accepted_values):
        raise PackError("duplicate license-acceptance record")
    result = []
    seen: set[str] = set()
    seen_modules: set[str] = set()
    for source in sources:
        if source.model_id in seen:
            raise PackError(f"duplicate source for {source.model_id}")
        seen.add(source.model_id)
        if not MODEL_ID_PATTERN.fullmatch(source.model_id):
            raise PackError(f"invalid model ID: {source.model_id}")
        model = models.get(source.model_id)
        if model is None:
            raise PackError(f"unknown catalog model: {source.model_id}")
        record = accepted.get(source.model_id)
        if record is None:
            raise PackError(f"license acceptance missing for {source.model_id}")
        if record.get("license_url") != model.get("license_url"):
            raise PackError(f"license URL mismatch for {source.model_id}")
        if not record.get("accepted_at") or not record.get("accepted_by"):
            raise PackError(f"incomplete license acceptance for {source.model_id}")
        resolved = source.path.resolve(strict=True)
        if not resolved.is_file():
            raise PackError(f"model source is not a file: {resolved}")
        if model.get("reference_bundle") is not None:
            if "bundle" not in model.get("artifact_formats", []):
                raise PackError(f"catalog does not allow a bundle for {source.model_id}")
            bundle_members(model, resolved)
        else:
            suffix = resolved.suffix.lower()
            if suffix not in ALLOWED_SUFFIXES:
                raise PackError(f"unsupported model suffix for {source.model_id}: {suffix}")
            artifact_format = "ggml" if suffix == ".bin" else suffix.removeprefix(".")
            if artifact_format not in model.get("artifact_formats", []):
                raise PackError(f"catalog does not allow {suffix} for {source.model_id}")
            reference = model.get("reference_artifact")
            if reference is not None and sha256(resolved) != reference.get("sha256"):
                raise PackError(f"reference artifact digest mismatch for {source.model_id}")
        module = module_name(source.model_id)
        if module in seen_modules:
            raise PackError(f"Soong module-name collision: {module}")
        seen_modules.add(module)
        backend = source.backend or model.get("default_backend")
        if backend not in model.get("allowed_backends", []):
            raise PackError(f"catalog does not allow backend {backend!r} for {source.model_id}")
        result.append((model, Source(source.model_id, backend, resolved)))
    if not result:
        raise PackError("at least one model source is required")
    return result


def verify_generated_pack(output: Path) -> dict[str, Any]:
    manifest = read_json(output / "model_artifacts.json")
    if manifest.get("schema_version") != 1:
        raise PackError("unsupported generated artifact schema")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise PackError("generated artifact list is empty")
    seen: set[str] = set()
    assets = (output / "assets").resolve(strict=True)

    def verified_file(record: dict[str, Any], owner: str) -> Path:
        raw_relative = record.get("relative_path")
        if not isinstance(raw_relative, str) or "\\" in raw_relative:
            raise PackError(f"unsafe generated artifact path: {raw_relative!r}")
        relative = Path(raw_relative)
        if (relative.is_absolute() or len(relative.parts) < 2
                or relative.parts[0] != "models"
                or any(part in {"", ".", ".."} for part in relative.parts)):
            raise PackError(f"unsafe generated artifact path: {relative}")
        path = (assets / Path(*relative.parts[1:])).resolve(strict=True)
        if assets not in path.parents or not path.is_file():
            raise PackError(f"generated artifact escapes asset directory: {path}")
        if record.get("size_bytes", 0) <= 0 or path.stat().st_size != record["size_bytes"]:
            raise PackError(f"generated artifact size mismatch: {owner}")
        expected = record.get("sha256")
        if not isinstance(expected, str) or not DIGEST_PATTERN.fullmatch(expected) \
                or sha256(path) != expected:
            raise PackError(f"generated artifact digest mismatch: {owner}")
        return path

    for artifact in artifacts:
        model_id = artifact["model_id"]
        if model_id in seen:
            raise PackError(f"duplicate generated artifact: {model_id}")
        seen.add(model_id)
        path = verified_file(artifact, model_id)
        members = artifact.get("bundle_members")
        if members is not None:
            if not isinstance(members, list) or not members:
                raise PackError(f"generated bundle member list is empty: {model_id}")
            names: set[str] = set()
            for member in members:
                name = member.get("name")
                if not isinstance(name, str) or name in names:
                    raise PackError(f"duplicate generated bundle member: {model_id}/{name}")
                names.add(name)
                verified_file(member, f"{model_id}/{name}")
            descriptor = read_json(path)
            expected_descriptor = {
                "schema_version": 1,
                "model_id": model_id,
                "source_archive_sha256": artifact.get("source_archive_sha256"),
                "members": members,
            }
            if descriptor != expected_descriptor:
                raise PackError(f"generated bundle descriptor mismatch: {model_id}")
    return manifest


def generate_bundle(
        model: dict[str, Any], source: Source, assets: Path,
) -> tuple[dict[str, Any], list[str], list[str]]:
    locked_members = bundle_members(model, source.path)
    bundle = model["reference_bundle"]
    destination_dir = assets / source.model_id
    destination_dir.mkdir()
    descriptor_members: list[dict[str, Any]] = []
    blueprint_blocks: list[str] = []
    modules: list[str] = []
    with tarfile.open(source.path, mode="r:bz2") as archive:
        for index, locked in enumerate(locked_members):
            name = locked["path"]
            info = archive.getmember(f"{bundle['archive_root']}/{name}")
            extracted = archive.extractfile(info)
            if extracted is None:
                raise PackError(f"cannot extract locked bundle member: {name}")
            destination = destination_dir / name
            with extracted, destination.open("wb") as output_stream:
                shutil.copyfileobj(extracted, output_stream, 1024 * 1024)
            member = {
                "name": name,
                "relative_path": f"models/{source.model_id}/{name}",
                "size_bytes": destination.stat().st_size,
                "sha256": sha256(destination),
            }
            if (member["size_bytes"] != locked["size_bytes"]
                    or member["sha256"] != locked["sha256"]):
                raise PackError(f"copied bundle member failed verification: {name}")
            descriptor_members.append(member)
            member_module = module_name(f"{source.model_id}_bundle_{index}")
            modules.append(member_module)
            blueprint_blocks.append(
                "prebuilt_etc {\n"
                f"    name: \"{member_module}\",\n"
                f"    src: \"assets/{source.model_id}/{name}\",\n"
                f"    filename: \"{name}\",\n"
                f"    sub_dir: \"aios/models/{source.model_id}\",\n"
                "    product_specific: true,\n"
                "}\n"
            )

    descriptor = {
        "schema_version": 1,
        "model_id": source.model_id,
        "source_archive_sha256": bundle["sha256"],
        "members": descriptor_members,
    }
    descriptor_name = f"{source.model_id}.bundle.json"
    descriptor_path = assets / descriptor_name
    descriptor_path.write_text(
        json.dumps(descriptor, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    descriptor_module = module_name(source.model_id)
    modules.insert(0, descriptor_module)
    blueprint_blocks.insert(0,
        "prebuilt_etc {\n"
        f"    name: \"{descriptor_module}\",\n"
        f"    src: \"assets/{descriptor_name}\",\n"
        f"    filename: \"{descriptor_name}\",\n"
        "    sub_dir: \"aios/models\",\n"
        "    product_specific: true,\n"
        "}\n"
    )
    entry = {
        "model_id": source.model_id,
        "artifact_format": "bundle",
        "relative_path": f"models/{descriptor_name}",
        "sha256": sha256(descriptor_path),
        "size_bytes": descriptor_path.stat().st_size,
        "source_archive_sha256": bundle["sha256"],
        "bundle_members": descriptor_members,
        "runtime": model["runtime"],
        "backend": source.backend,
        "capabilities": model["capabilities"],
        "languages": model["languages"],
        "license_url": model["license_url"],
    }
    return entry, blueprint_blocks, modules


def generate(
        catalog_path: Path,
        acceptance_path: Path,
        sources: Iterable[Source],
        output: Path,
) -> dict[str, Any]:
    catalog = read_json(catalog_path)
    acceptance = read_json(acceptance_path)
    validated = validate_inputs(catalog, acceptance, sources)
    if output.exists() and any(output.iterdir()):
        raise PackError(f"output directory must be absent or empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    assets = output / "assets"
    assets.mkdir()

    manifest_entries = []
    blueprint_blocks = []
    modules = []
    for model, source in validated:
        if model.get("reference_bundle") is not None:
            entry, blocks, bundle_modules = generate_bundle(model, source, assets)
            manifest_entries.append(entry)
            blueprint_blocks.extend(blocks)
            modules.extend(bundle_modules)
            continue
        suffix = source.path.suffix.lower()
        destination_name = source.model_id + suffix
        destination = assets / destination_name
        shutil.copyfile(source.path, destination)
        digest = sha256(destination)
        module = module_name(source.model_id)
        modules.append(module)
        blueprint_blocks.append(
            "prebuilt_etc {\n"
            f"    name: \"{module}\",\n"
            f"    src: \"assets/{destination_name}\",\n"
            f"    filename: \"{destination_name}\",\n"
            "    sub_dir: \"aios/models\",\n"
            "    product_specific: true,\n"
            "}\n"
        )
        manifest_entries.append({
            "model_id": source.model_id,
            "artifact_format": "ggml" if suffix == ".bin" else suffix.removeprefix("."),
            "relative_path": f"models/{destination_name}",
            "sha256": digest,
            "size_bytes": destination.stat().st_size,
            "runtime": model["runtime"],
            "backend": source.backend,
            "capabilities": model["capabilities"],
            "languages": model["languages"],
            "license_url": model["license_url"],
        })

    artifact_manifest = {"schema_version": 1, "artifacts": manifest_entries}
    manifest_path = output / "model_artifacts.json"
    manifest_path.write_text(
        json.dumps(artifact_manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    blueprint_blocks.append(
        "prebuilt_etc {\n"
        "    name: \"aios_model_artifacts\",\n"
        "    src: \"model_artifacts.json\",\n"
        "    filename: \"model_artifacts.json\",\n"
        "    sub_dir: \"aios\",\n"
        "    product_specific: true,\n"
        "}\n"
    )
    (output / "Android.bp").write_text("\n".join(blueprint_blocks), encoding="utf-8")
    package_lines = ["PRODUCT_PACKAGES += \\"]
    all_modules = [*modules, "aios_model_artifacts"]
    for index, module in enumerate(all_modules):
        suffix = " \\" if index < len(all_modules) - 1 else ""
        package_lines.append(f"    {module}{suffix}")
    (output / "aios_model_pack.mk").write_text(
        "\n".join(package_lines) + "\n", encoding="utf-8"
    )
    return verify_generated_pack(output)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=ROOT / "config" / "model_catalog.json")
    parser.add_argument("--acceptance", type=Path, required=True)
    parser.add_argument("--source", action="append", default=[], type=parse_source)
    parser.add_argument("--output", type=Path, default=ROOT / "generated" / "modelpack")
    args = parser.parse_args(argv)
    try:
        manifest = generate(args.catalog, args.acceptance, args.source, args.output)
    except (OSError, PackError) as exc:
        print(f"model pack generation failed: {exc}", file=sys.stderr)
        return 1
    print(f"generated {len(manifest['artifacts'])} verified model artifact(s) at {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
