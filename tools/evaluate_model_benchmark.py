#!/usr/bin/env python3
"""Turn raw device measurements into fail-closed model-admission evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IDENTIFIER = re.compile(r"[a-z0-9][a-z0-9._-]{0,127}")
DIGEST = re.compile(r"[0-9a-f]{64}")
ROLES = ("text_model", "media_model", "tts_model", "asr_candidate")


class BenchmarkError(ValueError):
    pass


def read_json(path: Path) -> tuple[dict, bytes]:
    try:
        raw = path.read_bytes()
        return json.loads(raw.decode("utf-8")), raw
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BenchmarkError(f"cannot read JSON {path}: {error}") from error


def canonical_sha256(value: dict) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def finite_metric(value: object) -> bool:
    return isinstance(value, (int, float, bool)) \
        and (not isinstance(value, float) or math.isfinite(value))


def validate_suite(suite: dict) -> None:
    if set(suite) != {
            "schema_version", "suite_version", "required_observations",
            "required_role_coverage", "gate_profiles"} \
            or suite.get("schema_version") != 1:
        raise BenchmarkError("benchmark suite has unknown or missing fields")
    if not isinstance(suite.get("suite_version"), int) \
            or suite["suite_version"] < 1:
        raise BenchmarkError("benchmark suite version must be positive")
    observations = suite.get("required_observations")
    if not isinstance(observations, list) or not observations \
            or len(observations) != len(set(observations)) \
            or not all(isinstance(item, str) and IDENTIFIER.fullmatch(item)
                       for item in observations):
        raise BenchmarkError("required observations must be unique identifiers")
    profiles = suite.get("gate_profiles")
    if not isinstance(profiles, dict) or set(profiles) != set(ROLES):
        raise BenchmarkError("benchmark suite must define every model role")
    for role, gates in profiles.items():
        if not isinstance(gates, list) or not gates:
            raise BenchmarkError(f"{role}: gates are required")
        ids: set[str] = set()
        for gate in gates:
            if not isinstance(gate, dict) or set(gate) != {
                    "id", "metric", "operator", "threshold"}:
                raise BenchmarkError(f"{role}: malformed gate")
            if not isinstance(gate["id"], str) \
                    or IDENTIFIER.fullmatch(gate["id"]) is None \
                    or gate["id"] in ids:
                raise BenchmarkError(f"{role}: gate IDs must be unique identifiers")
            ids.add(gate["id"])
            if not isinstance(gate["metric"], str) \
                    or IDENTIFIER.fullmatch(gate["metric"]) is None \
                    or gate["operator"] not in {"eq", "gte", "lte"} \
                    or not finite_metric(gate["threshold"]):
                raise BenchmarkError(f"{role}: invalid gate predicate")
    coverage = suite.get("required_role_coverage")
    if not isinstance(coverage, dict) or set(coverage) != {"all", "at_least_one"} \
            or coverage["all"] != ["text_model", "media_model", "tts_model"] \
            or coverage["at_least_one"] != ["asr_candidate"]:
        raise BenchmarkError("unsupported benchmark role coverage")


def tier_roles(catalog: dict, tier_id: str) -> tuple[dict[str, dict], dict[str, set[str]]]:
    models = {item["id"]: item for item in catalog.get("models", [])}
    tiers = {item.get("id"): item for item in catalog.get("tiers", [])}
    tier = tiers.get(tier_id)
    if tier is None:
        raise BenchmarkError(f"unknown catalog tier: {tier_id}")
    roles: dict[str, set[str]] = {}
    seen_tiers: set[str] = set()
    current = tier
    while current is not None:
        current_id = current["id"]
        if current_id in seen_tiers:
            raise BenchmarkError("catalog tier fallback cycle")
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
            raise BenchmarkError("invalid catalog fallback tier")
        current = fallback
    if set(roles) - set(models):
        raise BenchmarkError("benchmark tier chain references an unknown catalog model")
    return models, roles


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


def evaluate(catalog_path: Path, suite_path: Path, raw_path: Path,
             output_path: Path) -> dict:
    catalog, _ = read_json(catalog_path)
    suite, _ = read_json(suite_path)
    raw, _ = read_json(raw_path)
    validate_suite(suite)
    expected_top = {
        "schema_version", "suite_version", "profile_id", "catalog_tier",
        "device_codename", "total_ram_mb", "build_fingerprint_sha256",
        "completed_at", "results",
    }
    if set(raw) != expected_top or raw.get("schema_version") != 2:
        raise BenchmarkError("raw benchmark has unknown or missing top-level fields")
    if raw.get("suite_version") != suite["suite_version"]:
        raise BenchmarkError("raw benchmark suite version does not match policy")
    for field in ("profile_id", "catalog_tier", "device_codename"):
        if not isinstance(raw.get(field), str) \
                or IDENTIFIER.fullmatch(raw[field]) is None:
            raise BenchmarkError(f"invalid raw benchmark {field}")
    if not isinstance(raw.get("total_ram_mb"), int) \
            or raw["total_ram_mb"] <= 0:
        raise BenchmarkError("raw benchmark total RAM must be positive")
    if DIGEST.fullmatch(str(raw.get("build_fingerprint_sha256", ""))) is None:
        raise BenchmarkError("raw benchmark fingerprint digest must be SHA-256")
    completed_at = raw.get("completed_at")
    if not isinstance(completed_at, str) or not completed_at.endswith("Z"):
        raise BenchmarkError("raw benchmark completion time must be UTC RFC3339")

    models, roles = tier_roles(catalog, raw["catalog_tier"])
    raw_results = raw.get("results")
    if not isinstance(raw_results, list) or not raw_results:
        raise BenchmarkError("raw benchmark model results are required")
    results: list[dict] = []
    seen: set[tuple[str, str]] = set()
    for result in raw_results:
        if not isinstance(result, dict) or set(result) != {
                "model_id", "role", "runtime", "backend", "artifact_sha256",
                "metrics"}:
            raise BenchmarkError("raw model result has unknown or missing fields")
        model_id = result.get("model_id")
        role = result.get("role")
        result_key = (model_id, role)
        if model_id not in roles or role not in roles[model_id] or result_key in seen:
            raise BenchmarkError(
                f"duplicate or out-of-tier raw model role: {model_id}/{role}")
        seen.add(result_key)
        model = models[model_id]
        if result.get("runtime") != model["runtime"] \
                or result.get("backend") not in model["allowed_backends"]:
            raise BenchmarkError(f"{model_id}: runtime/backend does not match catalog")
        if DIGEST.fullmatch(str(result.get("artifact_sha256", ""))) is None:
            raise BenchmarkError(f"{model_id}: artifact digest must be SHA-256")
        metrics = result.get("metrics")
        if not isinstance(metrics, dict) or not metrics \
                or not all(isinstance(name, str) and IDENTIFIER.fullmatch(name)
                           and finite_metric(value)
                           for name, value in metrics.items()):
            raise BenchmarkError(f"{model_id}: metrics must be finite numeric/boolean values")
        missing_observations = set(suite["required_observations"]) - set(metrics)
        if missing_observations:
            raise BenchmarkError(
                f"{model_id}: missing observations {sorted(missing_observations)}")
        peak_rss_mb = metrics["peak_rss_mb"]
        thermal_status_max = metrics["thermal_status_max"]
        if isinstance(peak_rss_mb, bool) \
                or not isinstance(peak_rss_mb, int) or peak_rss_mb <= 0:
            raise BenchmarkError(
                f"{model_id}: PSS observation must be a positive integer")
        if isinstance(thermal_status_max, bool) \
                or not isinstance(thermal_status_max, int) \
                or not 0 <= thermal_status_max <= 6:
            raise BenchmarkError(
                f"{model_id}: thermal observation must be an Android status 0..6")
        gates = suite["gate_profiles"][role]
        missing_gate_metrics = {gate["metric"] for gate in gates} - set(metrics)
        if missing_gate_metrics:
            raise BenchmarkError(
                f"{model_id}: missing gate metrics {sorted(missing_gate_metrics)}")
        failed = [gate["id"] for gate in gates
                  if not gate_passes(metrics[gate["metric"]],
                                     gate["operator"], gate["threshold"])]
        results.append({
            "model_id": model_id,
            "role": role,
            "runtime": result["runtime"],
            "backend": result["backend"],
            "artifact_sha256": result["artifact_sha256"],
            "decision": "failed" if failed else "passed",
            "required_gates": [gate["id"] for gate in gates],
            "failed_gates": failed,
            "metrics": metrics,
        })
    seen_roles = {role for _, role in seen}
    coverage = suite["required_role_coverage"]
    if not set(coverage["all"]) <= seen_roles \
            or not set(coverage["at_least_one"]).intersection(seen_roles):
        raise BenchmarkError(
            "raw benchmark needs text, media, TTS, and at least one ASR result")

    evidence = {
        "schema_version": 3,
        "suite_version": suite["suite_version"],
        "suite_sha256": canonical_sha256(suite),
        "profile_id": raw["profile_id"],
        "catalog_tier": raw["catalog_tier"],
        "device_codename": raw["device_codename"],
        "total_ram_mb": raw["total_ram_mb"],
        "build_fingerprint_sha256": raw["build_fingerprint_sha256"],
        "completed_at": completed_at,
        "results": sorted(results, key=lambda item: (item["model_id"], item["role"])),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path,
                        default=ROOT / "config" / "model_catalog.json")
    parser.add_argument("--suite", type=Path,
                        default=ROOT / "config" / "model_benchmark_suite.json")
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        evidence = evaluate(arguments.catalog, arguments.suite,
                            arguments.raw, arguments.output)
    except BenchmarkError as error:
        print(f"model benchmark evaluation failed: {error}", file=sys.stderr)
        return 1
    failures = sum(item["decision"] == "failed" for item in evidence["results"])
    print(f"Wrote benchmark evidence: {arguments.output} ({failures} model failure(s))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
