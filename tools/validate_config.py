#!/usr/bin/env python3
"""Validate AIOS product and model policy using only the Python standard library."""

from __future__ import annotations

import hashlib
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
OFFICIAL_AOSP_MANIFEST_URL = "https://android.googlesource.com/platform/manifest"


class ValidationError(ValueError):
    pass


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"{path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValidationError(f"{path}: root must be an object")
    return value


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def select_tier(catalog: dict[str, Any], total_ram_mb: int) -> str | None:
    eligible = [
        tier
        for tier in catalog["tiers"]
        if tier["min_total_ram_mb"] <= total_ram_mb
    ]
    if not eligible:
        return None
    return max(eligible, key=lambda tier: tier["min_total_ram_mb"])["id"]


def call_policy_decision(
        mode: str,
        known_contact: bool,
        emergency: bool,
        emergency_callback_mode: bool,
        processing_enabled: bool = True,
) -> str:
    if emergency or emergency_callback_mode:
        return "bypass_ai"
    if mode in {"all", "unknown_only", "missed_only"} \
            and not processing_enabled:
        return "ring_owner"
    if mode == "all":
        return "answer_with_ai"
    if mode == "unknown_only":
        return "ring_owner" if known_contact else "answer_with_ai"
    if mode == "missed_only":
        return "ring_then_ai"
    return "ring_owner"


def validate_product(policy: dict[str, Any]) -> None:
    require(policy.get("schema_version") == 1, "unsupported product schema")
    calls = policy["calls"]
    require(calls["default_answer_mode"] in calls["allowed_answer_modes"],
            "default answer mode is not allowed")
    expected_delay_modes = {
        "fixed_1000_ms",
        "fixed_2000_ms",
        "fixed_3000_ms",
        "fixed_4000_ms",
        "random_1010_3990_ms",
    }
    require(set(calls["allowed_auto_answer_delay_modes"]) == expected_delay_modes,
            "auto-answer delay modes must be exactly 1, 2, 3, 4 seconds and random")
    require(calls["default_auto_answer_delay_mode"]
            in calls["allowed_auto_answer_delay_modes"],
            "default auto-answer delay mode is not allowed")
    random_delay = calls["random_auto_answer_delay_ms"]
    require(random_delay == {"min_inclusive": 1010, "max_inclusive": 3990},
            "random auto-answer delay must be 1.01 through 3.99 seconds inclusive")
    require(set(calls["supported_languages"]) >= {"en", "es"},
            "English and Spanish are required")
    require(calls["bypass_emergency_calls"] is True,
            "emergency calls must bypass the AI receptionist")
    require(calls["processing_default_enabled"] is False,
            "call processing must remain opt-in")
    require(calls["automatic_answer_requires_processing"] is True,
            "automatic answering must require processing")
    require(calls["spoken_disclosure_required"] is False
            and calls["capture_starts_after_ai_answer"] is True,
            "AI answering must start capture without a mandatory spoken disclosure")
    require(calls["caller_audio_injection_status"] == "implemented_unvalidated",
            "caller audio must remain explicitly unvalidated until physical evidence exists")

    retention = policy["retention"]
    require(retention["call_artifact_ttl_hours"] == 24,
            "prototype call retention must be exactly 24 hours")
    require(retention["cleanup_on_boot"] and retention["cleanup_after_call"],
            "both boot and post-call cleanup are required")

    media = policy["media"]
    require(media["defer_bursts"] and media["defer_videos"],
            "bursts and videos must be deferred")
    require(media["deferred_requires_charging"] is True,
            "deferred media work must require charging")
    require(media["deferred_min_battery_percent"] == 80,
            "deferred media threshold must be 80 percent")
    require(set(media["writable_mime_types"]).isdisjoint(
                media["index_only_mime_types"]),
            "a MIME type cannot be writable and index-only")
    require(media["writable_mime_types"] == ["image/jpeg", "image/png"],
            "only validated simple JPEG and still-PNG writers may mutate media")
    enhanced_video = media["enhanced_video_copy"]
    require(media["automatic_video_mutation"] is False
            and enhanced_video == {
                "trigger": "owner_confirmed_share_action",
                "output_container": "video/mp4",
                "output_directory": "Movies/AIOS",
                "copy_encoded_audio_video_samples": True,
                "crash_recovery": "durable_pending_journal",
                "description_track_mime":
                    "application/vnd.aios.video-description+json",
                "subtitle_track_mime": "application/vnd.aios.subtitle+json",
                "reader_access": "signature_permission",
                "reader_max_cues_per_page": 16,
                "generic_player_subtitle_support_required": False,
            },
            "video export must be an explicit embedded-track MP4 copy")

    broker = policy["broker"]
    require(broker["access"] == "signature_permission",
            "model broker must be signature protected")
    require(broker["preempt_background_on_call"] is True,
            "calls must preempt background inference")
    require(broker.get("global_session_capacity") == 3
            and broker.get("call_asr_stream_capacity") == 2
            and broker.get("call_agent_capacity") == 1,
            "broker capacity must reserve RX, TX, and one call-agent session")
    require(broker["raw_model_file_access"] is False,
            "apps must not receive raw model file access")
    require(broker.get("release_model_admission") == "evidence_bound_fail_closed"
            and broker.get("debug_model_admission")
            == "known_device_research_candidates",
            "model admission must be evidence-bound in release and device-scoped in debug")


def validate_catalog(catalog: dict[str, Any]) -> None:
    require(catalog.get("schema_version") == 1, "unsupported catalog schema")
    memory_policy = catalog.get("memory_policy")
    require(memory_policy == {
                "mode": "adaptive_system_pressure",
                "fixed_model_limit_mb": None,
                "prefer_quality_when_headroom_available": True,
                "preempt_background_during_calls": True,
                "release_idle_models_on_trim": True,
            },
            "model memory policy must be pressure-adaptive without a fixed cap")
    models = catalog["models"]
    model_by_id = {model["id"]: model for model in models}
    require(len(model_by_id) == len(models), "model IDs must be unique")

    for model in models:
        require(model["estimated_resident_mb"] > 0,
                f"{model['id']}: resident memory must be positive")
        require(set(model["languages"]) >= {"en", "es"},
                f"{model['id']}: English and Spanish are required")
        require(model["distribution"] == "licensed_build_input",
                f"{model['id']}: model weights cannot be source-controlled")
        require(isinstance(model.get("artifact_formats"), list)
                and model["artifact_formats"],
                f"{model['id']}: allowed artifact formats are required")
        require(str(model.get("license_url", "")).startswith("https://"),
                f"{model['id']}: an HTTPS license URL is required")
        packaged_license = model.get("packaged_license")
        if packaged_license is not None:
            require(isinstance(packaged_license, dict)
                    and re.fullmatch(r"[a-zA-Z0-9._-]+\.txt",
                                     str(packaged_license.get("filename", "")))
                    is not None
                    and isinstance(packaged_license.get("size_bytes"), int)
                    and packaged_license["size_bytes"] > 0
                    and re.fullmatch(r"[0-9a-f]{64}",
                                     str(packaged_license.get("sha256", "")))
                    is not None
                    and isinstance(packaged_license.get("soong_license_kinds"), list)
                    and packaged_license["soong_license_kinds"]
                    and all(kind == "legacy_restricted"
                            or re.fullmatch(r"SPDX-license-identifier-[A-Za-z0-9.-]+",
                                            str(kind)) is not None
                            for kind in packaged_license["soong_license_kinds"]),
                    f"{model['id']}: packaged model license needs exact file metadata")
        allowed_backends = model.get("allowed_backends")
        require(isinstance(allowed_backends, list) and allowed_backends
                and len(allowed_backends) == len(set(allowed_backends)),
                f"{model['id']}: unique allowed backends are required")
        require(model.get("default_backend") in allowed_backends,
                f"{model['id']}: default backend must be allowed")
        reference = model.get("reference_artifact")
        bundle = model.get("reference_bundle")
        require(reference is None or bundle is None,
                f"{model['id']}: artifact and bundle references are mutually exclusive")
        if reference is not None:
            require(isinstance(reference, dict)
                    and str(reference.get("url", "")).startswith("https://")
                    and re.fullmatch(r"[0-9a-f]{64}",
                                     str(reference.get("sha256", ""))) is not None,
                    f"{model['id']}: reference artifact must have HTTPS URL and digest")
        if bundle is not None:
            require("bundle" in model["artifact_formats"]
                    and isinstance(bundle, dict)
                    and str(bundle.get("url", "")).startswith("https://")
                    and bundle.get("source_format") == "tar_bz2"
                    and re.fullmatch(r"[0-9a-f]{64}",
                                     str(bundle.get("sha256", ""))) is not None
                    and isinstance(bundle.get("size_bytes"), int)
                    and bundle["size_bytes"] > 0
                    and re.fullmatch(r"[a-zA-Z0-9._-]+",
                                     str(bundle.get("archive_root", ""))) is not None,
                    f"{model['id']}: reference bundle needs an exact archive lock")
            members = bundle.get("members")
            require(isinstance(members, list) and members,
                    f"{model['id']}: reference bundle members are required")
            names = [member.get("path") for member in members]
            require(len(names) == len(set(names))
                    and all(isinstance(name, str)
                            and re.fullmatch(r"[a-zA-Z0-9._-]+", name)
                            for name in names),
                    f"{model['id']}: bundle member names must be unique and flat")
            for member in members:
                require(isinstance(member.get("size_bytes"), int)
                        and member["size_bytes"] > 0
                        and re.fullmatch(r"[0-9a-f]{64}",
                                         str(member.get("sha256", ""))) is not None,
                        f"{model['id']}: bundle member needs exact size and digest")
            if "speech_synthesis" in model.get("capabilities", []):
                require(packaged_license is not None
                        and re.search(r"/blob/[0-9a-f]{40}/",
                                      model["license_url"]) is not None,
                        f"{model['id']}: TTS bundle needs an immutable packaged model license")

    tiers = catalog["tiers"]
    tier_by_id = {tier["id"]: tier for tier in tiers}
    require(len(tier_by_id) == len(tiers), "tier IDs must be unique")
    thresholds = [tier["min_total_ram_mb"] for tier in tiers]
    require(thresholds == sorted(thresholds) and len(set(thresholds)) == len(thresholds),
            "tier RAM thresholds must be unique and ascending")

    for tier in tiers:
        referenced = [
            tier["text_model"], tier["media_model"], tier["tts_model"],
            *tier["asr_candidates"]
        ]
        for model_id in referenced:
            require(model_id in model_by_id,
                    f"{tier['id']}: unknown model {model_id}")
        require("text_generation" in model_by_id[tier["text_model"]]["capabilities"],
                f"{tier['id']}: text model lacks text_generation")
        require("call_classification" in model_by_id[tier["text_model"]]["capabilities"],
                f"{tier['id']}: text model lacks call_classification")
        require("image_understanding" in model_by_id[tier["media_model"]]["capabilities"],
                f"{tier['id']}: media model lacks image_understanding")
        require("video_understanding" in model_by_id[tier["media_model"]]["capabilities"],
                f"{tier['id']}: media model lacks video_understanding")
        require("speech_synthesis" in model_by_id[tier["tts_model"]]["capabilities"],
                f"{tier['id']}: TTS model lacks speech_synthesis")
        require(all("streaming_asr" in model_by_id[item]["capabilities"]
                    for item in tier["asr_candidates"]),
                f"{tier['id']}: ASR candidate lacks streaming_asr")
        require("max_foreground_model_mb" not in tier
                and "max_live_call_model_mb" not in tier,
                f"{tier['id']}: fixed model-memory ceilings are forbidden")
        fallback = tier.get("fallback_tier")
        require(fallback is None or fallback in tier_by_id,
                f"{tier['id']}: unknown fallback tier")

    known_devices = catalog.get("known_devices")
    require(isinstance(known_devices, list) and known_devices,
            "known-device hardware records are required")
    marketing_names: set[str] = set()
    known_codenames: set[str] = set()
    for device in known_devices:
        require(isinstance(device, dict) and set(device) == {
                    "marketing_name", "codename", "ram_mb", "soc",
                    "expected_tier", "enablement_status", "hardware_source",
                    "identity_source", "build_lane",
                }, "known-device hardware record has unknown or missing fields")
        marketing_name = device["marketing_name"]
        require(isinstance(marketing_name, str)
                and marketing_name.startswith("Pixel ")
                and marketing_name not in marketing_names,
                "known-device marketing names must be unique Pixel names")
        marketing_names.add(marketing_name)
        codename = device["codename"]
        status = device["enablement_status"]
        identity_source = device["identity_source"]
        build_lane = device["build_lane"]
        if codename is None:
            require(status == "catalog_only_awaiting_device_codename_and_build_lane"
                    and identity_source is None
                    and build_lane is None,
                    f"{marketing_name}: unknown codename must remain catalog-only")
        else:
            require(isinstance(codename, str)
                    and re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", codename)
                    is not None
                    and codename not in known_codenames,
                    f"{marketing_name}: device codename must be a unique identifier")
            known_codenames.add(codename)
            require(str(identity_source).startswith(
                        "https://source.android.com/docs/setup/reference/build-numbers"),
                    f"{marketing_name}: official Android identity source is required")
            if build_lane is None:
                require(status == "catalog_only_awaiting_build_lane",
                        f"{marketing_name}: device without a build lane must remain catalog-only")
            else:
                require(isinstance(build_lane, str)
                        and re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", build_lane)
                        is not None
                        and status in {"first_target", "supported"},
                        f"{marketing_name}: enabled device needs a valid build lane and status")
        require(isinstance(device["ram_mb"], int)
                and device["ram_mb"] > 0
                and device["ram_mb"] % 1024 == 0,
                f"{marketing_name}: RAM must be recorded in whole GiB")
        require(re.fullmatch(r"Google Tensor G[0-9]+", str(device["soc"]))
                is not None,
                f"{marketing_name}: official Tensor generation is required")
        require(str(device["hardware_source"]).startswith(
                    "https://support.google.com/pixelphone/answer/7158570"),
                f"{marketing_name}: official Google hardware source is required")
        selected = select_tier(catalog, device["ram_mb"])
        require(selected == device["expected_tier"],
                f"{marketing_name}: expected {device['expected_tier']}, got {selected}")


def validate_model_benchmark_suite(root: Path) -> None:
    suite = load_json(root / "config" / "model_benchmark_suite.json")
    require(set(suite) == {
        "schema_version", "suite_version", "required_observations",
        "required_role_coverage", "gate_profiles",
    } and suite["schema_version"] == 1
            and suite["suite_version"] == 2,
            "unsupported model benchmark suite")
    observations = suite["required_observations"]
    require(isinstance(observations, list) and observations
            and len(observations) == len(set(observations))
            and {"peak_rss_mb", "thermal_status_max"} <= set(observations),
            "benchmark suite must record memory and thermal observations")
    profiles = suite["gate_profiles"]
    require(isinstance(profiles, dict) and set(profiles) == {
        "text_model", "media_model", "tts_model", "asr_candidate",
    }, "benchmark suite must define every model role")
    coverage = suite["required_role_coverage"]
    require(isinstance(coverage, dict)
            and coverage == {
                "all": ["text_model", "media_model", "tts_model"],
                "at_least_one": ["asr_candidate"],
            },
            "benchmark suite must require text/media/TTS and one selected ASR")
    all_metrics: set[str] = set()
    for role, gates in profiles.items():
        require(isinstance(gates, list) and gates,
                f"{role}: benchmark gates are required")
        gate_ids: set[str] = set()
        for gate in gates:
            require(isinstance(gate, dict) and set(gate) == {
                "id", "metric", "operator", "threshold",
            } and re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}",
                               str(gate["id"])) is not None
                    and gate["id"] not in gate_ids
                    and re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}",
                                     str(gate["metric"])) is not None
                    and gate["operator"] in {"eq", "gte", "lte"}
                    and isinstance(gate["threshold"], (int, float, bool))
                    and (not isinstance(gate["threshold"], float)
                         or math.isfinite(gate["threshold"])),
                    f"{role}: malformed benchmark gate")
            gate_ids.add(gate["id"])
            all_metrics.add(gate["metric"])
    require("peak_rss_mb" not in all_metrics,
            "device admission may observe RAM but cannot impose a fixed model cap")
    require({"en_known_answer_rate", "es_known_answer_rate"}
            <= {gate["metric"] for gate in profiles["text_model"]}
            and {"en_wer", "es_wer"}
            <= {gate["metric"] for gate in profiles["asr_candidate"]},
            "benchmark suite must gate English and Spanish quality")
    require({
                "p95_image_latency_ms",
                "p95_video_storyboard_inference_ms",
                "video_invocation_success_rate",
                "video_output_valid_rate",
            } <= {gate["metric"] for gate in profiles["media_model"]},
            "benchmark suite must separately gate image and storyboard-video inference")


def benchmark_gate_passes(value: int | float | bool, operator: str,
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


def validate_model_admission(root: Path) -> None:
    catalog = load_json(root / "config" / "model_catalog.json")
    suite_path = root / "config" / "model_benchmark_suite.json"
    suite = load_json(suite_path)
    suite_sha256 = hashlib.sha256(json.dumps(
        suite, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")).hexdigest()
    document = load_json(root / "config" / "model_admission.json")
    require(set(document) == {
        "schema_version", "default_action", "debug_policy", "profiles"
    }, "model admission has unknown or missing top-level fields")
    require(document["schema_version"] == 1
            and document["default_action"] == "deny"
            and document["debug_policy"] == "known_profiles_research_candidates",
            "model admission must fail closed outside known debug profiles")
    models = {model["id"]: model for model in catalog["models"]}
    tiers = {tier["id"]: tier for tier in catalog["tiers"]}
    profiles = document["profiles"]
    require(isinstance(profiles, list) and profiles,
            "at least one device admission profile is required")
    profile_ids: set[str] = set()
    devices_seen: set[str] = set()
    profiles_by_device: dict[str, dict[str, Any]] = {}
    for profile in profiles:
        require(isinstance(profile, dict) and set(profile) == {
            "id", "devices", "catalog_tier", "min_total_ram_mb",
            "max_total_ram_mb", "status", "research_candidate_models",
            "admitted_models", "evidence",
        }, "device admission profile has unknown or missing fields")
        require(re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}",
                             str(profile["id"])) is not None
                and profile["id"] not in profile_ids,
                "device admission profile IDs must be valid and unique")
        profile_ids.add(profile["id"])
        device_names = profile["devices"]
        require(isinstance(device_names, list) and device_names
                and len(device_names) == len(set(device_names))
                and all(re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", str(item))
                        for item in device_names),
                f"{profile['id']}: device codenames must be unique identifiers")
        require(not devices_seen.intersection(device_names),
                "a device codename cannot appear in multiple admission profiles")
        devices_seen.update(device_names)
        for device in device_names:
            profiles_by_device[device] = profile
        tier = tiers.get(profile["catalog_tier"])
        require(tier is not None, f"{profile['id']}: unknown catalog tier")
        require(isinstance(profile["min_total_ram_mb"], int)
                and isinstance(profile["max_total_ram_mb"], int)
                and 0 < profile["min_total_ram_mb"]
                <= profile["max_total_ram_mb"],
                f"{profile['id']}: invalid total-RAM range")
        tier_ids = {
            tier["text_model"], tier["media_model"], tier["tts_model"],
            *tier["asr_candidates"],
        }
        benchmark_roles = {
            tier["text_model"]: "text_model",
            tier["media_model"]: "media_model",
            tier["tts_model"]: "tts_model",
            **{item: "asr_candidate" for item in tier["asr_candidates"]},
        }
        research = profile["research_candidate_models"]
        require(isinstance(research, list) and len(research) == len(set(research))
                and set(research) == tier_ids,
                f"{profile['id']}: debug research candidates must exactly match the tier")
        status = profile["status"]
        admitted = profile["admitted_models"]
        evidence_entries = profile["evidence"]
        require(status in {"benchmark_pending", "supported"},
                f"{profile['id']}: unknown admission status")
        require(isinstance(admitted, list) and isinstance(evidence_entries, list),
                f"{profile['id']}: admissions and evidence must be arrays")
        if status == "benchmark_pending":
            require(not admitted and not evidence_entries,
                    f"{profile['id']}: pending profile cannot admit release models")
            continue
        require(admitted and evidence_entries,
                f"{profile['id']}: supported profile requires models and evidence")
        evidence_digests: set[str] = set()
        passed_by_evidence: dict[str, set[tuple[str, str, str]]] = {}
        for evidence in evidence_entries:
            require(isinstance(evidence, dict) and set(evidence) == {
                "path", "sha256", "build_fingerprint_sha256", "suite_sha256",
                "completed_at"
            }, f"{profile['id']}: malformed evidence entry")
            path_text = evidence["path"]
            evidence_path = (root / path_text).resolve()
            evidence_root = (root / "evidence" / "model-admission").resolve()
            require(isinstance(path_text, str)
                    and evidence_root in evidence_path.parents
                    and evidence_path.is_file(),
                    f"{profile['id']}: evidence must exist under evidence/model-admission")
            raw = evidence_path.read_bytes()
            actual_digest = hashlib.sha256(raw).hexdigest()
            require(evidence["sha256"] == actual_digest
                    and re.fullmatch(r"[0-9a-f]{64}", actual_digest) is not None
                    and evidence["sha256"] not in evidence_digests,
                    f"{profile['id']}: evidence digest mismatch or duplicate")
            evidence_digests.add(evidence["sha256"])
            benchmark = load_json(evidence_path)
            require(set(benchmark) == {
                        "schema_version", "suite_version", "suite_sha256",
                        "profile_id", "catalog_tier", "device_codename",
                        "total_ram_mb", "build_fingerprint_sha256",
                        "completed_at", "results",
                    }
                    and benchmark.get("schema_version") == 2
                    and isinstance(benchmark.get("suite_version"), int)
                    and benchmark["suite_version"] == suite["suite_version"]
                    and benchmark.get("suite_sha256") == suite_sha256
                    and evidence.get("suite_sha256") == suite_sha256
                    and benchmark.get("profile_id") == profile["id"]
                    and benchmark.get("catalog_tier") == profile["catalog_tier"]
                    and benchmark.get("device_codename") in device_names
                    and profile["min_total_ram_mb"] <= benchmark.get("total_ram_mb", 0)
                    <= profile["max_total_ram_mb"]
                    and benchmark.get("build_fingerprint_sha256")
                    == evidence["build_fingerprint_sha256"]
                    and benchmark.get("completed_at") == evidence["completed_at"],
                    f"{profile['id']}: benchmark identity does not match profile")
            passes: set[tuple[str, str, str]] = set()
            results = benchmark.get("results")
            require(isinstance(results, list) and results,
                    f"{profile['id']}: benchmark results are required")
            result_ids: set[str] = set()
            for result in results:
                require(isinstance(result, dict) and set(result) == {
                    "model_id", "runtime", "backend", "artifact_sha256",
                    "decision", "required_gates", "failed_gates", "metrics"
                }, f"{profile['id']}: malformed benchmark result")
                model_id = result["model_id"]
                model = models.get(model_id)
                required_gates = result["required_gates"]
                failed_gates = result["failed_gates"]
                metrics = result["metrics"]
                require(model_id in tier_ids and model_id not in result_ids
                        and model is not None
                        and result["runtime"] == model["runtime"]
                        and result["backend"] in model["allowed_backends"]
                        and re.fullmatch(r"[0-9a-f]{64}",
                                         str(result["artifact_sha256"])) is not None,
                        f"{profile['id']}: benchmark result does not match catalog")
                result_ids.add(model_id)
                require(isinstance(required_gates, list) and required_gates
                        and len(required_gates) == len(set(required_gates))
                        and all(isinstance(item, str)
                                and re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", item)
                                for item in required_gates)
                        and isinstance(failed_gates, list)
                        and len(failed_gates) == len(set(failed_gates))
                        and all(isinstance(item, str)
                                and re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", item)
                                for item in failed_gates)
                        and set(failed_gates) <= set(required_gates)
                        and isinstance(metrics, dict) and metrics
                        and all(re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}",
                                             str(name)) is not None
                                and isinstance(metric, (int, float, bool))
                                and (not isinstance(metric, float)
                                     or math.isfinite(metric))
                                for name, metric in metrics.items()),
                        f"{profile['id']}: benchmark gates and metrics are required")
                peak_rss_mb = metrics.get("peak_rss_mb")
                thermal_status_max = metrics.get("thermal_status_max")
                require(not isinstance(peak_rss_mb, bool)
                        and isinstance(peak_rss_mb, int)
                        and peak_rss_mb > 0,
                        f"{profile['id']}: benchmark PSS observation is invalid")
                require(not isinstance(thermal_status_max, bool)
                        and isinstance(thermal_status_max, int)
                        and 0 <= thermal_status_max <= 6,
                        f"{profile['id']}: benchmark thermal observation is invalid")
                gates = suite["gate_profiles"][benchmark_roles[model_id]]
                expected_gate_ids = [gate["id"] for gate in gates]
                require(required_gates == expected_gate_ids
                        and set(suite["required_observations"]) <= set(metrics)
                        and all(gate["metric"] in metrics for gate in gates),
                        f"{profile['id']}: evidence does not match benchmark suite")
                expected_failed = [
                    gate["id"] for gate in gates
                    if not benchmark_gate_passes(
                        metrics[gate["metric"]], gate["operator"], gate["threshold"])
                ]
                require(failed_gates == expected_failed
                        and (result["decision"] == "passed") == (not expected_failed)
                        and result["decision"] in {"passed", "failed"},
                        f"{profile['id']}: benchmark decision disagrees with suite gates")
                if result["decision"] == "passed":
                    passes.add((model_id, result["backend"],
                                result["artifact_sha256"]))
            measured_roles = {benchmark_roles[model_id] for model_id in result_ids}
            coverage = suite["required_role_coverage"]
            require(set(coverage["all"]) <= measured_roles
                    and set(coverage["at_least_one"]).intersection(measured_roles),
                    f"{profile['id']}: benchmark needs text/media/TTS/ASR coverage")
            passed_by_evidence[evidence["sha256"]] = passes
        admitted_ids: set[str] = set()
        for item in admitted:
            require(isinstance(item, dict) and set(item) == {
                "model_id", "backend", "artifact_sha256", "evidence_sha256"
            }, f"{profile['id']}: malformed admitted model")
            model_id = item["model_id"]
            key = (model_id, item["backend"], item["artifact_sha256"])
            require(model_id not in admitted_ids and model_id in tier_ids
                    and item["evidence_sha256"] in passed_by_evidence
                    and key in passed_by_evidence[item["evidence_sha256"]],
                    f"{profile['id']}: admitted model lacks an exact benchmark pass")
            admitted_ids.add(model_id)
        require({tier["text_model"], tier["media_model"], tier["tts_model"]}
                <= admitted_ids and admitted_ids.intersection(tier["asr_candidates"]),
                f"{profile['id']}: supported profile lacks text/media/TTS/ASR coverage")
    for device in catalog["known_devices"]:
        codename = device.get("codename")
        if codename is None or device.get("build_lane") is None:
            continue
        profile = profiles_by_device.get(codename)
        require(profile is not None
                and profile["catalog_tier"] == device["expected_tier"]
                and profile["min_total_ram_mb"] <= device["ram_mb"]
                <= profile["max_total_ram_mb"],
                f"{device['marketing_name']}: known device lacks a matching admission profile")


def validate_patch_series(root: Path) -> None:
    series = load_json(root / "patches" / "series.json")
    require(set(series) == {"schema_version", "patches"}
            and series.get("schema_version") == 2,
            "unsupported patch-series schema")
    patches = series.get("patches")
    require(isinstance(patches, list), "patch series must be an array")
    required = {
        "id", "project", "file", "base_revision", "sha256", "reason",
        "removal_condition", "owner", "paths", "tests", "rebase_notes"
    }
    relative_pattern = re.compile(
        r"[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+-]+)*"
    )
    seen: set[str] = set()
    for patch in patches:
        require(isinstance(patch, dict), "each patch entry must be an object")
        require(set(patch) == required,
                "patch entry fields must exactly match schema v2")
        require(isinstance(patch["id"], str)
                and re.fullmatch(r"[a-z0-9][a-z0-9-]{2,79}", patch["id"]),
                "patch ID must be a stable lowercase slug")
        require(patch["id"] not in seen, f"duplicate patch ID: {patch['id']}")
        seen.add(patch["id"])
        require(isinstance(patch["project"], str)
                and relative_pattern.fullmatch(patch["project"]) is not None,
                f"{patch['id']}: project must be a safe relative Repo path")
        require(isinstance(patch["owner"], str)
                and re.fullmatch(r"[a-z][a-z0-9-]{2,63}", patch["owner"]),
                f"{patch['id']}: owner must be a stable team slug")
        require(isinstance(patch["base_revision"], str)
                and re.fullmatch(r"[0-9a-f]{40}", patch["base_revision"]) is not None,
                f"{patch['id']}: base revision must be a full commit hash")
        require(isinstance(patch["file"], str)
                and patch["file"].endswith(".patch")
                and relative_pattern.fullmatch(patch["file"]) is not None,
                f"{patch['id']}: patch file must be a safe relative path")
        patch_path = (root / "patches" / patch["file"]).resolve()
        require((root / "patches").resolve() in patch_path.parents,
                f"{patch['id']}: patch path escapes patches directory")
        require(patch_path.is_file(), f"{patch['id']}: missing patch file")
        require(isinstance(patch["sha256"], str)
                and re.fullmatch(r"[0-9a-f]{64}", patch["sha256"]) is not None,
                f"{patch['id']}: patch digest must be SHA-256")
        actual_digest = hashlib.sha256(patch_path.read_bytes()).hexdigest()
        require(actual_digest == patch["sha256"],
                f"{patch['id']}: patch digest mismatch")
        patch_text = patch_path.read_text(encoding="utf-8")
        diff_pairs = re.findall(
            r"^diff --git a/(\S+) b/(\S+)$", patch_text, re.MULTILINE
        )
        require(diff_pairs and all(left == right for left, right in diff_pairs),
                f"{patch['id']}: patch must use explicit non-rename diff paths")
        actual_paths = sorted(left for left, _ in diff_pairs)
        declared_paths = patch["paths"]
        require(isinstance(declared_paths, list) and declared_paths
                and all(isinstance(path, str)
                        and relative_pattern.fullmatch(path) is not None
                        for path in declared_paths)
                and declared_paths == sorted(set(declared_paths))
                and declared_paths == actual_paths,
                f"{patch['id']}: declared footprint does not match patch diff paths")
        tests = patch["tests"]
        require(isinstance(tests, list) and tests
                and all(isinstance(path, str)
                        and relative_pattern.fullmatch(path) is not None
                        for path in tests)
                and tests == sorted(set(tests)),
                f"{patch['id']}: tests must be unique safe relative paths")
        for test in tests:
            test_path = (root / test).resolve()
            require(root.resolve() in test_path.parents and test_path.is_file(),
                    f"{patch['id']}: missing regression test {test}")
        for field in ("reason", "removal_condition", "rebase_notes"):
            value = patch[field]
            require(isinstance(value, str) and value == value.strip()
                    and len(value) >= 40,
                    f"{patch['id']}: {field} must be an actionable review note")


def validate_aosp_overlay(root: Path) -> None:
    required_files = [
        "Android.bp",
        "AndroidProducts.mk",
        ".github/workflows/aosp-upstream-watch.yml",
        "products/aios_common.mk",
        "products/aios_tegu.mk",
        "products/aios_cf_x86_64_phone.mk",
        "config/aosp_lanes.json",
        "tools/check_aosp_manifest.py",
        "tools/refresh_aosp_tracking.py",
        "tools/capture_build_evidence.py",
        "scripts/refresh-aosp-integration.sh",
        "scripts/capture-aosp-lock.sh",
        "scripts/build-aosp-lane.sh",
        "permissions/privapp-permissions-aios.xml",
        "apps/phone/Android.bp",
        "apps/phone/AndroidManifest.xml",
        "apps/phone/tests/src/com/aios/phone/intelligence/PendingAiAnswerGateTest.kt",
        "apps/phone/tests/src/com/aios/phone/intelligence/EmergencyProcessingGateTest.kt",
        "apps/phone/tests/src/com/aios/phone/model/AssistantCallContractTest.kt",
        "apps/phone/tests/src/com/aios/phone/model/CallRiskContractTest.kt",
        "apps/phone/tests/src/com/aios/phone/context/CallEventContractTest.kt",
        "apps/phone/src/com/aios/phone/PhoneRuntime.kt",
        "apps/phone/src/com/aios/phone/context/CallEventContract.kt",
        "apps/phone/src/com/aios/phone/context/CallEventContextClient.kt",
        "apps/phone/src/com/aios/phone/data/CallHistoryRepository.kt",
        "apps/phone/src/com/aios/phone/data/VoicemailRepository.kt",
        "apps/phone/src/com/aios/phone/model/PhoneContract.kt",
        "apps/phone/src/com/aios/phone/model/AssistantCallContract.kt",
        "apps/phone/src/com/aios/phone/model/CallRiskContract.kt",
        "apps/phone/src/com/aios/phone/telecom/CallRegistry.kt",
        "apps/phone/src/com/aios/phone/telecom/AiosInCallService.kt",
        "apps/phone/src/com/aios/phone/telecom/ProximityLockController.kt",
        "apps/phone/src/com/aios/phone/telecom/RttSessionController.kt",
        "apps/phone/src/com/aios/phone/telecom/VoicemailPlaybackController.kt",
        "apps/phone/src/com/aios/phone/notifications/CallNotificationCoordinator.kt",
        "apps/phone/src/com/aios/phone/notifications/CallActionReceiver.kt",
        "apps/phone/src/com/aios/phone/intelligence/CallAssistantClient.kt",
        "apps/phone/src/com/aios/phone/intelligence/EmergencyProcessingGate.kt",
        "apps/phone/src/com/aios/phone/intelligence/PendingAiAnswerGate.kt",
        "apps/phone/src/com/aios/phone/ui/InCallActivity.kt",
        "apps/phone/src/com/aios/phone/ui/screens/PhoneScreens.kt",
        "apps/phone/src/com/aios/phone/ui/theme/PhoneTheme.kt",
        "apps/messaging/Android.bp",
        "apps/messaging/AndroidManifest.xml",
        "apps/messaging/src/com/aios/messaging/AiosMessagingApplication.kt",
        "apps/messaging/src/com/aios/messaging/MessagingRuntime.kt",
        "apps/messaging/src/com/aios/messaging/model/MessagingContract.kt",
        "apps/messaging/src/com/aios/messaging/model/MessagePolicy.kt",
        "apps/messaging/src/com/aios/messaging/model/SubscriptionSelectionPolicy.kt",
        "apps/messaging/src/com/aios/messaging/data/MessagingRepository.kt",
        "apps/messaging/src/com/aios/messaging/context/CommunicationContextClient.kt",
        "apps/messaging/src/com/aios/messaging/context/MediaContextAssociationClient.kt",
        "apps/messaging/src/com/aios/messaging/context/MessageContextLedger.kt",
        "apps/messaging/src/com/aios/messaging/context/MessageContextLifecycleReceiver.kt",
        "apps/messaging/src/com/aios/messaging/context/MessageContextPolicy.kt",
        "apps/messaging/src/com/aios/messaging/context/MessageContextProvider.kt",
        "apps/messaging/src/com/aios/messaging/context/MessageContextReconcileJobService.kt",
        "apps/messaging/src/com/aios/messaging/telephony/SmsDeliverReceiver.kt",
        "apps/messaging/src/com/aios/messaging/telephony/MmsDeliverReceiver.kt",
        "apps/messaging/src/com/aios/messaging/telephony/MmsResultReceiver.kt",
        "apps/messaging/src/com/aios/messaging/telephony/RespondViaMessageService.kt",
        "apps/messaging/src/com/aios/messaging/mms/MmsOperationPolicy.kt",
        "apps/messaging/src/com/aios/messaging/mms/MmsPduProvider.kt",
        "apps/messaging/src/com/aios/messaging/mms/MmsTransport.kt",
        "apps/messaging/platform/src/com/aios/messaging/mms/platform/MmsOperationStore.kt",
        "apps/messaging/platform/src/com/aios/messaging/mms/platform/MmsPhotoTranscoder.kt",
        "apps/messaging/platform/src/com/aios/messaging/mms/platform/MmsTransportFactory.kt",
        "apps/messaging/platform/src/com/aios/messaging/mms/platform/PlatformMmsTransport.kt",
        "apps/messaging/src/com/aios/messaging/ui/MainActivity.kt",
        "apps/messaging/src/com/aios/messaging/ui/MessagingScreens.kt",
        "apps/messaging/src/com/aios/messaging/ui/theme/MessagingTheme.kt",
        "apps/messaging/tests/src/com/aios/messaging/model/MessagePolicyTest.kt",
        "apps/messaging/tests/src/com/aios/messaging/model/SubscriptionSelectionPolicyTest.kt",
        "apps/messaging/tests/src/com/aios/messaging/context/MessageContextPolicyTest.kt",
        "apps/messaging/tests/src/com/aios/messaging/mms/MmsOperationPolicyTest.kt",
        "patches/0002-framework-mms-aios-visibility.patch",
        "docs/mms-transport.md",
        "preview/messagingcheck/build.gradle.kts",
        "preview/callcontextcheck/build.gradle.kts",
        "preview/callcontextcheck/src/main/AndroidManifest.xml",
        "preview/mediascancheck/build.gradle.kts",
        "preview/mediascancheck/src/main/AndroidManifest.xml",
        "services/contextintelligence/Android.bp",
        "services/contextintelligence/AndroidManifest.xml",
        "services/contextintelligence/aidl/com/aios/context/ICommunicationContext.aidl",
        "services/contextintelligence/aidl/com/aios/context/ConversationIdentity.aidl",
        "services/contextintelligence/aidl/com/aios/context/ContextDocument.aidl",
        "services/contextintelligence/aidl/com/aios/context/ContextSnippet.aidl",
        "services/contextintelligence/api/com/aios/context/ConversationIdentity.java",
        "services/contextintelligence/api/com/aios/context/ContextDocument.java",
        "services/contextintelligence/api/com/aios/context/ContextSnippet.java",
        "services/contextintelligence/src/com/aios/contextintelligence/CommunicationContextService.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextStore.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextPolicy.java",
        "services/contextintelligence/src/com/aios/contextintelligence/RevisionGate.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/ContextPolicyTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/RevisionGateTest.java",
        "docs/communications-context.md",
        "docs/compose-dialer-decision.md",
        "preview/README.md",
        "preview/prodcheck/build.gradle.kts",
        "preview/telecomsmoke/build.gradle.kts",
        "preview/telecomsmoke/src/debug/AndroidManifest.xml",
        "preview/telecomsmoke/src/debug/kotlin/com/aios/phone/smoke/EmulatorCallActivity.kt",
        "preview/telecomsmoke/src/debug/kotlin/com/aios/phone/smoke/EmulatorConnectionService.kt",
        "scripts/emulator-telecom-smoke.ps1",
        "services/modelbroker/Android.bp",
        "services/modelbroker/AndroidManifest.xml",
        "services/modelbroker/aidl/com/aios/model/IAiosModelService.aidl",
        "services/modelbroker/src/com/aios/modelbroker/ModelBrokerService.java",
        "services/modelbroker/src/com/aios/modelbroker/ArtifactVerifier.java",
        "services/modelbroker/src/com/aios/modelbroker/AuthorizedClientPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/CatalogPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/DeviceModelAdmission.java",
        "services/modelbroker/src/com/aios/modelbroker/BrokerState.java",
        "services/modelbroker/src/com/aios/modelbroker/RuntimeAdapter.java",
        "services/modelbroker/src/com/aios/modelbroker/RemoteRuntimeAdapter.java",
        "services/modelbroker/src/com/aios/modelbroker/RuntimeRegistry.java",
        "services/modelbroker/src/com/aios/modelbroker/SessionController.java",
        "services/modelbroker/src/com/aios/modelbroker/SessionArbiter.java",
        "services/modelbroker/src/com/aios/modelbroker/CallActivityLeaseTracker.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/SessionArbiterTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/CallActivityLeaseTrackerTest.java",
        "tools/generate_model_pack.py",
        "tools/generate_model_admission.py",
        "tools/generate_runtime_pack.py",
        "docs/model-packaging.md",
        "docs/model-admission.md",
        "evidence/model-admission/README.md",
        "docs/runtime-packaging.md",
        "config/runtime_catalog.json",
        "config/model_admission.json",
        "services/runtimeapi/Android.bp",
        "services/runtimeapi/aidl/com/aios/runtime/IAiosRuntimeProvider.aidl",
        "services/runtimeapi/aidl/com/aios/runtime/RuntimeArtifact.aidl",
        "runtime/litertlmprovider/settings.gradle.kts",
        "runtime/litertlmprovider/build.gradle.kts",
        "runtime/litertlmprovider/app/build.gradle.kts",
        "runtime/litertlmprovider/app/gradle.lockfile",
        "runtime/litertlmprovider/gradle/verification-metadata.xml",
        "runtime/litertlmprovider/app/src/main/AndroidManifest.xml",
        "runtime/litertlmprovider/app/src/main/java/com/aios/runtime/litertlm/LiteRtLmRuntimeService.kt",
        "runtime/litertlmprovider/bootstrap_dependency_locks.sh",
        "runtime/litertlmprovider/build_provider.sh",
        "runtime/whisperprovider/settings.gradle.kts",
        "runtime/whisperprovider/build.gradle.kts",
        "runtime/whisperprovider/app/build.gradle.kts",
        "runtime/whisperprovider/app/src/main/AndroidManifest.xml",
        "runtime/whisperprovider/app/src/main/cpp/CMakeLists.txt",
        "runtime/whisperprovider/app/src/main/cpp/aios_whisper_jni.cpp",
        "runtime/whisperprovider/app/src/main/java/com/aios/runtime/whispercpp/NativeWhisper.kt",
        "runtime/whisperprovider/app/src/main/java/com/aios/runtime/whispercpp/WhisperRuntimeService.kt",
        "runtime/whisperprovider/bootstrap_source.sh",
        "runtime/whisperprovider/bootstrap_dependency_locks.sh",
        "runtime/whisperprovider/build_provider.sh",
        "docs/asr-runtime.md",
        "runtime/ttsprovider/settings.gradle.kts",
        "runtime/ttsprovider/build.gradle.kts",
        "runtime/ttsprovider/app/build.gradle.kts",
        "runtime/ttsprovider/app/src/main/AndroidManifest.xml",
        "runtime/ttsprovider/app/src/main/java/com/aios/runtime/sherpatts/SherpaTtsRuntimeService.kt",
        "runtime/ttsprovider/bootstrap_artifacts.sh",
        "runtime/ttsprovider/bootstrap_dependency_locks.sh",
        "runtime/ttsprovider/build_provider.sh",
        "docs/tts-runtime.md",
        "benchmarks/modeladmission/Android.bp",
        "benchmarks/modeladmission/README.md",
        "benchmarks/modeladmission/app/AndroidManifest.xml",
        "benchmarks/modeladmission/common/com/aios/modelbenchmark/BenchmarkMath.java",
        "benchmarks/modeladmission/tests/AndroidManifest.xml",
        "benchmarks/modeladmission/tests/src/com/aios/modelbenchmark/ModelAdmissionBenchmarkTest.java",
        "scripts/capture-model-benchmark.ps1",
        "scripts/capture-media-timing.ps1",
        "services/callintelligence/AndroidManifest.xml",
        "services/callintelligence/aidl/com/aios/call/IAiosCallIntelligence.aidl",
        "services/callintelligence/aidl/com/aios/call/ICallIntelligenceListener.aidl",
        "services/callintelligence/aidl/com/aios/call/CallAssistantState.aidl",
        "services/callintelligence/aidl/com/aios/call/CallRiskAssessment.aidl",
        "services/callintelligence/aidl/com/aios/call/CallAssistantPolicy.aidl",
        "services/callintelligence/aidl/com/aios/call/IncomingCallContext.aidl",
        "services/callintelligence/src/com/aios/callintelligence/AnswerDelayPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/AssistantHandlingTracker.java",
        "services/callintelligence/src/com/aios/callintelligence/AssistantTurnQueue.java",
        "services/callintelligence/src/com/aios/callintelligence/CallPolicyEngine.java",
        "services/callintelligence/src/com/aios/callintelligence/CallCommunicationContextClient.java",
        "services/callintelligence/src/com/aios/callintelligence/CallContextAccumulator.java",
        "services/callintelligence/src/com/aios/callintelligence/CallArtifactRetention.java",
        "services/callintelligence/src/com/aios/callintelligence/CallArtifactStore.java",
        "services/callintelligence/src/com/aios/callintelligence/TelephonyAudioCapture.java",
        "services/callintelligence/src/com/aios/callintelligence/RequiredCaptureGate.java",
        "services/callintelligence/src/com/aios/callintelligence/CallerAudioUplink.java",
        "services/callintelligence/src/com/aios/callintelligence/Pcm16MonoToStereo48k.java",
        "services/callintelligence/src/com/aios/callintelligence/PriorContextFormatter.java",
        "services/callintelligence/src/com/aios/callintelligence/SpeechSynthesisBrokerClient.java",
        "services/callintelligence/src/com/aios/callintelligence/AsrBrokerClient.java",
        "services/callintelligence/src/com/aios/callintelligence/SpamRiskEngine.java",
        "services/callintelligence/src/com/aios/callintelligence/RiskAssessmentTracker.java",
        "services/callintelligence/src/com/aios/callintelligence/CallClassifierClient.java",
        "services/callintelligence/src/com/aios/callintelligence/ReceptionistDialogueClient.java",
        "services/callintelligence/src/com/aios/callintelligence/ReceptionistReplyPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/TelecomCallPresenceTracker.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/SpamRiskEngineTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/AssistantHandlingTrackerTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/RiskAssessmentTrackerTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/AssistantTurnQueueTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/ReceptionistReplyPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/AnswerDelayPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallArtifactRetentionTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallContextAccumulatorTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/Pcm16MonoToStereo48kTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/RequiredCaptureGateTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/PriorContextFormatterTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/TelecomCallPresenceTrackerTest.java",
        "services/callintelligence/src/com/aios/callintelligence/ResilientFanoutOutputStream.java",
        "docs/dialer-integration.md",
        "docs/caller-audio-uplink.md",
        "services/mediaintelligence/AndroidManifest.xml",
        "services/mediaintelligence/aidl/com/aios/media/IMediaContextAssociation.aidl",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaAssociationPolicy.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaContextAssociationService.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaContextProjection.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaObserverService.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaGenerationReconciler.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaGenerationScanner.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaLivenessReconciler.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaLivenessScanner.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaInferenceJobService.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaWorkPolicy.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaConstraintProbe.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaInputPolicy.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoStoryboardPlan.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoStoryboard.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoAudioExtractor.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoTranscript.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoEmbeddedMetadata.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoEnhancedCopyMuxer.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoEnhancedCopyActivity.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoEnhancedCopyService.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoExportRecovery.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/VideoExportRecoveryPolicy.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/XmpProjection.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaBrokerClient.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaContent.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaResult.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaJobStore.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaTiming.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaTimingSummary.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/JpegXmpInjector.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/PngXmpInjector.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaMetadataCommitter.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/JpegXmpInjectorTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/PngXmpInjectorTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaWorkPolicyTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaInputPolicyTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/VideoStoryboardPlanTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/VideoTranscriptTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/VideoEmbeddedMetadataTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/VideoExportRecoveryPolicyTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaTimingTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaGenerationReconcilerTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaLivenessReconcilerTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaAssociationPolicyTest.java",
        "permissions/default-permissions-aios.xml",
        "docs/media-metadata-schema.md",
        "docs/media-performance.md",
    ]
    for relative in required_files:
        require((root / relative).is_file(), f"missing AOSP overlay file: {relative}")

    tegu_product = (root / "products" / "aios_tegu.mk").read_text(encoding="utf-8")
    require("device/google/tegu/aosp_tegu.mk" in tegu_product,
            "Pixel 9a product must inherit upstream aosp_tegu")
    require("vendor/aios/products/aios_common.mk" in tegu_product,
            "Pixel 9a product must inherit common AIOS additions")
    cuttlefish_product = (root / "products" /
                          "aios_cf_x86_64_phone.mk").read_text(encoding="utf-8")
    require("device/google/cuttlefish/vsoc_x86_64/phone/aosp_cf.mk"
            in cuttlefish_product
            and "vendor/aios/products/aios_common.mk" in cuttlefish_product
            and "PRODUCT_NAME := aios_cf_x86_64_phone" in cuttlefish_product,
            "Android-latest must have an additive Cuttlefish integration product")
    android_products = (root / "AndroidProducts.mk").read_text(encoding="utf-8")
    require("aios_tegu-aosp_current-userdebug" in android_products
            and "aios_cf_x86_64_phone-aosp_current-userdebug" in android_products,
            "AIOS must expose separate Pixel hardware and latest-AOSP integration targets")
    lock_script = (root / "scripts" / "capture-aosp-lock.sh").read_text(
        encoding="utf-8"
    )
    require("repo manifest -r" in lock_script
            and "check_aosp_manifest.py" in lock_script
            and "refresh_aosp_tracking.py" in lock_script
            and "--manifest-revision" in lock_script
            and "status --porcelain --untracked-files=all" in lock_script
            and "Refusing to overwrite" in lock_script,
            "AOSP locks must be resolved, clean, lane-checked, and non-overwriting")
    refresh_script = (root / "scripts" /
                      "refresh-aosp-integration.sh").read_text(encoding="utf-8")
    require("repo init" in refresh_script
            and "android-latest-release" in refresh_script
            and "refresh_aosp_tracking.py" in refresh_script
            and "--write" in refresh_script
            and "status --porcelain --untracked-files=all" in refresh_script,
            "AOSP refresh must be explicit, clean, official, and reviewable")
    watch_workflow = (root / ".github" / "workflows" /
                      "aosp-upstream-watch.yml").read_text(encoding="utf-8")
    require("schedule:" in watch_workflow
            and "workflow_dispatch:" in watch_workflow
            and "contents: read" in watch_workflow
            and OFFICIAL_AOSP_MANIFEST_URL in watch_workflow
            and "android-latest-release" in watch_workflow
            and "refresh_aosp_tracking.py" in watch_workflow
            and "--check" in watch_workflow,
            "AOSP upstream watch must be scheduled, read-only, and official")
    patch_tool = (root / "tools" / "verify_patch_series.py").read_text(
        encoding="utf-8"
    )
    require("def apply_series(" in patch_tool
            and "def revert_series(" in patch_tool
            and "def load_series(" in patch_tool
            and "declared footprint does not match patch diff paths" in patch_tool
            and "--show-toplevel" in patch_tool
            and '"--index"' in patch_tool
            and "refusing to patch dirty tracked checkout" in patch_tool,
            "AOSP topics must be review-complete, footprint-locked transactions")
    build_script = (root / "scripts" / "build-aosp-lane.sh").read_text(
        encoding="utf-8"
    )
    require("capture-aosp-lock.sh" in build_script
            and "--apply" in build_script
            and "--revert" in build_script
            and "trap cleanup EXIT INT TERM" in build_script
            and "capture_build_evidence.py" in build_script
            and 'build_status="${PIPESTATUS[0]}"' in build_script,
            "lane builds must be locked, patch-transactional, logged, and evidence-bound")
    build_evidence_source = (root / "tools" /
                             "capture_build_evidence.py").read_text(encoding="utf-8")
    require("installed-files-product.json" in build_evidence_source
            and "require_manifest_membership" in build_evidence_source
            and "installed_files_product_sha256" in build_evidence_source
            and "patch_queue_record" in build_evidence_source
            and "patch_queue_sha256" in build_evidence_source
            and '"schema_version": 2' in build_evidence_source
            and "empty installed product artifact" in build_evidence_source,
            "build evidence must bind product artifacts and the review-complete patch queue")

    common_product = (root / "products" / "aios_common.mk").read_text(
        encoding="utf-8"
    )
    require("AiosPhone" in common_product and "AiosPhoneAssistant" not in common_product,
            "the product must include the full AIOS Phone module")
    require("AiosMessaging" in common_product
            and "AiosContextIntelligence" in common_product,
            "the product must include first-party messaging and communication context")
    require("aios_model_admission" in common_product
            and "ro.aios.model_admission=/product/etc/aios/model_admission.json"
            in common_product,
            "the product must install the fail-closed device model-admission policy")
    debug_packages = common_product.partition("PRODUCT_PACKAGES_DEBUG +=")[2]
    production_packages = common_product.partition("PRODUCT_PACKAGES_DEBUG +=")[0]
    require("AiosModelBenchmark" in debug_packages
            and "AiosModelBenchmarkTests" in debug_packages
            and "AiosModelBenchmark" not in production_packages,
            "model benchmarks must be installed only on eng/userdebug images")
    benchmark_manifest = (root / "benchmarks" / "modeladmission" / "app" /
                          "AndroidManifest.xml").read_text(encoding="utf-8")
    benchmark_source = (root / "benchmarks" / "modeladmission" / "tests" / "src" /
                        "com" / "aios" / "modelbenchmark" /
                        "ModelAdmissionBenchmarkTest.java").read_text(encoding="utf-8")
    require('android:testOnly="true"' in benchmark_manifest
            and '"userdebug".equals(Build.TYPE)' in benchmark_source
            and "telecom.isInCall()" in benchmark_source
            and ".setCallActive(" not in benchmark_source
            and "IAiosModelService" in benchmark_source
            and '"video_understanding"' in benchmark_source
            and '"p95_image_latency_ms"' in benchmark_source
            and '"first_image_latency_ms"' in benchmark_source
            and '"p50_warm_image_latency_ms"' in benchmark_source
            and '"p95_video_storyboard_inference_ms"' in benchmark_source
            and "aios_measurements_base64" in benchmark_source,
            "model benchmark must be test-only, call-safe, and measure image/video Broker paths")
    benchmark_capture = (root / "scripts" /
                         "capture-model-benchmark.ps1").read_text(encoding="utf-8")
    require('"config\\model_benchmark_suite.json"' in benchmark_capture
            and "$measurementDocument.suite_version -ne $suite.suite_version"
            in benchmark_capture
            and "$measurementDocument.suite_version -ne 1" not in benchmark_capture,
            "device benchmark capture must follow the checked-in suite version")
    overlay_text = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for directory in (root / "products", root / "overlays")
        if directory.exists()
        for path in directory.rglob("*")
        if path.is_file()
    )
    require("config_defaultDialer" not in overlay_text
            and "com.aios.phone" not in overlay_text,
            "AIOS Phone must not replace the system/emergency dialer before gates pass")

    phone_manifest = (root / "apps" / "phone" / "AndroidManifest.xml").read_text(
        encoding="utf-8"
    )
    phone_build = (root / "apps" / "phone" / "Android.bp").read_text(
        encoding="utf-8"
    )
    require('android:name="android.telecom.IN_CALL_SERVICE_UI"' in phone_manifest
            and 'android:name="android.telecom.IN_CALL_SERVICE_RINGING"' in phone_manifest
            and 'android:permission="android.permission.BIND_INCALL_SERVICE"'
            in phone_manifest,
            "AIOS Phone must fully declare its InCallService UI and ringing role")
    require(phone_manifest.count('android.intent.action.DIAL') == 2
            and 'android:scheme="tel"' in phone_manifest,
            "AIOS Phone must handle ACTION_DIAL with and without a tel URI")

    messaging_root = root / "apps" / "messaging"
    messaging_manifest = (messaging_root / "AndroidManifest.xml").read_text(
        encoding="utf-8"
    )
    messaging_runtime = (messaging_root / "src" / "com" / "aios" / "messaging" /
                         "MessagingRuntime.kt").read_text(encoding="utf-8")
    messaging_repository = (messaging_root / "src" / "com" / "aios" / "messaging" /
                            "data" / "MessagingRepository.kt").read_text(encoding="utf-8")
    messaging_activity = (messaging_root / "src" / "com" / "aios" / "messaging" /
                          "ui" / "MainActivity.kt").read_text(encoding="utf-8")
    messaging_ui = (messaging_root / "src" / "com" / "aios" / "messaging" /
                    "ui" / "MessagingScreens.kt").read_text(encoding="utf-8")
    messaging_contract = (messaging_root / "src" / "com" / "aios" / "messaging" /
                          "model" / "MessagingContract.kt").read_text(encoding="utf-8")
    subscription_policy = (messaging_root / "src" / "com" / "aios" / "messaging" /
                           "model" / "SubscriptionSelectionPolicy.kt").read_text(
                               encoding="utf-8")
    sms_receiver = (messaging_root / "src" / "com" / "aios" / "messaging" /
                    "telephony" / "SmsDeliverReceiver.kt").read_text(encoding="utf-8")
    mms_receiver = (messaging_root / "src" / "com" / "aios" / "messaging" /
                    "telephony" / "MmsDeliverReceiver.kt").read_text(encoding="utf-8")
    mms_platform = messaging_root / "platform" / "src" / "com" / "aios" / "messaging"
    mms_transport = (mms_platform /
                     "mms" / "platform" / "PlatformMmsTransport.kt").read_text(
                         encoding="utf-8")
    mms_store = (mms_platform /
                 "mms" / "platform" / "MmsOperationStore.kt").read_text(
                     encoding="utf-8")
    mms_policy = (messaging_root / "src" / "com" / "aios" / "messaging" /
                  "mms" / "MmsOperationPolicy.kt").read_text(encoding="utf-8")
    mms_contract = (messaging_root / "src" / "com" / "aios" / "messaging" /
                    "mms" / "MmsTransport.kt").read_text(encoding="utf-8")
    messaging_bp = (messaging_root / "Android.bp").read_text(encoding="utf-8")
    context_client = (messaging_root / "src" / "com" / "aios" / "messaging" /
                       "context" / "CommunicationContextClient.kt").read_text(
                           encoding="utf-8")
    media_context_client = (messaging_root / "src" / "com" / "aios" / "messaging" /
                            "context" / "MediaContextAssociationClient.kt").read_text(
                                encoding="utf-8")
    message_context_root = (messaging_root / "src" / "com" / "aios" / "messaging" /
                            "context")
    message_context_ledger = (message_context_root / "MessageContextLedger.kt").read_text(
        encoding="utf-8")
    message_context_provider = (message_context_root / "MessageContextProvider.kt").read_text(
        encoding="utf-8")
    message_context_job = (message_context_root /
                           "MessageContextReconcileJobService.kt").read_text(
                               encoding="utf-8")
    require('android.intent.action.SENDTO' in messaging_manifest
            and all(f'android:scheme="{scheme}"' in messaging_manifest
                    for scheme in ("sms", "smsto", "mms", "mmsto"))
            and 'android.intent.action.RESPOND_VIA_MESSAGE' in messaging_manifest
            and 'android.provider.Telephony.SMS_DELIVER' in messaging_manifest
            and 'android.permission.BROADCAST_SMS' in messaging_manifest
            and 'android.provider.Telephony.WAP_PUSH_DELIVER' in messaging_manifest
            and 'android.permission.BROADCAST_WAP_PUSH' in messaging_manifest,
            "AIOS Messaging must satisfy every Android SMS-role component")
    require("RoleManager.ROLE_SMS" in messaging_runtime
            and "Telephony.Sms.Inbox.CONTENT_URI" in messaging_repository
            and "Telephony.Sms.Sent.CONTENT_URI" in messaging_repository
            and "sendMultipartTextMessage" in messaging_repository
            and "pending.finish()" in sms_receiver,
            "SMS delivery must be role-gated, provider-backed, multipart, and durable")
    require('android.permission.READ_PHONE_STATE' in messaging_manifest
            and "activeSubscriptionInfoList" in messaging_repository
            and "createForSubscriptionId(subscriptionId)" in messaging_repository
            and "SubscriptionSelectionPolicy.select" in messaging_runtime
            and "SelectSubscription" in messaging_contract
            and "SubscriptionPicker" in messaging_ui
            and "preferredSubscriptionId" in subscription_policy
            and "defaultSubscriptionId" in subscription_policy,
            "AIOS Messaging must fail closed and expose explicit multi-SIM routing")
    require("PickVisualMedia" in messaging_activity
            and "ImageOnly" in messaging_activity
            and "Intent.ACTION_DIAL" in messaging_activity
            and "Build.TYPE != \"user\"" in mms_transport
            and "mmsTransport.admitted" in messaging_runtime
            and "debuggable AIOS builds until carrier gates pass" in mms_transport
            and "RESULT_ERROR_GENERIC_FAILURE" in mms_receiver,
            "photo drafts must use the picker and release MMS must fail closed")
    require(":framework-mms-shared-srcs" in messaging_bp
            and "PduPersister" in mms_transport
            and "sendMultimediaMessage" in mms_transport
            and "downloadMultimediaMessage" in mms_transport
            and "MMS_SENT" in mms_contract
            and "MMS_DOWNLOADED" in mms_contract
            and "requireActiveSubscription(requestedSubscriptionId)" in mms_transport
            and "CREATE TABLE operations" in mms_store
            and all(state in mms_policy for state in (
                "PREPARING", "PROVIDER_PERSISTED", "SUBMITTED",
                "SUCCEEDED", "FAILED"))
            and "pending.finish()" in mms_receiver,
            "MMS must use AOSP PDU persistence and a durable carrier callback lifecycle")
    require("associationToken" in messaging_runtime
            and "association_token TEXT NOT NULL" in mms_store
            and "completion_reported INTEGER NOT NULL" in mms_store
            and "unreportedSuccessful" in mms_store
            and "markMediaAssociationReported" in mms_store
            and "acknowledgeMediaAssociation" in mms_contract
            and "acknowledgeMediaAssociation" in mms_transport
            and "acknowledgeMediaAssociation" in messaging_runtime
            and "stageMmsPhoto" in media_context_client
            and "completeMmsPhoto" in media_context_client
            and "onDurablyRecorded" in media_context_client
            and "deleteMmsPhoto" in media_context_client
            and "clearMmsPhotos" in media_context_client,
            "selected MMS photos must retain a crash-recoverable media-context lifecycle")
    require("indexSms" in context_client
            and "indexMms" in context_client
            and "deleteMms" in context_client
            and "deleteSource" in context_client
            and "queryRecent" in context_client,
            "messaging must index, retrieve, and tombstone SMS/MMS context")
    require("registerContentObserver" in context_client
            and "provider.page" in context_client
            and "provider::highWatermark" in context_client
            and "staleBatch" in context_client
            and "pendingMutations" in context_client
            and "queuedMutations" in context_client
            and "ledger.nextRevision(watermark)" in context_client
            and "service.getStoreInstanceId()" in context_client
            and "setProviderReconciliationEnabled(held)" in messaging_runtime
            and "CREATE TABLE ledger" in message_context_ledger
            and "fingerprint TEXT NOT NULL" in message_context_ledger
            and "address TEXT" not in message_context_ledger
            and "body TEXT" not in message_context_ledger
            and "LIMIT $limit" in message_context_provider
            and "MESSAGE_BOX_FAILED" not in message_context_provider
            and ".setPersisted(true)" in message_context_job
            and "NETWORK_TYPE_NONE" in message_context_job
            and "DEFAULT_SMS_PACKAGE_CHANGED" in messaging_manifest
            and "android.permission.RECEIVE_BOOT_COMPLETED" in messaging_manifest
            and "android.permission.BIND_JOB_SERVICE" in messaging_manifest,
            "Messaging context must durably reconcile bounded authoritative provider pages")

    context_root = root / "services" / "contextintelligence"
    context_manifest = (context_root / "AndroidManifest.xml").read_text(encoding="utf-8")
    context_service = (context_root / "src" / "com" / "aios" /
                       "contextintelligence" /
                       "CommunicationContextService.java").read_text(encoding="utf-8")
    context_store = (context_root / "src" / "com" / "aios" /
                     "contextintelligence" / "ContextStore.java").read_text(
                         encoding="utf-8")
    context_policy = (context_root / "src" / "com" / "aios" /
                      "contextintelligence" / "ContextPolicy.java").read_text(
                          encoding="utf-8")
    context_aidl = (context_root / "aidl" / "com" / "aios" / "context" /
                    "ICommunicationContext.aidl").read_text(encoding="utf-8")
    require('protectionLevel="signature|privileged"' in context_manifest
            and 'android.permission.READ_CONTACTS' in context_manifest
            and "HmacSHA256" in context_service
            and "PhoneNumberUtils.formatNumberToE164" in context_service
            and "contactNumbers" in context_service
            and "related.toArray" in context_service,
            "communication identities must be opaque and re-resolve current contact members")
    require("CREATE VIRTUAL TABLE entries_fts USING fts4" in context_store
            and "CREATE TABLE tombstones" in context_store
            and "CREATE TABLE source_delete_watermarks" in context_store
            and "usesSourceDeleteWatermark" in context_store
            and "ContextPolicy.CALL_EVENT.equals(sourceType)" in context_store
            and "ContextPolicy.SMS.equals(sourceType)" in context_store
            and "ContextPolicy.MMS.equals(sourceType)" in context_store
            and "ContextPolicy.MEDIA_METADATA.equals(sourceType)" in context_store
            and "deleteSourceType" in context_store
            and "deleteSourceType" in context_service
            and "long deleteSourceType" in context_aidl
            and "getStoreInstanceId" in context_service
            and "getStoreInstanceId" in context_aidl
            and "RevisionGate.accepts" in context_store
            and "identity.relatedConversationKeys" in context_store
            and "MAX_QUERY_RESULTS = 8" in context_policy
            and "MAX_SNIPPET_CHARS = 512" in context_policy
            and "CALL_ARTIFACT_TTL_MILLIS" in context_policy
            and "call artifacts must expire within 24 hours" in context_policy,
            "communication retrieval must be bounded, revisioned, and retention-aware")
    require("rawAddress" not in context_store
            and "phone_number" not in context_store
            and "contact_lookup" not in context_store,
            "communication index must not store raw phone or contact identifiers")
    context_client_manifests = (
        phone_manifest,
        messaging_manifest,
        (root / "services" / "callintelligence" / "AndroidManifest.xml").read_text(
            encoding="utf-8"),
        (root / "services" / "mediaintelligence" / "AndroidManifest.xml").read_text(
            encoding="utf-8"),
    )
    require(all("com.aios.permission.USE_COMMUNICATION_CONTEXT" in manifest
                for manifest in context_client_manifests),
            "every declared communication-context client must request its signature permission")

    phone_contract = (root / "apps" / "phone" / "src" / "com" / "aios" /
                      "phone" / "model" / "PhoneContract.kt").read_text(
                          encoding="utf-8")
    call_risk_contract = (root / "apps" / "phone" / "src" / "com" / "aios" /
                          "phone" / "model" / "CallRiskContract.kt").read_text(
                              encoding="utf-8")
    assistant_call_contract = (root / "apps" / "phone" / "src" / "com" / "aios" /
                               "phone" / "model" /
                               "AssistantCallContract.kt").read_text(encoding="utf-8")
    phone_registry = (root / "apps" / "phone" / "src" / "com" / "aios" /
                      "phone" / "telecom" / "CallRegistry.kt").read_text(
                          encoding="utf-8")
    phone_runtime = (root / "apps" / "phone" / "src" / "com" / "aios" /
                     "phone" / "PhoneRuntime.kt").read_text(encoding="utf-8")
    in_call_service = (root / "apps" / "phone" / "src" / "com" / "aios" /
                       "phone" / "telecom" / "AiosInCallService.kt").read_text(
                           encoding="utf-8")
    theme_source = (root / "apps" / "phone" / "src" / "com" / "aios" /
                    "phone" / "ui" / "theme" / "PhoneTheme.kt").read_text(
                        encoding="utf-8")
    assistant_client = (root / "apps" / "phone" / "src" / "com" / "aios" /
                        "phone" / "intelligence" /
                        "CallAssistantClient.kt").read_text(encoding="utf-8")
    pending_answer_gate = (root / "apps" / "phone" / "src" / "com" / "aios" /
                           "phone" / "intelligence" /
                           "PendingAiAnswerGate.kt").read_text(encoding="utf-8")
    emergency_processing_gate = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "intelligence" / "EmergencyProcessingGate.kt"
    ).read_text(encoding="utf-8")
    call_event_contract = (root / "apps" / "phone" / "src" / "com" / "aios" /
                           "phone" / "context" /
                           "CallEventContract.kt").read_text(encoding="utf-8")
    call_event_client = (root / "apps" / "phone" / "src" / "com" / "aios" /
                         "phone" / "context" /
                         "CallEventContextClient.kt").read_text(encoding="utf-8")
    call_event_test = (root / "apps" / "phone" / "tests" / "src" / "com" /
                       "aios" / "phone" / "context" /
                       "CallEventContractTest.kt").read_text(encoding="utf-8")
    pending_answer_test = (root / "apps" / "phone" / "tests" / "src" / "com" /
                           "aios" / "phone" / "intelligence" /
                           "PendingAiAnswerGateTest.kt").read_text(encoding="utf-8")
    emergency_processing_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "intelligence" / "EmergencyProcessingGateTest.kt"
    ).read_text(encoding="utf-8")
    call_risk_test = (root / "apps" / "phone" / "tests" / "src" / "com" /
                      "aios" / "phone" / "model" /
                      "CallRiskContractTest.kt").read_text(encoding="utf-8")
    assistant_call_test = (root / "apps" / "phone" / "tests" / "src" / "com" /
                           "aios" / "phone" / "model" /
                           "AssistantCallContractTest.kt").read_text(encoding="utf-8")
    in_call_activity = (root / "apps" / "phone" / "src" / "com" / "aios" /
                        "phone" / "ui" / "InCallActivity.kt").read_text(
                            encoding="utf-8")
    notification_source = (root / "apps" / "phone" / "src" / "com" / "aios" /
                           "phone" / "notifications" /
                           "CallNotificationCoordinator.kt").read_text(encoding="utf-8")
    notification_receiver = (root / "apps" / "phone" / "src" / "com" / "aios" /
                             "phone" / "notifications" /
                             "CallActionReceiver.kt").read_text(encoding="utf-8")
    proximity_source = (root / "apps" / "phone" / "src" / "com" / "aios" /
                        "phone" / "telecom" /
                        "ProximityLockController.kt").read_text(encoding="utf-8")
    history_source = (root / "apps" / "phone" / "src" / "com" / "aios" /
                      "phone" / "data" /
                      "CallHistoryRepository.kt").read_text(encoding="utf-8")
    rtt_source = (root / "apps" / "phone" / "src" / "com" / "aios" /
                  "phone" / "telecom" /
                  "RttSessionController.kt").read_text(encoding="utf-8")
    voicemail_source = (root / "apps" / "phone" / "src" / "com" / "aios" /
                        "phone" / "data" /
                        "VoicemailRepository.kt").read_text(encoding="utf-8")
    voicemail_player = (root / "apps" / "phone" / "src" / "com" / "aios" /
                        "phone" / "telecom" /
                        "VoicemailPlaybackController.kt").read_text(encoding="utf-8")
    phone_screens = (root / "apps" / "phone" / "src" / "com" / "aios" /
                     "phone" / "ui" / "screens" /
                     "PhoneScreens.kt").read_text(encoding="utf-8")
    smoke_build = (root / "preview" / "telecomsmoke" /
                   "build.gradle.kts").read_text(encoding="utf-8")
    prodcheck_build = (root / "preview" / "prodcheck" /
                       "build.gradle.kts").read_text(encoding="utf-8")
    smoke_manifest = (root / "preview" / "telecomsmoke" / "src" / "debug" /
                      "AndroidManifest.xml").read_text(encoding="utf-8")
    smoke_activity = (root / "preview" / "telecomsmoke" / "src" / "debug" /
                      "kotlin" / "com" / "aios" / "phone" / "smoke" /
                      "EmulatorCallActivity.kt").read_text(encoding="utf-8")
    smoke_script = (root / "scripts" /
                    "emulator-telecom-smoke.ps1").read_text(encoding="utf-8")
    require("data class PhoneUiState" in phone_contract
            and "sealed interface PhoneAction" in phone_contract
            and "StateFlow<PhoneUiState>" in phone_runtime,
            "AIOS Phone must use immutable UDF state and typed actions")
    require("linkedMapOf<String, Call>()" in phone_registry
            and "IdentityHashMap<Call, String>()" in phone_registry
            and "conferenceableIds" in phone_registry
            and "var currentCall" not in phone_registry,
            "AIOS Phone must model all calls rather than one current-call singleton")
    require("onAvailableCallEndpointsChanged" in in_call_service
            and "requestCallEndpointChange" in in_call_service,
            "AIOS Phone must use modern audio endpoint callbacks")
    require("INCOMING_CHANNEL" in notification_source
            and "SILENT_INCOMING_CHANNEL" in notification_source
            and "ONGOING_CHANNEL" in notification_source
            and "USAGE_NOTIFICATION_RINGTONE" in notification_source
            and "onSilenceRinger" in in_call_service,
            "AIOS Phone must own distinct ringing, silenced, and ongoing call channels")
    require("PROXIMITY_SCREEN_OFF_WAKE_LOCK" in proximity_source
            and "TYPE_EARPIECE" in phone_runtime,
            "AIOS Phone must limit the proximity lock to active earpiece calls")
    require("onPostDialWait" in phone_registry
            and "postDialContinue" in phone_runtime,
            "AIOS Phone must model post-dial waits without exposing queued digits")
    require("PhoneAccountSuggestion" in phone_runtime
            and "phoneAccountSelected" in phone_runtime,
            "AIOS Phone must support explicit multi-SIM selection")
    require("playDtmfTone" in phone_runtime
            and "stopDtmfTone" in phone_runtime
            and "DTMF_PULSE_MILLIS" in phone_runtime,
            "AIOS Phone must bound DTMF tones and always stop them")
    require("CONTENT_URI_WITH_VOICEMAIL" in history_source
            and "CallLog.Calls.CONTENT_URI" in history_source
            and "MAX_ROWS = 50" in history_source
            and "NUMBER_PRESENTATION" in history_source,
            "AIOS Phone call history must be bounded, read-only, and presentation aware")
    require('"aios_context_api"' in phone_build
            and "CallEventContextClient(value)" in phone_runtime
            and "contextEvents.setEnabled(held)" in phone_runtime
            and "startWatchingMode(" in phone_runtime
            and "AppOpsManager.OPSTR_READ_CALL_LOG" in phone_runtime
            and "contextEvents.onCallLogMayHaveChanged()" in phone_runtime
            and "registerContentObserver" in call_event_client
            and "service.resolveIdentity(record.address, record.countryIso)"
            in call_event_client
            and "ContextDocument(" in call_event_client
            and 'SOURCE_CALL_EVENT = "call_event"' in call_event_client
            and "MAX_INDEXED_EVENTS = 256" in call_event_contract,
            "the dialer role must reconcile a bounded durable call-event context source")
    require("TelecomManager.PRESENTATION_ALLOWED" in call_event_client
            and "telephony.isEmergencyNumber(address)" in call_event_client
            and "CACHED_NAME" not in call_event_client
            and "address=<redacted>" in call_event_contract
            and "HmacSHA256" in call_event_contract
            and "FINGERPRINT_SECRET_BYTES = 32" in call_event_client,
            "call-event context must exclude hidden/emergency identities and protect its ledger")
    require("saveLedger(updated)" in call_event_client
            and "nextRevision()" in call_event_client
            and ".commit()" in call_event_client
            and "CallEventMutation.Delete" in call_event_client
            and "reconciliationDeletesMissingAndUpsertsOnlyChangedRows"
            in call_event_test
            and "reconciliationKeepsOnlyNewestBoundedSet" in call_event_test
            and "../../services/contextintelligence/aidl" in prodcheck_build
            and "../../services/contextintelligence/api" in prodcheck_build,
            "call-event changes and tombstones must be durable, monotonic, and compile checked")
    require("onCallDestroyed" in phone_registry
            and "InCallService.onCallRemoved is the canonical terminal event"
            in phone_registry,
            "AIOS Phone must retain opaque call IDs until terminal artifact cleanup")
    require("onRttStatusChanged" in phone_registry
            and "onRttRequest" in phone_registry
            and "sendRttRequest" in phone_runtime
            and "EXTRA_START_CALL_WITH_RTT" in phone_runtime
            and "readImmediately" in rtt_source
            and "MAX_RTT_TRANSCRIPT_CHARS" in phone_runtime,
            "AIOS Phone must support bounded incoming, outgoing, and mid-call RTT")
    require("sendSessionModifyRequest" in phone_registry
            and "sendSessionModifyResponse" in phone_registry
            and "setDisplaySurface" in phone_registry
            and "setPreviewSurface" in phone_registry
            and "SurfaceView" in phone_screens
            and "android.permission.CAMERA" in phone_manifest,
            "AIOS Phone must negotiate and render video calls with explicit camera access")
    require("VoicemailContract.Voicemails.CONTENT_URI" in voicemail_source
            and "IS_OMTP_VOICEMAIL" in voicemail_source
            and "visualVoicemailPackageName" in voicemail_source
            and "MAX_ROWS = 50" in voicemail_source
            and "setDataSource(context, uri)" in voicemail_player
            and "ACTION_FETCH_VOICEMAIL" in voicemail_source
            and "com.android.voicemail.permission.READ_VOICEMAIL" in phone_manifest
            and "com.android.voicemail.permission.WRITE_VOICEMAIL" in phone_manifest,
            "AIOS Phone must provide bounded, OMTP-aware, provider-streamed voicemail")
    require("ThemePreference.SYSTEM" in theme_source
            and "ThemePreference.LIGHT" in theme_source
            and "ThemePreference.DARK" in theme_source,
            "AIOS Phone must support system, light, and dark themes")
    require("data class MessageNumber" in phone_contract
            and "Intent.ACTION_SENDTO" in phone_runtime
            and '"smsto"' in phone_runtime
            and "PhoneAction.MessageNumber" in phone_screens,
            "AIOS Phone recents must open the user-selected messaging app")
    require('getByName("debug")' in smoke_build
            and 'src/debug/AndroidManifest.xml' in smoke_build
            and "android.permission.MANAGE_OWN_CALLS" in smoke_manifest
            and "BIND_TELECOM_CONNECTION_SERVICE" in smoke_manifest
            and "MANAGE_OWN_CALLS" not in phone_manifest,
            "the synthetic ConnectionService must remain debug-only")
    require("private fun isEmulator()" in smoke_activity
            and "Build.HARDWARE" in smoke_activity
            and "ACTION_INCOMING" in smoke_activity
            and "ACTION_DISCONNECT" in smoke_activity,
            "the Telecom fixture must refuse physical hardware and clean up calls")
    require("'^emulator-[0-9]+$'" in smoke_script
            and "ro.kernel.qemu" in smoke_script
            and 'physical_gate_evidence = $false' in smoke_script
            and "finally {" in smoke_script
            and "remove-role-holder" in smoke_script
            and "full_screen_intent_launched_automatically" in smoke_script,
            "the Telecom smoke script must be emulator-only, reversible, and non-release evidence")
    require("isKnownContact" in assistant_client
            and "addressHash" in assistant_client
            and "normalizedAddressHash" in assistant_client
            and "MAX_TRANSCRIPT_CHARS" in assistant_client,
            "AIOS Phone must minimize call identity and bound transcript callbacks")
    require("PROPERTY_EMERGENCY_CALLBACK_MODE" in assistant_client
            and "PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL" in assistant_client
            and "EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS" in assistant_client
            and "EmergencyProcessingGate(" in assistant_client
            and "applyEmergencyProtection(session)" in assistant_client
            and "service.onEmergencyCallDetected(session.callId)" in assistant_client
            and "cancelDelayedAnswer" in assistant_client
            and '"src/com/aios/phone/intelligence/EmergencyProcessingGate.kt"'
            in phone_build
            and "completeNumberCheck" in emergency_processing_gate
            and "observeTelecom" in emergency_processing_gate
            and "lateTelecomSignalInvalidatesPendingNumberResult"
            in emergency_processing_test,
            "AIOS Phone must stop and erase processing for emergency calls in either direction")
    require("service.onCallAnswered" in assistant_client
            and "service.onCallEnded" in assistant_client
            and "onServiceDisconnected" in assistant_client,
            "AIOS Phone must bracket intelligence sessions and survive Binder loss")
    require("telecomLifecycleToken: IBinder = Binder()" in assistant_client
            and "service.setTelecomCallPresent(telecomLifecycleToken" in assistant_client
            and "announceEveryPresentCall(service)" in assistant_client,
            "AIOS Phone must publish every Telecom call with a replayable lifecycle token")
    require("PendingAiAnswerGate()" in assistant_client
            and "pendingAiAnswers.consume(callId, reservation)" in assistant_client
            and "fun cancelAutomaticAnswer(callId: String)" in assistant_client
            and "assistant.cancelAutomaticAnswer(callId)" in phone_runtime
            and "private fun rejectCall(callId: String)" in phone_runtime
            and "PhoneAction.ClaimOwnerAnswer(action.callId)" in in_call_activity,
            "owner answer and decline must synchronously revoke delayed AI answering")
    require("reservations.remove(callId)" in pending_answer_gate
            and "ownerCancellationRejectsAlreadyQueuedCallback" in pending_answer_test
            and 'name: "aios_phone_host_tests"' in phone_build
            and 'kotlin.directories.add("../../apps/phone/tests/src")' in prodcheck_build
            and 'testImplementation("junit:junit:4.13.2")' in prodcheck_build,
            "delayed AI-answer cancellation must have a host-tested stale-callback guard")
    require("CallRiskAssessment" in assistant_client
            and "CallRiskLabel.fromWire" in assistant_client
            and "CallRiskSource.fromWire" in assistant_client
            and "label.accepts" in assistant_client
            and "CallRiskSemantics.shouldReplace" in phone_runtime
            and "risk.label.headline" in phone_screens
            and "risk.source.displayName" in phone_screens
            and "onlyNewerPositiveRevisionsReplaceVisibleState" in call_risk_test
            and "MAX_REASON_CODE_CHARS" in call_risk_contract,
            "AIOS Phone must validate, humanize, and monotonically project typed call risk")
    require("CallAssistantState" in assistant_client
            and "onAssistantStateChanged" in assistant_client
            and "service.takeOverCall(callId)" in assistant_client
            and "pendingTakeovers.add(callId)" in assistant_client
            and "session.assistantRevision" in assistant_client
            and "AssistantCallSemantics.shouldReplace" in assistant_client
            and "AssistantCallSemantics.shouldReplace" in phone_runtime
            and "assistantCalls" in phone_contract
            and "PhoneAction.TakeOver" in phone_runtime
            and "PhoneAction.TakeOver" in phone_screens
            and 'Text("Take over")' in phone_screens
            and '"Take over"' in notification_source
            and "ACTION_TAKE_OVER" in notification_source
            and "PhoneAction.TakeOver" in notification_receiver
            and "onlyNewerPositiveAssistantStateReplacesVisibleState"
            in assistant_call_test
            and "candidateRevision > 0L" in assistant_call_contract,
            "AIOS Phone must expose one revision-safe owner-takeover action in UI and notification")
    require("onAssistantFailure" in assistant_client
            and "status < 0" in assistant_client
            and "The call is connected to you" in phone_runtime
            and "The phone call is still connected" in phone_runtime,
            "per-call AI failures must visibly hand the connected call to the owner")

    api = (root / "services" / "modelbroker" / "aidl" / "com" / "aios" /
           "model" / "IAiosModelService.aidl").read_text(encoding="utf-8")
    require("ParcelFileDescriptor" in api,
            "model API must stream media through a file descriptor")
    require("attachAudioOutput" in api and "in ParcelFileDescriptor pcmSink" in api,
            "model API must expose a bounded PCM sink for speech synthesis")
    require("IModelCallback" in api, "model API must be asynchronous")
    require("import android.os.IBinder" in api
            and "setCallActive(in IBinder lifecycleToken, boolean active)" in api,
            "call priority must be tied to a client-owned Binder lifecycle token")
    require("String modelPath" not in api and "String filePath" not in api,
            "model API must not expose filesystem paths")

    manifest = (root / "services" / "modelbroker" / "AndroidManifest.xml").read_text(
        encoding="utf-8"
    )
    require('android:protectionLevel="signature"' in manifest,
            "model broker permission must be signature protected")
    require('android:permission="com.aios.permission.USE_MODEL_BROKER"' in manifest,
            "model broker service must enforce its signature permission")

    service = (root / "services" / "modelbroker" / "src" / "com" / "aios" /
               "modelbroker" / "ModelBrokerService.java").read_text(encoding="utf-8")
    require("ERROR_NOT_READY" in service and "state.runtimeAvailable" in service,
            "unconfigured model broker must fail closed")
    require("onTrimMemory" in service
            and "sessions.onMemoryPressure()" in service,
            "model broker must preempt background work under Android memory pressure")
    require("token.linkToDeath" in service
            and "callActivityLeases.removeDead(token)" in service
            and "state.setCallActive(active)" in service
            and "sessions.setCallActive(desired)" in service,
            "call priority must clear on client death and reconcile broker arbitration")
    session_controller = (root / "services" / "modelbroker" / "src" / "com" /
                          "aios" / "modelbroker" / "SessionController.java").read_text(
                              encoding="utf-8")
    require("closeDescriptor(pcmStream)" in session_controller
            and "closeDescriptor(media)" in session_controller
            and "closeDescriptor(pcmSink)" in session_controller
            and "input.close()" in session_controller,
            "broker session ownership must close client descriptors")
    require("class AudioOutput" in session_controller
            and '"speech_synthesis".equals(capability)' in session_controller
            and '"synthesis".equals(format.direction)' in session_controller
            and "audioOutputAttached" in session_controller,
            "speech output must be capability-bound, format-checked, and single-attach")
    require('"image_understanding".equals(capability)' in session_controller
            and '"video_understanding".equals(capability)' in session_controller,
            "broker media input must admit explicit image and storyboard-video capabilities")
    require('"media".equals(format.direction)' in session_controller,
            "broker audio input must admit the background video timeline direction")

    runtime_api = (root / "services" / "runtimeapi" / "aidl" / "com" / "aios" /
                   "runtime" / "IAiosRuntimeProvider.aidl").read_text(encoding="utf-8")
    require("attachAudioOutput" in runtime_api
            and "in ParcelFileDescriptor pcmSink" in runtime_api,
            "runtime API v2 must carry the broker-owned synthesis sink")

    broker_source_root = (
        root / "services" / "modelbroker" / "src" / "com" / "aios" /
        "modelbroker"
    )
    verifier_source = (broker_source_root / "ArtifactVerifier.java").read_text(
        encoding="utf-8"
    )
    require("getCanonicalFile()" in verifier_source and "modelPrefix" in verifier_source,
            "artifact verifier must confine canonical paths")
    require("artifact.length() != expectedSize" in verifier_source
            and "MessageDigest.isEqual" in verifier_source,
            "artifact verifier must check exact size and digest")
    require("verifyBundle" in verifier_source
            and 'value.has("bundle_members")' in verifier_source
            and 'inner.getString("source_archive_sha256")' in verifier_source
            and "verifyFile(modelId + \"/\" + name, locked)" in verifier_source,
            "artifact verifier must reverify every locked bundle member")
    require("MAX_ARTIFACT_MANIFEST_BYTES" in verifier_source
            and "MAX_BUNDLE_DESCRIPTOR_BYTES" in verifier_source
            and "total > maximumBytes" in verifier_source,
            "artifact and bundle JSON reads must be explicitly bounded")
    broker_state = (broker_source_root / "BrokerState.java").read_text(encoding="utf-8")
    require("RuntimeRegistry.modelFree()" in broker_state
            and "RuntimeRegistry.load" in broker_state,
            "runtime loading must retain a fail-closed fallback")
    admission_source = (broker_source_root / "DeviceModelAdmission.java").read_text(
        encoding="utf-8"
    )
    require("DeviceModelAdmission.load" in broker_state
            and "Build.DEVICE" in broker_state
            and 'SystemProperties.getInt("ro.debuggable", 0) == 1' in broker_state
            and '"model_admission.json"' in broker_state
            and '"deny".equals(root.getString("default_action"))' in admission_source
            and "artifactSha256.equals(artifact.sha256)" in admission_source
            and "STATUS_PENDING.equals(profile.status) && debuggable" in admission_source,
            "broker model selection must be device-scoped, digest-bound, and debug-only while unbenchmarked")
    runtime_registry = (broker_source_root / "RuntimeRegistry.java").read_text(
        encoding="utf-8"
    )
    require('SystemProperties.getInt("ro.debuggable", 0) == 1' in runtime_registry
            and "adapter.supportsBackend(artifact.backend)" in runtime_registry,
            "runtime activation must honor device/debug and backend policy")
    remote_runtime = (broker_source_root / "RemoteRuntimeAdapter.java").read_text(
        encoding="utf-8"
    )
    require("MATCH_SYSTEM_ONLY" in remote_runtime
            and "PROVIDE_MODEL_RUNTIME" in remote_runtime
            and "getImplementationVersion" in remote_runtime,
            "runtime providers must be system, signature-authorized, and version-pinned")
    require("linkToDeath" in session_controller
            and "requireOwner(record, ownerUid)" in session_controller
            and "MAX_PENDING_INPUTS" in session_controller,
            "runtime sessions must handle client death, UID ownership, and input bounds")
    arbiter_source = (broker_source_root / "SessionArbiter.java").read_text(
        encoding="utf-8"
    )
    require("mediaBlocked()" in arbiter_source
            and "callActive = true" not in arbiter_source,
            "foreground sessions must not leave a sticky media gate after completion")

    provider_root = root / "runtime" / "litertlmprovider"
    provider_manifest = (provider_root / "app" / "src" / "main" /
                         "AndroidManifest.xml").read_text(encoding="utf-8")
    require('android:process=":runtime"' in provider_manifest
            and 'android:permission="com.aios.permission.PROVIDE_MODEL_RUNTIME"'
            in provider_manifest
            and 'android:name="libOpenCL.so"' in provider_manifest,
            "LiteRT-LM must run in its own protected GPU-capable process")
    provider_source = (provider_root / "app" / "src" / "main" / "java" /
                       "com" / "aios" / "runtime" / "litertlm" /
                       "LiteRtLmRuntimeService.kt").read_text(encoding="utf-8")
    require('BROKER_PACKAGE = "com.aios.modelbroker"' in provider_source
            and "packages.size != 1" in provider_source,
            "runtime provider must admit only the exact broker UID")
    require("MODEL_DIRECTORY.canonicalFile" in provider_source
            and "MessageDigest.isEqual" in provider_source
            and "model.length() == artifact.sizeBytes" in provider_source,
            "runtime provider must reverify model confinement, size, and digest")
    require("automaticToolCalling = false" in provider_source
            and "conversation?.cancelProcess()" in provider_source,
            "runtime provider must disable tools and support native cancellation")
    require('"video_understanding"' in provider_source
            and "Content.ImageBytes(bytes)" in provider_source
            and "chronological 5 by 4 storyboard" in provider_source
            and "visionBackend" in provider_source,
            "LiteRT-LM must process sampled video storyboards through the vision backend")
    require("TRIM_MEMORY_RUNNING_LOW" in provider_source
            and "sessions.isEmpty()" in provider_source
            and "closeEngine()" in provider_source,
            "LiteRT-LM must release an idle engine under Android memory pressure")
    provider_build = (provider_root / "app" / "build.gradle.kts").read_text(
        encoding="utf-8"
    )
    require("litertlm-android:0.15.0" in provider_build
            and "lockAllConfigurations" in provider_build
            and "dependency_verification_sha256" in provider_build
            and "JavaVersion.VERSION_17" in provider_build
            and "JvmTarget.JVM_17" in provider_build,
            "runtime build must pin LiteRT-LM, use JVM 17, and emit locked provenance")
    provider_bootstrap = (provider_root / "bootstrap_dependency_locks.sh").read_text(
        encoding="utf-8"
    )
    provider_release_build = (provider_root / "build_provider.sh").read_text(
        encoding="utf-8"
    )
    require(":app:dependencies" in provider_bootstrap
            and "--dependency-verification=strict" in provider_bootstrap
            and "app/gradle.lockfile" in provider_release_build,
            "LiteRT-LM lock bootstrap must precede strict offline provenance build")
    provider_lock = (provider_root / "app" / "gradle.lockfile").read_text(
        encoding="utf-8"
    )
    provider_verification = (provider_root / "gradle" /
                             "verification-metadata.xml").read_text(encoding="utf-8")
    require("com.google.ai.edge.litertlm:litertlm-android:0.15.0=" in provider_lock
            and "litertlm-android-0.15.0.aar" in provider_verification
            and "b398c4745934a6035d192ffce5fdaf4f72a0009830a97b73c017c21f2a92b5bd"
            in provider_verification,
            "LiteRT-LM dependency lock and verification digest must match the reviewed AAR")
    provider_properties = (provider_root / "gradle.properties").read_text(
        encoding="utf-8"
    )
    require("org.gradle.configuration-cache=false" in provider_properties,
            "runtime build must disable incompatible Gradle configuration caching")

    whisper_root = root / "runtime" / "whisperprovider"
    whisper_manifest = (whisper_root / "app" / "src" / "main" /
                        "AndroidManifest.xml").read_text(encoding="utf-8")
    require('android:process=":runtime"' in whisper_manifest
            and 'android:permission="com.aios.permission.PROVIDE_MODEL_RUNTIME"'
            in whisper_manifest,
            "whisper.cpp must run in its own protected process")
    whisper_source = (whisper_root / "app" / "src" / "main" / "java" /
                      "com" / "aios" / "runtime" / "whispercpp" /
                      "WhisperRuntimeService.kt").read_text(encoding="utf-8")
    require('BROKER_PACKAGE = "com.aios.modelbroker"' in whisper_source
            and "packages.size != 1" in whisper_source,
            "ASR runtime provider must admit only the exact broker UID")
    require("PriorityBlockingQueue" in whisper_source
            and '"call_rx" -> 3' in whisper_source
            and 'request.workload == "media_background"' in whisper_source
            and "MAX_PENDING_WINDOWS = 4" in whisper_source
            and 'endOfTurn = session.isMedia' in whisper_source
            and "Thread.sleep(10L)" in whisper_source
            and '"ASR fell behind real time"' in whisper_source,
            "ASR runtime must prioritize incoming windows and bound live/offline lag")
    require("TRIM_MEMORY_RUNNING_LOW" in whisper_source
            and "synchronized(modelLock)" in whisper_source
            and "closeModelLocked()" in whisper_source,
            "ASR runtime must safely release an idle model under memory pressure")
    require("MODEL_DIRECTORY.canonicalFile" in whisper_source
            and "MessageDigest.isEqual" in whisper_source
            and "model.length() == artifact.sizeBytes" in whisper_source,
            "ASR runtime must reverify model confinement, size, and digest")
    whisper_cmake = (whisper_root / "app" / "src" / "main" / "cpp" /
                     "CMakeLists.txt").read_text(encoding="utf-8")
    require("CMAKE_CXX_STANDARD 17" in whisper_cmake
            and "armv8.2-a+fp16" in whisper_cmake
            and "WHISPER_BUILD_TESTS OFF" in whisper_cmake,
            "ASR native build must be pinned to the arm64 mobile profile")

    tts_root = root / "runtime" / "ttsprovider"
    tts_manifest = (tts_root / "app" / "src" / "main" /
                    "AndroidManifest.xml").read_text(encoding="utf-8")
    require('android:process=":runtime"' in tts_manifest
            and 'android:permission="com.aios.permission.PROVIDE_MODEL_RUNTIME"'
            in tts_manifest,
            "TTS must run in its own protected process")
    tts_source = (tts_root / "app" / "src" / "main" / "java" / "com" /
                  "aios" / "runtime" / "sherpatts" /
                  "SherpaTtsRuntimeService.kt").read_text(encoding="utf-8")
    require('BROKER_PACKAGE = "com.aios.modelbroker"' in tts_source
            and "packages.size != 1" in tts_source,
            "TTS runtime provider must admit only the exact broker UID")
    require("generateWithConfigAndCallback" in tts_source
            and 'extra = mapOf("lang" to session.request.language)' in tts_source
            and 'request.language in setOf("en", "es")' in tts_source
            and "ParcelFileDescriptor.AutoCloseOutputStream" in tts_source
            and "writePcm16" in tts_source,
            "TTS runtime must stream bilingual PCM with pipe backpressure")
    require("session.cancelled.get()" in tts_source
            and "deadlineElapsedRealtimeMillis" in tts_source
            and "TRIM_MEMORY_RUNNING_LOW" in tts_source
            and "if (sessions.isEmpty()) closeEngine()" in tts_source,
            "TTS runtime must support cancellation, deadlines, and pressure cleanup")
    require("MODEL_DIRECTORY.canonicalFile" in tts_source
            and "MessageDigest.isEqual" in tts_source
            and "EXPECTED_MEMBERS" in tts_source
            and "source_archive_sha256" in tts_source,
            "TTS runtime must independently reverify its complete model bundle")

    model_catalog = load_json(root / "config" / "model_catalog.json")
    tts_models = [model for model in model_catalog["models"]
                  if model.get("runtime") == "sherpa_onnx_tts"
                  and "speech_synthesis" in model.get("capabilities", [])]
    require(len(tts_models) == 1 and isinstance(
                tts_models[0].get("reference_bundle"), dict),
            "exactly one locked Sherpa TTS model bundle is required")
    tts_model = tts_models[0]
    bundle = tts_model["reference_bundle"]
    packaged_license = tts_model["packaged_license"]
    normalized_tts_source = tts_source.replace("_", "")
    require(f'const val MODEL_ID = "{tts_model["id"]}"' in tts_source
            and bundle["sha256"] in tts_source,
            "TTS provider model/archive identity must match the model catalog")
    for member in bundle["members"]:
        require(member["path"] in tts_source
                and member["sha256"] in tts_source
                and f'{member["size_bytes"]}L' in normalized_tts_source,
                f'TTS provider lock is stale for {member["path"]}')
    model_packager = (root / "tools" / "generate_model_pack.py").read_text(
        encoding="utf-8")
    require('parser.add_argument("--license-file"' in model_packager
            and "packaged model license missing" in model_packager
            and 'entry["packaged_license"] = license_record' in model_packager
            and "copied model license failed verification" in model_packager
            and '"license {\\n"' in model_packager
            and "soong_license_kinds" in model_packager
            and "licenses_property(license_module)" in model_packager
            and packaged_license["filename"] in (root / "docs" /
                "tts-runtime.md").read_text(encoding="utf-8"),
            "Supertonic model-license packaging must remain mandatory and verified")

    runtime_catalog = load_json(root / "config" / "runtime_catalog.json")
    tts_providers = [provider for provider in runtime_catalog["providers"]
                     if provider.get("runtime") == "sherpa_onnx_tts"]
    require(len(tts_providers) == 1
            and isinstance(tts_providers[0].get("binary_artifact"), dict),
            "exactly one binary-locked Sherpa TTS provider is required")
    tts_provider = tts_providers[0]
    binary = tts_provider["binary_artifact"]
    tts_build = (tts_root / "app" / "build.gradle.kts").read_text(
        encoding="utf-8")
    tts_bootstrap = (tts_root / "bootstrap_artifacts.sh").read_text(
        encoding="utf-8")
    normalized_tts_build = tts_build.replace("_", "")
    require(tts_provider["implementation_version"] in tts_build
            and binary["sha256"] in tts_build
            and str(binary["size_bytes"]) in normalized_tts_build
            and binary["url"] in tts_bootstrap
            and binary["sha256"] in tts_bootstrap
            and str(binary["size_bytes"]) in tts_bootstrap,
            "TTS build/bootstrap inputs must match the runtime catalog lock")
    require('abiFilters += "arm64-v8a"' in tts_build
            and "lockAllConfigurations" in tts_build
            and "verifyPinnedInputs" in tts_build
            and "dependency_verification_sha256" in tts_build,
            "TTS APK must be arm64-only and emit verified provenance")
    for notice in tts_provider["required_apk_entries"]:
        require(Path(notice["path"]).name in tts_build
                and notice["sha256"] in tts_build,
                f'TTS build does not pin {notice["path"]}')
    asr_client = (root / "services" / "callintelligence" / "src" / "com" /
                  "aios" / "callintelligence" / "AsrBrokerClient.java").read_text(
                      encoding="utf-8")
    require("chunk.language" in asr_client,
            "call transcript metadata must preserve detected ASR language")
    client_policy = (broker_source_root / "AuthorizedClientPolicy.java").read_text(
        encoding="utf-8"
    )
    require("packages.length != 1" in client_policy,
            "shared/multiple-package UIDs must be rejected")
    arbiter_source = (broker_source_root / "SessionArbiter.java").read_text(
        encoding="utf-8"
    )
    require("removeWorkClass(WorkClass.MEDIA_BACKGROUND)" in arbiter_source,
            "call work must cancel background media leases")
    require("preemptBackgroundForMemoryPressure" in arbiter_source,
            "memory pressure must cancel only preemptible background inference")
    require("requireOwner(sessionId, ownerUid)" in arbiter_source,
            "session operations must verify UID ownership")
    require("REJECTED_QUOTA" in arbiter_source,
            "session arbiter must enforce per-client quotas")

    call_manifest = (root / "services" / "callintelligence" /
                     "AndroidManifest.xml").read_text(encoding="utf-8")
    require('android:name="android.permission.CAPTURE_AUDIO_OUTPUT"' in call_manifest,
            "Call Intelligence must request privileged call capture")
    require('android:name="android.permission.USE_EXACT_ALARM"' in call_manifest
            and 'android.permission.SCHEDULE_EXACT_ALARM' not in call_manifest,
            "preinstalled Call Intelligence must have non-revocable exact retention wakeups")
    require('android:protectionLevel="signature|privileged"' in call_manifest,
            "Call Intelligence control API must support the shared-key privileged Dialer")

    call_api = (root / "services" / "callintelligence" / "aidl" / "com" /
                "aios" / "call" / "IAiosCallIntelligence.aidl").read_text(
                    encoding="utf-8")
    call_listener_api = (root / "services" / "callintelligence" / "aidl" / "com" /
                         "aios" / "call" /
                         "ICallIntelligenceListener.aidl").read_text(encoding="utf-8")
    call_risk_api = (root / "services" / "callintelligence" / "aidl" / "com" /
                     "aios" / "call" /
                     "CallRiskAssessment.aidl").read_text(encoding="utf-8")
    call_assistant_api = (root / "services" / "callintelligence" / "aidl" / "com" /
                          "aios" / "call" /
                          "CallAssistantState.aidl").read_text(encoding="utf-8")
    incoming_call_api = (root / "services" / "callintelligence" / "aidl" / "com" /
                         "aios" / "call" /
                         "IncomingCallContext.aidl").read_text(encoding="utf-8")
    require("import android.os.IBinder" in call_api
            and "void setTelecomCallPresent(" in call_api
            and "in IBinder lifecycleToken" in call_api,
            "Call Intelligence must expose death-linked Telecom presence independently of AI")

    call_source_root = (
        root / "services" / "callintelligence" / "src" / "com" / "aios" /
        "callintelligence"
    )
    policy_source = (call_source_root / "CallPolicyEngine.java").read_text(encoding="utf-8")
    require("emergency_bypass" in policy_source and "emergencyCallbackMode" in policy_source,
            "call policy must bypass emergency states")
    require('MODE_OFF = "off"' in policy_source,
            "call policy must have a fail-safe off mode")
    require("if (!processingEnabled)" in policy_source
            and '"assistant_not_ready"' in policy_source,
            "automatic answering must fail closed without processing")
    spam_source = (call_source_root / "SpamRiskEngine.java").read_text(encoding="utf-8")
    risk_tracker_source = (call_source_root / "RiskAssessmentTracker.java").read_text(
        encoding="utf-8")
    artifact_source = (call_source_root / "CallArtifactStore.java").read_text(
        encoding="utf-8")
    risk_tracker_test = (root / "services" / "callintelligence" / "tests" / "src" /
                         "com" / "aios" / "callintelligence" /
                         "RiskAssessmentTrackerTest.java").read_text(encoding="utf-8")
    assistant_tracker_source = (call_source_root /
                                "AssistantHandlingTracker.java").read_text(
                                    encoding="utf-8")
    assistant_tracker_test = (root / "services" / "callintelligence" / "tests" /
                              "src" / "com" / "aios" / "callintelligence" /
                              "AssistantHandlingTrackerTest.java").read_text(
                                  encoding="utf-8")
    require("advisory only" in spam_source
            and 'new Signal("gift_card_payment"' in spam_source
            and 'new Signal("credential_request"' in spam_source
            and '"es".equals(language)' in spam_source,
            "call risk scoring must be advisory, explainable, and English/Spanish aware")
    call_service = (call_source_root / "CallIntelligenceService.java").read_text(
        encoding="utf-8")
    call_context_client = (
        call_source_root / "CallCommunicationContextClient.java"
    ).read_text(encoding="utf-8")
    call_context_accumulator = (
        call_source_root / "CallContextAccumulator.java"
    ).read_text(encoding="utf-8")
    prior_context_formatter = (
        call_source_root / "PriorContextFormatter.java"
    ).read_text(encoding="utf-8")
    call_context_accumulator_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "CallContextAccumulatorTest.java"
    ).read_text(encoding="utf-8")
    prior_context_formatter_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "PriorContextFormatterTest.java"
    ).read_text(encoding="utf-8")
    call_context_check_build = (
        root / "preview" / "callcontextcheck" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    telecom_presence = (call_source_root / "TelecomCallPresenceTracker.java").read_text(
        encoding="utf-8")
    require("token.linkToDeath" in call_service
            and "onTelecomPresenceTokenDied" in call_service
            and "asr.setCallActive(desired)" in call_service
            and "MAX_TELECOM_LIFECYCLE_TOKENS" in call_service
            and "MAX_CALLS_PER_LIFECYCLE_TOKEN" in call_service
            and "ownerUid" in telecom_presence
            and "maxTokens" in telecom_presence
            and "maxCallsPerToken" in telecom_presence,
            "Telecom presence must be UID-owned, bounded, death-linked, and drive call priority")
    require("String transientAddress" in incoming_call_api
            and "String countryIso" in incoming_call_api
            and incoming_call_api.index("ringingSinceElapsedRealtimeMillis")
            < incoming_call_api.index("transientAddress")
            and "ownerProcessingEnabled == true && !emergency" in assistant_client
            and "context.transientAddress" in call_service
            and "decision.processingAllowed" in call_service
            and "!context.emergency" in call_service
            and "!context.emergencyCallbackMode" in call_service,
            "raw caller identity must be transient and retrieved only for authorized non-emergency processing")
    require("candidate.resolveIdentity(address, countryIso)" in call_context_client
            and 'identity, "", PriorContextFormatter.MAX_ITEMS' in call_context_client
            and '"call_artifact"' in call_context_client
            and "candidate.upsert(new ContextDocument(" in call_context_client
            and "expiresAtEpochMillis <= observedNow" in call_context_client
            and "resolvedCalls.remove(callId)" in call_context_client
            and "void discardCall(String callId)" in call_context_client
            and "MAX_ITEMS = 8" in prior_context_formatter
            and "MAX_EXCERPT_CHARS = 512" in prior_context_formatter
            and '"source_id"' not in prior_context_formatter,
            "call RAG must resolve opaque identity, retrieve bounded context, and publish only expiring summaries")
    require("if (!isFinal" in call_context_accumulator
            and "MAX_DOCUMENT_CHARS = 4_096" in call_context_accumulator
            and "appendContextTranscript" in call_service
            and "appendContextAssistantReply" in call_service
            and "appendContextAssessment" in call_service
            and "communicationContext.indexCallArtifact(" in call_service
            and "stored.expiresAtEpochMillis" in call_service
            and "sourceId = digest(callId)" in artifact_source,
            "indexed call summaries must use final bounded content, opaque IDs, and the artifact TTL")
    require("retainsOnlyFinalBilingualSegmentsAndSafeAssessments"
            in call_context_accumulator_test
            and "evictsOldestTextWithinDocumentLimit"
            in call_context_accumulator_test
            and "formatsOnlyBoundedIdentifierFreeContext"
            in prior_context_formatter_test
            and "rejectsUnknownRowsAndEscapesJsonText"
            in prior_context_formatter_test,
            "call-context bounds and prompt serialization must remain host-tested")
    require("CallCommunicationContextClient.java" in call_context_check_build
            and "../../services/contextintelligence/api" in call_context_check_build
            and "../../services/contextintelligence/aidl" in call_context_check_build
            and "../../services/contextintelligence/src" in call_context_check_build
            and "ContextPolicyTest.java" in call_context_check_build
            and "RevisionGateTest.java" in call_context_check_build
            and "CallContextAccumulatorTest.java" in call_context_check_build
            and "PriorContextFormatterTest.java" in call_context_check_build,
            "the call-context Binder client and bounds tests need a public-SDK compile check")
    require('"downlink".equals(direction)' in call_service
            and ".onRiskChanged(" in call_service
            and "appendAssessment(" in call_service,
            "only incoming speech may drive persisted live call-risk updates")
    require("CallRiskAssessment" in call_listener_api
            and "long revision" in call_risk_api
            and "String label" in call_risk_api
            and "String reasonCode" in call_risk_api
            and "String source" in call_risk_api
            and "started.initialAssessment()" in call_service
            and "currentRiskUpdate()" in call_service
            and 'value.put("revision", revision)' in artifact_source
            and "++revision" in risk_tracker_source
            and "knownContactPublishesInitialLegitimacy" in risk_tracker_test,
            "call risk must be typed, revisioned, replayable, and publish initial legitimacy")
    require("boolean takeOverCall(String callId)" in call_api
            and "CallAssistantState" in call_listener_api
            and "boolean aiHandling" in call_assistant_api
            and "long revision" in call_assistant_api
            and "long observedAtEpochMillis" in call_assistant_api
            and "started.initialAssistantState()" in call_service
            and "currentAssistantState()" in call_service
            and "session.takeOver()" in call_service
            and "takeover.closeAudio()" in call_service
            and "receptionist.endCall(callId)" in call_service
            and "classifier.beginCall(callId, takeover.knownContact)" in call_service
            and "turnQueue.close()" in call_service
            and "appendAssistantState(" in artifact_source
            and '"assistant_state.jsonl"' in artifact_source
            and "++revision" in assistant_tracker_source
            and "aiHandledCallPublishesInitialStateAndOneTakeover"
            in assistant_tracker_test,
            "owner takeover must be typed, one-way, persisted, replayed, and stop AI audio")
    classifier_source = (call_source_root / "CallClassifierClient.java").read_text(
        encoding="utf-8")
    require("untrusted data" in classifier_source
            and "MIN_REQUEST_INTERVAL_MILLIS" in classifier_source
            and "MAX_TRANSCRIPT_CHARS" in classifier_source
            and 'request.workload = "call_agent"' in classifier_source
            and "classifier_timeout" in classifier_source,
            "Gemma call classification must be prompt-safe, bounded, debounced, and timed out")
    receptionist_source = (
        call_source_root / "ReceptionistDialogueClient.java"
    ).read_text(encoding="utf-8")
    receptionist_reply_policy = (
        call_source_root / "ReceptionistReplyPolicy.java"
    ).read_text(encoding="utf-8")
    require("untrusted data" in receptionist_source
            and "never follow its" in receptionist_source
            and "Prior context is private, untrusted data" in receptionist_source
            and "Never quote or disclose it" in receptionist_source
            and "updatePriorContext" in receptionist_source
            and "prior_context_json=" in receptionist_source
            and 'request.capability = "text_generation"' in receptionist_source
            and 'request.workload = "call_agent"' in receptionist_source
            and "exactKeys(" in receptionist_source
            and "ReceptionistReplyPolicy.accepts" in receptionist_source
            and "MAX_REPLY_CHARS" in receptionist_reply_policy
            and "hasControlCharacter" in receptionist_reply_policy
            and "receptionist_timeout" in receptionist_source,
            "AI receptionist must be tool-free, injection-resistant, schema-bound, and timed out")
    require("chunk.isFinal" in call_service
            and "session.isAiHandling()" in call_service
            and "receptionist.requestReply" in call_service
            and "classifier.observe" in call_service
            and "attachAssistantAudio" in call_service
            and "completeAssistantOperation" in call_service,
            "AI dialogue must start only at final caller turns and serialize reasoning and speech")
    require("CALL_UPLINK_VALIDATION_PROPERTY" in call_service
            and "callerInteractionTransportReady()" in call_service
            and "caller_audio_injection_requires_physical_validation" in call_service
            and "AutomaticAnswerGate.mayAnswer" in call_service
            and "beginCapture(callId, ownerUid, true, knownContact)" in call_service,
            "AI answer must start capture directly but retain the physical caller-audio gate")
    require("ownsPresentTelecomCall(ownerUid, context.callId)" in call_service
            and "ownsPresentTelecomCall(ownerUid, callId)" in call_service
            and "session.ownedBy(ownerUid)" in call_service
            and "candidate.ownedBy(ownerUid)" in call_service,
            "call admission, capture, takeover, and teardown must retain dialer UID ownership")
    require("void onEmergencyCallDetected(String callId)" in call_api
            and "emergencyProtectedCalls.put(callId, ownerUid)" in call_service
            and '"emergency_processing_blocked"' in call_service
            and "stopped.takeOver()" in call_service
            and "artifactStore.discard(callId)" in call_service
            and "void discard(String callId)" in artifact_source
            and "CallArtifactRetention.deleteTree(directory)" in artifact_source,
            "late emergency detection must latch, stop AI audio, and erase call artifacts")

    caller_uplink = (call_source_root / "CallerAudioUplink.java").read_text(
        encoding="utf-8"
    )
    require("AudioDeviceInfo.TYPE_TELEPHONY" in caller_uplink
            and "setPreferredDevice" in caller_uplink
            and "getRoutedDevice" in caller_uplink
            and "getPlaybackHeadPosition" in caller_uplink
            and "MODIFY_PHONE_STATE" in caller_uplink,
            "caller audio must verify telephony-TX routing and drain every frame")
    require("CallerDisclosureCoordinator" not in call_service
            and "pendingAiDisclosures" not in call_service,
            "mandatory spoken disclosure state must not remain in the AI answer path")

    common_product = (root / "products" / "aios_common.mk").read_text(encoding="utf-8")
    require("ro.aios.call_uplink_validated=false" in common_product,
            "caller uplink must remain disabled in source until physical validation")

    retention_source = (call_source_root / "CallArtifactRetention.java").read_text(
        encoding="utf-8"
    )
    retention_alarm = (call_source_root / "RetentionAlarm.java").read_text(
        encoding="utf-8"
    )
    require("24L * 60L * 60L * 1000L" in retention_source
            and "Math.addExact" in retention_source
            and "expiresAtEpochMillis <= nowEpochMillis" in retention_source
            and "UNREADABLE_EXPIRY = Long.MIN_VALUE" in retention_source,
            "call artifact policy must enforce an overflow-safe, fail-closed 24-hour TTL")
    require("CallArtifactRetention.expiresAt" in artifact_source
            and "STORAGE_LOCK" in artifact_source
            and "ACTIVE_SESSIONS" in artifact_source
            and "closeActiveSession" in artifact_source
            and "CallArtifactRetention.validatedExpiry" in artifact_source
            and "CallArtifactRetention.cleanup" in artifact_source
            and "CallArtifactRetention.nextExpiry" in artifact_source,
            "call artifact storage must close live files, lock, and delegate to the tested TTL policy")
    require("CallArtifactRetention.elapsedAlarmTrigger" in retention_alarm
            and "AlarmManager.ELAPSED_REALTIME_WAKEUP" in retention_alarm
            and "setExactAndAllowWhileIdle" in retention_alarm
            and "canScheduleExactAlarms" in retention_alarm,
            "retention alarm must resist wall-clock rollback after scheduling")
    require("expires_at_epoch_ms" in artifact_source,
            "call artifacts need an absolute expiry")
    require("dialogue.jsonl" in artifact_source
            and "appendAssistantReply" in artifact_source,
            "local receptionist replies must share the call-artifact retention boundary")

    capture_source = (call_source_root / "TelephonyAudioCapture.java").read_text(
        encoding="utf-8"
    )
    capture_gate = (call_source_root / "RequiredCaptureGate.java").read_text(
        encoding="utf-8"
    )
    require("VOICE_DOWNLINK" in capture_source
            and "VOICE_UPLINK" in capture_source
            and "startRequired" in capture_source
            and "FIRST_PCM_TIMEOUT_MILLIS" in capture_source
            and "startup.markReady(name)" in capture_source
            and "downlinkReady && uplinkReady" in capture_gate
            and "first_pcm_timeout" in capture_gate
            and "capture.startRequired()" in call_service,
            "call capture must keep both directions separate and prove live PCM")
    asr_client = (call_source_root / "AsrBrokerClient.java").read_text(encoding="utf-8")
    require('request.language = "und"' in asr_client,
            "ASR client must permit English/Spanish auto-detection")
    require('"call_rx" : "call_tx"' in asr_client,
            "downlink and uplink must receive distinct server priorities")
    require("ParcelFileDescriptor.createPipe()" in asr_client,
            "call ASR must stream through a pipe rather than expose files")
    require("callActivityToken = new Binder()" in asr_client
            and "service.setCallActive(callActivityToken, callActive)" in asr_client,
            "call ASR must hold call priority with a process-lifetime Binder token")
    whisper_source = (
        root / "runtime" / "whisperprovider" / "app" / "src" / "main" /
        "java" / "com" / "aios" / "runtime" / "whispercpp" /
        "WhisperRuntimeService.kt"
    ).read_text(encoding="utf-8")
    require("ENDPOINT_SILENCE_MILLIS = 600" in whisper_source
            and "endOfTurn" in whisper_source
            and "emitTurn(session, isFinal = true)" in whisper_source
            and "session.turnText" in whisper_source,
            "call ASR must expose silence-endpointed revision-style final turns")
    fanout = (call_source_root / "ResilientFanoutOutputStream.java").read_text(
        encoding="utf-8"
    )
    require("dropSecondary()" in fanout and "primary.write" in fanout,
            "ASR failure must not stop the authoritative local PCM sink")

    media_source_root = (
        root / "services" / "mediaintelligence" / "src" / "com" / "aios" /
        "mediaintelligence"
    )
    media_policy = (media_source_root / "MediaWorkPolicy.java").read_text(
        encoding="utf-8"
    )
    require("MIN_DEFERRED_BATTERY_PERCENT = 80" in media_policy,
            "media service must enforce the 80 percent deferred threshold")
    require("BLOCK_NOT_CHARGING" in media_policy
            and "BLOCK_BELOW_BATTERY_THRESHOLD" in media_policy
            and "batteryPercent < MIN_DEFERRED_BATTERY_PERCENT" in media_policy,
            "deferred media work must require charging and battery threshold")
    require("BLOCK_ACTIVE_CALL" in media_policy
            and "BLOCK_THERMAL_PRESSURE" in media_policy
            and "BLOCK_BATTERY_STATE_UNAVAILABLE" in media_policy,
            "media execution policy must fail closed on runtime constraints")
    require("!motionPhoto" in media_policy and "!ultraHdr" in media_policy,
            "portable metadata policy must reject complex photos by default")
    media_policy_test = (root / "services" / "mediaintelligence" / "tests" /
                         "src" / "com" / "aios" / "mediaintelligence" /
                         "MediaWorkPolicyTest.java").read_text(encoding="utf-8")
    require("everyVideoIsDeferredRegardlessOfCaptureGroupSize" in media_policy_test,
            "every video must remain deferred regardless of capture grouping")

    observer_source = (media_source_root / "MediaObserverService.java").read_text(
        encoding="utf-8"
    )
    require("MediaStore.Images.Media.EXTERNAL_CONTENT_URI" in observer_source,
            "media service must observe images from all camera apps")
    require("MediaStore.Video.Media.EXTERNAL_CONTENT_URI" in observer_source,
            "media service must observe videos")
    require("requestReconcile(CaptureCoalescer.QUIET_PERIOD_MILLIS)" in observer_source
            and "reconcileExactSource" in observer_source,
            "media observer must debounce generation scans and remove deleted or trashed items")
    require("initializeObservation" in observer_source
            and "MediaGenerationScanner.reconcile" in observer_source
            and "registerObservedVolumes" in observer_source,
            "media observer must reconcile missed additions across startup registration")

    generation_scanner = (media_source_root / "MediaGenerationScanner.java").read_text(
        encoding="utf-8"
    )
    generation_reconciler = (
        media_source_root / "MediaGenerationReconciler.java"
    ).read_text(encoding="utf-8")
    generation_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "MediaGenerationReconcilerTest.java"
    ).read_text(encoding="utf-8")
    require("MediaStore.getExternalVolumeNames" in generation_scanner
            and "MediaStore.getVersion" in generation_scanner
            and "MediaStore.getGeneration" in generation_scanner
            and "GENERATION_ADDED" in generation_scanner
            and "GENERATION_MODIFIED" in generation_scanner
            and "IS_PENDING" in generation_scanner
            and "IS_TRASHED" in generation_scanner
            and "shouldSuppressOwnMutation" in generation_scanner
            and "MAX_ROWS_PER_VOLUME = 512" in generation_scanner,
            "media recovery must use bounded settled, non-trashed generation scans")
    require("END_OF_GENERATION" in generation_reconciler
            and "thenComparingLong(row -> row.mediaId)" in generation_reconciler
            and "pendingInsertCannotBeSkipped" in generation_test
            and "truncatedBatchResumesWithinSharedGeneration" in generation_test,
            "media recovery cursor must handle pending and same-generation batch rows")

    liveness_scanner = (media_source_root / "MediaLivenessScanner.java").read_text(
        encoding="utf-8"
    )
    liveness_reconciler = (
        media_source_root / "MediaLivenessReconciler.java"
    ).read_text(encoding="utf-8")
    liveness_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "MediaLivenessReconcilerTest.java"
    ).read_text(encoding="utf-8")
    require("MAX_ROWS = 128" in liveness_scanner
            and "MediaStore.Files.getContentUri" in liveness_scanner
            and "IS_TRASHED" in liveness_scanner
            and "generationBefore != generationAfter" in liveness_scanner
            and "successfullyProbedVolumes" in liveness_reconciler
            and "deletesOnlyMissingRowsFromSuccessfullyProbedVolumes" in liveness_test
            and "duplicateGenerationsDeleteOneSourceUri" in liveness_test,
            "media deletion recovery must be bounded and use a stable mounted-volume snapshot")
    require("startFullLivenessSweep" in observer_source
            and "MediaLivenessScanner.reconcileExact" in observer_source,
            "media observer must remove deleted canonical sources and sweep after restart")

    job_source = (media_source_root / "MediaInferenceJobService.java").read_text(
        encoding="utf-8"
    )
    media_preview_build = (root / "preview" / "mediascancheck" /
                           "build.gradle.kts").read_text(encoding="utf-8")
    require("MediaInferenceJobService.java" in media_preview_build
            and "VideoAudioExtractor.java" in media_preview_build
            and "VideoTranscript.java" in media_preview_build
            and "VideoExportRecovery.java" in media_preview_build
            and "MediaBootReceiver.java" in media_preview_build
            and not (root / "preview" / "mediascancheck" / "src" / "main" / "java" /
                     "com" / "aios" / "mediaintelligence" /
                     "MediaInferenceJobService.java").exists(),
            "media preview must compile the real inference and video-ASR implementation")
    require("setRequiresCharging(true)" in job_source,
            "deferred job must carry an OS charging constraint")
    require("telecom.isInCall()" in job_source
            and "checkSelfPermission(Manifest.permission.READ_PHONE_STATE)"
            in job_source
            and "catch (SecurityException denied)" in job_source,
            "media jobs must fail closed when call state is active or unavailable")
    require("store.claimNext(workClass)" in job_source,
            "media worker must atomically claim persisted work")
    require("MediaContent.sha256" in job_source
            and "generationAfter != job.generation" in job_source,
            "media worker must bind results to an unchanged generation and digest")
    require("store.commitResult" in job_source and "XmpProjection.build" in job_source,
            "media worker must transactionally index results and portable metadata")
    require("MediaMetadataCommitter" in job_source
            and "hasPortableMetadataPending" in job_source,
            "media worker must durably finish pending portable metadata")
    require("currentBlockReason(workClass)" in job_source
            and "job, () -> currentBlockReason(workClass)" in job_source,
            "media worker must enforce constraints before and during inference")

    media_broker_source = (media_source_root / "MediaBrokerClient.java").read_text(
        encoding="utf-8"
    )
    require("CONSTRAINT_RECHECK_MILLIS = 1_000L" in media_broker_source
            and "constraints.blockedReason()" in media_broker_source
            and "cancelActiveSession()" in media_broker_source,
            "media Broker client must periodically cancel work that loses constraints")
    require("PreparedMedia.open" in media_broker_source
            and "prepared.capability" in media_broker_source
            and "prepared.submittedMimeType" in media_broker_source
            and "VideoStoryboard.isStoryboard(temporary)" in media_broker_source,
            "media Broker client must submit explicit inputs and erase private storyboards")
    require('request.capability = "streaming_asr"' in media_broker_source
            and 'request.workload = "media_background"' in media_broker_source
            and 'format.direction = "media"' in media_broker_source
            and "VideoAudioExtractor.stream" in media_broker_source
            and "VideoTranscript.fromInference" in media_broker_source
            and "transcribeVideoAudio" in job_source,
            "videos must stream their complete primary audio through background ASR")

    media_input_policy = (media_source_root / "MediaInputPolicy.java").read_text(
        encoding="utf-8"
    )
    media_input_test = (root / "services" / "mediaintelligence" / "tests" /
                        "src" / "com" / "aios" / "mediaintelligence" /
                        "MediaInputPolicyTest.java").read_text(encoding="utf-8")
    require('CAPABILITY_VIDEO = "video_understanding"' in media_input_policy
            and 'STORYBOARD_MIME_TYPE = "image/jpeg"' in media_input_policy
            and "videosUseAnExplicitJpegStoryboardContract" in media_input_test,
            "video input must use an explicit JPEG-storyboard capability contract")

    storyboard_source = (media_source_root / "VideoStoryboard.java").read_text(
        encoding="utf-8"
    )
    storyboard_plan_test = (root / "services" / "mediaintelligence" / "tests" /
                            "src" / "com" / "aios" / "mediaintelligence" /
                            "VideoStoryboardPlanTest.java").read_text(encoding="utf-8")
    require("MediaMetadataRetriever" in storyboard_source
            and "STORYBOARD_WIDTH_PIXELS" in storyboard_source
            and "GRID_COLUMNS = 5" in storyboard_source
            and "GRID_ROWS = 4" in storyboard_source
            and "getScaledFrameAtTime" in storyboard_source
            and "requireAvailable(constraints)" in storyboard_source
            and "eraseCached(context)" in storyboard_source
            and "stream.getFD().sync()" in storyboard_source
            and "samplesTwentyChronologicalSegmentMidpoints" in storyboard_plan_test
            and "scalingBoundsLandscapePortraitAndRotation" in storyboard_plan_test,
            "video storyboards must sample twenty bounded keyframes with constraint checks")
    require("VideoStoryboard.InvalidVideoException" in job_source
            and "store.markFailed(job.id)" in job_source,
            "undecodable videos must fail permanently rather than retry forever")

    media_store_source = (media_source_root / "MediaJobStore.java").read_text(
        encoding="utf-8"
    )
    require("database.beginTransaction()" in media_store_source
            and "STATUS_RUNNING" in media_store_source,
            "media claims and result commits must be transactional")
    require("own_mutations" in media_store_source
            and "recoverInterruptedWork" in media_store_source,
            "media database must suppress self-writes and recover interrupted jobs")
    require("VERSION = 8" in media_store_source
            and "oldVersion < 2" in media_store_source
            and "oldVersion < 3" in media_store_source
            and "oldVersion < 4" in media_store_source
            and "oldVersion < 5" in media_store_source
            and "oldVersion < 6" in media_store_source
            and "oldVersion < 7" in media_store_source
            and "oldVersion < 8" in media_store_source
            and "oldVersion >= 2 && oldVersion < 7" in media_store_source
            and "CREATE TABLE timing_samples" in media_store_source
            and "CREATE TABLE media_scan_state" in media_store_source
            and "CREATE TABLE result_digests" in media_store_source
            and "CREATE TABLE context_associations" in media_store_source
            and "CREATE TABLE video_subtitles" in media_store_source
            and "CREATE TABLE video_export_journal" in media_store_source
            and "CREATE VIRTUAL TABLE video_subtitle_fts" in media_store_source
            and "video_subtitles_ad BEFORE DELETE" in media_store_source
            and "audio_status" in media_store_source
            and "video_audio_duration_ms" in media_store_source
            and "video_audio_pipeline_ms" in media_store_source
            and "media_store_version" in media_store_source
            and "cannot durably enqueue media job" in media_store_source
            and "boolean purgeVolume" in media_store_source
            and '"media_uri=? AND _id<>?"' in media_store_source
            and "MediaTimingSummary.MAX_SAMPLES_PER_KIND" in media_store_source
            and "timing_samples" in media_store_source.partition("commitResult(")[2],
            "media database must explicitly migrate timing and durable scan state")
    video_audio_source = (media_source_root / "VideoAudioExtractor.java").read_text(
        encoding="utf-8")
    video_transcript_source = (media_source_root / "VideoTranscript.java").read_text(
        encoding="utf-8")
    video_transcript_test = (root / "services" / "mediaintelligence" / "tests" /
                             "src" / "com" / "aios" / "mediaintelligence" /
                             "VideoTranscriptTest.java").read_text(encoding="utf-8")
    require("MediaExtractor" in video_audio_source
            and "MediaCodec" in video_audio_source
            and "OUTPUT_SAMPLE_RATE_HZ = 16_000" in video_audio_source
            and "writeSilence" in video_audio_source
            and "requireAvailable(constraints)" in video_audio_source
            and "MAX_TIMELINE_MILLIS" in video_transcript_source
            and "MAX_TRANSCRIPT_CHARS" in video_transcript_source
            and "keepsOnlyFinalTimestampedEnglishAndSpanishSegments"
            in video_transcript_test,
            "video ASR must be full-track, timestamped, bilingual, bounded, and preemptible")

    embedded_video = (media_source_root / "VideoEmbeddedMetadata.java").read_text(
        encoding="utf-8")
    enhanced_muxer = (media_source_root / "VideoEnhancedCopyMuxer.java").read_text(
        encoding="utf-8")
    enhanced_activity = (
        media_source_root / "VideoEnhancedCopyActivity.java"
    ).read_text(encoding="utf-8")
    enhanced_service = (
        media_source_root / "VideoEnhancedCopyService.java"
    ).read_text(encoding="utf-8")
    export_recovery = (
        media_source_root / "VideoExportRecovery.java"
    ).read_text(encoding="utf-8")
    export_recovery_policy = (
        media_source_root / "VideoExportRecoveryPolicy.java"
    ).read_text(encoding="utf-8")
    export_recovery_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "VideoExportRecoveryPolicyTest.java"
    ).read_text(encoding="utf-8")
    enhanced_manifest = (root / "services" / "mediaintelligence" /
                         "AndroidManifest.xml").read_text(encoding="utf-8")
    require('DESCRIPTION_MIME =' in embedded_video
            and '"application/vnd.aios.video-description+json"' in embedded_video
            and '"application/vnd.aios.subtitle+json"' in embedded_video
            and "MAX_SAMPLE_BYTES = 64 * 1024" in embedded_video
            and "source_sha256" in embedded_video
            and "subtitle_track_mime" in embedded_video,
            "enhanced MP4 metadata tracks must be bounded and source-bound")
    require("new MediaMuxer" in enhanced_muxer
            and "MediaExtractor" in enhanced_muxer
            and "copySamples" in enhanced_muxer
            and "verifyOutput" in enhanced_muxer
            and "encoded MP4 samples changed during remux" in enhanced_muxer
            and "SAMPLE_FLAG_ENCRYPTED" in enhanced_muxer
            and "SAMPLE_FLAG_PARTIAL_FRAME" in enhanced_muxer,
            "enhanced MP4 export must copy and verify encoded source samples")
    require("Create AI-enhanced copy?" in enhanced_activity
            and "The original stays unchanged" in enhanced_activity
            and "players may ignore it" in enhanced_activity
            and "startForegroundService" in enhanced_activity,
            "enhanced MP4 export must require an explicit, compatibility-aware owner action")
    require("MediaContent.sha256" in enhanced_service
            and "MediaContent.generation" in enhanced_service
            and "IS_PENDING" in enhanced_service
            and 'Environment.DIRECTORY_MOVIES + "/AIOS"' in enhanced_service
            and "beginOwnMutation" in enhanced_service
            and "finishOwnMutation" in enhanced_service
            and "beginVideoExport" in enhanced_service
            and "attachVideoExportOutput" in enhanced_service
            and "clearVideoExportJournal" in enhanced_service
            and "QUERY_ARG_MATCH_PENDING" in export_recovery
            and "deleteUnattachedOutput" in export_recovery
            and "PRESERVE_PUBLISHED" in export_recovery_policy
            and "deletesOnlyOwnedMarkedPendingMp4" in export_recovery_test
            and "VideoEnhancedCopyMuxer.create" in enhanced_service,
            "enhanced MP4 publication must be source-bound, crash-safe, and self-suppressed")
    require('android.intent.action.SEND' in enhanced_manifest
            and 'android:mimeType="video/mp4"' in enhanced_manifest
            and 'android:foregroundServiceType="dataSync"' in enhanced_manifest
            and "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in enhanced_manifest,
            "Media Intelligence must expose only the confirmed MP4 share target and internal exporter")
    require("state != null && store.purgeVolume(volumeName)" in generation_scanner,
            "MediaProvider identity changes must purge stale URI-keyed media results")

    association_manifest = (root / "services" / "mediaintelligence" /
                            "AndroidManifest.xml").read_text(encoding="utf-8")
    association_aidl = (root / "services" / "mediaintelligence" / "aidl" / "com" /
                        "aios" / "media" / "IMediaContextAssociation.aidl").read_text(
                            encoding="utf-8")
    association_service = (media_source_root / "MediaContextAssociationService.java").read_text(
        encoding="utf-8")
    association_policy_test = (root / "services" / "mediaintelligence" / "tests" /
                               "src" / "com" / "aios" / "mediaintelligence" /
                               "MediaAssociationPolicyTest.java").read_text(encoding="utf-8")
    association_clear_request = media_store_source.partition(
        "void requestClearMmsPhotos()")[2].partition(
            "boolean clearMmsPhotosPending()")[0]
    association_clear_complete = media_store_source.partition(
        "void completeAssociationClear(")[2].partition(
            "private static long associationRevision(")[0]
    require('protectionLevel="signature"' in association_manifest
            and 'android:permission="com.aios.permission.ASSOCIATE_MEDIA_CONTEXT"'
            in association_manifest
            and "ParcelFileDescriptor" in association_aidl
            and "ConversationIdentity" in association_aidl
            and "draftAndCarrierSubmissionCannotPublish" in association_policy_test
            and "remote.upsert(new ContextDocument" in association_service
            and "remote.deleteSource(SOURCE_TYPE" in association_service
            and "remote.deleteSourceType(SOURCE_TYPE" in association_service
            and "readyAssociationBatch" in association_service
            and "associationInstanceId" in association_service
            and 'database.delete("context_associations", null, null)' in
            association_clear_request
            and 'database.delete("context_associations", null, null)' not in
            association_clear_complete
            and "if (store.clearMmsPhotosPending()) return;" not in association_service
            and "result_digests" in media_store_source,
            "selected-photo context must be signature-gated, carrier-admitted, and lifecycle-bound")

    timing_source = (media_source_root / "MediaTiming.java").read_text(
        encoding="utf-8"
    )
    timing_summary = (media_source_root / "MediaTimingSummary.java").read_text(
        encoding="utf-8"
    )
    timing_test = (root / "services" / "mediaintelligence" / "tests" / "src" /
                   "com" / "aios" / "mediaintelligence" /
                   "MediaTimingTest.java").read_text(encoding="utf-8")
    require("UNKNOWN_MILLIS = -1L" in timing_source
            and "elapsedDuration" in timing_source
            and "videoAudioDurationMillis" in timing_source
            and "videoAudioPipelineMillis" in timing_source
            and "MAX_SAMPLES_PER_KIND = 100" in timing_summary
            and "nearestRank" in timing_summary
            and "videoAudioRealtimeFactorPermille" in timing_summary
            and "p50_video_audio_realtime_factor_permille" in timing_summary
            and "video_audio_realtime_factor_sample_count" in timing_summary
            and "video_audio_sample_count" in timing_summary
            and "snapshotUsesBoundedNearestRankPercentilesAndNoMediaContent"
            in timing_test,
            "media timing must export bounded aggregate latency and video-audio RTF")

    observer_source = (media_source_root / "MediaObserverService.java").read_text(
        encoding="utf-8"
    )
    media_timing_capture = (root / "scripts" /
                            "capture-media-timing.ps1").read_text(encoding="utf-8")
    require("Build.TYPE" in observer_source
            and '"user".equals(Build.TYPE)' in observer_source
            and '"--timing-json"' in observer_source
            and "AIOS_MEDIA_TIMING_BASE64=" in observer_source
            and "dumpsys\", \"activity\", \"service\"" in media_timing_capture
            and "ro.debuggable" in media_timing_capture
            and "build_fingerprint_sha256" in media_timing_capture
            and '$timing.schema_version -ne 2' in media_timing_capture
            and "p95_video_audio_realtime_factor_permille" in media_timing_capture
            and "video_audio_realtime_factor_sample_count" in media_timing_capture
            and "content://|caption|transcript|prompt|media_uri|phone"
            in media_timing_capture,
            "media timing evidence must be debug-only, device-bound, and privacy-minimized")

    jpeg_writer = (media_source_root / "JpegXmpInjector.java").read_text(
        encoding="utf-8"
    )
    require("MPF\\0" in jpeg_writer and "MotionPhoto" in jpeg_writer
            and "hdr-gain-map" in jpeg_writer,
            "portable JPEG writer must reject offset-bearing photo formats")
    require("lossless byte-preservation check failed" in jpeg_writer
            and "appended payload after JPEG EOI" in jpeg_writer,
            "portable JPEG writer must preserve source bytes and reject trailers")

    png_writer = (media_source_root / "PngXmpInjector.java").read_text(
        encoding="utf-8"
    )
    require('"iTXt"' in png_writer and "XML:com.adobe.xmp" in png_writer
            and "CRC32" in png_writer,
            "portable PNG writer must use a CRC-protected standard XMP iTXt chunk")
    require("lossless PNG byte-preservation check failed" in png_writer
            and "animated PNG is not writable" in png_writer
            and "digitally signed PNG is not writable" in png_writer
            and "unknown critical PNG chunk" in png_writer,
            "portable PNG writer must preserve source chunks and reject unsafe containers")

    metadata_committer = (media_source_root / "MediaMetadataCommitter.java").read_text(
        encoding="utf-8"
    )
    require("new AtomicFile" in metadata_committer
            and 'openFileDescriptor(uri, "rwt")' in metadata_committer,
            "portable metadata writes must use a durable journal before MediaStore")
    require("writeSynced(journal.backupFile" in metadata_committer
            and "verifyCandidate" in metadata_committer
            and "restoreOriginal" in metadata_committer,
            "portable metadata writes must back up, verify, and recover supported images")
    require('JPEG_MIME_TYPE = "image/jpeg"' in metadata_committer
            and 'PNG_MIME_TYPE = "image/png"' in metadata_committer
            and '.put("mime_type", mimeType)' in metadata_committer
            and "schemaVersion == 1" in metadata_committer
            and '"media".equals(uri.getAuthority())' in metadata_committer,
            "portable mutation must remain format-bound and recover legacy JPEG journals")

    boot_source = (media_source_root / "MediaBootReceiver.java").read_text(
        encoding="utf-8"
    )
    require("MediaMetadataCommitter(application).recover(store)" in boot_source,
            "boot handling must recover interrupted metadata commits")
    require("VideoExportRecovery.recover(application, store)" in boot_source,
            "boot handling must recover interrupted enhanced-video exports")
    require("VideoStoryboard.eraseCached(application)" in boot_source,
            "boot handling must erase private storyboard remnants")

    xmp_source = (media_source_root / "XmpProjection.java").read_text(encoding="utf-8")
    require("https://aios.dev/ns/media/1.0/" in xmp_source,
            "portable metadata must use the versioned AIOS namespace")


def validate_policy_vectors(root: Path) -> None:
    value = load_json(root / "config" / "call_policy_vectors.json")
    require(value.get("schema_version") == 1, "unsupported call-policy vector schema")
    vectors = value.get("vectors")
    require(isinstance(vectors, list) and vectors, "call-policy vectors are required")
    names: set[str] = set()
    for vector in vectors:
        name = vector["name"]
        require(name not in names, f"duplicate call-policy vector: {name}")
        names.add(name)
        actual = call_policy_decision(
            vector["mode"],
            vector["known_contact"],
            vector["emergency"],
            vector["emergency_callback_mode"],
            vector.get("processing_enabled", True),
        )
        require(actual == vector["expected"],
                f"{name}: expected {vector['expected']}, got {actual}")


def validate_authorized_clients(root: Path) -> None:
    catalog = load_json(root / "config" / "model_catalog.json")
    available_capabilities = {
        capability
        for model in catalog["models"]
        for capability in model["capabilities"]
    }
    value = load_json(root / "config" / "authorized_clients.json")
    require(value.get("schema_version") == 1, "unsupported authorized-client schema")
    clients = value.get("clients")
    require(isinstance(clients, list) and clients, "authorized clients are required")
    packages = [client["package"] for client in clients]
    require(len(packages) == len(set(packages)), "authorized packages must be unique")
    allowed_workloads = {"call_rx", "call_tx", "call_agent", "media_background"}
    for client in clients:
        require(str(client["package"]).startswith("com.aios."),
                f"unexpected preauthorized package: {client['package']}")
        require(set(client["capabilities"]).issubset(available_capabilities),
                f"{client['package']}: unknown capability")
        require(set(client["workloads"]).issubset(allowed_workloads),
                f"{client['package']}: unknown workload")
        require(client["max_sessions"] > 0 and client["max_output_tokens"] > 0,
                f"{client['package']}: invalid quota")
    by_package = {client["package"]: client for client in clients}
    require("call_rx" in by_package["com.aios.callintelligence"]["workloads"],
            "Call Intelligence must be authorized for call_rx")
    require(by_package["com.aios.mediaintelligence"]["workloads"]
            == ["media_background"],
            "Media Intelligence must remain background-only")
    require("streaming_asr" in
            by_package["com.aios.mediaintelligence"]["capabilities"],
            "Media Intelligence needs background ASR for complete video subtitles")
    require(by_package["com.aios.callintelligence"]["can_control_call_state"] is True,
            "Call Intelligence must control the call-active gate")
    require(by_package["com.aios.mediaintelligence"]["can_control_call_state"] is False,
            "Media Intelligence must not control the call-active gate")
    call_gate_controllers = {
        client["package"] for client in clients
        if client["can_control_call_state"] is True
    }
    require(call_gate_controllers == {"com.aios.callintelligence"},
            "only Call Intelligence may control the call-active gate")
    benchmark = by_package.get("com.aios.modelbenchmark")
    require(benchmark is not None
            and set(benchmark["capabilities"]) == {
                "streaming_asr", "text_generation", "image_understanding",
                "video_understanding", "speech_synthesis",
            }
            and set(benchmark["workloads"])
            == {"call_rx", "call_agent", "media_background"}
            and benchmark["max_sessions"] == 1
            and benchmark["can_control_call_state"] is False,
            "debug model benchmark must have one bounded production-path session "
            "and no call-gate authority")


def validate_runtime_catalog(root: Path) -> None:
    value = load_json(root / "config" / "runtime_catalog.json")
    require(value.get("schema_version") == 1, "unsupported runtime-catalog schema")
    require(value.get("provider_api_version") == 2,
            "unsupported runtime-provider API")
    catalog = load_json(root / "config" / "model_catalog.json")
    model_runtimes = {model["runtime"] for model in catalog["models"]}
    providers = value.get("providers")
    require(isinstance(providers, list) and providers,
            "at least one runtime provider is required")
    runtime_ids = [provider["runtime"] for provider in providers]
    require(len(runtime_ids) == len(set(runtime_ids)),
            "runtime provider IDs must be unique")
    require(set(runtime_ids).issubset(model_runtimes),
            "runtime provider must correspond to a catalog runtime")
    for provider in providers:
        runtime = provider["runtime"]
        require(provider.get("status") in {"candidate", "supported"},
                f"{runtime}: invalid provider status")
        require(str(provider.get("package", "")).startswith("com.aios.runtime."),
                f"{runtime}: provider package must use the AIOS runtime namespace")
        require(provider.get("action") == "com.aios.model.RUNTIME_PROVIDER",
                f"{runtime}: unexpected runtime action")
        require(re.fullmatch(r"[0-9a-f]{40}",
                             str(provider.get("source_revision", ""))) is not None,
                f"{runtime}: source revision must be a full commit")
        require(str(provider.get("source_repository", "")).startswith("https://"),
                f"{runtime}: HTTPS source repository is required")
        require(provider.get("license_spdx") in {"Apache-2.0", "MIT"}
                and str(provider.get("license_url", "")).startswith("https://"),
                f"{runtime}: an approved SPDX license and HTTPS source are required")
        version = provider.get("implementation_version")
        maven_artifact = provider.get("maven_artifact")
        binary_artifact = provider.get("binary_artifact")
        source_build = provider.get("source_build")
        locks = [maven_artifact, binary_artifact, source_build]
        require(sum(isinstance(item, dict) for item in locks) == 1,
                f"{runtime}: exactly one Maven, binary, or native source-build lock is required")
        artifact = maven_artifact if isinstance(maven_artifact, dict) \
            else binary_artifact
        if isinstance(artifact, dict):
            require(str(artifact.get("coordinate", "")).endswith(f":{version}"),
                    f"{runtime}: binary coordinate/version mismatch")
            require(str(artifact.get("url", artifact.get("repository", ""))).startswith(
                        "https://"),
                    f"{runtime}: binary artifact needs an HTTPS source")
            require(re.fullmatch(r"[0-9a-f]{64}",
                                 str(artifact.get("sha256", ""))) is not None
                    and artifact.get("size_bytes", 0) > 0,
                    f"{runtime}: exact binary size and digest are required")
        else:
            require(source_build.get("build_system") == "cmake"
                    and source_build.get("language") == "cxx17"
                    and source_build.get("android_abi") == "arm64-v8a",
                    f"{runtime}: unsupported native source-build lock")
        dependencies = provider.get("direct_dependencies")
        require(isinstance(dependencies, list),
                f"{runtime}: direct dependency lock must be an array")
        require(all(str(item).count(":") == 2 for item in dependencies),
                f"{runtime}: dependencies must be exact Maven coordinates")
        notices = provider.get("required_apk_entries")
        require(isinstance(notices, list) and notices,
                f"{runtime}: at least one packaged license/notice is required")
        for notice in notices:
            path = str(notice.get("path", ""))
            require(path.startswith("assets/THIRD_PARTY_NOTICES/")
                    and ".." not in Path(path).parts,
                    f"{runtime}: unsafe required notice path")
            require(isinstance(notice.get("size_bytes"), int)
                    and notice["size_bytes"] > 0
                    and re.fullmatch(r"[0-9a-f]{64}",
                                     str(notice.get("sha256", ""))) is not None,
                    f"{runtime}: exact notice size and digest are required")

    profiles = value.get("device_profiles")
    require(isinstance(profiles, list) and profiles,
            "runtime device profiles are required")
    profile_ids = [profile["id"] for profile in profiles]
    require(len(profile_ids) == len(set(profile_ids)),
            "runtime profile IDs must be unique")
    wildcard_profiles = [profile for profile in profiles if "*" in profile["devices"]]
    require(len(wildcard_profiles) == 1
            and wildcard_profiles[0]["runtime_backends"] == {},
            "unknown devices must have exactly one model-free fallback")
    tegu = next((profile for profile in profiles if "tegu" in profile["devices"]), None)
    require(tegu is not None and tegu.get("debuggable_only") is True,
            "Pixel 9a runtime activation must remain userdebug-only until gated")
    require("npu" not in tegu.get("runtime_backends", {}).get("litert_lm", []),
            "Pixel 9a NPU must remain denied until validated")
    for profile in profiles:
        allowed = profile.get("runtime_backends")
        preferred = profile.get("preferred_backends")
        require(isinstance(allowed, dict) and isinstance(preferred, dict),
                f"{profile['id']}: backend maps are required")
        for runtime, backend in preferred.items():
            require(backend in allowed.get(runtime, []),
                    f"{profile['id']}: preferred backend must be allowed")


def validate_xml_files(root: Path) -> None:
    generated_directories = {
        ".git", ".gradle", ".cache", "build", "out", "dist", "third_party",
    }
    for path in root.rglob("*.xml"):
        if generated_directories.intersection(path.relative_to(root).parts):
            continue
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            raise ValidationError(f"{path}: malformed XML: {exc}") from exc


def validate_security_surface(root: Path) -> None:
    permissions_path = root / "permissions" / "privapp-permissions-aios.xml"
    permissions = ET.parse(permissions_path).getroot()
    capture_holders = []
    phone_state_modifiers = []
    media_managers = []
    android_name = "{http://schemas.android.com/apk/res/android}name"
    for package in permissions.findall("privapp-permissions"):
        package_name = package.attrib.get("package")
        names = {item.attrib.get("name") or item.attrib.get(android_name)
                 for item in package.findall("permission")}
        if "android.permission.CAPTURE_AUDIO_OUTPUT" in names:
            capture_holders.append(package_name)
        if "android.permission.MODIFY_PHONE_STATE" in names:
            phone_state_modifiers.append(package_name)
        if "android.permission.MANAGE_MEDIA" in names:
            media_managers.append(package_name)
    require(capture_holders == ["com.aios.callintelligence"],
            "only Call Intelligence may hold CAPTURE_AUDIO_OUTPUT")
    require(phone_state_modifiers == ["com.aios.callintelligence"],
            "only Call Intelligence may hold MODIFY_PHONE_STATE")
    require(media_managers == ["com.aios.mediaintelligence"],
            "only Media Intelligence may hold MANAGE_MEDIA")
    benchmark_permissions = next((
        package for package in permissions.findall("privapp-permissions")
        if package.attrib.get("package") == "com.aios.modelbenchmark"
    ), None)
    require(benchmark_permissions is not None,
            "debug model benchmark needs an explicit privileged allowlist")
    benchmark_permission_names = {
        item.attrib.get("name") or item.attrib.get(android_name)
        for item in benchmark_permissions.findall("permission")
    }
    require(benchmark_permission_names == {
                "android.permission.DUMP",
                "android.permission.READ_PRIVILEGED_PHONE_STATE",
            },
            "debug model benchmark may hold only PSS and live-call safety permissions")
    privileged_text = permissions_path.read_text(encoding="utf-8")
    require("android.permission.RECORD_AUDIO" not in privileged_text
            and "android.permission.READ_CALL_LOG" not in privileged_text
            and "android.permission.WRITE_CALL_LOG" not in privileged_text,
            "runtime permissions must use default-permissions, not privapp allowlisting")

    default_permissions_path = root / "permissions" / "default-permissions-aios.xml"
    default_permissions_text = default_permissions_path.read_text(encoding="utf-8")
    require('package="com.aios.callintelligence"' in default_permissions_text
            and 'name="android.permission.RECORD_AUDIO"' in default_permissions_text,
            "Call Intelligence must receive its runtime microphone grant explicitly")
    default_permissions = ET.parse(default_permissions_path).getroot()
    media_defaults = [
        package for package in default_permissions.findall("exception")
        if package.attrib.get("package") == "com.aios.mediaintelligence"
    ]
    require(len(media_defaults) == 1,
            "Media Intelligence needs one explicit default-permissions exception")
    media_default_grants = {
        item.attrib.get("name"): item.attrib.get("fixed")
        for item in media_defaults[0].findall("permission")
    }
    require(media_default_grants == {
                "android.permission.READ_MEDIA_IMAGES": "true",
                "android.permission.READ_MEDIA_VIDEO": "true",
                "android.permission.READ_PHONE_STATE": "true",
                "android.permission.POST_NOTIFICATIONS": "true",
            },
            "Media Intelligence defaults must match read, call-gate, and export notification access")
    messaging_defaults = [
        package for package in default_permissions.findall("exception")
        if package.attrib.get("package") == "com.aios.messaging"
    ]
    require(len(messaging_defaults) == 1,
            "AIOS Messaging needs one explicit default-permissions exception")
    messaging_default_grants = {
        item.attrib.get("name"): item.attrib.get("fixed")
        for item in messaging_defaults[0].findall("permission")
    }
    require(messaging_default_grants == {
                "android.permission.READ_PHONE_STATE": "false",
            },
            "AIOS Messaging may receive only revocable SIM-routing access")
    bridge_holders = []
    voicemail_read_holders = []
    voicemail_write_holders = []
    for package in permissions.findall("privapp-permissions"):
        names = {item.attrib.get("name") or item.attrib.get(android_name)
                 for item in package.findall("permission")}
        if "com.aios.permission.CONTROL_CALL_INTELLIGENCE" in names:
            bridge_holders.append(package.attrib.get("package"))
        if "com.android.voicemail.permission.READ_VOICEMAIL" in names:
            voicemail_read_holders.append(package.attrib.get("package"))
        if "com.android.voicemail.permission.WRITE_VOICEMAIL" in names:
            voicemail_write_holders.append(package.attrib.get("package"))
    require(bridge_holders == ["com.android.dialer", "com.aios.phone"]
            and "com.aios.dialer" not in privileged_text,
            "only the transition AOSP Dialer and AIOS Phone may control Call Intelligence")
    require(voicemail_read_holders == ["com.aios.phone"]
            and voicemail_write_holders == ["com.aios.phone"],
            "only AIOS Phone may receive privileged voicemail access")

    call_manifest_text = (root / "services" / "callintelligence" /
                          "AndroidManifest.xml").read_text(encoding="utf-8")
    media_manifest_text = (root / "services" / "mediaintelligence" /
                           "AndroidManifest.xml").read_text(encoding="utf-8")
    require("READ_PRIVILEGED_PHONE_STATE" not in call_manifest_text
            and "READ_PRIVILEGED_PHONE_STATE" not in media_manifest_text,
            "non-dialer AI services must not request privileged phone state")
    phone_manifest_text = (root / "apps" / "phone" / "AndroidManifest.xml").read_text(
        encoding="utf-8"
    )
    require("READ_PRIVILEGED_PHONE_STATE" not in phone_manifest_text,
            "AIOS Phone must use Telecom call details instead of privileged phone-state reads")

    forbidden_suffixes = {".tflite", ".litertlm", ".safetensors", ".gguf", ".onnx"}
    committed_artifacts = [
        str(path.relative_to(root))
        for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() in forbidden_suffixes
        and not {".git", ".cache", "generated"}.intersection(path.parts)
    ]
    require(not committed_artifacts,
            f"model artifacts must not be committed: {committed_artifacts}")

    common_product = (root / "products" / "aios_common.mk").read_text(encoding="utf-8")
    module_names: set[str] = set()
    for blueprint in root.rglob("Android.bp"):
        text = blueprint.read_text(encoding="utf-8")
        module_names.update(re.findall(r'\bname:\s*"([^"]+)"', text))
    local_packages = [name for name in module_names if re.search(r"(?i)aios", name)]
    for module in local_packages:
        if module == "AIOS_Apache_2_0":
            continue
        require(module in common_product or module in {
                    "aios_call_api", "aios_context_api", "aios_media_context_api",
                    "aios_media_metadata_api", "aios_model_api", "aios_runtime_api"}
                or module.endswith("_tests"),
                f"local AIOS module is not reachable from the product: {module}")


def validate_release_configuration(root: Path) -> None:
    tracking = load_json(root / "config" / "aosp_tracking.json")
    require(tracking.get("schema_version") == 1, "unsupported AOSP tracking schema")
    require(tracking["tracking_revision"] == "android-latest-release",
            "integration must track android-latest-release")
    require(tracking["first_device"]["codename"] == "tegu",
            "first hardware target must be Pixel 9a tegu")
    require(tracking["first_device"]["vendor_input_status"]
            == "must_be_resolved_on_linux_build_host",
            "vendor input must remain an explicit bring-up gate")
    require(re.fullmatch(r"[0-9a-f]{40}",
                         tracking.get("observed_release_manifest_commit", ""))
            is not None,
            "the observed latest-release manifest must record its exact commit")
    require(re.fullmatch(r"android[0-9]+-release",
                         tracking.get("observed_release_branch", "")) is not None,
            "latest-release must resolve to a numbered Android release branch")
    try:
        observed_on = date.fromisoformat(tracking.get("observed_on", ""))
    except (TypeError, ValueError) as error:
        raise ValidationError("AOSP observation date must be an ISO calendar date") from error
    require(observed_on <= date.today(), "AOSP observation date cannot be in the future")
    require(tracking["first_device"].get("present_in_observed_release_manifest")
            is False,
            "Pixel 9a must not be represented as part of the Android 17 manifest")

    lanes_document = load_json(root / "config" / "aosp_lanes.json")
    require(lanes_document.get("schema_version") == 1,
            "unsupported AOSP lane schema")
    lanes = lanes_document.get("lanes")
    require(isinstance(lanes, list), "AOSP lanes must be an array")
    expected_artifacts = lanes_document.get("expected_product_artifacts")
    require(isinstance(expected_artifacts, list)
            and all(isinstance(item, str) and item.startswith("product/")
                    and ".." not in Path(item).parts for item in expected_artifacts)
            and len(expected_artifacts) == len(set(expected_artifacts)),
            "expected product artifacts must be unique product-relative paths")
    required_aios_apks = {
        f"product/priv-app/{module}/{module}.apk"
        for module in (
            "AiosContextIntelligence", "AiosMessaging", "AiosPhone",
            "AiosCallIntelligence", "AiosMediaIntelligence", "AiosModelBroker",
        )
    }
    require(required_aios_apks <= set(expected_artifacts),
            "build evidence must require every core installed AIOS application")
    lane_ids = [lane.get("id") for lane in lanes]
    require(lane_ids == ["android_latest_integration", "pixel9a_tegu_hardware"],
            "AIOS must declare exactly the latest-integration and Pixel 9a lanes")
    catalog = load_json(root / "config" / "model_catalog.json")
    catalog_build_lanes = {
        device["build_lane"]
        for device in catalog["known_devices"]
        if device["build_lane"] is not None
    }
    require(catalog_build_lanes == {"pixel9a_tegu_hardware"}
            and catalog_build_lanes <= set(lane_ids),
            "enabled device catalog entries must reference declared hardware lanes")
    integration, hardware = lanes
    require(integration.get("kind") == "virtual_integration"
            and integration.get("manifest_revision") == "android-latest-release"
            and integration.get("product") == "aios_cf_x86_64_phone"
            and integration.get("physical_gate_evidence") is False
            and "frameworks/base" in integration.get("required_projects", [])
            and "device/google/cuttlefish" in integration.get("required_projects", []),
            "latest AOSP must build on Cuttlefish and remain non-physical evidence")
    require(hardware.get("kind") == "physical_hardware"
            and hardware.get("manifest_revision") is None
            and hardware.get("product") == "aios_tegu"
            and hardware.get("compatibility_status")
            == "awaiting_pinned_platform_device_vendor_set"
            and hardware.get("allow_cross_release_device_tree") is False
            and hardware.get("physical_gate_evidence") is True
            and "frameworks/base" in hardware.get("required_projects", [])
            and "device/google/tegu" in hardware.get("required_projects", []),
            "Pixel 9a lane must require a pinned compatible device/vendor set")
    dialer_reference = tracking.get("dialer_reference", {})
    require(dialer_reference.get("tag") == "android-17.0.0_r1"
            and re.fullmatch(r"[0-9a-f]{40}",
                             str(dialer_reference.get("commit", ""))) is not None,
            "Dialer patch must record its exact official Android 17 base")

    series = load_json(root / "patches" / "series.json")
    dialer_patches = [
        patch for patch in series["patches"]
        if patch["project"] == "packages/apps/Dialer"
    ]
    require(len(dialer_patches) == 1
            and dialer_patches[0]["base_revision"] == dialer_reference["commit"],
            "Dialer patch base must match the tracked reference")
    mms_reference = tracking.get("mms_reference", {})
    mms_patches = [
        patch for patch in series["patches"]
        if patch["project"] == "frameworks/base"
    ]
    require(mms_reference.get("tag") == "android-17.0.0_r1"
            and re.fullmatch(
                r"[0-9a-f]{40}",
                str(mms_reference.get("frameworks_base_commit", ""))) is not None
            and len(mms_patches) == 1
            and mms_patches[0]["base_revision"]
            == mms_reference["frameworks_base_commit"],
            "MMS source visibility patch must match the tracked Android 17 framework base")
    mms_patch_text = (root / "patches" / mms_patches[0]["file"]).read_text(
        encoding="utf-8"
    )
    require('"//vendor/aios/apps/messaging"' in mms_patch_text
            and "framework-mms-shared-srcs" in mms_patch_text,
            "MMS patch may only expose the maintained framework source group")
    dialer_patch_text = (root / "patches" / dialer_patches[0]["file"]).read_text(
        encoding="utf-8"
    )
    require("AiosCallAssistant implements CallList.Listener" in dialer_patch_text
            and "unsafeCall(call)" in dialer_patch_text
            and "remote.onEmergencyCallDetected(callId)" in dialer_patch_text
            and "onBindingDied" in dialer_patch_text,
            "Dialer topic must use the call lifecycle and fail-safe cancellation")
    require("hashAddress(number)" in dialer_patch_text
            and "processingAllowed" in dialer_patch_text
            and '"aios_call_api"' in dialer_patch_text,
            "Dialer topic must minimize addresses and use the typed AIOS API")
    require("telecomLifecycleToken = new Binder()" in dialer_patch_text
            and "currentCalls.getAllCalls()" in dialer_patch_text
            and "DialerCallState.isConnectingOrConnected" in dialer_patch_text
            and "remote.setTelecomCallPresent" in dialer_patch_text,
            "AOSP Dialer bridge must publish complete Telecom presence with Binder cleanup")

    release = load_json(root / "config" / "release_gates.json")
    require(release.get("schema_version") == 1, "unsupported release-gate schema")
    gates = release.get("gates")
    require(isinstance(gates, list) and gates, "release gates are required")
    ids = [gate["id"] for gate in gates]
    require(len(ids) == len(set(ids)), "release gate IDs must be unique")
    require(all(gate.get("required") is True for gate in gates),
            "prototype release gates cannot silently become optional")
    critical = {
        "integration.android_latest_manifest_locked",
        "integration.android_latest_userdebug_succeeds",
        "telephony.emergency_ui_bypass",
        "telephony.call_waiting",
        "telephony.audio_endpoint_switch",
        "telephony.ringtone_and_silence",
        "telephony.multi_sim_selection",
        "telephony.post_dial",
        "telephony.proximity_sensor",
        "telephony.rtt",
        "telephony.video_call",
        "telephony.call_log",
        "telephony.voicemail",
        "dialer.user_role_selection",
        "dialer.system_emergency_fallback",
        "dialer.multi_call_udf",
        "dialer.light_dark_theme",
        "dialer.emergency_never_ai",
        "messaging.user_sms_role_selection",
        "messaging.mms_carrier_transport",
        "context.opaque_conversation_identity",
        "context.contact_membership_refresh",
        "context.bounded_local_retrieval",
        "context.source_deletion_tombstone",
        "context.call_artifact_expiry_24_hours",
        "context.call_source_lifecycle",
        "context.photo_metadata_lifecycle",
        "call.caller_uplink_remote_audibility",
        "call.ai_receptionist_dialog_round_trip",
        "call.owner_takeover_stops_ai_speech",
        "call.offline_mode",
        "call.telephony_survives_ai_crash",
        "retention.expiry_24_hours",
        "media.blocked_below_80_percent",
        "media.original_preserved",
        "media.video_storyboard_indexed",
        "media.video_subtitles_indexed",
        "media.pixel9a_latency_profile",
        "model.runtime_dependency_lock_verified",
        "model.runtime_identity_enforced",
        "model.runtime_crash_isolated",
        "model.litertlm_known_answer",
        "model.pixel9a_gpu_benchmark",
    }
    require(critical.issubset(ids),
            f"missing critical release gates: {sorted(critical - set(ids))}")

    status_document = load_json(root / "config" / "release_status.json")
    require(status_document.get("schema_version") == 1,
            "unsupported release-status schema")
    require(status_document.get("target") == release.get("target"),
            "release status target must match release gates")
    statuses = status_document.get("statuses")
    require(isinstance(statuses, dict), "release statuses must be an object")
    require(set(statuses) == set(ids),
            "release status IDs must exactly match release gates")
    allowed_statuses = {"not_run", "passed", "failed", "blocked"}
    for gate_id, value in statuses.items():
        require(isinstance(value, dict), f"{gate_id}: status must be an object")
        require(value.get("status") in allowed_statuses,
                f"{gate_id}: invalid release status")
        evidence = value.get("evidence")
        require(isinstance(evidence, list)
                and all(isinstance(item, str) and item for item in evidence),
                f"{gate_id}: evidence must be a string array")
        if value["status"] == "passed":
            require(bool(evidence), f"{gate_id}: passed gate requires evidence")
        if value["status"] == "blocked":
            require(isinstance(value.get("notes"), str) and value["notes"].strip(),
                    f"{gate_id}: blocked gate requires notes")


def validate(root: Path = ROOT) -> None:
    validate_product(load_json(root / "config" / "product_policy.json"))
    validate_catalog(load_json(root / "config" / "model_catalog.json"))
    validate_model_benchmark_suite(root)
    validate_model_admission(root)
    validate_patch_series(root)
    validate_aosp_overlay(root)
    validate_policy_vectors(root)
    validate_authorized_clients(root)
    validate_runtime_catalog(root)
    validate_xml_files(root)
    validate_security_surface(root)
    validate_release_configuration(root)


def main() -> int:
    try:
        validate()
    except (KeyError, TypeError, ValidationError) as exc:
        print(f"configuration validation failed: {exc}", file=sys.stderr)
        return 1
    print("AIOS configuration is valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
