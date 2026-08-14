#!/usr/bin/env python3
"""Capture exact post-boot evidence for an evidenced AIOS Pixel A/B OTA."""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import capture_pixel_aios_boot as boot_capture  # noqa: E402


class UpdateEvidenceError(RuntimeError):
    pass


def validate_chain(
    build_path: Path,
    ota_path: Path,
    update_path: Path,
    serial: str,
) -> tuple[dict, dict, dict]:
    build = boot_capture.load(build_path)
    ota = boot_capture.load(ota_path)
    update = boot_capture.load(update_path)
    if (build.get("schema_version") != 2
            or build.get("status") != "passed"
            or build.get("lane") != "pixel9a_tegu_hardware"
            or build.get("product") != "aios_tegu"
            or build.get("target_device") != "tegu"
            or build.get("artifact_layout") != "full_device_target_files"
            or not isinstance(build.get("build_incremental"), str)
            or not isinstance(build.get("build_timestamp"), int)
            or not isinstance(build.get("generated_payloads"), dict)):
        raise UpdateEvidenceError("build record is not an eligible AIOS Pixel update")
    ota_archive = ota.get("ota_archive")
    ota_metadata = ota.get("ota_metadata")
    signature = ota.get("signature_verification")
    if (ota.get("schema_version") != 1
            or ota.get("status") != "passed"
            or ota.get("update_kind") != "full_virtual_ab_ota"
            or ota.get("lane") != build["lane"]
            or ota.get("product") != build["product"]
            or ota.get("target_device") != "tegu"
            or ota.get("build_fingerprint") != build.get("build_fingerprint")
            or ota.get("security_patch") != build.get("security_patch")
            or ota.get("build_evidence_sha256") != boot_capture.sha256(build_path)
            or ota.get("target_files_sha256")
            != build.get("target_files_package", {}).get("sha256")
            or ota.get("contains_required_model_payloads") is not True
            or ota.get("installation_performed") is not False
            or not isinstance(ota_archive, dict)
            or not isinstance(ota_metadata, dict)
            or ota_metadata.get("post-build") != build.get("build_fingerprint")
            or ota_metadata.get("post-build-incremental")
            != build.get("build_incremental")
            or ota_metadata.get("post-timestamp") != str(build.get("build_timestamp"))
            or ota_metadata.get("post-security-patch-level")
            != build.get("security_patch")
            or not isinstance(signature, dict)
            or signature.get("status") != "passed"
            or signature.get("whole_file_and_payload_verified") is not True):
        raise UpdateEvidenceError("OTA record does not bind the Pixel build")
    if (update.get("schema_version") != 1
            or update.get("status") != "update_engine_command_passed"
            or update.get("kind") != "pixel9a_aios_virtual_ab_update"
            or update.get("serial_sha256") != boot_capture.text_sha256(serial)
            or update.get("ota_evidence_sha256") != boot_capture.sha256(ota_path)
            or update.get("ota_archive_sha256") != ota_archive.get("sha256")
            or update.get("target_fingerprint") != build.get("build_fingerprint")
            or update.get("source_fingerprint") == update.get("target_fingerprint")
            or update.get("source_slot") not in {"_a", "_b"}
            or update.get("expected_target_slot") not in {"_a", "_b"}
            or update.get("source_slot") == update.get("expected_target_slot")
            or update.get("staging_removed") is not True
            or update.get("reboot_performed") is not False
            or update.get("proves_update_engine_command_passed") is not True
            or update.get("proves_post_update_boot") is not False
            or update.get("proves_slot_switch") is not False
            or update.get("proves_merge_completed") is not False):
        raise UpdateEvidenceError("update-engine result does not bind the OTA application")
    return build, ota, update


def collect(
    runner,
    build: dict,
    ota: dict,
    update: dict,
    serial: str,
    build_path: Path,
    ota_path: Path,
    update_path: Path,
) -> dict:
    # Reuse the full-device verifier for boot state, encryption, package paths,
    # default dialer, and every evidenced /product artifact. Only its two
    # release-link inputs are synthesized; its first-boot claims are discarded.
    verified = boot_capture.collect(
        runner,
        build,
        {"fastboot_archive": {"sha256": ota["ota_archive"]["sha256"]}},
        {"release_evidence_sha256": boot_capture.sha256(ota_path)},
        serial,
    )
    additional_names = (
        "ro.build.version.incremental",
        "ro.build.date.utc",
        "ro.boot.slot_suffix",
        "ro.virtual_ab.enabled",
        "ro.virtual_ab.compression.enabled",
    )
    additional = {
        name: runner.run(["shell", "getprop", name]) for name in additional_names
    }
    expected = {
        "ro.build.version.incremental": build["build_incremental"],
        "ro.build.date.utc": str(build["build_timestamp"]),
        "ro.boot.slot_suffix": update["expected_target_slot"],
        "ro.virtual_ab.enabled": "true",
        "ro.virtual_ab.compression.enabled": "true",
    }
    for name, value in expected.items():
        if additional.get(name) != value:
            raise UpdateEvidenceError(
                f"post-update Pixel has unexpected property {name}"
            )
    properties = {**verified["properties"], **additional}
    return {
        "schema_version": 1,
        "status": "passed",
        "kind": "pixel9a_aios_virtual_ab_post_update_boot",
        "collected_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "serial_sha256": boot_capture.text_sha256(serial),
        "build_fingerprint": build["build_fingerprint"],
        "source_fingerprint": update["source_fingerprint"],
        "source_slot": update["source_slot"],
        "active_slot": additional["ro.boot.slot_suffix"],
        "properties": properties,
        "packages": verified["packages"],
        "dialer_role_holders": verified["dialer_role_holders"],
        "default_dialer_overlay": verified["default_dialer_overlay"],
        "installed_artifacts": verified["installed_artifacts"],
        "checks": {
            "build_ota_update_chain_verified": True,
            "boot_completed": True,
            "full_device_not_gsi": True,
            "exact_target_fingerprint": True,
            "exact_target_incremental_and_timestamp": True,
            "inactive_slot_became_active": True,
            "virtual_ab_enabled": True,
            "unlocked_test_key_state": True,
            "owner_unlocked_and_setup": True,
            "required_packages_present": True,
            "default_dialer_resolved": True,
            "every_evidenced_product_artifact_verified": True,
        },
        "build_evidence_sha256": boot_capture.sha256(build_path),
        "ota_evidence_sha256": boot_capture.sha256(ota_path),
        "update_result_sha256": boot_capture.sha256(update_path),
        "ota_archive_sha256": ota["ota_archive"]["sha256"],
        "proves_update_engine_command_passed": True,
        "proves_post_update_boot": True,
        "proves_slot_switch": True,
        "proves_model_payload_install": True,
        "proves_merge_completed": False,
        "proves_rollback": False,
        "proves_telephony_gate": False,
        "proves_model_inference": False,
        "proves_model_latency_gate": False,
        "proves_media_gate": False,
    }


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    root = ROOT.resolve()
    if path == root or root in path.parents:
        raise UpdateEvidenceError("physical OTA boot evidence must remain outside source")
    if path.exists():
        raise UpdateEvidenceError(f"refusing to overwrite OTA boot evidence: {path}")
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
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--build-evidence", type=Path, required=True)
    parser.add_argument("--ota-evidence", type=Path, required=True)
    parser.add_argument("--update-result", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        build_path = arguments.build_evidence.resolve()
        ota_path = arguments.ota_evidence.resolve()
        update_path = arguments.update_result.resolve()
        build, ota, update = validate_chain(
            build_path, ota_path, update_path, arguments.serial
        )
        value = collect(
            boot_capture.AdbRunner(arguments.adb, arguments.serial),
            build,
            ota,
            update,
            arguments.serial,
            build_path,
            ota_path,
            update_path,
        )
        write_json_atomic(arguments.output, value)
    except (KeyError, OSError, boot_capture.BootEvidenceError,
            UpdateEvidenceError) as error:
        print(f"Pixel AIOS post-update evidence refused: {error}", file=sys.stderr)
        return 1
    print(f"Pixel AIOS post-update evidence captured: {arguments.output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
