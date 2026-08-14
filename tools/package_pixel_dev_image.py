#!/usr/bin/env python3
"""Create and evidence-bind a test-key Pixel fastboot image archive."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


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
        raise PackageError("development fastboot packaging requires a full-device lane")
    return lane


def validate_build_input(
    lane: dict, build_evidence: dict, target_files: Path
) -> None:
    if (build_evidence.get("schema_version") != 2
            or build_evidence.get("status") != "passed"
            or build_evidence.get("lane") != lane.get("id")
            or build_evidence.get("product") != lane.get("product")
            or build_evidence.get("target_device") != lane.get("target_device")
            or build_evidence.get("artifact_layout")
            != "full_device_target_files"):
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
    runtime_packs = (
        generated.get("runtime_packs") if isinstance(generated, dict) else None
    )
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


def parse_requirements(text: str) -> dict[str, str]:
    requirements = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line.startswith("require ") or "=" not in line:
            continue
        key, value = line.removeprefix("require ").split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key or not value or key in requirements:
            raise PackageError("fastboot archive contains invalid requirements")
        requirements[key] = value
    return requirements


def inspect_fastboot_zip(path: Path, lane: dict) -> dict:
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            names = [member.filename for member in members]
            if len(names) != len(set(names)):
                raise PackageError("fastboot archive contains duplicate members")
            corrupt = archive.testzip()
            if corrupt is not None:
                raise PackageError(f"fastboot archive failed CRC validation: {corrupt}")
            required_metadata = {"android-info.txt", "fastboot-info.txt"}
            missing_metadata = required_metadata - set(names)
            if missing_metadata:
                raise PackageError(
                    f"fastboot archive lacks metadata: {sorted(missing_metadata)}"
                )
            required_images = lane.get("required_images", [])
            missing_images = [image for image in required_images if image not in names]
            if missing_images:
                raise PackageError(
                    f"fastboot archive lacks required images: {missing_images}"
                )
            android_info = archive.read("android-info.txt").decode("utf-8")
            fastboot_info = archive.read("fastboot-info.txt").decode("utf-8")
    except (OSError, UnicodeDecodeError, zipfile.BadZipFile) as error:
        raise PackageError(f"cannot inspect fastboot archive: {error}") from error

    requirements = parse_requirements(android_info)
    fastboot_requirements = parse_requirements(fastboot_info)
    expected_device = lane.get("target_device")
    if requirements.get("board") != expected_device:
        raise PackageError("fastboot archive is not restricted to the lane device")
    if ("board" in fastboot_requirements
            and fastboot_requirements["board"] != expected_device):
        raise PackageError("fastboot commands contradict the lane device")
    for key in ("version-bootloader", "version-baseband"):
        if not requirements.get(key):
            raise PackageError(f"fastboot archive lacks {key} requirement")
    flash_commands = {
        line.strip() for line in fastboot_info.splitlines()
        if line.strip().startswith(("flash ", "update-super"))
    }
    if "update-super" not in flash_commands:
        raise PackageError("fastboot archive lacks dynamic-partition update command")
    for image in lane["required_images"]:
        partition = image.removesuffix(".img")
        if partition in {"product", "system", "vendor"}:
            expected = f"flash {partition}"
            if expected not in flash_commands:
                raise PackageError(f"fastboot archive does not flash {partition}")
    return {
        "requirements": requirements,
        "fastboot_info_sha256": hashlib.sha256(
            fastboot_info.encode("utf-8")
        ).hexdigest(),
        "android_info_sha256": hashlib.sha256(
            android_info.encode("utf-8")
        ).hexdigest(),
        "member_count": len(names),
        "required_images": list(lane["required_images"]),
    }


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    if path.exists():
        raise PackageError(f"refusing to overwrite release evidence: {path}")
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


def image_tool_command(
    image_tool: Path, target_files: Path, output: Path
) -> list[str]:
    arguments = [str(image_tool), str(target_files), str(output)]
    if image_tool.suffix.lower() == ".py":
        return [sys.executable, *arguments]
    return arguments


def package(
    root: Path,
    lane_id: str,
    build_evidence_path: Path,
    target_files: Path,
    image_tool: Path,
    output: Path,
    evidence_output: Path,
) -> dict:
    root = root.resolve()
    lane = select_lane(root, lane_id)
    build_evidence = load(build_evidence_path)
    target_files = target_files.resolve()
    output = output.resolve()
    evidence_output = evidence_output.resolve()
    if output.exists():
        raise PackageError(f"refusing to overwrite fastboot archive: {output}")
    if not image_tool.is_file():
        raise PackageError(f"missing img_from_target_files tool: {image_tool}")
    validate_build_input(lane, build_evidence, target_files)
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".zip", dir=output.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    temporary.unlink()
    try:
        completed = subprocess.run(
            image_tool_command(image_tool, target_files, temporary),
            check=False,
        )
        if completed.returncode != 0 or not temporary.is_file():
            raise PackageError(
                f"img_from_target_files failed with exit code {completed.returncode}"
            )
        inspection = inspect_fastboot_zip(temporary, lane)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)

    image_digest = sha256(output)
    value = {
        "schema_version": 1,
        "status": "passed",
        "lane": lane_id,
        "product": lane["product"],
        "target_device": lane["target_device"],
        "build_fingerprint": build_evidence.get("build_fingerprint"),
        "aios_revision": build_evidence.get("aios_revision"),
        "build_evidence_sha256": sha256(build_evidence_path),
        "target_files_sha256": build_evidence["target_files_package"]["sha256"],
        "fastboot_archive": {
            "path": output.name,
            "size_bytes": output.stat().st_size,
            "sha256": image_digest,
        },
        "signing_state": "public_android_test_keys_unlocked_bootloader_only",
        "contains_required_model_payloads": True,
        "captured_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        **inspection,
    }
    write_json_atomic(evidence_output, value)
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--lane", required=True)
    parser.add_argument("--build-evidence", type=Path, required=True)
    parser.add_argument("--target-files", type=Path, required=True)
    parser.add_argument("--img-from-target-files", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--evidence-output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        value = package(
            arguments.root,
            arguments.lane,
            arguments.build_evidence,
            arguments.target_files,
            arguments.img_from_target_files,
            arguments.output,
            arguments.evidence_output,
        )
    except (KeyError, OSError, PackageError) as error:
        print(f"Pixel development image packaging failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Pixel development image captured for {value['target_device']}: "
        f"{value['fastboot_archive']['sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
