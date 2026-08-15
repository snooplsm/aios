#!/usr/bin/env python3
"""Capture fail-closed Virtual A/B merge evidence for an updated AIOS Pixel."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import capture_pixel_aios_boot as boot_capture  # noqa: E402


class MergeEvidenceError(RuntimeError):
    pass


SNAPSHOT_STATES = {
    "none",
    "initiated",
    "unverified",
    "merging",
    "mergeneedsreboot",
    "mergecompleted",
    "mergefailed",
    "cancelled",
}
BOOT_MERGE_STATES = {
    "none",
    "unknown",
    "snapshotted",
    "merging",
    "cancelled",
}


def validate_post_update(path: Path, serial: str) -> dict:
    value = boot_capture.load(path)
    properties = value.get("properties")
    checks = value.get("checks")
    if (value.get("schema_version") != 1
            or value.get("status") != "passed"
            or value.get("kind") != "pixel9a_aios_virtual_ab_post_update_boot"
            or value.get("serial_sha256") != boot_capture.text_sha256(serial)
            or not isinstance(value.get("build_fingerprint"), str)
            or not value["build_fingerprint"].startswith("AIOS/aios_tegu/tegu:")
            or value.get("source_fingerprint") == value.get("build_fingerprint")
            or value.get("source_slot") not in {"_a", "_b"}
            or value.get("active_slot") not in {"_a", "_b"}
            or value.get("source_slot") == value.get("active_slot")
            or not isinstance(properties, dict)
            or not isinstance(checks, dict)
            or properties.get("ro.build.fingerprint") != value["build_fingerprint"]
            or properties.get("ro.product.device") != "tegu"
            or properties.get("ro.boot.slot_suffix") != value["active_slot"]
            or properties.get("ro.virtual_ab.enabled") != "true"
            or properties.get("ro.virtual_ab.compression.enabled") != "true"
            or checks.get("build_ota_update_chain_verified") is not True
            or checks.get("exact_target_fingerprint") is not True
            or checks.get("inactive_slot_became_active") is not True
            or value.get("proves_update_engine_command_passed") is not True
            or value.get("proves_post_update_boot") is not True
            or value.get("proves_slot_switch") is not True
            or value.get("proves_merge_completed") is not False):
        raise MergeEvidenceError(
            "post-update record does not bind an unmerged AIOS Pixel update"
        )
    return value


def unique_field(output: str, label: str) -> str:
    values = [
        line.split(":", 1)[1].strip()
        for line in output.splitlines()
        if line.startswith(label + ":")
    ]
    if len(values) != 1:
        raise MergeEvidenceError(f"snapshot dump lacks one {label} field")
    return values[0]


def parse_snapshot_dump(output: str) -> dict:
    state = unique_field(output, "Update state").lower()
    current_slot = unique_field(output, "Current slot")
    rollback_indicator = unique_field(output, "Rollback indicator")
    forward_indicator = unique_field(output, "Forward merge indicator")
    source_fingerprint = unique_field(output, "Source build fingerprint")
    if state not in SNAPSHOT_STATES:
        raise MergeEvidenceError("snapshot dump has an unknown update state")
    if current_slot not in {"_a", "_b"}:
        raise MergeEvidenceError("snapshot dump has an invalid current slot")
    snapshot_count = sum(
        1 for line in output.splitlines() if line.startswith("Snapshot: ")
    )
    return {
        "update_state": state,
        "current_slot": current_slot,
        "snapshot_count": snapshot_count,
        "rollback_indicator": rollback_indicator,
        "forward_merge_indicator": forward_indicator,
        "source_build_fingerprint_present": bool(source_fingerprint),
    }


def parse_boot_merge_status(output: str) -> str:
    values = [line.strip().lower() for line in output.splitlines()
              if line.strip().lower() in BOOT_MERGE_STATES]
    if len(values) != 1:
        raise MergeEvidenceError("boot control returned an invalid merge status")
    return values[0]


def collect(runner, post_update: dict, serial: str, post_update_path: Path) -> dict:
    serials = boot_capture.connected_serials(
        runner.run(["devices", "-l"], serial=False)
    )
    if serial not in serials:
        raise MergeEvidenceError(
            f"authorized ADB device {serial} is not connected, found {serials}"
        )
    property_names = (
        "sys.boot_completed",
        "ro.gsid.image_running",
        "ro.product.device",
        "ro.build.fingerprint",
        "ro.build.version.incremental",
        "ro.boot.slot_suffix",
        "ro.virtual_ab.enabled",
        "ro.virtual_ab.compression.enabled",
    )
    properties = {
        name: runner.run(["shell", "getprop", name]) for name in property_names
    }
    expected = {
        "sys.boot_completed": "1",
        "ro.product.device": "tegu",
        "ro.build.fingerprint": post_update["build_fingerprint"],
        "ro.build.version.incremental": post_update["properties"][
            "ro.build.version.incremental"
        ],
        "ro.boot.slot_suffix": post_update["active_slot"],
        "ro.virtual_ab.enabled": "true",
        "ro.virtual_ab.compression.enabled": "true",
    }
    for name, value in expected.items():
        if properties.get(name) != value:
            raise MergeEvidenceError(
                f"connected Pixel has unexpected property {name}"
            )
    if properties["ro.gsid.image_running"] not in {"", "0"}:
        raise MergeEvidenceError("connected Pixel is running a GSI")
    required_tools = {
        "snapshotctl": "/system/bin/snapshotctl",
        "bootctl": "/system/bin/bootctl",
    }
    for command, expected_path in required_tools.items():
        if runner.run(["shell", "command", "-v", command]) != expected_path:
            raise MergeEvidenceError(f"connected Pixel lacks {command}")

    snapshot_output = runner.run(["shell", "snapshotctl", "dump"])
    snapshot = parse_snapshot_dump(snapshot_output)
    boot_merge_status = parse_boot_merge_status(
        runner.run(["shell", "bootctl", "get-snapshot-merge-status"])
    )
    slot_number = "0" if post_update["active_slot"] == "_a" else "1"
    current_slot_number = runner.run(["shell", "bootctl", "get-current-slot"])
    active_slot_number = runner.run(["shell", "bootctl", "get-active-boot-slot"])
    if current_slot_number != slot_number or active_slot_number != slot_number:
        raise MergeEvidenceError("boot control does not bind the active target slot")
    runner.run(["shell", "bootctl", "is-slot-marked-successful", slot_number])

    if (snapshot["update_state"] != "none"
            or snapshot["snapshot_count"] != 0
            or snapshot["current_slot"] != post_update["active_slot"]
            or snapshot["rollback_indicator"] != "No such file or directory"
            or snapshot["forward_merge_indicator"] != "No such file or directory"
            or snapshot["source_build_fingerprint_present"]
            or boot_merge_status != "none"):
        raise MergeEvidenceError(
            "Virtual A/B merge is incomplete or snapshot state remains"
        )

    return {
        "schema_version": 1,
        "status": "passed",
        "kind": "pixel9a_aios_virtual_ab_merge",
        "captured_at": datetime.now(timezone.utc).replace(
            microsecond=0
        ).isoformat(),
        "serial_sha256": boot_capture.text_sha256(serial),
        "build_fingerprint": post_update["build_fingerprint"],
        "build_incremental": properties["ro.build.version.incremental"],
        "active_slot": post_update["active_slot"],
        "snapshot_update_state": snapshot["update_state"],
        "snapshot_count": snapshot["snapshot_count"],
        "boot_control_merge_status": boot_merge_status,
        "current_slot_marked_successful": True,
        "snapshot_dump_sha256": boot_capture.text_sha256(snapshot_output),
        "post_update_evidence_sha256": boot_capture.sha256(post_update_path),
        "checks": {
            "exact_post_update_chain_verified": True,
            "exact_target_still_booted": True,
            "target_slot_current_and_active": True,
            "target_slot_marked_successful": True,
            "snapshot_update_state_none": True,
            "no_snapshot_records": True,
            "no_merge_indicators": True,
            "boot_control_merge_status_none": True,
        },
        "proves_post_update_boot": True,
        "proves_slot_switch": True,
        "proves_merge_completed": True,
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
        raise MergeEvidenceError("physical merge evidence must remain outside source")
    if path.exists():
        raise MergeEvidenceError(f"refusing to overwrite merge evidence: {path}")
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
    parser.add_argument("--post-update-evidence", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        post_update_path = arguments.post_update_evidence.resolve()
        post_update = validate_post_update(post_update_path, arguments.serial)
        value = collect(
            boot_capture.AdbRunner(arguments.adb, arguments.serial),
            post_update,
            arguments.serial,
            post_update_path,
        )
        write_json_atomic(arguments.output, value)
    except (KeyError, OSError, boot_capture.BootEvidenceError,
            MergeEvidenceError) as error:
        print(f"Pixel AIOS merge evidence refused: {error}", file=sys.stderr)
        return 1
    print(f"Pixel AIOS merge evidence captured: {arguments.output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
