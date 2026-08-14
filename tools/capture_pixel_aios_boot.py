#!/usr/bin/env python3
"""Capture exact first-boot evidence for a full AIOS Pixel 9a image."""

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


ROOT = Path(__file__).resolve().parents[1]
PRODUCT_PATH = re.compile(r"product/[A-Za-z0-9._+/-]+")
REQUIRED_PACKAGES = (
    "com.aios.phone",
    "com.aios.messaging",
    "com.aios.callintelligence",
    "com.aios.contextintelligence",
    "com.aios.mediaintelligence",
    "com.aios.modelbroker",
    "com.aios.runtime.litertlm",
    "com.aios.runtime.sherpatts",
    "com.aios.runtime.whispercpp",
)
PROPERTY_NAMES = (
    "sys.boot_completed",
    "sys.user.0.ce_available",
    "ro.gsid.image_running",
    "ro.build.fingerprint",
    "ro.build.type",
    "ro.build.version.release",
    "ro.build.version.security_patch",
    "ro.product.device",
    "ro.product.vendor.device",
    "ro.product.cpu.abilist64",
    "ro.boot.verifiedbootstate",
    "ro.boot.flash.locked",
    "ro.boot.vbmeta.device_state",
    "ro.crypto.state",
    "ro.crypto.type",
    "ro.aios.version",
)


class BootEvidenceError(RuntimeError):
    pass


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BootEvidenceError(f"cannot load {path}: {error}") from error
    if not isinstance(value, dict):
        raise BootEvidenceError(f"JSON root must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def validate_chain(
    build_path: Path,
    release_path: Path,
    flash_path: Path,
    serial: str,
) -> tuple[dict, dict, dict]:
    build = load(build_path)
    release = load(release_path)
    flash = load(flash_path)
    if (build.get("schema_version") != 2
            or build.get("status") != "passed"
            or build.get("lane") != "pixel9a_tegu_hardware"
            or build.get("product") != "aios_tegu"
            or build.get("target_device") != "tegu"
            or build.get("artifact_layout") != "full_device_target_files"
            or not isinstance(build.get("generated_payloads"), dict)):
        raise BootEvidenceError("build record is not an eligible AIOS Pixel image")
    if (release.get("schema_version") != 1
            or release.get("status") != "passed"
            or release.get("lane") != build["lane"]
            or release.get("target_device") != "tegu"
            or release.get("build_fingerprint") != build.get("build_fingerprint")
            or release.get("build_evidence_sha256") != sha256(build_path)
            or release.get("target_files_sha256")
            != build.get("target_files_package", {}).get("sha256")
            or release.get("contains_required_model_payloads") is not True):
        raise BootEvidenceError("release record does not bind the Pixel build")
    archive = release.get("fastboot_archive")
    if (flash.get("schema_version") != 1
            or flash.get("status") != "flash_command_passed"
            or flash.get("kind") != "pixel9a_aios_development_flash"
            or flash.get("flashed") is not True
            or flash.get("wipe_requested") is not True
            or flash.get("proves_flash_command_passed") is not True
            or flash.get("proves_first_boot") is not False
            or flash.get("release_evidence_sha256") != sha256(release_path)
            or not isinstance(archive, dict)
            or flash.get("fastboot_archive_sha256") != archive.get("sha256")
            or flash.get("serial_sha256") != text_sha256(serial)):
        raise BootEvidenceError("flash record does not bind the wiped Pixel install")
    return build, release, flash


class AdbRunner:
    def __init__(self, executable: Path, serial: str):
        self.executable = executable.resolve()
        self.serial = serial
        if not self.executable.is_file():
            raise BootEvidenceError(f"adb executable not found: {self.executable}")

    def run(self, arguments: list[str], *, serial: bool = True) -> str:
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
                timeout=300 if "sha256sum" in arguments else 20,
            )
        except subprocess.TimeoutExpired as error:
            raise BootEvidenceError(
                f"adb command timed out: {' '.join(arguments)}"
            ) from error
        if completed.returncode != 0:
            raise BootEvidenceError(
                f"adb command failed ({' '.join(arguments)}): "
                f"{completed.stdout.strip()}"
            )
        return completed.stdout.strip()


def connected_serials(output: str) -> list[str]:
    serials = []
    for line in output.splitlines():
        fields = line.split()
        if not fields or fields[0] == "List":
            continue
        if len(fields) >= 2 and fields[1] == "device":
            serials.append(fields[0])
    return serials


def collect(
    runner,
    build: dict,
    release: dict,
    flash: dict,
    serial: str,
) -> dict:
    serials = connected_serials(runner.run(["devices", "-l"], serial=False))
    if serials != [serial]:
        raise BootEvidenceError(
            f"expected exactly authorized ADB device {serial}, found {serials}"
        )
    properties = {
        name: runner.run(["shell", "getprop", name])
        for name in PROPERTY_NAMES
    }
    expected_properties = {
        "sys.boot_completed": "1",
        "sys.user.0.ce_available": "true",
        "ro.build.fingerprint": build.get("build_fingerprint"),
        "ro.build.type": "userdebug",
        "ro.build.version.release": build.get("android_release"),
        "ro.build.version.security_patch": build.get("security_patch"),
        "ro.product.device": "tegu",
        "ro.product.vendor.device": "tegu",
        "ro.boot.verifiedbootstate": "orange",
        "ro.boot.flash.locked": "0",
        "ro.boot.vbmeta.device_state": "unlocked",
        "ro.crypto.state": "encrypted",
        "ro.crypto.type": "file",
        "ro.aios.version": "0.1-dev",
    }
    for name, expected in expected_properties.items():
        if properties.get(name) != expected:
            raise BootEvidenceError(f"connected Pixel has unexpected property {name}")
    if properties["ro.gsid.image_running"] not in {"", "0"}:
        raise BootEvidenceError("connected Pixel is running a GSI rather than full AIOS")
    if "arm64-v8a" not in properties["ro.product.cpu.abilist64"].split(","):
        raise BootEvidenceError("connected Pixel does not expose arm64-v8a")
    if runner.run(["shell", "settings", "get", "secure", "user_setup_complete"]) != "1":
        raise BootEvidenceError("fresh-user setup is not complete")

    packages = {}
    for package_name in REQUIRED_PACKAGES:
        package_path = runner.run(["shell", "pm", "path", package_name])
        if not re.fullmatch(r"package:/product/(?:app|priv-app)/\S+\.apk", package_path):
            raise BootEvidenceError(
                f"required AIOS package is not installed from product: {package_name}"
            )
        packages[package_name] = package_path

    role_output = runner.run([
        "shell", "cmd", "role", "get-role-holders", "--user", "0",
        "android.app.role.DIALER",
    ])
    role_holders = [line.strip() for line in role_output.splitlines() if line.strip()]
    if role_holders != ["com.aios.phone"]:
        raise BootEvidenceError("AIOS Phone is not the sole fresh-user dialer")
    overlay = runner.run([
        "shell", "cmd", "overlay", "lookup", "android",
        "android:string/config_defaultDialer",
    ])
    if "com.aios.phone" not in overlay:
        raise BootEvidenceError("default-dialer overlay does not resolve to AIOS Phone")

    artifacts = []
    product_artifacts = [
        item for item in build.get("artifacts", [])
        if isinstance(item, dict)
        and isinstance(item.get("path"), str)
        and item["path"].startswith("product/")
    ]
    if len(product_artifacts) < 34:
        raise BootEvidenceError("build evidence lacks the complete AIOS product payload")
    for artifact in product_artifacts:
        relative = artifact["path"]
        if PRODUCT_PATH.fullmatch(relative) is None:
            raise BootEvidenceError(f"unsafe evidenced product path: {relative}")
        device_path = "/" + relative
        observed_size = runner.run([
            "shell", "stat", "-c", "%s", device_path,
        ])
        digest_output = runner.run(["shell", "sha256sum", device_path])
        observed_digest = digest_output.split()[0].lower() if digest_output else ""
        if (observed_size != str(artifact.get("size_bytes"))
                or observed_digest != artifact.get("sha256")):
            raise BootEvidenceError(
                f"installed artifact differs from build evidence: {device_path}"
            )
        artifacts.append({
            "path": device_path,
            "size_bytes": int(observed_size),
            "sha256": observed_digest,
        })

    return {
        "schema_version": 1,
        "status": "passed",
        "kind": "pixel9a_aios_full_device_first_boot",
        "collected_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "serial_sha256": text_sha256(serial),
        "build_fingerprint": build["build_fingerprint"],
        "properties": properties,
        "packages": packages,
        "dialer_role_holders": role_holders,
        "default_dialer_overlay": overlay,
        "installed_artifacts": artifacts,
        "checks": {
            "wiped_flash_chain_verified": True,
            "boot_completed": True,
            "full_device_not_gsi": True,
            "exact_build_fingerprint": True,
            "unlocked_test_key_state": True,
            "owner_unlocked_and_setup": True,
            "required_packages_present": True,
            "default_dialer_resolved": True,
            "every_evidenced_product_artifact_verified": True,
        },
        "proves_boot_first_boot": True,
        "proves_model_payload_install": True,
        "proves_physical_full_device_boot": True,
        "proves_telephony_gate": False,
        "proves_model_inference": False,
        "proves_model_latency_gate": False,
        "proves_media_gate": False,
        "proves_factory_restore": False,
        "release_evidence_sha256": flash["release_evidence_sha256"],
        "fastboot_archive_sha256": release["fastboot_archive"]["sha256"],
    }


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    root = ROOT.resolve()
    if path == root or root in path.parents:
        raise BootEvidenceError("physical first-boot evidence must remain outside source")
    if path.exists():
        raise BootEvidenceError(f"refusing to overwrite first-boot evidence: {path}")
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
    parser.add_argument("--release-evidence", type=Path, required=True)
    parser.add_argument("--flash-result", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        build, release, flash = validate_chain(
            arguments.build_evidence,
            arguments.release_evidence,
            arguments.flash_result,
            arguments.serial,
        )
        value = collect(
            AdbRunner(arguments.adb, arguments.serial),
            build,
            release,
            flash,
            arguments.serial,
        )
        write_json_atomic(arguments.output, value)
    except (KeyError, OSError, BootEvidenceError) as error:
        print(f"Pixel AIOS first-boot evidence refused: {error}", file=sys.stderr)
        return 1
    print(f"Pixel AIOS first-boot evidence captured: {arguments.output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
