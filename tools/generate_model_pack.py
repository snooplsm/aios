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


@dataclass(frozen=True)
class LicenseSource:
    model_id: str
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


def parse_license_file(value: str) -> LicenseSource:
    if "=" not in value:
        raise PackError("model license must use MODEL_ID=/absolute/path")
    model_id, raw_path = value.split("=", 1)
    path = Path(raw_path)
    if not model_id or not path.is_absolute():
        raise PackError("model license must use MODEL_ID=/absolute/path")
    return LicenseSource(model_id=model_id, path=path)


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


def licenses_property(license_module: str | None) -> str:
    return (f"    licenses: [\"{license_module}\"],\n"
            if license_module is not None else "")


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
        catalog: dict[str, Any], acceptance: dict[str, Any], sources: Iterable[Source],
        license_files: Iterable[LicenseSource] = (),
) -> list[tuple[dict[str, Any], Source, Path | None]]:
    if catalog.get("schema_version") != 1:
        raise PackError("unsupported model catalog schema")
    if acceptance.get("schema_version") != 1:
        raise PackError("unsupported license-acceptance schema")

    models = {item["id"]: item for item in catalog.get("models", [])}
    accepted_values = acceptance.get("accepted", [])
    accepted = {item["model_id"]: item for item in accepted_values}
    if len(accepted) != len(accepted_values):
        raise PackError("duplicate license-acceptance record")
    license_by_model: dict[str, LicenseSource] = {}
    for license_source in license_files:
        if license_source.model_id in license_by_model:
            raise PackError(f"duplicate model license for {license_source.model_id}")
        license_by_model[license_source.model_id] = license_source
    result = []
    seen: set[str] = set()
    seen_modules: set[str] = set()
    source_digests: dict[Path, str] = {}

    def source_digest(path: Path) -> str:
        digest = source_digests.get(path)
        if digest is None:
            digest = sha256(path)
            source_digests[path] = digest
        return digest

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
        license_lock = model.get("packaged_license")
        license_source = license_by_model.get(source.model_id)
        resolved_license: Path | None = None
        if license_lock is not None:
            if (not isinstance(license_lock, dict)
                    or not re.fullmatch(r"[a-zA-Z0-9._-]+\.txt",
                                        str(license_lock.get("filename", "")))
                    or not isinstance(license_lock.get("size_bytes"), int)
                    or license_lock["size_bytes"] <= 0
                    or not isinstance(license_lock.get("sha256"), str)
                    or not DIGEST_PATTERN.fullmatch(license_lock["sha256"])
                    or not isinstance(license_lock.get("soong_license_kinds"), list)
                    or not license_lock["soong_license_kinds"]
                    or not all(isinstance(kind, str)
                               and (kind == "legacy_restricted"
                                    or kind.startswith("SPDX-license-identifier-"))
                               for kind in license_lock["soong_license_kinds"])):
                raise PackError(f"invalid packaged model license lock for {source.model_id}")
            if license_source is None:
                raise PackError(f"packaged model license missing for {source.model_id}")
            resolved_license = license_source.path.resolve(strict=True)
            if (not resolved_license.is_file()
                    or resolved_license.stat().st_size != license_lock.get("size_bytes")
                    or sha256(resolved_license) != license_lock.get("sha256")):
                raise PackError(f"packaged model license mismatch for {source.model_id}")
        elif license_source is not None:
            raise PackError(f"catalog does not require a model license for {source.model_id}")
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
            if reference is not None:
                if (reference.get("size_bytes") is not None
                        and resolved.stat().st_size != reference["size_bytes"]):
                    raise PackError(
                        f"reference artifact size mismatch for {source.model_id}")
                if source_digest(resolved) != reference.get("sha256"):
                    raise PackError(
                        f"reference artifact digest mismatch for {source.model_id}")
        module = module_name(source.model_id)
        if module in seen_modules:
            raise PackError(f"Soong module-name collision: {module}")
        seen_modules.add(module)
        backend = source.backend or model.get("default_backend")
        if backend not in model.get("allowed_backends", []):
            raise PackError(f"catalog does not allow backend {backend!r} for {source.model_id}")
        result.append((model, Source(source.model_id, backend, resolved), resolved_license))
    if not result:
        raise PackError("at least one model source is required")
    unused_licenses = set(license_by_model) - seen
    if unused_licenses:
        raise PackError(f"model license has no matching source: {sorted(unused_licenses)}")
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
    verified_identities: dict[Path, tuple[int, str]] = {}

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
        if not isinstance(expected, str) or not DIGEST_PATTERN.fullmatch(expected):
            raise PackError(f"generated artifact digest mismatch: {owner}")
        identity = (record["size_bytes"], expected)
        prior = verified_identities.get(path)
        if prior is not None and prior != identity:
            raise PackError(f"shared generated artifact identity mismatch: {owner}")
        if prior is None and sha256(path) != expected:
            raise PackError(f"generated artifact digest mismatch: {owner}")
        verified_identities[path] = identity
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
        license_record = artifact.get("packaged_license")
        if license_record is not None:
            if not isinstance(license_record, dict):
                raise PackError(f"generated model license record is malformed: {model_id}")
            license_path = verified_file(license_record, f"{model_id}/model-license")
            expected_parent = (assets / model_id).resolve(strict=True)
            if license_path.parent != expected_parent:
                raise PackError(f"generated model license is misplaced: {model_id}")
            if (license_record.get("filename") != license_path.name
                    or not str(license_record.get("license_url", "")).startswith("https://")
                    or not isinstance(license_record.get("soong_license_kinds"), list)
                    or not license_record["soong_license_kinds"]
                    or license_record.get("soong_license_module") !=
                        module_name(f"{model_id}_model_license_terms")):
                raise PackError(f"generated model license identity is malformed: {model_id}")
    return manifest


def generate_bundle(
        model: dict[str, Any], source: Source, assets: Path,
        license_module: str | None,
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
                f"{licenses_property(license_module)}"
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
        f"{licenses_property(license_module)}"
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


def generate_packaged_license(
        model: dict[str, Any], source: Path, assets: Path, license_module: str,
) -> tuple[dict[str, Any], list[str], str]:
    lock = model["packaged_license"]
    destination_dir = assets / model["id"]
    destination_dir.mkdir(exist_ok=True)
    filename = lock["filename"]
    destination = destination_dir / filename
    shutil.copyfile(source, destination)
    record = {
        "filename": filename,
        "relative_path": f"models/{model['id']}/{filename}",
        "size_bytes": destination.stat().st_size,
        "sha256": sha256(destination),
        "license_url": model["license_url"],
        "soong_license_module": license_module,
        "soong_license_kinds": lock["soong_license_kinds"],
    }
    if (record["size_bytes"] != lock["size_bytes"]
            or record["sha256"] != lock["sha256"]):
        raise PackError(f"copied model license failed verification: {model['id']}")
    module = module_name(f"{model['id']}_model_license")
    license_block = (
        "license {\n"
        f"    name: \"{license_module}\",\n"
        f"    license_kinds: {json.dumps(lock['soong_license_kinds'])},\n"
        f"    license_text: [\"assets/{model['id']}/{filename}\"],\n"
        "}\n"
    )
    prebuilt_block = (
        "prebuilt_etc {\n"
        f"    name: \"{module}\",\n"
        f"    src: \"assets/{model['id']}/{filename}\",\n"
        f"    filename: \"{filename}\",\n"
        f"    sub_dir: \"aios/models/{model['id']}\",\n"
        f"{licenses_property(license_module)}"
        "    product_specific: true,\n"
        "}\n"
    )
    return record, [license_block, prebuilt_block], module


def generate(
        catalog_path: Path,
        acceptance_path: Path,
        sources: Iterable[Source],
        output: Path,
        license_files: Iterable[LicenseSource] = (),
) -> dict[str, Any]:
    catalog = read_json(catalog_path)
    acceptance = read_json(acceptance_path)
    validated = validate_inputs(catalog, acceptance, sources, license_files)
    if output.exists() and any(output.iterdir()):
        raise PackError(f"output directory must be absent or empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    assets = output / "assets"
    assets.mkdir()

    manifest_entries = []
    blueprint_blocks = []
    modules = []
    shared_artifacts: dict[tuple[str, int, str, str, str, str, str], dict[str, Any]] = {}
    source_digests: dict[Path, str] = {}
    for model, source, license_source in validated:
        license_module = (module_name(f"{model['id']}_model_license_terms")
                          if license_source is not None else None)
        if model.get("reference_bundle") is not None:
            entry, blocks, bundle_modules = generate_bundle(
                model, source, assets, license_module)
            manifest_entries.append(entry)
            blueprint_blocks.extend(blocks)
            modules.extend(bundle_modules)
        else:
            suffix = source.path.suffix.lower()
            source_digest = source_digests.get(source.path)
            if source_digest is None:
                source_digest = sha256(source.path)
                source_digests[source.path] = source_digest
            reference = model.get("reference_artifact")
            if (reference is not None
                    and source_digest != reference.get("sha256")):
                raise PackError(
                    f"reference artifact changed after validation for {source.model_id}")
            source_size = source.path.stat().st_size
            artifact_key = (
                source_digest,
                source_size,
                suffix,
                model["runtime"],
                source.backend,
                model["license_url"],
                json.dumps(model.get("packaged_license"), sort_keys=True),
            )
            shared = shared_artifacts.get(artifact_key)
            if shared is None:
                destination_name = source.model_id + suffix
                destination = assets / destination_name
                shutil.copyfile(source.path, destination)
                digest = sha256(destination)
                if digest != source_digest:
                    raise PackError(
                        f"model source changed while copying {source.model_id}")
                module = module_name(source.model_id)
                modules.append(module)
                blueprint_blocks.append(
                    "prebuilt_etc {\n"
                    f"    name: \"{module}\",\n"
                    f"    src: \"assets/{destination_name}\",\n"
                    f"    filename: \"{destination_name}\",\n"
                    "    sub_dir: \"aios/models\",\n"
                    f"{licenses_property(license_module)}"
                    "    product_specific: true,\n"
                    "}\n"
                )
                shared = {
                    "destination_name": destination_name,
                    "sha256": digest,
                    "size_bytes": destination.stat().st_size,
                }
                shared_artifacts[artifact_key] = shared
            destination_name = shared["destination_name"]
            entry = {
                "model_id": source.model_id,
                "artifact_format": (
                    "ggml" if suffix == ".bin" else suffix.removeprefix(".")),
                "relative_path": f"models/{destination_name}",
                "sha256": shared["sha256"],
                "size_bytes": shared["size_bytes"],
                "runtime": model["runtime"],
                "backend": source.backend,
                "capabilities": model["capabilities"],
                "languages": model["languages"],
                "license_url": model["license_url"],
            }
            manifest_entries.append(entry)
        if license_source is not None:
            assert license_module is not None
            license_record, blocks, module = generate_packaged_license(
                model, license_source, assets, license_module)
            entry["packaged_license"] = license_record
            blueprint_blocks.extend(blocks)
            modules.append(module)

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
    all_modules = [*modules, "aios_model_artifacts"]
    anchor_required = "\n".join(
        f'        "{module}",' for module in all_modules)
    blueprint_blocks.append(
        "// Stable transitive packaging anchor for the Android 17 GSI wrapper.\n"
        "phony {\n"
        "    name: \"aios_model_pack_anchor\",\n"
        "    required: [\n"
        f"{anchor_required}\n"
        "    ],\n"
        "}\n"
    )
    (output / "Android.bp").write_text("\n".join(blueprint_blocks), encoding="utf-8")
    package_lines = ["PRODUCT_PACKAGES += \\"]
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
    parser.add_argument("--license-file", action="append", default=[],
                        type=parse_license_file,
                        help="MODEL_ID=/absolute/path to a catalog-locked model license")
    parser.add_argument("--output", type=Path, default=ROOT / "generated" / "modelpack")
    args = parser.parse_args(argv)
    try:
        manifest = generate(
            args.catalog, args.acceptance, args.source, args.output,
            args.license_file)
    except (OSError, PackError) as exc:
        print(f"model pack generation failed: {exc}", file=sys.stderr)
        return 1
    print(f"generated {len(manifest['artifacts'])} verified model artifact(s) at {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
