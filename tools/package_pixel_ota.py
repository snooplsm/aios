#!/usr/bin/env python3
"""Create and evidence-bind a full test-key Pixel A/B OTA package."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
TARGET_METADATA = "META/misc_info.txt"
AB_PARTITIONS = "META/ab_partitions.txt"
OTA_METADATA = "META-INF/com/android/metadata"
PAYLOAD = "payload.bin"
PAYLOAD_PROPERTIES = "payload_properties.txt"


class PackageError(RuntimeError):
    pass


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PackageError(f"cannot load {path}: {error}") from error
    if not isinstance(value, dict):
        raise PackageError(f"expected a JSON object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def select_lane(root: Path, lane_id: str) -> dict:
    document = load(root / "config" / "aosp_lanes.json")
    matches = [
        lane for lane in document.get("lanes", [])
        if isinstance(lane, dict) and lane.get("id") == lane_id
    ]
    if len(matches) != 1:
        raise PackageError(f"unknown or duplicate lane: {lane_id}")
    lane = matches[0]
    if lane.get("artifact_layout") != "full_device_target_files":
        raise PackageError("OTA packaging requires a full-device target-files lane")
    return lane


def validate_build_input(lane: dict, build_evidence: dict, target_files: Path) -> None:
    if (build_evidence.get("schema_version") != 2
            or build_evidence.get("status") != "passed"
            or build_evidence.get("lane") != lane.get("id")
            or build_evidence.get("product") != lane.get("product")
            or build_evidence.get("target_device") != lane.get("target_device")
            or build_evidence.get("artifact_layout") != "full_device_target_files"):
        raise PackageError("build evidence does not match the full-device lane")
    record = build_evidence.get("target_files_package")
    if (not isinstance(record, dict)
            or not target_files.is_file()
            or record.get("size_bytes") != target_files.stat().st_size
            or SHA256_PATTERN.fullmatch(str(record.get("sha256", ""))) is None
            or record["sha256"] != sha256(target_files)
            or record["sha256"] != build_evidence.get("installed_files_sha256")):
        raise PackageError("target-files archive does not match build evidence")

    generated = build_evidence.get("generated_payloads")
    model_pack = generated.get("model_pack") if isinstance(generated, dict) else None
    runtime_packs = generated.get("runtime_packs") if isinstance(generated, dict) else None
    models = model_pack.get("models") if isinstance(model_pack, dict) else None
    runtimes = (
        [item.get("runtime") for item in runtime_packs]
        if isinstance(runtime_packs, list)
        and all(isinstance(item, dict) for item in runtime_packs)
        else None
    )
    if (not isinstance(models, list)
            or set(models) != set(lane.get("required_model_ids", []))
            or len(models) != len(lane.get("required_model_ids", []))):
        raise PackageError("build evidence lacks the exact required model set")
    if (not isinstance(runtimes, list)
            or set(runtimes) != set(lane.get("required_runtime_ids", []))
            or len(runtimes) != len(lane.get("required_runtime_ids", []))):
        raise PackageError("build evidence lacks the exact required runtime set")
    version_policy = lane.get("build_version_policy")
    if isinstance(version_policy, dict):
        incremental = str(build_evidence.get("build_incremental", ""))
        timestamp = build_evidence.get("build_timestamp")
        if (re.fullmatch(r"[0-9]{10}", incremental) is None
                or not isinstance(timestamp, int)
                or int(incremental)
                <= int(version_policy.get("minimum_build_number_exclusive", 0))
                or timestamp
                <= int(version_policy.get("minimum_build_timestamp_exclusive", 0))):
            raise PackageError("build evidence lacks an eligible monotonic OTA version")


def parse_key_values(
    text: str, label: str, *, allow_empty_values: bool = False
) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise PackageError(f"{label} contains an invalid line")
        key, value = line.split("=", 1)
        if (not key or key in values
                or (not value and not allow_empty_values)):
            raise PackageError(f"{label} contains an invalid or duplicate key")
        values[key] = value
    return values


def _read_utf8(archive: zipfile.ZipFile, name: str, limit: int) -> str:
    info = archive.getinfo(name)
    if info.file_size <= 0 or info.file_size > limit:
        raise PackageError(f"{name} has an invalid size")
    try:
        return archive.read(name).decode("utf-8")
    except UnicodeDecodeError as error:
        raise PackageError(f"{name} is not UTF-8") from error


def inspect_target_files(path: Path, lane: dict) -> dict:
    try:
        with zipfile.ZipFile(path) as archive:
            names = [member.filename for member in archive.infolist()]
            if len(names) != len(set(names)):
                raise PackageError("target-files archive contains duplicate members")
            missing = {TARGET_METADATA, AB_PARTITIONS} - set(names)
            if missing:
                raise PackageError(f"target-files archive lacks metadata: {sorted(missing)}")
            misc_text = _read_utf8(archive, TARGET_METADATA, 4 * 1024 * 1024)
            partitions_text = _read_utf8(archive, AB_PARTITIONS, 1024 * 1024)
    except (KeyError, OSError, zipfile.BadZipFile) as error:
        raise PackageError(f"cannot inspect target-files archive: {error}") from error

    # releasetools emits informational empty values such as
    # ``building_oem_image=``. Required booleans and signing fields below are
    # still checked for exact non-empty values.
    misc = parse_key_values(
        misc_text, TARGET_METADATA, allow_empty_values=True
    )
    for key in (
        "ab_update",
        "use_dynamic_partitions",
        "virtual_ab",
        "virtual_ab_compression",
        "avb_enable",
    ):
        if misc.get(key) != "true":
            raise PackageError(f"target-files archive requires {key}=true")
    certificate = misc.get("default_system_dev_certificate", "")
    if not certificate.endswith("/testkey"):
        raise PackageError("development OTA requires the public Android test key")

    partitions = [line.strip() for line in partitions_text.splitlines() if line.strip()]
    if len(partitions) != len(set(partitions)) or not partitions:
        raise PackageError("target-files archive has invalid A/B partitions")
    required_partitions = {
        str(image).removesuffix(".img") for image in lane.get("required_images", [])
    }
    missing_partitions = sorted(required_partitions - set(partitions))
    if missing_partitions:
        raise PackageError(
            f"target-files archive lacks required A/B partitions: {missing_partitions}"
        )
    return {
        "target_metadata_sha256": hashlib.sha256(misc_text.encode("utf-8")).hexdigest(),
        "ab_partitions_sha256": hashlib.sha256(
            partitions_text.encode("utf-8")
        ).hexdigest(),
        "ab_partitions": partitions,
        "virtual_ab_compression": misc["virtual_ab_compression"],
        "default_system_dev_certificate": certificate,
    }


def _strict_base64_sha256(value: str, label: str) -> bytes:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as error:
        raise PackageError(f"{label} is not valid base64") from error
    if len(decoded) != hashlib.sha256().digest_size:
        raise PackageError(f"{label} is not a SHA-256 digest")
    return decoded


def _payload_digests(
    archive: zipfile.ZipFile, metadata_size: int
) -> tuple[int, str, str]:
    if metadata_size <= 0:
        raise PackageError("payload METADATA_SIZE must be positive")
    whole = hashlib.sha256()
    metadata = hashlib.sha256()
    total = 0
    remaining_metadata = metadata_size
    with archive.open(PAYLOAD) as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            whole.update(chunk)
            if remaining_metadata > 0:
                prefix = chunk[:remaining_metadata]
                metadata.update(prefix)
                remaining_metadata -= len(prefix)
            total += len(chunk)
    if remaining_metadata != 0:
        raise PackageError("payload METADATA_SIZE exceeds payload size")
    return total, whole.hexdigest(), metadata.hexdigest()


def inspect_whole_file_signature_footer(path: Path) -> dict:
    length = path.stat().st_size
    if length < 28:
        raise PackageError("OTA archive is too small for a whole-file signature")
    with path.open("rb") as stream:
        stream.seek(length - 6)
        footer = stream.read(6)
        if len(footer) != 6 or footer[2:4] != b"\xff\xff":
            raise PackageError("OTA archive lacks an Android whole-file signature footer")
        signature_start = int.from_bytes(footer[0:2], "little")
        comment_size = int.from_bytes(footer[4:6], "little")
        if signature_start <= 6 or signature_start > comment_size:
            raise PackageError("OTA whole-file signature footer has invalid offsets")
        eocd_offset = length - comment_size - 22
        if eocd_offset < 0:
            raise PackageError("OTA whole-file signature footer exceeds archive size")
        stream.seek(eocd_offset)
        if stream.read(4) != b"PK\x05\x06":
            raise PackageError("OTA whole-file signature does not align with ZIP EOCD")
        signature_size = signature_start - 6
        signature_offset = length - signature_start
        stream.seek(signature_offset)
        signature = stream.read(signature_size)
        if len(signature) != signature_size:
            raise PackageError("OTA whole-file signature is truncated")
    return {
        "comment_size_bytes": comment_size,
        "signature_offset_bytes": signature_offset,
        "signature_size_bytes": signature_size,
        "signature_blob_sha256": hashlib.sha256(signature).hexdigest(),
        "signed_data_size_bytes": length - comment_size - 2,
    }


def inspect_ota_zip(path: Path, lane: dict, build_evidence: dict) -> dict:
    whole_file_signature = inspect_whole_file_signature_footer(path)
    required = {PAYLOAD, PAYLOAD_PROPERTIES, OTA_METADATA}
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            names = [member.filename for member in members]
            if len(names) != len(set(names)):
                raise PackageError("OTA archive contains duplicate members")
            missing = required - set(names)
            if missing:
                raise PackageError(f"OTA archive lacks required members: {sorted(missing)}")
            properties_text = _read_utf8(archive, PAYLOAD_PROPERTIES, 1024 * 1024)
            metadata_text = _read_utf8(archive, OTA_METADATA, 1024 * 1024)
            properties = parse_key_values(properties_text, PAYLOAD_PROPERTIES)
            metadata = parse_key_values(metadata_text, OTA_METADATA)
            try:
                file_size = int(properties["FILE_SIZE"])
                metadata_size = int(properties["METADATA_SIZE"])
                expected_file_hash = _strict_base64_sha256(
                    properties["FILE_HASH"], "payload FILE_HASH"
                )
                expected_metadata_hash = _strict_base64_sha256(
                    properties["METADATA_HASH"], "payload METADATA_HASH"
                )
            except (KeyError, ValueError) as error:
                raise PackageError("payload properties are incomplete or invalid") from error
            payload_info = archive.getinfo(PAYLOAD)
            if file_size != payload_info.file_size:
                raise PackageError("payload FILE_SIZE does not match the ZIP member")
            actual_size, file_hash, metadata_hash = _payload_digests(
                archive, metadata_size
            )
    except (KeyError, OSError, zipfile.BadZipFile) as error:
        raise PackageError(f"cannot inspect OTA archive: {error}") from error

    if actual_size != file_size:
        raise PackageError("payload stream size does not match FILE_SIZE")
    if bytes.fromhex(file_hash) != expected_file_hash:
        raise PackageError("payload FILE_HASH does not match payload.bin")
    if bytes.fromhex(metadata_hash) != expected_metadata_hash:
        raise PackageError("payload METADATA_HASH does not match payload.bin")

    if metadata.get("ota-type") != "AB":
        raise PackageError("OTA metadata does not declare an A/B package")
    if metadata.get("pre-device") != lane.get("target_device"):
        raise PackageError("OTA metadata is not restricted to the lane device")
    if metadata.get("post-build") != build_evidence.get("build_fingerprint"):
        raise PackageError("OTA post-build fingerprint does not match build evidence")
    if metadata.get("post-security-patch-level") != build_evidence.get("security_patch"):
        raise PackageError("OTA security patch does not match build evidence")
    fingerprint = str(build_evidence.get("build_fingerprint", ""))
    fingerprint_match = re.fullmatch(
        r"[^:]+:[^/]+/[^/]+/([^:]+):[^/]+/.+", fingerprint
    )
    if fingerprint_match is None:
        raise PackageError("build evidence has an invalid fingerprint")
    expected_incremental = fingerprint_match.group(1)
    if metadata.get("post-build-incremental") != expected_incremental:
        raise PackageError("OTA incremental build does not match build evidence")

    return {
        "member_count": len(names),
        "ota_metadata": metadata,
        "ota_metadata_sha256": hashlib.sha256(
            metadata_text.encode("utf-8")
        ).hexdigest(),
        "payload_properties_sha256": hashlib.sha256(
            properties_text.encode("utf-8")
        ).hexdigest(),
        "payload": {
            "size_bytes": actual_size,
            "sha256": file_hash,
            "metadata_size_bytes": metadata_size,
            "metadata_sha256": metadata_hash,
        },
        "whole_file_signature": whole_file_signature,
    }


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    if path.exists():
        raise PackageError(f"refusing to overwrite OTA evidence: {path}")
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


def ota_tool_command(ota_tool: Path, target_files: Path, output: Path) -> list[str]:
    arguments = [str(ota_tool), str(target_files), str(output)]
    if ota_tool.suffix.lower() == ".py":
        return [sys.executable, *arguments]
    return arguments


def signature_checker_command(
    signature_checker: Path, certificate: Path, ota: Path
) -> list[str]:
    arguments = [str(signature_checker), str(certificate), str(ota)]
    if signature_checker.suffix.lower() == ".py":
        return [sys.executable, *arguments]
    return arguments


def package(
    root: Path,
    aosp_root: Path,
    lane_id: str,
    build_evidence_path: Path,
    target_files: Path,
    ota_tool: Path,
    signature_checker: Path,
    output: Path,
    evidence_output: Path,
) -> dict:
    root = root.resolve()
    aosp_root = aosp_root.resolve()
    lane = select_lane(root, lane_id)
    build_evidence_path = build_evidence_path.resolve()
    build_evidence = load(build_evidence_path)
    target_files = target_files.resolve()
    ota_tool = ota_tool.resolve()
    signature_checker = signature_checker.resolve()
    output = output.resolve()
    evidence_output = evidence_output.resolve()
    if output.exists():
        raise PackageError(f"refusing to overwrite OTA archive: {output}")
    if evidence_output.exists():
        raise PackageError(f"refusing to overwrite OTA evidence: {evidence_output}")
    if not ota_tool.is_file():
        raise PackageError(f"missing ota_from_target_files tool: {ota_tool}")
    if (not (aosp_root / ".repo").is_dir()
            or not (aosp_root / "build" / "make" / "target" / "product"
                    / "security" / "testkey.pk8").is_file()):
        raise PackageError("AOSP root lacks the Repo checkout or development signing key")
    certificate = (aosp_root / "build" / "make" / "target" / "product"
                   / "security" / "testkey.x509.pem")
    if not certificate.is_file():
        raise PackageError("AOSP root lacks its development OTA certificate")
    java_home = aosp_root / "prebuilts" / "jdk" / "jdk21" / "linux-x86"
    java = java_home / "bin" / "java"
    if not java.is_file() or not os.access(java, os.X_OK):
        raise PackageError("AOSP root lacks its executable pinned JDK 21 runtime")
    try:
        ota_tool.relative_to(aosp_root)
        signature_checker.relative_to(aosp_root)
    except ValueError as error:
        raise PackageError("OTA host tools must come from the selected AOSP root") from error
    if not signature_checker.is_file() or not os.access(signature_checker, os.X_OK):
        raise PackageError("missing executable check_ota_package_signature tool")
    validate_build_input(lane, build_evidence, target_files)
    target_inspection = inspect_target_files(target_files, lane)

    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".zip", dir=output.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    temporary.unlink()
    tool_environment = os.environ.copy()
    tool_environment["ANDROID_JAVA_HOME"] = str(java_home)
    tool_environment["JAVA_HOME"] = str(java_home)
    tool_environment["PATH"] = (
        str(ota_tool.parent) + os.pathsep + str(java_home / "bin")
        + os.pathsep + tool_environment.get("PATH", "")
    )
    openssl_text = shutil.which("openssl", path=tool_environment["PATH"])
    if not openssl_text:
        raise PackageError("OTA signature verification requires OpenSSL")
    openssl = Path(openssl_text).resolve()
    openssl_version = subprocess.run(
        [str(openssl), "version"],
        check=False,
        capture_output=True,
        text=True,
    )
    if openssl_version.returncode != 0 or not openssl_version.stdout.strip():
        raise PackageError("cannot identify OpenSSL used for OTA verification")
    try:
        completed = subprocess.run(
            ota_tool_command(ota_tool, target_files, temporary),
            cwd=aosp_root,
            env=tool_environment,
            check=False,
        )
        if completed.returncode != 0 or not temporary.is_file():
            raise PackageError(
                f"ota_from_target_files failed with exit code {completed.returncode}"
            )
        verification = subprocess.run(
            signature_checker_command(signature_checker, certificate, temporary),
            cwd=aosp_root,
            env=tool_environment,
            check=False,
        )
        if verification.returncode != 0:
            raise PackageError(
                "check_ota_package_signature failed with exit code "
                f"{verification.returncode}"
            )
        inspection = inspect_ota_zip(temporary, lane, build_evidence)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)

    value = {
        "schema_version": 1,
        "status": "passed",
        "update_kind": "full_virtual_ab_ota",
        "lane": lane_id,
        "product": lane["product"],
        "target_device": lane["target_device"],
        "build_fingerprint": build_evidence.get("build_fingerprint"),
        "security_patch": build_evidence.get("security_patch"),
        "aios_revision": build_evidence.get("aios_revision"),
        "build_evidence_sha256": sha256(build_evidence_path),
        "target_files_sha256": build_evidence["target_files_package"]["sha256"],
        "ota_archive": {
            "path": output.name,
            "size_bytes": output.stat().st_size,
            "sha256": sha256(output),
        },
        "signing_state": "public_android_test_keys_unlocked_bootloader_only",
        "contains_required_model_payloads": True,
        "installation_performed": False,
        "host_tools": {
            "ota_from_target_files_sha256": sha256(ota_tool),
            "check_ota_package_signature_sha256": sha256(signature_checker),
            "java_runtime": "prebuilts/jdk/jdk21/linux-x86/bin/java",
            "java_runtime_sha256": sha256(java),
            "openssl_path": str(openssl),
            "openssl_sha256": sha256(openssl),
            "openssl_version": openssl_version.stdout.strip(),
        },
        "signature_verification": {
            "status": "passed",
            "certificate": "build/make/target/product/security/testkey.x509.pem",
            "certificate_sha256": sha256(certificate),
            "whole_file_and_payload_verified": True,
        },
        "captured_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        **target_inspection,
        **inspection,
    }
    write_json_atomic(evidence_output, value)
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--aosp-root", type=Path, required=True)
    parser.add_argument("--lane", required=True)
    parser.add_argument("--build-evidence", type=Path, required=True)
    parser.add_argument("--target-files", type=Path, required=True)
    parser.add_argument("--ota-from-target-files", type=Path, required=True)
    parser.add_argument("--check-ota-package-signature", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--evidence-output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        value = package(
            arguments.root,
            arguments.aosp_root,
            arguments.lane,
            arguments.build_evidence,
            arguments.target_files,
            arguments.ota_from_target_files,
            arguments.check_ota_package_signature,
            arguments.output,
            arguments.evidence_output,
        )
    except (KeyError, OSError, PackageError) as error:
        print(f"Pixel OTA packaging failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Pixel full OTA captured for {value['target_device']}: "
        f"{value['ota_archive']['sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
