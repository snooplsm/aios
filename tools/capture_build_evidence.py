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


def select_lane(root: Path, lane_id: str) -> tuple[dict, list[str]]:
    document = load(root / "config" / "aosp_lanes.json")
    matches = [lane for lane in document["lanes"] if lane["id"] == lane_id]
    if len(matches) != 1:
        raise BuildEvidenceError(f"unknown or duplicate lane: {lane_id}")
    return matches[0], document["expected_product_artifacts"]


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


def installed_product_records(product_out: Path) -> tuple[Path, dict[str, dict]]:
    manifest = product_out / "installed-files-product.json"
    if not manifest.is_file():
        raise BuildEvidenceError("missing installed-files-product.json")
    document = load(manifest)
    if not isinstance(document, list) or not document:
        raise BuildEvidenceError("installed product-file manifest must be a non-empty array")
    records: dict[str, dict] = {}
    for record in document:
        if not isinstance(record, dict):
            raise BuildEvidenceError("installed product-file manifest has a malformed row")
        name = record.get("Name")
        size = record.get("Size")
        digest = record.get("SHA256")
        if (not isinstance(name, str) or not name
                or not isinstance(size, int) or size < 0
                or not isinstance(digest, str)):
            raise BuildEvidenceError("installed product-file manifest has a malformed row")
        normalized = name.replace("\\", "/").lstrip("/")
        if not normalized or ".." in Path(normalized).parts or normalized in records:
            raise BuildEvidenceError(
                f"installed product-file manifest has an invalid path: {name}")
        records[normalized] = record
    return manifest, records


def require_manifest_membership(
    records: dict[str, dict], relative: str, artifact: dict
) -> None:
    prefix = "product/"
    if not relative.startswith(prefix):
        raise BuildEvidenceError(
            f"expected installed artifact is outside product partition: {relative}")
    installed_name = relative[len(prefix):]
    record = records.get(installed_name)
    if record is None:
        raise BuildEvidenceError(
            f"artifact is absent from installed-files-product.json: {relative}")
    if record["Size"] != artifact["size_bytes"]:
        raise BuildEvidenceError(
            f"installed-file size does not match staged artifact: {relative}")
    if record["SHA256"] != artifact["sha256"]:
        raise BuildEvidenceError(
            f"installed-file digest does not match staged artifact: {relative}")


def find_system_build_prop(product_out: Path) -> Path:
    candidates = [
        product_out / "system" / "build.prop",
        product_out / "system" / "system" / "build.prop",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise BuildEvidenceError("missing system build.prop in product output")


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
    product_properties_path = product_out / "product" / "build.prop"
    if not product_properties_path.is_file():
        raise BuildEvidenceError("missing product/build.prop in product output")
    product_properties = read_properties(product_properties_path)
    if product_properties.get("ro.aios.version") != "0.1-dev":
        raise BuildEvidenceError("built product does not contain the expected AIOS identity")

    system_properties = read_properties(find_system_build_prop(product_out))
    fingerprint = (system_properties.get("ro.build.fingerprint")
                   or product_properties.get("ro.product.build.fingerprint"))
    if not fingerprint:
        raise BuildEvidenceError("built product does not expose a build fingerprint")

    installed_manifest, installed_records = installed_product_records(product_out)
    artifacts = [
        artifact_record(product_out, relative)
        for relative in expected_artifacts
    ]
    for relative, artifact in zip(expected_artifacts, artifacts, strict=True):
        require_manifest_membership(installed_records, relative, artifact)
    for image in ("product.img", "system.img"):
        artifacts.append(artifact_record(product_out, image))

    value = {
        "schema_version": 2,
        "status": "passed",
        "lane": lane_id,
        "kind": lane["kind"],
        "product": lane["product"],
        "target_device": lane["target_device"],
        "lunch_target": lane["lunch_target"],
        "build_fingerprint": fingerprint,
        "android_release": system_properties.get("ro.build.version.release"),
        "aios_revision": head,
        "manifest_sha256": actual_manifest_digest,
        "manifest_repository_revision": manifest_repository_revision,
        "manifest_lock_sha256": sha256(manifest_lock),
        "build_log_sha256": sha256(build_log),
        "installed_files_product_sha256": sha256(installed_manifest),
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
