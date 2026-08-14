#!/usr/bin/env python3
"""Preflight and optionally flash an evidenced AIOS Pixel development image."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


class FlashError(RuntimeError):
    pass


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FlashError(f"cannot load release evidence: {error}") from error
    if not isinstance(value, dict):
        raise FlashError("release evidence must be a JSON object")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class FastbootRunner:
    def __init__(self, executable: Path, serial: str):
        self.executable = executable.resolve()
        self.serial = serial
        if not self.executable.is_file():
            raise FlashError(f"fastboot executable not found: {self.executable}")

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
                timeout=None if "update" in arguments else 20,
            )
        except subprocess.TimeoutExpired as error:
            raise FlashError(f"fastboot command timed out: {' '.join(arguments)}") \
                from error
        if completed.returncode != 0:
            raise FlashError(
                f"fastboot command failed ({' '.join(arguments)}): "
                f"{completed.stdout.strip()}"
            )
        return completed.stdout


def verify_release_input(evidence: dict, archive: Path) -> None:
    record = evidence.get("fastboot_archive")
    if (evidence.get("schema_version") != 1
            or evidence.get("status") != "passed"
            or evidence.get("target_device") != "tegu"
            or evidence.get("signing_state")
            != "public_android_test_keys_unlocked_bootloader_only"
            or evidence.get("contains_required_model_payloads") is not True
            or not isinstance(record, dict)
            or not archive.is_file()
            or record.get("size_bytes") != archive.stat().st_size
            or not re.fullmatch(r"[0-9a-f]{64}", str(record.get("sha256", "")))
            or record["sha256"] != sha256(archive)):
        raise FlashError("fastboot archive does not match passed release evidence")


def connected_serials(output: str) -> list[str]:
    serials = []
    for line in output.splitlines():
        fields = line.split()
        if fields:
            serials.append(fields[0])
    return serials


def parse_getvar(output: str, key: str) -> str:
    pattern = re.compile(
        rf"^(?:\(bootloader\)\s*)?{re.escape(key)}:\s*(\S.*)$",
        re.IGNORECASE,
    )
    values = [
        match.group(1).strip()
        for line in output.splitlines()
        if (match := pattern.match(line.strip())) is not None
    ]
    if len(values) != 1:
        raise FlashError(f"fastboot did not return one unambiguous {key} value")
    return values[0]


def preflight(runner, evidence: dict, serial: str) -> dict:
    serials = connected_serials(runner.run(["devices", "-l"], serial=False))
    if serials != [serial]:
        raise FlashError(
            f"expected exactly fastboot device {serial}, found {serials}"
        )
    state = {}
    for key in (
        "product", "unlocked", "version-bootloader", "version-baseband",
        "is-userspace",
    ):
        state[key] = parse_getvar(runner.run(["getvar", key]), key)
    requirements = evidence.get("requirements")
    if not isinstance(requirements, dict):
        raise FlashError("release evidence lacks device requirements")
    if state["product"] != evidence.get("target_device"):
        raise FlashError("connected fastboot product does not match tegu evidence")
    if state["unlocked"].lower() not in {"yes", "true", "1"}:
        raise FlashError("development image requires an unlocked bootloader")
    if state["is-userspace"].lower() not in {"no", "false", "0"}:
        raise FlashError("start the development flash from bootloader fastboot")
    for key in ("version-bootloader", "version-baseband"):
        if state[key] != requirements.get(key):
            raise FlashError(
                f"connected {key} does not match the evidenced image requirement"
            )
    return state


def require_wipe_confirmation(serial: str, confirmation: str | None) -> None:
    expected_confirmation = f"ERASE-{serial}-FOR-AIOS"
    if confirmation != expected_confirmation:
        raise FlashError(
            f"execution requires --confirm-wipe {expected_confirmation}"
        )


def flash(
    runner,
    evidence: dict,
    archive: Path,
    serial: str,
    execute: bool,
    confirmation: str | None,
) -> dict:
    verify_release_input(evidence, archive)
    state = preflight(runner, evidence, serial)
    if not execute:
        return {"status": "preflight_passed", "device": state, "flashed": False}
    require_wipe_confirmation(serial, confirmation)
    runner.run(["-w", "update", str(archive.resolve())])
    return {"status": "flash_command_passed", "device": state, "flashed": True}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fastboot", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-wipe")
    arguments = parser.parse_args()
    try:
        evidence = load(arguments.evidence)
        result = flash(
            FastbootRunner(arguments.fastboot, arguments.serial),
            evidence,
            arguments.archive,
            arguments.serial,
            arguments.execute,
            arguments.confirm_wipe,
        )
    except (KeyError, OSError, FlashError) as error:
        print(f"Pixel development flash refused: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
