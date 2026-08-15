#!/usr/bin/env python3
"""Cancel an evidenced unverified Pixel Virtual A/B update without implicit reboot."""

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
import capture_pixel_aios_merge as merge_capture  # noqa: E402


class RollbackEvidenceError(RuntimeError):
    pass


FINGERPRINT = re.compile(
    r"^AIOS/aios_tegu/tegu:[^/]+/[^/]+/"
    r"(?P<incremental>[A-Za-z0-9._+-]{1,64}):userdebug/test-keys$"
)
SLOTS = {"_a": "0", "_b": "1"}


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RollbackEvidenceError(f"cannot load {path}: {error}") from error
    if not isinstance(value, dict):
        raise RollbackEvidenceError(f"JSON root must be an object: {path}")
    return value


def incremental(fingerprint: str) -> str:
    match = FINGERPRINT.fullmatch(fingerprint or "")
    if match is None:
        raise RollbackEvidenceError("rollback fingerprint is not an AIOS tegu userdebug build")
    return match.group("incremental")


def validate_chain(
    ota_path: Path,
    update_path: Path,
    serial: str,
) -> tuple[dict, dict]:
    ota = load(ota_path)
    update = load(update_path)
    archive = ota.get("ota_archive")
    if (ota.get("schema_version") != 1
            or ota.get("status") != "passed"
            or ota.get("update_kind") != "full_virtual_ab_ota"
            or ota.get("lane") != "pixel9a_tegu_hardware"
            or ota.get("product") != "aios_tegu"
            or ota.get("target_device") != "tegu"
            or ota.get("virtual_ab_compression") != "true"
            or ota.get("contains_required_model_payloads") is not True
            or ota.get("installation_performed") is not False
            or not isinstance(archive, dict)
            or not isinstance(archive.get("sha256"), str)
            or re.fullmatch(r"[0-9a-f]{64}", archive["sha256"]) is None):
        raise RollbackEvidenceError("OTA record is not an eligible AIOS Pixel update")
    if (update.get("schema_version") != 1
            or update.get("status") != "update_engine_command_passed"
            or update.get("kind") != "pixel9a_aios_virtual_ab_update"
            or update.get("serial_sha256") != boot_capture.text_sha256(serial)
            or update.get("ota_evidence_sha256") != boot_capture.sha256(ota_path)
            or update.get("ota_archive_sha256") != archive["sha256"]
            or update.get("target_fingerprint") != ota.get("build_fingerprint")
            or update.get("source_fingerprint") == update.get("target_fingerprint")
            or update.get("source_slot") not in SLOTS
            or update.get("expected_target_slot") not in SLOTS
            or update.get("source_slot") == update.get("expected_target_slot")
            or update.get("payload_applicability_verified") is not True
            or update.get("payload_space_allocated") is not True
            or update.get("staging_removed") is not True
            or update.get("reboot_performed") is not False
            or update.get("proves_update_engine_command_passed") is not True
            or update.get("proves_post_update_boot") is not False
            or update.get("proves_slot_switch") is not False
            or update.get("proves_merge_completed") is not False):
        raise RollbackEvidenceError("update result does not bind an unapplied reboot")
    incremental(update["source_fingerprint"])
    incremental(update["target_fingerprint"])
    return ota, update


def connected(runner, serial: str) -> None:
    serials = boot_capture.connected_serials(
        runner.run(["devices", "-l"], serial=False)
    )
    if serial not in serials:
        raise RollbackEvidenceError(
            f"authorized ADB device {serial} is not connected, found {serials}"
        )


def assert_slot_flag(runner, command: str, slot: str) -> None:
    try:
        runner.run(["shell", "bootctl", command, SLOTS[slot]])
    except boot_capture.BootEvidenceError as error:
        raise RollbackEvidenceError(f"slot {slot} failed {command}") from error


def base_device_state(runner, serial: str, update: dict) -> tuple[dict, str]:
    connected(runner, serial)
    source = update["source_slot"]
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
        "ro.build.fingerprint": update["source_fingerprint"],
        "ro.build.version.incremental": incremental(update["source_fingerprint"]),
        "ro.boot.slot_suffix": source,
        "ro.virtual_ab.enabled": "true",
        "ro.virtual_ab.compression.enabled": "true",
    }
    for name, value in expected.items():
        if properties.get(name) != value:
            raise RollbackEvidenceError(f"connected Pixel has unexpected property {name}")
    if properties["ro.gsid.image_running"] not in {"", "0"}:
        raise RollbackEvidenceError("connected Pixel is running a GSI")
    for command in ("bootctl", "snapshotctl"):
        if runner.run(["shell", "command", "-v", command]) != f"/system/bin/{command}":
            raise RollbackEvidenceError(f"connected Pixel lacks {command}")
    if runner.run(["shell", "bootctl", "get-current-slot"]) != SLOTS[source]:
        raise RollbackEvidenceError("Pixel is no longer running the OTA source slot")
    assert_slot_flag(runner, "is-slot-marked-successful", source)
    assert_slot_flag(runner, "is-slot-bootable", source)
    snapshot_output = runner.run(["shell", "snapshotctl", "dump"])
    return properties, snapshot_output


def parse_snapshot_state(runner, snapshot_output: str) -> tuple[dict, str]:
    try:
        snapshot = merge_capture.parse_snapshot_dump(snapshot_output)
        boot_status = merge_capture.parse_boot_merge_status(
            runner.run(["shell", "bootctl", "get-snapshot-merge-status"])
        )
    except merge_capture.MergeEvidenceError as error:
        raise RollbackEvidenceError(str(error)) from error
    return snapshot, boot_status


def confirmation_token(serial: str, update: dict) -> str:
    return f"ROLLBACK-{serial}-TO-{incremental(update['source_fingerprint'])}"


def preflight(
    runner,
    update: dict,
    serial: str,
) -> dict:
    _, snapshot_output = base_device_state(runner, serial, update)
    source = update["source_slot"]
    target = update["expected_target_slot"]
    active_number = runner.run(["shell", "bootctl", "get-active-boot-slot"])
    snapshot, boot_status = parse_snapshot_state(runner, snapshot_output)
    if active_number != SLOTS[target]:
        raise RollbackEvidenceError("the evidenced OTA target is not pending next boot")
    if (snapshot["update_state"] != "unverified"
            or snapshot["snapshot_count"] <= 0
            or snapshot["current_slot"] != source
            or snapshot["rollback_indicator"] != "No such file or directory"
            or snapshot["forward_merge_indicator"] != "No such file or directory"
            or not snapshot["source_build_fingerprint_present"]
            or boot_status != "snapshotted"):
        raise RollbackEvidenceError(
            "Virtual A/B update is not in the pre-merge unverified rollback window"
        )
    return {
        "eligible": True,
        "source_fingerprint": update["source_fingerprint"],
        "source_incremental": incremental(update["source_fingerprint"]),
        "source_slot": source,
        "target_fingerprint": update["target_fingerprint"],
        "target_incremental": incremental(update["target_fingerprint"]),
        "pending_target_slot": target,
        "snapshot_update_state": snapshot["update_state"],
        "snapshot_count": snapshot["snapshot_count"],
        "boot_control_merge_status": boot_status,
        "rollback_indicator_absent": True,
        "forward_merge_indicator_absent": True,
        "source_build_fingerprint_present": True,
        "confirmation": confirmation_token(serial, update),
    }


def arm_rollback(
    runner,
    ota: dict,
    update: dict,
    serial: str,
    ota_path: Path,
    update_path: Path,
    confirmation: str,
) -> dict:
    observed = preflight(runner, update, serial)
    token = confirmation_token(serial, update)
    if confirmation != token:
        raise RollbackEvidenceError(f"execution requires exact confirmation: {token}")
    source = update["source_slot"]
    target = update["expected_target_slot"]
    runner.run(["shell", "bootctl", "set-active-boot-slot", SLOTS[source]])
    if runner.run(["shell", "bootctl", "get-current-slot"]) != SLOTS[source]:
        raise RollbackEvidenceError("current source slot changed before a reboot")
    if runner.run(["shell", "bootctl", "get-active-boot-slot"]) != SLOTS[source]:
        raise RollbackEvidenceError("source slot was not armed for the next boot")
    assert_slot_flag(runner, "is-slot-bootable", source)
    return {
        "schema_version": 1,
        "status": "source_slot_armed",
        "kind": "pixel9a_aios_virtual_ab_rollback_prepare",
        "recorded_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "serial_sha256": boot_capture.text_sha256(serial),
        "source_fingerprint": update["source_fingerprint"],
        "source_incremental": observed["source_incremental"],
        "source_slot": source,
        "target_fingerprint": update["target_fingerprint"],
        "target_incremental": observed["target_incremental"],
        "pending_target_slot": target,
        "pre_current_slot": source,
        "pre_active_slot": target,
        "post_current_slot": source,
        "post_active_slot": source,
        "source_slot_bootable_after_arm": True,
        "snapshot_update_state": observed["snapshot_update_state"],
        "snapshot_count": observed["snapshot_count"],
        "boot_control_merge_status": observed["boot_control_merge_status"],
        "rollback_indicator_absent": observed["rollback_indicator_absent"],
        "forward_merge_indicator_absent": observed["forward_merge_indicator_absent"],
        "source_build_fingerprint_present": observed[
            "source_build_fingerprint_present"
        ],
        "ota_evidence_sha256": boot_capture.sha256(ota_path),
        "update_result_sha256": boot_capture.sha256(update_path),
        "ota_archive_sha256": ota["ota_archive"]["sha256"],
        "confirmation_token_sha256": boot_capture.text_sha256(token),
        "reboot_performed": False,
        "target_boot_performed": False,
        "proves_update_engine_command_passed": True,
        "proves_pending_update_was_armed": True,
        "proves_source_slot_boot": False,
        "proves_post_update_boot": False,
        "proves_merge_completed": False,
        "proves_rollback": False,
    }


def validate_prepare(
    path: Path,
    ota: dict,
    update: dict,
    serial: str,
    ota_path: Path,
    update_path: Path,
) -> dict:
    value = load(path)
    source = update["source_slot"]
    target = update["expected_target_slot"]
    if (value.get("schema_version") != 1
            or value.get("status") != "source_slot_armed"
            or value.get("kind") != "pixel9a_aios_virtual_ab_rollback_prepare"
            or value.get("serial_sha256") != boot_capture.text_sha256(serial)
            or value.get("source_fingerprint") != update["source_fingerprint"]
            or value.get("source_incremental")
            != incremental(update["source_fingerprint"])
            or value.get("source_slot") != source
            or value.get("target_fingerprint") != update["target_fingerprint"]
            or value.get("target_incremental")
            != incremental(update["target_fingerprint"])
            or value.get("pending_target_slot") != target
            or value.get("pre_current_slot") != source
            or value.get("pre_active_slot") != target
            or value.get("post_current_slot") != source
            or value.get("post_active_slot") != source
            or value.get("source_slot_bootable_after_arm") is not True
            or value.get("snapshot_update_state") != "unverified"
            or not isinstance(value.get("snapshot_count"), int)
            or value["snapshot_count"] <= 0
            or value.get("boot_control_merge_status") != "snapshotted"
            or value.get("rollback_indicator_absent") is not True
            or value.get("forward_merge_indicator_absent") is not True
            or value.get("source_build_fingerprint_present") is not True
            or value.get("ota_evidence_sha256") != boot_capture.sha256(ota_path)
            or value.get("update_result_sha256") != boot_capture.sha256(update_path)
            or value.get("ota_archive_sha256") != ota["ota_archive"]["sha256"]
            or value.get("confirmation_token_sha256")
            != boot_capture.text_sha256(confirmation_token(serial, update))
            or value.get("reboot_performed") is not False
            or value.get("target_boot_performed") is not False
            or value.get("proves_update_engine_command_passed") is not True
            or value.get("proves_pending_update_was_armed") is not True
            or value.get("proves_source_slot_boot") is not False
            or value.get("proves_post_update_boot") is not False
            or value.get("proves_merge_completed") is not False
            or value.get("proves_rollback") is not False):
        raise RollbackEvidenceError("rollback preparation record is invalid")
    return value


def capture_rollback(
    runner,
    ota: dict,
    update: dict,
    serial: str,
    ota_path: Path,
    update_path: Path,
    prepare_path: Path,
) -> dict:
    validate_prepare(
        prepare_path, ota, update, serial, ota_path, update_path
    )
    _, snapshot_output = base_device_state(runner, serial, update)
    source = update["source_slot"]
    if runner.run(["shell", "bootctl", "get-active-boot-slot"]) != SLOTS[source]:
        raise RollbackEvidenceError("source slot is not current and active after reboot")
    snapshot, boot_status = parse_snapshot_state(runner, snapshot_output)
    if (snapshot["update_state"] != "none"
            or snapshot["snapshot_count"] != 0
            or snapshot["current_slot"] != source
            or snapshot["rollback_indicator"] != "No such file or directory"
            or snapshot["forward_merge_indicator"] != "No such file or directory"
            or snapshot["source_build_fingerprint_present"]
            or boot_status != "none"):
        raise RollbackEvidenceError(
            "rolled-back Virtual A/B snapshots have not been fully removed"
        )
    return {
        "schema_version": 1,
        "status": "passed",
        "kind": "pixel9a_aios_virtual_ab_rollback",
        "captured_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "serial_sha256": boot_capture.text_sha256(serial),
        "source_fingerprint": update["source_fingerprint"],
        "source_incremental": incremental(update["source_fingerprint"]),
        "source_slot": source,
        "cancelled_target_fingerprint": update["target_fingerprint"],
        "cancelled_target_incremental": incremental(update["target_fingerprint"]),
        "cancelled_target_slot": update["expected_target_slot"],
        "final_active_slot": source,
        "ota_evidence_sha256": boot_capture.sha256(ota_path),
        "update_result_sha256": boot_capture.sha256(update_path),
        "prepare_result_sha256": boot_capture.sha256(prepare_path),
        "ota_archive_sha256": ota["ota_archive"]["sha256"],
        "snapshot_dump_sha256": boot_capture.text_sha256(snapshot_output),
        "checks": {
            "exact_ota_update_prepare_chain_verified": True,
            "source_boot_completed": True,
            "full_device_not_gsi": True,
            "exact_source_fingerprint": True,
            "source_slot_current_and_active": True,
            "source_slot_marked_successful": True,
            "source_slot_bootable": True,
            "unverified_update_cancelled": True,
            "snapshot_update_state_none": True,
            "no_snapshot_records": True,
            "no_snapshot_indicators": True,
            "boot_control_merge_status_none": True,
        },
        "target_boot_performed": False,
        "fresh_update_required": True,
        "proves_update_engine_command_passed": True,
        "proves_source_slot_boot": True,
        "proves_post_update_boot": False,
        "proves_merge_completed": False,
        "proves_rollback": True,
        "proves_telephony_gate": False,
        "proves_model_inference": False,
        "proves_model_latency_gate": False,
        "proves_media_gate": False,
    }


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    root = ROOT.resolve()
    if path == root or root in path.parents:
        raise RollbackEvidenceError("physical rollback evidence must remain outside source")
    if path.exists():
        raise RollbackEvidenceError(f"refusing to overwrite rollback evidence: {path}")
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


def add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--ota-evidence", type=Path, required=True)
    parser.add_argument("--update-result", type=Path, required=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    phases = parser.add_subparsers(dest="phase", required=True)
    prepare_parser = phases.add_parser("prepare")
    add_common(prepare_parser)
    prepare_parser.add_argument("--execute", action="store_true")
    prepare_parser.add_argument("--confirmation")
    prepare_parser.add_argument("--output", type=Path)
    capture_parser = phases.add_parser("capture")
    add_common(capture_parser)
    capture_parser.add_argument("--prepare-result", type=Path, required=True)
    capture_parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        ota_path = arguments.ota_evidence.resolve()
        update_path = arguments.update_result.resolve()
        ota, update = validate_chain(ota_path, update_path, arguments.serial)
        runner = boot_capture.AdbRunner(arguments.adb, arguments.serial)
        if arguments.phase == "prepare":
            if arguments.execute != bool(arguments.output):
                raise RollbackEvidenceError("executed preparation requires one output path")
            if arguments.execute:
                value = arm_rollback(
                    runner, ota, update, arguments.serial, ota_path, update_path,
                    arguments.confirmation or "",
                )
                write_json_atomic(arguments.output, value)
                print(f"Pending update rollback armed; reboot was not performed: "
                      f"{arguments.output.resolve()}")
            else:
                value = preflight(runner, update, arguments.serial)
                print(json.dumps(value, indent=2, sort_keys=True))
                print(f"Exact execution confirmation: "
                      f"{confirmation_token(arguments.serial, update)}")
        else:
            value = capture_rollback(
                runner, ota, update, arguments.serial, ota_path, update_path,
                arguments.prepare_result.resolve(),
            )
            write_json_atomic(arguments.output, value)
            print(f"Pending update rollback evidence captured: {arguments.output.resolve()}")
    except (KeyError, OSError, boot_capture.BootEvidenceError,
            RollbackEvidenceError) as error:
        print(f"Pixel AIOS rollback refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
