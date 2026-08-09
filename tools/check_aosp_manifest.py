#!/usr/bin/env python3
"""Validate and lock a resolved Repo manifest for an explicit AIOS build lane."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")


class ManifestContractError(RuntimeError):
    pass


def load_lanes(root: Path) -> list[dict]:
    value = json.loads((root / "config" / "aosp_lanes.json").read_text(
        encoding="utf-8"
    ))
    if value.get("schema_version") != 1 or not isinstance(value.get("lanes"), list):
        raise ManifestContractError("unsupported AOSP lane configuration")
    return value["lanes"]


def select_lane(root: Path, lane_id: str) -> dict:
    lanes = [lane for lane in load_lanes(root) if lane.get("id") == lane_id]
    if len(lanes) != 1:
        raise ManifestContractError(f"unknown or duplicate AOSP lane: {lane_id}")
    return lanes[0]


def parse_resolved_manifest(path: Path) -> tuple[ElementTree.Element, list[dict]]:
    try:
        document = ElementTree.parse(path)
    except (OSError, ElementTree.ParseError) as error:
        raise ManifestContractError(f"cannot parse Repo manifest: {error}") from error

    root = document.getroot()
    if root.tag != "manifest":
        raise ManifestContractError("Repo manifest root must be <manifest>")
    if root.findall("include"):
        raise ManifestContractError(
            "manifest still contains <include>; capture a flattened `repo manifest -r`"
        )

    projects: list[dict] = []
    seen_paths: set[str] = set()
    for element in root.findall("project"):
        name = element.get("name", "").strip()
        project_path = element.get("path", name).strip()
        revision = element.get("revision", "").strip().lower()
        if not name or not project_path:
            raise ManifestContractError("every project needs a non-empty name and path")
        if project_path.startswith("/") or ".." in Path(project_path).parts:
            raise ManifestContractError(f"unsafe project path: {project_path}")
        if project_path in seen_paths:
            raise ManifestContractError(f"duplicate project path: {project_path}")
        if COMMIT_PATTERN.fullmatch(revision) is None:
            raise ManifestContractError(
                f"{project_path}: revision is not an immutable 40-character commit"
            )
        seen_paths.add(project_path)
        projects.append({
            "name": name,
            "path": project_path,
            "revision": revision,
            "remote": element.get("remote"),
        })

    if not projects:
        raise ManifestContractError("resolved manifest contains no projects")
    return root, sorted(projects, key=lambda project: project["path"])


def validate_lane(manifest: Path, root: Path, lane_id: str) -> tuple[dict, list[dict]]:
    lane = select_lane(root, lane_id)
    _, projects = parse_resolved_manifest(manifest)
    paths = {project["path"] for project in projects}
    missing = sorted(set(lane.get("required_projects", [])) - paths)
    if missing:
        raise ManifestContractError(
            f"{lane_id}: resolved manifest is missing required projects: {missing}"
        )
    return lane, projects


def build_lock(manifest: Path, lane: dict, projects: list[dict]) -> dict:
    manifest_bytes = manifest.read_bytes()
    canonical_projects = json.dumps(
        projects, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    aios_project = next(project for project in projects
                        if project["path"] == "vendor/aios")
    return {
        "schema_version": 1,
        "lane": lane["id"],
        "kind": lane["kind"],
        "product": lane["product"],
        "lunch_target": lane["lunch_target"],
        "manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
        "project_set_sha256": hashlib.sha256(canonical_projects).hexdigest(),
        "project_count": len(projects),
        "aios_revision": aios_project["revision"],
        "captured_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "lane_eligible_for_physical_gates": bool(lane["physical_gate_evidence"]),
        "proves_physical_runtime_gate": False,
    }


def write_json_atomic(path: Path, value: dict, force: bool) -> None:
    path = path.resolve()
    if path.exists() and not force:
        raise ManifestContractError(f"refusing to overwrite existing lock: {path}")
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


def check(
    manifest: Path,
    root: Path,
    lane_id: str,
    output: Path | None = None,
    force: bool = False,
) -> dict:
    manifest = manifest.resolve()
    lane, projects = validate_lane(manifest, root.resolve(), lane_id)
    lock = build_lock(manifest, lane, projects)
    if output is not None:
        write_json_atomic(output, lock, force)
    return lock


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--lane", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--force", action="store_true")
    arguments = parser.parse_args()
    try:
        result = check(
            arguments.manifest,
            arguments.root,
            arguments.lane,
            arguments.output,
            arguments.force,
        )
    except (KeyError, OSError, json.JSONDecodeError, ManifestContractError) as error:
        print(f"AOSP manifest contract failed: {error}", file=sys.stderr)
        return 1
    print(
        f"AOSP manifest locked for {result['lane']}: "
        f"{result['project_count']} projects, {result['manifest_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
