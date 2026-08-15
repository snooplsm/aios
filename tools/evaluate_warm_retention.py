#!/usr/bin/env python3
"""Evaluate a cold/warm physical run without retaining content-bearing fields."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DIGEST = re.compile(r"[0-9a-f]{64}")
RUNTIMES = ("sherpa_onnx_tts", "litert_lm", "whisper_cpp")


class WarmRetentionError(ValueError):
    pass


def read_json(path: Path) -> tuple[dict, bytes]:
    try:
        raw = path.read_bytes()
        value = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise WarmRetentionError(f"cannot read JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise WarmRetentionError(f"JSON root must be an object: {path}")
    return value, raw


def canonical_sha256(value: dict) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_suite(suite: dict) -> None:
    expected = {
        "schema_version", "suite_version", "source_evidence_kind",
        "source_suite_version", "supported_device_codenames",
        "required_cold_initializations", "required_warm_cache_hits",
        "required_warm_digest_cache_hits", "health_gates", "latency_gates_ms",
    }
    if set(suite) != expected or suite.get("schema_version") != 1:
        raise WarmRetentionError("warm-retention suite has unknown or missing fields")
    if not isinstance(suite.get("suite_version"), int) or suite["suite_version"] < 1:
        raise WarmRetentionError("warm-retention suite version must be positive")
    if suite.get("source_evidence_kind") != "pixel_aios_single_model_diagnostic" \
            or suite.get("source_suite_version") != 4:
        raise WarmRetentionError("warm-retention source contract is unsupported")
    devices = suite.get("supported_device_codenames")
    if not isinstance(devices, list) or not devices \
            or not all(isinstance(item, str) and item for item in devices) \
            or len(devices) != len(set(devices)):
        raise WarmRetentionError("supported device codenames must be unique strings")
    for field in ("required_cold_initializations", "required_warm_cache_hits"):
        values = suite.get(field)
        if not isinstance(values, dict) or set(values) != set(RUNTIMES) \
                or not all(isinstance(value, int) and not isinstance(value, bool)
                           and value >= 1 for value in values.values()):
            raise WarmRetentionError(f"{field} must cover every retained runtime")
    digest_hits = suite.get("required_warm_digest_cache_hits")
    if not isinstance(digest_hits, dict) \
            or set(digest_hits) != {"litert_lm", "whisper_cpp"} \
            or not all(isinstance(value, int) and not isinstance(value, bool)
                       and value >= 1 for value in digest_hits.values()):
        raise WarmRetentionError(
            "required_warm_digest_cache_hits must cover large model artifacts")
    health = suite.get("health_gates")
    required_health = {
        "max_release_or_eviction_events", "max_aios_low_memory_kills",
        "max_background_low_memory_kills", "max_oom_events",
        "max_fatal_events", "max_thermal_status", "min_available_memory_mb",
    }
    if not isinstance(health, dict) or set(health) != required_health \
            or not all(isinstance(value, int) and not isinstance(value, bool)
                       and value >= 0 for value in health.values()) \
            or not 0 <= health["max_thermal_status"] <= 6 \
            or health["min_available_memory_mb"] <= 0:
        raise WarmRetentionError("warm-retention health gates are invalid")
    latency = suite.get("latency_gates_ms")
    required_capabilities = {
        "speech_synthesis", "streaming_asr", "text_generation",
        "image_understanding",
    }
    if not isinstance(latency, dict) or set(latency) != required_capabilities \
            or not all(isinstance(value, int) and not isinstance(value, bool)
                       and value > 0 for value in latency.values()):
        raise WarmRetentionError("warm-retention latency gates are invalid")


def validate_source(record: dict, suite: dict, label: str) -> None:
    required = {
        "schema_version", "evidence_kind", "suite_version", "device_codename",
        "serial_sha256", "total_ram_mb", "build_fingerprint_sha256",
        "completed_at", "host_resource_sampling", "runtime_phase_diagnostics",
        "results",
    }
    missing = required - set(record)
    if missing or record.get("schema_version") != 1:
        raise WarmRetentionError(f"{label}: source evidence is missing {sorted(missing)}")
    if record.get("evidence_kind") != suite["source_evidence_kind"] \
            or record.get("suite_version") != suite["source_suite_version"]:
        raise WarmRetentionError(f"{label}: source evidence contract does not match")
    if record.get("device_codename") not in suite["supported_device_codenames"]:
        raise WarmRetentionError(f"{label}: unsupported physical device")
    for field in ("serial_sha256", "build_fingerprint_sha256"):
        if DIGEST.fullmatch(str(record.get(field, ""))) is None:
            raise WarmRetentionError(f"{label}: {field} must be SHA-256")
    if not isinstance(record.get("total_ram_mb"), int) \
            or record["total_ram_mb"] <= 0:
        raise WarmRetentionError(f"{label}: total RAM must be positive")
    if not isinstance(record.get("completed_at"), str) \
            or not record["completed_at"].endswith("Z"):
        raise WarmRetentionError(f"{label}: completion time must be UTC RFC3339")
    diagnostics = record.get("runtime_phase_diagnostics")
    if not isinstance(diagnostics, dict) or diagnostics.get("schema_version") != 2:
        raise WarmRetentionError(f"{label}: runtime diagnostics schema 2 is required")
    for field in (
            "tts_engine_events", "litert_lm_engine_events",
            "whisper_model_events", "residency_events",
            "artifact_verification_events"):
        if not isinstance(diagnostics.get(field), list):
            raise WarmRetentionError(f"{label}: missing diagnostic list {field}")
    health = diagnostics.get("system_health")
    required_health = {
        "low_memory_event_count", "low_memory_kill_count",
        "aios_low_memory_kill_count", "background_low_memory_kill_count",
        "oom_event_count", "fatal_event_count", "max_runtime_thermal_status",
    }
    if not isinstance(health, dict) or set(health) != required_health \
            or not all(isinstance(value, int) and not isinstance(value, bool)
                       for value in health.values()):
        raise WarmRetentionError(f"{label}: system-health diagnostics are invalid")
    if not isinstance(record.get("results"), list) or not record["results"]:
        raise WarmRetentionError(f"{label}: model results are required")


def initialization_counts(diagnostics: dict) -> dict[str, int]:
    return {
        "sherpa_onnx_tts": len(diagnostics["tts_engine_events"]),
        "litert_lm": len(diagnostics["litert_lm_engine_events"]),
        "whisper_cpp": len(diagnostics["whisper_model_events"]),
    }


def cache_hit_counts(diagnostics: dict) -> dict[str, int]:
    counts = {runtime: 0 for runtime in RUNTIMES}
    for event in diagnostics["residency_events"]:
        if isinstance(event, dict) and event.get("action") == "cache_hit" \
                and event.get("runtime") in counts:
            counts[event["runtime"]] += 1
    return counts


def digest_cache_hit_counts(diagnostics: dict) -> dict[str, int]:
    counts = {"litert_lm": 0, "whisper_cpp": 0}
    for event in diagnostics["artifact_verification_events"]:
        if isinstance(event, dict) and event.get("action") == "digest_cache_hit" \
                and event.get("runtime") in counts:
            counts[event["runtime"]] += 1
    return counts


def available_memory_floor(record: dict) -> float:
    sampling = record.get("host_resource_sampling")
    if not isinstance(sampling, dict):
        raise WarmRetentionError("warm: host resource sampling is absent")
    values: list[float] = []
    for field in ("memory_before", "memory_after"):
        snapshot = sampling.get(field)
        value = snapshot.get("MemAvailable_mb") if isinstance(snapshot, dict) else None
        if isinstance(value, bool) or not isinstance(value, (int, float)) \
                or not math.isfinite(value) or value < 0:
            raise WarmRetentionError(f"warm: invalid {field} MemAvailable observation")
        values.append(float(value))
    return min(values)


def result_metrics(record: dict) -> tuple[dict[str, int], int, list[str]]:
    first_outputs: dict[str, int] = {}
    thermal_status_max = 0
    failures: list[str] = []
    for result in record["results"]:
        if not isinstance(result, dict) or not isinstance(result.get("capability"), str) \
                or not isinstance(result.get("metrics"), dict):
            raise WarmRetentionError("warm: malformed model result")
        capability = result["capability"]
        metrics = result["metrics"]
        if capability in first_outputs:
            raise WarmRetentionError(f"warm: duplicate capability {capability}")
        first_output = metrics.get("first_output_ms")
        thermal = metrics.get("thermal_status_max")
        if isinstance(first_output, bool) or not isinstance(first_output, int) \
                or first_output < 0:
            raise WarmRetentionError(f"warm: {capability} first output is invalid")
        if isinstance(thermal, bool) or not isinstance(thermal, int) \
                or not 0 <= thermal <= 6:
            raise WarmRetentionError(f"warm: {capability} thermal status is invalid")
        first_outputs[capability] = first_output
        thermal_status_max = max(thermal_status_max, thermal)
        if metrics.get("succeeded") is not True or metrics.get("error") not in ("", None):
            failures.append(capability)
    return first_outputs, thermal_status_max, failures


def evaluate(suite_path: Path, cold_path: Path, warm_path: Path,
             output_path: Path) -> dict:
    suite, _ = read_json(suite_path)
    cold, cold_bytes = read_json(cold_path)
    warm, warm_bytes = read_json(warm_path)
    validate_suite(suite)
    validate_source(cold, suite, "cold")
    validate_source(warm, suite, "warm")
    identity_fields = (
        "device_codename", "serial_sha256", "total_ram_mb",
        "build_fingerprint_sha256",
    )
    if any(cold[field] != warm[field] for field in identity_fields):
        raise WarmRetentionError("cold and warm evidence are not from the same build/device")

    cold_diagnostics = cold["runtime_phase_diagnostics"]
    warm_diagnostics = warm["runtime_phase_diagnostics"]
    cold_initializations = initialization_counts(cold_diagnostics)
    warm_initializations = initialization_counts(warm_diagnostics)
    warm_cache_hits = cache_hit_counts(warm_diagnostics)
    warm_digest_cache_hits = digest_cache_hit_counts(warm_diagnostics)
    release_or_eviction_events = sum(
        isinstance(event, dict) and event.get("action") in {
            "release_requested", "released", "cache_evicted"
        } for event in warm_diagnostics["residency_events"]
    )
    health = warm_diagnostics["system_health"]
    memory_floor = available_memory_floor(warm)
    first_outputs, result_thermal_max, failed_capabilities = result_metrics(warm)
    runtime_thermal_max = health["max_runtime_thermal_status"]
    thermal_max = max(result_thermal_max, runtime_thermal_max)

    failed_gates: list[str] = []
    for runtime, minimum in suite["required_cold_initializations"].items():
        if cold_initializations[runtime] < minimum:
            failed_gates.append(f"cold_initialization:{runtime}")
    for runtime, minimum in suite["required_warm_cache_hits"].items():
        if warm_cache_hits[runtime] < minimum:
            failed_gates.append(f"warm_cache_hit:{runtime}")
    for runtime, minimum in suite["required_warm_digest_cache_hits"].items():
        if warm_digest_cache_hits[runtime] < minimum:
            failed_gates.append(f"warm_digest_cache_hit:{runtime}")
    for runtime, count in warm_initializations.items():
        if count != 0:
            failed_gates.append(f"unexpected_warm_initialization:{runtime}")
    health_gates = suite["health_gates"]
    comparisons = (
        ("release_or_eviction", release_or_eviction_events,
         health_gates["max_release_or_eviction_events"]),
        ("aios_low_memory_kills", health["aios_low_memory_kill_count"],
         health_gates["max_aios_low_memory_kills"]),
        ("background_low_memory_kills", health["background_low_memory_kill_count"],
         health_gates["max_background_low_memory_kills"]),
        ("oom_events", health["oom_event_count"], health_gates["max_oom_events"]),
        ("fatal_events", health["fatal_event_count"], health_gates["max_fatal_events"]),
        ("thermal_status", thermal_max, health_gates["max_thermal_status"]),
    )
    failed_gates.extend(name for name, observed, maximum in comparisons
                        if observed > maximum)
    if memory_floor < health_gates["min_available_memory_mb"]:
        failed_gates.append("available_memory")
    for capability, maximum in suite["latency_gates_ms"].items():
        if capability not in first_outputs:
            raise WarmRetentionError(f"warm: missing required capability {capability}")
        if first_outputs[capability] > maximum:
            failed_gates.append(f"first_output:{capability}")
    failed_gates.extend(f"inference_failed:{item}" for item in failed_capabilities)

    evidence = {
        "schema_version": 1,
        "suite_version": suite["suite_version"],
        "suite_sha256": canonical_sha256(suite),
        "decision": "failed" if failed_gates else "passed",
        "failed_gates": sorted(set(failed_gates)),
        "device_codename": warm["device_codename"],
        "serial_sha256": warm["serial_sha256"],
        "total_ram_mb": warm["total_ram_mb"],
        "build_fingerprint_sha256": warm["build_fingerprint_sha256"],
        "completed_at": warm["completed_at"],
        "cold_source_sha256": hashlib.sha256(cold_bytes).hexdigest(),
        "warm_source_sha256": hashlib.sha256(warm_bytes).hexdigest(),
        "observations": {
            "cold_initializations": cold_initializations,
            "warm_initializations": warm_initializations,
            "warm_cache_hits": warm_cache_hits,
            "warm_digest_cache_hits": warm_digest_cache_hits,
            "release_or_eviction_events": release_or_eviction_events,
            "aios_low_memory_kills": health["aios_low_memory_kill_count"],
            "background_low_memory_kills": health["background_low_memory_kill_count"],
            "oom_events": health["oom_event_count"],
            "fatal_events": health["fatal_event_count"],
            "thermal_status_max": thermal_max,
            "available_memory_floor_mb": memory_floor,
            "first_output_ms": first_outputs,
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", type=Path,
                        default=ROOT / "config" / "warm_retention_benchmark.json")
    parser.add_argument("--cold", type=Path, required=True)
    parser.add_argument("--warm", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        evidence = evaluate(
            arguments.suite, arguments.cold, arguments.warm, arguments.output)
    except WarmRetentionError as error:
        print(f"warm-retention evaluation failed: {error}", file=sys.stderr)
        return 1
    print(f"Wrote warm-retention evidence: {arguments.output} "
          f"({evidence['decision']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
