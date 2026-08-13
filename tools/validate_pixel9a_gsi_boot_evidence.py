#!/usr/bin/env python3
"""Validate exact-image Pixel 9a DSU first-boot evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path


SHA256 = re.compile(r"[0-9a-f]{64}")
REQUIRED_PACKAGES = {
    "com.aios.phone",
    "com.aios.messaging",
    "com.aios.callintelligence",
    "com.aios.contextintelligence",
    "com.aios.mediaintelligence",
    "com.aios.modelbroker",
}
EXPECTED_CHECKS = {
    "exact_preflight_chain_verified",
    "dsu_running",
    "boot_completed",
    "exact_build_fingerprint",
    "arm64_userdebug",
    "required_packages_present",
    "default_dialer_resolved",
    "every_evidenced_system_artifact_verified",
}


class PixelBootEvidenceError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PixelBootEvidenceError(message)


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PixelBootEvidenceError(f"cannot load {path}: {error}") from error
    if not isinstance(value, dict):
        raise PixelBootEvidenceError(f"JSON root must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact_map(build: dict) -> dict[str, dict]:
    rows = build.get("artifacts")
    require(isinstance(rows, list) and rows, "build evidence lacks artifacts")
    result = {}
    for row in rows:
        require(isinstance(row, dict), "build artifact row is malformed")
        path = row.get("path")
        require(isinstance(path, str) and path and path not in result,
                "build artifact paths must be unique strings")
        require(isinstance(row.get("size_bytes"), int)
                and row["size_bytes"] > 0
                and SHA256.fullmatch(str(row.get("sha256", ""))) is not None,
                f"build artifact identity is invalid: {path}")
        result[path] = row
    return result


def validate(
    evidence_path: Path,
    inventory_path: Path,
    preflight_path: Path,
    build_path: Path,
) -> dict:
    evidence = load(evidence_path)
    inventory = load(inventory_path)
    preflight = load(preflight_path)
    build = load(build_path)
    artifacts = artifact_map(build)

    require(inventory.get("schema_version") == 2
            and inventory.get("status") == "captured"
            and inventory.get("adb_state") == "device"
            and SHA256.fullmatch(str(inventory.get("serial_sha256", "")))
            is not None,
            "inventory is not a schema-2 captured device")
    require(preflight.get("schema_version") == 1
            and preflight.get("status") == "candidate"
            and preflight.get("expected_device") == "tegu"
            and preflight.get("observed_device") == "tegu"
            and preflight.get("dsu_candidate") is True
            and preflight.get("safe_to_flash") is False
            and preflight.get("proves_gsi_compatibility") is False
            and preflight.get("proves_physical_runtime_gate") is False,
            "preflight is not the unproven Pixel 9a DSU candidate")
    require(build.get("schema_version") == 2
            and build.get("status") == "passed"
            and build.get("lane") == "android_gsi_arm64"
            and build.get("product") == "aios_gsi_arm64"
            and build.get("target_device") == "generic_arm64"
            and build.get("deployable_images") == ["system.img", "vbmeta.img"]
            and build.get("proves_physical_runtime_gate") is False,
            "build is not the exact eligible ARM64 GSI")

    require(evidence.get("schema_version") == 1
            and evidence.get("status") == "passed"
            and evidence.get("kind") == "pixel9a_gsi_dsu_first_boot",
            "first-boot evidence has the wrong identity")
    try:
        collected = datetime.fromisoformat(
            str(evidence.get("collected_at_utc", "")).replace("Z", "+00:00")
        )
    except ValueError as error:
        raise PixelBootEvidenceError(
            "first-boot collection timestamp is invalid"
        ) from error
    require(collected.tzinfo is not None,
            "first-boot collection timestamp must include a timezone")
    require(evidence.get("serial_sha256") == inventory.get("serial_sha256")
            and evidence.get("inventory_sha256") == sha256(inventory_path)
            and evidence.get("preflight_sha256") == sha256(preflight_path)
            and evidence.get("build_evidence_sha256") == sha256(build_path)
            and evidence.get("avb_evidence_sha256")
            == preflight.get("avb_evidence_sha256")
            and evidence.get("dsu_payload_evidence_sha256")
            == preflight.get("dsu_payload_evidence_sha256")
            and evidence.get("system_interface_evidence_sha256")
            == preflight.get("system_interface_evidence_sha256"),
            "first-boot evidence is not bound to its input chain")

    expected_images = {
        name: {
            "size_bytes": artifacts[name]["size_bytes"],
            "sha256": artifacts[name]["sha256"],
        }
        for name in ("system.img", "vbmeta.img")
    }
    require(preflight.get("gsi_images") == expected_images
            and evidence.get("images") == expected_images,
            "first-boot evidence does not bind the deployable images")
    require(evidence.get("build_fingerprint") == build.get("build_fingerprint"),
            "first-boot build fingerprint differs from build evidence")

    properties = evidence.get("properties")
    require(isinstance(properties, dict)
            and properties.get("sys.boot_completed") == "1"
            and properties.get("ro.gsid.image_running") == "1"
            and properties.get("ro.build.fingerprint")
            == build.get("build_fingerprint")
            and properties.get("ro.build.type") == "userdebug"
            and properties.get("ro.build.version.release")
            == build.get("android_release")
            and properties.get("ro.build.version.security_patch")
            == build.get("security_patch")
            and "arm64-v8a" in str(
                properties.get("ro.product.cpu.abilist64", "")
            ).split(","),
            "first-boot properties do not prove the exact completed ARM64 DSU boot")

    packages = evidence.get("packages")
    require(isinstance(packages, dict) and set(packages) == REQUIRED_PACKAGES
            and all(
                re.fullmatch(r"package:/system/product/(?:app|priv-app)/.+\.apk",
                             str(path)) is not None
                for path in packages.values()
            ),
            "required AIOS packages are not installed from system/product")
    require(evidence.get("dialer_role_holders") == ["com.aios.phone"]
            and "com.aios.phone" in str(
                evidence.get("default_dialer_overlay", "")
            ),
            "fresh-user default dialer is not AIOS Phone")

    expected_system = {
        "/" + path: row for path, row in artifacts.items()
        if path.startswith("system/") and path != "system.img"
    }
    observed_rows = evidence.get("installed_artifacts")
    require(isinstance(observed_rows, list)
            and len(observed_rows) == len(expected_system),
            "first-boot artifact inventory is incomplete")
    observed = {}
    for row in observed_rows:
        require(isinstance(row, dict), "first-boot artifact row is malformed")
        path = row.get("path")
        require(isinstance(path, str) and path not in observed,
                "first-boot artifact paths must be unique strings")
        observed[path] = row
    require(set(observed) == set(expected_system)
            and all(
                observed[path].get("size_bytes")
                == expected_system[path].get("size_bytes")
                and observed[path].get("sha256")
                == expected_system[path].get("sha256")
                for path in expected_system
            ),
            "installed AIOS artifacts do not match the exact build evidence")

    checks = evidence.get("checks")
    require(isinstance(checks, dict) and set(checks) == EXPECTED_CHECKS
            and all(checks[name] is True for name in EXPECTED_CHECKS),
            "first-boot checks are incomplete")
    require(evidence.get("proves_gsi_compatibility") is True
            and evidence.get("proves_boot_first_boot") is True
            and evidence.get("proves_physical_runtime_gate") is False
            and evidence.get("proves_telephony_gate") is False
            and evidence.get("proves_model_latency_gate") is False
            and evidence.get("proves_media_gate") is False
            and evidence.get("proves_factory_restore") is False,
            "first-boot evidence overclaims or omits its proof boundary")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--preflight", type=Path, required=True)
    parser.add_argument("--build-evidence", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        validate(
            arguments.evidence.resolve(),
            arguments.inventory.resolve(),
            arguments.preflight.resolve(),
            arguments.build_evidence.resolve(),
        )
    except (KeyError, OSError, PixelBootEvidenceError) as error:
        print(f"Pixel 9a GSI boot evidence invalid: {error}", file=sys.stderr)
        return 1
    print("Pixel 9a GSI first-boot evidence is valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
