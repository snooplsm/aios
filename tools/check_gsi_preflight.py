#!/usr/bin/env python3
"""Evaluate a read-only device inventory against an exact AIOS GSI build."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from datetime import date, datetime, timezone
from pathlib import Path


SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
REQUIRED_PACKAGES = (
    "AiosContextIntelligence",
    "AiosMessaging",
    "AiosPhone",
    "AiosCallIntelligence",
    "AiosMediaIntelligence",
    "AiosModelBroker",
)


class GsiPreflightError(RuntimeError):
    pass


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GsiPreflightError(f"cannot load {path}: {error}") from error
    if not isinstance(value, dict):
        raise GsiPreflightError(f"JSON root must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_date(value: object, field: str) -> date:
    try:
        return date.fromisoformat(str(value))
    except ValueError as error:
        raise GsiPreflightError(f"{field} is not an ISO date: {value}") from error


def parse_android_release(value: object, field: str) -> int:
    match = re.fullmatch(r"([0-9]+)(?:\..*)?", str(value))
    if match is None:
        raise GsiPreflightError(f"{field} is not a numbered Android release: {value}")
    return int(match.group(1))


def property_value(inventory: dict, name: str) -> str:
    properties = inventory.get("properties")
    if not isinstance(properties, dict):
        raise GsiPreflightError("device inventory properties must be an object")
    value = properties.get(name)
    if not isinstance(value, str):
        raise GsiPreflightError(f"device inventory lacks string property {name}")
    return value.strip()


def build_artifact_map(build: dict) -> dict[str, dict]:
    artifacts = build.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise GsiPreflightError("GSI build evidence lacks artifacts")
    records: dict[str, dict] = {}
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise GsiPreflightError("GSI build artifact row is malformed")
        path = artifact.get("path")
        digest = artifact.get("sha256")
        size = artifact.get("size_bytes")
        if (not isinstance(path, str) or not path
                or path in records
                or SHA256_PATTERN.fullmatch(str(digest)) is None
                or not isinstance(size, int) or size <= 0):
            raise GsiPreflightError(f"GSI build artifact row is invalid: {artifact}")
        records[path] = artifact
    return records


def validate_inputs(
    inventory: dict, build: dict, expected_device: str
) -> tuple[dict[str, dict], dict[str, bool]]:
    collection = inventory.get("collection")
    if (inventory.get("schema_version") != 2
            or inventory.get("status") != "captured"
            or inventory.get("adb_state") != "device"
            or not isinstance(collection, dict)
            or collection.get("read_only") is not True
            or collection.get("unlock_attempted") is not False
            or collection.get("flash_attempted") is not False
            or inventory.get("proves_gsi_compatibility") is not False
            or inventory.get("proves_physical_runtime_gate") is not False
            or SHA256_PATTERN.fullmatch(str(
                inventory.get("serial_sha256", ""))) is None):
        raise GsiPreflightError("inventory is not a read-only schema-2 factory capture")

    if (build.get("schema_version") != 2
            or build.get("status") != "passed"
            or build.get("lane") != "android_gsi_arm64"
            or build.get("kind") != "generic_system_image"
            or build.get("product") != "aios_gsi_arm64"
            or build.get("target_device") != "generic_arm64"
            or build.get("artifact_layout") != "gsi_system_product"
            or build.get("deployable_images") != ["system.img", "vbmeta.img"]
            or build.get("installed_files_manifest")
            != "installed-files-system.json"
            or build.get("lane_eligible_for_physical_gates") is not True
            or build.get("proves_physical_runtime_gate") is not False):
        raise GsiPreflightError("build evidence is not the exact ARM64 GSI lane")

    artifacts = build_artifact_map(build)
    required_paths = {"system.img", "vbmeta.img"}
    required_paths.update(
        f"system/product/priv-app/{name}/{name}.apk"
        for name in REQUIRED_PACKAGES
    )
    missing = sorted(required_paths - set(artifacts))
    if missing:
        raise GsiPreflightError(f"GSI evidence lacks required artifacts: {missing}")

    device = property_value(inventory, "ro.product.device")
    checks = {
        "expected_device": device == expected_device,
        "google_hardware": property_value(
            inventory, "ro.product.manufacturer").lower() == "google",
        "arm64_userspace": "arm64-v8a" in property_value(
            inventory, "ro.product.cpu.abilist64").split(","),
        "treble_enabled": property_value(
            inventory, "ro.treble.enabled").lower() == "true",
        "dynamic_partitions": property_value(
            inventory, "ro.boot.dynamic_partitions").lower() == "true",
        "vendor_api_reported": property_value(
            inventory, "ro.vendor.api_level").isdigit(),
        "vndk_reported": bool(property_value(inventory, "ro.vndk.version")),
    }
    return artifacts, checks


def evaluate(
    inventory_path: Path,
    build_path: Path,
    expected_device: str,
) -> dict:
    inventory = load(inventory_path)
    build = load(build_path)
    artifacts, checks = validate_inputs(inventory, build, expected_device)

    device_release = parse_android_release(
        property_value(inventory, "ro.build.version.release"),
        "device Android release",
    )
    gsi_release = parse_android_release(build.get("android_release"),
                                        "GSI Android release")
    device_patch = parse_date(
        property_value(inventory, "ro.build.version.security_patch"),
        "device security patch",
    )
    gsi_patch = parse_date(build.get("security_patch"), "GSI security patch")
    checks["system_release_not_older"] = gsi_release >= device_release
    checks["system_patch_not_older"] = gsi_patch >= device_patch

    capabilities = inventory.get("capabilities")
    if not isinstance(capabilities, dict):
        raise GsiPreflightError("device inventory capabilities must be an object")
    dsu_advertised = str(capabilities.get("dynamic_system_feature", "")).lower() == "true"
    structural = all(checks.values())
    locked = property_value(inventory, "ro.boot.flash.locked") != "0"

    blockers = []
    for name, passed in checks.items():
        if not passed:
            blockers.append(f"failed structural check: {name}")
    if locked:
        blockers.append("bootloader is currently locked; fastboot deployment would wipe on unlock")
    blockers.extend([
        "VINTF negotiation has not been exercised with this exact image",
        "system partition capacity and AVB deployment have not been verified",
        "matching factory restoration and both-slot recovery have not been witnessed",
        "physical telephony, camera, media, and model gates have not run",
    ])

    return {
        "schema_version": 1,
        "status": "candidate" if structural else "incompatible",
        "expected_device": expected_device,
        "observed_device": property_value(inventory, "ro.product.device"),
        "inventory_sha256": sha256(inventory_path),
        "build_evidence_sha256": sha256(build_path),
        "gsi_images": {
            name: {
                "size_bytes": artifacts[name]["size_bytes"],
                "sha256": artifacts[name]["sha256"],
            }
            for name in ("system.img", "vbmeta.img")
        },
        "checks": checks,
        "dsu_advertised": dsu_advertised,
        "dsu_candidate": structural and dsu_advertised and checks[
            "system_patch_not_older"
        ],
        "fastboot_candidate": structural,
        "bootloader_locked": locked,
        "blockers": blockers,
        "safe_to_flash": False,
        "proves_gsi_compatibility": False,
        "proves_physical_runtime_gate": False,
        "evaluated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    }


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    if path.exists():
        raise GsiPreflightError(f"refusing to overwrite GSI preflight: {path}")
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--build-evidence", type=Path, required=True)
    parser.add_argument("--expected-device", default="tegu")
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    try:
        result = evaluate(
            arguments.inventory.resolve(),
            arguments.build_evidence.resolve(),
            arguments.expected_device,
        )
        if arguments.output is not None:
            write_json_atomic(arguments.output, result)
    except (KeyError, OSError, GsiPreflightError) as error:
        print(f"GSI preflight failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] == "candidate" else 2


if __name__ == "__main__":
    raise SystemExit(main())
