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


def canonical_sha256(value: dict) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def finite_metric(value: object) -> bool:
    return isinstance(value, (int, float, bool)) \
        and (not isinstance(value, float) or math.isfinite(value))


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AdmissionError(f"cannot read JSON {path}: {error}") from error


def tier_models(catalog: dict, tier_id: str) -> tuple[dict, dict[str, set[str]]]:
    models = {item["id"]: item for item in catalog["models"]}
    tiers = {item["id"]: item for item in catalog["tiers"]}
    tier = tiers.get(tier_id)
    if tier is None:
        raise AdmissionError(f"unknown catalog tier: {tier_id}")
    roles: dict[str, set[str]] = {}
    seen_tiers: set[str] = set()
    current = tier
    while current is not None:
        current_id = current["id"]
        if current_id in seen_tiers:
            raise AdmissionError("catalog tier fallback cycle")
        seen_tiers.add(current_id)
        candidates = [
            (current["text_model"], "text_model"),
            (current["media_model"], "media_model"),
            (current["tts_model"], "tts_model"),
            *((item, "asr_candidate") for item in current["asr_candidates"]),
        ]
        for model_id, role in candidates:
            roles.setdefault(model_id, set()).add(role)
        fallback_id = current.get("fallback_tier")
        if fallback_id is None:
            break
        fallback = tiers.get(fallback_id)
        if fallback is None or fallback["min_total_ram_mb"] >= current["min_total_ram_mb"]:
            raise AdmissionError("invalid catalog fallback tier")
        current = fallback
    if set(roles) - set(models):
        raise AdmissionError("catalog tier chain references an unknown model")
    return models, roles


def require_timestamp(value: object) -> str:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise AdmissionError("completed_at must be an RFC3339 UTC timestamp")
    try:
        datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise AdmissionError("completed_at must be an RFC3339 UTC timestamp") from error
    return value


def gate_passes(value: int | float | bool, operator: str,
                threshold: int | float | bool) -> bool:
    if isinstance(threshold, bool):
        return isinstance(value, bool) and operator == "eq" and value is threshold
    if isinstance(value, bool):
        return False
    if operator == "eq":
        return value == threshold
    if operator == "gte":
        return value >= threshold
    return value <= threshold


def validate_evidence(catalog: dict, suite: dict, evidence: dict) -> dict:
    required = {
        "schema_version", "suite_version", "suite_sha256", "profile_id", "catalog_tier",
        "device_codename", "total_ram_mb", "build_fingerprint_sha256",
        "completed_at", "results",
    }
    if set(evidence) != required or evidence.get("schema_version") != 3:
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
    if DIGEST.fullmatch(str(evidence.get("suite_sha256", ""))) is None:
        raise AdmissionError("benchmark suite digest must be SHA-256")
    if suite.get("schema_version") != 1 \
            or evidence["suite_version"] != suite.get("suite_version") \
            or evidence["suite_sha256"] != canonical_sha256(suite):
        raise AdmissionError("benchmark evidence was evaluated by a different suite")
    completed_at = require_timestamp(evidence.get("completed_at"))
    models, roles = tier_models(catalog, evidence["catalog_tier"])
    tier_ids = set(roles)
    profiles = suite.get("gate_profiles")
    observations = suite.get("required_observations")
    coverage = suite.get("required_role_coverage")
    if not isinstance(profiles, dict) or not isinstance(observations, list) \
            or not isinstance(coverage, dict) \
            or set(coverage) != {"all", "at_least_one"}:
        raise AdmissionError("benchmark suite gate policy is malformed")
    results = evidence.get("results")
    if not isinstance(results, list) or not results:
        raise AdmissionError("benchmark evidence must contain model results")

    passed: list[dict] = []
    seen: set[tuple[str, str]] = set()
    for result in results:
        expected_fields = {
            "model_id", "role", "runtime", "backend", "artifact_sha256", "decision",
            "required_gates", "failed_gates", "metrics",
        }
        if not isinstance(result, dict) or set(result) != expected_fields:
            raise AdmissionError("model result has unknown or missing fields")
        model_id = result.get("model_id")
        role = result.get("role")
        result_key = (model_id, role)
        if model_id not in tier_ids or role not in roles[model_id] or result_key in seen:
            raise AdmissionError(
                f"duplicate or out-of-tier benchmark model role: {model_id}/{role}")
        seen.add(result_key)
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
        peak_rss_mb = metrics.get("peak_rss_mb")
        thermal_status_max = metrics.get("thermal_status_max")
        if isinstance(peak_rss_mb, bool) \
                or not isinstance(peak_rss_mb, int) or peak_rss_mb <= 0:
            raise AdmissionError(
                f"{model_id}: PSS observation must be a positive integer")
        if isinstance(thermal_status_max, bool) \
                or not isinstance(thermal_status_max, int) \
                or not 0 <= thermal_status_max <= 6:
            raise AdmissionError(
                f"{model_id}: thermal observation must be an Android status 0..6")
        gates = profiles.get(role)
        if not isinstance(gates, list) or not gates \
                or required_gates != [gate.get("id") for gate in gates] \
                or not set(observations) <= set(metrics) \
                or not all(isinstance(gate, dict)
                           and set(gate) == {"id", "metric", "operator", "threshold"}
                           and gate["operator"] in {"eq", "gte", "lte"}
                           and gate["metric"] in metrics
                           and finite_metric(gate["threshold"])
                           for gate in gates):
            raise AdmissionError(f"{model_id}: evidence does not match benchmark suite")
        expected_failed = [gate["id"] for gate in gates
                           if not gate_passes(metrics[gate["metric"]],
                                              gate["operator"], gate["threshold"])]
        if failed_gates != expected_failed \
                or (decision == "passed") != (not expected_failed):
            raise AdmissionError(f"{model_id}: decision disagrees with suite gates")
        if decision == "passed":
            passed.append({
                "model_id": model_id,
                "role": role,
                "backend": result["backend"],
                "artifact_sha256": result["artifact_sha256"],
            })

    seen_roles = {role for _, role in seen}
    if not set(coverage["all"]) <= seen_roles \
            or not set(coverage["at_least_one"]).intersection(seen_roles):
        raise AdmissionError(
            "benchmark evidence needs text, media, TTS, and at least one ASR result")
    passed_roles = {item["role"] for item in passed}
    if not {"text_model", "media_model", "tts_model"} <= passed_roles \
            or "asr_candidate" not in passed_roles:
        raise AdmissionError(
            "a supported profile needs text, media, TTS, and at least one ASR pass"
        )
    return {
        "profile_id": evidence["profile_id"],
        "catalog_tier": evidence["catalog_tier"],
        "device_codename": evidence["device_codename"],
        "total_ram_mb": evidence["total_ram_mb"],
        "build_fingerprint_sha256": evidence["build_fingerprint_sha256"],
        "suite_sha256": evidence["suite_sha256"],
        "completed_at": completed_at,
        "passed": sorted(passed, key=lambda item: (item["model_id"], item["role"])),
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

    promotions: dict[str, dict] = {}
    suite_path = root / "config" / "model_benchmark_suite.json"
    try:
        suite = load(suite_path)
    except AdmissionError as error:
        raise AdmissionError(f"cannot read benchmark suite: {suite_path}") from error
    for evidence_path in evidence_paths:
        try:
            raw = evidence_path.read_bytes()
            evidence = json.loads(raw.decode("utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AdmissionError(f"cannot read benchmark evidence: {evidence_path}") \
                from error
        checked = validate_evidence(catalog, suite, evidence)
        profile = by_id.get(checked["profile_id"])
        if profile is None:
            raise AdmissionError(f"unknown base profile: {checked['profile_id']}")
        profile_devices = profile.get("devices")
        if not isinstance(profile_devices, list) or len(profile_devices) != 1:
            raise AdmissionError(
                "one admission profile must identify exactly one device codename")
        if checked["device_codename"] not in profile_devices \
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
        identity = {
            "device_codename": checked["device_codename"],
            "total_ram_mb": checked["total_ram_mb"],
            "build_fingerprint_sha256": checked["build_fingerprint_sha256"],
        }
        promotion = promotions.setdefault(checked["profile_id"], {
            "models": {},
            "evidence": {},
            "identity": identity,
        })
        if promotion["identity"] != identity:
            raise AdmissionError(
                "evidence merged into one profile must share device, RAM, "
                "and build fingerprint")
        evidence_record = {
            "path": evidence_label,
            "sha256": evidence_sha256,
            "build_fingerprint_sha256": checked["build_fingerprint_sha256"],
            "suite_sha256": checked["suite_sha256"],
            "completed_at": checked["completed_at"],
        }
        promotion["evidence"][evidence_sha256] = evidence_record
        for item in checked["passed"]:
            candidate = {
                "model_id": item["model_id"],
                "backend": item["backend"],
                "artifact_sha256": item["artifact_sha256"],
                "evidence_sha256": evidence_sha256,
            }
            existing = promotion["models"].get(item["model_id"])
            if existing is not None and (
                    existing["backend"] != item["backend"]
                    or existing["artifact_sha256"] != item["artifact_sha256"]):
                raise AdmissionError(
                    f"conflicting admitted artifact for {item['model_id']}")
            promotion["models"].setdefault(item["model_id"], candidate)
            promotion.setdefault("roles", set()).add(item["role"])

    for profile_id, promotion in promotions.items():
        profile = by_id[profile_id]
        admitted_roles = promotion.get("roles", set())
        if not {"text_model", "media_model", "tts_model"} <= admitted_roles \
                or "asr_candidate" not in admitted_roles:
            raise AdmissionError(
                "combined evidence needs text, media, TTS, and at least one ASR pass")
        profile["status"] = "supported"
        profile["admitted_models"] = sorted(
            promotion["models"].values(), key=lambda item: item["model_id"])
        profile["evidence"] = sorted(
            promotion["evidence"].values(), key=lambda item: item["path"])

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
