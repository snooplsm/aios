#!/usr/bin/env python3
"""Record explicit model-license acceptance outside the AIOS source tree."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "config" / "model_catalog.json"
MODEL_ID = re.compile(r"[a-z0-9][a-z0-9._-]{0,127}")


class AcceptanceError(ValueError):
    pass


def catalog_licenses(catalog_path: Path) -> dict[str, str]:
    try:
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AcceptanceError(f"cannot read model catalog: {error}") from error
    models = catalog.get("models")
    if catalog.get("schema_version") != 1 or not isinstance(models, list):
        raise AcceptanceError("unsupported model catalog")
    licenses: dict[str, str] = {}
    for model in models:
        if not isinstance(model, dict):
            raise AcceptanceError("malformed model catalog entry")
        model_id = model.get("id")
        license_url = model.get("license_url")
        if (not isinstance(model_id, str)
                or MODEL_ID.fullmatch(model_id) is None
                or not isinstance(license_url, str)
                or not license_url.startswith("https://")
                or model_id in licenses):
            raise AcceptanceError("malformed or duplicate model license entry")
        licenses[model_id] = license_url
    return licenses


def parse_acceptance(value: str) -> tuple[str, str]:
    try:
        model_id, license_url = value.split("=", 1)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            "acceptance must be MODEL_ID=EXACT_LICENSE_URL") from error
    if MODEL_ID.fullmatch(model_id) is None or not license_url.startswith("https://"):
        raise argparse.ArgumentTypeError(
            "acceptance must be MODEL_ID=EXACT_LICENSE_URL")
    return model_id, license_url


def external_output(output: Path, source_root: Path = ROOT) -> Path:
    if output.exists() and output.is_symlink():
        raise AcceptanceError("acceptance output must not be a symbolic link")
    if output.parent.exists() and output.parent.is_symlink():
        raise AcceptanceError("acceptance output directory must not be a symbolic link")
    root = source_root.resolve()
    resolved = output.resolve()
    if resolved == root or root in resolved.parents:
        raise AcceptanceError("acceptance output must be outside the AIOS source tree")
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.parent.is_symlink() or not output.parent.is_dir():
        raise AcceptanceError("acceptance output directory must be a real directory")
    return output


def _read_existing(output: Path) -> list[dict[str, str]]:
    if not output.exists():
        return []
    try:
        document = json.loads(output.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AcceptanceError(f"cannot read existing acceptance record: {error}") from error
    accepted = document.get("accepted")
    if document.get("schema_version") != 1 or not isinstance(accepted, list):
        raise AcceptanceError("unsupported existing acceptance record")
    result: list[dict[str, str]] = []
    seen: set[str] = set()
    for record in accepted:
        if (not isinstance(record, dict)
                or not all(isinstance(record.get(field), str) and record[field]
                           for field in ("model_id", "license_url", "accepted_at", "accepted_by"))
                or record["model_id"] in seen):
            raise AcceptanceError("malformed or duplicate existing acceptance record")
        seen.add(record["model_id"])
        result.append({field: record[field] for field in
                       ("model_id", "license_url", "accepted_at", "accepted_by")})
    return result


def record_acceptance(
        catalog_path: Path,
        output: Path,
        accepted_by: str,
        requested: list[tuple[str, str]],
        accepted_at: str | None = None,
        source_root: Path = ROOT,
) -> dict[str, object]:
    if not accepted_by.strip() or len(accepted_by) > 128 or "\n" in accepted_by:
        raise AcceptanceError("accepted-by must be a non-empty single-line label")
    if not requested:
        raise AcceptanceError("at least one explicit model-license acceptance is required")
    licenses = catalog_licenses(catalog_path)
    requested_by_id: dict[str, str] = {}
    for model_id, license_url in requested:
        expected_url = licenses.get(model_id)
        if expected_url is None:
            raise AcceptanceError(f"unknown catalog model: {model_id}")
        if license_url != expected_url:
            raise AcceptanceError(f"license URL mismatch for {model_id}")
        if model_id in requested_by_id:
            raise AcceptanceError(f"duplicate requested acceptance: {model_id}")
        requested_by_id[model_id] = license_url

    destination = external_output(output, source_root)
    records = _read_existing(destination)
    existing = {record["model_id"]: record for record in records}
    timestamp = accepted_at or datetime.now(timezone.utc).replace(
        microsecond=0).isoformat().replace("+00:00", "Z")
    added: list[str] = []
    for model_id, license_url in requested_by_id.items():
        record = existing.get(model_id)
        if record is not None:
            if record["license_url"] != license_url:
                raise AcceptanceError(
                    f"existing acceptance URL mismatch for {model_id}")
            continue
        records.append({
            "model_id": model_id,
            "license_url": license_url,
            "accepted_at": timestamp,
            "accepted_by": accepted_by.strip(),
        })
        added.append(model_id)

    payload = json.dumps({"schema_version": 1, "accepted": records}, indent=2) + "\n"
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
    if temporary.exists() or temporary.is_symlink():
        raise AcceptanceError(f"temporary acceptance path already exists: {temporary}")
    try:
        descriptor = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
    return {"path": str(destination), "added": added, "record_count": len(records)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--accepted-by", required=True)
    parser.add_argument(
        "--accept", action="append", type=parse_acceptance, required=True,
        metavar="MODEL_ID=EXACT_LICENSE_URL",
        help="explicitly accept the exact catalogued license URL for one model",
    )
    arguments = parser.parse_args(argv)
    try:
        result = record_acceptance(
            arguments.catalog, arguments.output, arguments.accepted_by,
            arguments.accept)
    except (AcceptanceError, OSError) as error:
        print(f"model acceptance failed: {error}", file=sys.stderr)
        return 1
    print(
        f"recorded {len(result['added'])} new acceptance(s) in {result['path']} "
        f"({result['record_count']} total)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
