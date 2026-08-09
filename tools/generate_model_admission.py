#!/usr/bin/env python3
"""Promote exact device/model artifacts only from reviewable benchmark evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from copy import deepcopy
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IDENTIFIER = re.compile(r"[a-z0-9][a-z0-9._-]{0,127}")
DIGEST = re.compile(r"[0-9a-f]{64}")


class AdmissionError(ValueError):
    pass


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AdmissionError(f"cannot read JSON {path}: {error}") from error


def tier_models(catalog: dict, tier_id: str) -> tuple[dict, set[str]]:
    models = {item["id"]: item for item in catalog["models"]}
    tier = next((item for item in catalog["tiers"] if item["id"] == tier_id), None)
    if tier is None:
        raise AdmissionError(f"unknown catalog tier: {tier_id}")
    ids = {
        tier["text_model"],
        tier["media_model"],
        tier["tts_model"],
        *tier["asr_candidates"],
    }
    return models, ids


def require_timestamp(value: object) -> str:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise AdmissionError("completed_at must be an RFC3339 UTC timestamp")
    try:
        datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise AdmissionError("completed_at must be an RFC3339 UTC timestamp") from error
    return value


def validate_evidence(catalog: dict, evidence: dict) -> dict:
    required = {
        "schema_version", "suite_version", "profile_id", "catalog_tier",
        "device_codename", "total_ram_mb", "build_fingerprint_sha256",
        "completed_at", "results",
    }
    if set(evidence) != required or evidence.get("schema_version") != 1:
        raise AdmissionError("benchmark evidence has unknown or missing top-level fields")
    if not isinstance(evidence.get("suite_version"), int) \
            or evidence["suite_version"] < 1:
        raise AdmissionError("benchmark suite version must be positive")
    for field in ("profile_id", "catalog_tier", "device_codename"):
        if not isinstance(evidence.get(field), str) \
                or IDENTIFIER.fullmatch(evidence[field]) is None:
            raise AdmissionError(f"invalid benchmark {field}")
    if not isinstance(evidence.get("total_ram_mb"), int) \
            or evidence["total_ram_mb"] <= 0:
        raise AdmissionError("benchmark total RAM must be positive")
    if DIGEST.fullmatch(str(evidence.get("build_fingerprint_sha256", ""))) is None:
        raise AdmissionError("benchmark fingerprint digest must be SHA-256")
    completed_at = require_timestamp(evidence.get("completed_at"))
    models, tier_ids = tier_models(catalog, evidence["catalog_tier"])
    results = evidence.get("results")
    if not isinstance(results, list) or not results:
        raise AdmissionError("benchmark evidence must contain model results")

    passed: list[dict] = []
    seen: set[str] = set()
    for result in results:
        expected_fields = {
            "model_id", "runtime", "backend", "artifact_sha256", "decision",
            "required_gates", "failed_gates", "metrics",
        }
        if not isinstance(result, dict) or set(result) != expected_fields:
            raise AdmissionError("model result has unknown or missing fields")
        model_id = result.get("model_id")
        if model_id not in tier_ids or model_id in seen:
            raise AdmissionError(f"duplicate or out-of-tier benchmark model: {model_id}")
        seen.add(model_id)
        model = models[model_id]
        if result.get("runtime") != model["runtime"] \
                or result.get("backend") not in model["allowed_backends"]:
            raise AdmissionError(f"{model_id}: runtime/backend does not match catalog")
        if DIGEST.fullmatch(str(result.get("artifact_sha256", ""))) is None:
            raise AdmissionError(f"{model_id}: artifact digest must be SHA-256")
        decision = result.get("decision")
        required_gates = result.get("required_gates")
        failed_gates = result.get("failed_gates")
        metrics = result.get("metrics")
        if decision not in {"passed", "failed"}:
            raise AdmissionError(f"{model_id}: decision must be passed or failed")
        if not isinstance(required_gates, list) or not required_gates \
                or len(required_gates) != len(set(required_gates)) \
                or not all(isinstance(item, str) and IDENTIFIER.fullmatch(item)
                           for item in required_gates):
            raise AdmissionError(f"{model_id}: required gates must be unique identifiers")
        if not isinstance(failed_gates, list) \
                or len(failed_gates) != len(set(failed_gates)) \
                or not all(isinstance(item, str) and IDENTIFIER.fullmatch(item)
                           for item in failed_gates) \
                or not set(failed_gates) <= set(required_gates):
            raise AdmissionError(f"{model_id}: failed gates must be a subset")
        if not isinstance(metrics, dict) or not metrics \
                or not all(IDENTIFIER.fullmatch(str(name))
                           and isinstance(value, (int, float, bool))
                           and (not isinstance(value, float) or math.isfinite(value))
                           for name, value in metrics.items()):
            raise AdmissionError(f"{model_id}: measured numeric/boolean metrics are required")
        if (decision == "passed") != (not failed_gates):
            raise AdmissionError(f"{model_id}: decision disagrees with failed gates")
        if decision == "passed":
            passed.append({
                "model_id": model_id,
                "backend": result["backend"],
                "artifact_sha256": result["artifact_sha256"],
            })

    passed_ids = {item["model_id"] for item in passed}
    tier = next(item for item in catalog["tiers"]
                if item["id"] == evidence["catalog_tier"])
    required_ids = {tier["text_model"], tier["media_model"], tier["tts_model"]}
    if not required_ids <= passed_ids \
            or not passed_ids.intersection(tier["asr_candidates"]):
        raise AdmissionError(
            "a supported profile needs text, media, TTS, and at least one ASR pass"
        )
    return {
        "profile_id": evidence["profile_id"],
        "catalog_tier": evidence["catalog_tier"],
        "device_codename": evidence["device_codename"],
        "total_ram_mb": evidence["total_ram_mb"],
        "build_fingerprint_sha256": evidence["build_fingerprint_sha256"],
        "completed_at": completed_at,
        "passed": sorted(passed, key=lambda item: item["model_id"]),
    }


def generate(
        catalog_path: Path,
        base_path: Path,
        evidence_paths: list[Path],
        output_path: Path,
        root: Path = ROOT) -> dict:
    catalog = load(catalog_path)
    document = deepcopy(load(base_path))
    if document.get("schema_version") != 1 \
            or document.get("default_action") != "deny" \
            or document.get("debug_policy") \
            != "known_profiles_research_candidates":
        raise AdmissionError("unsupported base admission policy")
    profiles = document.get("profiles")
    if not isinstance(profiles, list):
        raise AdmissionError("base admission profiles must be an array")
    by_id = {profile.get("id"): profile for profile in profiles
             if isinstance(profile, dict)}
    if len(by_id) != len(profiles) or None in by_id:
        raise AdmissionError("base admission profile IDs must be present and unique")

    promoted_profiles: set[str] = set()
    for evidence_path in evidence_paths:
        try:
            raw = evidence_path.read_bytes()
            evidence = json.loads(raw.decode("utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AdmissionError(f"cannot read benchmark evidence: {evidence_path}") \
                from error
        checked = validate_evidence(catalog, evidence)
        if checked["profile_id"] in promoted_profiles:
            raise AdmissionError("one evidence suite must own each promoted profile")
        promoted_profiles.add(checked["profile_id"])
        profile = by_id.get(checked["profile_id"])
        if profile is None:
            raise AdmissionError(f"unknown base profile: {checked['profile_id']}")
        if checked["device_codename"] not in profile.get("devices", []) \
                or checked["catalog_tier"] != profile.get("catalog_tier") \
                or not profile.get("min_total_ram_mb", 0) \
                <= checked["total_ram_mb"] \
                <= profile.get("max_total_ram_mb", -1):
            raise AdmissionError("benchmark device, tier, or RAM does not match profile")
        try:
            evidence_label = evidence_path.resolve().relative_to(root.resolve()).as_posix()
        except ValueError as error:
            raise AdmissionError("benchmark evidence must be stored under the repository") \
                from error
        evidence_sha256 = hashlib.sha256(raw).hexdigest()
        profile["status"] = "supported"
        profile["admitted_models"] = [
            {**item, "evidence_sha256": evidence_sha256}
            for item in checked["passed"]
        ]
        profile["evidence"] = [{
            "path": evidence_label,
            "sha256": evidence_sha256,
            "build_fingerprint_sha256": checked["build_fingerprint_sha256"],
            "completed_at": checked["completed_at"],
        }]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    return document


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path,
                        default=ROOT / "config" / "model_catalog.json")
    parser.add_argument("--base", type=Path,
                        default=ROOT / "config" / "model_admission.json")
    parser.add_argument("--evidence", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    arguments = parser.parse_args()
    try:
        generate(arguments.catalog, arguments.base, arguments.evidence,
                 arguments.output, arguments.root)
    except AdmissionError as error:
        print(f"model admission generation failed: {error}", file=sys.stderr)
        return 1
    print(f"Wrote reviewed model admission candidate: {arguments.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
