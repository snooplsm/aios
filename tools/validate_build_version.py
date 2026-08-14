#!/usr/bin/env python3
"""Validate an explicit, monotonic, reproducible AIOS physical build version."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_NUMBER = re.compile(r"[0-9]{10}")


class VersionError(RuntimeError):
    pass


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VersionError(f"cannot load {path}: {error}") from error
    if not isinstance(value, dict):
        raise VersionError(f"expected a JSON object: {path}")
    return value


def select_policy(root: Path, lane_id: str) -> dict:
    document = load(root / "config" / "aosp_lanes.json")
    matches = [
        lane for lane in document.get("lanes", [])
        if isinstance(lane, dict) and lane.get("id") == lane_id
    ]
    if len(matches) != 1:
        raise VersionError(f"unknown or duplicate lane: {lane_id}")
    policy = matches[0].get("build_version_policy")
    if not isinstance(policy, dict):
        raise VersionError(f"lane does not require an explicit build version: {lane_id}")
    return policy


def validate(policy: dict, build_number: str, build_datetime: str) -> dict:
    if policy.get("format") != "utc_date_sequence_yyyyMMddNN":
        raise VersionError("unsupported build-number policy")
    if BUILD_NUMBER.fullmatch(build_number) is None:
        raise VersionError("build number must be exactly YYYYMMDDNN")
    try:
        build_date = datetime.strptime(build_number[:8], "%Y%m%d").date()
        timestamp = int(build_datetime)
    except (ValueError, OverflowError) as error:
        raise VersionError("build number date or Unix timestamp is invalid") from error
    if timestamp <= 0:
        raise VersionError("build Unix timestamp must be positive")
    try:
        timestamp_date = datetime.fromtimestamp(timestamp, timezone.utc).date()
    except (ValueError, OverflowError, OSError) as error:
        raise VersionError("build Unix timestamp is out of range") from error
    if timestamp_date != build_date:
        raise VersionError("build number date must match the UTC timestamp date")
    minimum_number = str(policy.get("minimum_build_number_exclusive", ""))
    minimum_timestamp = policy.get("minimum_build_timestamp_exclusive")
    if (BUILD_NUMBER.fullmatch(minimum_number) is None
            or not isinstance(minimum_timestamp, int)
            or minimum_timestamp <= 0):
        raise VersionError("lane has an invalid minimum build version")
    if int(build_number) <= int(minimum_number):
        raise VersionError("build number must be newer than the lane minimum")
    if timestamp <= minimum_timestamp:
        raise VersionError("build timestamp must be newer than the lane minimum")
    return {
        "build_number": build_number,
        "build_datetime": timestamp,
        "utc_date": build_date.isoformat(),
        "minimum_build_number_exclusive": minimum_number,
        "minimum_build_timestamp_exclusive": minimum_timestamp,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--lane", required=True)
    parser.add_argument("--build-number", required=True)
    parser.add_argument("--build-datetime", required=True)
    arguments = parser.parse_args()
    try:
        value = validate(
            select_policy(arguments.root.resolve(), arguments.lane),
            arguments.build_number,
            arguments.build_datetime,
        )
    except VersionError as error:
        print(f"AIOS build version refused: {error}", file=sys.stderr)
        return 1
    print(json.dumps(value, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
