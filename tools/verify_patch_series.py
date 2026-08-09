#!/usr/bin/env python3
"""Verify or transactionally stage AIOS patches at immutable AOSP commits."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class PatchVerificationError(RuntimeError):
    pass


def command(arguments: list[str], cwd: Path) -> str:
    if arguments and arguments[0] == "git":
        arguments = ["git", "-c", f"safe.directory={cwd}", *arguments[1:]]
    completed = subprocess.run(
        arguments,
        cwd=cwd,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise PatchVerificationError(completed.stdout.strip())
    return completed.stdout.strip()


def parse_overrides(values: list[str]) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for value in values:
        project, separator, raw_path = value.partition("=")
        if not separator or not project or not raw_path or project in result:
            raise PatchVerificationError(
                "--project-root must be a unique PROJECT=/absolute/or/relative/path"
            )
        result[project] = Path(raw_path).resolve()
    return result


def load_entries(
    root: Path,
    aosp_root: Path,
    overrides: dict[str, Path],
) -> list[tuple[dict, Path, Path, str]]:
    series = json.loads((root / "patches" / "series.json").read_text(encoding="utf-8"))
    entries = []
    for item in series["patches"]:
        project = item["project"]
        checkout = overrides.get(project, (aosp_root / project).resolve())
        if not (checkout / ".git").exists():
            raise PatchVerificationError(f"{item['id']}: missing checkout {checkout}")
        head = command(["git", "rev-parse", "HEAD"], checkout)
        if head != item["base_revision"]:
            raise PatchVerificationError(
                f"{item['id']}: expected {item['base_revision']}, found {head}"
            )
        patch_path = (root / "patches" / item["file"]).resolve()
        actual_digest = hashlib.sha256(patch_path.read_bytes()).hexdigest()
        if actual_digest != item["sha256"]:
            raise PatchVerificationError(f"{item['id']}: patch digest mismatch")
        entries.append((item, checkout, patch_path, head))
    return entries


def verify(
    root: Path,
    aosp_root: Path,
    overrides: dict[str, Path],
    reverse: bool,
) -> None:
    for item, checkout, patch_path, head in load_entries(
        root, aosp_root, overrides
    ):
        arguments = ["git", "apply", "--check"]
        if reverse:
            arguments.append("--reverse")
        arguments.append(str(patch_path))
        command(arguments, checkout)
        direction = "reverse" if reverse else "forward"
        print(f"{item['id']}: {direction} apply verified at {head}")


def apply_series(
    root: Path,
    aosp_root: Path,
    overrides: dict[str, Path],
) -> None:
    entries = load_entries(root, aosp_root, overrides)
    for checkout in {entry[1] for entry in entries}:
        if command(["git", "status", "--porcelain", "--untracked-files=all"], checkout):
            raise PatchVerificationError(
                f"refusing to patch dirty tracked checkout: {checkout}"
            )

    applied: list[tuple[dict, Path, Path, str]] = []
    try:
        for entry in entries:
            item, checkout, patch_path, head = entry
            command(["git", "apply", "--check", "--index", str(patch_path)], checkout)
            command(["git", "apply", "--index", str(patch_path)], checkout)
            applied.append(entry)
            print(f"{item['id']}: staged at immutable base {head}")
    except PatchVerificationError as error:
        rollback_errors = []
        for item, checkout, patch_path, _ in reversed(applied):
            try:
                command(["git", "apply", "--reverse", "--index", str(patch_path)],
                        checkout)
            except PatchVerificationError as rollback_error:
                rollback_errors.append(f"{item['id']}: {rollback_error}")
        suffix = (f"; rollback failures: {rollback_errors}" if rollback_errors else "")
        raise PatchVerificationError(f"patch transaction failed: {error}{suffix}") from error


def revert_series(
    root: Path,
    aosp_root: Path,
    overrides: dict[str, Path],
) -> None:
    entries = load_entries(root, aosp_root, overrides)
    checkouts = {entry[1] for entry in entries}
    for checkout in checkouts:
        if command(["git", "diff", "--name-only"], checkout):
            raise PatchVerificationError(
                f"refusing to revert patches with unstaged changes: {checkout}"
            )

    reverted: list[tuple[dict, Path, Path, str]] = []
    try:
        for entry in reversed(entries):
            item, checkout, patch_path, _ = entry
            command(
                ["git", "apply", "--reverse", "--check", "--index", str(patch_path)],
                checkout,
            )
            command(["git", "apply", "--reverse", "--index", str(patch_path)],
                    checkout)
            reverted.append(entry)
            print(f"{item['id']}: staged patch reverted")
        dirty = [
            str(checkout) for checkout in checkouts
            if command(["git", "status", "--porcelain", "--untracked-files=all"],
                       checkout)
        ]
        if dirty:
            raise PatchVerificationError(
                f"revert would leave unrelated tracked changes: {dirty}"
            )
    except PatchVerificationError as error:
        rollback_errors = []
        for item, checkout, patch_path, _ in reversed(reverted):
            try:
                command(["git", "apply", "--index", str(patch_path)], checkout)
            except PatchVerificationError as rollback_error:
                rollback_errors.append(f"{item['id']}: {rollback_error}")
        suffix = (f"; rollback failures: {rollback_errors}" if rollback_errors else "")
        raise PatchVerificationError(f"patch revert failed: {error}{suffix}") from error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--aosp-root", type=Path, required=True)
    parser.add_argument("--project-root", action="append", default=[])
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument(
        "--reverse",
        action="store_true",
        help="verify an already-applied checkout by checking reverse application",
    )
    actions.add_argument(
        "--apply",
        action="store_true",
        help="transactionally apply and stage the exact patch series",
    )
    actions.add_argument(
        "--revert",
        action="store_true",
        help="transactionally remove a previously staged exact patch series",
    )
    arguments = parser.parse_args()
    try:
        root = arguments.root.resolve()
        aosp_root = arguments.aosp_root.resolve()
        overrides = parse_overrides(arguments.project_root)
        if arguments.apply:
            apply_series(root, aosp_root, overrides)
        elif arguments.revert:
            revert_series(root, aosp_root, overrides)
        else:
            verify(root, aosp_root, overrides, arguments.reverse)
    except (OSError, KeyError, json.JSONDecodeError, PatchVerificationError) as error:
        print(f"patch verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
