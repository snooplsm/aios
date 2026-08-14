#!/usr/bin/env python3
"""Preflight and optionally apply an evidenced AIOS Pixel Virtual A/B OTA."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import struct
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OTA_METADATA = "META-INF/com/android/metadata"
PAYLOAD = "payload.bin"
PAYLOAD_PROPERTIES = "payload_properties.txt"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
SAFE_INCREMENTAL = re.compile(r"[A-Za-z0-9._+-]{1,64}")
SAFE_REMOTE = re.compile(r"/data/ota_package/aios-[0-9a-f]{16}\.(?:zip|properties)")


class UpdateError(RuntimeError):
    pass


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise UpdateError(f"cannot load {path}: {error}") from error
    if not isinstance(value, dict):
        raise UpdateError(f"expected a JSON object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def parse_key_values(text: str, label: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise UpdateError(f"{label} contains an invalid line")
        key, value = line.split("=", 1)
        if not key or not value or key in values:
            raise UpdateError(f"{label} contains an invalid or duplicate key")
        values[key] = value
    return values


def parse_property_files(value: str) -> dict[str, tuple[int, int]]:
    records: dict[str, tuple[int, int]] = {}
    for token in value.split(","):
        fields = token.split(":")
        if len(fields) != 3 or not fields[0] or fields[0] in records:
            raise UpdateError("OTA property-files metadata is invalid")
        try:
            offset = int(fields[1])
            size = int(fields[2])
        except ValueError as error:
            raise UpdateError("OTA property-files offsets are invalid") from error
        if offset < 0 or size <= 0:
            raise UpdateError("OTA property-files ranges are invalid")
        records[fields[0]] = (offset, size)
    return records


def zip_data_offset(stream, info: zipfile.ZipInfo) -> int:
    stream.seek(info.header_offset)
    header = stream.read(30)
    if len(header) != 30:
        raise UpdateError("OTA payload local ZIP header is truncated")
    fields = struct.unpack("<IHHHHHIIIHH", header)
    if fields[0] != 0x04034B50:
        raise UpdateError("OTA payload local ZIP header has invalid magic")
    return info.header_offset + 30 + fields[-2] + fields[-1]


def verify_ota_input(evidence: dict, evidence_path: Path, archive: Path) -> dict:
    archive_record = evidence.get("ota_archive")
    signature = evidence.get("signature_verification")
    payload_record = evidence.get("payload")
    evidenced_metadata = evidence.get("ota_metadata")
    if (evidence.get("schema_version") != 1
            or evidence.get("status") != "passed"
            or evidence.get("update_kind") != "full_virtual_ab_ota"
            or evidence.get("lane") != "pixel9a_tegu_hardware"
            or evidence.get("product") != "aios_tegu"
            or evidence.get("target_device") != "tegu"
            or evidence.get("signing_state")
            != "public_android_test_keys_unlocked_bootloader_only"
            or evidence.get("contains_required_model_payloads") is not True
            or evidence.get("installation_performed") is not False
            or not isinstance(signature, dict)
            or signature.get("status") != "passed"
            or signature.get("whole_file_and_payload_verified") is not True
            or not isinstance(archive_record, dict)
            or not isinstance(payload_record, dict)
            or not isinstance(evidenced_metadata, dict)
            or not archive.is_file()
            or archive_record.get("size_bytes") != archive.stat().st_size
            or SHA256_PATTERN.fullmatch(str(archive_record.get("sha256", ""))) is None
            or archive_record["sha256"] != sha256(archive)):
        raise UpdateError("OTA archive does not match passed release evidence")

    try:
        with archive.open("rb") as raw_stream, zipfile.ZipFile(raw_stream) as ota:
            names = [item.filename for item in ota.infolist()]
            if len(names) != len(set(names)):
                raise UpdateError("OTA archive contains duplicate members")
            missing = {OTA_METADATA, PAYLOAD, PAYLOAD_PROPERTIES} - set(names)
            if missing:
                raise UpdateError(f"OTA archive lacks required members: {sorted(missing)}")
            metadata_bytes = ota.read(OTA_METADATA)
            properties_bytes = ota.read(PAYLOAD_PROPERTIES)
            try:
                metadata_text = metadata_bytes.decode("utf-8")
                properties_text = properties_bytes.decode("utf-8")
            except UnicodeDecodeError as error:
                raise UpdateError("OTA metadata is not UTF-8") from error
            metadata = parse_key_values(metadata_text, OTA_METADATA)
            properties = parse_key_values(properties_text, PAYLOAD_PROPERTIES)
            payload_info = ota.getinfo(PAYLOAD)
            if payload_info.compress_type != zipfile.ZIP_STORED:
                raise UpdateError("update_engine requires an uncompressed payload member")
            payload_offset = zip_data_offset(raw_stream, payload_info)
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise UpdateError(f"cannot inspect OTA archive: {error}") from error

    if metadata != evidenced_metadata:
        raise UpdateError("OTA metadata differs from release evidence")
    if hashlib.sha256(metadata_bytes).hexdigest() != evidence.get("ota_metadata_sha256"):
        raise UpdateError("OTA metadata digest differs from release evidence")
    if (hashlib.sha256(properties_bytes).hexdigest()
            != evidence.get("payload_properties_sha256")):
        raise UpdateError("payload properties digest differs from release evidence")
    try:
        property_size = int(properties["FILE_SIZE"])
        property_hash = base64.b64decode(properties["FILE_HASH"], validate=True).hex()
    except (KeyError, ValueError) as error:
        raise UpdateError("payload properties are incomplete") from error
    if (property_size != payload_info.file_size
            or property_size != payload_record.get("size_bytes")
            or property_hash != payload_record.get("sha256")):
        raise UpdateError("payload properties differ from release evidence")
    property_files = parse_property_files(metadata.get("ota-property-files", ""))
    if property_files.get(PAYLOAD) != (payload_offset, payload_info.file_size):
        raise UpdateError("OTA streaming payload range is invalid")
    if (metadata.get("ota-type") != "AB"
            or metadata.get("pre-device") != "tegu"
            or metadata.get("post-build") != evidence.get("build_fingerprint")
            or metadata.get("post-security-patch-level")
            != evidence.get("security_patch")):
        raise UpdateError("OTA target metadata differs from release evidence")
    incremental = metadata.get("post-build-incremental", "")
    if SAFE_INCREMENTAL.fullmatch(incremental) is None:
        raise UpdateError("OTA target incremental is invalid")
    try:
        timestamp = int(metadata["post-timestamp"])
    except (KeyError, ValueError) as error:
        raise UpdateError("OTA target timestamp is invalid") from error
    if timestamp <= 0:
        raise UpdateError("OTA target timestamp is invalid")
    return {
        "evidence_sha256": sha256(evidence_path),
        "archive_sha256": archive_record["sha256"],
        "archive_size_bytes": archive_record["size_bytes"],
        "payload_offset_bytes": payload_offset,
        "payload_size_bytes": payload_info.file_size,
        "payload_properties": properties_text.rstrip("\n"),
        "target_fingerprint": metadata["post-build"],
        "target_incremental": incremental,
        "target_timestamp": timestamp,
        "target_security_patch": metadata["post-security-patch-level"],
    }


class AdbRunner:
    def __init__(self, executable: Path, serial: str):
        self.executable = executable.resolve()
        self.serial = serial
        if not self.executable.is_file():
            raise UpdateError(f"adb executable not found: {self.executable}")

    def run(
        self,
        arguments: list[str],
        *,
        serial: bool = True,
        timeout: int | None = 20,
    ) -> str:
        command = [str(self.executable)]
        if serial:
            command.extend(["-s", self.serial])
        command.extend(arguments)
        try:
            completed = subprocess.run(
                command,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            raise UpdateError(f"adb command timed out: {' '.join(arguments)}") from error
        if completed.returncode != 0:
            raise UpdateError(
                f"adb command failed ({' '.join(arguments)}): "
                f"{completed.stdout.strip()}"
            )
        return completed.stdout.strip()


def connected_devices(output: str) -> dict[str, str]:
    devices: dict[str, str] = {}
    for line in output.splitlines():
        fields = line.split()
        if not fields or fields[0] == "List":
            continue
        if len(fields) < 2 or fields[0] in devices:
            raise UpdateError("adb returned malformed or duplicate device state")
        devices[fields[0]] = fields[1]
    return devices


def parse_data_available_kib(output: str) -> int:
    rows = [line.split() for line in output.splitlines() if line.strip()]
    if len(rows) < 2 or len(rows[-1]) < 4:
        raise UpdateError("cannot parse /data free space")
    try:
        available = int(rows[-1][3])
    except ValueError as error:
        raise UpdateError("cannot parse /data free space") from error
    if available < 0:
        raise UpdateError("cannot parse /data free space")
    return available


def inspect_device(runner, serial: str, ota: dict) -> dict:
    devices = connected_devices(runner.run(["devices", "-l"], serial=False))
    if devices.get(serial) != "device":
        raise UpdateError(
            f"authorized ADB device {serial} is not connected and ready: {devices}"
        )
    property_names = (
        "sys.boot_completed",
        "ro.product.device",
        "ro.build.fingerprint",
        "ro.build.version.incremental",
        "ro.build.date.utc",
        "ro.build.version.security_patch",
        "ro.build.tags",
        "ro.boot.slot_suffix",
        "ro.boot.flash.locked",
        "ro.boot.verifiedbootstate",
        "ro.boot.vbmeta.device_state",
        "ro.virtual_ab.enabled",
        "ro.virtual_ab.compression.enabled",
        "ro.aios.version",
    )
    properties = {
        name: runner.run(["shell", "getprop", name]) for name in property_names
    }
    exact = {
        "sys.boot_completed": "1",
        "ro.product.device": "tegu",
        "ro.build.tags": "test-keys",
        "ro.boot.flash.locked": "0",
        "ro.boot.verifiedbootstate": "orange",
        "ro.boot.vbmeta.device_state": "unlocked",
        "ro.virtual_ab.enabled": "true",
        "ro.virtual_ab.compression.enabled": "true",
    }
    for name, expected in exact.items():
        if properties.get(name) != expected:
            raise UpdateError(f"connected Pixel has unexpected property {name}")
    if not properties["ro.build.fingerprint"].startswith("AIOS/aios_tegu/tegu:"):
        raise UpdateError("connected Pixel is not running the AIOS full-device product")
    if not properties["ro.aios.version"]:
        raise UpdateError("connected Pixel lacks the AIOS version property")
    if properties["ro.boot.slot_suffix"] not in {"_a", "_b"}:
        raise UpdateError("connected Pixel has an invalid active slot")
    if runner.run(["shell", "command", "-v", "update_engine_client"]) \
            != "/system/bin/update_engine_client":
        raise UpdateError("connected Pixel lacks update_engine_client")
    service = runner.run([
        "shell", "service", "check", "android.os.UpdateEngineService",
    ])
    if "found" not in service.lower():
        raise UpdateError("connected Pixel lacks the update engine Binder service")
    available_kib = parse_data_available_kib(
        runner.run(["shell", "df", "-k", "/data"])
    )
    try:
        current_timestamp = int(properties["ro.build.date.utc"])
    except ValueError as error:
        raise UpdateError("connected Pixel has an invalid build timestamp") from error
    if current_timestamp <= 0:
        raise UpdateError("connected Pixel has an invalid build timestamp")
    required_kib = (ota["archive_size_bytes"] + 2 * 1024**3 + 1023) // 1024
    reasons = []
    if properties["ro.build.fingerprint"] == ota["target_fingerprint"]:
        reasons.append("same_build")
    if ota["target_timestamp"] <= current_timestamp:
        reasons.append("target_not_newer")
    if ota["target_security_patch"] < properties["ro.build.version.security_patch"]:
        reasons.append("security_patch_downgrade")
    if available_kib < required_kib:
        reasons.append("insufficient_staging_space")
    return {
        "source_fingerprint": properties["ro.build.fingerprint"],
        "source_incremental": properties["ro.build.version.incremental"],
        "source_timestamp": current_timestamp,
        "source_security_patch": properties["ro.build.version.security_patch"],
        "source_slot": properties["ro.boot.slot_suffix"],
        "expected_target_slot": "_b" if properties["ro.boot.slot_suffix"] == "_a" else "_a",
        "data_available_kib": available_kib,
        "data_required_kib": required_kib,
        "install_eligible": not reasons,
        "ineligibility_reasons": reasons,
    }


def require_update_confirmation(
    serial: str, target_incremental: str, confirmation: str | None
) -> None:
    expected = f"APPLY-{serial}-TO-{target_incremental}"
    if confirmation != expected:
        raise UpdateError(f"execution requires --confirm-update {expected}")


def write_text_atomic(path: Path, value: str) -> None:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(value)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def write_json_atomic(path: Path, value: dict) -> None:
    if path.exists():
        raise UpdateError(f"refusing to overwrite OTA result: {path}")
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


def apply_update(
    runner,
    ota: dict,
    archive: Path,
    serial: str,
    device: dict,
    confirmation: str | None,
) -> dict:
    if not device["install_eligible"]:
        raise UpdateError(
            "connected Pixel is not eligible for this OTA: "
            + ", ".join(device["ineligibility_reasons"])
        )
    require_update_confirmation(serial, ota["target_incremental"], confirmation)
    stem = f"/data/ota_package/aios-{ota['archive_sha256'][:16]}"
    remote_archive = stem + ".zip"
    remote_properties = stem + ".properties"
    if (SAFE_REMOTE.fullmatch(remote_archive) is None
            or SAFE_REMOTE.fullmatch(remote_properties) is None):
        raise UpdateError("refusing unsafe OTA staging paths")
    runner.run(["root"], timeout=60)
    runner.run(["wait-for-device"], timeout=60)
    runner.run(["shell", "mkdir", "-p", "/data/ota_package"])
    with tempfile.TemporaryDirectory() as raw:
        properties_path = Path(raw) / "payload_properties.txt"
        write_text_atomic(properties_path, ota["payload_properties"])
        runner.run(["push", str(archive.resolve()), remote_archive], timeout=None)
        runner.run(["push", str(properties_path), remote_properties], timeout=60)
    runner.run(["shell", "chown", "system:cache", remote_archive, remote_properties])
    runner.run(["shell", "chmod", "0640", remote_archive, remote_properties])
    runner.run(["shell", "restorecon", remote_archive, remote_properties])
    command = (
        "update_engine_client --update --follow "
        f"--payload=file://{remote_archive} "
        f"--offset={ota['payload_offset_bytes']} "
        f"--size={ota['payload_size_bytes']} "
        f"--headers=\"$(cat {remote_properties})\""
    )
    try:
        output = runner.run(["shell", command], timeout=None)
    finally:
        runner.run(["shell", "rm", "-f", remote_archive, remote_properties])
    return {
        "schema_version": 1,
        "status": "update_engine_command_passed",
        "kind": "pixel9a_aios_virtual_ab_update",
        "completed_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "serial_sha256": text_sha256(serial),
        "ota_evidence_sha256": ota["evidence_sha256"],
        "ota_archive_sha256": ota["archive_sha256"],
        "source_fingerprint": device["source_fingerprint"],
        "target_fingerprint": ota["target_fingerprint"],
        "source_slot": device["source_slot"],
        "expected_target_slot": device["expected_target_slot"],
        "update_engine_output_sha256": text_sha256(output),
        "staging_removed": True,
        "reboot_performed": False,
        "proves_update_engine_command_passed": True,
        "proves_post_update_boot": False,
        "proves_slot_switch": False,
        "proves_merge_completed": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-update")
    parser.add_argument("--result-output", type=Path)
    arguments = parser.parse_args()
    try:
        evidence_path = arguments.evidence.resolve()
        archive = arguments.archive.resolve()
        ota = verify_ota_input(load(evidence_path), evidence_path, archive)
        runner = AdbRunner(arguments.adb, arguments.serial)
        device = inspect_device(runner, arguments.serial, ota)
        if not arguments.execute:
            result = {
                "status": "preflight_passed",
                "updated": False,
                "ota": {
                    "target_fingerprint": ota["target_fingerprint"],
                    "target_incremental": ota["target_incremental"],
                    "target_security_patch": ota["target_security_patch"],
                    "archive_sha256": ota["archive_sha256"],
                },
                "device": device,
            }
        else:
            if arguments.result_output is None:
                raise UpdateError("--execute requires --result-output")
            result_path = arguments.result_output.resolve()
            if result_path.exists():
                raise UpdateError(f"refusing to overwrite OTA result: {result_path}")
            result = apply_update(
                runner,
                ota,
                archive,
                arguments.serial,
                device,
                arguments.confirm_update,
            )
            write_json_atomic(result_path, result)
    except (KeyError, OSError, UpdateError) as error:
        print(f"Pixel OTA update refused: {error}", file=os.sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
