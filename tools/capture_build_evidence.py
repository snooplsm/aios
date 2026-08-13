#!/usr/bin/env python3
"""Capture digest-bound evidence from a successful AIOS Soong product build."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
PATCH_REVIEW_FIELDS = (
    "id", "project", "file", "base_revision", "sha256", "owner", "paths",
    "tests", "reason", "removal_condition", "rebase_notes",
)


class BuildEvidenceError(RuntimeError):
    pass


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BuildEvidenceError(f"cannot load {path}: {error}") from error


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_properties(path: Path) -> dict[str, str]:
    values = {}
    for raw_line in path.read_text(encoding="utf-8", errors="strict").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def git_output(root: Path, *arguments: str) -> str:
    completed = subprocess.run(
        ["git", "-c", f"safe.directory={root}", *arguments],
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise BuildEvidenceError(completed.stdout.strip())
    return completed.stdout.strip()


def installed_artifact_path(lane: dict, relative: str) -> str:
    layout = lane.get("artifact_layout")
    if layout == "product_partition":
        return relative
    if layout == "gsi_system_product":
        if not relative.startswith("product/"):
            raise BuildEvidenceError(
                f"GSI logical artifact is outside product namespace: {relative}"
            )
        return f"system/{relative}"
    raise BuildEvidenceError(f"unsupported artifact layout: {layout}")


def select_lane(root: Path, lane_id: str) -> tuple[dict, list[str]]:
    document = load(root / "config" / "aosp_lanes.json")
    matches = [lane for lane in document["lanes"] if lane["id"] == lane_id]
    if len(matches) != 1:
        raise BuildEvidenceError(f"unknown or duplicate lane: {lane_id}")
    lane = matches[0]
    return lane, [
        installed_artifact_path(lane, relative)
        for relative in document["expected_product_artifacts"]
    ]


def patch_queue_record(root: Path) -> tuple[list[dict], str]:
    patches_root = (root / "patches").resolve()
    document = load(patches_root / "series.json")
    if set(document) != {"schema_version", "patches"} \
            or document.get("schema_version") != 2 \
            or not isinstance(document.get("patches"), list):
        raise BuildEvidenceError("unsupported patch queue schema")
    records = []
    for item in document["patches"]:
        if not isinstance(item, dict) or set(item) != set(PATCH_REVIEW_FIELDS):
            raise BuildEvidenceError("patch queue lacks review-complete metadata")
        relative = item["file"]
        if not isinstance(relative, str):
            raise BuildEvidenceError("patch queue contains an invalid payload path")
        relative_path = Path(relative)
        if relative_path.is_absolute() or not relative_path.parts \
                or ".." in relative_path.parts:
            raise BuildEvidenceError("patch queue contains an unsafe payload path")
        payload = (patches_root / relative_path).resolve()
        if patches_root not in payload.parents or not payload.is_file():
            raise BuildEvidenceError(f"missing patch payload: {relative}")
        actual_digest = sha256(payload)
        if item["sha256"] != actual_digest:
            raise BuildEvidenceError(f"patch payload digest mismatch: {relative}")
        records.append({field: item[field] for field in PATCH_REVIEW_FIELDS})
    canonical = json.dumps(
        records, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return records, hashlib.sha256(canonical).hexdigest()


def artifact_record(product_out: Path, relative: str) -> dict:
    relative_path = Path(relative)
    if (relative_path.is_absolute() or not relative_path.parts
            or ".." in relative_path.parts):
        raise BuildEvidenceError(f"invalid installed artifact path: {relative}")
    path = (product_out / relative_path).resolve()
    product_out = product_out.resolve()
    if path != product_out and product_out not in path.parents:
        raise BuildEvidenceError(f"installed artifact escapes product output: {relative}")
    if not path.is_file():
        raise BuildEvidenceError(f"missing installed product artifact: {relative}")
    if path.stat().st_size <= 0:
        raise BuildEvidenceError(f"empty installed product artifact: {relative}")
    return {
        "path": relative.replace("\\", "/"),
        "size_bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def installed_partition_records(
    product_out: Path, lane: dict
) -> tuple[Path, dict[str, dict]]:
    manifests = {
        "product_partition": "installed-files-product.json",
        "gsi_system_product": "installed-files-system.json",
    }
    manifest_name = manifests.get(lane.get("artifact_layout"))
    if manifest_name is None:
        raise BuildEvidenceError(
            f"unsupported artifact layout: {lane.get('artifact_layout')}"
        )
    manifest = product_out / manifest_name
    if not manifest.is_file():
        raise BuildEvidenceError(f"missing {manifest_name}")
    document = load(manifest)
    if not isinstance(document, list) or not document:
        raise BuildEvidenceError("installed-file manifest must be a non-empty array")
    records: dict[str, dict] = {}
    for record in document:
        if not isinstance(record, dict):
            raise BuildEvidenceError("installed-file manifest has a malformed row")
        name = record.get("Name")
        size = record.get("Size")
        digest = record.get("SHA256")
        if (not isinstance(name, str) or not name
                or not isinstance(size, int) or size < 0
                or not isinstance(digest, str)):
            raise BuildEvidenceError("installed-file manifest has a malformed row")
        normalized = name.replace("\\", "/").lstrip("/")
        if not normalized or ".." in Path(normalized).parts or normalized in records:
            raise BuildEvidenceError(
                f"installed-file manifest has an invalid path: {name}")
        records[normalized] = record
    return manifest, records


def require_manifest_membership(
    records: dict[str, dict], lane: dict, relative: str, artifact: dict
) -> None:
    layout = lane.get("artifact_layout")
    if layout == "product_partition" and relative.startswith("product/"):
        candidates = (relative, relative.removeprefix("product/"))
    elif layout == "gsi_system_product" and relative.startswith("system/product/"):
        # Android's GSI BoardConfig redirects product-specific modules into
        # /system/product in the single system image. The installed-system
        # manifest has used both system-rooted and system-relative names.
        candidates = (relative, relative.removeprefix("system/"))
    else:
        raise BuildEvidenceError(
            f"artifact path does not match {layout}: {relative}"
        )
    record = next((records.get(name) for name in candidates if records.get(name)), None)
    if record is None:
        raise BuildEvidenceError(
            f"artifact is absent from installed-file manifest: {relative}")
    if record["Size"] != artifact["size_bytes"]:
        raise BuildEvidenceError(
            f"installed-file size does not match staged artifact: {relative}")
    if record["SHA256"] != artifact["sha256"]:
        raise BuildEvidenceError(
            f"installed-file digest does not match staged artifact: {relative}")


def generated_product_path(lane: dict, relative: str) -> str:
    path = Path(relative)
    if (path.is_absolute() or not path.parts or ".." in path.parts
            or path.parts[0] not in {"etc", "priv-app"}):
        raise BuildEvidenceError(f"unsafe generated product path: {relative}")
    return installed_artifact_path(
        lane, f"product/{path.as_posix()}"
    )


def require_source_payload(
    source: Path, expected_size: int, expected_sha256: str, label: str
) -> None:
    if (not source.is_file() or not isinstance(expected_size, int)
            or expected_size <= 0
            or SHA256_PATTERN.fullmatch(str(expected_sha256)) is None):
        raise BuildEvidenceError(f"invalid generated payload declaration: {label}")
    if source.stat().st_size != expected_size or sha256(source) != expected_sha256:
        raise BuildEvidenceError(f"generated payload does not match its lock: {label}")


def capture_generated_payloads(
    root: Path,
    product_out: Path,
    lane: dict,
    installed_records: dict[str, dict],
) -> tuple[list[dict], dict]:
    generated = root / "generated"
    artifacts: list[dict] = []
    summaries: dict = {}
    installed_paths: set[str] = set()

    def installed_record(relative: str) -> dict:
        installed = generated_product_path(lane, relative)
        if installed in installed_paths:
            return next(item for item in artifacts if item["path"] == installed)
        record = artifact_record(product_out, installed)
        require_manifest_membership(installed_records, lane, installed, record)
        installed_paths.add(installed)
        artifacts.append(record)
        return record

    model_pack = generated / "modelpack"
    model_manifest_path = model_pack / "model_artifacts.json"
    if model_manifest_path.is_file():
        document = load(model_manifest_path)
        declarations = document.get("artifacts")
        if document.get("schema_version") != 1 or not isinstance(declarations, list):
            raise BuildEvidenceError("generated model pack manifest is invalid")
        models: list[str] = []
        declared_sources: dict[str, tuple[int, str]] = {}

        def admit_model_file(item: dict, label: str) -> None:
            relative = item.get("relative_path")
            size = item.get("size_bytes")
            digest = item.get("sha256")
            if (not isinstance(relative, str)
                    or not relative.startswith("models/")):
                raise BuildEvidenceError(f"invalid model-pack path: {label}")
            prior = declared_sources.get(relative)
            declaration = (size, digest)
            if prior is not None and prior != declaration:
                raise BuildEvidenceError(
                    f"conflicting shared model declaration: {relative}"
                )
            source = model_pack / "assets" / relative.removeprefix("models/")
            require_source_payload(source, size, digest, label)
            declared_sources[relative] = declaration
            installed = installed_record(f"etc/aios/{relative}")
            if (installed["size_bytes"] != size
                    or installed["sha256"] != digest):
                raise BuildEvidenceError(
                    f"installed model payload differs from its lock: {label}"
                )

        for item in declarations:
            if not isinstance(item, dict) or not isinstance(item.get("model_id"), str):
                raise BuildEvidenceError("generated model declaration is invalid")
            model_id = item["model_id"]
            models.append(model_id)
            admit_model_file(item, model_id)
            packaged_license = item.get("packaged_license")
            if packaged_license is not None:
                if not isinstance(packaged_license, dict):
                    raise BuildEvidenceError(f"invalid packaged license: {model_id}")
                admit_model_file(packaged_license, f"{model_id} license")
            members = item.get("bundle_members", [])
            if not isinstance(members, list):
                raise BuildEvidenceError(f"invalid model bundle: {model_id}")
            for member in members:
                if not isinstance(member, dict):
                    raise BuildEvidenceError(f"invalid model bundle member: {model_id}")
                admit_model_file(member, f"{model_id}:{member.get('name', '')}")

        manifest_installed = installed_record("etc/aios/model_artifacts.json")
        manifest_digest = sha256(model_manifest_path)
        if manifest_installed["sha256"] != manifest_digest:
            raise BuildEvidenceError("installed model manifest differs from generated input")
        summaries["model_pack"] = {
            "manifest_sha256": manifest_digest,
            "models": models,
            "installed_file_count": len(declared_sources) + 1,
        }

    runtime_root = generated / "runtimepack"
    runtime_summaries = []
    if runtime_root.is_dir():
        for pack in sorted(path for path in runtime_root.iterdir() if path.is_dir()):
            manifest_path = pack / "runtime_artifacts.json"
            if not manifest_path.is_file():
                continue
            document = load(manifest_path)
            runtime = document.get("runtime")
            provider = document.get("provider_apk")
            if (document.get("schema_version") != 1
                    or not isinstance(runtime, str)
                    or re.fullmatch(r"[a-z0-9][a-z0-9_]{0,63}", runtime) is None
                    or runtime != pack.name
                    or not isinstance(provider, dict)):
                raise BuildEvidenceError(f"generated runtime pack is invalid: {pack.name}")
            unsigned_relative = provider.get("relative_path")
            if not isinstance(unsigned_relative, str):
                raise BuildEvidenceError(f"runtime provider path is invalid: {runtime}")
            unsigned_source = pack / "assets" / Path(unsigned_relative).name
            require_source_payload(
                unsigned_source,
                provider.get("size_bytes"),
                provider.get("sha256"),
                runtime,
            )
            manifest_installed = installed_record(
                f"etc/aios/runtime_artifacts-{runtime}.json"
            )
            manifest_digest = sha256(manifest_path)
            if manifest_installed["sha256"] != manifest_digest:
                raise BuildEvidenceError(
                    f"installed runtime manifest differs from generated input: {runtime}"
                )
            module = f"AiosRuntimeProvider_{runtime}"
            signed = installed_record(f"priv-app/{module}/{module}.apk")
            runtime_summaries.append({
                "runtime": runtime,
                "source_revision": document.get("source_revision"),
                "manifest_sha256": manifest_digest,
                "unsigned_provider_sha256": provider["sha256"],
                "platform_signed_provider_sha256": signed["sha256"],
            })
    if runtime_summaries:
        summaries["runtime_packs"] = runtime_summaries
    return artifacts, summaries


def find_system_build_prop(product_out: Path) -> Path:
    candidates = [
        product_out / "system" / "build.prop",
        product_out / "system" / "system" / "build.prop",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise BuildEvidenceError("missing system build.prop in product output")


def find_product_build_prop(product_out: Path) -> Path:
    candidates = [
        # Older trees staged partition properties at the partition root.
        product_out / "product" / "build.prop",
        # Android 17 stages the product property file at its installed location.
        product_out / "product" / "etc" / "build.prop",
        # GSI redirects product into the single system image.
        product_out / "system" / "product" / "build.prop",
        product_out / "system" / "product" / "etc" / "build.prop",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise BuildEvidenceError("missing product build.prop in product output")


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    if path.exists():
        raise BuildEvidenceError(f"refusing to overwrite build evidence: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def capture(
    root: Path,
    lane_id: str,
    manifest: Path,
    manifest_lock: Path,
    out_dir: Path,
    build_log: Path,
    output: Path | None = None,
) -> dict:
    root = root.resolve()
    lane, expected_artifacts = select_lane(root, lane_id)
    lock = load(manifest_lock)
    if lock.get("schema_version") != 1 or lock.get("lane") != lane_id:
        raise BuildEvidenceError("manifest lock does not match the build lane")
    if SHA256_PATTERN.fullmatch(str(lock.get("manifest_sha256", ""))) is None:
        raise BuildEvidenceError("manifest lock lacks a valid SHA-256 digest")
    manifest_repository_revision = str(lock.get("manifest_repository_revision", ""))
    if re.fullmatch(r"[0-9a-f]{40}", manifest_repository_revision) is None:
        raise BuildEvidenceError("manifest lock lacks an immutable repository revision")
    actual_manifest_digest = sha256(manifest)
    if lock.get("manifest_sha256") != actual_manifest_digest:
        raise BuildEvidenceError("resolved manifest digest does not match its lock")
    if lock.get("product") != lane["product"]:
        raise BuildEvidenceError("manifest lock product does not match lane policy")

    head = git_output(root, "rev-parse", "HEAD")
    if head != lock.get("aios_revision"):
        raise BuildEvidenceError("AIOS HEAD differs from the resolved manifest lock")
    if git_output(root, "status", "--porcelain", "--untracked-files=all"):
        raise BuildEvidenceError("AIOS sources changed after manifest capture")
    patch_queue, patch_queue_digest = patch_queue_record(root)

    if not build_log.is_file() or build_log.stat().st_size == 0:
        raise BuildEvidenceError("successful build evidence requires a non-empty log")
    product_out = out_dir.resolve() / "target" / "product" / lane["target_device"]
    product_properties_path = find_product_build_prop(product_out)
    product_properties = read_properties(product_properties_path)
    if product_properties.get("ro.aios.version") != "0.1-dev":
        raise BuildEvidenceError("built product does not contain the expected AIOS identity")

    system_properties = read_properties(find_system_build_prop(product_out))
    fingerprint = (system_properties.get("ro.build.fingerprint")
                   or product_properties.get("ro.product.build.fingerprint"))
    if not fingerprint:
        raise BuildEvidenceError("built product does not expose a build fingerprint")

    installed_manifest, installed_records = installed_partition_records(
        product_out, lane
    )
    artifacts = [
        artifact_record(product_out, relative)
        for relative in expected_artifacts
    ]
    for relative, artifact in zip(expected_artifacts, artifacts, strict=True):
        require_manifest_membership(installed_records, lane, relative, artifact)
    generated_artifacts, generated_payloads = capture_generated_payloads(
        root, product_out, lane, installed_records
    )
    artifacts.extend(generated_artifacts)
    required_images = lane.get("required_images")
    if (not isinstance(required_images, list) or not required_images
            or any(not isinstance(image, str) or not image
                   for image in required_images)):
        raise BuildEvidenceError("lane lacks required build images")
    for image in required_images:
        artifacts.append(artifact_record(product_out, image))

    value = {
        "schema_version": 2,
        "status": "passed",
        "lane": lane_id,
        "kind": lane["kind"],
        "product": lane["product"],
        "target_device": lane["target_device"],
        "lunch_target": lane["lunch_target"],
        "artifact_layout": lane["artifact_layout"],
        "deployable_images": required_images,
        "build_fingerprint": fingerprint,
        "android_release": system_properties.get("ro.build.version.release"),
        "security_patch": system_properties.get("ro.build.version.security_patch"),
        "aios_revision": head,
        "manifest_sha256": actual_manifest_digest,
        "manifest_repository_revision": manifest_repository_revision,
        "manifest_lock_sha256": sha256(manifest_lock),
        "build_log_sha256": sha256(build_log),
        "installed_files_manifest": installed_manifest.name,
        "installed_files_sha256": sha256(installed_manifest),
        "patch_series_sha256": sha256(root / "patches" / "series.json"),
        "patch_queue_sha256": patch_queue_digest,
        "patch_queue": patch_queue,
        "captured_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "host": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
        },
        "artifacts": artifacts,
        "lane_eligible_for_physical_gates": bool(lane["physical_gate_evidence"]),
        "proves_physical_runtime_gate": False,
    }
    if generated_payloads:
        value["generated_payloads"] = generated_payloads
    if lane["artifact_layout"] == "product_partition":
        # Preserve the schema-2 field consumed by existing checked-in build
        # evidence while exposing a partition-neutral field for GSI records.
        value["installed_files_product_sha256"] = sha256(installed_manifest)
    if output is not None:
        write_json_atomic(output, value)
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--lane", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--manifest-lock", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--build-log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        value = capture(
            arguments.root,
            arguments.lane,
            arguments.manifest,
            arguments.manifest_lock,
            arguments.out_dir,
            arguments.build_log,
            arguments.output,
        )
    except (KeyError, OSError, BuildEvidenceError) as error:
        print(f"build evidence capture failed: {error}", file=sys.stderr)
        return 1
    print(
        f"AIOS build evidence captured for {value['product']}: "
        f"{value['build_fingerprint']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
