#!/usr/bin/env python3
"""Capture fail-closed first-boot evidence for the AIOS Cuttlefish lane."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


SERIAL_PATTERN = re.compile(
    r"(?:localhost|127\.0\.0\.1|0\.0\.0\.0):[1-9][0-9]{1,4}"
)
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
EXPECTED_LANE = "android_latest_integration"
EXPECTED_KIND = "virtual_integration"
EXPECTED_PRODUCT = "aios_cf_x86_64_phone"
EXPECTED_TARGET_DEVICE = "vsoc_x86_64"
EXPECTED_AIOS_VERSION = "0.1-dev"
EXPECTED_PACKAGES = (
    "com.aios.callintelligence",
    "com.aios.contextintelligence",
    "com.aios.mediaintelligence",
    "com.aios.messaging",
    "com.aios.modelbroker",
    "com.aios.phone",
)
EXPECTED_SERVICES = (
    (
        "com.aios.call.CALL_INTELLIGENCE_SERVICE",
        "com.aios.callintelligence",
        "com.aios.callintelligence/.CallIntelligenceService",
    ),
    (
        "com.aios.context.COMMUNICATION_CONTEXT_SERVICE",
        "com.aios.contextintelligence",
        "com.aios.contextintelligence/.CommunicationContextService",
    ),
    (
        "com.aios.model.MODEL_SERVICE",
        "com.aios.modelbroker",
        "com.aios.modelbroker/.ModelBrokerService",
    ),
)


class CuttlefishBootEvidenceError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_build_evidence(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CuttlefishBootEvidenceError(
            f"cannot load build evidence: {error}"
        ) from error
    if not isinstance(value, dict):
        raise CuttlefishBootEvidenceError("build evidence must be a JSON object")
    return value


def validate_build_evidence(value: dict) -> None:
    expected = {
        "schema_version": 2,
        "status": "passed",
        "lane": EXPECTED_LANE,
        "kind": EXPECTED_KIND,
        "product": EXPECTED_PRODUCT,
        "target_device": EXPECTED_TARGET_DEVICE,
        "lane_eligible_for_physical_gates": False,
        "proves_physical_runtime_gate": False,
    }
    for field, expected_value in expected.items():
        if value.get(field) != expected_value:
            raise CuttlefishBootEvidenceError(
                f"build evidence {field} does not match the Cuttlefish lane"
            )
    if not isinstance(value.get("build_fingerprint"), str) \
            or not value["build_fingerprint"].strip():
        raise CuttlefishBootEvidenceError("build evidence lacks a fingerprint")
    if COMMIT_PATTERN.fullmatch(str(value.get("aios_revision", ""))) is None:
        raise CuttlefishBootEvidenceError(
            "build evidence lacks an immutable AIOS revision"
        )

    images = {}
    artifacts = value.get("artifacts")
    if not isinstance(artifacts, list):
        raise CuttlefishBootEvidenceError("build evidence lacks artifacts")
    for artifact in artifacts:
        if isinstance(artifact, dict) and artifact.get("path") in {
                "product.img", "system.img"}:
            images[artifact["path"]] = artifact
    for image in ("product.img", "system.img"):
        artifact = images.get(image, {})
        if not isinstance(artifact.get("size_bytes"), int) \
                or artifact["size_bytes"] <= 0 \
                or SHA256_PATTERN.fullmatch(
                    str(artifact.get("sha256", ""))) is None:
            raise CuttlefishBootEvidenceError(
                f"build evidence lacks a valid {image} record"
            )


def adb_output(adb: str, serial: str, *arguments: str) -> str:
    completed = subprocess.run(
        [adb, "-s", serial, *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=20,
    )
    if completed.returncode != 0:
        detail = completed.stdout.strip()
        raise CuttlefishBootEvidenceError(
            f"adb command failed ({' '.join(arguments)}): {detail}"
        )
    return completed.stdout.strip()


def require_property(
        query: Callable[..., str], serial: str, name: str, expected: str) -> str:
    actual = query(serial, "shell", "getprop", name).strip()
    if actual != expected:
        raise CuttlefishBootEvidenceError(
            f"{name} mismatch: expected {expected!r}, observed {actual!r}"
        )
    return actual


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    if path.exists():
        raise CuttlefishBootEvidenceError(
            f"refusing to overwrite boot evidence: {path}"
        )
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
        build_evidence_path: Path,
        serial: str,
        output: Path | None = None,
        adb: str = "adb",
        query: Callable[..., str] | None = None) -> dict:
    if SERIAL_PATTERN.fullmatch(serial) is None:
        raise CuttlefishBootEvidenceError(
            "refusing non-local Cuttlefish serial; expected localhost TCP adb"
        )
    build_evidence_path = build_evidence_path.resolve()
    build = load_build_evidence(build_evidence_path)
    validate_build_evidence(build)
    if query is None:
        query = lambda selected_serial, *arguments: adb_output(
            adb, selected_serial, *arguments
        )

    if query(serial, "get-state").strip() != "device":
        raise CuttlefishBootEvidenceError("Cuttlefish is not in adb device state")
    require_property(query, serial, "sys.boot_completed", "1")
    require_property(query, serial, "ro.aios.version", EXPECTED_AIOS_VERSION)
    require_property(query, serial, "ro.product.name", EXPECTED_PRODUCT)
    require_property(query, serial, "ro.product.device", EXPECTED_TARGET_DEVICE)
    require_property(query, serial, "ro.build.type", "userdebug")
    require_property(query, serial, "ro.debuggable", "1")
    fingerprint = require_property(
        query, serial, "ro.build.fingerprint", build["build_fingerprint"]
    )

    package_paths = {}
    for package_name in EXPECTED_PACKAGES:
        raw_paths = query(serial, "shell", "pm", "path", package_name).splitlines()
        paths = [line.removeprefix("package:").strip() for line in raw_paths
                 if line.startswith("package:")]
        if not paths or any(
                not path.startswith("/product/priv-app/") for path in paths):
            raise CuttlefishBootEvidenceError(
                f"{package_name} is missing from the privileged product partition"
            )
        package_paths[package_name] = paths

    resolved_services = {}
    for action, package_name, expected_component in EXPECTED_SERVICES:
        resolved = query(
            serial,
            "shell", "cmd", "package", "query-services", "--brief",
            "--components", "--user", "0", "-a", action, "-p", package_name,
        ).strip()
        if expected_component not in resolved.splitlines():
            raise CuttlefishBootEvidenceError(
                f"{action} did not expose {expected_component}"
            )
        resolved_services[action] = expected_component

    boot_id = query(
        serial, "shell", "cat", "/proc/sys/kernel/random/boot_id"
    ).strip().lower()
    if re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            boot_id) is None:
        raise CuttlefishBootEvidenceError("Cuttlefish did not expose a valid boot ID")

    uptime_text = query(serial, "shell", "cat", "/proc/uptime").split()
    try:
        uptime_seconds = float(uptime_text[0])
    except (IndexError, ValueError) as error:
        raise CuttlefishBootEvidenceError("Cuttlefish uptime is invalid") from error
    if uptime_seconds <= 0:
        raise CuttlefishBootEvidenceError("Cuttlefish uptime must be positive")

    value = {
        "schema_version": 1,
        "status": "passed",
        "gate": "integration.android_latest_first_boot",
        "lane": EXPECTED_LANE,
        "kind": EXPECTED_KIND,
        "product": EXPECTED_PRODUCT,
        "target_device": EXPECTED_TARGET_DEVICE,
        "serial": serial,
        "boot_id": boot_id,
        "uptime_seconds": uptime_seconds,
        "build_fingerprint": fingerprint,
        "aios_version": EXPECTED_AIOS_VERSION,
        "aios_revision": build["aios_revision"],
        "build_evidence_sha256": sha256(build_evidence_path),
        "packages": package_paths,
        "resolved_services": resolved_services,
        "captured_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "lane_eligible_for_physical_gates": False,
        "proves_physical_runtime_gate": False,
    }
    if output is not None:
        write_json_atomic(output, value)
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-evidence", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    arguments = parser.parse_args()
    try:
        value = capture(
            arguments.build_evidence,
            arguments.serial,
            arguments.output,
            arguments.adb,
        )
    except (OSError, subprocess.SubprocessError,
            CuttlefishBootEvidenceError) as error:
        print(f"Cuttlefish boot evidence capture failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(value, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
