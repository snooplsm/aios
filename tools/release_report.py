#!/usr/bin/env python3
"""Report AIOS release-gate state without pretending unrun hardware tests passed."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def report(root: Path, require_pass: bool = False) -> int:
    gates = load(root / "config" / "release_gates.json")["gates"]
    status_document = load(root / "config" / "release_status.json")
    statuses = status_document["statuses"]
    counts = Counter(value["status"] for value in statuses.values())
    print(f"Release target: {status_document['target']}")
    print("Gate summary: " + ", ".join(
        f"{name}={counts.get(name, 0)}"
        for name in ("passed", "failed", "blocked", "not_run")
    ))
    unfinished = [
        gate["id"] for gate in gates
        if gate["required"] and statuses[gate["id"]]["status"] != "passed"
    ]
    if unfinished:
        print("Required gates not passed:")
        for gate_id in unfinished:
            print(f"  {gate_id}: {statuses[gate_id]['status']}")
    return 1 if require_pass and unfinished else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--require-pass", action="store_true")
    arguments = parser.parse_args()
    return report(arguments.root.resolve(), arguments.require_pass)


if __name__ == "__main__":
    raise SystemExit(main())
