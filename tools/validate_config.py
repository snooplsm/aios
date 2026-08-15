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


def discover_blueprint_modules(root: Path) -> set[str]:
    """Return source-tree modules without inspecting ignored generated output."""
    ignored_parts = {".git", ".cache", "generated"}
    module_names: set[str] = set()
    for blueprint in root.rglob("Android.bp"):
        if ignored_parts.intersection(blueprint.relative_to(root).parts):
            continue
        text = blueprint.read_text(encoding="utf-8")
        module_names.update(re.findall(r'\bname:\s*"([^"]+)"', text))
    return module_names


def select_tier(catalog: dict[str, Any], total_ram_mb: int) -> str | None:
    eligible = [
        tier
        for tier in catalog["tiers"]
        if tier["min_total_ram_mb"] <= total_ram_mb
    ]
    if not eligible:
        return None
    return max(eligible, key=lambda tier: tier["min_total_ram_mb"])["id"]


def tier_chain(catalog: dict[str, Any], tier_id: str) -> list[dict[str, Any]]:
    tiers = {tier["id"]: tier for tier in catalog.get("tiers", [])}
    current = tiers.get(tier_id)
    require(current is not None, f"unknown catalog tier: {tier_id}")
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    while current is not None:
        require(current["id"] not in seen, "catalog tier fallback cycle")
        seen.add(current["id"])
        result.append(current)
        fallback_id = current.get("fallback_tier")
        if fallback_id is None:
            break
        fallback = tiers.get(fallback_id)
        require(fallback is not None,
                f"{current['id']}: unknown fallback tier")
        require(fallback["min_total_ram_mb"] < current["min_total_ram_mb"],
                f"{current['id']}: fallback tier must require less RAM")
        current = fallback
    return result


def tier_candidate_roles(
        catalog: dict[str, Any], tier_id: str) -> dict[str, str]:
    roles: dict[str, str] = {}
    for tier in tier_chain(catalog, tier_id):
        candidates = [
            (tier["text_model"], "text_model"),
            (tier["media_model"], "media_model"),
            (tier["tts_model"], "tts_model"),
            *((item, "asr_candidate") for item in tier["asr_candidates"]),
        ]
        for model_id, role in candidates:
            previous = roles.setdefault(model_id, role)
            require(previous == role,
                    f"{model_id}: fallback chain changes the model role")
    return roles


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
                "subtitle_rendering_enabled": False,
                "subtitle_burn_in_allowed": False,
            },
            "video export must be a non-rendering explicit embedded-track MP4 copy")

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
        if model.get("family") == "gemma4":
            require(model.get("license_url") == "https://ai.google.dev/gemma/apache_2"
                    and model.get("license_spdx") == "Apache-2.0",
                    f"{model['id']}: Gemma 4 must use its Apache-2.0 model license")
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
        if model.get("family") == "gemma4":
            require(packaged_license == {
                        "filename": "LICENSE.Apache-2.0.txt",
                        "size_bytes": 11357,
                        "sha256": ("c71d239df91726fc519c6eb72d318ec65820627232b2f796"
                                   "219e87dcf35d0ab4"),
                        "soong_license_kinds": [
                            "SPDX-license-identifier-Apache-2.0"],
                    },
                    f"{model['id']}: Gemma 4 must package its Apache-2.0 license")
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
                    and isinstance(reference.get("size_bytes"), int)
                    and reference["size_bytes"] > 0
                    and re.fullmatch(r"[0-9a-f]{64}",
                                     str(reference.get("sha256", ""))) is not None,
                    f"{model['id']}: reference artifact needs HTTPS URL, size, and digest")
        if model.get("family") == "gemma4":
            variant = "E2B" if "-e2b-" in model["id"] else (
                "E4B" if "-e4b-" in model["id"] else None)
            expected_repository = (f"litert-community/gemma-4-{variant}-it-litert-lm"
                                   if variant is not None else None)
            expected_digest = {
                "E2B": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                "E4B": "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            }.get(variant)
            require(reference == {
                        "url": (f"https://huggingface.co/{expected_repository}/resolve/main/"
                                f"gemma-4-{variant}-it.litertlm"),
                        "size_bytes": {
                            "E2B": 2588147712,
                            "E4B": 3659530240,
                        }.get(variant),
                        "sha256": expected_digest,
                    },
                    f"{model['id']}: Gemma 4 must use the pinned LiteRT-LM artifact")
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
                require(isinstance(model.get("sample_rate_hz"), int)
                        and model["sample_rate_hz"] > 0,
                        f"{model['id']}: TTS bundle needs an explicit sample rate")

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
        tier_chain(catalog, tier["id"])

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
            and suite["suite_version"] == 4,
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
            and {
                "en_wer", "es_wer", "live_non_final_partial_rate",
                "live_final_endpoint_rate", "en_language_detection_rate",
                "es_language_detection_rate",
                "p95_partial_latency_ms", "p95_final_latency_ms",
                "p95_endpoint_delay_ms", "p95_first_partial_source_span_ms",
            }
            <= {gate["metric"] for gate in profiles["asr_candidate"]},
            "benchmark suite must gate bilingual ASR quality and live cadence")
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
        require(isinstance(device_names, list) and len(device_names) == 1
                and len(device_names) == len(set(device_names))
                and all(re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", str(item))
                        for item in device_names),
                f"{profile['id']}: exactly one device codename is required")
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
        benchmark_roles = tier_candidate_roles(catalog, profile["catalog_tier"])
        tier_ids = set(benchmark_roles)
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
        evidence_build_fingerprints: set[str] = set()
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
            evidence_fingerprint = evidence["build_fingerprint_sha256"]
            require(isinstance(evidence_fingerprint, str)
                    and re.fullmatch(r"[0-9a-f]{64}", evidence_fingerprint)
                    is not None,
                    f"{profile['id']}: evidence build fingerprint is invalid")
            evidence_build_fingerprints.add(evidence_fingerprint)
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
        require(len(evidence_build_fingerprints) == 1,
                f"{profile['id']}: evidence must bind one build fingerprint")
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
        admitted_roles = {benchmark_roles[model_id] for model_id in admitted_ids}
        coverage = suite["required_role_coverage"]
        require(set(coverage["all"]) <= admitted_roles
                and set(coverage["at_least_one"]).intersection(admitted_roles),
                f"{profile['id']}: supported profile lacks text/media/TTS/ASR coverage")
    for device in catalog["known_devices"]:
        codename = device.get("codename")
        if codename is None:
            continue
        profile = profiles_by_device.get(codename)
        require(profile is not None
                and profile["catalog_tier"] == device["expected_tier"]
                and profile["min_total_ram_mb"] <= device["ram_mb"]
                <= profile["max_total_ram_mb"],
                f"{device['marketing_name']}: known device lacks a matching admission profile")
        if device.get("build_lane") is None:
            require(profile["status"] == "benchmark_pending"
                    and not profile["admitted_models"]
                    and not profile["evidence"],
                    f"{device['marketing_name']}: catalog-only device must remain research-only")


def validate_patch_series_file(root: Path, series_name: str) -> None:
    series = load_json(root / "patches" / series_name)
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


def validate_patch_series(root: Path) -> None:
    validate_patch_series_file(root, "series.json")
    validate_patch_series_file(root, "pixel9a-series.json")


def validate_default_dialer_overlay(root: Path) -> None:
    common_product = (root / "products" / "aios_common.mk").read_text(
        encoding="utf-8"
    )
    defaults_root = root / "overlays" / "frameworkdefaults"
    defaults_build = (defaults_root / "Android.bp").read_text(encoding="utf-8")
    defaults_manifest = (defaults_root / "AndroidManifest.xml").read_text(
        encoding="utf-8"
    )
    defaults_config = (defaults_root / "res" / "values" / "config.xml").read_text(
        encoding="utf-8"
    )
    require("AiosFrameworkDefaultsOverlay" in common_product,
            "the product must include the AIOS framework-defaults overlay")
    require('runtime_resource_overlay {' in defaults_build
            and 'name: "AiosFrameworkDefaultsOverlay"' in defaults_build
            and 'certificate: "platform"' in defaults_build
            and "product_specific: true" in defaults_build,
            "the framework-defaults overlay must be a platform-signed product RRO")
    require('android:targetPackage="android"' in defaults_manifest
            and 'android:isStatic="true"' in defaults_manifest
            and 'android:priority="1000"' in defaults_manifest,
            "the framework-defaults overlay must statically target android")
    require(defaults_config.count('name="config_defaultDialer"') == 1
            and ">com.aios.phone</string>" in defaults_config,
            "fresh AIOS users must receive AIOS Phone as the configured dialer")
    developer_manifest = (root / "apps" / "developerdefaults" /
                          "AndroidManifest.xml").read_text(encoding="utf-8")
    developer_receiver = (root / "apps" / "developerdefaults" / "src" / "com" /
                          "aios" / "developerdefaults" /
                          "DeveloperDefaultsReceiver.java").read_text(encoding="utf-8")
    developer_policy = (root / "apps" / "developerdefaults" / "src" / "com" /
                        "aios" / "developerdefaults" /
                        "DeveloperDefaultsPolicy.java").read_text(encoding="utf-8")
    developer_test = (root / "apps" / "developerdefaults" / "tests" / "src" /
                      "com" / "aios" / "developerdefaults" /
                      "DeveloperDefaultsPolicyTest.java").read_text(encoding="utf-8")
    developer_build = (root / "apps" / "developerdefaults" /
                       "Android.bp").read_text(encoding="utf-8")
    debug_provisioner = (root / "apps" / "developerdefaults" / "src" / "com" /
                         "aios" / "developerdefaults" /
                         "DebugInstantProvisioner.java").read_text(encoding="utf-8")
    debug_policy = (root / "apps" / "developerdefaults" / "src" / "com" /
                    "aios" / "developerdefaults" /
                    "DebugProvisioningPolicy.java").read_text(encoding="utf-8")
    debug_test = (root / "apps" / "developerdefaults" / "tests" / "src" / "com" /
                  "aios" / "developerdefaults" /
                  "DebugProvisioningPolicyTest.java").read_text(encoding="utf-8")
    debug_resources = (root / "apps" / "developerdefaults" / "res" / "values" /
                       "config.xml").read_text(encoding="utf-8")
    debug_generator = (root / "tools" /
                       "configure_debug_provisioning.py").read_text(encoding="utf-8")
    require("AIOS_ENABLE_DEVELOPER_DEFAULTS ?=" in common_product
            and "$(filter userdebug eng,$(TARGET_BUILD_VARIANT))" in common_product
            and "AIOS_ENABLE_DEVELOPER_DEFAULTS=true is forbidden" in common_product
            and "PRODUCT_PACKAGES += AiosDeveloperDefaults" in common_product
            and "ro.aios.developer_defaults=true" in common_product
            and "ro.aios.developer_defaults=false" in common_product
            and "ro.adb.secure" not in common_product,
            "developer defaults must be an authenticated-ADB debug-only build flag")
    require('name: "AiosDeveloperDefaults"' in developer_build
            and 'sdk_version: "system_current"' in developer_build
            and "platform_apis: true" not in developer_build
            and "privileged: true" in developer_build
            and "product_specific: true" in developer_build
            and "android.permission.WRITE_SECURE_SETTINGS" in developer_manifest
            and 'android:directBootAware="true"' in developer_manifest
            and 'android:name="com.aios.developer_defaults"' in developer_manifest
            and "Build.TYPE" in developer_receiver
            and "ENABLED_METADATA" in developer_receiver
            and "getBoolean(ENABLED_METADATA, false)" in developer_receiver
            and "Settings.Global.DEVELOPMENT_SETTINGS_ENABLED" in developer_receiver
            and "Settings.Global.ADB_ENABLED" in developer_receiver
            and '"userdebug".equals(buildType)' in developer_policy
            and '"eng".equals(buildType)' in developer_policy
            and "requiresDebuggableBuildAndExplicitProductFlag" in developer_test,
            "developer defaults app must double-gate secure settings at runtime and in tests")
    require("AIOS_ENABLE_INSTANT_PROVISIONING ?=" in common_product
            and "generated/debugprovisioning/Android.bp" in common_product
            and "AIOS_ENABLE_INSTANT_PROVISIONING=true is forbidden" in common_product
            and "PRODUCT_PACKAGES += AiosDebugProvisioningOverlay" in common_product
            and "ro.aios.instant_provisioning=true" in common_product
            and "ro.aios.instant_provisioning=false" in common_product,
            "instant provisioning must require a generated debug-only resource overlay")
    require("android.permission.NETWORK_SETTINGS" in developer_manifest
            and "android.permission.CHANGE_COMPONENT_ENABLED_STATE" in developer_manifest
            and "Settings.Global.DEVICE_PROVISIONED" in debug_provisioner
            and "Settings.Secure.USER_SETUP_COMPLETE" in debug_provisioner
            and "setLocationEnabledForUser(true" in debug_provisioner
            and 'NETWORK_LOCATION_SETTING = "network_location"' in debug_provisioner
            and 'GEOCODER_SETTING = "geocoder"' in debug_provisioner
            and 'WIFI_SCAN_ALWAYS_SETTING = "wifi_scan_always_enabled"'
            in debug_provisioner
            and "WifiManager.AddNetworkResult" in debug_provisioner
            and "addNetworkPrivileged" in debug_provisioner
            and "DebugProvisioningPolicy.shouldApply" in debug_provisioner
            and "developerDefaultsAllowed" in debug_policy
            and "requiresEveryDebugAndCredentialGate" in debug_test,
            "debug provisioning must gate onboarding, location, and privileged Wi-Fi seeding")
    require('<bool name="debug_instant_provisioning">false</bool>' in debug_resources
            and '<string name="debug_wifi_ssid" translatable="false"></string>'
            in debug_resources
            and '<string name="debug_wifi_psk" translatable="false"></string>'
            in debug_resources
            and "os.environ.get" in debug_generator
            and "refusing to overwrite" in debug_generator
            and "generated/debugprovisioning/" in
            (root / ".gitignore").read_text(encoding="utf-8"),
            "Wi-Fi credentials must enter only through a non-overwriting gitignored overlay")
    boot_make = (root / "assets" / "bootanimation" /
                 "Android.mk").read_text(encoding="utf-8")
    boot_builder = (root / "tools" /
                    "build_boot_animation.py").read_text(encoding="utf-8")
    require("AIOS_ENABLE_BOOT_ANIMATION ?= true" in common_product
            and "PRODUCT_PACKAGES += aios_bootanimation" in common_product
            and "LOCAL_MODULE := aios_bootanimation" in boot_make
            and "$(TARGET_OUT_PRODUCT)/media" in boot_make
            and "ZIP_STORED" in boot_builder
            and "WIDTH = 1080" in boot_builder
            and "HEIGHT = 2424" in boot_builder,
            "AIOS boot animation must be optional, product-scoped, and deterministically generated")
def validate_aosp_overlay(root: Path) -> None:
    required_files = [
        "Android.bp",
        "AndroidProducts.mk",
        "products/aios_common.mk",
        "products/aios_tegu.mk",
        "products/aios_cf_x86_64_phone.mk",
        "products/aios_sdk_phone_x86_64.mk",
        "products/aios_gsi_arm64.mk",
        "config/aosp_lanes.json",
        "patches/pixel9a-series.json",
        "tools/check_aosp_manifest.py",
        "tools/refresh_aosp_tracking.py",
        "tools/capture_build_evidence.py",
        "tools/package_pixel_dev_image.py",
        "tools/package_pixel_ota.py",
        "tools/apply_pixel_ota.py",
        "tools/validate_build_version.py",
        "tools/capture_pixel_aios_update.py",
        "tools/capture_pixel_aios_merge.py",
        "tools/exercise_pixel_rollback.py",
        "tools/flash_pixel_dev_image.py",
        "tools/capture_pixel_aios_boot.py",
        "tools/capture_cuttlefish_boot_evidence.py",
        "tools/capture_avd_boot_evidence.py",
        "tools/check_gsi_preflight.py",
        "tools/validate_pixel9a_gsi_boot_evidence.py",
        "scripts/refresh-aosp-integration.sh",
        "scripts/capture-aosp-lock.sh",
        "scripts/build-aosp-lane.sh",
        "scripts/build-aosp-modules.sh",
        "scripts/test-aosp-modules.sh",
        "scripts/install-cuttlefish-host.sh",
        "scripts/device-inventory.ps1",
        "scripts/pixel9a-gsi-preflight.ps1",
        "scripts/start-pixel9a-dsu.ps1",
        "scripts/capture-pixel9a-gsi-boot.ps1",
        "scripts/capture-realtime-smoke.ps1",
        "scripts/AiosRuntimeDiagnostics.psm1",
        "scripts/test-runtime-diagnostic-parser.ps1",
        "scripts/capture-physical-call.ps1",
        "docs/cuttlefish-bringup.md",
        "docs/emulator-bringup.md",
        "docs/gsi-bringup.md",
        "permissions/privapp-permissions-aios.xml",
        "apps/developerdefaults/Android.bp",
        "apps/developerdefaults/AndroidManifest.xml",
        "apps/developerdefaults/res/values/config.xml",
        "apps/developerdefaults/src/com/aios/developerdefaults/DebugInstantProvisioner.java",
        "apps/developerdefaults/src/com/aios/developerdefaults/DebugProvisioningPolicy.java",
        "apps/developerdefaults/src/com/aios/developerdefaults/DeveloperDefaultsPolicy.java",
        "apps/developerdefaults/src/com/aios/developerdefaults/DeveloperDefaultsReceiver.java",
        "apps/developerdefaults/tests/src/com/aios/developerdefaults/DebugProvisioningPolicyTest.java",
        "apps/developerdefaults/tests/src/com/aios/developerdefaults/DeveloperDefaultsPolicyTest.java",
        "assets/branding/README.md",
        "assets/branding/aios-boot-emblem-master.png",
        "assets/branding/aios-boot-wordmark-master.png",
        "assets/branding/aios-ui-knot.png",
        "assets/bootanimation/Android.mk",
        "assets/bootanimation/bootanimation.zip",
        "tools/build_boot_animation.py",
        "tools/configure_debug_provisioning.py",
        "tests/test_boot_animation.py",
        "tests/test_configure_debug_provisioning.py",
        "docs/development-defaults.md",
        "docs/boot-animation.md",
        "docs/visual-branding.md",
        "overlays/frameworkdefaults/Android.bp",
        "overlays/frameworkdefaults/AndroidManifest.xml",
        "overlays/frameworkdefaults/res/values/config.xml",
        "overlays/frameworkbranding/Android.bp",
        "overlays/frameworkbranding/AndroidManifest.xml",
        "overlays/frameworkbranding/res/values/strings.xml",
        "overlays/settingsbranding/Android.bp",
        "overlays/settingsbranding/AndroidManifest.xml",
        "overlays/settingsbranding/res/values/strings.xml",
        "overlays/setupwizardbranding/Android.bp",
        "overlays/setupwizardbranding/AndroidManifest.xml",
        "overlays/setupwizardbranding/res/values/strings.xml",
        "overlays/setupwizardbranding/res/drawable-nodpi/grapheneos_icon.png",
        "apps/phone/Android.bp",
        "apps/phone/AndroidManifest.xml",
        "apps/phone/src/com/aios/phone/DirectBootPreferencePolicy.kt",
        "apps/phone/tests/src/com/aios/phone/DirectBootPreferencePolicyTest.kt",
        "apps/phone/tests/src/com/aios/phone/intelligence/PendingAiAnswerGateTest.kt",
        "apps/phone/tests/src/com/aios/phone/intelligence/PhoneServiceRebindPolicyTest.kt",
        "apps/phone/tests/src/com/aios/phone/intelligence/ServiceGenerationRevisionGateTest.kt",
        "apps/phone/tests/src/com/aios/phone/intelligence/EmergencyProcessingGateTest.kt",
        "apps/phone/tests/src/com/aios/phone/model/AssistantCallContractTest.kt",
        "apps/phone/tests/src/com/aios/phone/model/AssistantPolicySemanticsTest.kt",
        "apps/phone/tests/src/com/aios/phone/model/CallRiskContractTest.kt",
        "apps/phone/tests/src/com/aios/phone/model/TranscriptTimelineReducerTest.kt",
        "apps/phone/tests/src/com/aios/phone/context/CallEventContractTest.kt",
        "apps/phone/tests/src/com/aios/phone/telecom/CallSelectionPolicyTest.kt",
        "apps/phone/src/com/aios/phone/PhoneRuntime.kt",
        "apps/phone/src/com/aios/phone/context/CallEventContract.kt",
        "apps/phone/src/com/aios/phone/context/CallEventContextClient.kt",
        "apps/phone/src/com/aios/phone/context/ResilientCommunicationContextBinding.kt",
        "apps/phone/src/com/aios/phone/data/CallHistoryRepository.kt",
        "apps/phone/src/com/aios/phone/data/VoicemailRepository.kt",
        "apps/phone/src/com/aios/phone/model/PhoneContract.kt",
        "apps/phone/src/com/aios/phone/model/AssistantCallContract.kt",
        "apps/phone/src/com/aios/phone/model/AssistantPolicySemantics.kt",
        "apps/phone/src/com/aios/phone/model/CallRiskContract.kt",
        "apps/phone/src/com/aios/phone/model/TranscriptUiState.kt",
        "apps/phone/src/com/aios/phone/model/TranscriptTimelineReducer.kt",
        "apps/phone/src/com/aios/phone/telecom/CallRegistry.kt",
        "apps/phone/src/com/aios/phone/telecom/CallSelectionPolicy.kt",
        "apps/phone/src/com/aios/phone/telecom/AiosInCallService.kt",
        "apps/phone/src/com/aios/phone/telecom/ProximityLockController.kt",
        "apps/phone/src/com/aios/phone/telecom/RttSessionController.kt",
        "apps/phone/src/com/aios/phone/telecom/VoicemailPlaybackController.kt",
        "apps/phone/src/com/aios/phone/notifications/CallNotificationCoordinator.kt",
        "apps/phone/src/com/aios/phone/notifications/CallActionReceiver.kt",
        "apps/phone/src/com/aios/phone/intelligence/CallAssistantClient.kt",
        "apps/phone/src/com/aios/phone/intelligence/PhoneServiceRebindPolicy.kt",
        "apps/phone/src/com/aios/phone/intelligence/ServiceGenerationRevisionGate.kt",
        "apps/phone/src/com/aios/phone/intelligence/EmergencyProcessingGate.kt",
        "apps/phone/src/com/aios/phone/intelligence/PendingAiAnswerGate.kt",
        "apps/phone/src/com/aios/phone/ui/InCallActivity.kt",
        "apps/phone/src/com/aios/phone/ui/screens/PhoneScreens.kt",
        "apps/phone/src/com/aios/phone/ui/theme/PhoneTheme.kt",
        "apps/phone/res/xml/data_extraction_rules.xml",
        "apps/messaging/Android.bp",
        "apps/messaging/AndroidManifest.xml",
        "apps/messaging/res/xml/data_extraction_rules.xml",
        "apps/messaging/src/com/aios/messaging/AiosMessagingApplication.kt",
        "apps/messaging/src/com/aios/messaging/MessagingRuntime.kt",
        "apps/messaging/src/com/aios/messaging/model/MessagingContract.kt",
        "apps/messaging/src/com/aios/messaging/model/MessagePolicy.kt",
        "apps/messaging/src/com/aios/messaging/model/SubscriptionSelectionPolicy.kt",
        "apps/messaging/src/com/aios/messaging/data/MessagingRepository.kt",
        "apps/messaging/src/com/aios/messaging/context/CommunicationContextClient.kt",
        "apps/messaging/src/com/aios/messaging/context/ResilientCommunicationContextBinding.kt",
        "apps/messaging/src/com/aios/messaging/context/MessagingServiceRebindPolicy.kt",
        "apps/messaging/src/com/aios/messaging/context/LatestOperationQueue.kt",
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
        "apps/messaging/tests/src/com/aios/messaging/context/MessagingServiceRebindPolicyTest.kt",
        "apps/messaging/tests/src/com/aios/messaging/context/LatestOperationQueueTest.kt",
        "apps/messaging/tests/src/com/aios/messaging/mms/MmsOperationPolicyTest.kt",
        "patches/0002-framework-mms-aios-visibility.patch",
        "patches/0003-build-make-configurable-gsi-size.patch",
        "docs/mms-transport.md",
        "preview/messagingcheck/build.gradle.kts",
        "preview/messagingcheck/src/main/java/com/aios/messaging/mms/platform/MmsTransportFactory.kt",
        "preview/messagingcheck/src/debug/AndroidManifest.xml",
        "preview/messagingcheck/src/debug/kotlin/com/aios/messaging/smoke/EmulatorMessagingFixtureActivity.kt",
        "preview/emulatorcontrol/build.gradle.kts",
        "preview/emulatorcontrol/src/main/java/com/aios/tools/emulatorcontrol/EmulatorControlMain.java",
        "preview/emulatorcontrol/src/test/java/com/aios/tools/emulatorcontrol/EmulatorControlMainTest.java",
        "preview/callcontextcheck/build.gradle.kts",
        "preview/callcontextcheck/src/debug/AndroidManifest.xml",
        "preview/callcontextcheck/src/debug/java/com/aios/contextintelligence/ContextLifecycleSmokeActivity.java",
        "scripts/emulator-context-lifecycle-smoke.ps1",
        "preview/callservicecheck/build.gradle.kts",
        "preview/callservicecheck/src/main/java/com/aios/callintelligence/CallProductProperties.java",
        "preview/callservicecheck/src/debug/AndroidManifest.xml",
        "preview/callservicecheck/src/debug/java/com/aios/callintelligence/CallRetentionSmokeActivity.java",
        "preview/modelservicecheck/build.gradle.kts",
        "preview/modelbenchmarkcheck/build.gradle.kts",
        "preview/modelbenchmarkcheck/src/androidTest/AndroidManifest.xml",
        "preview/modelbenchmarkcheck/src/main/AndroidManifest.xml",
        "preview/modelservicecheck/src/main/java/com/aios/modelbroker/BrokerProductProperties.java",
        "preview/modelservicecheck/src/debug/AndroidManifest.xml",
        "preview/modelservicecheck/src/debug/java/com/aios/modelbroker/ModelAdmissionSmokeActivity.java",
        "scripts/emulator-model-admission-smoke.ps1",
        "preview/mediascancheck/build.gradle.kts",
        "services/contextintelligence/Android.bp",
        "services/contextintelligence/AndroidManifest.xml",
        "services/contextintelligence/res/drawable/ic_context_intelligence.xml",
        "services/contextintelligence/res/xml/data_extraction_rules.xml",
        "services/contextintelligence/aidl/com/aios/context/ICommunicationContext.aidl",
        "services/contextintelligence/aidl/com/aios/context/ConversationIdentity.aidl",
        "services/contextintelligence/aidl/com/aios/context/ContextDocument.aidl",
        "services/contextintelligence/aidl/com/aios/context/ContextSnippet.aidl",
        "services/contextintelligence/api/com/aios/context/ConversationIdentity.java",
        "services/contextintelligence/api/com/aios/context/ContextDocument.java",
        "services/contextintelligence/api/com/aios/context/ContextSnippet.java",
        "services/contextintelligence/src/com/aios/contextintelligence/CommunicationContextService.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextExpiryPolicy.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextRetentionAlarm.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextRetentionClock.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextStore.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextEmbeddingClient.java",
        "services/contextintelligence/src/com/aios/contextintelligence/EmbeddingCapabilityPolicy.java",
        "services/contextintelligence/src/com/aios/contextintelligence/EmbeddingModelIdentity.java",
        "services/contextintelligence/src/com/aios/contextintelligence/HybridRetrievalRanker.java",
        "services/contextintelligence/src/com/aios/contextintelligence/QuantizedEmbedding.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextPolicy.java",
        "services/contextintelligence/src/com/aios/contextintelligence/ContextSourceScope.java",
        "services/contextintelligence/src/com/aios/contextintelligence/RevisionGate.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/ContextExpiryPolicyTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/ContextPolicyTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/ContextSourceScopeTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/ContextStoreQueryTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/EmbeddingCapabilityPolicyTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/EmbeddingModelIdentityTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/HybridRetrievalRankerTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/QuantizedEmbeddingTest.java",
        "services/contextintelligence/tests/src/com/aios/contextintelligence/RevisionGateTest.java",
        "docs/communications-context.md",
        "docs/compose-dialer-decision.md",
        "preview/README.md",
        "preview/prodcheck/build.gradle.kts",
        "preview/telecomsmoke/build.gradle.kts",
        "preview/telecomsmoke/src/debug/AndroidManifest.xml",
        "preview/telecomsmoke/src/debug/kotlin/com/aios/phone/smoke/EmulatorCallActivity.kt",
        "preview/telecomsmoke/src/debug/kotlin/com/aios/phone/smoke/EmulatorConnectionService.kt",
        "preview/callassistantsmoke/build.gradle.kts",
        "preview/callassistantsmoke/src/main/AndroidManifest.xml",
        "preview/callassistantsmoke/src/main/java/com/aios/callintelligence/EmulatorGuard.java",
        "preview/callassistantsmoke/src/main/java/com/aios/callintelligence/EmulatorCallAssistantControlActivity.java",
        "preview/callassistantsmoke/src/main/java/com/aios/callintelligence/EmulatorCallAssistantService.java",
        "scripts/emulator-telecom-smoke.ps1",
        "scripts/emulator-messaging-smoke.ps1",
        "scripts/emulator-call-retention-smoke.ps1",
        "scripts/emulator-media-smoke.ps1",
        "preview/mediascancheck/src/debug/AndroidManifest.xml",
        "preview/mediascancheck/src/debug/java/com/aios/mediaintelligence/MediaObserverRecoverySmokeActivity.java",
        "services/modelbroker/Android.bp",
        "services/modelbroker/AndroidManifest.xml",
        "services/modelbroker/aidl/com/aios/model/IAiosModelService.aidl",
        "services/modelbroker/aidl/com/aios/model/ModelRequest.aidl",
        "services/modelbroker/aidl/com/aios/model/InferenceResult.aidl",
        "services/modelbroker/aidl/com/aios/model/ModelCapability.aidl",
        "services/modelbroker/src/com/aios/modelbroker/ModelBrokerService.java",
        "services/modelbroker/src/com/aios/modelbroker/ArtifactVerifier.java",
        "services/modelbroker/src/com/aios/modelbroker/AuthorizedClientPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/BuildFingerprintPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/CatalogPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/CatalogTierPlanner.java",
        "services/modelbroker/src/com/aios/modelbroker/DeviceModelAdmission.java",
        "services/modelbroker/src/com/aios/modelbroker/EmbeddingRequestPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/EmbeddingResultPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/BrokerProductProperties.java",
        "services/modelbroker/src/com/aios/modelbroker/BrokerCapacityPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/BrokerState.java",
        "services/modelbroker/src/com/aios/modelbroker/PolicyFileReader.java",
        "services/modelbroker/src/com/aios/modelbroker/RuntimeCandidatePolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/RuntimePressurePolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/RuntimeAdapter.java",
        "services/modelbroker/src/com/aios/modelbroker/RemoteRuntimeAdapter.java",
        "services/modelbroker/src/com/aios/modelbroker/RuntimeRebindPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/RuntimeRegistry.java",
        "services/modelbroker/src/com/aios/modelbroker/SessionController.java",
        "services/modelbroker/src/com/aios/modelbroker/SessionCapacityPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/SessionArbiter.java",
        "services/modelbroker/src/com/aios/modelbroker/SessionChunkPolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/SessionDeadlinePolicy.java",
        "services/modelbroker/src/com/aios/modelbroker/SessionDeadlineQueue.java",
        "services/modelbroker/src/com/aios/modelbroker/CallActivityLeaseTracker.java",
        "services/modelbroker/src/com/aios/modelbroker/VerifiedArtifact.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/SessionArbiterTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/SessionCapacityPolicyTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/SessionChunkPolicyTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/SessionDeadlinePolicyTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/SessionDeadlineQueueTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/CallActivityLeaseTrackerTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/BuildFingerprintPolicyTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/EmbeddingRequestPolicyTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/EmbeddingResultPolicyTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/EmbeddingWorkClassTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/PolicyFileReaderTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/CatalogTierPlannerTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/RuntimeCandidatePolicyTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/RuntimePressurePolicyTest.java",
        "services/modelbroker/tests/src/com/aios/modelbroker/RuntimeRebindPolicyTest.java",
        "runtime/common/Android.bp",
        "runtime/common/src/main/java/com/aios/runtime/common/RuntimeMemoryTrimPolicy.java",
        "runtime/common/tests/src/com/aios/runtime/common/RuntimeMemoryTrimPolicyTest.java",
        "preview/runtimecommoncheck/build.gradle.kts",
        "preview/runtimeprovidercheck/build.gradle.kts",
        "preview/runtimeprovidercheck/src/main/AndroidManifest.xml",
        "preview/runtimeprovidercheck/src/main/java/com/aios/runtime/smoke/RuntimeProviderSmokeActivity.java",
        "preview/runtimeprovidercheck/src/main/java/com/aios/runtime/smoke/WhisperProviderSmokeActivity.java",
        "scripts/emulator-runtime-provider-smoke.ps1",
        "scripts/bootstrap-emulator-asr-fixtures.ps1",
        "scripts/emulator-whisper-provider-smoke.ps1",
        "preview/whisperpolicycheck/build.gradle.kts",
        "tools/bootstrap_reference_model.py",
        "tools/record_model_acceptance.py",
        "tools/capture_model_pack_evidence.py",
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
        "runtime/whisperprovider/app/gradle.lockfile",
        "runtime/whisperprovider/gradle/verification-metadata.xml",
        "runtime/whisperprovider/app/src/main/AndroidManifest.xml",
        "runtime/whisperprovider/app/src/main/cpp/CMakeLists.txt",
        "runtime/whisperprovider/app/src/main/cpp/aios_whisper_jni.cpp",
        "runtime/whisperprovider/app/src/main/java/com/aios/runtime/whispercpp/NativeWhisper.kt",
        "runtime/whisperprovider/app/src/main/java/com/aios/runtime/whispercpp/WhisperRuntimeService.kt",
        "runtime/whisperprovider/app/src/main/java/com/aios/runtime/whispercpp/DecodeCancellationFence.java",
        "runtime/whisperprovider/app/src/main/java/com/aios/runtime/whispercpp/Pcm16EnergyVad.java",
        "runtime/whisperprovider/app/src/main/java/com/aios/runtime/whispercpp/StreamingVadState.java",
        "runtime/whisperprovider/app/src/main/java/com/aios/runtime/whispercpp/StreamingAsrTurnAccumulator.java",
        "runtime/whisperprovider/app/src/test/java/com/aios/runtime/whispercpp/DecodeCancellationFenceTest.java",
        "runtime/whisperprovider/app/src/test/java/com/aios/runtime/whispercpp/Pcm16EnergyVadTest.java",
        "runtime/whisperprovider/app/src/test/java/com/aios/runtime/whispercpp/StreamingVadStateTest.java",
        "runtime/whisperprovider/app/src/test/java/com/aios/runtime/whispercpp/StreamingAsrTurnAccumulatorTest.java",
        "runtime/whisperprovider/bootstrap_source.sh",
        "runtime/whisperprovider/bootstrap_dependency_locks.sh",
        "runtime/whisperprovider/build_provider.sh",
        "docs/asr-runtime.md",
        "runtime/ttsprovider/settings.gradle.kts",
        "runtime/ttsprovider/build.gradle.kts",
        "runtime/ttsprovider/app/build.gradle.kts",
        "runtime/ttsprovider/app/gradle.lockfile",
        "runtime/ttsprovider/gradle/verification-metadata.xml",
        "runtime/ttsprovider/app/src/main/AndroidManifest.xml",
        "runtime/ttsprovider/app/src/main/java/com/aios/runtime/sherpatts/SherpaTtsRuntimeService.kt",
        "runtime/ttsprovider/bootstrap_artifacts.sh",
        "runtime/ttsprovider/bootstrap_dependency_locks.sh",
        "runtime/ttsprovider/build_provider.sh",
        "preview/runtimeprovidercheck/src/main/java/com/aios/runtime/smoke/TtsProviderSmokeActivity.java",
        "scripts/bootstrap-emulator-tts-fixtures.ps1",
        "scripts/emulator-tts-provider-smoke.ps1",
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
        "services/callintelligence/Android.bp",
        "services/callintelligence/aidl/com/aios/call/IAiosCallIntelligence.aidl",
        "services/callintelligence/aidl/com/aios/call/ICallIntelligenceListener.aidl",
        "services/callintelligence/aidl/com/aios/call/CallAssistantState.aidl",
        "services/callintelligence/aidl/com/aios/call/CallRiskAssessment.aidl",
        "services/callintelligence/aidl/com/aios/call/CallAssistantPolicy.aidl",
        "services/callintelligence/aidl/com/aios/call/IncomingCallContext.aidl",
        "services/callintelligence/src/com/aios/callintelligence/AnswerDelayPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/AssistantHandlingTracker.java",
        "services/callintelligence/src/com/aios/callintelligence/AssistantGreetingPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/AssistantAudioIdentityGate.java",
        "services/callintelligence/src/com/aios/callintelligence/AssistantTurnQueue.java",
        "services/callintelligence/src/com/aios/callintelligence/CallPolicyEngine.java",
        "services/callintelligence/src/com/aios/callintelligence/CallProductProperties.java",
        "services/callintelligence/src/com/aios/callintelligence/CallCommunicationContextClient.java",
        "services/callintelligence/src/com/aios/callintelligence/ResilientCommunicationContextBinding.java",
        "services/callintelligence/src/com/aios/callintelligence/CallContextAccumulator.java",
        "services/callintelligence/src/com/aios/callintelligence/CallStatusLogPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/IncrementalCallerTranscript.java",
        "services/callintelligence/src/com/aios/callintelligence/TranscriptRevisionGate.java",
        "services/callintelligence/src/com/aios/callintelligence/TranscriptContextRecovery.java",
        "services/callintelligence/src/com/aios/callintelligence/CallTranscriptRevisionClock.java",
        "services/callintelligence/src/com/aios/callintelligence/PcmTranscriptTimeline.java",
        "services/callintelligence/src/com/aios/callintelligence/CallRequestIdentityTracker.java",
        "services/callintelligence/src/com/aios/callintelligence/CallerHistoryPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/CallerHistoryConversationPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/CallerHistorySourcePolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/CallArtifactRetention.java",
        "services/callintelligence/src/com/aios/callintelligence/CallArtifactStore.java",
        "services/callintelligence/src/com/aios/callintelligence/RetentionClock.java",
        "services/callintelligence/src/com/aios/callintelligence/TelephonyAudioCapture.java",
        "services/callintelligence/src/com/aios/callintelligence/RequiredCaptureGate.java",
        "services/callintelligence/src/com/aios/callintelligence/CaptureLivenessGate.java",
        "services/callintelligence/src/com/aios/callintelligence/CallerAudioUplink.java",
        "services/callintelligence/src/com/aios/callintelligence/Pcm16MonoToStereo48k.java",
        "services/callintelligence/src/com/aios/callintelligence/PriorContextFormatter.java",
        "services/callintelligence/src/com/aios/callintelligence/SpeechSynthesisBrokerClient.java",
        "services/callintelligence/src/com/aios/callintelligence/SpeechSynthesisStatusPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/SpeechTerminalGate.java",
        "services/callintelligence/src/com/aios/callintelligence/AsrBrokerClient.java",
        "services/callintelligence/src/com/aios/callintelligence/ServiceRebindPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/ResilientModelBrokerBinding.java",
        "services/callintelligence/src/com/aios/callintelligence/SpamRiskEngine.java",
        "services/callintelligence/src/com/aios/callintelligence/RiskAssessmentTracker.java",
        "services/callintelligence/src/com/aios/callintelligence/CallClassifierClient.java",
        "services/callintelligence/src/com/aios/callintelligence/ReceptionistDialogueClient.java",
        "services/callintelligence/src/com/aios/callintelligence/ReceptionistReplyPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/ReceptionistRequestTracker.java",
        "services/callintelligence/src/com/aios/callintelligence/ReceptionistStatusPolicy.java",
        "services/callintelligence/src/com/aios/callintelligence/TelecomCallPresenceTracker.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/SpamRiskEngineTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/AssistantHandlingTrackerTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/AssistantGreetingPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/AssistantAudioIdentityGateTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/RiskAssessmentTrackerTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/AssistantTurnQueueTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/ReceptionistReplyPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/ReceptionistRequestTrackerTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/ReceptionistStatusPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/AnswerDelayPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallArtifactRetentionTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallContextAccumulatorTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallStatusLogPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/IncrementalCallerTranscriptTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/TranscriptRevisionGateTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/TranscriptContextRecoveryTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallTranscriptRevisionClockTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/PcmTranscriptTimelineTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallRequestIdentityTrackerTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallerHistoryPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallerHistoryConversationPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CallerHistorySourcePolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/Pcm16MonoToStereo48kTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/SpeechSynthesisStatusPolicyTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/SpeechTerminalGateTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/RequiredCaptureGateTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/CaptureLivenessGateTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/PriorContextFormatterTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/TelecomCallPresenceTrackerTest.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/ServiceRebindPolicyTest.java",
        "services/callintelligence/src/com/aios/callintelligence/ResilientFanoutOutputStream.java",
        "services/callintelligence/tests/src/com/aios/callintelligence/ResilientFanoutOutputStreamTest.java",
        "docs/dialer-integration.md",
        "docs/caller-audio-uplink.md",
        "services/mediaintelligence/Android.bp",
        "services/mediaintelligence/AndroidManifest.xml",
        "services/mediaintelligence/res/drawable/ic_media_intelligence.xml",
        "services/mediaintelligence/res/xml/data_extraction_rules.xml",
        "services/mediaintelligence/aidl/com/aios/media/IMediaContextAssociation.aidl",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaAssociationPolicy.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/ContextServiceRebindPolicy.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/ResilientContextServiceBinding.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaContextAssociationService.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaContextProjection.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaObserverService.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaGenerationBaselinePolicy.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaGenerationReconciler.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaGenerationScanner.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaLivenessReconciler.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaLivenessScanner.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaInferenceJobService.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaWorkPolicy.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/ContextServiceRebindPolicyTest.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaConstraintProbe.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaInputPolicy.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaInferenceAttempt.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaJobCommitFence.java",
        "services/mediaintelligence/src/com/aios/mediaintelligence/MediaJobRunGate.java",
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
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaInferenceAttemptTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaJobCommitFenceTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaJobRunGateTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/VideoStoryboardPlanTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/VideoTranscriptTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/VideoEmbeddedMetadataTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/VideoExportRecoveryPolicyTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaTimingTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaGenerationReconcilerTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaGenerationBaselinePolicyTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaLivenessReconcilerTest.java",
        "services/mediaintelligence/tests/src/com/aios/mediaintelligence/MediaAssociationPolicyTest.java",
        "permissions/default-permissions-aios.xml",
        "docs/media-metadata-schema.md",
        "docs/media-performance.md",
    ]
    for relative in required_files:
        require((root / relative).is_file(), f"missing AOSP overlay file: {relative}")

    product_app_blueprints = (
        "apps/phone/Android.bp",
        "apps/messaging/Android.bp",
        "services/callintelligence/Android.bp",
        "services/contextintelligence/Android.bp",
        "services/mediaintelligence/Android.bp",
        "services/modelbroker/Android.bp",
        "benchmarks/modeladmission/Android.bp",
    )
    for relative in product_app_blueprints:
        blueprint = (root / relative).read_text(encoding="utf-8")
        require('sdk_version: "system_current"' in blueprint
                and 'min_sdk_version: "35"' in blueprint
                and "platform_apis: true" not in blueprint,
                f"product modules must use the stable system SDK: {relative}")
    for blueprint_path in root.rglob("Android.bp"):
        blueprint = blueprint_path.read_text(encoding="utf-8")
        require("platform_apis: true" not in blueprint,
                "AIOS source modules may not bypass the product-partition SDK "
                f"contract: {blueprint_path.relative_to(root)}")

    tegu_product = (root / "products" / "aios_tegu.mk").read_text(encoding="utf-8")
    envsetup_commands = (root / "aios_tegu" /
                         "cmds-for-envsetup.sh").read_text(encoding="utf-8")
    require("vendor/google_devices/tegu/tegu.mk" in tegu_product,
            "Pixel 9a product must inherit the generated pinned tegu product")
    require("vendor/aios/products/aios_common.mk" in tegu_product,
            "Pixel 9a product must inherit common AIOS additions")
    require('source "$aios_tegu_env"' in envsetup_commands
            and 'export BUILD_ID_aios_tegu="$BUILD_ID_tegu"'
            in envsetup_commands,
            "AIOS Pixel product must inherit the generated tegu BUILD_ID")
    cuttlefish_product = (root / "products" /
                          "aios_cf_x86_64_phone.mk").read_text(encoding="utf-8")
    require("device/google/cuttlefish/vsoc_x86_64/phone/aosp_cf.mk"
            in cuttlefish_product
            and "vendor/aios/products/aios_common.mk" in cuttlefish_product
            and "PRODUCT_NAME := aios_cf_x86_64_phone" in cuttlefish_product,
            "Android-latest must have an additive Cuttlefish integration product")
    emulator_product = (root / "products" /
                        "aios_sdk_phone_x86_64.mk").read_text(encoding="utf-8")
    require("device/generic/goldfish/64bitonly/product/sdk_phone64_x86_64.mk"
            in emulator_product
            and "vendor/aios/products/aios_common.mk" in emulator_product
            and "PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := relaxed"
            in emulator_product
            and "BOARD_EMULATOR_DYNAMIC_PARTITIONS_SIZE := 2147483648"
            in emulator_product
            and "PRODUCT_NAME := aios_sdk_phone_x86_64" in emulator_product
            and "PRODUCT_DEVICE := emu64x" in emulator_product,
            "Android Emulator must have an additive x86-64 AIOS product with "
            "sufficient virtual dynamic-partition space")
    gsi_product = (root / "products" / "aios_gsi_arm64.mk").read_text(
        encoding="utf-8"
    )
    require("device/generic/common/gsi_arm64.mk" in gsi_product
            and "vendor/aios/products/aios_common.mk" in gsi_product
            and "PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := relaxed"
            in gsi_product
            and "BOARD_GSI_DYNAMIC_PARTITIONS_SIZE := 6442450944"
            in gsi_product
            and "BOARD_SUPER_PARTITION_SIZE := 6450839552" in gsi_product
            and "PRODUCT_NAME := aios_gsi_arm64" in gsi_product
            and "PRODUCT_DEVICE := generic_arm64" in gsi_product,
            "AIOS must have an additive ARM64 Generic System Image product "
            "with room for the catalog-pinned on-device model stack")
    android_products = (root / "AndroidProducts.mk").read_text(encoding="utf-8")
    require("aios_tegu-cur-userdebug" in android_products
            and "aios_cf_x86_64_phone-aosp_current-userdebug" in android_products
            and "aios_sdk_phone_x86_64-aosp_current-userdebug" in android_products
            and "aios_gsi_arm64-aosp_current-userdebug" in android_products,
            "AIOS must expose Pixel, Cuttlefish, Emulator, and ARM64 GSI targets")
    bootstrap_script = (root / "scripts" / "bootstrap-aosp.sh").read_text(
        encoding="utf-8"
    )
    require("android_latest_integration|android_avd_integration|android_gsi_arm64"
            in bootstrap_script,
            "AOSP bootstrap must admit every moving latest-release lane")
    inventory_script = (root / "scripts" / "device-inventory.ps1").read_text(
        encoding="utf-8"
    )
    require("[string]$Serial" in inventory_script
            and "[string]$Output" in inventory_script
            and "Resolve-AdbExecutable" in inventory_script
            and "ANDROID_SDK_ROOT" in inventory_script
            and '"Android\\Sdk"' in inventory_script
            and "pass -AdbPath" in inventory_script
            and '$ErrorActionPreference = "Continue"' in inventory_script
            and '"shell", "cat", "/proc/meminfo"' in inventory_script
            and 'Could not identify exactly one MemTotal row'
            in inventory_script
            and '"shell", "sh", "-c"' not in inventory_script
            and "adb -s $Serial" in inventory_script
            and '"ro.product.cpu.abilist64"' in inventory_script
            and '"ro.vendor.api_level"' in inventory_script
            and '"ro.product.first_api_level"' in inventory_script
            and '"ro.board.api_level"' in inventory_script
            and '"ro.vendor.build.version.sdk"' in inventory_script
            and '"ro.llndk.api_level"' in inventory_script
            and "PreserveFailureOutput" in inventory_script
            and '"android.software.virtualization_framework"'
            in inventory_script
            and "virtualization_framework_feature" in inventory_script
            and "dynamic_partition_metadata" in inventory_script
            and '"ro.treble.enabled"' in inventory_script
            and '"ro.boot.dynamic_partitions"' in inventory_script
            and '"android.software.dynamic_system"' in inventory_script
            and "read_only = $true" in inventory_script
            and "unlock_attempted = $false" in inventory_script
            and "flash_attempted = $false" in inventory_script
            and "proves_gsi_compatibility = $false" in inventory_script
            and "serial_sha256 = $serialDigest" in inventory_script
            and "Refusing to overwrite existing device inventory"
            in inventory_script,
            "device inventory must be explicit, read-only, and GSI-aware")
    pixel_preflight_script = (
        root / "scripts" / "pixel9a-gsi-preflight.ps1"
    ).read_text(encoding="utf-8")
    require("[string]$Serial" in pixel_preflight_script
            and "[string]$OutputDirectory" in pixel_preflight_script
            and "outside the source repository" in pixel_preflight_script
            and "device-inventory.ps1" in pixel_preflight_script
            and "check_gsi_preflight.py" in pixel_preflight_script
            and "Only absolute Windows drive paths can be converted for WSL"
            in pixel_preflight_script
            and 'return "/mnt/$drive/$relative"' in pixel_preflight_script
            and '@("wslpath"' not in pixel_preflight_script
            and "avb-verification.json" in pixel_preflight_script
            and "dsu-payload.json" in pixel_preflight_script
            and "system-interface.json" in pixel_preflight_script
            and "--expected-device tegu" in pixel_preflight_script
            and '$Preflight.status -ne "candidate"' in pixel_preflight_script
            and '$Preflight.safe_to_flash -ne $false' in pixel_preflight_script
            and "No image was pushed, installed, flashed, or booted."
            in pixel_preflight_script,
            "Pixel 9a preflight wrapper must remain read-only and exact-image bound")
    dsu_start_script = (
        root / "scripts" / "start-pixel9a-dsu.ps1"
    ).read_text(encoding="utf-8")
    require("[switch]$IUnderstandThisStartsDsu" in dsu_start_script
            and "Inventory, build, AVB, DSU, or system-interface evidence changed after preflight"
            in dsu_start_script
            and "SystemInterfacePath" in dsu_start_script
            and "Connected serial does not match" in dsu_start_script
            and "Connected phone changed since inventory" in dsu_start_script
            and "current free space" in dsu_start_script
            and "Get-FileHash" in dsu_start_script
            and "StagingDirectory" in dsu_start_script
            and "payload size plus 1 GiB headroom" in dsu_start_script
            and "Copy-Item -LiteralPath $PayloadPath" in dsu_start_script
            and "Refusing to clean an unbounded DSU staging path"
            in dsu_start_script
            and "Removed the generated local DSU staging copy."
            in dsu_start_script
            and "android.software.dynamic_system" in dsu_start_script
            and "com.android.dynsystem/com.android.dynsystem.VerificationActivity"
            in dsu_start_script
            and "android.os.image.action.START_INSTALL" in dsu_start_script
            and '"KEY_SYSTEM_SIZE"' in dsu_start_script
            and '"KEY_USERDATA_SIZE"' in dsu_start_script
            and "did not unlock, fastboot-flash, disable AVB, or reboot"
            in dsu_start_script
            and "fastboot.exe" not in dsu_start_script
            and "adb reboot" not in dsu_start_script,
            "Pixel 9a DSU start must be explicit, exact-evidence bound, and non-flashing")
    pixel_boot_capture = (
        root / "scripts" / "capture-pixel9a-gsi-boot.ps1"
    ).read_text(encoding="utf-8")
    require('kind = "pixel9a_gsi_dsu_first_boot"' in pixel_boot_capture
            and "outside the source repository" in pixel_boot_capture
            and '"sys.boot_completed"' in pixel_boot_capture
            and '"ro.gsid.image_running"' in pixel_boot_capture
            and '$BuildRecord.build_fingerprint' in pixel_boot_capture
            and '"com.aios.phone"' in pixel_boot_capture
            and '"com.aios.messaging"' in pixel_boot_capture
            and '"com.aios.callintelligence"' in pixel_boot_capture
            and '"com.aios.contextintelligence"' in pixel_boot_capture
            and '"com.aios.mediaintelligence"' in pixel_boot_capture
            and '"com.aios.modelbroker"' in pixel_boot_capture
            and '"android.app.role.DIALER"' in pixel_boot_capture
            and '"android:string/config_defaultDialer"' in pixel_boot_capture
            and '"sha256sum"' in pixel_boot_capture
            and 'every_evidenced_system_artifact_verified = $true'
            in pixel_boot_capture
            and 'system_interface_evidence_sha256' in pixel_boot_capture
            and 'proves_gsi_compatibility = $true' in pixel_boot_capture
            and 'proves_boot_first_boot = $true' in pixel_boot_capture
            and 'proves_physical_runtime_gate = $false' in pixel_boot_capture
            and 'proves_telephony_gate = $false' in pixel_boot_capture
            and 'proves_factory_restore = $false' in pixel_boot_capture,
            "Pixel 9a first-boot capture must bind exact artifacts without overclaiming runtime gates")
    pixel_boot_validator = (
        root / "tools" / "validate_pixel9a_gsi_boot_evidence.py"
    ).read_text(encoding="utf-8")
    require('"pixel9a_gsi_dsu_first_boot"' in pixel_boot_validator
            and '"sys.boot_completed"' in pixel_boot_validator
            and '"ro.gsid.image_running"' in pixel_boot_validator
            and 'EXPECTED_CHECKS' in pixel_boot_validator
            and 'installed AIOS artifacts do not match' in pixel_boot_validator
            and 'evidence.get("proves_gsi_compatibility") is True'
            in pixel_boot_validator
            and 'evidence.get("proves_physical_runtime_gate") is False'
            in pixel_boot_validator
            and 'evidence.get("proves_telephony_gate") is False'
            in pixel_boot_validator
            and 'evidence.get("proves_factory_restore") is False'
            in pixel_boot_validator,
            "Pixel 9a boot evidence validator must bind inputs, artifacts, and proof boundaries")
    gsi_preflight = (root / "tools" / "check_gsi_preflight.py").read_text(
        encoding="utf-8"
    )
    require('build.get("lane") != "android_gsi_arm64"' in gsi_preflight
            and 'build.get("artifact_layout") != "gsi_system_product"'
            in gsi_preflight
            and '"pvmfw.img", "system.img", "vbmeta.img"' in gsi_preflight
            and "EXPECTED_PVMFW_PUBLIC_KEY_SHA1" in gsi_preflight
            and '"pvmfw_required": avf_advertised' in gsi_preflight
            and '"arm64_userspace"' in gsi_preflight
            and '"treble_enabled"' in gsi_preflight
            and '"dynamic_partitions"' in gsi_preflight
            and '"system_patch_not_older"' in gsi_preflight
            and '"safe_to_flash": False' in gsi_preflight
            and '"proves_gsi_compatibility": False' in gsi_preflight
            and '"proves_physical_runtime_gate": False' in gsi_preflight
            and "refusing to overwrite GSI preflight" in gsi_preflight,
            "GSI preflight must bind artifacts and remain non-authorizing")
    avd_evidence_source = (root / "tools" /
                           "capture_avd_boot_evidence.py").read_text(
        encoding="utf-8"
    )
    require('SERIAL_PATTERN = re.compile(r"emulator-[0-9]+")'
            in avd_evidence_source
            and 'EXPECTED_LANE = "android_avd_integration"'
            in avd_evidence_source
            and 'EXPECTED_PRODUCT = "aios_sdk_phone_x86_64"'
            in avd_evidence_source
            and '"ro.kernel.qemu", "1"' in avd_evidence_source
            and '"sys.boot_completed", "1"' in avd_evidence_source
            and '"ro.build.type", "userdebug"' in avd_evidence_source
            and '"ro.debuggable", "1"' in avd_evidence_source
            and 'build_evidence_sha256' in avd_evidence_source
            and 'path.startswith("/product/priv-app/")' in avd_evidence_source
            and '"proves_physical_runtime_gate": False'
            in avd_evidence_source
            and "os.replace(temporary, path)" in avd_evidence_source,
            "AVD first-boot evidence must bind identity, packages, and virtual-only scope")
    for package_name in (
            "com.aios.callintelligence", "com.aios.contextintelligence",
            "com.aios.mediaintelligence", "com.aios.messaging",
            "com.aios.modelbroker", "com.aios.phone"):
        require(package_name in avd_evidence_source,
                f"AVD boot evidence must require {package_name}")
    cuttlefish_evidence_source = (root / "tools" /
                                  "capture_cuttlefish_boot_evidence.py").read_text(
        encoding="utf-8"
    )
    require('EXPECTED_LANE = "android_latest_integration"'
            in cuttlefish_evidence_source
            and 'EXPECTED_KIND = "virtual_integration"'
            in cuttlefish_evidence_source
            and 'EXPECTED_PRODUCT = "aios_cf_x86_64_phone"'
            in cuttlefish_evidence_source
            and 'EXPECTED_TARGET_DEVICE = "vsoc_x86_64"'
            in cuttlefish_evidence_source
            and '"ro.product.device", EXPECTED_TARGET_DEVICE'
            in cuttlefish_evidence_source
            and '"sys.boot_completed", "1"' in cuttlefish_evidence_source
            and '"ro.build.type", "userdebug"' in cuttlefish_evidence_source
            and '"ro.debuggable", "1"' in cuttlefish_evidence_source
            and 'build_evidence_sha256' in cuttlefish_evidence_source
            and 'path.startswith("/product/priv-app/")'
            in cuttlefish_evidence_source
            and '"resolved_services": resolved_services'
            in cuttlefish_evidence_source
            and '"proves_physical_runtime_gate": False'
            in cuttlefish_evidence_source
            and "os.replace(temporary, path)" in cuttlefish_evidence_source,
            "Cuttlefish first-boot evidence must bind identity, packages, "
            "services, and virtual-only scope")
    for package_name in (
            "com.aios.callintelligence", "com.aios.contextintelligence",
            "com.aios.mediaintelligence", "com.aios.messaging",
            "com.aios.modelbroker", "com.aios.phone"):
        require(package_name in cuttlefish_evidence_source,
                f"Cuttlefish boot evidence must require {package_name}")
    for action in (
            "com.aios.call.CALL_INTELLIGENCE_SERVICE",
            "com.aios.context.COMMUNICATION_CONTEXT_SERVICE",
            "com.aios.model.MODEL_SERVICE"):
        require(action in cuttlefish_evidence_source,
                f"Cuttlefish boot evidence must resolve {action}")
    cuttlefish_host_setup = (root / "scripts" /
                             "install-cuttlefish-host.sh").read_text(
        encoding="utf-8"
    )
    require("if [[ \"${EUID}\" -ne 0 ]]" in cuttlefish_host_setup
            and "https://us-apt.pkg.dev/doc/repo-signing-key.gpg"
            in cuttlefish_host_setup
            and "android-cuttlefish-artifacts android-cuttlefish main"
            in cuttlefish_host_setup
            and "cuttlefish-base cuttlefish-user" in cuttlefish_host_setup
            and "usermod -aG kvm,cvdnetwork,render" in cuttlefish_host_setup
            and "Restart the WSL distribution" in cuttlefish_host_setup,
            "Cuttlefish host setup must use official packages, narrow groups, "
            "and require a membership refresh")
    lock_script = (root / "scripts" / "capture-aosp-lock.sh").read_text(
        encoding="utf-8"
    )
    require("repo manifest -r" in lock_script
            and "check_aosp_manifest.py" in lock_script
            and "refresh_aosp_tracking.py" in lock_script
            and "android_avd_integration" in lock_script
            and "android_gsi_arm64" in lock_script
            and "verify-tag 2026080500" in lock_script
            and "vendor/google_devices/tegu/tegu.mk" in lock_script
            and "vendor/state/tegu.json" in lock_script
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
            and "pixel9a_tegu_hardware" in build_script
            and "pixel9a-series.json" in build_script
            and '--series "$patch_series"' in build_script
            and "target-files-package" in build_script
            and "img_from_target_files" in build_script
            and "ota_from_target_files" in build_script
            and "check_ota_package_signature" in build_script
            and "validate_build_version.py" in build_script
            and 'export BUILD_NUMBER="$build_number"' in build_script
            and 'export BUILD_DATETIME="$build_datetime"' in build_script
            and 'build_datetime_file="$out_dir/build_date.txt"' in build_script
            and 'build_status="${PIPESTATUS[0]}"' in build_script,
            "lane builds must be locked, patch-transactional, logged, and evidence-bound")
    module_build_script = (root / "scripts" / "build-aosp-modules.sh").read_text(
        encoding="utf-8"
    )
    require("aosp_lanes.json" in module_build_script
            and "pixel9a_tegu_hardware" in module_build_script
            and "pixel9a-series.json" in module_build_script
            and "verify_patch_series.py" in module_build_script
            and "--apply" in module_build_script
            and "--revert" in module_build_script
            and "trap cleanup EXIT INT TERM" in module_build_script
            and 'm -j "$jobs" "${targets[@]}"' in module_build_script
            and "not release evidence" in module_build_script,
            "focused Soong builds must resolve lanes and transact the same patch queue")
    module_test_script = (root / "scripts" / "test-aosp-modules.sh").read_text(
        encoding="utf-8"
    )
    require("aosp_lanes.json" in module_test_script
            and "pixel9a_tegu_hardware" in module_test_script
            and "pixel9a-series.json" in module_test_script
            and "verify_patch_series.py" in module_test_script
            and "--apply" in module_test_script
            and "--revert" in module_test_script
            and "trap cleanup EXIT INT TERM" in module_test_script
            and 'atest --host "${tests[@]}"' in module_test_script,
            "Soong host tests must resolve lanes, stay host-only, and transact patches")
    build_evidence_source = (root / "tools" /
                             "capture_build_evidence.py").read_text(encoding="utf-8")
    require("installed-files-product.json" in build_evidence_source
            and "target_files_image_record" in build_evidence_source
            and "target_files_package" in build_evidence_source
            and "require_manifest_membership" in build_evidence_source
            and "installed_files_product_sha256" in build_evidence_source
            and "patch_queue_record" in build_evidence_source
            and "patch_queue_sha256" in build_evidence_source
            and "generated_device_support" in build_evidence_source
            and "vendor/state/tegu.json" in build_evidence_source
            and "def digest_tree(" in build_evidence_source
            and '"schema_version": 2' in build_evidence_source
            and '"build_incremental": build_incremental' in build_evidence_source
            and '"build_timestamp": build_timestamp' in build_evidence_source
            and "empty installed product artifact" in build_evidence_source,
            "build evidence must bind product artifacts and the review-complete patch queue")
    pixel_packager = (root / "tools" /
                      "package_pixel_dev_image.py").read_text(encoding="utf-8")
    require("img_from_target_files" in pixel_packager
            and "validate_build_input" in pixel_packager
            and "inspect_fastboot_zip" in pixel_packager
            and "def image_tool_command(" in pixel_packager
            and "sys.executable" in pixel_packager
            and "public_android_test_keys_unlocked_bootloader_only" in pixel_packager
            and "version-bootloader" in pixel_packager
            and "version-baseband" in pixel_packager
            and "contains_required_model_payloads" in pixel_packager,
            "Pixel development packaging must be evidence-bound and device-guarded")
    pixel_ota_packager = (root / "tools" /
                          "package_pixel_ota.py").read_text(encoding="utf-8")
    require("ota_from_target_files" in pixel_ota_packager
            and "inspect_target_files" in pixel_ota_packager
            and "inspect_ota_zip" in pixel_ota_packager
            and "inspect_whole_file_signature_footer" in pixel_ota_packager
            and "virtual_ab_compression" in pixel_ota_packager
            and "payload FILE_HASH" in pixel_ota_packager
            and "payload METADATA_HASH" in pixel_ota_packager
            and "post-security-patch-level" in pixel_ota_packager
            and "prebuilts" in pixel_ota_packager
            and "jdk21" in pixel_ota_packager
            and "whole_file_and_payload_verified" in pixel_ota_packager
            and "public_android_test_keys_unlocked_bootloader_only" in pixel_ota_packager
            and '"installation_performed": False' in pixel_ota_packager,
            "Pixel OTA packaging must be A/B, payload-, build-, and device-bound")
    pixel_ota_updater = (root / "tools" /
                         "apply_pixel_ota.py").read_text(encoding="utf-8")
    require("verify_ota_input" in pixel_ota_updater
            and "inspect_device" in pixel_ota_updater
            and "ota-property-files" in pixel_ota_updater
            and "copy_payload_metadata" in pixel_ota_updater
            and "update_engine_client --verify" in pixel_ota_updater
            and "update_engine_client --allocate" in pixel_ota_updater
            and "require_update_engine_applicable" in pixel_ota_updater
            and "require_update_engine_allocation" in pixel_ota_updater
            and "update_engine_client --update --follow" in pixel_ota_updater
            and "same_build" in pixel_ota_updater
            and "target_not_newer" in pixel_ota_updater
            and "security_patch_downgrade" in pixel_ota_updater
            and "APPLY-" in pixel_ota_updater
            and "--execute" in pixel_ota_updater
            and "--preflight-output" in pixel_ota_updater
            and "physical OTA evidence must remain outside source" in pixel_ota_updater
            and '"payload_applicability_verified": True' in pixel_ota_updater
            and '"payload_space_allocated": True' in pixel_ota_updater
            and "reboot_performed" in pixel_ota_updater
            and '"proves_post_update_boot": False' in pixel_ota_updater,
            "Pixel OTA application must be evidence-bound, monotonic, explicit, and non-rebooting")
    pixel_flasher = (root / "tools" /
                     "flash_pixel_dev_image.py").read_text(encoding="utf-8")
    require("verify_release_input" in pixel_flasher
            and "expected exactly fastboot device" in pixel_flasher
            and "development image requires an unlocked bootloader" in pixel_flasher
            and "version-bootloader" in pixel_flasher
            and "version-baseband" in pixel_flasher
            and "is-userspace" in pixel_flasher
            and "ERASE-{serial}-FOR-AIOS" in pixel_flasher
            and '["-w", "update"' in pixel_flasher
            and "--execute" in pixel_flasher
            and "--result-output" in pixel_flasher
            and "release_evidence_sha256" in pixel_flasher
            and "serial_sha256" in pixel_flasher
            and "proves_flash_command_passed" in pixel_flasher,
            "Pixel development flashing must fail closed before destructive execution")
    pixel_boot_capture = (root / "tools" /
                          "capture_pixel_aios_boot.py").read_text(encoding="utf-8")
    require("pixel9a_aios_full_device_first_boot" in pixel_boot_capture
            and "validate_chain" in pixel_boot_capture
            and "ro.gsid.image_running" in pixel_boot_capture
            and "ro.boot.verifiedbootstate" in pixel_boot_capture
            and "ro.boot.vbmeta.device_state" in pixel_boot_capture
            and "sys.user.0.ce_available" in pixel_boot_capture
            and "android.app.role.DIALER" in pixel_boot_capture
            and "every_evidenced_product_artifact_verified" in pixel_boot_capture
            and "proves_physical_full_device_boot" in pixel_boot_capture
            and '"proves_telephony_gate": False' in pixel_boot_capture
            and '"proves_model_inference": False' in pixel_boot_capture,
            "Pixel first-boot capture must bind the wiped flash without overclaiming runtime gates")
    pixel_update_capture = (root / "tools" /
                            "capture_pixel_aios_update.py").read_text(encoding="utf-8")
    require("pixel9a_aios_virtual_ab_post_update_boot" in pixel_update_capture
            and "validate_chain" in pixel_update_capture
            and "expected_target_slot" in pixel_update_capture
            and 'update.get("payload_applicability_verified") is not True'
            in pixel_update_capture
            and 'update.get("payload_space_allocated") is not True'
            in pixel_update_capture
            and "every_evidenced_product_artifact_verified" in pixel_update_capture
            and '"proves_post_update_boot": True' in pixel_update_capture
            and '"proves_slot_switch": True' in pixel_update_capture
            and '"proves_merge_completed": False' in pixel_update_capture
            and '"proves_telephony_gate": False' in pixel_update_capture,
            "Pixel OTA boot capture must bind the exact slot and payload without overclaiming merge or runtime gates")
    pixel_merge_capture = (root / "tools" /
                           "capture_pixel_aios_merge.py").read_text(encoding="utf-8")
    require("pixel9a_aios_virtual_ab_merge" in pixel_merge_capture
            and "validate_post_update" in pixel_merge_capture
            and "snapshotctl" in pixel_merge_capture
            and "bootctl" in pixel_merge_capture
            and 'snapshot["update_state"] != "none"' in pixel_merge_capture
            and 'snapshot["snapshot_count"] != 0' in pixel_merge_capture
            and 'boot_merge_status != "none"' in pixel_merge_capture
            and "is-slot-marked-successful" in pixel_merge_capture
            and "physical merge evidence must remain outside source" in pixel_merge_capture
            and '"proves_merge_completed": True' in pixel_merge_capture
            and '"proves_rollback": False' in pixel_merge_capture
            and '"proves_telephony_gate": False' in pixel_merge_capture,
            "Pixel merge capture must bind the post-update slot and prove all snapshot state is gone")
    pixel_rollback = (root / "tools" /
                      "exercise_pixel_rollback.py").read_text(encoding="utf-8")
    require("pixel9a_aios_virtual_ab_rollback_prepare" in pixel_rollback
            and "pixel9a_aios_virtual_ab_rollback" in pixel_rollback
            and "set-active-boot-slot" in pixel_rollback
            and "ROLLBACK-" in pixel_rollback
            and 'snapshot["update_state"] != "unverified"' in pixel_rollback
            and 'boot_status != "snapshotted"' in pixel_rollback
            and '"fresh_update_required": True' in pixel_rollback
            and "reboot_performed" in pixel_rollback
            and "physical rollback evidence must remain outside source" in pixel_rollback
            and '"proves_source_slot_boot": True' in pixel_rollback
            and '"proves_post_update_boot": False' in pixel_rollback
            and '"proves_merge_completed": False' in pixel_rollback
            and '"proves_rollback": True' in pixel_rollback
            and '"proves_telephony_gate": False' in pixel_rollback,
            "Pixel rollback must cancel only an unverified pre-merge update without overclaiming")

    common_product = (root / "products" / "aios_common.mk").read_text(
        encoding="utf-8"
    )
    require("AiosPhone" in common_product and "AiosPhoneAssistant" not in common_product,
            "the product must include the full AIOS Phone module")
    validate_default_dialer_overlay(root)
    tegu_product = (root / "products" / "aios_tegu.mk").read_text(
        encoding="utf-8"
    )
    require(all(module in tegu_product for module in (
                "AiosFrameworkBrandingOverlay",
                "AiosSettingsBrandingOverlay",
                "AiosSetupWizardBrandingOverlay")),
            "the Pixel product must install every visual-only AIOS branding overlay")
    setup_branding_manifest = (root / "overlays" / "setupwizardbranding" /
                               "AndroidManifest.xml").read_text(encoding="utf-8")
    setup_branding_values = (root / "overlays" / "setupwizardbranding" / "res" /
                             "values" / "strings.xml").read_text(encoding="utf-8")
    settings_branding_manifest = (root / "overlays" / "settingsbranding" /
                                  "AndroidManifest.xml").read_text(encoding="utf-8")
    settings_branding_values = (root / "overlays" / "settingsbranding" / "res" /
                                "values" / "strings.xml").read_text(encoding="utf-8")
    framework_branding_manifest = (root / "overlays" / "frameworkbranding" /
                                   "AndroidManifest.xml").read_text(encoding="utf-8")
    framework_branding_values = (root / "overlays" / "frameworkbranding" / "res" /
                                 "values" / "strings.xml").read_text(encoding="utf-8")
    require('android:targetPackage="app.grapheneos.setupwizard"'
            in setup_branding_manifest
            and 'android:isStatic="true"' in setup_branding_manifest
            and "Welcome to AIOS" in setup_branding_values
            and "Private, on-device intelligence" in setup_branding_values
            and "GrapheneOS" not in setup_branding_values,
            "Setup Wizard must retain its internal target while showing only AIOS copy")
    require('android:targetPackage="com.android.settings"'
            in settings_branding_manifest
            and "AIOS exploit protections" in settings_branding_values
            and "AIOS remains installed" in settings_branding_values
            and "Privacy proxy" in settings_branding_values
            and "GrapheneOS" not in settings_branding_values,
            "Settings must replace visible upstream branding without mislabeling endpoints")
    require('android:targetPackage="android"' in framework_branding_manifest
            and "AIOS keeps 32-bit app support disabled" in framework_branding_values
            and "GrapheneOS" not in framework_branding_values,
            "framework notices must show AIOS branding on the Pixel lane")
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
    benchmark_math = (root / "benchmarks" / "modeladmission" / "common" / "com" /
                      "aios" / "modelbenchmark" / "BenchmarkMath.java").read_text(
                          encoding="utf-8")
    benchmark_math_test = (root / "benchmarks" / "modeladmission" / "hosttests" /
                           "com" / "aios" / "modelbenchmark" /
                           "BenchmarkMathTest.java").read_text(encoding="utf-8")
    benchmark_compile_check = (root / "preview" / "modelbenchmarkcheck" /
                               "build.gradle.kts").read_text(encoding="utf-8")
    benchmark_preview_settings = (root / "preview" / "settings.gradle.kts").read_text(
        encoding="utf-8")
    realtime_capture = (root / "scripts" /
                        "capture-realtime-smoke.ps1").read_text(encoding="utf-8")
    runtime_diagnostic_parser = (root / "scripts" /
                                 "AiosRuntimeDiagnostics.psm1").read_text(
                                     encoding="utf-8")
    runtime_diagnostic_test = (root / "scripts" /
                               "test-runtime-diagnostic-parser.ps1").read_text(
                                   encoding="utf-8")
    physical_call_capture = (root / "scripts" /
                             "capture-physical-call.ps1").read_text(encoding="utf-8")
    require("runRealtimeSmoke" in benchmark_source
            and 'runBenchmark(1, false, true, "realtime_smoke")' in benchmark_source
            and "runAudioRealtimeSmoke" in benchmark_source
            and 'runBenchmark(1, false, false, "audio_realtime_smoke")'
            in benchmark_source
            and "runSingleModelDiagnostic" in benchmark_source
            and '"single_model_diagnostic"' in benchmark_source
            and "DIAGNOSTIC_TIMEOUT_MILLIS = 45_000L" in benchmark_source
            and "AiosModelDiagnostic" in benchmark_source
            and "ttsFirstAudioMillis" in benchmark_source
            and "long firstOutput" in benchmark_source
            and "values.get(name).available" in benchmark_source
            and "READINESS_TIMEOUT capability=" in benchmark_source
            and "ADMISSION_RUNS_PER_LANGUAGE = 5" in benchmark_source
            and "pixel_aios_realtime_model_smoke" in realtime_capture
            and "pixel_aios_audio_realtime_smoke" in realtime_capture
            and "pixel_aios_single_model_diagnostic" in realtime_capture
            and 'ValidateSet("full", "audio", "single")' in realtime_capture
            and "diagnostic_log" in realtime_capture
            and "AiosRuntimeDiagnostics.psm1" in realtime_capture
            and "runtime_phase_diagnostics" in realtime_capture
            and "AiosWhisperNative" in realtime_capture
            and "Get-AiosRuntimeRssSnapshot" in realtime_capture
            and "peak_total_aios_runtime_rss_mb" in realtime_capture
            and "instrumentation_runtime_pss_available" in realtime_capture
            and "contains_aios_low_memory_kill" in realtime_capture
            and "details.time_to_first_audio_ms" in realtime_capture
            and "ExpectedRoleCounts = @(4, 5)" in realtime_capture
            and "$expectedRoleCounts -notcontains" in realtime_capture
            and "details.dimensions -ne 256" in realtime_capture
            and "details.cross_language_ordering_valid" in realtime_capture
            and "physical realtime smoke refuses QEMU targets" in realtime_capture
            and "admission_evidence = $false" in realtime_capture
            and "#$testMethod" in realtime_capture
            and "runAudioRealtimeSmoke" in realtime_capture
            and "refusing to overwrite" in realtime_capture,
            "physical realtime smoke must be focused, non-overwriting, and non-admission")
    require("ConvertFrom-AiosRuntimeDiagnosticLog" in runtime_diagnostic_parser
            and "first_audio_after_text_ms" in runtime_diagnostic_parser
            and "first_token_after_ready_ms" in runtime_diagnostic_parser
            and "decode_elapsed_total_ms" in runtime_diagnostic_parser
            and "Export-ModuleMember" in runtime_diagnostic_parser
            and "Runtime diagnostic parser test passed" in runtime_diagnostic_test
            and "first_audio_after_engine_ready_ms" in runtime_diagnostic_test,
            "physical diagnostics must parse TTS, Gemma, and Whisper phases reproducibly")
    require('include("com/aios/modelbenchmark/**/*.java")'
            in benchmark_compile_check
            and 'include(":modelbenchmarkcheck")' in benchmark_preview_settings
            and '../../benchmarks/modeladmission/tests/src'
            in benchmark_compile_check
            and '../../benchmarks/modeladmission/common'
            in benchmark_compile_check
            and '../../benchmarks/modeladmission/hosttests'
            in benchmark_compile_check
            and '../../services/modelbroker/aidl' in benchmark_compile_check
            and 'manifest.srcFile("src/main/AndroidManifest.xml")'
            in benchmark_compile_check
            and 'manifest.srcFile("src/androidTest/AndroidManifest.xml")'
            in benchmark_compile_check
            and 'getByName("test")' in benchmark_compile_check
            and 'getByName("androidTest")' in benchmark_compile_check
            and 'testImplementation("junit:junit:' in benchmark_compile_check
            and 'androidx.test.ext:junit:' in benchmark_compile_check
            and 'androidx.test:runner:' in benchmark_compile_check,
            "the physical model benchmark needs a public-SDK instrumentation compile check")
    require("physical call capture refuses emulator serials" in physical_call_capture
            and '"ro.boot.qemu"' in physical_call_capture
            and '"ro.kernel.qemu"' in physical_call_capture
            and '"persist.aios.debug.call_uplink_test"' in physical_call_capture
            and '"ro.aios.call_uplink_validated"' in physical_call_capture
            and "AiosWhisperRuntime:I" in physical_call_capture
            and "AiosLiteRtLmRuntime:I" in physical_call_capture
            and "AiosTtsRuntime:I" in physical_call_capture
            and "lowmemorykiller:I" in physical_call_capture
            and "OutOfMemory" in physical_call_capture
            and "post-thermal.txt" in physical_call_capture
            and "memory-samples.log" in physical_call_capture
            and 'Save-AiosFilteredAdb -Name "pre-processes.txt"'
            in physical_call_capture
            and 'Save-AiosFilteredAdb -Name "post-processes.txt"'
            in physical_call_capture
            and 'Save-AiosFilteredAdb -Name "post-cpuinfo.txt"'
            in physical_call_capture
            and '-Pattern "com\\.aios\\."' in physical_call_capture
            and "build_fingerprint_sha256" in physical_call_capture
            and "serial_sha256" in physical_call_capture
            and "admission_evidence = $false" in physical_call_capture
            and "refusing to overwrite" in physical_call_capture,
            "physical call capture must be guarded, privacy-minimized, and diagnose latency/OOM")
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
            and "ASR_PACING_FRAME_MILLIS = 100" in benchmark_source
            and "request.deadlineElapsedRealtimeMillis = Long.MAX_VALUE"
            in benchmark_source
            and "writeAsrPcm" in benchmark_source
            and 'audioFormat(ASR_SAMPLE_RATE, "downlink"), false'
            in benchmark_source
            and '"streaming_asr", "call_rx", "und", 0' in benchmark_source
            and '"live_non_final_partial_rate"' in benchmark_source
            and '"live_final_endpoint_rate"' in benchmark_source
            and '"en_language_detection_rate"' in benchmark_source
            and '"es_language_detection_rate"' in benchmark_source
            and "finalChunkLanguage" in benchmark_source
            and '"p95_endpoint_delay_ms"' in benchmark_source
            and '"p95_first_partial_source_span_ms"' in benchmark_source
            and "invokeEmbedding(" in benchmark_source
            and "request.embeddingTask = embeddingTask" in benchmark_source
            and '"context_query"' in benchmark_source
            and '"context_background"' in benchmark_source
            and "embeddingCapability.selectedModelDigest" in benchmark_source
            and '"cross_language_ordering_valid"' in benchmark_source
            and '"positive_cosine"' in benchmark_source
            and '"negative_cosine"' in benchmark_source
            and "BenchmarkMath.isNormalizedEmbedding" in benchmark_source
            and "BenchmarkMath.cosine" in benchmark_source
            and "static boolean isNormalizedEmbedding" in benchmark_math
            and "static double cosine" in benchmark_math
            and "embeddingShapeNormAndCosineAreFailClosed" in benchmark_math_test
            and "aios_measurements_base64" in benchmark_source,
            "model benchmark must cover image/video, optional bilingual embeddings, and source-paced live ASR Broker paths")
    benchmark_capture = (root / "scripts" /
                         "capture-model-benchmark.ps1").read_text(encoding="utf-8")
    require('"config\\model_benchmark_suite.json"' in benchmark_capture
            and "$measurementDocument.suite_version -ne $suite.suite_version"
            in benchmark_capture
            and "$measurementDocument.suite_version -ne 1" not in benchmark_capture
            and "#runAdmissionBenchmark" in benchmark_capture
            and "Get-AiosRuntimeRssMb" in benchmark_capture
            and "$hostPeakRuntimeRssMb" in benchmark_capture
            and "$result.metrics.peak_rss_mb = [int]$hostPeakRuntimeRssMb"
            in benchmark_capture
            and "model admission requires physical hardware" in benchmark_capture
            and "refusing to overwrite" in benchmark_capture,
            "device benchmark capture must follow the suite and host-sample runtime RSS")
    phone_manifest = (root / "apps" / "phone" / "AndroidManifest.xml").read_text(
        encoding="utf-8"
    )
    phone_build = (root / "apps" / "phone" / "Android.bp").read_text(
        encoding="utf-8"
    )
    phone_manifest_root = ET.parse(root / "apps" / "phone" /
                                   "AndroidManifest.xml").getroot()
    android_name = "{http://schemas.android.com/apk/res/android}name"
    android_direct_boot = "{http://schemas.android.com/apk/res/android}directBootAware"
    phone_application = phone_manifest_root.find("application")
    phone_components = {
        component.get(android_name): component
        for kind in ("activity", "service", "receiver")
        for component in phone_manifest_root.findall(f"application/{kind}")
    }
    require('android:name="android.telecom.IN_CALL_SERVICE_UI"' in phone_manifest
            and 'android:name="android.telecom.IN_CALL_SERVICE_RINGING"' in phone_manifest
            and 'android:permission="android.permission.BIND_INCALL_SERVICE"'
            in phone_manifest,
            "AIOS Phone must fully declare its InCallService UI and ringing role")
    require(phone_manifest.count('android.intent.action.DIAL') == 2
            and 'android:scheme="tel"' in phone_manifest,
            "AIOS Phone must handle ACTION_DIAL with and without a tel URI")
    direct_boot_components = (
        ".ui.InCallActivity",
        ".telecom.AiosInCallService",
        ".notifications.CallActionReceiver",
    )
    credential_components = (".ui.MainActivity", ".ui.SettingsActivity")
    require(phone_application is not None
            and phone_application.get(android_name) == ".AiosPhoneApplication"
            and phone_application.get(android_direct_boot) == "true"
            and all(phone_components.get(name) is not None
                    and phone_components[name].get(android_direct_boot) == "true"
                    for name in direct_boot_components)
            and all(phone_components.get(name) is not None
                    and phone_components[name].get(android_direct_boot) == "false"
                    for name in credential_components),
            "locked-boot call UI, Telecom service, and actions must be direct-boot aware")

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
    message_policy = (messaging_root / "src" / "com" / "aios" / "messaging" /
                      "model" / "MessagePolicy.kt").read_text(encoding="utf-8")
    message_policy_test = (messaging_root / "tests" / "src" / "com" / "aios" /
                           "messaging" / "model" / "MessagePolicyTest.kt").read_text(
                               encoding="utf-8")
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
    mms_transcoder = (mms_platform /
                      "mms" / "platform" / "MmsPhotoTranscoder.kt").read_text(
                          encoding="utf-8")
    mms_factory = (mms_platform /
                   "mms" / "platform" / "MmsTransportFactory.kt").read_text(
                       encoding="utf-8")
    mms_policy = (messaging_root / "src" / "com" / "aios" / "messaging" /
                  "mms" / "MmsOperationPolicy.kt").read_text(encoding="utf-8")
    mms_contract = (messaging_root / "src" / "com" / "aios" / "messaging" /
                    "mms" / "MmsTransport.kt").read_text(encoding="utf-8")
    messaging_bp = (messaging_root / "Android.bp").read_text(encoding="utf-8")
    messaging_check_build = (
        root / "preview" / "messagingcheck" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    messaging_check_factory = (
        root / "preview" / "messagingcheck" / "src" / "main" / "java" / "com" /
        "aios" / "messaging" / "mms" / "platform" / "MmsTransportFactory.kt"
    ).read_text(encoding="utf-8")
    messaging_smoke_manifest = (
        root / "preview" / "messagingcheck" / "src" / "debug" /
        "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    messaging_smoke_fixture = (
        root / "preview" / "messagingcheck" / "src" / "debug" / "kotlin" /
        "com" / "aios" / "messaging" / "smoke" /
        "EmulatorMessagingFixtureActivity.kt"
    ).read_text(encoding="utf-8")
    messaging_smoke_script = (
        root / "scripts" / "emulator-messaging-smoke.ps1"
    ).read_text(encoding="utf-8")
    emulator_control_build = (
        root / "preview" / "emulatorcontrol" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    emulator_control_source = (
        root / "preview" / "emulatorcontrol" / "src" / "main" / "java" /
        "com" / "aios" / "tools" / "emulatorcontrol" /
        "EmulatorControlMain.java"
    ).read_text(encoding="utf-8")
    emulator_control_test = (
        root / "preview" / "emulatorcontrol" / "src" / "test" / "java" /
        "com" / "aios" / "tools" / "emulatorcontrol" /
        "EmulatorControlMainTest.java"
    ).read_text(encoding="utf-8")
    messaging_preview_settings = (
        root / "preview" / "settings.gradle.kts"
    ).read_text(encoding="utf-8")
    messaging_extraction_rules = (
        messaging_root / "res" / "xml" / "data_extraction_rules.xml"
    ).read_text(encoding="utf-8")
    context_client = (messaging_root / "src" / "com" / "aios" / "messaging" /
                       "context" / "CommunicationContextClient.kt").read_text(
                           encoding="utf-8")
    messaging_context_binding = (
        messaging_root / "src" / "com" / "aios" / "messaging" / "context" /
        "ResilientCommunicationContextBinding.kt"
    ).read_text(encoding="utf-8")
    media_context_client = (messaging_root / "src" / "com" / "aios" / "messaging" /
                            "context" / "MediaContextAssociationClient.kt").read_text(
                                encoding="utf-8")
    messaging_rebind_policy = (
        messaging_root / "src" / "com" / "aios" / "messaging" / "context" /
        "MessagingServiceRebindPolicy.kt"
    ).read_text(encoding="utf-8")
    messaging_rebind_test = (
        messaging_root / "tests" / "src" / "com" / "aios" / "messaging" /
        "context" / "MessagingServiceRebindPolicyTest.kt"
    ).read_text(encoding="utf-8")
    association_queue = (
        messaging_root / "src" / "com" / "aios" / "messaging" / "context" /
        "LatestOperationQueue.kt"
    ).read_text(encoding="utf-8")
    association_queue_test = (
        messaging_root / "tests" / "src" / "com" / "aios" / "messaging" /
        "context" / "LatestOperationQueueTest.kt"
    ).read_text(encoding="utf-8")
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
    require("incomingTimestamp" in message_policy
            and "claimedAtEpochMillis.takeIf { it in 1L..received }" in message_policy
            and "MessagePolicy.incomingTimestamp(timestamp, receivedAt)"
            in messaging_runtime
            and "incomingTimestampPreservesDelayedMessages" in message_policy_test
            and "incomingTimestampReplacesInvalidOrFutureNetworkTime"
            in message_policy_test
            and "Modifier.navigationBarsPadding()" in messaging_ui,
            "Messaging must reject future PDU ordering and keep composer controls above system navigation")
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
    messaging_extraction_domains = {
        "root", "file", "database", "sharedpref", "external", "device_root",
        "device_file", "device_database", "device_sharedpref",
    }
    require('manifest.srcFile("../../apps/messaging/AndroidManifest.xml")'
            in messaging_check_build
            and 'kotlin.directories.add("../../apps/messaging/src")'
            in messaging_check_build
            and 'kotlin.directories.add("../../apps/messaging/tests/src")'
            in messaging_check_build
            and 'res.directories.add("../../apps/messaging/res")'
            in messaging_check_build
            and '../../services/contextintelligence/aidl' in messaging_check_build
            and '../../services/mediaintelligence/aidl' in messaging_check_build
            and 'include("com/aios/messaging/mms/platform/**/*.kt")'
            in messaging_check_build
            and '"com/aios/messaging/mms/platform/MmsTransportFactory.kt"'
            in messaging_check_build
            and '"com/aios/messaging/mms/platform/PlatformMmsTransport.kt"'
            in messaging_check_build
            and "dependsOn(stageMessagingPlatform)" in messaging_check_build
            and "MmsPhotoTranscoder" in mms_transcoder
            and "MAX_DIMENSION = 4_096" in mms_transcoder
            and "PlatformMmsTransport(context.applicationContext, listener)" in mms_factory
            and "override val admitted = false" in messaging_check_factory
            and "platform MMS unavailable" in messaging_check_factory
            and "PlatformMmsTransport" not in messaging_check_factory
            and all(messaging_extraction_rules.count(
                f'<exclude domain="{domain}" path="." />') == 2
                    for domain in messaging_extraction_domains)
            and "abortOnError" not in messaging_check_build,
            "Messaging compile-check must cover the full app and all public platform helpers while MMS transport fails closed")
    require('include(":emulatorcontrol")' in messaging_preview_settings
            and 'manifest.srcFile("src/debug/AndroidManifest.xml")'
            in messaging_check_build
            and 'kotlin.directories.add("src/debug/kotlin")'
            in messaging_check_build
            and "EmulatorMessagingFixtureActivity" in messaging_smoke_manifest
            and "Build.HARDWARE" in messaging_smoke_fixture
            and 'Regex("AIOS(?:IN|OUT)[A-F0-9]{12}")'
            in messaging_smoke_fixture
            and '"${Telephony.Sms.BODY}=?"' in messaging_smoke_fixture
            and "contentResolver.delete" in messaging_smoke_fixture
            and "emulator_loopback" in messaging_smoke_fixture
            and 'implementation("io.grpc:grpc-okhttp:1.69.1")'
            in emulator_control_build
            and 'implementation("io.grpc:grpc-stub:1.69.1")'
            in emulator_control_build
            and "android.emulation.control.EmulatorController/sendSms"
            in emulator_control_source
            and 'headers.put(AUTHORIZATION, "Bearer " + token)'
            in emulator_control_source
            and 'System.out.println("SMS_DELIVERED")' in emulator_control_source
            and "encodesCanonicalSmsMessage" in emulator_control_test
            and "decodesFailurePhoneResponseWithUnknownField" in emulator_control_test
            and "ro.kernel.qemu" in messaging_smoke_script
            and "Find-DiscoveryFile" in messaging_smoke_script
            and "Get-Process -Id" in messaging_smoke_script
            and "qemu-system-x86_64-headless" in messaging_smoke_script
            and "Get-FileHash -LiteralPath $apkPath -Algorithm SHA256"
            in messaging_smoke_script
            and "production_sms_deliver_provider_path = $true"
            in messaging_smoke_script
            and "emulator_loopback_inbox_verified = $true"
            in messaging_smoke_script
            and "synthetic_rows_removed = $providerRowsRemoved"
            in messaging_smoke_script
            and "sms_role_restored = $roleRestored" in messaging_smoke_script
            and "carrier_delivery_evidence = $false" in messaging_smoke_script
            and "physical_gate_evidence = $false" in messaging_smoke_script
            and "uninstall $package" in messaging_smoke_script,
            "Messaging emulator smoke must use authenticated modem injection, exact cleanup, and non-physical evidence")
    require(":framework-mms-shared-srcs" in messaging_bp
            and 'libs: ["unsupportedappusage"]' in messaging_bp
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
            "MMS must use AOSP PDU persistence, its compile-only annotation dependency, and a durable carrier callback lifecycle")
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
            and "clearMmsPhotos" in media_context_client
            and "LatestOperationQueue<PendingOperation>" in media_context_client
            and "catch (error: RemoteException)" in media_context_client
            and "invalidate(service)" in media_context_client
            and "activeConnection !== this" in media_context_client
            and "onBindingDied" in media_context_client
            and "onNullBinding" in media_context_client
            and "CONNECT_TIMEOUT_MILLIS = 15_000L" in media_context_client
            and "MAX_PENDING_OPERATIONS = 128" in media_context_client
            and "MAX_DELAY_MILLIS = 60_000L" in messaging_rebind_policy
            and "connectionRacingReservedRetryCancelsThatAttempt"
            in messaging_rebind_test
            and "protectedKey" in association_queue
            and "removeIfCurrent" in association_queue
            and "replacementRejectsLateCompletionFromSupersededOperation"
            in association_queue_test
            and "overflowDoesNotEvictTheOperationCurrentlyCrossingBinder"
            in association_queue_test
            and "replacementOfInFlightKeyRemainsProtectedFromLaterOverflow"
            in association_queue_test,
            "selected MMS photos must retain a crash-recoverable media-context lifecycle")
    require("indexSms" in context_client
            and "indexMms" in context_client
            and "deleteMms" in context_client
            and "deleteSource" in context_client
            and "queryRecent" in context_client
            and "QUERY_SOURCE_TYPES" in context_client
            and '"call_artifact"' in context_client
            and '"media_metadata"' in context_client,
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
    require("ResilientCommunicationContextBinding(" in context_client
            and "result.exceptionOrNull() is RemoteException" in context_client
            and "binding.invalidate(service)" in context_client
            and "submitRequest(service, request)" in context_client
            and "request.reject()" in context_client
            and "ContextConnection(++generation)" in messaging_context_binding
            and "activeConnection === connection" in messaging_context_binding
            and "connection.generation == generation" in messaging_context_binding
            and "onBindingDied" in messaging_context_binding
            and "onNullBinding" in messaging_context_binding
            and "CONNECT_TIMEOUT_MILLIS = 15_000L" in messaging_context_binding
            and '"src/com/aios/messaging/context/MessagingServiceRebindPolicy.kt"'
            in messaging_bp,
            "Messaging provider reconciliation must replace failed context bindings and complete queries")

    context_root = root / "services" / "contextintelligence"
    context_manifest = (context_root / "AndroidManifest.xml").read_text(encoding="utf-8")
    context_service = (context_root / "src" / "com" / "aios" /
                       "contextintelligence" /
                       "CommunicationContextService.java").read_text(encoding="utf-8")
    context_store = (context_root / "src" / "com" / "aios" /
                     "contextintelligence" / "ContextStore.java").read_text(
                         encoding="utf-8")
    context_text = (context_root / "src" / "com" / "aios" /
                    "contextintelligence" / "ContextText.java").read_text(
                        encoding="utf-8")
    context_policy = (context_root / "src" / "com" / "aios" /
                      "contextintelligence" / "ContextPolicy.java").read_text(
                          encoding="utf-8")
    context_source_scope = (context_root / "src" / "com" / "aios" /
                            "contextintelligence" /
                            "ContextSourceScope.java").read_text(encoding="utf-8")
    context_source_scope_test = (context_root / "tests" / "src" / "com" /
                                 "aios" / "contextintelligence" /
                                 "ContextSourceScopeTest.java").read_text(
                                     encoding="utf-8")
    context_embedding = (context_root / "src" / "com" / "aios" /
                         "contextintelligence" /
                         "QuantizedEmbedding.java").read_text(encoding="utf-8")
    context_ranker = (context_root / "src" / "com" / "aios" /
                      "contextintelligence" /
                      "HybridRetrievalRanker.java").read_text(encoding="utf-8")
    context_embedding_identity = (context_root / "src" / "com" / "aios" /
                                  "contextintelligence" /
                                  "EmbeddingModelIdentity.java").read_text(
                                      encoding="utf-8")
    context_embedding_client = (context_root / "src" / "com" / "aios" /
                                "contextintelligence" /
                                "ContextEmbeddingClient.java").read_text(
                                    encoding="utf-8")
    context_embedding_capability = (context_root / "src" / "com" / "aios" /
                                    "contextintelligence" /
                                    "EmbeddingCapabilityPolicy.java").read_text(
                                        encoding="utf-8")
    context_expiry = (context_root / "src" / "com" / "aios" /
                      "contextintelligence" / "ContextExpiryPolicy.java").read_text(
                          encoding="utf-8")
    context_alarm = (context_root / "src" / "com" / "aios" /
                     "contextintelligence" / "ContextRetentionAlarm.java").read_text(
                         encoding="utf-8")
    context_boot = (context_root / "src" / "com" / "aios" /
                    "contextintelligence" / "ContextBootReceiver.java").read_text(
                        encoding="utf-8")
    context_expiry_test = (context_root / "tests" / "src" / "com" / "aios" /
                           "contextintelligence" /
                           "ContextExpiryPolicyTest.java").read_text(encoding="utf-8")
    context_smoke = (root / "preview" / "callcontextcheck" / "src" / "debug" /
                     "java" / "com" / "aios" / "contextintelligence" /
                     "ContextLifecycleSmokeActivity.java").read_text(
                         encoding="utf-8")
    context_document = (context_root / "api" / "com" / "aios" / "context" /
                        "ContextDocument.java").read_text(encoding="utf-8")
    call_context_writer = (root / "services" / "callintelligence" / "src" / "com" /
                           "aios" / "callintelligence" /
                           "CallCommunicationContextClient.java").read_text(
                               encoding="utf-8")
    call_artifact_store = (root / "services" / "callintelligence" / "src" / "com" /
                           "aios" / "callintelligence" /
                           "CallArtifactStore.java").read_text(encoding="utf-8")
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
            and "ContextSourceScope.selectionClause(sourceTypes, arguments)"
            in context_store
            and "in String[] sourceTypes" in context_aidl
            and "String[] sourceTypes" in context_service
            and "QUERY_SOURCES" in context_policy
            and "!requestedSources.add(sourceType)" in context_policy
            and 'new StringBuilder(" AND e.source_type IN (")'
            in context_source_scope
            and "sourceArgumentsPrecedeLaterQueryArguments"
            in context_source_scope_test
            and "emptyScopeCannotAccidentallyBecomeAnUnfilteredQuery"
            in context_source_scope_test
            and "MAX_QUERY_RESULTS = 8" in context_policy
            and "MAX_SNIPPET_CHARS = 512" in context_policy
            and "CALL_ARTIFACT_TTL_MILLIS" in context_policy
            and "call artifacts must expire within 24 hours" in context_policy,
            "communication retrieval must be source-scoped, bounded, revisioned, and retention-aware")
    require("private static final int VERSION = 6" in context_store
            and "CREATE TABLE entry_embeddings" in context_store
            and "REFERENCES entries(_id) ON DELETE CASCADE" in context_store
            and "CHECK(dimensions=256)" in context_store
            and "CHECK(length(vector)=256)" in context_store
            and "oldVersion < 6" in context_store
            and "MAX_EMBEDDING_BATCH = 16" in context_store
            and "pendingEmbeddings" in context_store
            and "currentRevision != revision" in context_store
            and "expiresAtEpochMillis <= embeddedAtEpochMillis" in context_store
            and "model_bundle_sha256" in context_store
            and "commitEmbedding" in context_store
            and "DIMENSIONS = 256" in context_embedding
            and "embedding contains a non-finite value" in context_embedding
            and "MAX_CANDIDATES = 512" in context_ranker
            and "0.65 * semantic" in context_ranker
            and "0.25 * lexical" in context_ranker
            and "0.10 * recency" in context_ranker
            and "Partial indexing must not turn unrelated recent rows into matches"
            in context_ranker
            and "[0-9a-f]{64}" in context_embedding_identity,
            "communication hybrid retrieval must be bounded, artifact-pinned, quantized, and cascade-deleted")
    require('com.aios.permission.USE_MODEL_BROKER' in context_manifest
            and 'com.aios.model.MODEL_SERVICE' in context_manifest
            and "class ContextEmbeddingClient" in context_embedding_client
            and "QUERY_WAIT_MILLIS = 250L" in context_embedding_client
            and '"context_query"' in context_embedding_client
            and '"context_background"' in context_embedding_client
            and "request.allowFallback = false" in context_embedding_client
            and "capability.selectedModelDigest" in context_embedding_client
            and "pendingEmbeddings" in context_embedding_client
            and "commitEmbedding" in context_embedding_client
            and 'LANGUAGE = "und"' in context_embedding_capability
            and "embeddings.scheduleIndexing()" in context_service
            and "embeddings.embedQuery(query)" in context_service
            and "store.queryHybrid(" in context_service
            and "Semantic reranking failed; using SQL/FTS" in context_service
            and "List<ContextSnippet> queryHybrid(" in context_store
            and "ContextText.lexicalRank" in context_store,
            "communication embedding integration must be digest-pinned, bounded, preemptible, and fail back to SQL/FTS")
    require("pendingEmbeddings" in context_smoke
            and "commitEmbedding" in context_smoke
            and "embeddingCount(store) == 0" in context_smoke
            and "late stale-revision embedding callback was accepted" in context_smoke
            and "freshArtifact.expiresAtEpochMillis" in context_smoke
            and "model_bundle_sha256" in context_smoke
            and "length(vector)" in context_smoke
            and "store.queryHybrid(" in context_smoke
            and "hybrid retrieval did not rank the semantic fixture first" in context_smoke,
            "Android context smoke must exercise embedding persistence, stale callbacks, expiry, and cascade deletion")
    require("expiry_boot_identity TEXT NOT NULL" in context_store
            and "created_at_elapsed_ms INTEGER NOT NULL" in context_store
            and "expires_at_elapsed_ms INTEGER NOT NULL" in context_store
            and "oldVersion < 5" in context_store
            and "document.expiryBootIdentity" in context_store
            and "document.createdAtElapsedRealtimeMillis" in context_store
            and "document.expiresAtElapsedRealtimeMillis" in context_store
            and "expiry_boot_identity<>?" in context_store
            and "created_at_elapsed_ms>?" in context_store
            and "expires_at_elapsed_ms-created_at_elapsed_ms<>CAST(? AS INTEGER)"
            in context_store
            and "expires_at_elapsed_ms<=?" in context_store
            and "nextExpiryElapsedRealtimeMillis" in context_store
            and "!Objects.equals(expiryBootIdentity, currentBootIdentity)"
            in context_expiry
            and "static boolean isWellFormed" in context_expiry
            and "Math.addExact" in context_expiry
            and "public final String expiryBootIdentity" in context_document
            and "public final long createdAtElapsedRealtimeMillis" in context_document
            and "public final long expiresAtElapsedRealtimeMillis" in context_document
            and "document.expiryBootIdentity" in context_service
            and "document.createdAtElapsedRealtimeMillis" in context_service
            and "document.expiresAtElapsedRealtimeMillis" in context_service
            and "pending.expiryBootIdentity" in call_context_writer
            and "pending.createdAtElapsedRealtimeMillis" in call_context_writer
            and "pending.expiresAtElapsedRealtimeMillis" in call_context_writer
            and "CallArtifactRetention.isExpired" in call_context_writer
            and "deadline.bootIdentity" in call_artifact_store
            and "deadline.createdAtElapsedRealtimeMillis" in call_artifact_store
            and "deadline.expiresAtElapsedRealtimeMillis" in call_artifact_store
            and "setExactAndAllowWhileIdle" in context_alarm
            and "AlarmManager.ELAPSED_REALTIME_WAKEUP" in context_alarm
            and "ContextRetentionAlarm.scheduleNext" in context_service
            and "ContextRetentionAlarm.ACTION_CLEANUP" in context_boot
            and "wallClockRollbackCannotExtendTheMonotonicDeadline"
            in context_expiry_test
            and "rebootAndLegacyRowsExpireFailClosed" in context_expiry_test
            and 'android.permission.USE_EXACT_ALARM' in context_manifest
            and 'com.aios.contextintelligence.CLEANUP_EXPIRED_CONTEXT'
            in context_manifest,
            "call-derived communication context needs automatic dual-clock expiry")
    require("rawAddress" not in context_store
            and "phone_number" not in context_store
            and "contact_lookup" not in context_store,
            "communication index must not store raw phone or contact identifiers")
    require(context_store.count("<>CAST(? AS INTEGER)") == 2,
            "SQLite retention comparisons must cast text-bound TTL arguments")
    require("result.append(' ')" in context_text
            and 'result.append(" AND ")' not in context_text,
            "communication retrieval must use Android-portable FTS4 intersection syntax")
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
    assistant_policy_ui_state = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "model" / "AssistantPolicyUiState.kt"
    ).read_text(encoding="utf-8")
    transcript_reducer = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "model" / "TranscriptTimelineReducer.kt"
    ).read_text(encoding="utf-8")
    transcript_reducer_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "model" / "TranscriptTimelineReducerTest.kt"
    ).read_text(encoding="utf-8")
    call_risk_contract = (root / "apps" / "phone" / "src" / "com" / "aios" /
                          "phone" / "model" / "CallRiskContract.kt").read_text(
                              encoding="utf-8")
    assistant_call_contract = (root / "apps" / "phone" / "src" / "com" / "aios" /
                               "phone" / "model" /
                               "AssistantCallContract.kt").read_text(encoding="utf-8")
    phone_registry = (root / "apps" / "phone" / "src" / "com" / "aios" /
                      "phone" / "telecom" / "CallRegistry.kt").read_text(
                          encoding="utf-8")
    call_selection_policy = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "telecom" / "CallSelectionPolicy.kt"
    ).read_text(encoding="utf-8")
    call_selection_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "telecom" / "CallSelectionPolicyTest.kt"
    ).read_text(encoding="utf-8")
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
    assistant_capability_status_policy = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "intelligence" / "AssistantCapabilityStatusPolicy.kt"
    ).read_text(encoding="utf-8")
    assistant_capability_status_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "intelligence" / "AssistantCapabilityStatusPolicyTest.kt"
    ).read_text(encoding="utf-8")
    direct_boot_policy = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "DirectBootPreferencePolicy.kt"
    ).read_text(encoding="utf-8")
    direct_boot_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "DirectBootPreferencePolicyTest.kt"
    ).read_text(encoding="utf-8")
    phone_rebind_policy = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "intelligence" / "PhoneServiceRebindPolicy.kt"
    ).read_text(encoding="utf-8")
    service_generation_revision_gate = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "intelligence" / "ServiceGenerationRevisionGate.kt"
    ).read_text(encoding="utf-8")
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
    call_event_binding = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "context" / "ResilientCommunicationContextBinding.kt"
    ).read_text(encoding="utf-8")
    call_event_test = (root / "apps" / "phone" / "tests" / "src" / "com" /
                       "aios" / "phone" / "context" /
                       "CallEventContractTest.kt").read_text(encoding="utf-8")
    pending_answer_test = (root / "apps" / "phone" / "tests" / "src" / "com" /
                           "aios" / "phone" / "intelligence" /
                           "PendingAiAnswerGateTest.kt").read_text(encoding="utf-8")
    phone_rebind_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "intelligence" / "PhoneServiceRebindPolicyTest.kt"
    ).read_text(encoding="utf-8")
    service_generation_revision_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "intelligence" / "ServiceGenerationRevisionGateTest.kt"
    ).read_text(encoding="utf-8")
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
    assistant_policy_semantics = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "model" / "AssistantPolicySemantics.kt"
    ).read_text(encoding="utf-8")
    assistant_policy_semantics_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "model" / "AssistantPolicySemanticsTest.kt"
    ).read_text(encoding="utf-8")
    in_call_activity = (root / "apps" / "phone" / "src" / "com" / "aios" /
                        "phone" / "ui" / "InCallActivity.kt").read_text(
                            encoding="utf-8")
    notification_source = (root / "apps" / "phone" / "src" / "com" / "aios" /
                           "phone" / "notifications" /
                           "CallNotificationCoordinator.kt").read_text(encoding="utf-8")
    notification_semantics = (
        root / "apps" / "phone" / "src" / "com" / "aios" / "phone" /
        "notifications" / "CallNotificationSemantics.kt"
    ).read_text(encoding="utf-8")
    notification_semantics_test = (
        root / "apps" / "phone" / "tests" / "src" / "com" / "aios" /
        "phone" / "notifications" / "CallNotificationSemanticsTest.kt"
    ).read_text(encoding="utf-8")
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
    phone_extraction_rules = (
        root / "apps" / "phone" / "res" / "xml" / "data_extraction_rules.xml"
    ).read_text(encoding="utf-8")
    smoke_manifest = (root / "preview" / "telecomsmoke" / "src" / "debug" /
                      "AndroidManifest.xml").read_text(encoding="utf-8")
    smoke_activity = (root / "preview" / "telecomsmoke" / "src" / "debug" /
                      "kotlin" / "com" / "aios" / "phone" / "smoke" /
                      "EmulatorCallActivity.kt").read_text(encoding="utf-8")
    smoke_connection = (root / "preview" / "telecomsmoke" / "src" / "debug" /
                        "kotlin" / "com" / "aios" / "phone" / "smoke" /
                        "EmulatorConnectionService.kt").read_text(encoding="utf-8")
    assistant_smoke_build = (root / "preview" / "callassistantsmoke" /
                             "build.gradle.kts").read_text(encoding="utf-8")
    assistant_smoke_manifest = (
        root / "preview" / "callassistantsmoke" / "src" / "main" /
        "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    assistant_smoke_guard = (
        root / "preview" / "callassistantsmoke" / "src" / "main" / "java" /
        "com" / "aios" / "callintelligence" / "EmulatorGuard.java"
    ).read_text(encoding="utf-8")
    assistant_smoke_control = (
        root / "preview" / "callassistantsmoke" / "src" / "main" / "java" /
        "com" / "aios" / "callintelligence" /
        "EmulatorCallAssistantControlActivity.java"
    ).read_text(encoding="utf-8")
    assistant_smoke_service = (
        root / "preview" / "callassistantsmoke" / "src" / "main" / "java" /
        "com" / "aios" / "callintelligence" /
        "EmulatorCallAssistantService.java"
    ).read_text(encoding="utf-8")
    smoke_script = (root / "scripts" /
                    "emulator-telecom-smoke.ps1").read_text(encoding="utf-8")
    require("data class PhoneUiState" in phone_contract
            and "sealed interface PhoneAction" in phone_contract
            and "StateFlow<PhoneUiState>" in phone_runtime,
            "AIOS Phone must use immutable UDF state and typed actions")
    phone_extraction_domains = {
        "root", "file", "database", "sharedpref", "external", "device_root",
        "device_file", "device_database", "device_sharedpref",
    }
    require('manifest.srcFile("../../apps/phone/AndroidManifest.xml")'
            in prodcheck_build
            and 'kotlin.directories.add("../../apps/phone/src")' in prodcheck_build
            and 'kotlin.directories.add("../../apps/phone/tests/src")'
            in prodcheck_build
            and 'res.directories.add("../../apps/phone/res")' in prodcheck_build
            and '../../services/callintelligence/aidl' in prodcheck_build
            and '../../services/contextintelligence/aidl' in prodcheck_build
            and '../../services/contextintelligence/api' in prodcheck_build
            and 'srcs: ["src/**/*.kt"]' in phone_build
            and 'resource_dirs: ["res"]' in phone_build
            and 'android:dataExtractionRules="@xml/data_extraction_rules"'
            in phone_manifest
            and 'SALT_PREFS = "call_privacy"' in assistant_client
            and 'PREFS = "call_event_context"' in call_event_client
            and all(phone_extraction_rules.count(
                f'<exclude domain="{domain}" path="." />') == 2
                    for domain in phone_extraction_domains)
            and "abortOnError" not in prodcheck_build,
            "Phone compile-check and no-migration policy must cover the complete role app")
    require("linkedMapOf<String, Call>()" in phone_registry
            and "IdentityHashMap<Call, String>()" in phone_registry
            and "conferenceableIds" in phone_registry
            and "var currentCall" not in phone_registry,
            "AIOS Phone must model all calls rather than one current-call singleton")
    require("CallSelectionPolicy.afterCallAdded" in phone_registry
            and "CallSelectionPolicy.afterStateChanged" in phone_registry
            and "newCallIsRinging -> newCallId" in call_selection_policy
            and "newRingingCallPreemptsTheSelectedActiveCall" in call_selection_test
            and "backgroundCallDoesNotStealOwnerSelection" in call_selection_test
            and "delayedRingingTransitionPreemptsTheSelectedCall" in call_selection_test
            and "LaunchedEffect(selected?.id, selected?.isRinging)" in phone_screens
            and "verticalScroll(callScrollState)" in phone_screens,
            "a waiting call must preempt the selected surface without background-call theft")
    require("ACTION_OWNER_ANSWER" in smoke_activity
            and "ACTION_OWNER_DECLINE" in smoke_activity
            and "ACTION_OWNER_IGNORE" in smoke_activity
            and "PhoneRuntime.dispatch(action(ringing.id))" in smoke_activity
            and "com.aios.phone.smoke.OWNER_ANSWER" in smoke_script
            and "com.aios.phone.smoke.OWNER_DECLINE" in smoke_script
            and "com.aios.phone.smoke.OWNER_IGNORE" in smoke_script,
            "the emulator must inject timing-critical owner actions through production UDF")
    require("onAvailableCallEndpointsChanged" in in_call_service
            and "requestCallEndpointChange" in in_call_service,
            "AIOS Phone must use modern audio endpoint callbacks")
    require("INCOMING_CHANNEL" in notification_source
            and "SILENT_INCOMING_CHANNEL" in notification_source
            and "ONGOING_CHANNEL" in notification_source
            and "USAGE_NOTIFICATION_RINGTONE" in notification_source
            and "promoteCallNotification" in notification_source
            and "requiresPhoneCallForeground" in notification_source
            and "FOREGROUND_SERVICE_TYPE_PHONE_CALL" in in_call_service
            and 'android.permission.FOREGROUND_SERVICE_PHONE_CALL' in phone_manifest
            and 'android:foregroundServiceType="phoneCall"' in phone_manifest
            and "onSilenceRinger" in in_call_service,
            "AIOS Phone must own call channels and foreground ongoing CallStyle notifications")
    require("current.transcripts" in phone_runtime
            and "TranscriptTimelineReducer.reduce(" in phone_runtime
            and "current.lastOrNull()" not in phone_runtime
            and 'it.direction == candidate.direction' in transcript_reducer
            and "!it.isFinal" in transcript_reducer
            and "updated[openTurn] = candidate" in transcript_reducer
            and "interleavedDirectionsReplaceOnlyTheirOwnOpenTurn"
            in transcript_reducer_test
            and "interleavedFinalReplacesPartialWithoutDuplicatingWords"
            in transcript_reducer_test
            and '"src/com/aios/phone/model/TranscriptUiState.kt"'
            in phone_build
            and '"src/com/aios/phone/model/TranscriptTimelineReducer.kt"'
            in phone_build
            and "scheduleTranscriptNotificationSync()" in phone_runtime
            and "TRANSCRIPT_NOTIFICATION_SYNC_MILLIS = 350L" in phone_runtime
            and "latestIncomingTranscript" in notification_source
            and "CallNotificationSemantics.present(" in notification_source
            and "Notification.VISIBILITY_PRIVATE" in notification_source
            and 'ONGOING_CHANNEL = "ongoing_calls_private_v2"' in notification_source
            and "setOnlyAlertOnce(true)" in notification_source
            and "MAX_CONTENT_CHARS = 160" in notification_semantics
            and "Character.FORMAT" in notification_semantics
            and "ringingPresentationNeverIncludesTranscript"
            in notification_semantics_test
            and "liveCallerTextIsBoundedNormalizedAndMarkedPrivate"
            in notification_semantics_test
            and '"src/com/aios/phone/notifications/CallNotificationSemantics.kt"'
            in phone_build,
            "live caller notification previews must be bounded, private, non-alerting, and host-tested")
    require("PROXIMITY_SCREEN_OFF_WAKE_LOCK" in proximity_source
            and "TYPE_EARPIECE" in phone_runtime,
            "AIOS Phone must limit the proximity lock to active earpiece calls")
    require("onPostDialWait" in phone_registry
            and "CallSelectionPolicy.forOwnerPrompt" in phone_registry
            and "forOwnerPrompt" in call_selection_policy
            and "postDialPromptSelectsTheCallThatNeedsOwnerInput" in call_selection_test
            and "postDialContinue" in phone_runtime,
            "AIOS Phone must select post-dial prompts without exposing queued digits")
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
            and "CallEventContextClient(application)" in phone_runtime
            and "contextEvents?.setEnabled(held)" in phone_runtime
            and "startWatchingMode(" in phone_runtime
            and "AppOpsManager.OPSTR_READ_CALL_LOG" in phone_runtime
            and "contextEvents?.onCallLogMayHaveChanged()" in phone_runtime
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
    require("ResilientCommunicationContextBinding(" in call_event_client
            and "result.exceptionOrNull() is RemoteException" in call_event_client
            and "binding.invalidate(service)" in call_event_client
            and "binding.start()" in call_event_client
            and "binding.stop()" in call_event_client
            and "ContextConnection(++generation)" in call_event_binding
            and "activeConnection === connection" in call_event_binding
            and "connection.generation == generation" in call_event_binding
            and "onBindingDied" in call_event_binding
            and "onNullBinding" in call_event_binding
            and "CONNECT_TIMEOUT_MILLIS = 15_000L" in call_event_binding
            and "PhoneServiceRebindPolicy" in call_event_binding
            and "connectionRacingReservedRetryCancelsThatAttempt"
            in phone_rebind_test,
            "Phone call-event reconciliation must replace failed bindings without accepting stale callbacks")
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
            and "com.android.voicemail.permission.ADD_VOICEMAIL" in phone_manifest
            and "com.android.voicemail.permission.READ_VOICEMAIL" in phone_manifest
            and "com.android.voicemail.permission.WRITE_VOICEMAIL" in phone_manifest,
            "AIOS Phone must provide bounded, OMTP-aware, provider-streamed voicemail")
    require("ThemePreference.SYSTEM" in theme_source
            and "ThemePreference.LIGHT" in theme_source
            and "ThemePreference.DARK" in theme_source,
            "AIOS Phone must support system, light, and dark themes")
    require("MODE_MISSED_ONLY" in assistant_policy_semantics
            and "SELECTABLE_AUTO_ANSWER_MODES" in assistant_policy_semantics
            and "DIRECT_ANSWER_DELAY_MODES" in assistant_policy_semantics
            and "MISSED_DELAY_OPTIONS_MILLIS" in assistant_policy_semantics
            and "modeAfterAutoAnswerToggle" in phone_runtime
            and "isKnownAnswerMode" in phone_runtime
            and "isKnownDirectDelayMode" in phone_runtime
            and "clampMissedDelay" in phone_runtime
            and "safeAnswerMode" in assistant_policy_semantics
            and "safeDirectDelayMode" in assistant_policy_semantics
            and "safeUnavailableReason" in assistant_policy_semantics
            and "AssistantPolicySemantics.safeAnswerMode(answerMode)" in assistant_client
            and "AssistantPolicySemantics.safeDirectDelayMode(answerDelayMode)"
            in assistant_client
            and "malformedOrMissingBinderPolicyStringsFailClosed"
            in assistant_policy_semantics_test
            and "AssistantPolicySemantics.SELECTABLE_AUTO_ANSWER_MODES"
            in phone_screens
            and '"After I don\'t answer"' in phone_screens
            and "AssistantPolicySemantics.MISSED_DELAY_OPTIONS_MILLIS"
            in phone_screens
            and "PhoneAction.ChangeMissedDelay(millis)" in phone_screens
            and "AssistantPolicySemantics.DIRECT_ANSWER_DELAY_MODES"
            in phone_screens
            and "exposesEveryServiceSupportedAnswerMode"
            in assistant_policy_semantics_test
            and "ringFirstChoicesCoverDefaultAndServiceBounds"
            in assistant_policy_semantics_test
            and '"src/com/aios/phone/model/AssistantPolicySemantics.kt"'
            in phone_build,
            "Phone settings must expose every supported AI-answer scope with distinct delay semantics")
    require("callerHistoryEnabled: Boolean = false" in assistant_policy_ui_state
            and "messageHistoryEnabled: Boolean = true" in assistant_policy_ui_state
            and "callHistoryEnabled: Boolean = true" in assistant_policy_ui_state
            and "photoHistoryEnabled: Boolean = true" in assistant_policy_ui_state
            and "ChangeCallerHistoryEnabled" in phone_contract
            and "ChangeMessageHistoryEnabled" in phone_contract
            and "ChangeCallHistoryEnabled" in phone_contract
            and "ChangePhotoHistoryEnabled" in phone_contract
            and "ChangeConversationHistory" in phone_contract
            and "excludedCallerHistoryAddressHashes: Set<String>"
            in assistant_policy_ui_state
            and "withCallerHistoryEnabled" in assistant_policy_ui_state
            and "withoutEmptyCallerHistory" in assistant_policy_ui_state
            and "callerHistoryScopeCannotRemainEnabledWithoutSources"
            in assistant_policy_semantics_test
            and "ownerCallerHistoryEnabled == true" in assistant_client
            and "callerHistoryEnabled = value.callerHistoryEnabled" in assistant_client
            and "messageHistoryEnabled = value.messageHistoryEnabled" in assistant_client
            and "callHistoryEnabled = value.callHistoryEnabled" in assistant_client
            and "photoHistoryEnabled = value.photoHistoryEnabled" in assistant_client
            and "excludedCallerHistoryAddressHashes.sorted().toTypedArray()"
            in assistant_client
            and "ownerCallerHistoryExcludedHashes?.contains(normalizedAddressHash) == false"
            in assistant_client
            and "saveConversationHistory" in assistant_client
            and "decorateRecentCalls" in assistant_client
            and 'title = "Use caller history"' in phone_screens
            and 'title = "Messages"' in phone_screens
            and 'title = "Previous calls"' in phone_screens
            and 'title = "Sent photo descriptions"' in phone_screens,
            "Phone must expose default-off, non-empty source-scoped caller history and withhold raw identity when disabled")
    require('"Exclude AI history"' in phone_screens
            and '"Allow AI history"' in phone_screens
            and "conversationExclusionsUseOnlyBoundedOpaqueHashes"
            in assistant_policy_semantics_test,
            "Phone Recents must expose per-conversation AI-history exclusion without storing raw numbers")
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
            and "ACTION_ACTIVATE" in smoke_activity
            and "ACTION_POST_DIAL_WAIT" in smoke_activity
            and "POST_DIAL_SEQUENCE" in smoke_activity
            and "SECONDARY_ACCOUNT_ID" in smoke_activity
            and "unregisterPhoneAccount(phoneAccountHandle(SECONDARY_ACCOUNT_ID))"
            in smoke_activity
            and '"AIOS emulator primary"' in smoke_activity
            and '"AIOS emulator secondary"' in smoke_activity
            and "override fun onNewIntent" in smoke_activity
            and "ACTION_RESET_AUDIT" in smoke_activity
            and "ACTION_EXPORT_AUDIT" in smoke_activity
            and "ACTION_DISCONNECT" in smoke_activity,
            "the Telecom fixture must refuse physical hardware, handle reused commands, "
            "and clean up calls")
    require("onCreateOutgoingConnection" in smoke_connection
            and "setDialing()" in smoke_connection
            and "activateAll()" in smoke_connection
            and "onPlayDtmfTone" in smoke_connection
            and "onStopDtmfTone" in smoke_connection
            and "onPostDialContinue" in smoke_connection
            and "setPostDialWait" in smoke_connection
            and '"outgoing-account:${request.accountHandle?.id' in smoke_connection
            and "override fun onConference" in smoke_connection
            and "class EmulatorConference" in smoke_connection
            and "CAPABILITY_SEPARATE_FROM_CONFERENCE" in smoke_connection
            and 'fixtureEvents += "separate"' in smoke_connection
            and "auditSnapshot()" in smoke_connection,
            "the debug Telecom fixture must model outgoing, DTMF, and conference transitions")
    require('applicationId = "com.aios.callintelligence"' in assistant_smoke_build
            and "AnswerDelayPolicy.java" in assistant_smoke_build
            and "CallPolicyEngine.java" in assistant_smoke_build
            and '../../services/callintelligence/aidl' in assistant_smoke_build
            and "com.aios.permission.CONTROL_CALL_INTELLIGENCE"
            in assistant_smoke_manifest
            and 'android:protectionLevel="signature"' in assistant_smoke_manifest
            and "com.aios.call.CALL_INTELLIGENCE_SERVICE"
            in assistant_smoke_manifest
            and "Build.HARDWARE" in assistant_smoke_guard
            and "The call-assistant fixture only runs on an emulator"
            in assistant_smoke_control
            and "!EmulatorGuard.isEmulator()" in assistant_smoke_service
            and "return null;" in assistant_smoke_service
            and "new CallPolicyEngine(" in assistant_smoke_service
            and 'audit("policy_update:"' in assistant_smoke_service
            and "It intentionally supplies no capture, ASR, model, or caller-audio"
            in assistant_smoke_service,
            "the automatic-answer AIDL peer must reuse production policy and remain emulator-only")
    require("Intent(application, InCallActivity::class.java)" in phone_runtime
            and '"Call placed. Tap the active-call card to open controls"'
            in phone_runtime,
            "a production outgoing dial action must open or recover to in-call controls")
    require("'^emulator-[0-9]+$'" in smoke_script
            and "ro.kernel.qemu" in smoke_script
            and "$apiLevel -lt 35" in smoke_script
            and "Get-FileHash -LiteralPath $apkPath -Algorithm SHA256"
            in smoke_script
            and "apk_sha256 = $apkSha256" in smoke_script
            and "Refusing to replace an existing $package installation"
            in smoke_script
            and 'physical_gate_evidence = $false' in smoke_script
            and "finally {" in smoke_script
            and "remove-role-holder" in smoke_script
            and "cmd telecom wait-on-handlers" in smoke_script
            and "original_role_holders_restored" in smoke_script
            and "fixture_phone_account_removed" in smoke_script
            and "cleanup_verified" in smoke_script
            and "rm -f $remoteScreenshot" in smoke_script
            and "remote_screenshot_removed" in smoke_script
            and "Get-CurrentTelecomCalls" in smoke_script
            and 'Invoke-UiControl "Ignore"' in smoke_script
            and 'Invoke-UiControl "Answer"' in smoke_script
            and 'Invoke-UiControl "Decline"' in smoke_script
            and 'android.intent.action.DIAL' in smoke_script
            and 'Invoke-UiControl "Call"' in smoke_script
            and 'Invoke-UiControl "Mute"' in smoke_script
            and 'Invoke-UiControl "Unmute"' in smoke_script
            and 'Invoke-UiControl "Hold"' in smoke_script
            and 'Invoke-UiControl "Resume"' in smoke_script
            and 'Invoke-UiControl "Keypad"' in smoke_script
            and 'com.aios.phone.smoke.POST_DIAL_WAIT' in smoke_script
            and 'Invoke-UiControl "Continue"' in smoke_script
            and 'Invoke-UiControl "Cancel"' in smoke_script
            and "$postDialUi -match '739164'" in smoke_script
            and '$fixtureSecondaryAccount = "aios-emulator-smoke-secondary"'
            in smoke_script
            and "set-user-selected-outgoing-phone-account | Out-Null" in smoke_script
            and 'Invoke-UiControl "AIOS emulator primary"' not in smoke_script
            and 'Get-UiControl "AIOS emulator primary"' in smoke_script
            and 'Invoke-UiControl "AIOS emulator secondary"' in smoke_script
            and "state=SELECT_PHONE_ACCOUNT" in smoke_script
            and 'Invoke-UiControl "Merge calls"' in smoke_script
            and 'Invoke-UiControl "Separate call"' in smoke_script
            and 'Invoke-UiControl "End call"' in smoke_script
            and "original_outgoing_account_restored" in smoke_script
            and "outgoing_connection_active" in smoke_script
            and "mute_unmute_round_trip" in smoke_script
            and "hold_resume_round_trip" in smoke_script
            and "dtmf_play_stop_callbacks" in smoke_script
            and "post_dial_digits_redacted" in smoke_script
            and "post_dial_continue_callback" in smoke_script
            and "post_dial_cancel_callback" in smoke_script
            and "private_post_dial_audit_removed" in smoke_script
            and "multi_account_selector_visible" in smoke_script
            and "secondary_phone_account_selected" in smoke_script
            and "selected_account_reached_connection_service" in smoke_script
            and "private_account_selection_audit_removed" in smoke_script
            and "waiting_call_selected" in smoke_script
            and "waiting_answer_held_existing_call" in smoke_script
            and "conference_merge_separate_callbacks" in smoke_script
            and "private_dtmf_audit_removed" in smoke_script
            and "private_conference_audit_removed" in smoke_script
            and "run-as $package rm -f $privateAuditFile" in smoke_script
            and "phone_process_survived_answer" in smoke_script
            and "phone_call_foreground_service" in smoke_script
            and "ongoing_notification_posted" in smoke_script
            and "dumpsys notification --noredact" in smoke_script
            and "channel=ongoing_calls_private_v2" in smoke_script
            and "channel=ongoing_calls_v1" not in smoke_script
            and "full_screen_intent_launched_automatically" in smoke_script
            and '$assistantPackage = "com.aios.callintelligence"' in smoke_script
            and "Get-FileHash -LiteralPath $assistantApkPath -Algorithm SHA256"
            in smoke_script
            and "automatic_answer_fixed_delays" in smoke_script
            and "random_1010_3990_ms" in smoke_script
            and "owner_answer_cancelled_pending_ai" in smoke_script
            and "decline_cancelled_pending_ai" in smoke_script
            and "ignore_preserved_automatic_ai" in smoke_script
            and "service_loss_revoked_old_pending_ai" in smoke_script
            and "service_reconnect_restarted_full_delay_ms" in smoke_script
            and "synthetic_emergency_never_evaluated_for_ai" in smoke_script
            and "settings_policy_update_reached_binder" in smoke_script
            and "settings_policy_survived_service_restart" in smoke_script
            and "settings_to_telecom_answer" in smoke_script
            and 'Invoke-UiSwitch "Process and transcribe calls"' in smoke_script
            and 'Invoke-UiSwitch "Auto AI answer"' in smoke_script
            and 'Invoke-ScrolledUiControl "Save assistant settings"' in smoke_script
            and "policy_update:all:fixed_3000_ms:true" in smoke_script
            and "shell timeout 10 uiautomator dump" in smoke_script
            and "am start -W -a com.aios.phone.smoke.SHOW" not in smoke_script
            and "AutomaticAnswerOnly" in smoke_script
            and "assistant_package_removed" in smoke_script
            and "private_automatic_answer_audits_removed" in smoke_script
            and "[IO.File]::WriteAllText" in smoke_script,
            "the Telecom smoke script must be digest-bound, emulator-only, reversible, and non-release evidence")
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
            and "service.onCallResumed" in assistant_client
            and "service.onCallEnded" in assistant_client
            and "onServiceDisconnected" in assistant_client
            and "onBindingDied" in assistant_client
            and "onNullBinding" in assistant_client
            and "connection.generation != connectionGeneration" in assistant_client
            and "BINDING_WATCHDOG_MILLIS = 15_000L" in assistant_client
            and "resumeActiveCall(session, processing)" in assistant_client
            and "session.answeredNotified = false" in assistant_client
            and "MAX_DELAY_MILLIS = 60_000L" in phone_rebind_policy
            and "onlyOneRetryCanBeScheduled" in phone_rebind_test
            and "successfulConnectionResetsBackoff" in phone_rebind_test,
            "AIOS Phone must recover Call Intelligence bindings and resume active calls")
    require("val listener: ICallIntelligenceListener = createListener(this)"
            in assistant_client
            and "isCurrentListener(connection)" in assistant_client
            and "remote === connection.service" in assistant_client
            and "private data class ServiceLease" in assistant_client
            and "isCurrentLease(lease)" in assistant_client
            and "invalidate(lease)" in assistant_client
            and "session.riskRevisions.accept(assessment.revision)" in assistant_client
            and "session.assistantRevisions.accept(state.revision)" in assistant_client
            and "session.riskRevisions.nextGeneration()" in assistant_client
            and "session.assistantRevisions.nextGeneration()" in assistant_client
            and "error is RemoteException" in assistant_client
            and "terminateBindingOnMain(connection, expected, immediate = false)"
            in assistant_client
            and "candidateWireRevision <= wireRevision"
            in service_generation_revision_gate
            and "visibleRevision == Long.MAX_VALUE"
            in service_generation_revision_gate
            and "newServiceGenerationKeepsVisibleRevisionMonotonic"
            in service_generation_revision_test
            and '"src/com/aios/phone/intelligence/ServiceGenerationRevisionGate.kt"'
            in phone_build,
            "AIOS Phone callbacks and visible revisions must be generation-safe across service replacement")
    require("telecomLifecycleToken: IBinder = Binder()" in assistant_client
            and "service.setTelecomCallPresent(telecomLifecycleToken" in assistant_client
            and "announceEveryPresentCall(service)" in assistant_client,
            "AIOS Phone must publish every Telecom call with a replayable lifecycle token")
    require("createDeviceProtectedStorageContext()" in phone_runtime
            and "moveSharedPreferencesFrom(application, PREFS)" in phone_runtime
            and "fun onCredentialStorageUnlocked()" in phone_runtime
            and "Intent.ACTION_USER_UNLOCKED" in phone_runtime
            and "registerReceiver(" in phone_runtime
            and "unregisterReceiver(unlockReceiver)" in phone_runtime
            and "private var contextEvents: CallEventContextClient? = null"
            in phone_runtime
            and "if (contextEvents == null && credentialStorageUnlocked())"
            in phone_runtime
            and "assistant.onCredentialStorageUnlocked()" in phone_runtime
            and "fun onCredentialStorageUnlocked()" in assistant_client
            and "terminateBindingOnMain(connection, expected = null, immediate = true)"
            in assistant_client
            and "WAIT_FOR_UNLOCK" in direct_boot_policy
            and "MIGRATE_LEGACY" in direct_boot_policy
            and "credentialPreferencesWaitWhileUserIsLocked" in direct_boot_test
            and '"src/com/aios/phone/DirectBootPreferencePolicy.kt"' in phone_build,
            "AIOS Phone must keep call controls available before unlock and recover optional services afterward")
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
            and '"src/com/aios/phone/intelligence/PhoneServiceRebindPolicy.kt"'
            in phone_build
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
            and "session.assistantRevisions.accept" in assistant_client
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
    model_request_api = (
        root / "services" / "modelbroker" / "aidl" / "com" / "aios" /
        "model" / "ModelRequest.aidl"
    ).read_text(encoding="utf-8")
    require("Long.MAX_VALUE" in model_request_api
            and "lifecycle-bound streaming_asr" in model_request_api
            and "later admitted candidates" in model_request_api
            and "first admitted capability/language candidate" in model_request_api,
            "model request API must define lifecycle-bound ASR and exact fallback semantics")

    manifest = (root / "services" / "modelbroker" / "AndroidManifest.xml").read_text(
        encoding="utf-8"
    )
    require('android:protectionLevel="signature"' in manifest,
            "model broker permission must be signature protected")
    require('android:permission="com.aios.permission.USE_MODEL_BROKER"' in manifest,
            "model broker service must enforce its signature permission")

    service = (root / "services" / "modelbroker" / "src" / "com" / "aios" /
               "modelbroker" / "ModelBrokerService.java").read_text(encoding="utf-8")
    model_on_bind_start = service.index("public IBinder onBind(Intent intent)")
    model_on_bind_end = service.index("public void onTrimMemory", model_on_bind_start)
    model_on_bind = service[model_on_bind_start:model_on_bind_end]
    require('android:permission="com.aios.permission.USE_MODEL_BROKER"' in manifest
            and "enforceBrokerPermission()" not in model_on_bind
            and service.count("enforceBrokerPermission();") >= 8,
            "Model Broker must enforce clients at the manifest and AIDL boundary, not Service.onBind")
    require("ERROR_NOT_READY" in service
            and "candidates.stream().noneMatch(state::runtimeAvailable)" in service,
            "unconfigured model broker must fail closed")
    require("ERROR_DEADLINE_EXCEEDED" in service,
            "model broker must expose a distinct session-deadline failure")
    require("onTrimMemory" in service
            and "RuntimeMemoryTrimPolicy.isMemoryPressure(level)" in service
            and "sessions.onMemoryPressure()" in service
            and "level >= TRIM_MEMORY_RUNNING_LOW" not in service,
            "model broker must interpret Android trim families without treating UI-hidden as pressure")
    require("BrokerState.ResourcePressureException" in service
            and "ERROR_BUSY" in service,
            "transient broker resource pressure must return a retryable busy error")
    require("token.linkToDeath" in service
            and "callActivityLeases.removeDead(token)" in service
            and "state.setCallActive(active)" in service
            and "sessions.setCallActive(desired)" in service,
            "call priority must clear on client death and reconcile broker arbitration")
    session_controller = (root / "services" / "modelbroker" / "src" / "com" /
                          "aios" / "modelbroker" / "SessionController.java").read_text(
                              encoding="utf-8")
    embedding_request_policy = (
        root / "services" / "modelbroker" / "src" / "com" / "aios" /
        "modelbroker" / "EmbeddingRequestPolicy.java"
    ).read_text(encoding="utf-8")
    embedding_result_policy = (
        root / "services" / "modelbroker" / "src" / "com" / "aios" /
        "modelbroker" / "EmbeddingResultPolicy.java"
    ).read_text(encoding="utf-8")
    model_request_aidl = (
        root / "services" / "modelbroker" / "aidl" / "com" / "aios" /
        "model" / "ModelRequest.aidl"
    ).read_text(encoding="utf-8")
    inference_result_aidl = (
        root / "services" / "modelbroker" / "aidl" / "com" / "aios" /
        "model" / "InferenceResult.aidl"
    ).read_text(encoding="utf-8")
    model_capability_aidl = (
        root / "services" / "modelbroker" / "aidl" / "com" / "aios" /
        "model" / "ModelCapability.aidl"
    ).read_text(encoding="utf-8")
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
    require("deadlines.removeExpired(SystemClock.elapsedRealtime())"
            in session_controller
            and "deadlineExecutor.schedule" in session_controller
            and "SessionDeadlinePolicy.validAt" in session_controller
            and "SessionDeadlinePolicy.shouldTrack" in session_controller
            and "record.callbackLock" in session_controller
            and "ERROR_DEADLINE_EXCEEDED" in session_controller,
            "broker must expire finite queued/running sessions with one terminal callback")
    require("SessionChunkPolicy.accepts" in session_controller
            and "record.chunkChars" in session_controller
            and "record.createdAtElapsedMillis" in session_controller,
            "broker must apply workload-aware aggregate or timeline chunk bounds")
    require('String embeddingTask' in model_request_aidl
            and 'float[] embedding' in inference_result_aidl
            and 'String selectedModelDigest' in model_capability_aidl
            and 'CAPABILITY = "text_embedding"' in embedding_request_policy
            and 'QUERY = "query"' in embedding_request_policy
            and 'DOCUMENT = "document"' in embedding_request_policy
            and "maxOutputTokens == 0" in embedding_request_policy
            and "MAX_INPUT_CHARS = 4_096" in embedding_request_policy
            and "acceptsTextInput" in embedding_request_policy
            and "permitsGenerationChunks" in embedding_request_policy
            and "permitsTextSubmission" in embedding_request_policy
            and "DIMENSIONS = 256" in embedding_result_policy
            and "Float.isFinite(value)" in embedding_result_policy
            and "MIN_SQUARED_NORM" in embedding_result_policy
            and "MAX_SQUARED_NORM" in embedding_result_policy
            and "EmbeddingResultPolicy.accepts" in session_controller
            and "result.embedding" in session_controller
            and "record.embeddingInputSubmitted" in session_controller
            and "EmbeddingRequestPolicy.permitsGenerationChunks" in session_controller,
            "embedding transport must be typed, one-shot, bounded, non-streaming, finite, normalized, and 256-dimensional")

    runtime_api = (root / "services" / "runtimeapi" / "aidl" / "com" / "aios" /
                   "runtime" / "IAiosRuntimeProvider.aidl").read_text(encoding="utf-8")
    require("attachAudioOutput" in runtime_api
            and "in ParcelFileDescriptor pcmSink" in runtime_api,
            "runtime API v2 must carry the broker-owned synthesis sink")

    broker_source_root = (
        root / "services" / "modelbroker" / "src" / "com" / "aios" /
        "modelbroker"
    )
    broker_bp = (root / "services" / "modelbroker" / "Android.bp").read_text(
        encoding="utf-8")
    model_service_compile_build = (
        root / "preview" / "modelservicecheck" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    broker_product_properties = (
        broker_source_root / "BrokerProductProperties.java"
    ).read_text(encoding="utf-8")
    broker_deadline_policy = (
        broker_source_root / "SessionDeadlinePolicy.java"
    ).read_text(encoding="utf-8")
    broker_chunk_policy = (
        broker_source_root / "SessionChunkPolicy.java"
    ).read_text(encoding="utf-8")
    broker_state = (broker_source_root / "BrokerState.java").read_text(
        encoding="utf-8")
    broker_compile_properties = (
        root / "preview" / "modelservicecheck" / "src" / "main" / "java" /
        "com" / "aios" / "modelbroker" / "BrokerProductProperties.java"
    ).read_text(encoding="utf-8")
    model_admission_smoke_manifest = (
        root / "preview" / "modelservicecheck" / "src" / "debug" /
        "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    model_admission_smoke = (
        root / "preview" / "modelservicecheck" / "src" / "debug" / "java" /
        "com" / "aios" / "modelbroker" / "ModelAdmissionSmokeActivity.java"
    ).read_text(encoding="utf-8")
    model_admission_smoke_runner = (
        root / "scripts" / "emulator-model-admission-smoke.ps1"
    ).read_text(encoding="utf-8")
    model_preview_settings = (root / "preview" / "settings.gradle.kts").read_text(
        encoding="utf-8")
    broker_host_test = broker_bp[broker_bp.index("java_test_host {"):]
    require('name: "aios_modelbroker_host_tests"' in broker_host_test
            and '"src/com/aios/modelbroker/BuildFingerprintPolicy.java"'
            in broker_host_test
            and '"src/com/aios/modelbroker/CatalogTierPlanner.java"'
            in broker_host_test
            and '"src/com/aios/modelbroker/PolicyFileReader.java"' in broker_host_test
            and '"src/com/aios/modelbroker/RuntimeCandidatePolicy.java"'
            in broker_host_test
            and '"src/com/aios/modelbroker/RuntimePressurePolicy.java"'
            in broker_host_test
            and '"src/com/aios/modelbroker/SessionChunkPolicy.java"'
            in broker_host_test
            and '"src/com/aios/modelbroker/SessionDeadlinePolicy.java"'
            in broker_host_test
            and '"src/com/aios/modelbroker/SessionDeadlineQueue.java"'
            in broker_host_test
            and '"src/com/aios/modelbroker/RuntimeActivationState.java"'
            in broker_host_test
            and '"src/com/aios/modelbroker/VerifiedArtifact.java"'
            in broker_host_test
            and '"tests/src/**/*.java"' in broker_host_test,
            "Soong Model Broker host tests must include bounded policy and deadline logic")
    require('"aios_runtime_common"' in broker_bp
            and 'from("../../runtime/common/src/main/java")'
            in model_service_compile_build,
            "Model Broker must compile the same trim policy as runtime providers")
    require("LIFECYCLE_BOUND = Long.MAX_VALUE" in broker_deadline_policy
            and "MAX_FINITE_HORIZON_MILLIS" in broker_deadline_policy
            and '"streaming_asr".equals(capability)' in broker_deadline_policy
            and "SessionDeadlinePolicy.validAt" in broker_state,
            "broker must bound finite deadlines and reserve lifecycle mode for streaming ASR")
    require("MAX_BOUNDED_CHUNKS = 4_096L" in broker_chunk_policy
            and "MAX_BOUNDED_CHARS = 4L * 1024L * 1024L" in broker_chunk_policy
            and "CALL_MIN_MILLIS_PER_CHUNK = 100L" in broker_chunk_policy
            and "CALL_TIMELINE_LEAD_MILLIS = 10_000L" in broker_chunk_policy,
            "broker output policy must bound finite/media output and rate-limit live ASR")
    require('include(":modelservicecheck")' in model_preview_settings
            and 'include("com/aios/modelbroker/**/*.java")'
            in model_service_compile_build
            and 'exclude("com/aios/modelbroker/BrokerProductProperties.java")'
            in model_service_compile_build
            and '../../services/modelbroker/aidl' in model_service_compile_build
            and '../../services/runtimeapi/aidl' in model_service_compile_build
            and 'android.os.SystemProperties' in broker_product_properties
            and '"ro.debuggable"' in broker_product_properties
            and "return false;" in broker_compile_properties
            and "SystemProperties" not in broker_compile_properties
            and "abortOnError" not in model_service_compile_build,
            "the complete Model Broker compile lane must fail closed for research admission")
    require('applicationId = "com.aios.modelbenchmark"'
            in model_service_compile_build
            and "ModelAdmissionSmokeActivity" in model_admission_smoke_manifest
            and "IAiosModelService.Stub.asInterface" in model_admission_smoke
            and "new ArtifactVerifier(fixtureRoot).verifyAll()" in model_admission_smoke
            and "CatalogPolicy.load(catalogFile)" in model_admission_smoke
            and "DeviceModelAdmission.load(admissionFile)" in model_admission_smoke
            and "BuildFingerprintPolicy.sha256(Build.FINGERPRINT)"
            in model_admission_smoke
            and "temporary non-model bytes" in model_admission_smoke
            and "AIOS_MODEL_ADMISSION_SMOKE_OK" in model_admission_smoke
            and "Refusing to run model-admission smoke checks on non-emulator serial"
            in model_admission_smoke_runner
            and "real_inference_executed = $false" in model_admission_smoke_runner
            and "physical_gate_evidence = $false" in model_admission_smoke_runner,
            "Model Broker needs guarded Android artifact/admission evidence without fake inference")
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
            and "verifyFile(modelId + \"/\" + name, locked, verifiedDigests)"
            in verifier_source
            and "Map<String, String> verifiedDigests = new HashMap<>()"
            in verifier_source
            and "verifiedDigests.put(artifact.getPath(), actualDigest)"
            in verifier_source,
            "artifact verifier must reverify every locked bundle member")
    require("MAX_ARTIFACT_MANIFEST_BYTES" in verifier_source
            and "MAX_BUNDLE_DESCRIPTOR_BYTES" in verifier_source
            and "total > maximumBytes" in verifier_source,
            "artifact and bundle JSON reads must be explicitly bounded")
    broker_state = (broker_source_root / "BrokerState.java").read_text(encoding="utf-8")
    require("RuntimeRegistry.modelFree()" in broker_state
            and "RuntimeRegistry.load" in broker_state,
            "runtime loading must retain a fail-closed fallback")
    runtime_candidate_policy = (
        broker_source_root / "RuntimeCandidatePolicy.java"
    ).read_text(encoding="utf-8")
    runtime_candidate_test = (
        root / "services" / "modelbroker" / "tests" / "src" / "com" / "aios" /
        "modelbroker" / "RuntimeCandidatePolicyTest.java"
    ).read_text(encoding="utf-8")
    runtime_pressure_policy = (
        broker_source_root / "RuntimePressurePolicy.java"
    ).read_text(encoding="utf-8")
    runtime_pressure_test = (
        root / "services" / "modelbroker" / "tests" / "src" / "com" / "aios" /
        "modelbroker" / "RuntimePressurePolicyTest.java"
    ).read_text(encoding="utf-8")
    memory_trim_policy = (
        root / "runtime" / "common" / "src" / "main" / "java" / "com" /
        "aios" / "runtime" / "common" / "RuntimeMemoryTrimPolicy.java"
    ).read_text(encoding="utf-8")
    memory_trim_test = (
        root / "runtime" / "common" / "tests" / "src" / "com" / "aios" /
        "runtime" / "common" / "RuntimeMemoryTrimPolicyTest.java"
    ).read_text(encoding="utf-8")
    runtime_activation_state = (
        broker_source_root / "RuntimeActivationState.java"
    ).read_text(encoding="utf-8")
    runtime_activation_test = (
        root / "services" / "modelbroker" / "tests" / "src" / "com" / "aios" /
        "modelbroker" / "RuntimeActivationStateTest.java"
    ).read_text(encoding="utf-8")
    require("RuntimeCandidatePolicy.capabilities" in broker_state
            and "RuntimeCandidatePolicy.requestCandidates" in broker_state
            and "request.allowFallback" in broker_state
            and "!existing.available && available" in runtime_candidate_policy
            and "firstUnavailable" in runtime_candidate_policy
            and "if (!allowFallback)" in runtime_candidate_policy
            and "record.activation.beginNext()" in session_controller
            and "callbackFor(record, attempt)" in session_controller
            and "record.activation.allowCallback" in session_controller
            and "List.copyOf(candidates)" in runtime_activation_state
            and "current != attempt" in runtime_activation_state
            and "accepted == attempt" in runtime_activation_state
            and "readyPrimaryWinsForCapabilitiesAndRequests"
            in runtime_candidate_test
            and "readyFallbackReplacesUnavailablePrimary"
            in runtime_candidate_test
            and "languageAndNoRuntimeCasesRemainFailClosed"
            in runtime_candidate_test
            and "noFallbackBindsRequestToPrimaryEvenWhenFallbackIsReady"
            in runtime_candidate_test
            and "fallbackOptInCarriesCompleteOrderedActivationChain"
            in runtime_candidate_test
            and "rejectionAdvancesThroughTheExactOrderedChain"
            in runtime_activation_test
            and "earlyCallbackRejectsAttemptAndStaleCallbackCannotHitFallback"
            in runtime_activation_test
            and "unresolvedAttemptCannotBeSkipped" in runtime_activation_test,
            "broker must honor fallback opt-in through race-safe ordered activation")
    require("ActivityManager.MemoryInfo" in broker_state
            and "memory.lowMemory" in broker_state
            and "powerManager.getCurrentThermalStatus()" in broker_state
            and "RuntimePressurePolicy.order" in broker_state
            and "Decision.BLOCK_BACKGROUND" in broker_state
            and "PREFER_LOWER_MEMORY" in runtime_pressure_policy
            and "estimatedResidentMb" in runtime_pressure_policy
            and "constrainedCallPrefersLowerMeasuredResidentMemory"
            in runtime_pressure_test
            and "constrainedOrUnmeasurableBackgroundWorkIsBlocked"
            in runtime_pressure_test
            and "level == RUNNING_LOW" in memory_trim_policy
            and "level == RUNNING_CRITICAL" in memory_trim_policy
            and "level >= BACKGROUND" in memory_trim_policy
            and "moderateRunningAndUiHiddenCallbacksKeepWarmModels"
            in memory_trim_test,
            "new requests must use fail-closed live pressure policy with exact trim semantics")
    admission_source = (broker_source_root / "DeviceModelAdmission.java").read_text(
        encoding="utf-8"
    )
    build_fingerprint_policy = (
        broker_source_root / "BuildFingerprintPolicy.java"
    ).read_text(encoding="utf-8")
    build_fingerprint_test = (
        root / "services" / "modelbroker" / "tests" / "src" / "com" / "aios" /
        "modelbroker" / "BuildFingerprintPolicyTest.java"
    ).read_text(encoding="utf-8")
    require("DeviceModelAdmission.load" in broker_state
            and "Build.DEVICE" in broker_state
            and "Build.FINGERPRINT" in broker_state
            and "BuildFingerprintPolicy.sha256" in broker_state
            and "BrokerProductProperties.isDebuggableBuild()" in broker_state
            and "SystemProperties" not in broker_state
            and '"model_admission.json"' in broker_state
            and '"deny".equals(root.getString("default_action"))' in admission_source
            and "artifactSha256.equals(artifact.sha256)" in admission_source
            and "BuildFingerprintPolicy.matches" in admission_source
            and "one admission profile cannot span multiple build fingerprints"
            in admission_source
            and "STATUS_PENDING.equals(profile.status) && debuggable" in admission_source,
            "broker model selection must be device/build-scoped, digest-bound, and debug-only while unbenchmarked")
    require('MessageDigest.getInstance("SHA-256")' in build_fingerprint_policy
            and "MessageDigest.isEqual" in build_fingerprint_policy
            and "hashesTheExactUtf8Fingerprint" in build_fingerprint_test
            and "onlyExactLowercaseDigestsMatch" in build_fingerprint_test,
            "build admission must hash the exact fingerprint and compare it fail closed")
    runtime_registry = (broker_source_root / "RuntimeRegistry.java").read_text(
        encoding="utf-8"
    )
    require("BrokerProductProperties.isDebuggableBuild()" in runtime_registry
            and "SystemProperties" not in runtime_registry
            and "adapter.supportsBackend(artifact.backend)" in runtime_registry,
            "runtime activation must honor device/debug and backend policy")
    policy_reader = (broker_source_root / "PolicyFileReader.java").read_text(
        encoding="utf-8")
    policy_reader_test = (
        root / "services" / "modelbroker" / "tests" / "src" / "com" / "aios" /
        "modelbroker" / "PolicyFileReaderTest.java"
    ).read_text(encoding="utf-8")
    authorized_policy = (broker_source_root / "AuthorizedClientPolicy.java").read_text(
        encoding="utf-8")
    catalog_policy = (broker_source_root / "CatalogPolicy.java").read_text(
        encoding="utf-8")
    verified_artifact = (broker_source_root / "VerifiedArtifact.java").read_text(
        encoding="utf-8")
    catalog_tier_planner = (broker_source_root / "CatalogTierPlanner.java").read_text(
        encoding="utf-8")
    catalog_tier_planner_test = (
        root / "services" / "modelbroker" / "tests" / "src" / "com" / "aios" /
        "modelbroker" / "CatalogTierPlannerTest.java"
    ).read_text(encoding="utf-8")
    require("CatalogTierPlanner.candidates" in catalog_policy
            and 'value.has("fallback_tier")' in catalog_policy
            and 'value.getLong("estimated_resident_mb")' in catalog_policy
            and "withEstimatedResidentMb" in catalog_policy
            and "estimatedResidentMb" in verified_artifact
            and "fallback.minTotalRamMb >= current.minTotalRamMb"
            in catalog_tier_planner
            and "highestEligibleTierPrecedesDeduplicatedFallbacks"
            in catalog_tier_planner_test
            and "cyclesUnknownTargetsAndUpwardFallbacksFailClosed"
            in catalog_tier_planner_test,
            "catalog selection must prefer the measured tier and fail closed across fallbacks")
    require("MAX_POLICY_BYTES = 2 * 1024 * 1024" in policy_reader
            and "policy file was truncated" in policy_reader
            and "policy file grew while reading" in policy_reader
            and "PolicyFileReader.readUtf8" in authorized_policy
            and "PolicyFileReader.readUtf8" in catalog_policy
            and "PolicyFileReader.readUtf8" in admission_source
            and "PolicyFileReader.readUtf8" in runtime_registry
            and "Files.readString" not in authorized_policy
            and "Files.readString" not in catalog_policy
            and "Files.readString" not in runtime_registry
            and "rejectsMissingEmptyAndOversizedPolicies" in policy_reader_test,
            "broker policy reads must be Android-compatible, bounded, race-aware, and host-tested")
    remote_runtime = (broker_source_root / "RemoteRuntimeAdapter.java").read_text(
        encoding="utf-8"
    )
    require("MATCH_SYSTEM_ONLY" in remote_runtime
            and "PROVIDE_MODEL_RUNTIME" in remote_runtime
            and "getImplementationVersion" in remote_runtime,
            "runtime providers must be system, signature-authorized, and version-pinned")
    require("onBindingDied" in remote_runtime
            and "replaceTerminalBinding(this" in remote_runtime
            and "unbindService(connection)" in remote_runtime
            and "scheduleRebind(immediate)" in remote_runtime
            and "CONNECT_TIMEOUT_MILLIS" in remote_runtime
            and "armConnectionTimeout" in remote_runtime
            and "provider == current" in remote_runtime,
            "runtime bindings must recover from terminal death and missing callbacks, while session opens reject provider races")
    require("Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT" in remote_runtime
            and "acquirePriorityBinding" in remote_runtime
            and "releasePriorityOnce" in remote_runtime
            and '"PRIORITY_ACQUIRED runtime="' in remote_runtime
            and '"PRIORITY_RELEASED runtime="' in remote_runtime,
            "active inference must temporarily leave Android's restricted CPU set")
    require('TAG = "AiosRemoteRuntime"' in remote_runtime
            and '"OPEN runtime="' in remote_runtime
            and '"FIRST_CHUNK runtime="' in remote_runtime
            and '"COMPLETED runtime="' in remote_runtime
            and '"ERROR runtime="' in remote_runtime,
            "model broker must log privacy-safe provider lifecycle and failures")
    require("linkToDeath" in session_controller
            and "requireOwner(record, ownerUid)" in session_controller
            and "MAX_PENDING_INPUTS" in session_controller,
            "runtime sessions must handle client death, UID ownership, and input bounds")
    arbiter_source = (broker_source_root / "SessionArbiter.java").read_text(
        encoding="utf-8"
    )
    work_class_source = (broker_source_root / "WorkClass.java").read_text(
        encoding="utf-8"
    )
    require("EmbeddingRequestPolicy.accepts" in broker_state
            and "value.selectedModelDigest = choice.artifact.sha256" in broker_state
            and '"context_background".equals(request.workload)' in broker_state
            and 'case "context_query"' in work_class_source
            and 'case "context_background"' in work_class_source,
            "embedding queries must be interactive while background indexing remains call-preemptible")
    runtime_pressure_source = (
        broker_source_root / "RuntimePressurePolicy.java"
    ).read_text(encoding="utf-8")
    capacity_source = (broker_source_root / "SessionCapacityPolicy.java").read_text(
        encoding="utf-8"
    )
    capacity_loader = (broker_source_root / "BrokerCapacityPolicy.java").read_text(
        encoding="utf-8"
    )
    capacity_test = (
        root / "services" / "modelbroker" / "tests" / "src" / "com" / "aios" /
        "modelbroker" / "SessionCapacityPolicyTest.java"
    ).read_text(encoding="utf-8")
    arbiter_test = (
        root / "services" / "modelbroker" / "tests" / "src" / "com" / "aios" /
        "modelbroker" / "SessionArbiterTest.java"
    ).read_text(encoding="utf-8")
    require("mediaBlocked()" in arbiter_source
            and "callActive = true" not in arbiter_source,
            "foreground sessions must not leave a sticky media gate after completion")
    require('"product_policy.json"' in broker_state
            and "BrokerCapacityPolicy.load" in broker_state
            and "state.sessionCapacityPolicy()" in service
            and "new SessionController(state.runtimes(), 3)" not in service
            and "PolicyFileReader.readUtf8" in capacity_loader
            and "value instanceof Integer" in capacity_loader
            and "value instanceof Boolean" in capacity_loader,
            "broker session capacities must load fail closed from product policy")
    require("callAsrStreamCapacity" in capacity_source
            and "callAgentCapacity" in capacity_source
            and "sharesActivePool" in capacity_source
            and "canActivate(workClass)" in arbiter_source
            and "hasClassHeadroom(next.workClass)" in arbiter_source
            and "rxAndTxShareTwoStreamCapacity" in arbiter_test
            and "rxPreemptsTxWhenSharedPoolIsFull" in arbiter_test
            and "promotionSkipsSaturatedClassWithoutBreakingPriorityOrder"
            in arbiter_test
            and "mapsRxAndTxToOneSharedAsrPool" in capacity_test,
            "broker must enforce host-tested global, shared-ASR, and call-agent capacities")

    provider_root = root / "runtime" / "litertlmprovider"
    runtime_provider_preview = (
        root / "preview" / "runtimeprovidercheck" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    runtime_provider_smoke_manifest = (
        root / "preview" / "runtimeprovidercheck" / "src" / "main" /
        "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    runtime_provider_smoke = (
        root / "preview" / "runtimeprovidercheck" / "src" / "main" / "java" /
        "com" / "aios" / "runtime" / "smoke" /
        "RuntimeProviderSmokeActivity.java"
    ).read_text(encoding="utf-8")
    runtime_provider_smoke_runner = (
        root / "scripts" / "emulator-runtime-provider-smoke.ps1"
    ).read_text(encoding="utf-8")
    runtime_trim_policy = (
        root / "runtime" / "common" / "src" / "main" / "java" / "com" /
        "aios" / "runtime" / "common" / "RuntimeMemoryTrimPolicy.java"
    ).read_text(encoding="utf-8")
    runtime_trim_test = (
        root / "runtime" / "common" / "tests" / "src" / "com" / "aios" /
        "runtime" / "common" / "RuntimeMemoryTrimPolicyTest.java"
    ).read_text(encoding="utf-8")
    runtime_common_bp = (root / "runtime" / "common" / "Android.bp").read_text(
        encoding="utf-8")
    runtime_common_preview = (
        root / "preview" / "runtimecommoncheck" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    require("level == RUNNING_LOW" in runtime_trim_policy
            and "level == RUNNING_CRITICAL" in runtime_trim_policy
            and "level >= BACKGROUND" in runtime_trim_policy
            and "moderateRunningAndUiHiddenCallbacksKeepWarmModels"
            in runtime_trim_test
            and 'name: "aios_runtime_common"' in runtime_common_bp
            and "host_supported: true" in runtime_common_bp
            and 'name: "aios_runtime_common_host_tests"' in runtime_common_bp
            and 'include(":runtimecommoncheck")' in model_preview_settings
            and 'java.srcDir("../../runtime/common/src/main/java")'
            in runtime_common_preview,
            "runtime providers must share host-tested non-monotonic trim semantics")
    require('include(":runtimeprovidercheck")' in model_preview_settings
            and 'applicationId = "com.aios.modelbroker"'
            in runtime_provider_preview
            and '../../services/modelbroker/aidl' in runtime_provider_preview
            and '../../services/runtimeapi/aidl' in runtime_provider_preview
            and "com.aios.permission.PROVIDE_MODEL_RUNTIME"
            in runtime_provider_smoke_manifest
            and "IAiosRuntimeProvider.Stub.asInterface" in runtime_provider_smoke
            and "com.aios.runtime.litertlm.LiteRtLmRuntimeService"
            in runtime_provider_smoke
            and "outside the read-only model directory" in runtime_provider_smoke
            and "AIOS_RUNTIME_PROVIDER_SMOKE_OK" in runtime_provider_smoke
            and "AIOS_RUNTIME_REAL_INFERENCE_OK" in runtime_provider_smoke
            and "streamed chunks and terminal inference output diverged"
            in runtime_provider_smoke
            and "Refusing to run runtime-provider smoke checks on non-emulator serial"
            in runtime_provider_smoke_runner
            and "AIOS_RUNTIME_REAL_INFERENCE_OK" in runtime_provider_smoke_runner
            and "real_inference_executed = $runRealInference"
            in runtime_provider_smoke_runner
            and "temporary_fixture_bytes_are_model_weights = $runRealInference"
            in runtime_provider_smoke_runner
            and "inference_output_recorded = $false"
            in runtime_provider_smoke_runner
            and "provider_apk_x86_64_native_entry_verified = $true"
            in runtime_provider_smoke_runner
            and "arm64_provider_evidence = $false" in runtime_provider_smoke_runner
            and "physical_gate_evidence = $false" in runtime_provider_smoke_runner,
            "LiteRT-LM needs guarded optional real-inference emulator evidence")
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
            and "observed.sizeBytes == artifact.sizeBytes" in provider_source
            and "MODEL_DIGEST_CACHE_HIT" in provider_source
            and "model changed during digest verification" in provider_source
            and "BasicFileAttributes" in provider_source
            and "LinkOption.NOFOLLOW_LINKS" in provider_source
            and "BuildConfig.ALLOW_EMULATOR_MODEL_FIXTURES" in provider_source
            and 'Build.HARDWARE.equals("ranchu"' in provider_source
            and 'Build.HARDWARE.equals("goldfish"' in provider_source
            and 'Build.PRODUCT.contains("sdk"' in provider_source
            and 'Build.FINGERPRINT.startsWith("generic")' in provider_source,
            "runtime provider must reverify model confinement, size, and digest, with a QEMU-only debug fixture path")
    require("automaticToolCalling = false" in provider_source
            and "conversation?.cancelProcess()" in provider_source,
            "runtime provider must disable tools and support native cancellation")
    require('"video_understanding"' in provider_source
            and "Content.ImageBytes(bytes)" in provider_source
            and "chronological 5 by 4 storyboard" in provider_source
            and "visionBackend" in provider_source
            and "val vision = !audio" in provider_source
            and "MAX_RESIDENT_ENGINES = 2" in provider_source
            and "ENGINE_CACHE_HIT" in provider_source
            and "ENGINE_CACHE_EVICT" in provider_source,
            "LiteRT-LM must process sampled video storyboards through the vision backend")
    require("RuntimeMemoryTrimPolicy.isMemoryPressure(level)" in provider_source
            and "TRIM_MEMORY_RUNNING_LOW" not in provider_source
            and "sessions.isEmpty()" in provider_source
            and "closeEngine()" in provider_source,
            "LiteRT-LM must release an idle engine under Android memory pressure")
    require('TAG = "AiosLiteRtLmRuntime"' in provider_source
            and '"ENGINE_INITIALIZE_START' in provider_source
            and '"FIRST_TOKEN' in provider_source
            and '"INFERENCE_FAILED' in provider_source
            and 'Log.e(TAG, "PREPARE_FAILED' in provider_source,
            "LiteRT-LM must expose privacy-safe stage timing and exception logs")
    provider_build = (provider_root / "app" / "build.gradle.kts").read_text(
        encoding="utf-8"
    )
    require("litertlm-android:0.15.0" in provider_build
            and "lockAllConfigurations" in provider_build
            and "dependency_verification_sha256" in provider_build
            and "JavaVersion.VERSION_17" in provider_build
            and "JvmTarget.JVM_17" in provider_build
            and 'sourceSets["main"].java.srcDir("../../common/src/main/java")'
            in provider_build
            and provider_build.count(
                'buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "false")'
            ) == 2
            and provider_build.count(
                'buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "true")'
            ) == 1,
            "runtime build must pin LiteRT-LM, use JVM 17, emit locked provenance, and disable fixture paths in release")
    provider_bootstrap = (provider_root / "bootstrap_dependency_locks.sh").read_text(
        encoding="utf-8"
    )
    provider_release_build = (provider_root / "build_provider.sh").read_text(
        encoding="utf-8"
    )
    require(":app:dependencies" in provider_bootstrap
            and ":app:assembleRelease" in provider_bootstrap
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
            in provider_verification
            and "aapt2-8.10.1-12782657-linux.jar" in provider_verification
            and "52f864b7fd20a9ff09fc3db96162537a63c5b38ecc1c2549db4b491c6a517ff0"
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
    require("RuntimeMemoryTrimPolicy.isMemoryPressure(level)" in whisper_source
            and "TRIM_MEMORY_RUNNING_LOW" not in whisper_source
            and "synchronized(modelLock)" in whisper_source
            and "closeModelLocked()" in whisper_source,
            "ASR runtime must safely release an idle model under memory pressure")
    require("MODEL_DIRECTORY.canonicalFile" in whisper_source
            and "MessageDigest.isEqual" in whisper_source
            and "model.length() == artifact.sizeBytes" in whisper_source,
            "ASR runtime must reverify model confinement, size, and digest")
    require('TAG = "AiosWhisperRuntime"' in whisper_source
            and '"MODEL_INITIALIZE_START' in whisper_source
            and '"DECODE_START' in whisper_source
            and '"DECODE_FAILED' in whisper_source
            and '"SESSION_DONE' in whisper_source,
            "ASR runtime must expose privacy-safe model and decode diagnostics")
    require("BuildConfig.ALLOW_EMULATOR_MODEL_FIXTURES" in whisper_source
            and 'Build.HARDWARE.equals("ranchu"' in whisper_source
            and 'Build.HARDWARE.equals("goldfish"' in whisper_source
            and "File(filesDir, EMULATOR_FIXTURE_DIRECTORY).canonicalFile"
            in whisper_source,
            "private ASR model fixtures must remain debug-QEMU-only")
    whisper_cmake = (whisper_root / "app" / "src" / "main" / "cpp" /
                     "CMakeLists.txt").read_text(encoding="utf-8")
    whisper_build = (whisper_root / "app" / "build.gradle.kts").read_text(
        encoding="utf-8")
    runtime_catalog = load_json(root / "config" / "runtime_catalog.json")
    whisper_providers = [provider for provider in runtime_catalog["providers"]
                         if provider.get("runtime") == "whisper_cpp"]
    whisper_version = (whisper_providers[0]["implementation_version"]
                       if len(whisper_providers) == 1 else "")
    require(len(whisper_providers) == 1
            and f'const val IMPLEMENTATION_VERSION = "{whisper_version}"'
            in whisper_source
            and f'versionName = "{whisper_version}"' in whisper_build
            and f'"implementation_version" to "{whisper_version}"'
            in whisper_build,
            "Whisper implementation identity must match its runtime catalog entry")
    require("CMAKE_CXX_STANDARD 17" in whisper_cmake
            and 'if(ANDROID_ABI STREQUAL "x86_64")' in whisper_cmake
            and "add_compile_options(-O3)" in whisper_cmake
            and 'if(ANDROID_ABI STREQUAL "arm64-v8a")' in whisper_cmake
            and "armv8.6-a+fp16+dotprod+i8mm" in whisper_cmake
            and 'message(FATAL_ERROR "Unsupported AIOS whisper.cpp ABI:'
            in whisper_cmake
            and "WHISPER_BUILD_TESTS OFF" in whisper_cmake
            and whisper_build.count(
                'buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "false")'
            ) == 2
            and whisper_build.count(
                'buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "true")'
            ) == 1
            and 'ndk { abiFilters += "x86_64" }' in whisper_build
            and 'ndk { abiFilters += "arm64-v8a" }' in whisper_build
            and 'testImplementation("junit:junit:4.13.2")' in whisper_build
            and 'sourceSets["main"].java.srcDir("../../common/src/main/java")'
            in whisper_build,
            "ASR native build must keep release arm64 and guarded debug x86 profiles")
    whisper_bootstrap = (whisper_root / "bootstrap_dependency_locks.sh").read_text(
        encoding="utf-8"
    )
    whisper_release_build = (whisper_root / "build_provider.sh").read_text(
        encoding="utf-8"
    )
    require(":app:dependencies" in whisper_bootstrap
            and ":app:assembleRelease" in whisper_bootstrap
            and "--dependency-verification=strict" in whisper_bootstrap
            and "app/gradle.lockfile" in whisper_release_build,
            "ASR lock bootstrap must precede strict offline provenance build")
    whisper_lock = (whisper_root / "app" / "gradle.lockfile").read_text(
        encoding="utf-8"
    )
    whisper_verification = (whisper_root / "gradle" /
                            "verification-metadata.xml").read_text(encoding="utf-8")
    require("junit:junit:4.13.2=" in whisper_lock
            and "junit-4.13.2.jar" in whisper_verification
            and "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3"
            in whisper_verification
            and "aapt2-8.10.1-12782657-linux.jar" in whisper_verification
            and "52f864b7fd20a9ff09fc3db96162537a63c5b38ecc1c2549db4b491c6a517ff0"
            in whisper_verification,
            "ASR dependency lock and verification digest must match reviewed JUnit")
    whisper_properties = (whisper_root / "gradle.properties").read_text(
        encoding="utf-8"
    )
    require("org.gradle.configuration-cache=false" in whisper_properties,
            "ASR provenance build must disable incompatible configuration caching")
    whisper_jni = (whisper_root / "app" / "src" / "main" / "cpp" /
                   "aios_whisper_jni.cpp").read_text(encoding="utf-8")
    require("params.language = language_chars;" in whisper_jni
            and "params.detect_language = false;" in whisper_jni
            and 'params.detect_language = std::strcmp(language_chars, "auto") == 0;'
            not in whisper_jni,
            "Whisper auto language selection must transcribe instead of detect-only exit")
    require("params.single_segment = true;" in whisper_jni
            and "params.max_tokens = 32;" in whisper_jni
            and "params.temperature_inc = -1.0f;" in whisper_jni
            and "params.audio_ctx = bounded_audio_context(context, sample_count);"
            in whisper_jni
            and "MEL_HOP_SAMPLES = 160" in whisper_jni
            and "ENCODER_DOWNSAMPLE = 2" in whisper_jni
            and "AUDIO_CONTEXT_QUANTUM = 64" in whisper_jni
            and "MIN_AUDIO_CONTEXT = 256" in whisper_jni
            and "whisper_model_n_audio_ctx(context)" in whisper_jni
            and "audio_ctx=%d" in whisper_jni
            and 'LOG_TAG[] = "AiosWhisperNative"' in whisper_jni
            and '"DECODE_NATIVE_DONE' in whisper_jni
            and '"DECODE_NATIVE_FAILED' in whisper_jni,
            "Whisper live-call decode must bound tokens, retries, and short-window encoder context with privacy-safe native timing")
    require("AIOS_ARM64_COMPUTE_TARGETS whisper ggml-base ggml-cpu" in whisper_cmake
            and 'target_compile_options("${compute_target}" PRIVATE' in whisper_cmake
            and "-march=armv8.6-a+fp16+dotprod+i8mm" in whisper_cmake,
            "Whisper arm64 optimization flags must reach the actual whisper/ggml compute targets")
    whisper_native_api = (whisper_root / "app" / "src" / "main" / "java" /
                          "com" / "aios" / "runtime" / "whispercpp" /
                          "NativeWhisper.kt").read_text(encoding="utf-8")
    decode_fence = (whisper_root / "app" / "src" / "main" / "java" /
                    "com" / "aios" / "runtime" / "whispercpp" /
                    "DecodeCancellationFence.java").read_text(encoding="utf-8")
    decode_fence_test = (whisper_root / "app" / "src" / "test" / "java" /
                         "com" / "aios" / "runtime" / "whispercpp" /
                         "DecodeCancellationFenceTest.java").read_text(encoding="utf-8")
    require("params.abort_callback = abort_decode" in whisper_jni
            and "params.abort_callback_user_data = cancellation" in whisper_jni
            and "std::atomic<bool> cancelled" in whisper_jni
            and "memory_order_acquire" in whisper_jni
            and "memory_order_release" in whisper_jni
            and "external fun createCancellation(): Long" in whisper_native_api
            and "external fun destroyCancellation(cancellation: Long)"
            in whisper_native_api
            and "session.decodeCancellation.attach" in whisper_source
            and "session.decodeCancellation.finish" in whisper_source
            and whisper_source.count("session.decodeCancellation.cancel") == 2
            and "synchronized void attach" in decode_fence
            and "synchronized void cancel" in decode_fence
            and "synchronized void finish" in decode_fence
            and "activeToken != token" in decode_fence
            and "activeTokenIsCancelledAndDestroyedExactlyOnce"
            in decode_fence_test
            and "cancellationBeforeAttachAbortsTheNextToken" in decode_fence_test
            and "staleFinishCannotDestroyTheCurrentToken" in decode_fence_test,
            "ASR cancellation must abort active native compute without token lifetime races")

    whisper_smoke = (
        root / "preview" / "runtimeprovidercheck" / "src" / "main" / "java" /
        "com" / "aios" / "runtime" / "smoke" / "WhisperProviderSmokeActivity.java"
    ).read_text(encoding="utf-8")
    whisper_smoke_runner = (
        root / "scripts" / "emulator-whisper-provider-smoke.ps1"
    ).read_text(encoding="utf-8")
    whisper_fixture_bootstrap = (
        root / "scripts" / "bootstrap-emulator-asr-fixtures.ps1"
    ).read_text(encoding="utf-8")
    require('"AIOS_WHISPER_REAL_ASR_OK"' in whisper_smoke
            and '"AIOS_WHISPER_PROVIDER_SMOKE_OK"' in whisper_smoke
            and 'artifact.modelId = "whisper-base-multilingual-quantized"'
            in whisper_smoke
            and 'request.workload = "call_rx"' in whisper_smoke
            and 'transcribe(remote, model, english, "real-asr-english", "en", "country")'
            in whisper_smoke
            and 'transcribe(remote, model, spanish, "real-asr-spanish", "es", "ayudar")'
            in whisper_smoke
            and "finalChunkCount > 0" in whisper_smoke
            and "normalizedFinalText.contains(requiredContentMarker)" in whisper_smoke
            and "WALL_PACE_MILLIS = 250L" in whisper_smoke
            and "Log.i(TAG, normalizedFinalText)" not in whisper_smoke,
            "Whisper smoke must require private bilingual call-RX inference without logging text")
    required_asr_fixture_digests = {
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e",
        "70ef4a2b564905d07f626af2adc2df958f9de584c120f3b9d2278158712d1d70",
    }
    require(all(digest in whisper_fixture_bootstrap
                and digest in whisper_smoke_runner
                for digest in required_asr_fixture_digests)
            and "Refusing to run whisper-provider smoke checks on non-emulator serial"
            in whisper_smoke_runner
            and '$abi -ne "x86_64"' in whisper_smoke_runner
            and "provider_apk_x86_64_native_entry_verified = $true"
            in whisper_smoke_runner
            and "nonempty_final_transcripts_verified = $true"
            in whisper_smoke_runner
            and "fixture_content_markers_verified = $true" in whisper_smoke_runner
            and "english_language_detected = $true" in whisper_smoke_runner
            and "spanish_language_detected = $true" in whisper_smoke_runner
            and "source_audio_chunk_millis = 100" in whisper_smoke_runner
            and "wall_pace_per_chunk_millis = 250" in whisper_smoke_runner
            and "emulator_real_time_gate = $false" in whisper_smoke_runner
            and "transcript_output_recorded = $false" in whisper_smoke_runner
            and "temporary_fixture_files_remaining = 0" in whisper_smoke_runner
            and "arm64_provider_evidence = $false" in whisper_smoke_runner
            and "physical_gate_evidence = $false" in whisper_smoke_runner
            and 'gate = "integration.emulator_bilingual_asr_provider"'
            in whisper_smoke_runner
            and "aios_revision = $sourceRevision" in whisper_smoke_runner
            and "tracked_source_clean = $true" in whisper_smoke_runner
            and "git -C $repositoryRoot diff --quiet --" in whisper_smoke_runner
            and "git -C $repositoryRoot diff --cached --quiet --"
            in whisper_smoke_runner
            and "files/emulator-models/runtime-smoke.bin" in whisper_smoke_runner
            and "files/asr-fixtures/english.wav" in whisper_smoke_runner
            and "files/asr-fixtures/spanish.wav" in whisper_smoke_runner,
            "real ASR emulator evidence must be digest-bound, bilingual, self-cleaning, and non-physical")

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
            and '"lang" to session.request.language' in tts_source
            and '"max_len" to CALL_MAX_CHUNK_CODEPOINTS.toString()' in tts_source
            and "CALL_MAX_CHUNK_CODEPOINTS = 64" in tts_source
            and 'request.language in setOf("en", "es")' in tts_source
            and "private inner class PcmStreamingCallback" in tts_source
            and ": Function1<FloatArray, Int>" in tts_source
            and "generateWithConfigAndCallback(text, config, callback)" in tts_source
            and "ParcelFileDescriptor.AutoCloseOutputStream" in tts_source
            and "writePcm16" in tts_source,
            "TTS runtime must use a JNI-compatible callback and stream bilingual PCM with pipe backpressure")
    require("session.cancelled.get()" in tts_source
            and "deadlineElapsedRealtimeMillis" in tts_source
            and "RuntimeMemoryTrimPolicy.isMemoryPressure(level)" in tts_source
            and "TRIM_MEMORY_RUNNING_LOW" not in tts_source
            and "if (sessions.isEmpty()) closeEngine()" in tts_source,
            "TTS runtime must support cancellation, deadlines, and pressure cleanup")
    require('TAG = "AiosTtsRuntime"' in tts_source
            and '"ENGINE_INITIALIZE_START' in tts_source
            and '"FIRST_AUDIO' in tts_source
            and '"AUDIO_CHUNK' in tts_source
            and '"SYNTHESIS_FAILED' in tts_source
            and '"SYNTHESIS_DONE' in tts_source,
            "TTS runtime must expose privacy-safe engine and synthesis diagnostics")
    require('File(configuration, "models").canonicalFile' in tts_source
            and "MessageDigest.isEqual" in tts_source
            and "EXPECTED_MEMBERS" in tts_source
            and "source_archive_sha256" in tts_source,
            "TTS runtime must independently reverify its complete model bundle")
    require("BuildConfig.ALLOW_EMULATOR_MODEL_FIXTURES" in tts_source
            and 'Build.HARDWARE.equals("ranchu"' in tts_source
            and 'Build.HARDWARE.equals("goldfish"' in tts_source
            and "File(filesDir, EMULATOR_FIXTURE_DIRECTORY).canonicalFile"
            in tts_source
            and "configurationDirectoryForDescriptor" in tts_source,
            "private TTS model fixtures must remain debug-QEMU-only and confined")

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
            and bundle["sha256"] in tts_source
            and tts_model["sample_rate_hz"] == 44100
            and "const val SAMPLE_RATE_HZ = 44_100" in tts_source,
            "TTS provider model/archive identity and native rate must match the model catalog")
    for member in bundle["members"]:
        require(member["path"] in tts_source
                and member["sha256"] in tts_source
                and f'{member["size_bytes"]}L' in normalized_tts_source,
                f'TTS provider lock is stale for {member["path"]}')
    model_packager = (root / "tools" / "generate_model_pack.py").read_text(
        encoding="utf-8")
    model_bootstrap = (root / "tools" / "bootstrap_reference_model.py").read_text(
        encoding="utf-8")
    acceptance_recorder = (root / "tools" / "record_model_acceptance.py").read_text(
        encoding="utf-8")
    require("model output directory must be outside the AIOS source tree"
            in model_bootstrap
            and "os.O_CREAT | os.O_EXCL | os.O_WRONLY" in model_bootstrap
            and 'headers["Range"] = f"bytes={offset}-"' in model_bootstrap
            and "actual_digest = sha256(partial)" in model_bootstrap
            and "if actual_digest != expected_digest" in model_bootstrap
            and "os.replace(partial, destination)" in model_bootstrap
            and "refusing symbolic-link download path" in model_bootstrap,
            "reference-model bootstrap must remain external, resumable, atomic, and verified")
    require("acceptance output must be outside the AIOS source tree"
            in acceptance_recorder
            and "license URL mismatch" in acceptance_recorder
            and "os.O_CREAT | os.O_EXCL | os.O_WRONLY" in acceptance_recorder
            and "os.replace(temporary, destination)" in acceptance_recorder,
            "model acceptance must remain explicit, external, atomic, and catalog-bound")
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
    require(tts_build.count(
                'buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "false")'
            ) == 2
            and tts_build.count(
                'buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "true")'
            ) == 1
            and 'ndk { abiFilters += "x86_64" }' in tts_build
            and 'ndk { abiFilters += "arm64-v8a" }' in tts_build
            and "lockAllConfigurations" in tts_build
            and "verifyPinnedInputs" in tts_build
            and "dependency_verification_sha256" in tts_build
            and 'sourceSets["main"].java.srcDir("../../common/src/main/java")'
            in tts_build,
            "TTS APK must keep release arm64, debug x86, and verified provenance")
    tts_lock_bootstrap = (tts_root / "bootstrap_dependency_locks.sh").read_text(
        encoding="utf-8"
    )
    tts_release_build = (tts_root / "build_provider.sh").read_text(
        encoding="utf-8"
    )
    require(":app:dependencies" in tts_lock_bootstrap
            and ":app:assembleRelease" in tts_lock_bootstrap
            and "--dependency-verification=strict" in tts_lock_bootstrap
            and "app/gradle.lockfile" in tts_release_build,
            "TTS lock bootstrap must cover build tools before strict offline provenance")
    tts_lock = (tts_root / "app" / "gradle.lockfile").read_text(
        encoding="utf-8"
    )
    tts_verification = (tts_root / "gradle" /
                        "verification-metadata.xml").read_text(encoding="utf-8")
    require("org.jetbrains.kotlin:kotlin-stdlib:2.2.21=" in tts_lock
            and "kotlin-stdlib-2.2.21.jar" in tts_verification
            and "6558a3d233da56a20934b32159f9db5f86ed5816ef098f78a2c223dc6abb79dd"
            in tts_verification
            and "aapt2-8.10.1-12782657-linux.jar" in tts_verification
            and "52f864b7fd20a9ff09fc3db96162537a63c5b38ecc1c2549db4b491c6a517ff0"
            in tts_verification,
            "TTS dependency lock must include reviewed JVM and Linux build-tool inputs")
    tts_properties = (tts_root / "gradle.properties").read_text(encoding="utf-8")
    require("org.gradle.configuration-cache=false" in tts_properties,
            "TTS provenance build must disable incompatible configuration caching")
    for notice in tts_provider["required_apk_entries"]:
        require(Path(notice["path"]).name in tts_build
                and notice["sha256"] in tts_build,
                f'TTS build does not pin {notice["path"]}')
    tts_smoke = (
        root / "preview" / "runtimeprovidercheck" / "src" / "main" / "java" /
        "com" / "aios" / "runtime" / "smoke" / "TtsProviderSmokeActivity.java"
    ).read_text(encoding="utf-8")
    tts_smoke_runner = (
        root / "scripts" / "emulator-tts-provider-smoke.ps1"
    ).read_text(encoding="utf-8")
    tts_fixture_bootstrap = (
        root / "scripts" / "bootstrap-emulator-tts-fixtures.ps1"
    ).read_text(encoding="utf-8")
    require('"AIOS_TTS_REAL_BILINGUAL_OK"' in tts_smoke
            and '"AIOS_TTS_PROVIDER_SMOKE_OK"' in tts_smoke
            and '"real-tts-english", "en"' in tts_smoke
            and '"real-tts-spanish", "es"' in tts_smoke
            and 'request.capability = "speech_synthesis"' in tts_smoke
            and 'request.workload = "call_agent"' in tts_smoke
            and 'format.direction = "synthesis"' in tts_smoke
            and "output.getLong(\"sample_count\") == metrics.sampleCount"
            in tts_smoke
            and "TTS PCM was effectively silent" in tts_smoke
            and "MAX_PCM_BYTES" in tts_smoke
            and "SAMPLE_RATE_HZ = 44_100" in tts_smoke,
            "TTS smoke must require bounded, non-silent bilingual production PCM")
    require("[switch]$AcceptModelLicense" in tts_fixture_bootstrap
            and "if (-not $AcceptModelLicense)" in tts_fixture_bootstrap
            and tts_model["license_url"] in tts_fixture_bootstrap
            and bundle["sha256"] in tts_fixture_bootstrap
            and str(bundle["size_bytes"]) in tts_fixture_bootstrap
            and "model_license_accepted_for_local_research = $true"
            in tts_fixture_bootstrap
            and "$ttsConfiguration.ae.sample_rate" in tts_fixture_bootstrap
            and "$model.sample_rate_hz" in tts_fixture_bootstrap,
            "TTS emulator bootstrap must require explicit acceptance and exact weights")
    tts_call_client = (root / "services" / "callintelligence" / "src" / "com" /
                       "aios" / "callintelligence" /
                       "SpeechSynthesisBrokerClient.java").read_text(encoding="utf-8")
    tts_caller_converter = (root / "services" / "callintelligence" / "src" /
                            "com" / "aios" / "callintelligence" /
                            "Pcm16MonoToStereo48k.java").read_text(encoding="utf-8")
    require("OUTPUT_SAMPLE_RATE_HZ = 44_100" in tts_call_client
            and "sampleRateHz == 44_100" in tts_caller_converter
            and "format.sampleRateHz != 44_100" in session_controller
            and "TTS_SAMPLE_RATE = 44_100" in benchmark_source
            and "resampleTtsTo16k" in benchmark_source,
            "native Supertonic rate must remain coherent through Broker, calls, and benchmarks")
    require("Refusing to run TTS-provider smoke checks on non-emulator serial"
            in tts_smoke_runner
            and '$abi -ne "x86_64"' in tts_smoke_runner
            and bundle["sha256"] in tts_smoke_runner
            and "provider_apk_x86_64_native_entries_verified = $true"
            in tts_smoke_runner
            and "production_tts_provider_bound_cross_process = $true"
            in tts_smoke_runner
            and "bundle_member_digests_verified = $true" in tts_smoke_runner
            and "english_pcm_verified = $true" in tts_smoke_runner
            and "spanish_pcm_verified = $true" in tts_smoke_runner
            and "pcm_metadata_matches_stream = $true" in tts_smoke_runner
            and "pcm_content_recorded = $false" in tts_smoke_runner
            and "temporary_fixture_files_remaining = 0" in tts_smoke_runner
            and "rm -rf" in tts_smoke_runner
            and "files/emulator-config | Out-Null" in tts_smoke_runner
            and "arm64_provider_evidence = $false" in tts_smoke_runner
            and "physical_gate_evidence = $false" in tts_smoke_runner
            and 'gate = "integration.emulator_bilingual_tts_provider"'
            in tts_smoke_runner
            and "aios_revision = $sourceRevision" in tts_smoke_runner
            and "tracked_source_clean = $true" in tts_smoke_runner
            and "git -C $repositoryRoot diff --quiet --" in tts_smoke_runner
            and "git -C $repositoryRoot diff --cached --quiet --"
            in tts_smoke_runner,
            "real TTS emulator evidence must be bilingual, self-cleaning, and non-physical")
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
    require("removeWorkClass(WorkClass.CALL_BACKGROUND)" in arbiter_source
            and "preemptBackgroundForMemoryPressure" in arbiter_source,
            "foreground calls and memory pressure must cancel preemptible inference")
    require('case "call_background":' in work_class_source
            and "return CALL_BACKGROUND;" in work_class_source
            and "WorkClass.CALL_BACKGROUND" in runtime_pressure_source
            and "Decision.BLOCK_BACKGROUND" in runtime_pressure_source,
            "call compaction must have a pressure-blocked preemptible Broker class")
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
    call_policy_api = (root / "services" / "callintelligence" / "aidl" / "com" /
                       "aios" / "call" / "CallAssistantPolicy.aidl").read_text(
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
            and "in IBinder lifecycleToken" in call_api
            and "void onCallResumed(" in call_api,
            "Call Intelligence must expose death-linked Telecom presence independently of AI")

    call_source_root = (
        root / "services" / "callintelligence" / "src" / "com" / "aios" /
        "callintelligence"
    )
    caller_history_policy = (call_source_root / "CallerHistoryPolicy.java").read_text(
        encoding="utf-8")
    caller_history_test = (root / "services" / "callintelligence" / "tests" /
                           "src" / "com" / "aios" / "callintelligence" /
                           "CallerHistoryPolicyTest.java").read_text(encoding="utf-8")
    caller_history_source_policy = (
        call_source_root / "CallerHistorySourcePolicy.java"
    ).read_text(encoding="utf-8")
    caller_history_conversation_policy = (
        call_source_root / "CallerHistoryConversationPolicy.java"
    ).read_text(encoding="utf-8")
    caller_history_conversation_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "CallerHistoryConversationPolicyTest.java"
    ).read_text(encoding="utf-8")
    caller_history_source_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "CallerHistorySourcePolicyTest.java"
    ).read_text(encoding="utf-8")
    call_service_bp = (root / "services" / "callintelligence" / "Android.bp").read_text(
        encoding="utf-8")
    call_service_compile_build = (
        root / "preview" / "callservicecheck" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    call_product_properties = (
        call_source_root / "CallProductProperties.java"
    ).read_text(encoding="utf-8")
    caller_uplink_admission = (
        call_source_root / "CallerUplinkAdmission.java"
    ).read_text(encoding="utf-8")
    caller_uplink_admission_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "CallerUplinkAdmissionTest.java"
    ).read_text(encoding="utf-8")
    telephony_capture = (call_source_root / "TelephonyAudioCapture.java").read_text(
        encoding="utf-8")
    call_manifest = (
        root / "services" / "callintelligence" / "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    call_compile_properties = (
        root / "preview" / "callservicecheck" / "src" / "main" / "java" /
        "com" / "aios" / "callintelligence" / "CallProductProperties.java"
    ).read_text(encoding="utf-8")
    preview_settings = (root / "preview" / "settings.gradle.kts").read_text(
        encoding="utf-8")
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
    incremental_transcript_source = (
        call_source_root / "IncrementalCallerTranscript.java"
    ).read_text(encoding="utf-8")
    incremental_transcript_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "IncrementalCallerTranscriptTest.java"
    ).read_text(encoding="utf-8")
    transcript_revision_gate_source = (
        call_source_root / "TranscriptRevisionGate.java"
    ).read_text(encoding="utf-8")
    transcript_revision_gate_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "TranscriptRevisionGateTest.java"
    ).read_text(encoding="utf-8")
    call_transcript_clock_source = (
        call_source_root / "CallTranscriptRevisionClock.java"
    ).read_text(encoding="utf-8")
    call_transcript_clock_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "CallTranscriptRevisionClockTest.java"
    ).read_text(encoding="utf-8")
    pcm_transcript_timeline_source = (
        call_source_root / "PcmTranscriptTimeline.java"
    ).read_text(encoding="utf-8")
    pcm_transcript_timeline_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "PcmTranscriptTimelineTest.java"
    ).read_text(encoding="utf-8")
    assistant_tracker_source = (call_source_root /
                                "AssistantHandlingTracker.java").read_text(
                                    encoding="utf-8")
    assistant_tracker_test = (root / "services" / "callintelligence" / "tests" /
                              "src" / "com" / "aios" / "callintelligence" /
                              "AssistantHandlingTrackerTest.java").read_text(
                                  encoding="utf-8")
    assistant_greeting_policy = (call_source_root /
                                 "AssistantGreetingPolicy.java").read_text(
                                     encoding="utf-8")
    assistant_greeting_test = (root / "services" / "callintelligence" / "tests" /
                               "src" / "com" / "aios" / "callintelligence" /
                               "AssistantGreetingPolicyTest.java").read_text(
                                   encoding="utf-8")
    require("advisory only" in spam_source
            and 'new Signal("gift_card_payment"' in spam_source
            and 'new Signal("credential_request"' in spam_source
            and '"es".equals(language)' in spam_source,
            "call risk scoring must be advisory, explainable, and English/Spanish aware")
    call_service = (call_source_root / "CallIntelligenceService.java").read_text(
        encoding="utf-8")
    on_bind_start = call_service.index("public IBinder onBind(Intent intent)")
    on_bind_end = call_service.index("public void onDestroy()", on_bind_start)
    on_bind = call_service[on_bind_start:on_bind_end]
    require('android:permission="com.aios.permission.CONTROL_CALL_INTELLIGENCE"'
            in call_manifest
            and "enforceControlPermission()" not in on_bind
            and call_service.count("enforceControlPermission();") >= 10,
            "Call Intelligence must enforce clients at the manifest and AIDL boundary, not Service.onBind")
    call_host_test = call_service_bp[call_service_bp.index("java_test_host {"):]
    require('name: "aios_callintelligence_host_tests"' in call_host_test
            and '"src/com/aios/callintelligence/CallRequestIdentityTracker.java"'
            in call_host_test
            and '"src/com/aios/callintelligence/CallerHistoryPolicy.java"'
            in call_host_test
            and '"src/com/aios/callintelligence/CallerHistoryConversationPolicy.java"'
            in call_host_test
            and '"src/com/aios/callintelligence/CallerHistorySourcePolicy.java"'
            in call_host_test
            and '"src/com/aios/callintelligence/AssistantGreetingPolicy.java"'
            in call_host_test
            and '"src/com/aios/callintelligence/ServiceRebindPolicy.java"'
            in call_host_test
            and '"src/com/aios/callintelligence/ResilientFanoutOutputStream.java"'
            in call_host_test
            and '"tests/src/**/*.java"' in call_host_test,
            "Soong Call Intelligence host tests must include the full Android-free source closure")
    require("boolean callerHistoryEnabled" in call_policy_api
            and "boolean messageHistoryEnabled" in call_policy_api
            and "boolean callHistoryEnabled" in call_policy_api
            and "boolean photoHistoryEnabled" in call_policy_api
            and "String[] excludedCallerHistoryAddressHashes" in call_policy_api
            and '"caller_history_enabled", false' in call_service
            and '"message_history_enabled", true' in call_service
            and '"call_history_enabled", true' in call_service
            and '"photo_history_enabled", true' in call_service
            and "&& CallerHistorySourcePolicy.anyEnabled(" in call_service
            and "CallerHistoryPolicy.shouldPrepare(" in call_service
            and "callerHistorySources(preferences)" in call_service
            and "callerHistorySources(latestPreferences)" in call_service
            and "historyScopeChanged" in call_service
            and "CallerHistoryConversationPolicy.isAllowed(" in call_service
            and "PREF_EXCLUDED_CALLER_HISTORY_HASHES" in call_service
            and "new HashSet<>(requestedExclusions)" in call_service
            and "staleRequest" in call_service
            and "session.isAiHandling() && receptionist != null" in call_service
            and "receptionist.updatePriorContext(callId, prepared.priorContextJson)"
            in call_service
            and "revokeCallerHistory()" in call_service
            and 'updatePriorContext(callId, "[]")' in call_service
            and "enabled" in caller_history_policy
            and "static String[] selected" in caller_history_source_policy
            and "static boolean isValidScope" in caller_history_source_policy
            and "allOwnerCategoriesExpandToTheExactBoundedScope"
            in caller_history_source_test
            and "emptyUnknownAndDuplicateScopesFailClosed"
            in caller_history_source_test
            and "admitsOnlyExplicitlyEnabledEligibleCalls" in caller_history_test
            and "rejectsEmergencyAndMissingAddresses" in caller_history_test,
            "caller-history retrieval must be default-off, source-scoped, emergency-safe, revocable, and host-tested")
    require("MAX_EXCLUDED_CONVERSATIONS = 256" in caller_history_conversation_policy
            and 'Pattern.compile("[0-9a-f]{64}")' in caller_history_conversation_policy
            and "validateRequested" in caller_history_conversation_policy
            and "validateStored" in caller_history_conversation_policy
            and "validOpaqueExclusionsAreBoundedAndSorted"
            in caller_history_conversation_test
            and "invalidRequestedOrStoredIdentitiesFailClosed"
            in caller_history_conversation_test,
            "per-conversation caller-history exclusions must be opaque, bounded, durable, and fail closed")
    require("resumedAfterServiceLoss && resumedKnownContact" in call_service
            and "AssistantGreetingPolicy.shouldGreet" in call_service
            and "answeredByAi && !resumedAfterServiceLoss"
            in assistant_greeting_policy
            and "resumedAiSessionDoesNotReplayGreeting" in assistant_greeting_test,
            "restored call capture must not replay the receptionist greeting")
    require('include(":callservicecheck")' in preview_settings
            and 'include("com/aios/callintelligence/**/*.java")'
            in call_service_compile_build
            and 'exclude("com/aios/callintelligence/CallProductProperties.java")'
            in call_service_compile_build
            and '../../services/callintelligence/aidl' in call_service_compile_build
            and '../../services/contextintelligence/aidl' in call_service_compile_build
            and '../../services/modelbroker/aidl' in call_service_compile_build
            and 'android.os.SystemProperties' in call_product_properties
            and '"ro.aios.call_uplink_validated"' in call_product_properties
            and '"persist.aios.debug.call_uplink_test"' in call_product_properties
            and '"ro.debuggable"' in call_product_properties
            and "return false;" in call_compile_properties
            and "SystemProperties" not in call_compile_properties
            and "CallProductProperties.callerUplinkValidated()" in call_service
            and "CallProductProperties.developmentUplinkTestActive()" in call_service
            and "SystemProperties" not in call_service
            and 'disable += "ProtectedPermissions"' in call_service_compile_build
            and 'disable += "MissingPermission"' not in call_service_compile_build
            and "abortOnError" not in call_service_compile_build,
            "the full Call Intelligence compile lane must replace only a fail-closed product-property adapter")
    require("Manifest.permission.RECORD_AUDIO" in telephony_capture
            and "Manifest.permission.CAPTURE_AUDIO_OUTPUT" in telephony_capture
            and "context.checkSelfPermission" in telephony_capture
            and "new TelephonyAudioCapture(" in call_service
            and "onCaptureLost(" in call_service
            and 'android:name="android.hardware.telephony"' in call_manifest
            and 'android:required="true"' in call_manifest,
            "telephony capture must fail closed without both grants and declare phone hardware")
    call_context_client = (
        call_source_root / "CallCommunicationContextClient.java"
    ).read_text(encoding="utf-8")
    asr_client = (call_source_root / "AsrBrokerClient.java").read_text(
        encoding="utf-8")
    broker_binding = (call_source_root /
                      "ResilientModelBrokerBinding.java").read_text(
                          encoding="utf-8")
    context_binding = (call_source_root /
                       "ResilientCommunicationContextBinding.java").read_text(
                           encoding="utf-8")
    service_rebind_policy = (call_source_root /
                             "ServiceRebindPolicy.java").read_text(
                                 encoding="utf-8")
    service_rebind_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "ServiceRebindPolicyTest.java"
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
    call_status_log_policy = (
        call_source_root / "CallStatusLogPolicy.java"
    ).read_text(encoding="utf-8")
    call_status_log_policy_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "CallStatusLogPolicyTest.java"
    ).read_text(encoding="utf-8")
    prior_context_formatter_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "PriorContextFormatterTest.java"
    ).read_text(encoding="utf-8")
    call_context_check_build = (
        root / "preview" / "callcontextcheck" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    call_context_smoke_manifest = (
        root / "preview" / "callcontextcheck" / "src" / "debug" /
        "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    call_context_smoke = (
        root / "preview" / "callcontextcheck" / "src" / "debug" / "java" /
        "com" / "aios" / "contextintelligence" /
        "ContextLifecycleSmokeActivity.java"
    ).read_text(encoding="utf-8")
    call_context_smoke_runner = (
        root / "scripts" / "emulator-context-lifecycle-smoke.ps1"
    ).read_text(encoding="utf-8")
    context_bp = (root / "services" / "contextintelligence" /
                  "Android.bp").read_text(encoding="utf-8")
    context_manifest = (root / "services" / "contextintelligence" /
                        "AndroidManifest.xml").read_text(encoding="utf-8")
    context_extraction_rules = (
        root / "services" / "contextintelligence" / "res" / "xml" /
        "data_extraction_rules.xml"
    ).read_text(encoding="utf-8")
    telecom_presence = (call_source_root / "TelecomCallPresenceTracker.java").read_text(
        encoding="utf-8")
    require("token.linkToDeath" in call_service
            and "onTelecomPresenceTokenDied" in call_service
            and "telecomPresence.removeDeadAndReport(token)" in call_service
            and "telecomPresence.releaseAndReport(token, ownerUid, callId)"
            in call_service
            and "stopOrphanedWorkLocked(callId, ownerUid)" in call_service
            and '"telecom_presence_released"' in call_service
            and "finishOrphanedCallCleanup(orphanedCallIds," in call_service
            and "if (active != null) active.close();" in call_service
            and "telecomPresenceStopping || !telecomPresence.ownsCall(ownerUid, callId)"
            in call_service
            and '"dialer_process_died"' in call_service
            and "asr.setCallActive(desired)" in call_service
            and "MAX_TELECOM_LIFECYCLE_TOKENS" in call_service
            and "MAX_CALLS_PER_LIFECYCLE_TOKEN" in call_service
            and "ownerUid" in telecom_presence
            and "maxTokens" in telecom_presence
            and "maxCallsPerToken" in telecom_presence
            and "orphanedCallIds" in telecom_presence
            and "callOrphaned" in telecom_presence,
            "Telecom presence must be UID-owned, bounded, release/death-linked, stop orphaned capture, and drive call priority")
    require("CallArtifactRetention.canResume(" in artifact_source
            and 'new FileOutputStream(new File(directory, "rx.pcm"), true)'
            in artifact_source
            and 'new FileOutputStream(new File(directory, "tx.pcm"), true)'
            in artifact_source
            and "storedAnsweredByAi || answeredByAi" in artifact_source,
            "dialer restart must append to the original bounded call artifact without extending expiry")
    require("String transientAddress" in incoming_call_api
            and "String countryIso" in incoming_call_api
            and incoming_call_api.index("ringingSinceElapsedRealtimeMillis")
            < incoming_call_api.index("transientAddress")
            and "ownerProcessingEnabled == true" in assistant_client
            and "ownerCallerHistoryEnabled == true" in assistant_client
            and "&& !emergency" in assistant_client
            and "context.transientAddress" in call_service
            and "decision.processingAllowed" in call_service
            and "context.emergency," in call_service
            and "context.emergencyCallbackMode," in call_service,
            "raw caller identity must be transient and retrieved only for authorized non-emergency processing")
    require("candidate.resolveIdentity(" in call_context_client
            and "pending.address, pending.countryIso" in call_context_client
            and "pending.sourceTypes" in call_context_client
            and "CallerHistorySourcePolicy.isValidScope(sourceTypes)"
            in call_context_client
            and "PriorContextFormatter.MAX_ITEMS" in call_context_client
            and '"call_artifact"' in call_context_client
            and "candidate.upsert(new ContextDocument(" in call_context_client
            and "isExpired(pending, observedNow)" in call_context_client
            and "pending.expiryBootIdentity" in call_context_client
            and "pending.expiresAtElapsedRealtimeMillis" in call_context_client
            and "resolvedCalls.remove(callId)" in call_context_client
            and "void discardCall(String callId)" in call_context_client
            and "MAX_ITEMS = 8" in prior_context_formatter
            and "MAX_EXCERPT_CHARS = 512" in prior_context_formatter
            and '"source_id"' not in prior_context_formatter,
            "call RAG must resolve opaque identity, retrieve bounded context, and publish only expiring summaries")
    require("new ResilientCommunicationContextBinding" in call_context_client
            and "pendingPrepares" in call_context_client
            and "pendingIndexes" in call_context_client
            and '"communication_context_deferred"' in call_context_client
            and '"call_context_index_deferred"' in call_context_client
            and "binding.isCurrent(candidate)" in call_context_client
            and "binding.invalidate(candidate)" in call_context_client
            and "onBindingDied" in context_binding
            and "onNullBinding" in context_binding
            and "CONNECT_TIMEOUT_MILLIS = 15_000L" in context_binding
            and "activeConnection != this" in context_binding
            and "ServiceRebindPolicy" in context_binding,
            "call context must replay bounded live work through a generation-safe binding")
    require("if (!isFinal" in call_context_accumulator
            and "MAX_DOCUMENT_CHARS = 4_096" in call_context_accumulator
            and "latestAssessment" in call_context_accumulator
            and "provisional_false_alarm" in call_context_accumulator_test
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
            in prior_context_formatter_test
            and "exclusionsRemoveSourcesBeforeContextQuery"
            in caller_history_source_test,
            "call-context bounds and prompt serialization must remain host-tested")
    require("class CallStatusLogPolicy" in call_status_log_policy
            and 'scope = "availability"' in call_status_log_policy
            and 'scope = "call"' in call_status_log_policy
            and 'safeDetail = detail != null && DETAIL.matcher(detail).matches()'
            in call_status_log_policy
            and "Log.i(TAG, CallStatusLogPolicy.format(callId, status, detail))"
            in call_service
            and "markerNeverContainsCallIdentity" in call_status_log_policy_test
            and "unexpectedOrContentBearingDetailFailsClosed"
            in call_status_log_policy_test
            and '"src/com/aios/callintelligence/CallStatusLogPolicy.java"'
            in call_host_test,
            "physical call status logging must be content-free, bounded, and host-tested")
    context_extraction_domains = {
        "root", "file", "database", "sharedpref", "external", "device_root",
        "device_file", "device_database", "device_sharedpref",
    }
    require('include("com/aios/context/**/*.java")' in call_context_check_build
            and 'include("com/aios/contextintelligence/**/*.java")'
            in call_context_check_build
            and 'include("com/aios/contextintelligence/**/*Test.java")'
            in call_context_check_build
            and 'manifest.srcFile("../../services/contextintelligence/AndroidManifest.xml")'
            in call_context_check_build
            and 'res.directories.add("../../services/contextintelligence/res")'
            in call_context_check_build
            and '../../services/contextintelligence/aidl' in call_context_check_build
            and '../../services/callintelligence' not in call_context_check_build
            and '../../services/modelbroker/aidl' in call_context_check_build
            and 'resource_dirs: ["res"]' in context_bp
            and '"aios_model_api"' in context_bp
            and '"src/com/aios/contextintelligence/ContextSourceScope.java"'
            in context_bp
            and 'android:dataExtractionRules="@xml/data_extraction_rules"'
            in context_manifest
            and 'android:icon="@drawable/ic_context_intelligence"'
            in context_manifest
            and '.CommunicationContextService' in context_manifest
            and '.ContextBootReceiver' in context_manifest
            and all(context_extraction_rules.count(
                f'<exclude domain="{domain}" path="." />') == 2
                    for domain in context_extraction_domains)
             and not (root / "preview" / "callcontextcheck" / "src" / "main" /
                      "AndroidManifest.xml").exists(),
            "Communication Context needs a complete production-service compile check")
    require('applicationId = "com.aios.callintelligence"' in call_context_check_build
            and "ContextLifecycleSmokeActivity" in call_context_smoke_manifest
            and "ICommunicationContext.Stub.asInterface" in call_context_smoke
            and "remote.resolveIdentity" in call_context_smoke
            and "remote.upsert(freshArtifact)" in call_context_smoke
            and "store.deleteSourceType(ContextPolicy.MEDIA_METADATA" in call_context_smoke
            and "AIOS_CONTEXT_LIFECYCLE_SMOKE_OK" in call_context_smoke
            and "hybrid_retrieval_verified = $true" in call_context_smoke_runner
            and "Refusing to run context-lifecycle smoke checks on non-emulator serial"
            in call_context_smoke_runner
            and "physical_gate_evidence = $false" in call_context_smoke_runner,
            "Communication Context needs guarded Android Binder/SQLite lifecycle evidence")
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
            and "MIN_REQUEST_INTERVAL_MILLIS = 4_000L" in classifier_source
            and "MAX_TRANSCRIPT_CHARS" in classifier_source
            and "IncrementalCallerTranscript" in classifier_source
            and "pending.transcriptRevision" in classifier_source
            and "retryLatest" in classifier_source
            and "Lines marked partial are replaceable" in classifier_source
            and 'request.workload = "call_agent"' in classifier_source
            and "request.allowFallback = true" in classifier_source
            and "classifier_timeout" in classifier_source,
            "Gemma call classification must be prompt-safe, incremental, revision-bound, debounced, and timed out")
    require('isFinal ? "final" : "partial"' in incremental_transcript_source
            and "sourceRevision <= revision" in incremental_transcript_source
            and "partial = line" in incremental_transcript_source
            and "partial = \"\"" in incremental_transcript_source
            and "partialRevisionReplacesWordsInsteadOfDuplicatingThem"
            in incremental_transcript_test
            and "finalizedTurnBecomesHistoryForTheNextPartial"
            in incremental_transcript_test,
            "live classifier context must replace partial hypotheses and retain final turns")
    require("candidate <= latest" in transcript_revision_gate_source
            and "candidate == latest" in transcript_revision_gate_source
            and "void invalidate()" in transcript_revision_gate_source
            and "onlyStrictlyNewerAsrSequencesAdvance" in transcript_revision_gate_test
            and "classifierResultMustMatchTheExactCurrentSequence"
            in transcript_revision_gate_test
            and "streamReplacementInvalidatesEarlierClassifierRevision"
            in transcript_revision_gate_test
            and "activeStream != streamIdentity" in call_transcript_clock_source
            and "nextCallRevision++" in call_transcript_clock_source
            and "replacementStreamCanRestartAtZeroWithoutRevisionCollision"
            in call_transcript_clock_test
            and "detachmentRejectsLateCallbacksUntilReplacementActivates"
            in call_transcript_clock_test
            and "recoveredRevisionPreservesFinalizedClassifierContext"
            in call_transcript_clock_test
            and "acceptAsrChunk(" in call_service
            and "downlinkTranscriptRevisions.activate(downlink.identity)"
            in call_service
            and "downlinkTranscriptRevisions.deactivate(previousDownlink.identity)"
            in call_service
            and "classifierTranscriptRevisions.invalidate()" in call_service
            and "chunk.text, language, chunk.isFinal, accepted.revision"
            in call_service
            and '"src/com/aios/callintelligence/CallTranscriptRevisionClock.java"'
            in call_host_test
            and "classifierTranscriptRevisions.advance(transcriptRevision)"
            in call_service
            and "classifierTranscriptRevisions.accepts(" in call_service,
            "classifier results must use a call-global revision clock across recovered ASR streams")
    require("capturedPcmBytes / PCM16_BYTES_PER_SAMPLE" in pcm_transcript_timeline_source
            and "offsetMillis + sourceStartMillis" in pcm_transcript_timeline_source
            and "activeStream != streamIdentity" in pcm_transcript_timeline_source
            and "replacementStartsAtItsExactCapturedPcmOffset"
            in pcm_transcript_timeline_test
            and "cumulativePartialAndFinalMayReuseTheSameStartAndEnd"
            in pcm_transcript_timeline_test
            and "downlinkTranscriptTimeline.activate(downlink.identity, downlinkOffset)"
            in call_service
            and "uplinkTranscriptTimeline.activate(uplink.identity, uplinkOffset)"
            in call_service
            and "accepted.startMillis" in call_service
            and "accepted.endMillis" in call_service
            and '"src/com/aios/callintelligence/PcmTranscriptTimeline.java"'
            in call_host_test,
            "recovered ASR timestamps must remain anchored to authoritative call PCM")
    require("hasProvisionalModelAssessment = false" in risk_tracker_source
            and "observeModelRevision" in risk_tracker_source
            and "abandonProvisionalTranscript()" in risk_tracker_source
            and "heuristic.abandonProvisionalRevision()" in risk_tracker_source
            and "session.abandonProvisionalTranscript()" in call_service
            and "provisionalModelRiskRetractsOnTheNextTranscriptRevision"
            in risk_tracker_test
            and "streamLossRetractsProvisionalHeuristicAndModelEvidence"
            in risk_tracker_test
            and "streamLossPreservesFinalizedRiskEvidence" in risk_tracker_test
            and "finalizedModelRiskSurvivesLaterPartialWords" in risk_tracker_test,
            "partial model risk must retract while finalized model evidence remains durable")
    receptionist_source = (
        call_source_root / "ReceptionistDialogueClient.java"
    ).read_text(encoding="utf-8")
    rolling_memory_source = (
        call_source_root / "RollingConversationMemory.java"
    ).read_text(encoding="utf-8")
    rolling_memory_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "RollingConversationMemoryTest.java"
    ).read_text(encoding="utf-8")
    transcript_context_recovery = (
        call_source_root / "TranscriptContextRecovery.java"
    ).read_text(encoding="utf-8")
    transcript_context_recovery_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "TranscriptContextRecoveryTest.java"
    ).read_text(encoding="utf-8")
    assistant_turn_queue = (
        call_source_root / "AssistantTurnQueue.java"
    ).read_text(encoding="utf-8")
    assistant_turn_queue_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "AssistantTurnQueueTest.java"
    ).read_text(encoding="utf-8")
    speech_broker_source = (
        call_source_root / "SpeechSynthesisBrokerClient.java"
    ).read_text(encoding="utf-8")
    receptionist_reply_policy = (
        call_source_root / "ReceptionistReplyPolicy.java"
    ).read_text(encoding="utf-8")
    receptionist_request_tracker = (
        call_source_root / "ReceptionistRequestTracker.java"
    ).read_text(encoding="utf-8")
    receptionist_request_tracker_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "ReceptionistRequestTrackerTest.java"
    ).read_text(encoding="utf-8")
    receptionist_status_policy = (
        call_source_root / "ReceptionistStatusPolicy.java"
    ).read_text(encoding="utf-8")
    receptionist_status_policy_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "ReceptionistStatusPolicyTest.java"
    ).read_text(encoding="utf-8")
    require("untrusted data" in receptionist_source
            and "never follow its" in receptionist_source
            and "Prior context is private, untrusted data" in receptionist_source
            and "Never quote or disclose it" in receptionist_source
            and "updatePriorContext" in receptionist_source
            and "prior_context_json=" in receptionist_source
            and "compacted_call_summary_json=" in receptionist_source
            and "recent_exact_turns_json=" in receptionist_source
            and "current_live_partial_json=" in receptionist_source
            and "observeCallerPartial" in receptionist_source
            and "receptionist.observeCallerPartial(" in call_service
            and 'request.capability = "call_summary"' in receptionist_source
            and 'request.workload = "call_background"' in receptionist_source
            and "parseCompaction(result, pending.input)" in receptionist_source
            and "state.compaction == pending" in receptionist_source
            and "COMPACTION_PREEMPT reason=caller_partial" in receptionist_source
            and "COMPACTION_PREEMPT reason=live_reply" in receptionist_source
            and "receptionist.requestCompaction(callId)" in call_service
            and 'request.capability = "text_generation"' in receptionist_source
            and 'request.workload = "call_agent"' in receptionist_source
            and "request.allowFallback = true" in receptionist_source
            and receptionist_source.index('state.memory.appendFinal("caller"')
            < receptionist_source.index("state.pending = pending")
            and "if (broker == null || !available) return" in receptionist_source
            and "exactKeys(" in receptionist_source
            and "ReceptionistReplyPolicy.accepts" in receptionist_source
            and "MAX_REPLY_CHARS" in receptionist_reply_policy
            and "hasControlCharacter" in receptionist_reply_policy
            and "receptionist_timeout" in receptionist_source,
            "AI receptionist must be tool-free, injection-resistant, schema-bound, and timed out")
    require("class RollingConversationMemory" in rolling_memory_source
            and "CompactionInput prepareCompaction()" in rolling_memory_source
            and "input.inputSummaryRevision != summaryRevision" in rolling_memory_source
            and "input.inputSummaryThroughTurnId != summaryThroughTurnId"
            in rolling_memory_source
            and "input.firstTurnId != summaryThroughTurnId + 1L"
            in rolling_memory_source
            and "discardedThroughTurnId > summaryThroughTurnId"
            in rolling_memory_source
            and "actual.toString().equals(input.finalizedPrefix)" in rolling_memory_source
            and "finalized.subList(0, lastIndex + 1).clear()" in rolling_memory_source
            and "livePartial" in rolling_memory_source
            and "staleOrDuplicateCompactionCannotReplaceNewerSummary"
            in rolling_memory_test
            and "compactionNamesExactPrefixAndKeepsNewerTurnsVerbatim"
            in rolling_memory_test
            and '"src/com/aios/callintelligence/RollingConversationMemory.java"'
            in call_host_test,
            "call prompts must use versioned rolling memory with stale-result rejection")
    require("class TranscriptContextRecovery" in transcript_context_recovery
            and '"downlink".equals(direction)' in transcript_context_recovery
            and '"assistant".equals(direction)' in transcript_context_recovery
            and "MAX_RECOVERED_CHARS = RollingConversationMemory.MAX_RETAINED_CHARS"
            in transcript_context_recovery
            and "admitsOnlyFinalBilingualCallerAndAssistantTurns"
            in transcript_context_recovery_test
            and "recoveryKeepsTheNewestBoundedExactTail"
            in transcript_context_recovery_test
            and "readConversationTail()" in artifact_source
            and 'appendJsonLine("transcript.jsonl", transcript)' in artifact_source
            and "stored.readConversationTail()" in call_service
            and '"transcript_context_restored"' in call_service
            and 'communicationSummary.appendTranscript(' in call_service
            and 'communicationSummary.appendAssistantReply(' in call_service
            and "recoveredConversation" in receptionist_source
            and '"src/com/aios/callintelligence/TranscriptContextRecovery.java"'
            in call_host_test,
            "final call turns must support bounded crash-safe receptionist recovery")
    require("Math.addExact" in receptionist_request_tracker
            and "expected.deadlineElapsedRealtimeMillis" in receptionist_request_tracker
            and "current != expected" in receptionist_request_tracker
            and "recoveryPreservesDeadlineAndInvalidatesOldCallbacks"
            in receptionist_request_tracker_test
            and "repeatedRecoveryNeverRenewsTheBudget"
            in receptionist_request_tracker_test
            and "state.requests.recover(" in receptionist_source
            and "previous.prompt" in receptionist_source
            and "pending.token.deadlineElapsedRealtimeMillis" in receptionist_source
            and '"receptionist_broker_recovering"' in receptionist_source
            and "state.pending == pending" in receptionist_source
            and "state.requests.complete(pending.token)" in receptionist_source
            and "ReceptionistStatusPolicy.completesAssistantOperation"
            in call_service
            and '"receptionist_broker_recovering"' in receptionist_status_policy
            and "brokerRecoveryKeepsTheAssistantTurnOccupied"
            in receptionist_status_policy_test,
            "receptionist Broker recovery must preserve one deadline-bound prompt and reject stale callbacks")
    require("onBindingDied" in broker_binding
            and "onNullBinding" in broker_binding
            and "CONNECT_TIMEOUT_MILLIS = 15_000L" in broker_binding
            and "activeConnection != this" in broker_binding
            and "binding.markReady" in asr_client
            and "binding.markReady" in classifier_source
            and "binding.markReady" in receptionist_source
            and "binding.markReady" in speech_broker_source
            and all("new ResilientModelBrokerBinding" in source for source in (
                asr_client, classifier_source, receptionist_source,
                speech_broker_source))
            and "MAX_DELAY_MILLIS = 60_000L" in service_rebind_policy
            and "failuresBackOffAndCapAtOneMinute" in service_rebind_test
            and "connectionRacingReservedRetryCancelsThatAttempt"
            in service_rebind_test
            and "closeSuppressesReservedAndFutureRetries" in service_rebind_test,
            "every long-lived Call Intelligence binding must replace terminal, null, failed, and stalled bindings with bounded retries")
    require("Object streamIdentity" in asr_client
            and "nextStreamGeneration" in asr_client
            and "expected.identity == streamIdentity" in call_service
            and "state == pending.owner" in classifier_source
            and "pending.requestSerial" in classifier_source
            and "pending.transcriptRevision" in classifier_source
            and "state == pending.owner" in receptionist_source
            and "pending.requestSerial" in receptionist_source
            and "activeRequests.isCurrent(callId, requestIdentity)" in call_context_client
            and "communicationContextRequests.isCurrent(callId, requestIdentity)"
            in call_service
            and "session != expectedSession" in call_service
            and "nextSpeechRequestSerial()" in call_service,
            "restarted calls must reject stale ASR, model, context, TTS, and caller-audio generations")
    require("chunk.isFinal" in call_service
            and "observeHeuristicRevision" in call_service
            and "chunk.sequence" in call_service
            and "session.isAiHandling()" in call_service
            and "receptionist.requestReply" in call_service
            and "classifier.observeRevision" in call_service
            and "attachAssistantAudio" in call_service
            and "completeAssistantOperation" in call_service,
            "AI dialogue must start only at final caller turns and serialize reasoning and speech")
    require("MAX_PENDING_TEXT_CHARS = 2_048" in assistant_turn_queue
            and "pending = coalesce(pending, turn)" in assistant_turn_queue
            and "finalizedSegmentsCoalesceWhileAssistantIsBusy"
            in assistant_turn_queue_test
            and "pendingSpeechKeepsTheNewestBoundedContext"
            in assistant_turn_queue_test
            and "while (nextTurn != null && session.isAiHandling())"
            in call_service
            and "nextTurn = completion.nextTurn" in call_service,
            "busy receptionist work must preserve bounded finalized speech and drain submission races")
    require("CallProductProperties.callerUplinkValidated()" in call_service
            and "CALL_UPLINK_VALIDATION_PROPERTY" in call_product_properties
            and "automaticCallerInteractionTransportReady()" in call_service
            and "manualCallerInteractionTransportReady()" in call_service
            and "caller_audio_injection_requires_physical_validation" in call_service
            and "AutomaticAnswerGate.mayAnswer" in call_service
            and "void onCallAnsweredForDevelopmentTest" in call_api
            and "developmentManualTest" in call_service
            and "manualCallerUplinkAllowed()" in call_product_properties
            and "developmentTestActive" in caller_uplink_admission
            and "automaticAnswerAllowed" in caller_uplink_admission
            and "explicitDebugOptInUnlocksOnlyManualTesting"
            in caller_uplink_admission_test
            and "productionAndMissingOptInFailClosed"
            in caller_uplink_admission_test
            and "developmentUplinkTestActive" in call_policy_api
            and "manualAiAnswerAvailable" in call_policy_api
            and "onCallAnsweredForDevelopmentTest" in assistant_client
            and "AssistantCapabilityStatusPolicy.shouldReload" in assistant_client
            and all(detail in assistant_capability_status_policy for detail in (
                "streaming_asr_ready", "streaming_asr_unavailable",
                "speech_synthesis_ready", "speech_synthesis_unavailable",
                "receptionist_ready", "receptionist_unavailable"))
            and "everyCallerInteractionCapabilityRefreshesThePolicy"
            in assistant_capability_status_test
            and 'notifyStatus("availability", 3, "streaming_asr_ready")'
            in call_service
            and 'notifyStatus("availability", 3, "streaming_asr_unavailable")'
            in call_service
            and "safeDevelopmentUplinkTestActive" in assistant_policy_semantics
            and "AssistantPolicySemantics.safeDevelopmentUplinkTestActive"
            in assistant_client
            and "developmentUplinkTestActive" in phone_screens
            and "automatic answering remains locked" in phone_screens
            and "beginCapture(\n                callId,\n                ownerUid,\n                true,"
            in call_service,
            "AI answer must retain release validation while exposing only an explicit userdebug manual test path")
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
    assistant_audio_gate = (
        call_source_root / "AssistantAudioIdentityGate.java"
    ).read_text(encoding="utf-8")
    assistant_audio_gate_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "AssistantAudioIdentityGateTest.java"
    ).read_text(encoding="utf-8")
    speech_status_policy = (
        call_source_root / "SpeechSynthesisStatusPolicy.java"
    ).read_text(encoding="utf-8")
    speech_status_policy_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "SpeechSynthesisStatusPolicyTest.java"
    ).read_text(encoding="utf-8")
    speech_terminal_gate = (
        call_source_root / "SpeechTerminalGate.java"
    ).read_text(encoding="utf-8")
    speech_terminal_gate_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "SpeechTerminalGateTest.java"
    ).read_text(encoding="utf-8")
    speak_start = call_service.index("private void speakToCaller(")
    speak_end = call_service.index("private void handleCallerAudioStatus(", speak_start)
    speak_to_caller = call_service[speak_start:speak_end]
    require("AudioDeviceInfo.TYPE_TELEPHONY" in caller_uplink
            and "setPreferredDevice" in caller_uplink
            and "getRoutedDevice" in caller_uplink
            and "getPlaybackHeadPosition" in caller_uplink
            and "MODIFY_PHONE_STATE" in caller_uplink,
            "caller audio must verify telephony-TX routing and drain every frame")
    require("void onStatus(String callId, Stream stream, String detail)" in caller_uplink
            and "listener.onStatus(callId, this, detail)" in caller_uplink
            and "speech_synthesis_broker_disconnected" in speech_broker_source
            and "Speech speech, String detail" in speech_broker_source
            and "SpeechSynthesisStatusPolicy.terminatesCallerAudio(detail)"
            in call_service
            and "session.completeAssistantOperation(expectedSpeech)" in call_service
            and "session.completeAssistantOperation(expectedUplink)" in call_service
            and "assistantAudioIdentities.consumeSpeech" in call_service
            and "assistantAudioIdentities.consumeUplink" in call_service
            and "assistantAudioIdentities.begin(expectedSpeech, expectedUplink)"
            in call_service
            and "speech != expectedSpeech" in assistant_audio_gate
            and "uplink == expectedUplink" in assistant_audio_gate
            and "synchronized boolean begin(Object expectedSpeech" in assistant_audio_gate
            and "ttsFailureWinsOnceAndRejectsLateUplinkCompletion"
            in assistant_audio_gate_test
            and "uplinkCompletionWinsOnceAndRejectsLateTtsFailure"
            in assistant_audio_gate_test
            and "providerStartRequiresTheExactAttachedPairAndHappensOnce"
            in assistant_audio_gate_test
            and "speech_synthesis_complete" in speech_status_policy_test
            and "completionAllowsThePcmPipeToDrain" in speech_status_policy_test
            and "speech_synthesis_broker_disconnected" in speech_status_policy,
            "TTS loss and caller-audio completion must race through one identity-bound terminal gate")
    require("Speech prepare(" in speech_broker_source
            and "void start() throws IOException" in speech_broker_source
            and "broker.submitText(currentSessionId, text, true)" in speech_broker_source
            and "speech.synthesize(" not in call_service
            and speak_to_caller.index("speech.prepare(")
            < speak_to_caller.index("session.attachAssistantAudio(")
            < speak_to_caller.index("session.beginAssistantSpeech(")
            < speak_to_caller.index("synthesized.start()")
            and "? session.completeAssistantOperation(synthesized)" in speak_to_caller,
            "caller-facing TTS must start only after exact audio attachment and fail through that identity")
    require(speech_broker_source.count("if (speech.claimTerminal())") == 3
            and "if (closed || !terminal.claim()) return false"
            in speech_broker_source
            and "shouldCancel = terminal.claim() && currentSessionId > 0L"
            in speech_broker_source
            and "synchronized boolean claim()" in speech_terminal_gate
            and "if (terminal) return false" in speech_terminal_gate
            and "onlyTheFirstTerminalPathCanClaimSpeech"
            in speech_terminal_gate_test
            and "ownerCloseSuppressesEveryLaterProviderTerminal"
            in speech_terminal_gate_test,
            "each TTS request must expose only its first provider, Broker, or owner terminal state")
    require("CallerDisclosureCoordinator" not in call_service
            and "pendingAiDisclosures" not in call_service,
            "mandatory spoken disclosure state must not remain in the AI answer path")

    common_product = (root / "products" / "aios_common.mk").read_text(encoding="utf-8")
    require("ro.aios.call_uplink_validated=false" in common_product,
            "caller uplink must remain disabled in source until physical validation")
    call_uplink_test_script = (
        root / "scripts" / "set-call-uplink-test.ps1"
    ).read_text(encoding="utf-8")
    require('ValidateSet("enable", "disable")' in call_uplink_test_script
            and '"Android\\Sdk\\platform-tools\\adb.exe"' in call_uplink_test_script
            and '"ro.debuggable"' in call_uplink_test_script
            and '"ro.boot.qemu"' in call_uplink_test_script
            and '"persist.aios.debug.call_uplink_test"' in call_uplink_test_script
            and '"am", "force-stop", "com.aios.phone"' in call_uplink_test_script
            and "automatic answering remains locked" in call_uplink_test_script,
            "the development caller-uplink toggle must be explicit, physical-only, reversible, and restart both clients")

    retention_source = (call_source_root / "CallArtifactRetention.java").read_text(
        encoding="utf-8"
    )
    retention_alarm = (call_source_root / "RetentionAlarm.java").read_text(
        encoding="utf-8"
    )
    retention_smoke = (
        root / "scripts" / "emulator-call-retention-smoke.ps1"
    ).read_text(encoding="utf-8")
    retention_smoke_activity = (
        root / "preview" / "callservicecheck" / "src" / "debug" / "java" /
        "com" / "aios" / "callintelligence" / "CallRetentionSmokeActivity.java"
    ).read_text(encoding="utf-8")
    require("24L * 60L * 60L * 1000L" in retention_source
            and "Math.addExact" in retention_source
            and "deadline.expiresAtEpochMillis <= nowEpochMillis" in retention_source
            and "deadline.expiresAtElapsedRealtimeMillis <= nowElapsedRealtimeMillis"
            in retention_source
            and "Objects.equals(deadline.bootIdentity, currentBootIdentity)"
            in retention_source
            and "UNREADABLE_EXPIRY = Long.MIN_VALUE" in retention_source,
            "call artifact policy must enforce a dual-clock, reboot-fail-closed 24-hour TTL")
    require("CallArtifactRetention.Deadline.create" in artifact_source
            and "STORAGE_LOCK" in artifact_source
            and "ACTIVE_SESSIONS" in artifact_source
            and "closeActiveSession" in artifact_source
            and "CallArtifactRetention.cleanup" in artifact_source
            and "CallArtifactRetention.nextElapsedAlarm" in artifact_source
            and 'json.put("schema_version", 2)' in artifact_source
            and '"boot_identity"' in artifact_source
            and '"expires_at_elapsed_realtime_ms"' in artifact_source,
            "call artifact storage must close live files, lock, and delegate to the tested TTL policy")
    require("nextExpiryElapsedRealtimeMillis" in retention_alarm
            and "AlarmManager.ELAPSED_REALTIME_WAKEUP" in retention_alarm
            and "setExactAndAllowWhileIdle" in retention_alarm
            and "canScheduleExactAlarms" in retention_alarm,
            "retention alarm must schedule the persisted monotonic deadline directly")
    require("^emulator-[0-9]+$" in retention_smoke
            and "ro.kernel.qemu" in retention_smoke
            and "$apiLevel -lt 35" in retention_smoke
            and "Get-FileHash -LiteralPath $apkPath -Algorithm SHA256"
            in retention_smoke
            and "AIOS_CALL_RETENTION_SMOKE_OK" in retention_smoke
            and "AIOS_CALL_RETENTION_SMOKE_FAILED" in retention_smoke
            and "run-as $package find files -type f" in retention_smoke
            and "uninstall $package" in retention_smoke
            and "physical_gate_evidence = $false" in retention_smoke
            and "new CallArtifactStore(this)" in retention_smoke_activity
            and "CallArtifactRetention.RETENTION_MILLIS" in retention_smoke_activity
            and "expired.openDownlink()" in retention_smoke_activity
            and "expired.openUplink()" in retention_smoke_activity
            and "store.cleanup(nowEpochMillis)" in retention_smoke_activity
            and "store.discard(freshCallId)" in retention_smoke_activity
            and "RetentionAlarm.scheduleNext(this, store)" in retention_smoke_activity,
            "call-retention emulator smoke must be guarded, self-cleaning, and exercise production storage")
    require("expires_at_epoch_ms" in artifact_source,
            "call artifacts need an absolute expiry")
    require("dialogue.jsonl" in artifact_source
            and "appendAssistantReply" in artifact_source
            and 'transcript.put("direction", "assistant")' in artifact_source,
            "local receptionist replies must share the call-artifact retention boundary")

    capture_source = (call_source_root / "TelephonyAudioCapture.java").read_text(
        encoding="utf-8"
    )
    capture_gate = (call_source_root / "RequiredCaptureGate.java").read_text(
        encoding="utf-8"
    )
    capture_liveness = (call_source_root / "CaptureLivenessGate.java").read_text(
        encoding="utf-8"
    )
    capture_liveness_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "CaptureLivenessGateTest.java"
    ).read_text(encoding="utf-8")
    require("VOICE_DOWNLINK" in capture_source
            and "VOICE_UPLINK" in capture_source
            and "startRequired" in capture_source
            and "FIRST_PCM_TIMEOUT_MILLIS" in capture_source
            and "startup.markReady(name)" in capture_source
            and "downlinkReady && uplinkReady" in capture_gate
            and "first_pcm_timeout" in capture_gate
            and "capture.startRequired()" in call_service,
            "call capture must keep both directions separate and prove live PCM")
    capture_loss_start = call_service.index("private void finishCaptureLoss(")
    capture_loss_end = call_service.index(
        "private void enforceControlPermission", capture_loss_start)
    capture_loss = call_service[capture_loss_start:capture_loss_end]
    require("liveness.close()" in capture_source
            and "running.getAndSet(false)" in capture_source
            and "reportUnexpectedStop(name, true, failureReason)" in capture_source
            and "failureReported" in capture_liveness
            and "closing || failureReported || !receivedPcm" in capture_liveness
            and "candidate.usesCapture(failedCapture)" in capture_loss
            and "communicationContextRequests.remove(callId)" in capture_loss
            and "pendingCommunicationContexts.remove(callId)" in capture_loss
            and capture_loss.index("sessions.remove(callId)")
            < capture_loss.index("stopped.close()")
            and "takeover.closeAudio()" in capture_loss
            and "publishAssistantState(callId, stopped, takeover.update)"
            in capture_loss
            and "communicationContext.discardCall(callId)" in capture_loss
            and "notifyStatus(callId, -1" in capture_loss
            and "firstPostPcmLossWins" in capture_liveness_test
            and "intentionalCloseSuppressesLoss" in capture_liveness_test
            and '"src/com/aios/callintelligence/CaptureLivenessGate.java"'
            in call_host_test,
            "post-start capture loss must stop only the exact AI session once")
    asr_client = (call_source_root / "AsrBrokerClient.java").read_text(encoding="utf-8")
    require('request.language = "und"' in asr_client,
            "ASR client must permit English/Spanish auto-detection")
    require("request.deadlineElapsedRealtimeMillis = Long.MAX_VALUE" in asr_client,
            "live call ASR must follow the call lifecycle rather than a short turn deadline")
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
    pcm_vad = (
        root / "runtime" / "whisperprovider" / "app" / "src" / "main" /
        "java" / "com" / "aios" / "runtime" / "whispercpp" /
        "Pcm16EnergyVad.java"
    ).read_text(encoding="utf-8")
    pcm_vad_test = (
        root / "runtime" / "whisperprovider" / "app" / "src" / "test" /
        "java" / "com" / "aios" / "runtime" / "whispercpp" /
        "Pcm16EnergyVadTest.java"
    ).read_text(encoding="utf-8")
    vad_state = (
        root / "runtime" / "whisperprovider" / "app" / "src" / "main" /
        "java" / "com" / "aios" / "runtime" / "whispercpp" /
        "StreamingVadState.java"
    ).read_text(encoding="utf-8")
    vad_state_test = (
        root / "runtime" / "whisperprovider" / "app" / "src" / "test" /
        "java" / "com" / "aios" / "runtime" / "whispercpp" /
        "StreamingVadStateTest.java"
    ).read_text(encoding="utf-8")
    require("ENDPOINT_SILENCE_MILLIS = 600" in whisper_source
            and "CALL_WINDOW_MILLIS = 2_000" in whisper_source
            and "MEDIA_WINDOW_MILLIS = 4_000" in whisper_source
            and "if (session.isMedia) MEDIA_WINDOW_BYTES else CALL_WINDOW_BYTES"
            in whisper_source
            and "endOfTurn" in whisper_source
            and "session.turn.finishTurn()" in whisper_source
            and "session.turn.acceptDecoded(" in whisper_source
            and "session.turnText" not in whisper_source,
            "call ASR must expose two-second partials and silence-final turns")
    require("Pcm16EnergyVad.hasSpeech(" in whisper_source
            and "hasSpeech(pcm16ToFloat(frame" not in whisper_source
            and "long sumSquares = 0L" in pcm_vad
            and "threshold * threshold * sampleCount" in pcm_vad
            and "silenceAndSubthresholdFramesAreRejected" in pcm_vad_test
            and "thresholdAndAlternatingSpeechFramesAreAccepted" in pcm_vad_test,
            "live ASR VAD must scan PCM16 without allocating per-frame float arrays")
    require("StreamingVadState(ENDPOINT_SILENCE_FRAMES)" in whisper_source
            and "vad.accept(speechFrame)" in whisper_source
            and "silenceFrames >= endpointSilenceFrames" in vad_state
            and "exactlySixHundredMillisecondsOfSilenceEndsTheTurn"
            in vad_state_test
            and "resumedSpeechResetsTheSilenceRun" in vad_state_test,
            "live ASR endpoint cadence must use the host-tested streaming VAD state")
    turn_accumulator = (
        root / "runtime" / "whisperprovider" / "app" / "src" / "main" /
        "java" / "com" / "aios" / "runtime" / "whispercpp" /
        "StreamingAsrTurnAccumulator.java"
    ).read_text(encoding="utf-8")
    turn_accumulator_test = (
        root / "runtime" / "whisperprovider" / "app" / "src" / "test" /
        "java" / "com" / "aios" / "runtime" / "whispercpp" /
        "StreamingAsrTurnAccumulatorTest.java"
    ).read_text(encoding="utf-8")
    preview_settings = (root / "preview" / "settings.gradle.kts").read_text(
        encoding="utf-8"
    )
    whisper_policy_build = (
        root / "preview" / "whisperpolicycheck" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    require("Emission acceptDecoded(" in turn_accumulator
            and "Emission finishTurn()" in turn_accumulator
            and "text.append(' ')" in turn_accumulator
            and "if (endOfTurn)" in turn_accumulator
            and "reset();" in turn_accumulator,
            "live ASR turn state must accumulate revisions and reset after finalization")
    require("partialsContainTheCompleteCurrentTurn" in turn_accumulator_test
            and "silenceEndpointFinalizesAndResetsTheTurn" in turn_accumulator_test
            and "finalDecodedResidualIsIncludedBeforeReset" in turn_accumulator_test
            and "emptyFinalDecodeAdvancesTimestampAndFinalizesExistingText"
            in turn_accumulator_test
            and "endpointMarkerPreservesLastDecodedAudioBoundary"
            in turn_accumulator_test
            and "speechlessWindowsDoNotEmit" in turn_accumulator_test,
            "live ASR accumulator must retain host regression coverage")
    require('include(":whisperpolicycheck")' in preview_settings
            and '../../runtime/whisperprovider/app/src/main/java'
            in whisper_policy_build
            and '../../runtime/whisperprovider/app/src/test/java'
            in whisper_policy_build,
            "preview must compile and test the production live ASR accumulator")
    fanout = (call_source_root / "ResilientFanoutOutputStream.java").read_text(
        encoding="utf-8"
    )
    fanout_test = (
        root / "services" / "callintelligence" / "tests" / "src" / "com" /
        "aios" / "callintelligence" / "ResilientFanoutOutputStreamTest.java"
    ).read_text(encoding="utf-8")
    require("dropSecondary()" in fanout
            and "primary.write" in fanout
            and "replaceSecondary" in fanout
            and "primaryBytesWritten" in fanout
            and "replaceSecondaryAtCurrentByteOffset" in fanout
            and "onAsrUnavailable" in asr_client
            and "acceptsCallback" in asr_client
            and "activeStreams.clear()" in asr_client
            and "detachLostAsrStreams" in call_service
            and "restoreLiveAsrStreams" in call_service
            and "needsAsrRestore" in call_service
            and "replaceAsrStreams" in call_service
            and "replacementReceivesFutureAudioWithoutInterruptingPrimary"
            in fanout_test
            and "replacementReportsAtomicAuthoritativePcmOffset" in fanout_test
            and "failedSecondaryCanBeRestored" in fanout_test,
            "ASR loss must preserve local PCM, reject stale streams, and attach recovered inference sinks")

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
    capture_grouping = (media_source_root / "MediaCaptureGrouping.java").read_text(
        encoding="utf-8"
    )
    capture_grouping_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "MediaCaptureGroupingTest.java"
    ).read_text(encoding="utf-8")
    require("MediaStore.Images.Media.EXTERNAL_CONTENT_URI" in observer_source,
            "media service must observe images from all camera apps")
    require("MediaStore.Video.Media.EXTERNAL_CONTENT_URI" in observer_source,
            "media service must observe videos")
    require("requestReconcile(MediaCaptureGrouping.CAPTURE_SESSION_GAP_MILLIS)"
            in observer_source
            and "reconcileExactSource" in observer_source,
            "media observer must debounce generation scans and remove deleted or trashed items")
    require("initializeObservation" in observer_source
            and "MediaGenerationScanner.reconcile" in observer_source
            and "registerObservedVolumes" in observer_source,
            "media observer must reconcile missed additions across startup registration")
    baseline_position = observer_source.find(
        "MediaGenerationScanner.establishBaselines(this, store);")
    registration_position = observer_source.find(
        "registerObservedVolumes();", baseline_position + 1)
    settlement_position = observer_source.find(
        "requestReconcile(MediaCaptureGrouping.CAPTURE_SESSION_GAP_MILLIS);",
        registration_position + 1)
    require(0 <= baseline_position < registration_position < settlement_position
            and observer_source.count(
                "requestReconcile(MediaCaptureGrouping.CAPTURE_SESSION_GAP_MILLIS)")
            == 2
            and "scheduleScanResult(MediaGenerationScanner.reconcile(this, store))"
            not in observer_source,
            "startup must baseline, register, then share the live burst settlement window")

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
    baseline_policy = (
        media_source_root / "MediaGenerationBaselinePolicy.java"
    ).read_text(encoding="utf-8")
    baseline_policy_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "MediaGenerationBaselinePolicyTest.java"
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
    require("static void establishBaselines" in generation_scanner
            and "MediaGenerationBaselinePolicy.requiresBaseline"
            in generation_scanner
            and "currentGeneration < storedGeneration" in baseline_policy
            and "firstInstallEstablishesBaseline" in baseline_policy_test
            and "providerIdentityChangeEstablishesBaseline" in baseline_policy_test
            and "providerGenerationRegressionEstablishesBaseline"
            in baseline_policy_test
            and "matchingCursorPreservesRecoveryWork" in baseline_policy_test,
            "media startup baseline policy must preserve valid recovery cursors")
    require("MediaCaptureGrouping.classify(" in generation_scanner
            and "plan.more || plan.blockedByPendingItem" in generation_scanner
            and "state.mediaId != MediaGenerationReconciler.END_OF_GENERATION"
            in generation_scanner
            and "CAPTURE_SESSION_GAP_MILLIS = 5_000L" in capture_grouping
            and "groupEnd - groupStart" in capture_grouping
            and "continuationFromPreviousPage" in capture_grouping
            and "continuesOnFollowingPage" in capture_grouping
            and "unrelatedSettledPhotosRemainImmediate" in capture_grouping_test
            and "chainedCaptureSessionAndEveryVideoAreDeferred"
            in capture_grouping_test
            and "fiveSecondGapIsOneSessionButLongerGapIsNot"
            in capture_grouping_test
            and "unknownPageBoundariesFailEveryPhotoClosed"
            in capture_grouping_test,
            "media work classes must follow capture timing rather than reconciliation page size")
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
    media_smoke_script = (
        root / "scripts" / "emulator-media-smoke.ps1"
    ).read_text(encoding="utf-8")
    media_recovery_smoke = (
        root / "preview" / "mediascancheck" / "src" / "debug" / "java" /
        "com" / "aios" / "mediaintelligence" /
        "MediaObserverRecoverySmokeActivity.java"
    ).read_text(encoding="utf-8")
    media_preview_manifest = (
        root / "preview" / "mediascancheck" / "src" / "debug" /
        "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    media_bp = (root / "services" / "mediaintelligence" /
                "Android.bp").read_text(encoding="utf-8")
    media_manifest = (root / "services" / "mediaintelligence" /
                      "AndroidManifest.xml").read_text(encoding="utf-8")
    media_extraction_rules = (
        root / "services" / "mediaintelligence" / "res" / "xml" /
        "data_extraction_rules.xml"
    ).read_text(encoding="utf-8")
    extraction_domains = {
        "root", "file", "database", "sharedpref", "external", "device_root",
        "device_file", "device_database", "device_sharedpref",
    }
    require('include("com/aios/mediaintelligence/**/*.java")' in media_preview_build
            and 'include("com/aios/mediaintelligence/**/*Test.java")'
            in media_preview_build
            and 'manifest.srcFile("../../services/mediaintelligence/AndroidManifest.xml")'
            in media_preview_build
            and 'res.directories.add("../../services/mediaintelligence/res")'
            in media_preview_build
            and 'resource_dirs: ["res"]' in media_bp
            and '"src/com/aios/mediaintelligence/MediaInferenceAttempt.java"'
            in media_bp
            and '"src/com/aios/mediaintelligence/MediaCaptureGrouping.java"'
            in media_bp
            and '"src/com/aios/mediaintelligence/MediaJobCommitFence.java"'
            in media_bp
            and 'android:dataExtractionRules="@xml/data_extraction_rules"'
            in media_manifest
            and 'android:icon="@drawable/ic_media_intelligence"' in media_manifest
            and 'tools:ignore="ProtectedPermissions"' in media_manifest
            and media_manifest.count('tools:ignore="SelectedPhotoAccess"') == 2
            and all(media_extraction_rules.count(
                f'<exclude domain="{domain}" path="." />') == 2
                    for domain in extraction_domains)
            and not (root / "preview" / "mediascancheck" / "src" / "main" / "java" /
                     "com" / "aios" / "mediaintelligence" /
                     "MediaInferenceJobService.java").exists(),
            "media preview must compile the complete production service, manifest, and resources")
    require("^emulator-[0-9]+$" in media_smoke_script
            and "ro.kernel.qemu" in media_smoke_script
            and '$qemu -ne "1"' in media_smoke_script
            and "$apiLevel -lt 35" in media_smoke_script
            and "[Guid]::NewGuid()" in media_smoke_script
            and "Get-FileHash -LiteralPath $apkPath -Algorithm SHA256"
            in media_smoke_script
            and "apk_sha256 = $apkSha256" in media_smoke_script
            and "screenrecord --time-limit 2" in media_smoke_script
            and "MEDIA_SCANNER_SCAN_FILE" in media_smoke_script
            and "AIOS_MEDIA_POLICY_SMOKE_OK" in media_smoke_script
            and "AIOS_MEDIA_POLICY_SMOKE_FAILED" in media_smoke_script
            and "AIOS_MEDIA_RECOVERY_ASSERT_OK" in media_smoke_script
            and "restart_burst_settlement_verified = $true" in media_smoke_script
            and "historical_library_not_imported = $true" in media_smoke_script
            and "MediaObserverRecoverySmokeActivity" in media_preview_manifest
            and "MediaGenerationScanner.establishBaselines" in media_recovery_smoke
            and "MediaObserverService.class" in media_recovery_smoke
            and "MediaWorkPolicy.CLASS_DEFERRED" in media_recovery_smoke
            and "historical baseline image was imported" in media_recovery_smoke
            and "isolated_photo_immediate = $true" in media_smoke_script
            and "photo_burst_deferred = $true" in media_smoke_script
            and "video_deferred = $true" in media_smoke_script
            and "deferred_requires_80_percent = $true" in media_smoke_script
            and "android_job_constraints_verified = $true" in media_smoke_script
            and "AIOS_MUX_SMOKE_OK" in media_smoke_script
            and "AIOS_MUX_SMOKE_FAILED" in media_smoke_script
            and "AIOS_VIDEO_RECOVERY_SMOKE_OK" in media_smoke_script
            and "AIOS_VIDEO_RECOVERY_SMOKE_FAILED" in media_smoke_script
            and "encoded_source_samples_verified = $true" in media_smoke_script
            and "subtitle_renderer_exercised = $false" in media_smoke_script
            and "physical_gate_evidence = $false" in media_smoke_script
            and "content delete --uri $fixtureUri" in media_smoke_script
            and "uninstall $package" in media_smoke_script,
            "enhanced-video emulator smoke must be guarded, reproducible, self-cleaning, and non-physical")
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
    media_attempt_source = (
        media_source_root / "MediaInferenceAttempt.java"
    ).read_text(encoding="utf-8")
    media_attempt_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "MediaInferenceAttemptTest.java"
    ).read_text(encoding="utf-8")
    require("CONSTRAINT_RECHECK_MILLIS = 1_000L" in media_broker_source
            and "constraints.blockedReason()" in media_broker_source
            and "cancelAttempt(" in media_broker_source,
            "media Broker client must periodically cancel work that loses constraints")
    require("MediaInferenceAttempt<InferenceResult>" in media_broker_source
            and "onBindingDied" in media_broker_source
            and "onNullBinding" in media_broker_source
            and "disconnectBroker(" in media_broker_source
            and "attempt.fail(ERROR_BROKER_UNAVAILABLE, reason)"
            in media_broker_source
            and "finishAttempt(attempt)" in media_broker_source
            and "activeAttempt == attempt" in media_broker_source
            and "firstTerminalCallbackOwnsResult" in media_attempt_test
            and "disconnectWakesWaiterAndRejectsLateSuccess" in media_attempt_test
            and "cancellationReturnsOnlyItsOwnSession" in media_attempt_test
            and "resultBeforeInputSubmissionFailsClosed" in media_attempt_test
            and "sessionId = NO_SESSION" in media_attempt_source
            and "terminal.countDown()" in media_attempt_source,
            "media Broker loss and cancellation must wake one generation-owned attempt and reject stale callbacks")
    media_commit_fence = (
        media_source_root / "MediaJobCommitFence.java"
    ).read_text(encoding="utf-8")
    media_commit_fence_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "MediaJobCommitFenceTest.java"
    ).read_text(encoding="utf-8")
    require("commitFence.stop()" in job_source
            and "commitFence.runIfActive" in job_source
            and job_source.index("commitFence.runIfActive")
            < job_source.index("store.commitResult(")
            and "synchronized boolean runIfActive" in media_commit_fence
            and "stoppedJobCannotPublish" in media_commit_fence_test
            and "stopWaitsForInProgressPublication" in media_commit_fence_test,
            "JobScheduler stop must fence media result publication")
    media_job_run_gate = (
        media_source_root / "MediaJobRunGate.java"
    ).read_text(encoding="utf-8")
    media_job_run_gate_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "MediaJobRunGateTest.java"
    ).read_text(encoding="utf-8")
    require("jobInfo(\n                context, workClass, UUID.randomUUID().toString())"
            in job_source
            and "extras.putString(EXTRA_DELIVERY_ID, deliveryId)" in job_source
            and "MediaJobRunGate.Token run = runs.begin(deliveryId)" in job_source
            and "if (!runs.stop(deliveryId)) return false" in job_source
            and "MediaJobRunGate.Finish finish = runs.finish(run)" in job_source
            and "finish == MediaJobRunGate.Finish.COMPLETED" in job_source
            and "active.deliveryId.equals(deliveryId)" in media_job_run_gate
            and "active != expected" in media_job_run_gate
            and "staleStopCannotCancelTheActiveRun" in media_job_run_gate_test
            and "matchingStopSuppressesNormalCompletion" in media_job_run_gate_test
            and "oldFinishCannotClearAReplacementRun" in media_job_run_gate_test,
            "JobScheduler stop and finish callbacks must match one media worker-run identity")
    require("PreparedMedia.open" in media_broker_source
            and "prepared.capability" in media_broker_source
            and "prepared.submittedMimeType" in media_broker_source
            and "VideoStoryboard.isStoryboard(temporary)" in media_broker_source,
            "media Broker client must submit explicit inputs and erase private storyboards")
    require('request.capability = "streaming_asr"' in media_broker_source
            and 'request.workload = "media_background"' in media_broker_source
            and "request.deadlineElapsedRealtimeMillis = Long.MAX_VALUE"
            in media_broker_source
            and "TimeUnit.MINUTES.toMillis(INFERENCE_TIMEOUT_MINUTES)"
            in media_broker_source
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
            and "VideoStoryboardPlan.hasCompleteSampleSet(extractedFrames)"
            in storyboard_source
            and "requireAvailable(constraints)" in storyboard_source
            and "eraseCached(context)" in storyboard_source
            and "stream.getFD().sync()" in storyboard_source
            and "samplesTwentyChronologicalSegmentMidpoints" in storyboard_plan_test
            and "storyboardRequiresAllTwentySamples" in storyboard_plan_test
            and "scalingBoundsLandscapePortraitAndRotation" in storyboard_plan_test,
            "video storyboards must require twenty bounded keyframes with constraint checks")
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
            and "No text is drawn onto the video" in enhanced_activity
            and "startForegroundService" in enhanced_activity,
            "enhanced MP4 export must require explicit owner action and no subtitle rendering")
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
    association_context_binding = (
        media_source_root / "ResilientContextServiceBinding.java"
    ).read_text(encoding="utf-8")
    association_context_rebind_policy = (
        media_source_root / "ContextServiceRebindPolicy.java"
    ).read_text(encoding="utf-8")
    association_context_rebind_test = (
        root / "services" / "mediaintelligence" / "tests" / "src" / "com" /
        "aios" / "mediaintelligence" / "ContextServiceRebindPolicyTest.java"
    ).read_text(encoding="utf-8")
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
    require("new ResilientContextServiceBinding" in association_service
            and "contextBinding.isCurrent(service)" in association_service
            and "contextBinding.invalidate(failed)" in association_service
            and "activeConnection != this" in association_context_binding
            and "onBindingDied" in association_context_binding
            and "onNullBinding" in association_context_binding
            and "CONNECT_TIMEOUT_MILLIS = 15_000L" in association_context_binding
            and "ContextServiceRebindPolicy" in association_context_binding
            and "MAX_DELAY_MILLIS = 60_000L"
            in association_context_rebind_policy
            and "connectionRacingReservedRetryCancelsThatAttempt"
            in association_context_rebind_test
            and '"src/com/aios/mediaintelligence/ContextServiceRebindPolicy.java"'
            in media_bp,
            "media-context publication must recover through a generation-safe bounded binding")

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
    # The debug-only benchmark is allowed to probe the reserved typed contract
    # before a gated artifact is activated in the production catalog.
    available_capabilities.add("text_embedding")
    value = load_json(root / "config" / "authorized_clients.json")
    require(value.get("schema_version") == 1, "unsupported authorized-client schema")
    clients = value.get("clients")
    require(isinstance(clients, list) and clients, "authorized clients are required")
    packages = [client["package"] for client in clients]
    require(len(packages) == len(set(packages)), "authorized packages must be unique")
    allowed_workloads = {
        "call_rx", "call_tx", "call_agent", "call_background", "media_background",
        "context_query", "context_background",
    }
    for client in clients:
        require(str(client["package"]).startswith("com.aios."),
                f"unexpected preauthorized package: {client['package']}")
        require(set(client["capabilities"]).issubset(available_capabilities),
                f"{client['package']}: unknown capability")
        if "text_embedding" in client["capabilities"]:
            require(client["package"] == "com.aios.modelbenchmark",
                    "only the debug benchmark may hold dormant embedding authority")
        require(set(client["workloads"]).issubset(allowed_workloads),
                f"{client['package']}: unknown workload")
        require(client["max_sessions"] > 0 and client["max_output_tokens"] > 0,
                f"{client['package']}: invalid quota")
    by_package = {client["package"]: client for client in clients}
    require("call_rx" in by_package["com.aios.callintelligence"]["workloads"],
            "Call Intelligence must be authorized for call_rx")
    require("call_background"
            in by_package["com.aios.callintelligence"]["workloads"],
            "Call Intelligence needs a preemptible in-call compaction lane")
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
                "video_understanding", "speech_synthesis", "text_embedding",
            }
            and set(benchmark["workloads"])
            == {"call_rx", "call_agent", "media_background",
                "context_query", "context_background"}
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
            artifact_version = artifact.get("version", version)
            require(re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}",
                                 str(artifact_version)) is not None,
                    f"{runtime}: invalid binary artifact version")
            require(str(artifact.get("coordinate", "")).endswith(
                        f":{artifact_version}"),
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
    catalog_devices = {
        device["codename"]: device
        for device in catalog["known_devices"]
        if device["codename"] is not None
    }
    explicit_profiles: dict[str, dict[str, Any]] = {}
    for profile in profiles:
        devices = profile.get("devices")
        require(isinstance(devices, list) and devices
                and len(devices) == len(set(devices))
                and all(isinstance(device, str) and device
                        for device in devices),
                f"{profile['id']}: runtime device list must be non-empty and unique")
        allowed = profile.get("runtime_backends")
        preferred = profile.get("preferred_backends")
        require(isinstance(allowed, dict) and isinstance(preferred, dict),
                f"{profile['id']}: backend maps are required")
        for runtime, backend in preferred.items():
            require(backend in allowed.get(runtime, []),
                    f"{profile['id']}: preferred backend must be allowed")
        for device in devices:
            if device == "*":
                continue
            require(device in catalog_devices
                    and device not in explicit_profiles,
                    f"{profile['id']}: runtime device must be unique and catalogued")
            explicit_profiles[device] = profile
    require(set(explicit_profiles) == set(catalog_devices),
            "every officially identified Pixel needs an explicit runtime profile")
    for codename, device in catalog_devices.items():
        profile = explicit_profiles[codename]
        if device["enablement_status"] != "supported":
            require(profile.get("debuggable_only") is True,
                    f"{device['marketing_name']}: ungated runtime must be debug-only")
            require("npu" not in profile["runtime_backends"].get("litert_lm", []),
                    f"{device['marketing_name']}: unverified NPU must remain disabled")


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
            and "android.permission.WRITE_CALL_LOG" not in privileged_text
            and "com.android.voicemail.permission.ADD_VOICEMAIL" not in privileged_text,
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
    tegu_product = (root / "products" / "aios_tegu.mk").read_text(encoding="utf-8")
    module_names = discover_blueprint_modules(root)
    local_packages = [name for name in module_names if re.search(r"(?i)aios", name)]
    for module in local_packages:
        if module == "AIOS_Apache_2_0":
            continue
        require(module in common_product or module in tegu_product or module in {
                    "aios_call_api", "aios_context_api", "aios_media_context_api",
                    "aios_media_metadata_api", "aios_model_api", "aios_runtime_api",
                    "aios_runtime_common"}
                or module.endswith("_tests"),
                f"local AIOS module is not reachable from the product: {module}")


def validate_emulator_provider_evidence(record: dict, provider: str) -> None:
    """Validate native provider evidence without upgrading it to physical proof."""
    require(provider in {"asr", "tts"}, "unknown emulator provider evidence kind")
    gate = ("integration.emulator_bilingual_asr_provider" if provider == "asr"
            else "integration.emulator_bilingual_tts_provider")
    runtime = "whisper_cpp" if provider == "asr" else "sherpa_onnx_tts"
    require(record.get("schema_version") == 1
            and record.get("gate") == gate
            and re.fullmatch(r"[0-9a-f]{40}",
                             str(record.get("aios_revision", ""))) is not None
            and record.get("tracked_source_clean") is True
            and record.get("qemu") is True
            and isinstance(record.get("api_level"), int)
            and record["api_level"] >= 35
            and record.get("abi") == "x86_64"
            and record.get("runtime_id") == runtime
            and record.get("signature_permission_rejected_shell") is True
            and record.get("invalid_request_error_verified") is True
            and record.get("product_model_path_confinement_verified") is True
            and record.get("provider_survived_rejected_model") is True
            and record.get("temporary_fixture_files_remaining") == 0
            and record.get("arm64_provider_evidence") is False
            and record.get("physical_gate_evidence") is False,
            f"emulator {provider.upper()} provider evidence is incomplete or overclaims")
    if provider == "asr":
        require(record.get("real_native_asr_executed") is True
                and record.get("production_whisper_provider_bound_cross_process")
                is True
                and record.get("english_language_detected") is True
                and record.get("spanish_language_detected") is True
                and record.get("nonempty_final_transcripts_verified") is True
                and record.get("fixture_content_markers_verified") is True
                and record.get("call_rx_pipeline_verified") is True
                and record.get("emulator_real_time_gate") is False,
                "emulator ASR evidence must prove native bilingual content checks")
    else:
        require(record.get("real_native_tts_executed") is True
                and record.get("production_tts_provider_bound_cross_process") is True
                and record.get("english_pcm_verified") is True
                and record.get("spanish_pcm_verified") is True
                and record.get("pcm_metadata_matches_stream") is True,
                "emulator TTS evidence must prove native bilingual PCM checks")


def validate_emulator_integration_evidence(record: dict, kind: str) -> None:
    gates = {
        "context": ("integration.emulator_context_lifecycle", 1),
        "retention": ("integration.emulator_call_retention", 1),
        "model": ("integration.emulator_model_admission", 1),
        "media": ("integration.emulator_media_pipeline", 3),
        "messaging": ("integration.emulator_messaging", 1),
        "telecom": ("integration.emulator_telecom", 2),
    }
    require(kind in gates, "unknown emulator integration evidence kind")
    gate, schema = gates[kind]
    require(record.get("schema_version") == schema
            and record.get("gate") == gate
            and re.fullmatch(r"[0-9a-f]{40}",
                             str(record.get("aios_revision", ""))) is not None
            and record.get("tracked_source_clean") is True
            and record.get("qemu") is True
            and isinstance(record.get("api_level"), int)
            and record["api_level"] >= 35
            and record.get("physical_gate_evidence") is False,
            f"emulator {kind} evidence is incomplete or overclaims")
    if kind == "context":
        require(all(record.get(field) is True for field in (
                    "production_aidl_service_bound", "opaque_identity_verified",
                    "equivalent_number_convergence_verified", "sqlite_fts_verified",
                    "source_scoped_retrieval_verified", "query_limit_verified",
                    "sms_revision_and_tombstone_verified",
                    "media_bulk_delete_watermark_verified",
                    "call_artifact_binder_tombstone_verified",
                    "call_artifact_24h_expiry_verified",
                    "raw_address_absent_from_database"))
                and record.get("private_context_state_remaining") == 0,
                "emulator context evidence must prove retrieval and deletion lifecycle")
    elif kind == "retention":
        require(record.get("exact_retention_hours") == 24
                and all(record.get(field) is True for field in (
                    "active_writer_closed_before_expiry_delete",
                    "wall_clock_expiry_deleted", "unreadable_metadata_deleted_fail_closed",
                    "fresh_artifact_preserved", "resumed_artifact_deadline_unchanged",
                    "explicit_delete_verified", "nearest_elapsed_alarm_verified",
                    "empty_store_alarm_cancel_path_exercised"))
                and record.get("private_artifacts_remaining") == 0,
                "emulator retention evidence must prove exact fail-closed 24-hour cleanup")
    elif kind == "model":
        require(all(record.get(field) is True for field in (
                    "production_broker_aidl_bound",
                    "stock_install_without_product_policy_denied",
                    "artifact_digest_match_accepted", "same_size_artifact_tamper_rejected",
                    "canonical_path_escape_rejected", "ram_tier_catalog_selection_verified",
                    "release_device_admission_verified",
                    "build_fingerprint_mismatch_rejected",
                    "debug_research_candidate_gating_verified",
                    "authorized_client_quota_verified"))
                and record.get("temporary_fixture_bytes_are_model_weights") is False
                and record.get("real_inference_executed") is False
                and record.get("temporary_fixture_files_remaining") == 0,
                "emulator model evidence must prove admission without claiming inference")
    elif kind == "media":
        require(all(record.get(field) is True for field in (
                    "isolated_photo_immediate", "photo_burst_deferred",
                    "restart_burst_settlement_verified", "historical_library_not_imported",
                    "video_deferred", "deferred_requires_charging",
                    "deferred_requires_80_percent", "active_call_preempts_media",
                    "android_job_constraints_verified",
                    "mux_and_embedded_metadata_round_trip",
                    "encoded_source_samples_verified", "timed_subtitle_metadata_read",
                    "interrupted_export_recovery"))
                and record.get("subtitle_renderer_exercised") is False
                and record.get("original_opened_writable") is False,
                "emulator media evidence must prove scheduling and portable metadata safety")
    elif kind == "messaging":
        require(all(record.get(field) is True for field in (
                    "sms_role_assigned", "emulator_grpc_sms_injected",
                    "production_sms_deliver_provider_path", "incoming_provider_row_verified",
                    "incoming_compose_rendered", "sendto_composer_rendered",
                    "outgoing_sms_submission_accepted", "outgoing_provider_row_verified",
                    "emulator_loopback_inbox_verified", "outgoing_compose_rendered",
                    "same_provider_thread_verified", "valid_subscription_ids_verified",
                    "synthetic_rows_removed", "private_audit_removed",
                    "sms_role_restored", "package_removed"))
                and record.get("package_retained_for_debugging") is False
                and record.get("carrier_delivery_evidence") is False
                and record.get("multi_sim_selection_evidence") is False
                and record.get("mms_transport_evidence") is False,
                "emulator Messaging evidence must prove SMS while preserving carrier gates")
    else:
        fixed = record.get("automatic_answer_fixed_delays")
        require(record.get("execution_mode") == "full"
                and isinstance(fixed, list) and len(fixed) == 4
                and [item.get("resolved_delay_ms") for item in fixed]
                == [1000, 2000, 3000, 4000]
                and all(item.get("ai_answer_callback") is True
                        and item.get("connection_answer_count") == 1 for item in fixed)
                and isinstance(record.get("automatic_answer_random_delay"), dict)
                and 1010 <= record["automatic_answer_random_delay"].get(
                    "resolved_delay_ms", 0) <= 3990
                and all(record.get(field) is True for field in (
                    "in_call_activity_visible", "full_screen_intent_launched_automatically",
                    "in_call_service_bound", "incoming_notification_posted",
                    "ignore_preserved_ringing_call", "answer_activated_call",
                    "decline_disconnected_call", "ai_action_fail_closed",
                    "settings_policy_update_reached_binder",
                    "settings_policy_survived_service_restart",
                    "owner_answer_cancelled_pending_ai", "decline_cancelled_pending_ai",
                    "ignore_preserved_automatic_ai",
                    "service_loss_revoked_old_pending_ai",
                    "synthetic_emergency_never_evaluated_for_ai",
                    "outgoing_compose_call_action", "outgoing_connection_active",
                    "mute_unmute_round_trip", "hold_resume_round_trip",
                    "dtmf_play_stop_callbacks", "post_dial_digits_redacted",
                    "waiting_call_selected", "waiting_answer_held_existing_call",
                    "conference_merge_separate_callbacks",
                    "multi_account_selector_visible",
                    "selected_account_reached_connection_service",
                    "cleanup_verified", "original_role_holders_restored",
                    "original_outgoing_account_restored", "fixture_phone_account_removed",
                    "package_removed", "assistant_package_removed",
                    "remote_screenshot_removed", "remote_ui_dump_removed")),
                "emulator Telecom evidence must prove full call UDF and cleanup")


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
    target_policy = tracking.get("public_physical_target_policy", {})
    require(target_policy.get("changed_with_android") == 16
            and target_policy.get("google_guidance")
            == "build_cuttlefish_and_gsi_targets_for_experimentation"
            and target_policy.get("google_guidance_source")
            == "https://groups.google.com/g/android-building/c/S1G1edze3Co"
            and target_policy.get("android17_pixel_device_targets_present") is False
            and target_policy.get("forward_physical_lane")
            == "pixel9a_tegu_hardware"
            and target_policy.get("device_support_source")
            == "grapheneos_pinned_release_manifest"
            and target_policy.get("device_support_source_url")
            == "https://github.com/GrapheneOS/platform_manifest.git"
            and target_policy.get("device_support_build_guide")
            == "https://grapheneos.org/build",
            "physical Pixel releases must use the pinned full-device lane")
    require(tracking["first_device"].get("physical_manifest_tag")
            == "2026080500"
            and tracking["first_device"].get("physical_manifest_commit")
            == "d1b2739828a783bbf9bd6ba5d50c727b9329b9b7"
            and tracking["first_device"].get("physical_manifest_signature")
            == "verified_grapheneos_allowed_signer_2026_08_13"
            and tracking["first_device"].get("device_generation_command")
            == "adevtool generate-all -d tegu",
            "Pixel 9a device support must bind the reviewed signed manifest")
    public_candidate = tracking["first_device"].get(
        "last_complete_public_candidate", {}
    )
    require(public_candidate.get("platform_tag") == "android-15.0.0_r31"
            and public_candidate.get("build_id") == "BD4A.250505.003"
            and public_candidate.get("device_tree_commit")
            == "b0184eca7c2571669a0dd5708b5e555c475500be"
            and public_candidate.get("kernel_prebuilt_commit")
            == "5380e4f672819d5c9936b740b0f8b7772d80dd56"
            and public_candidate.get("vendor_archive")
            == "google_devices-tegu-bd4a.250505.003-9ab41e05.tgz"
            and public_candidate.get("vendor_archive_sha256")
            == "0ad7cd61322c38ba01d142123de4e30c69e091c54c0901d18beae7e4b6da7be2"
            and public_candidate.get("status")
            == "inventory_and_rollback_preflight_required",
            "Pixel 9a public fallback must be exact and blocked on rollback preflight")

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
    require("product/overlay/AiosFrameworkDefaultsOverlay.apk"
            in expected_artifacts,
            "build evidence must require the installed default-dialer overlay")
    lane_ids = [lane.get("id") for lane in lanes]
    require(lane_ids == [
                "android_latest_integration", "android_avd_integration",
                "android_gsi_arm64", "pixel9a_tegu_hardware",
            ],
            "AIOS must declare Cuttlefish, Emulator, ARM64 GSI, and Pixel lanes")
    catalog = load_json(root / "config" / "model_catalog.json")
    catalog_build_lanes = {
        device["build_lane"]
        for device in catalog["known_devices"]
        if device["build_lane"] is not None
    }
    require(catalog_build_lanes == {"pixel9a_tegu_hardware"}
            and catalog_build_lanes <= set(lane_ids),
            "enabled device catalog entries must reference declared hardware lanes")
    for lane in lanes:
        require(lane.get("artifact_layout")
                in {"product_partition", "gsi_system_product",
                    "full_device_target_files"}
                and isinstance(lane.get("required_images"), list)
                and bool(lane["required_images"])
                and len(lane["required_images"]) == len(set(lane["required_images"]))
                and all(re.fullmatch(r"[a-z0-9_]+\.img", image)
                        for image in lane["required_images"]),
                f"{lane.get('id')}: build artifact layout must be explicit")
    integration, emulator, gsi, hardware = lanes
    require(integration.get("kind") == "virtual_integration"
            and integration.get("manifest_revision") == "android-latest-release"
            and integration.get("product") == "aios_cf_x86_64_phone"
            and integration.get("physical_gate_evidence") is False
            and "frameworks/base" in integration.get("required_projects", [])
            and "device/google/cuttlefish" in integration.get("required_projects", []),
            "latest AOSP must build on Cuttlefish and remain non-physical evidence")
    require(emulator.get("kind") == "virtual_emulator"
            and emulator.get("manifest_revision") == "android-latest-release"
            and emulator.get("product") == "aios_sdk_phone_x86_64"
            and emulator.get("target_device") == "emu64x"
            and emulator.get("upstream_product") == "sdk_phone64_x86_64"
            and emulator.get("physical_gate_evidence") is False
            and "frameworks/base" in emulator.get("required_projects", [])
            and "device/generic/goldfish"
            in emulator.get("required_projects", []),
            "standard Android Emulator lane must remain virtual-only")
    require(gsi.get("kind") == "generic_system_image"
            and gsi.get("manifest_revision") == "android-latest-release"
            and gsi.get("product") == "aios_gsi_arm64"
            and gsi.get("target_device") == "generic_arm64"
            and gsi.get("upstream_product") == "gsi_arm64"
            and gsi.get("artifact_layout") == "gsi_system_product"
            and gsi.get("required_images")
            == ["pvmfw.img", "system.img", "vbmeta.img"]
            and gsi.get("compatibility_status")
            == "blocked_until_system_patch_matches_factory_and_physical_boot_passes"
            and gsi.get("factory_build_floor") == "CP2A.260705.006"
            and gsi.get("factory_security_patch_floor") == "2026-07-05"
            and gsi.get("public_aosp_source_status")
            == "exact_factory_tag_not_published"
            and gsi.get("pvmfw_deployment_policy")
            == "flash_atomically_with_matching_vbmeta_without_intermediate_reboot"
            and gsi.get("deployment_role")
            == "generic_and_virtual_research_only"
            and gsi.get("physical_gate_evidence") is True
            and gsi.get("replaces_device_partitions") == ["pvmfw", "system"]
            and set(gsi.get("preserves_device_partitions", []))
            == {"bootloader", "radio", "boot", "vendor", "odm"}
            and "device/generic/common" in gsi.get("required_projects", [])
            and "frameworks/base" in gsi.get("required_projects", []),
            "ARM64 GSI must remain blocked on the witnessed Pixel compatibility gates")
    require(hardware.get("kind") == "physical_hardware"
            and hardware.get("manifest_url")
            == "https://github.com/GrapheneOS/platform_manifest.git"
            and hardware.get("manifest_revision") == "refs/tags/2026080500"
            and hardware.get("manifest_commit")
            == "d1b2739828a783bbf9bd6ba5d50c727b9329b9b7"
            and hardware.get("manifest_signature_status")
            == "verified_grapheneos_allowed_signer"
            and hardware.get("product") == "aios_tegu"
            and hardware.get("lunch_target") == "aios_tegu-cur-userdebug"
            and hardware.get("upstream_product") == "tegu"
            and hardware.get("artifact_layout") == "full_device_target_files"
            and hardware.get("required_images") == [
                "boot.img", "product.img", "system.img", "vendor.img",
                "vendor_boot.img", "vendor_kernel_boot.img", "vbmeta.img",
            ]
            and hardware.get("required_model_ids") == [
                "gemma4-e2b-mobile-text",
                "gemma4-e2b-mobile-multimodal",
                "whisper-base-multilingual-quantized",
                "supertonic3-en-es-int8",
            ]
            and hardware.get("required_runtime_ids") == [
                "litert_lm", "sherpa_onnx_tts", "whisper_cpp",
            ]
            and hardware.get("build_version_policy") == {
                "format": "utc_date_sequence_yyyyMMddNN",
                "minimum_build_number_exclusive": "2026081300",
                "minimum_build_timestamp_exclusive": 1786646737,
            }
            and hardware.get("compatibility_status")
            == "full_model_inclusive_build_and_flash_passed_awaiting_exact_first_boot_evidence"
            and hardware.get("generated_device_path")
            == "vendor/google_devices/tegu"
            and hardware.get("device_generation_command")
            == "adevtool generate-all -d tegu"
            and hardware.get("allow_cross_release_device_tree") is False
            and hardware.get("physical_gate_evidence") is True
            and "frameworks/base" in hardware.get("required_projects", [])
            and "device/google/tegu-kernels/6.1"
            in hardware.get("required_projects", [])
            and "vendor/adevtool" in hardware.get("required_projects", []),
            "Pixel 9a lane must require the pinned full device-support set")
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
    require('"//vendor:__subpackages__"' in mms_patch_text
            and '"//vendor/aios/apps/messaging"' not in mms_patch_text
            and "framework-mms-shared-srcs" in mms_patch_text,
            "MMS patch must use Soong's legal platform-to-vendor visibility boundary")
    pixel_series = load_json(root / "patches" / "pixel9a-series.json")
    pixel_patches = pixel_series.get("patches", [])
    pixel_by_project = {patch.get("project"): patch for patch in pixel_patches}
    require(len(pixel_patches) == 2
            and set(pixel_by_project)
            == {"packages/apps/Dialer", "frameworks/base"}
            and pixel_by_project["packages/apps/Dialer"]["base_revision"]
            == "6a629762cf425002d34ecf28596813babda7d751"
            and pixel_by_project["frameworks/base"]["base_revision"]
            == "d6fd0d0e16b98e60d1cd738879c2c8807160f05e"
            and pixel_by_project["packages/apps/Dialer"]["file"]
            == dialer_patches[0]["file"]
            and pixel_by_project["frameworks/base"]["file"]
            == mms_patches[0]["file"],
            "Pixel patch queue must bind only the pinned Dialer and framework forks")
    gsi_size_patches = [
        patch for patch in series["patches"]
        if patch["project"] == "build/make"
    ]
    require(len(gsi_size_patches) == 1
            and gsi_size_patches[0]["paths"]
            == [
                "target/board/BoardConfigGsiCommon.mk",
                "target/product/gsi/Android.bp",
            ],
            "GSI integration must have one build/make patch and exact two-file scope")
    gsi_size_patch_text = (
        root / "patches" / gsi_size_patches[0]["file"]
    ).read_text(encoding="utf-8")
    require("BOARD_SUPER_PARTITION_SIZE ?= 3229614080"
            in gsi_size_patch_text
            and "BOARD_GSI_DYNAMIC_PARTITIONS_SIZE ?= 3221225472"
            in gsi_size_patch_text
            and gsi_size_patch_text.count("?=") == 2,
            "GSI integration patch must make exactly two size defaults conditional")
    require('name: "aios_gsi_system_image"' in gsi_size_patch_text
            and 'defaults: ["android_gsi_defaults"]' in gsi_size_patch_text
            and 'avb_private_key: ":avb_testkey_rsa2048"'
            in gsi_size_patch_text
            and 'avb_algorithm: "SHA256_RSA2048"' in gsi_size_patch_text
            and '"aios_product_policy"' in gsi_size_patch_text
            and "aios_model" not in gsi_size_patch_text
            and "AiosPhone" not in gsi_size_patch_text,
            "GSI wrapper must match the board AVB chain and use one stable AIOS anchor")
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
        "integration.android_latest_first_boot",
        "integration.android_avd_userdebug_succeeds",
        "integration.android_avd_first_boot",
        "integration.android_gsi_arm64_userdebug_succeeds",
        "integration.reference_model_pack_verified",
        "integration.emulator_bilingual_asr_provider",
        "integration.emulator_bilingual_tts_provider",
        "integration.emulator_context_lifecycle",
        "integration.emulator_call_retention",
        "integration.emulator_model_admission",
        "integration.emulator_media_pipeline",
        "integration.emulator_messaging",
        "integration.emulator_telecom",
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
        "dialer.preloaded_default_emergency_path",
        "dialer.direct_boot_call_controls",
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
        "call.capture_loss_fail_open",
        "retention.expiry_24_hours",
        "media.blocked_below_80_percent",
        "media.original_preserved",
        "media.video_storyboard_indexed",
        "media.video_subtitles_indexed",
        "media.pixel9a_latency_profile",
        "model.runtime_dependency_lock_verified",
        "model.build_fingerprint_admission_enforced",
        "model.runtime_fallback_selection",
        "model.runtime_identity_enforced",
        "model.runtime_crash_isolated",
        "model.runtime_provider_recovery",
        "model.litertlm_known_answer",
        "model.pixel9a_gpu_benchmark",
    }
    require(critical.issubset(ids),
            f"missing critical release gates: {sorted(critical - set(ids))}")

    source_bound_smokes = {
        "emulator-context-lifecycle-smoke.ps1":
            "integration.emulator_context_lifecycle",
        "emulator-call-retention-smoke.ps1":
            "integration.emulator_call_retention",
        "emulator-model-admission-smoke.ps1":
            "integration.emulator_model_admission",
        "emulator-media-smoke.ps1": "integration.emulator_media_pipeline",
        "emulator-messaging-smoke.ps1": "integration.emulator_messaging",
        "emulator-telecom-smoke.ps1": "integration.emulator_telecom",
    }
    for script_name, gate_id in source_bound_smokes.items():
        script = (root / "scripts" / script_name).read_text(encoding="utf-8")
        require(f'gate = "{gate_id}"' in script
                and "aios_revision = $sourceRevision" in script
                and "tracked_source_clean = $true" in script
                and "git -C $repositoryRoot diff --quiet --" in script
                and "git -C $repositoryRoot diff --cached --quiet --" in script,
                f"{script_name}: evidence must bind a clean exact AIOS revision")
    telecom_smoke = (root / "scripts" / "emulator-telecom-smoke.ps1").read_text(
        encoding="utf-8"
    )
    require("am start -W -a com.aios.phone.smoke.REGISTER" in telecom_smoke
            and "Wait-ForPhoneAccountState -Enabled $false -MinimumCount 2"
            in telecom_smoke
            and "Wait-ForPhoneAccountState -Enabled $true -MinimumCount 1"
            in telecom_smoke
            and "Wait-ForPhoneAccountState -Enabled $true -MinimumCount 2"
            in telecom_smoke
            and "Telecom deliberately redacts PhoneAccount IDs" in telecom_smoke,
            "Telecom smoke must wait for PhoneAccount registration and enablement")

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
        for reference in evidence:
            if reference.startswith("https://"):
                continue
            evidence_path = (root / reference).resolve()
            require(evidence_path == root.resolve()
                    or root.resolve() in evidence_path.parents,
                    f"{gate_id}: evidence path escapes the repository")
            require(evidence_path.is_file(),
                    f"{gate_id}: local evidence file does not exist: {reference}")

    physical_build_gate_ids = (
        "build.manifest_locked",
        "build.userdebug_succeeds",
    )
    physical_build_references = {
        reference
        for gate_id in physical_build_gate_ids
        if statuses[gate_id]["status"] == "passed"
        for reference in statuses[gate_id]["evidence"]
    }
    physical_build = None
    physical_build_path = None
    if physical_build_references:
        require(len(physical_build_references) == 1,
                "Pixel build gates must reference one exact build record")
        physical_build_reference = next(iter(physical_build_references))
        require(not physical_build_reference.startswith("https://"),
                "Pixel build evidence must be locally reviewable")
        physical_build_path = (root / physical_build_reference).resolve()
        physical_build = load_json(physical_build_path)
        target_files = physical_build.get("target_files_package")
        generated_support = physical_build.get("generated_device_support")
        generated_payloads = physical_build.get("generated_payloads")
        model_pack = (generated_payloads or {}).get("model_pack")
        runtime_packs = (generated_payloads or {}).get("runtime_packs")
        artifacts = physical_build.get("artifacts")
        require(physical_build.get("schema_version") == 2
                and physical_build.get("status") == "passed"
                and physical_build.get("lane") == "pixel9a_tegu_hardware"
                and physical_build.get("kind") == "physical_hardware"
                and physical_build.get("product") == "aios_tegu"
                and physical_build.get("target_device") == "tegu"
                and physical_build.get("lunch_target")
                == "aios_tegu-cur-userdebug"
                and physical_build.get("android_release") == "17"
                and physical_build.get("artifact_layout")
                == "full_device_target_files"
                and physical_build.get("lane_eligible_for_physical_gates") is True
                and physical_build.get("proves_physical_runtime_gate") is False
                and physical_build.get("manifest_repository_revision")
                == hardware.get("manifest_commit")
                and re.fullmatch(r"[0-9a-f]{40}",
                                 str(physical_build.get("aios_revision", "")))
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(physical_build.get("manifest_lock_sha256", "")))
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(physical_build.get("manifest_sha256", "")))
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(physical_build.get("build_log_sha256", "")))
                and physical_build.get("deployable_images")
                == hardware.get("required_images")
                and isinstance(generated_support, dict)
                and generated_support.get("path") == "vendor/google_devices/tegu"
                and generated_support.get("generator") == "adevtool"
                and generated_support.get("symlink_count") == 0
                and isinstance(target_files, dict)
                and target_files.get("size_bytes", 0) > 0
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(target_files.get("sha256", "")))
                and physical_build.get("installed_files_sha256")
                == target_files.get("sha256")
                and isinstance(model_pack, dict)
                and model_pack.get("models") == hardware.get("required_model_ids")
                and isinstance(runtime_packs, list)
                and [item.get("runtime") for item in runtime_packs]
                == hardware.get("required_runtime_ids")
                and isinstance(artifacts, list) and len(artifacts) >= 34
                and all(isinstance(item, dict)
                        and re.fullmatch(r"[a-z0-9_+./-]+", str(item.get("path", "")),
                                         re.IGNORECASE)
                        and ".." not in Path(item["path"]).parts
                        and item.get("size_bytes", 0) > 0
                        and re.fullmatch(r"[0-9a-f]{64}",
                                         str(item.get("sha256", "")))
                        for item in artifacts),
                "Pixel build evidence does not prove the locked full-device build")
        incremental = str(physical_build.get("build_incremental", ""))
        build_timestamp = physical_build.get("build_timestamp")
        require(re.fullmatch(r"[0-9]{10}", incremental)
                and incremental > hardware["build_version_policy"][
                    "minimum_build_number_exclusive"]
                and isinstance(build_timestamp, int)
                and build_timestamp > hardware["build_version_policy"][
                    "minimum_build_timestamp_exclusive"]
                and f"/{incremental}:userdebug/test-keys"
                in str(physical_build.get("build_fingerprint", "")),
                "Pixel build evidence is not monotonic or fingerprint-bound")
    if statuses["build.userdebug_succeeds"]["status"] == "passed":
        require(statuses["build.manifest_locked"]["status"] == "passed",
                "Pixel userdebug build cannot pass before its manifest lock")

    ota_gate = statuses["update.full_virtual_ab_ota_packaged"]
    physical_ota = None
    physical_ota_path = None
    if ota_gate["status"] == "passed":
        require(statuses["build.userdebug_succeeds"]["status"] == "passed"
                and physical_build is not None
                and physical_build_path is not None,
                "Pixel full OTA cannot pass before its exact build")
        require(len(ota_gate["evidence"]) == 1
                and not ota_gate["evidence"][0].startswith("https://"),
                "Pixel full OTA evidence must be one local record")
        physical_ota_path = (root / ota_gate["evidence"][0]).resolve()
        physical_ota = load_json(physical_ota_path)
        signature = physical_ota.get("signature_verification")
        ota_archive = physical_ota.get("ota_archive")
        payload = physical_ota.get("payload")
        ota_metadata = physical_ota.get("ota_metadata")
        require(physical_ota.get("schema_version") == 1
                and physical_ota.get("status") == "passed"
                and physical_ota.get("update_kind") == "full_virtual_ab_ota"
                and physical_ota.get("lane") == "pixel9a_tegu_hardware"
                and physical_ota.get("product") == "aios_tegu"
                and physical_ota.get("target_device") == "tegu"
                and physical_ota.get("aios_revision")
                == physical_build.get("aios_revision")
                and physical_ota.get("build_fingerprint")
                == physical_build.get("build_fingerprint")
                and physical_ota.get("security_patch")
                == physical_build.get("security_patch")
                and physical_ota.get("build_evidence_sha256")
                == hashlib.sha256(physical_build_path.read_bytes()).hexdigest()
                and physical_ota.get("target_files_sha256")
                == physical_build["target_files_package"]["sha256"]
                and physical_ota.get("virtual_ab_compression") == "true"
                and physical_ota.get("contains_required_model_payloads") is True
                and physical_ota.get("installation_performed") is False
                and physical_ota.get("signing_state")
                == "public_android_test_keys_unlocked_bootloader_only"
                and isinstance(signature, dict)
                and signature.get("status") == "passed"
                and signature.get("whole_file_and_payload_verified") is True
                and isinstance(ota_archive, dict)
                and ota_archive.get("size_bytes", 0) > 0
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(ota_archive.get("sha256", "")))
                and isinstance(payload, dict)
                and payload.get("size_bytes", 0) > 0
                and 0 < payload.get("metadata_size_bytes", 0)
                < payload["size_bytes"]
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(payload.get("sha256", "")))
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(payload.get("metadata_sha256", "")))
                and isinstance(ota_metadata, dict)
                and ota_metadata.get("ota-type") == "AB"
                and ota_metadata.get("pre-device") == "tegu"
                and ota_metadata.get("post-build")
                == physical_build.get("build_fingerprint")
                and ota_metadata.get("post-build-incremental")
                == physical_build.get("build_incremental")
                and ota_metadata.get("post-timestamp")
                == str(physical_build.get("build_timestamp"))
                and ota_metadata.get("post-security-patch-level")
                == physical_build.get("security_patch"),
                "Pixel full OTA evidence is not bound to the signed model-inclusive build")

    update_dependency_chain = (
        ("update.post_update_boot", "update.full_virtual_ab_ota_packaged"),
        ("update.snapshot_merge_completed", "update.post_update_boot"),
        ("update.rollback_to_previous_slot", "update.full_virtual_ab_ota_packaged"),
    )
    for gate_id, prerequisite in update_dependency_chain:
        if statuses[gate_id]["status"] == "passed":
            require(statuses[prerequisite]["status"] == "passed",
                    f"{gate_id} cannot pass before {prerequisite}")

    post_update_gate = statuses["update.post_update_boot"]
    post_update = None
    post_update_path = None
    update_result = None
    update_result_path = None
    if post_update_gate["status"] == "passed":
        require(physical_build is not None and physical_build_path is not None
                and physical_ota is not None and physical_ota_path is not None,
                "Pixel post-update boot requires its local build and OTA chain")
        require(len(post_update_gate["evidence"]) == 2
                and all(not item.startswith("https://")
                        for item in post_update_gate["evidence"]),
                "Pixel post-update boot requires local update-result and boot records")
        for reference in post_update_gate["evidence"]:
            candidate_path = (root / reference).resolve()
            candidate = load_json(candidate_path)
            if candidate.get("kind") == "pixel9a_aios_virtual_ab_update":
                require(update_result is None,
                        "Pixel post-update evidence has duplicate update results")
                update_result = candidate
                update_result_path = candidate_path
            elif candidate.get("kind") == "pixel9a_aios_virtual_ab_post_update_boot":
                require(post_update is None,
                        "Pixel post-update evidence has duplicate boot records")
                post_update = candidate
                post_update_path = candidate_path
            else:
                raise ValidationError(
                    "Pixel post-update evidence contains an unknown record"
                )
        require(update_result is not None and update_result_path is not None
                and post_update is not None and post_update_path is not None,
                "Pixel post-update evidence is incomplete")
        update_serial = update_result.get("serial_sha256")
        require(update_result.get("schema_version") == 1
                and update_result.get("status") == "update_engine_command_passed"
                and re.fullmatch(r"[0-9a-f]{64}", str(update_serial or ""))
                and update_result.get("ota_evidence_sha256")
                == hashlib.sha256(physical_ota_path.read_bytes()).hexdigest()
                and update_result.get("ota_archive_sha256")
                == physical_ota["ota_archive"]["sha256"]
                and update_result.get("target_fingerprint")
                == physical_build["build_fingerprint"]
                and update_result.get("source_fingerprint")
                != update_result.get("target_fingerprint")
                and update_result.get("source_slot") in {"_a", "_b"}
                and update_result.get("expected_target_slot") in {"_a", "_b"}
                and update_result.get("source_slot")
                != update_result.get("expected_target_slot")
                and update_result.get("payload_applicability_verified") is True
                and update_result.get("payload_space_allocated") is True
                and update_result.get("staging_removed") is True
                and update_result.get("reboot_performed") is False
                and update_result.get("proves_update_engine_command_passed") is True
                and update_result.get("proves_post_update_boot") is False
                and update_result.get("proves_slot_switch") is False
                and update_result.get("proves_merge_completed") is False,
                "Pixel update result does not bind the signed OTA command")
        post_checks = post_update.get("checks")
        post_properties = post_update.get("properties")
        require(post_update.get("schema_version") == 1
                and post_update.get("status") == "passed"
                and post_update.get("serial_sha256") == update_serial
                and post_update.get("build_fingerprint")
                == physical_build["build_fingerprint"]
                and post_update.get("source_fingerprint")
                == update_result.get("source_fingerprint")
                and post_update.get("source_slot")
                == update_result.get("source_slot")
                and post_update.get("active_slot")
                == update_result.get("expected_target_slot")
                and post_update.get("build_evidence_sha256")
                == hashlib.sha256(physical_build_path.read_bytes()).hexdigest()
                and post_update.get("ota_evidence_sha256")
                == hashlib.sha256(physical_ota_path.read_bytes()).hexdigest()
                and post_update.get("update_result_sha256")
                == hashlib.sha256(update_result_path.read_bytes()).hexdigest()
                and post_update.get("ota_archive_sha256")
                == physical_ota["ota_archive"]["sha256"]
                and isinstance(post_properties, dict)
                and post_properties.get("ro.build.fingerprint")
                == physical_build["build_fingerprint"]
                and post_properties.get("ro.build.version.incremental")
                == physical_build["build_incremental"]
                and post_properties.get("ro.boot.slot_suffix")
                == update_result.get("expected_target_slot")
                and post_properties.get("ro.virtual_ab.enabled") == "true"
                and post_properties.get("ro.virtual_ab.compression.enabled") == "true"
                and isinstance(post_checks, dict)
                and all(post_checks.get(field) is True for field in (
                    "build_ota_update_chain_verified",
                    "boot_completed",
                    "exact_target_fingerprint",
                    "inactive_slot_became_active",
                    "every_evidenced_product_artifact_verified",
                ))
                and post_update.get("proves_update_engine_command_passed") is True
                and post_update.get("proves_post_update_boot") is True
                and post_update.get("proves_slot_switch") is True
                and post_update.get("proves_model_payload_install") is True
                and post_update.get("proves_merge_completed") is False
                and post_update.get("proves_rollback") is False
                and post_update.get("proves_telephony_gate") is False
                and post_update.get("proves_model_inference") is False,
                "Pixel post-update boot record does not bind the exact target slot")

    merge_gate = statuses["update.snapshot_merge_completed"]
    merge = None
    merge_path = None
    if merge_gate["status"] == "passed":
        require(post_update is not None and post_update_path is not None,
                "Pixel merge evidence requires the local post-update record")
        require(len(merge_gate["evidence"]) == 1
                and not merge_gate["evidence"][0].startswith("https://"),
                "Pixel merge evidence must be one local record")
        merge_path = (root / merge_gate["evidence"][0]).resolve()
        merge = load_json(merge_path)
        merge_checks = merge.get("checks")
        require(merge.get("schema_version") == 1
                and merge.get("status") == "passed"
                and merge.get("kind") == "pixel9a_aios_virtual_ab_merge"
                and merge.get("serial_sha256") == post_update.get("serial_sha256")
                and merge.get("build_fingerprint")
                == post_update.get("build_fingerprint")
                and merge.get("build_incremental")
                == physical_build.get("build_incremental")
                and merge.get("active_slot") == post_update.get("active_slot")
                and merge.get("snapshot_update_state") == "none"
                and merge.get("snapshot_count") == 0
                and merge.get("boot_control_merge_status") == "none"
                and merge.get("current_slot_marked_successful") is True
                and merge.get("post_update_evidence_sha256")
                == hashlib.sha256(post_update_path.read_bytes()).hexdigest()
                and isinstance(merge_checks, dict)
                and all(merge_checks.get(field) is True for field in (
                    "exact_post_update_chain_verified",
                    "exact_target_still_booted",
                    "target_slot_current_and_active",
                    "target_slot_marked_successful",
                    "snapshot_update_state_none",
                    "no_snapshot_records",
                    "no_merge_indicators",
                    "boot_control_merge_status_none",
                ))
                and merge.get("proves_post_update_boot") is True
                and merge.get("proves_slot_switch") is True
                and merge.get("proves_merge_completed") is True
                and merge.get("proves_rollback") is False
                and merge.get("proves_telephony_gate") is False
                and merge.get("proves_model_inference") is False,
                "Pixel merge evidence does not prove the exact update is durable")

    rollback_gate = statuses["update.rollback_to_previous_slot"]
    if rollback_gate["status"] == "passed":
        require(physical_ota is not None and physical_ota_path is not None,
                "Pixel rollback evidence requires the local full OTA record")
        require(len(rollback_gate["evidence"]) == 3
                and all(not item.startswith("https://")
                        for item in rollback_gate["evidence"]),
                "Pixel rollback evidence requires update, preparation, and capture records")
        rollback_records = {}
        rollback_paths = {}
        rollback_kinds = {
            "pixel9a_aios_virtual_ab_update",
            "pixel9a_aios_virtual_ab_rollback_prepare",
            "pixel9a_aios_virtual_ab_rollback",
        }
        for reference in rollback_gate["evidence"]:
            record_path = (root / reference).resolve()
            record = load_json(record_path)
            kind = record.get("kind")
            require(kind in rollback_kinds and kind not in rollback_records,
                    "Pixel rollback evidence has an unknown or duplicate phase")
            rollback_records[kind] = record
            rollback_paths[kind] = record_path
        require(set(rollback_records) == rollback_kinds,
                "Pixel rollback evidence is missing a required phase")

        rollback_update = rollback_records["pixel9a_aios_virtual_ab_update"]
        prepare = rollback_records["pixel9a_aios_virtual_ab_rollback_prepare"]
        rollback = rollback_records["pixel9a_aios_virtual_ab_rollback"]
        update_path = rollback_paths["pixel9a_aios_virtual_ab_update"]
        prepare_path = rollback_paths["pixel9a_aios_virtual_ab_rollback_prepare"]
        serial_sha = rollback_update.get("serial_sha256")
        source_fingerprint = rollback_update.get("source_fingerprint")
        source_match = re.fullmatch(
            r"AIOS/aios_tegu/tegu:[^/]+/[^/]+/"
            r"([A-Za-z0-9._+-]{1,64}):userdebug/test-keys",
            str(source_fingerprint or ""),
        )
        require(source_match is not None,
                "Pixel rollback source fingerprint is invalid")
        source_incremental = source_match.group(1)
        source_slot = rollback_update.get("source_slot")
        target_slot = rollback_update.get("expected_target_slot")
        target_fingerprint = physical_ota.get("build_fingerprint")
        target_incremental = physical_build.get("build_incremental")
        ota_sha = hashlib.sha256(physical_ota_path.read_bytes()).hexdigest()
        update_sha = hashlib.sha256(update_path.read_bytes()).hexdigest()
        if update_result_path is not None:
            require(update_sha != hashlib.sha256(
                update_result_path.read_bytes()).hexdigest(),
                    "Pixel rollback requires a separate OTA application attempt")

        require(rollback_update.get("schema_version") == 1
                and rollback_update.get("status") == "update_engine_command_passed"
                and re.fullmatch(r"[0-9a-f]{64}", str(serial_sha or ""))
                and rollback_update.get("ota_evidence_sha256") == ota_sha
                and rollback_update.get("ota_archive_sha256")
                == physical_ota["ota_archive"]["sha256"]
                and rollback_update.get("target_fingerprint") == target_fingerprint
                and source_fingerprint != target_fingerprint
                and source_slot in {"_a", "_b"}
                and target_slot in {"_a", "_b"}
                and source_slot != target_slot
                and rollback_update.get("payload_applicability_verified") is True
                and rollback_update.get("payload_space_allocated") is True
                and rollback_update.get("staging_removed") is True
                and rollback_update.get("reboot_performed") is False
                and rollback_update.get("proves_update_engine_command_passed") is True
                and rollback_update.get("proves_post_update_boot") is False
                and rollback_update.get("proves_slot_switch") is False
                and rollback_update.get("proves_merge_completed") is False,
                "Pixel rollback update result does not bind an unverified OTA")

        require(prepare.get("schema_version") == 1
                and prepare.get("status") == "source_slot_armed"
                and prepare.get("serial_sha256") == serial_sha
                and prepare.get("source_fingerprint") == source_fingerprint
                and prepare.get("source_incremental") == source_incremental
                and prepare.get("source_slot") == source_slot
                and prepare.get("target_fingerprint") == target_fingerprint
                and prepare.get("target_incremental") == target_incremental
                and prepare.get("pending_target_slot") == target_slot
                and prepare.get("pre_current_slot") == source_slot
                and prepare.get("pre_active_slot") == target_slot
                and prepare.get("post_current_slot") == source_slot
                and prepare.get("post_active_slot") == source_slot
                and prepare.get("source_slot_bootable_after_arm") is True
                and prepare.get("snapshot_update_state") == "unverified"
                and isinstance(prepare.get("snapshot_count"), int)
                and prepare["snapshot_count"] > 0
                and prepare.get("boot_control_merge_status") == "snapshotted"
                and prepare.get("rollback_indicator_absent") is True
                and prepare.get("forward_merge_indicator_absent") is True
                and prepare.get("source_build_fingerprint_present") is True
                and prepare.get("ota_evidence_sha256") == ota_sha
                and prepare.get("update_result_sha256") == update_sha
                and prepare.get("ota_archive_sha256")
                == physical_ota["ota_archive"]["sha256"]
                and re.fullmatch(r"[0-9a-f]{64}", str(
                    prepare.get("confirmation_token_sha256", "")))
                and prepare.get("reboot_performed") is False
                and prepare.get("target_boot_performed") is False
                and prepare.get("proves_update_engine_command_passed") is True
                and prepare.get("proves_pending_update_was_armed") is True
                and prepare.get("proves_source_slot_boot") is False
                and prepare.get("proves_post_update_boot") is False
                and prepare.get("proves_merge_completed") is False
                and prepare.get("proves_rollback") is False,
                "Pixel rollback preparation is not inside the unverified window")

        rollback_checks = rollback.get("checks")
        require(rollback.get("schema_version") == 1
                and rollback.get("status") == "passed"
                and rollback.get("serial_sha256") == serial_sha
                and rollback.get("source_fingerprint") == source_fingerprint
                and rollback.get("source_incremental") == source_incremental
                and rollback.get("source_slot") == source_slot
                and rollback.get("cancelled_target_fingerprint") == target_fingerprint
                and rollback.get("cancelled_target_incremental") == target_incremental
                and rollback.get("cancelled_target_slot") == target_slot
                and rollback.get("final_active_slot") == source_slot
                and rollback.get("ota_evidence_sha256") == ota_sha
                and rollback.get("update_result_sha256") == update_sha
                and rollback.get("prepare_result_sha256")
                == hashlib.sha256(prepare_path.read_bytes()).hexdigest()
                and rollback.get("ota_archive_sha256")
                == physical_ota["ota_archive"]["sha256"]
                and re.fullmatch(r"[0-9a-f]{64}", str(
                    rollback.get("snapshot_dump_sha256", "")))
                and isinstance(rollback_checks, dict)
                and all(rollback_checks.get(field) is True for field in (
                    "exact_ota_update_prepare_chain_verified",
                    "source_boot_completed",
                    "full_device_not_gsi",
                    "exact_source_fingerprint",
                    "source_slot_current_and_active",
                    "source_slot_marked_successful",
                    "source_slot_bootable",
                    "unverified_update_cancelled",
                    "snapshot_update_state_none",
                    "no_snapshot_records",
                    "no_snapshot_indicators",
                    "boot_control_merge_status_none",
                ))
                and rollback.get("target_boot_performed") is False
                and rollback.get("fresh_update_required") is True
                and rollback.get("proves_update_engine_command_passed") is True
                and rollback.get("proves_source_slot_boot") is True
                and rollback.get("proves_post_update_boot") is False
                and rollback.get("proves_merge_completed") is False
                and rollback.get("proves_rollback") is True
                and rollback.get("proves_telephony_gate") is False
                and rollback.get("proves_model_inference") is False
                and rollback.get("proves_model_latency_gate") is False
                and rollback.get("proves_media_gate") is False,
                "Pixel rollback evidence does not prove pre-merge cancellation")

    latest_build_gates = (
        "integration.android_latest_manifest_locked",
        "integration.android_latest_userdebug_succeeds",
    )
    passed_build_references = {
        reference
        for gate_id in latest_build_gates
        if statuses[gate_id]["status"] == "passed"
        for reference in statuses[gate_id]["evidence"]
    }
    if passed_build_references:
        require(len(passed_build_references) == 1,
                "Android-latest build gates must reference one build record")
        build_reference = next(iter(passed_build_references))
        require(not build_reference.startswith("https://"),
                "Android-latest build evidence must be locally reviewable")
        build_path = (root / build_reference).resolve()
        build = load_json(build_path)
        require(build.get("schema_version") == 2
                and build.get("status") == "passed"
                and build.get("lane") == "android_latest_integration"
                and build.get("kind") == "virtual_integration"
                and build.get("product") == "aios_cf_x86_64_phone"
                and build.get("target_device") == "vsoc_x86_64"
                and build.get("android_release") == "17"
                and build.get("lane_eligible_for_physical_gates") is False
                and build.get("proves_physical_runtime_gate") is False,
                "Android-latest build evidence does not prove the virtual lane")

        first_boot = statuses["integration.android_latest_first_boot"]
        if first_boot["status"] == "passed":
            require(len(first_boot["evidence"]) == 1
                    and not first_boot["evidence"][0].startswith("https://"),
                    "Android-latest first-boot evidence must be one local record")
            boot = load_json((root / first_boot["evidence"][0]).resolve())
            require(boot.get("schema_version") == 1
                    and boot.get("status") == "passed"
                    and boot.get("gate")
                    == "integration.android_latest_first_boot"
                    and boot.get("lane") == "android_latest_integration"
                    and boot.get("kind") == "virtual_integration"
                    and boot.get("aios_revision") == build.get("aios_revision")
                    and boot.get("build_fingerprint")
                    == build.get("build_fingerprint")
                    and boot.get("build_evidence_sha256")
                    == hashlib.sha256(build_path.read_bytes()).hexdigest()
                    and boot.get("lane_eligible_for_physical_gates") is False
                    and boot.get("proves_physical_runtime_gate") is False,
                    "Android-latest boot evidence is not bound to its build")

    avd_build_gate = statuses["integration.android_avd_userdebug_succeeds"]
    if avd_build_gate["status"] == "passed":
        require(len(avd_build_gate["evidence"]) == 1
                and not avd_build_gate["evidence"][0].startswith("https://"),
                "Android AVD build evidence must be one local record")
        avd_build_path = (root / avd_build_gate["evidence"][0]).resolve()
        avd_build = load_json(avd_build_path)
        require(avd_build.get("schema_version") == 2
                and avd_build.get("status") == "passed"
                and avd_build.get("lane") == "android_avd_integration"
                and avd_build.get("kind") == "virtual_emulator"
                and avd_build.get("product") == "aios_sdk_phone_x86_64"
                and avd_build.get("target_device") == "emu64x"
                and avd_build.get("android_release") == "17"
                and avd_build.get("lane_eligible_for_physical_gates") is False
                and avd_build.get("proves_physical_runtime_gate") is False,
                "Android AVD build evidence does not prove the Goldfish lane")

        avd_boot_gate = statuses["integration.android_avd_first_boot"]
        if avd_boot_gate["status"] == "passed":
            require(len(avd_boot_gate["evidence"]) == 1
                    and not avd_boot_gate["evidence"][0].startswith("https://"),
                    "Android AVD first-boot evidence must be one local record")
            avd_boot = load_json((root / avd_boot_gate["evidence"][0]).resolve())
            require(avd_boot.get("schema_version") == 1
                    and avd_boot.get("status") == "passed"
                    and avd_boot.get("gate") == "integration.android_avd_first_boot"
                    and avd_boot.get("lane") == "android_avd_integration"
                    and avd_boot.get("kind") == "virtual_emulator"
                    and avd_boot.get("product") == "aios_sdk_phone_x86_64"
                    and avd_boot.get("target_device") == "emu64x"
                    and avd_boot.get("aios_revision")
                    == avd_build.get("aios_revision")
                    and avd_boot.get("build_fingerprint")
                    == avd_build.get("build_fingerprint")
                    and avd_boot.get("build_evidence_sha256")
                    == hashlib.sha256(avd_build_path.read_bytes()).hexdigest()
                    and avd_boot.get("lane_eligible_for_physical_gates") is False
                    and avd_boot.get("proves_physical_runtime_gate") is False,
                    "Android AVD boot evidence is not bound to its build")
    else:
        require(statuses["integration.android_avd_first_boot"]["status"]
                != "passed",
                "Android AVD first boot cannot pass before its build gate")

    gsi_build_gate = statuses["integration.android_gsi_arm64_userdebug_succeeds"]
    if gsi_build_gate["status"] == "passed":
        require(len(gsi_build_gate["evidence"]) == 1
                and not gsi_build_gate["evidence"][0].startswith("https://"),
                "ARM64 GSI build evidence must be one local record")
        gsi_build_path = (root / gsi_build_gate["evidence"][0]).resolve()
        gsi_build = load_json(gsi_build_path)
        require(gsi_build.get("schema_version") == 2
                and gsi_build.get("status") == "passed"
                and gsi_build.get("lane") == "android_gsi_arm64"
                and gsi_build.get("kind") == "generic_system_image"
                and gsi_build.get("product") == "aios_gsi_arm64"
                and gsi_build.get("target_device") == "generic_arm64"
                and gsi_build.get("android_release") == "17"
                and gsi_build.get("artifact_layout") == "gsi_system_product"
                and gsi_build.get("deployable_images")
                == ["pvmfw.img", "system.img", "vbmeta.img"]
                and gsi_build.get("installed_files_manifest") in {
                    "installed-files-system.json", "installed-files.json"
                }
                and gsi_build.get("lane_eligible_for_physical_gates") is True
                and gsi_build.get("proves_physical_runtime_gate") is False,
                "ARM64 GSI build evidence does not prove the deployable lane")
        generated_payloads = gsi_build.get("generated_payloads")
        model_payload = (generated_payloads.get("model_pack")
                         if isinstance(generated_payloads, dict) else None)
        runtime_payloads = (generated_payloads.get("runtime_packs")
                            if isinstance(generated_payloads, dict) else None)
        require(isinstance(model_payload, dict)
                and set(model_payload.get("models", [])) == {
                    "gemma4-e2b-mobile-text",
                    "gemma4-e2b-mobile-multimodal",
                    "whisper-base-multilingual-quantized",
                    "supertonic3-en-es-int8",
                }
                and isinstance(model_payload.get("installed_file_count"), int)
                and model_payload["installed_file_count"] == 15
                and re.fullmatch(
                    r"[0-9a-f]{64}",
                    str(model_payload.get("manifest_sha256", ""))) is not None,
                "ARM64 GSI evidence must bind every Pixel 9a model payload")
        require(isinstance(runtime_payloads, list)
                and {item.get("runtime") for item in runtime_payloads
                     if isinstance(item, dict)} == {
                         "litert_lm", "whisper_cpp", "sherpa_onnx_tts"
                     }
                and all(
                    isinstance(item, dict)
                    and re.fullmatch(r"[0-9a-f]{40}",
                                     str(item.get("source_revision", "")))
                    and re.fullmatch(r"[0-9a-f]{64}",
                                     str(item.get("manifest_sha256", "")))
                    and re.fullmatch(r"[0-9a-f]{64}",
                                     str(item.get("unsigned_provider_sha256", "")))
                    and re.fullmatch(
                        r"[0-9a-f]{64}",
                        str(item.get("platform_signed_provider_sha256", "")))
                    for item in runtime_payloads
                ),
                "ARM64 GSI evidence must bind all platform-signed AI providers")
        avb_path = gsi_build_path.parent / "avb-verification.json"
        require(avb_path.is_file(),
                "ARM64 GSI build evidence requires sibling AVB verification")
        avb = load_json(avb_path)
        image_artifacts = {
            item.get("path"): item
            for item in gsi_build.get("artifacts", [])
            if isinstance(item, dict)
            and item.get("path") in {"pvmfw.img", "system.img", "vbmeta.img"}
        }
        avb_images = avb.get("images")
        expected_checks = {
            "vbmeta_signature_verified",
            "system_chain_descriptor_matches_expected_key_and_slot",
            "system_footer_signature_verified",
            "system_sha256_hashtree_verified",
            "pvmfw_sha256_hash_verified",
            "ext_filesystem_read_only_check_passed",
        }
        checks = avb.get("checks")
        chain = avb.get("expected_chain_partition")
        pvmfw_descriptor = avb.get("pvmfw_descriptor")
        require(avb.get("schema_version") == 1
                and avb.get("status") == "passed"
                and avb.get("kind") == "gsi_avb_chain_verification"
                and avb.get("aios_revision") == gsi_build.get("aios_revision")
                and avb.get("build_evidence_sha256")
                == hashlib.sha256(gsi_build_path.read_bytes()).hexdigest()
                and isinstance(chain, dict)
                and chain.get("partition") == "system"
                and chain.get("rollback_index_location") == 1
                and chain.get("public_key_sha1")
                == "cdbb77177f731920bbe0a0f94f84d9038ae0617d"
                and chain.get("algorithm") == "SHA256_RSA2048"
                and isinstance(pvmfw_descriptor, dict)
                and pvmfw_descriptor.get("partition") == "pvmfw"
                and pvmfw_descriptor.get("algorithm") == "SHA256_RSA4096"
                and pvmfw_descriptor.get("public_key_sha1")
                == "2597c218aae470a130f61162feaae70afd97f011"
                and isinstance(
                    pvmfw_descriptor.get("original_image_size_bytes"), int
                )
                and pvmfw_descriptor["original_image_size_bytes"] > 0
                and re.fullmatch(
                    r"[0-9a-f]{64}",
                    str(pvmfw_descriptor.get("digest", "")),
                ) is not None
                and isinstance(avb_images, dict)
                and set(avb_images)
                == {"pvmfw.img", "system.img", "vbmeta.img"}
                and set(image_artifacts)
                == {"pvmfw.img", "system.img", "vbmeta.img"}
                and all(
                    isinstance(avb_images[name], dict)
                    and avb_images[name].get("size_bytes")
                    == image_artifacts[name].get("size_bytes")
                    and avb_images[name].get("sha256")
                    == image_artifacts[name].get("sha256")
                    for name in ("pvmfw.img", "system.img", "vbmeta.img")
                )
                and isinstance(checks, dict)
                and set(checks) == expected_checks
                and all(checks[name] is True for name in expected_checks)
                and avb.get("lane_eligible_for_physical_gates") is True
                and avb.get("proves_physical_runtime_gate") is False,
                "ARM64 GSI AVB evidence is not bound to the deployable images")
        dsu_path = gsi_build_path.parent / "dsu-payload.json"
        require(dsu_path.is_file(),
                "ARM64 GSI build evidence requires sibling DSU payload evidence")
        dsu = load_json(dsu_path)
        dsu_source = dsu.get("source_image")
        dsu_payload = dsu.get("payload")
        dsu_checks = dsu.get("checks")
        dsu_transfer = dsu.get("windows_transfer_probe")
        system_artifact = image_artifacts.get("system.img", {})
        require(dsu.get("schema_version") == 1
                and dsu.get("status") == "passed"
                and dsu.get("kind") == "gsi_dsu_payload"
                and dsu.get("aios_revision") == gsi_build.get("aios_revision")
                and dsu.get("build_evidence_sha256")
                == hashlib.sha256(gsi_build_path.read_bytes()).hexdigest()
                and isinstance(dsu_source, dict)
                and dsu_source.get("name") == "system.img"
                and dsu_source.get("format")
                == "raw_ext4_with_avb_footer"
                and dsu_source.get("size_bytes")
                == system_artifact.get("size_bytes")
                and dsu_source.get("sha256")
                == system_artifact.get("sha256")
                and isinstance(dsu_payload, dict)
                and isinstance(dsu_payload.get("name"), str)
                and dsu_payload["name"].endswith(".raw.gz")
                and dsu_payload.get("format") == "gzip"
                and dsu_payload.get("compression_level") in range(1, 10)
                and isinstance(dsu_payload.get("size_bytes"), int)
                and dsu_payload["size_bytes"] > 0
                and dsu_payload.get("uncompressed_size_bytes")
                == system_artifact.get("size_bytes")
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(dsu_payload.get("sha256", ""))) is not None
                and isinstance(dsu_checks, dict)
                and set(dsu_checks) == {
                    "gzip_integrity_verified",
                    "stream_decompression_sha256_verified",
                    "windows_local_staging_sha256_verified",
                }
                and all(value is True for value in dsu_checks.values())
                and isinstance(dsu_transfer, dict)
                and dsu_transfer.get("source_transport") == "wsl_unc"
                and isinstance(dsu_transfer.get("copy_seconds"), (int, float))
                and dsu_transfer["copy_seconds"] > 0
                and isinstance(dsu_transfer.get("hash_seconds"), (int, float))
                and dsu_transfer["hash_seconds"] > 0
                and dsu_transfer.get("size_bytes")
                == dsu_payload.get("size_bytes")
                and dsu_transfer.get("sha256") == dsu_payload.get("sha256")
                and dsu_transfer.get("temporary_copy_removed") is True
                and dsu.get("external_payload_only") is True
                and dsu.get("safe_to_install") is False
                and dsu.get("proves_physical_runtime_gate") is False,
                "ARM64 GSI DSU payload evidence is not bound to the system image")
        interface_path = gsi_build_path.parent / "system-interface.json"
        require(interface_path.is_file(),
                "ARM64 GSI build evidence requires system-interface evidence")
        interface = load_json(interface_path)
        interface_image = interface.get("system_image")
        embedded = interface.get("embedded_property_file")
        interface_properties = interface.get("properties")
        interface_checks = interface.get("checks")
        require(interface.get("schema_version") == 1
                and interface.get("status") == "passed"
                and interface.get("kind")
                == "gsi_system_interface_properties"
                and interface.get("aios_revision")
                == gsi_build.get("aios_revision")
                and interface.get("build_evidence_sha256")
                == hashlib.sha256(gsi_build_path.read_bytes()).hexdigest()
                and isinstance(interface_image, dict)
                and interface_image.get("size_bytes")
                == system_artifact.get("size_bytes")
                and interface_image.get("sha256")
                == system_artifact.get("sha256")
                and isinstance(embedded, dict)
                and embedded.get("path") == "/system/build.prop"
                and isinstance(embedded.get("size_bytes"), int)
                and embedded["size_bytes"] > 0
                and re.fullmatch(r"[0-9a-f]{64}",
                                 str(embedded.get("sha256", ""))) is not None
                and isinstance(interface_properties, dict)
                and interface_properties.get("ro.build.version.release")
                == gsi_build.get("android_release")
                and interface_properties.get(
                    "ro.build.version.security_patch"
                ) == gsi_build.get("security_patch")
                and re.fullmatch(
                    r"20[0-9]{2}(?:0[1-9]|1[0-2])",
                    str(interface_properties.get("ro.llndk.api_level", "")),
                ) is not None
                and interface_properties.get("ro.treble.enabled") == "true"
                and isinstance(interface_checks, dict)
                and set(interface_checks) == {
                    "extracted_from_verified_system_image",
                    "build_output_matches_embedded_file",
                    "llndk_api_level_uses_yyyymm_format",
                }
                and all(value is True for value in interface_checks.values())
                and interface.get("proves_device_compatibility") is False
                and interface.get("proves_physical_runtime_gate") is False,
                "ARM64 GSI system-interface evidence is not bound to the image")

    model_pack_gate = statuses["integration.reference_model_pack_verified"]
    if model_pack_gate["status"] == "passed":
        require(len(model_pack_gate["evidence"]) == 1
                and not model_pack_gate["evidence"][0].startswith("https://"),
                "reference model-pack evidence must be one local record")
        model_pack = load_json((root / model_pack_gate["evidence"][0]).resolve())
        require(model_pack.get("schema_version") == 1
                and model_pack.get("status") == "passed"
                and re.fullmatch(r"[0-9a-f]{40}",
                                 str(model_pack.get("aios_revision", ""))) is not None
                and model_pack.get("model_catalog_sha256")
                == hashlib.sha256(
                    (root / "config" / "model_catalog.json").read_bytes()).hexdigest()
                and model_pack.get("catalog_binding_verified") is True
                and model_pack.get("generated_pack_verified") is True
                and model_pack.get("contains_model_weights") is False
                and model_pack.get("proves_model_inference") is False
                and model_pack.get("proves_physical_device_runtime") is False
                and isinstance(model_pack.get("logical_artifact_count"), int)
                and model_pack["logical_artifact_count"] == 4
                and isinstance(model_pack.get("physical_model_payload_count"), int)
                and model_pack["physical_model_payload_count"] == 10,
                "reference model-pack evidence is not catalog-bound packaging proof")
        packed_ids = {
            item.get("model_id") for item in model_pack.get("artifacts", [])
            if isinstance(item, dict)
        }
        require(packed_ids == {
                    "gemma4-e2b-mobile-text",
                    "gemma4-e2b-mobile-multimodal",
                    "whisper-base-multilingual-quantized",
                    "supertonic3-en-es-int8",
                },
                "reference model pack must contain every Pixel 9a model role")

    for gate_id, provider in (
        ("integration.emulator_bilingual_asr_provider", "asr"),
        ("integration.emulator_bilingual_tts_provider", "tts"),
    ):
        provider_gate = statuses[gate_id]
        if provider_gate["status"] != "passed":
            continue
        require(len(provider_gate["evidence"]) == 1
                and not provider_gate["evidence"][0].startswith("https://"),
                f"emulator {provider.upper()} evidence must be one local record")
        provider_record = load_json(
            (root / provider_gate["evidence"][0]).resolve()
        )
        validate_emulator_provider_evidence(provider_record, provider)

    for gate_id, kind in (
        ("integration.emulator_context_lifecycle", "context"),
        ("integration.emulator_call_retention", "retention"),
        ("integration.emulator_model_admission", "model"),
        ("integration.emulator_media_pipeline", "media"),
        ("integration.emulator_messaging", "messaging"),
        ("integration.emulator_telecom", "telecom"),
    ):
        integration_gate = statuses[gate_id]
        if integration_gate["status"] != "passed":
            continue
        require(len(integration_gate["evidence"]) == 1
                and not integration_gate["evidence"][0].startswith("https://"),
                f"emulator {kind} evidence must be one local record")
        integration_record = load_json(
            (root / integration_gate["evidence"][0]).resolve()
        )
        validate_emulator_integration_evidence(integration_record, kind)


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
