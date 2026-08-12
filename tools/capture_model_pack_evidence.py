#!/usr/bin/env python3
"""Capture a catalog-bound, weight-free verification record for a model pack."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import platform
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_model_pack import PackError, verify_generated_pack  # noqa: E402


class ModelPackEvidenceError(RuntimeError):
    pass


def load(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ModelPackEvidenceError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise ModelPackEvidenceError(f"JSON document must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_output(root: Path, *arguments: str) -> str:
    completed = subprocess.run(
        ["git", "-c", f"safe.directory={root}", *arguments],
        cwd=root, check=False, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise ModelPackEvidenceError(completed.stdout.strip())
    return completed.stdout.strip()


def validate_catalog_binding(
        catalog: dict[str, Any], manifest: dict[str, Any]) -> list[dict[str, Any]]:
    if catalog.get("schema_version") != 1 or not isinstance(catalog.get("models"), list):
        raise ModelPackEvidenceError("unsupported model catalog")
    models = {item.get("id"): item for item in catalog["models"]
              if isinstance(item, dict) and isinstance(item.get("id"), str)}
    if len(models) != len(catalog["models"]):
        raise ModelPackEvidenceError("malformed or duplicate model catalog")
    artifacts = manifest.get("artifacts")
    if manifest.get("schema_version") != 1 or not isinstance(artifacts, list):
        raise ModelPackEvidenceError("unsupported model artifact manifest")

    records: list[dict[str, Any]] = []
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise ModelPackEvidenceError("malformed model artifact record")
        model_id = artifact.get("model_id")
        model = models.get(model_id)
        if model is None:
            raise ModelPackEvidenceError(f"artifact is absent from catalog: {model_id}")
        for field in ("runtime", "capabilities", "languages", "license_url"):
            if artifact.get(field) != model.get(field):
                raise ModelPackEvidenceError(
                    f"catalog field mismatch for {model_id}: {field}")
        if artifact.get("backend") not in model.get("allowed_backends", []):
            raise ModelPackEvidenceError(f"catalog backend mismatch for {model_id}")

        reference = model.get("reference_artifact")
        bundle = model.get("reference_bundle")
        if isinstance(reference, dict):
            if (artifact.get("sha256") != reference.get("sha256")
                    or artifact.get("size_bytes") != reference.get("size_bytes")
                    or artifact.get("artifact_format") not in
                        model.get("artifact_formats", [])):
                raise ModelPackEvidenceError(
                    f"reference artifact lock mismatch for {model_id}")
        elif isinstance(bundle, dict):
            if (artifact.get("artifact_format") != "bundle"
                    or artifact.get("source_archive_sha256") != bundle.get("sha256")):
                raise ModelPackEvidenceError(
                    f"reference bundle lock mismatch for {model_id}")
            members = artifact.get("bundle_members")
            expected_members = bundle.get("members")
            if not isinstance(members, list) or not isinstance(expected_members, list):
                raise ModelPackEvidenceError(f"bundle members missing for {model_id}")
            actual_by_name = {item.get("name"): item for item in members
                              if isinstance(item, dict)}
            if len(actual_by_name) != len(members):
                raise ModelPackEvidenceError(f"duplicate bundle members for {model_id}")
            for expected in expected_members:
                actual = actual_by_name.get(expected.get("path"))
                if (actual is None
                        or actual.get("size_bytes") != expected.get("size_bytes")
                        or actual.get("sha256") != expected.get("sha256")):
                    raise ModelPackEvidenceError(
                        f"bundle member lock mismatch for {model_id}")
        else:
            raise ModelPackEvidenceError(f"catalog lacks a reference lock: {model_id}")

        license_lock = model.get("packaged_license")
        packaged_license = artifact.get("packaged_license")
        if isinstance(license_lock, dict):
            if not isinstance(packaged_license, dict):
                raise ModelPackEvidenceError(f"packaged license missing for {model_id}")
            comparisons = {
                "filename": license_lock.get("filename"),
                "size_bytes": license_lock.get("size_bytes"),
                "sha256": license_lock.get("sha256"),
                "soong_license_kinds": license_lock.get("soong_license_kinds"),
                "license_url": model.get("license_url"),
            }
            if any(packaged_license.get(field) != value
                   for field, value in comparisons.items()):
                raise ModelPackEvidenceError(
                    f"packaged license lock mismatch for {model_id}")
        elif packaged_license is not None:
            raise ModelPackEvidenceError(f"unexpected packaged license for {model_id}")

        records.append({
            "model_id": model_id,
            "relative_path": artifact.get("relative_path"),
            "sha256": artifact.get("sha256"),
            "size_bytes": artifact.get("size_bytes"),
            "runtime": artifact.get("runtime"),
            "backend": artifact.get("backend"),
            "license_url": artifact.get("license_url"),
        })
    return records


def write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    if path.exists():
        raise ModelPackEvidenceError(f"refusing to overwrite model-pack evidence: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
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
        root: Path, pack: Path, output: Path | None = None,
        captured_at: str | None = None) -> dict[str, Any]:
    root = root.resolve(strict=True)
    pack = pack.resolve(strict=True)
    if git_output(root, "status", "--porcelain"):
        raise ModelPackEvidenceError("AIOS checkout must be clean for evidence capture")
    revision = git_output(root, "rev-parse", "HEAD")
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        raise ModelPackEvidenceError("AIOS revision is not immutable")
    try:
        manifest = verify_generated_pack(pack)
    except (OSError, PackError) as error:
        raise ModelPackEvidenceError(str(error)) from error
    catalog_path = root / "config" / "model_catalog.json"
    artifact_records = validate_catalog_binding(load(catalog_path), manifest)
    physical_payloads: set[tuple[str, str, int]] = set()
    for artifact in manifest["artifacts"]:
        payloads = artifact.get("bundle_members") or [artifact]
        for payload in payloads:
            physical_payloads.add((
                payload["relative_path"], payload["sha256"], payload["size_bytes"]))
    evidence = {
        "schema_version": 1,
        "status": "passed",
        "captured_at": captured_at or datetime.now(timezone.utc).isoformat(),
        "aios_revision": revision,
        "model_catalog_sha256": sha256(catalog_path),
        "model_artifacts_manifest_sha256": sha256(pack / "model_artifacts.json"),
        "logical_artifact_count": len(artifact_records),
        "physical_model_payload_count": len(physical_payloads),
        "artifacts": artifact_records,
        "catalog_binding_verified": True,
        "generated_pack_verified": True,
        "contains_model_weights": False,
        "proves_model_inference": False,
        "proves_physical_device_runtime": False,
        "host": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
        },
    }
    if output is not None:
        write_json_atomic(output, evidence)
    return evidence


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args(argv)
    try:
        capture(arguments.root, arguments.pack, arguments.output)
    except ModelPackEvidenceError as error:
        print(f"model-pack evidence capture failed: {error}", file=sys.stderr)
        return 1
    print(f"captured verified model-pack evidence at {arguments.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
