#!/usr/bin/env python3
"""Check or refresh AIOS's observation of AOSP android-latest-release."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import date
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
RELEASE_PATTERN = re.compile(r"android[0-9]+-release")
OFFICIAL_MANIFEST_URL = "https://android.googlesource.com/platform/manifest"


class TrackingContractError(RuntimeError):
    pass


class TrackingOutOfDate(TrackingContractError):
    pass


def git_output(repository: Path, *arguments: str) -> str:
    completed = subprocess.run(
        ["git", "-c", f"safe.directory={repository.resolve()}", *arguments],
        cwd=repository,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise TrackingContractError(completed.stdout.strip() or "Git command failed")
    return completed.stdout.strip()


def normalized_url(value: str) -> str:
    result = value.strip().rstrip("/")
    if result.endswith(".git"):
        result = result[:-4]
    return result


def load_tracking(root: Path) -> tuple[Path, dict]:
    path = root.resolve() / "config" / "aosp_tracking.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise TrackingContractError(f"cannot load AOSP tracking policy: {error}") from error
    if value.get("schema_version") != 1:
        raise TrackingContractError("unsupported AOSP tracking schema")
    if normalized_url(str(value.get("manifest_url", ""))) != OFFICIAL_MANIFEST_URL:
        raise TrackingContractError("AOSP tracking must use the official manifest repository")
    revision = value.get("tracking_revision")
    if revision != "android-latest-release":
        raise TrackingContractError("integration must track android-latest-release")
    return path, value


def release_branch(default_xml: str) -> str:
    try:
        root = ElementTree.fromstring(default_xml)
    except ElementTree.ParseError as error:
        raise TrackingContractError(f"cannot parse default.xml: {error}") from error
    defaults = root.findall("default") if root.tag == "manifest" else []
    if len(defaults) != 1:
        raise TrackingContractError("manifest must contain exactly one <default>")
    revision = defaults[0].get("revision", "").strip()
    prefix = "refs/heads/"
    if revision.startswith(prefix):
        revision = revision[len(prefix):]
    if RELEASE_PATTERN.fullmatch(revision) is None:
        raise TrackingContractError(
            "android-latest-release does not resolve to a numbered release branch"
        )
    return revision


def inspect_manifest_repository(repository: Path, tracking: dict) -> dict:
    repository = repository.resolve()
    if not repository.is_dir():
        raise TrackingContractError(f"manifest repository is absent: {repository}")
    top_level = Path(git_output(repository, "rev-parse", "--show-toplevel")).resolve()
    if top_level != repository:
        raise TrackingContractError("manifest repository path is not its Git toplevel")
    remote = normalized_url(git_output(repository, "remote", "get-url", "origin"))
    expected_remote = normalized_url(str(tracking["manifest_url"]))
    if remote != expected_remote:
        raise TrackingContractError(
            f"manifest origin is not official: expected {expected_remote}, found {remote}"
        )

    revision = tracking["tracking_revision"]
    remote_ref = f"refs/remotes/origin/{revision}^{{commit}}"
    observed_commit = git_output(repository, "rev-parse", "--verify", remote_ref).lower()
    head = git_output(repository, "rev-parse", "--verify", "HEAD^{commit}").lower()
    if COMMIT_PATTERN.fullmatch(observed_commit) is None:
        raise TrackingContractError("tracking ref did not resolve to an immutable commit")
    if head != observed_commit:
        raise TrackingContractError(
            "manifest checkout is not at origin/android-latest-release; rerun repo init"
        )
    default_xml = git_output(repository, "show", f"{observed_commit}:default.xml")
    return {
        "observed_release_branch": release_branch(default_xml),
        "observed_release_manifest_commit": observed_commit,
    }


def refreshed_value(tracking: dict, observation: dict, observed_on: str) -> dict:
    try:
        parsed_date = date.fromisoformat(observed_on)
    except (TypeError, ValueError) as error:
        raise TrackingContractError("observed-on must be an ISO calendar date") from error
    if parsed_date > date.today():
        raise TrackingContractError("observed-on cannot be in the future")
    result = dict(tracking)
    result.update(observation)
    result["observed_on"] = parsed_date.isoformat()
    return result


def check_current(tracking: dict, observation: dict) -> None:
    for field in ("observed_release_branch", "observed_release_manifest_commit"):
        if tracking.get(field) != observation[field]:
            raise TrackingOutOfDate(
                "AOSP tracking is stale: "
                f"{field} records {tracking.get(field)!r}, upstream is {observation[field]!r}"
            )


def write_json_atomic(path: Path, value: dict) -> None:
    path = path.resolve()
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def run(
    root: Path,
    manifest_repository: Path,
    write: bool,
    observed_on: str | None = None,
) -> dict:
    path, tracking = load_tracking(root)
    observation = inspect_manifest_repository(manifest_repository, tracking)
    if write:
        updated = refreshed_value(tracking, observation, observed_on or date.today().isoformat())
        write_json_atomic(path, updated)
        return updated
    check_current(tracking, observation)
    return tracking


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--manifest-repo", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--observed-on")
    arguments = parser.parse_args()
    if arguments.observed_on is not None and not arguments.write:
        parser.error("--observed-on is valid only with --write")
    try:
        result = run(
            arguments.root,
            arguments.manifest_repo,
            arguments.write,
            arguments.observed_on,
        )
    except TrackingOutOfDate as error:
        print(str(error), file=sys.stderr)
        return 3
    except (KeyError, OSError, TrackingContractError) as error:
        print(f"AOSP tracking refresh failed: {error}", file=sys.stderr)
        return 1
    action = "updated" if arguments.write else "matches"
    print(
        "AOSP tracking " + action + " origin/android-latest-release at "
        + result["observed_release_manifest_commit"]
        + " (" + result["observed_release_branch"] + ")"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
