import copy
import io
import importlib.util
import json
import hashlib
import shutil
import tempfile
import tarfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "validate_config", ROOT / "tools" / "validate_config.py"
)
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)

PACK_SPEC = importlib.util.spec_from_file_location(
    "generate_model_pack", ROOT / "tools" / "generate_model_pack.py"
)
packager = importlib.util.module_from_spec(PACK_SPEC)
assert PACK_SPEC.loader is not None
sys_modules_before = __import__("sys").modules
sys_modules_before[PACK_SPEC.name] = packager
PACK_SPEC.loader.exec_module(packager)

RUNTIME_PACK_SPEC = importlib.util.spec_from_file_location(
    "generate_runtime_pack", ROOT / "tools" / "generate_runtime_pack.py"
)
runtime_packager = importlib.util.module_from_spec(RUNTIME_PACK_SPEC)
assert RUNTIME_PACK_SPEC.loader is not None
RUNTIME_PACK_SPEC.loader.exec_module(runtime_packager)

ADMISSION_SPEC = importlib.util.spec_from_file_location(
    "generate_model_admission", ROOT / "tools" / "generate_model_admission.py"
)
admission_generator = importlib.util.module_from_spec(ADMISSION_SPEC)
assert ADMISSION_SPEC.loader is not None
ADMISSION_SPEC.loader.exec_module(admission_generator)


def load(name):
    return json.loads((ROOT / "config" / name).read_text(encoding="utf-8"))


def copy_patch_contract_fixture(destination):
    shutil.copytree(ROOT / "patches", destination / "patches")
    for relative in (
            "scripts/emulator-context-lifecycle-smoke.ps1",
            "scripts/emulator-call-retention-smoke.ps1",
            "scripts/emulator-model-admission-smoke.ps1",
            "scripts/emulator-media-smoke.ps1",
            "scripts/emulator-messaging-smoke.ps1",
            "scripts/emulator-telecom-smoke.ps1",
            "tests/test_patch_series.py",
            "tests/test_validate_config.py"):
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(ROOT / relative, target)


def passing_admission_evidence(
        catalog, suite, tier_id="edge_8gb", profile_id="pixel_9a_tegu",
        device_codename="tegu", total_ram_mb=8192, selected_ids=None):
    roles = validator.tier_candidate_roles(catalog, tier_id)
    if selected_ids is not None:
        roles = {model_id: roles[model_id] for model_id in selected_ids}
    models = {item["id"]: item for item in catalog["models"]}
    results = []
    for model_id, role in roles.items():
        metrics = {item: 1 for item in suite["required_observations"]}
        gates = suite["gate_profiles"][role]
        for gate in gates:
            metrics[gate["metric"]] = gate["threshold"]
        results.append({
            "model_id": model_id,
            "runtime": models[model_id]["runtime"],
            "backend": models[model_id]["default_backend"],
            "artifact_sha256": hashlib.sha256(model_id.encode()).hexdigest(),
            "decision": "passed",
            "required_gates": [gate["id"] for gate in gates],
            "failed_gates": [],
            "metrics": metrics,
        })
    return {
        "schema_version": 2,
        "suite_version": suite["suite_version"],
        "suite_sha256": admission_generator.canonical_sha256(suite),
        "profile_id": profile_id,
        "catalog_tier": tier_id,
        "device_codename": device_codename,
        "total_ram_mb": total_ram_mb,
        "build_fingerprint_sha256": "1" * 64,
        "completed_at": "2026-08-09T12:00:00Z",
        "results": results,
    }


class ProductPolicyTests(unittest.TestCase):
    def test_repository_configuration_is_valid(self):
        validator.validate(ROOT)

    def test_default_dialer_overlay_is_fail_closed(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            shutil.copytree(ROOT / "overlays", temporary / "overlays")
            (temporary / "products").mkdir()
            shutil.copy(ROOT / "products" / "aios_common.mk",
                        temporary / "products" / "aios_common.mk")
            config = (temporary / "overlays" / "frameworkdefaults" / "res" /
                      "values" / "config.xml")
            config.write_text(
                config.read_text(encoding="utf-8").replace(
                    "com.aios.phone", "com.android.dialer"),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(validator.ValidationError,
                                        "fresh AIOS users"):
                validator.validate_default_dialer_overlay(temporary)

    def test_emergency_bypass_is_required(self):
        policy = load("product_policy.json")
        policy["calls"]["bypass_emergency_calls"] = False
        with self.assertRaisesRegex(validator.ValidationError, "emergency"):
            validator.validate_product(policy)

    def test_retention_cannot_drift_past_24_hours(self):
        policy = load("product_policy.json")
        policy["retention"]["call_artifact_ttl_hours"] = 48
        with self.assertRaisesRegex(validator.ValidationError, "24 hours"):
            validator.validate_product(policy)

    def test_media_threshold_is_enforced(self):
        policy = load("product_policy.json")
        policy["media"]["deferred_min_battery_percent"] = 50
        with self.assertRaisesRegex(validator.ValidationError, "80 percent"):
            validator.validate_product(policy)

    def test_call_processing_must_default_off(self):
        policy = load("product_policy.json")
        policy["calls"]["processing_default_enabled"] = True
        with self.assertRaisesRegex(validator.ValidationError, "opt-in"):
            validator.validate_product(policy)

    def test_auto_answer_delay_modes_are_exact(self):
        policy = load("product_policy.json")
        policy["calls"]["allowed_auto_answer_delay_modes"].append("fixed_5000_ms")
        with self.assertRaisesRegex(validator.ValidationError, "delay modes"):
            validator.validate_product(policy)

    def test_random_auto_answer_delay_bounds_are_exact(self):
        policy = load("product_policy.json")
        policy["calls"]["random_auto_answer_delay_ms"]["min_inclusive"] = 1000
        with self.assertRaisesRegex(validator.ValidationError, "1.01 through 3.99"):
            validator.validate_product(policy)

    def test_unvalidated_media_writer_is_rejected(self):
        policy = load("product_policy.json")
        policy["media"]["index_only_mime_types"].remove("image/webp")
        policy["media"]["writable_mime_types"].append("image/webp")
        with self.assertRaisesRegex(validator.ValidationError, "still-PNG"):
            validator.validate_product(policy)

    def test_call_policy_vectors_are_valid(self):
        validator.validate_policy_vectors(ROOT)

    def test_authorized_clients_are_valid(self):
        validator.validate_authorized_clients(ROOT)

    def test_emergency_vector_cannot_be_overridden_by_all_mode(self):
        actual = validator.call_policy_decision("all", False, True, False)
        self.assertEqual("bypass_ai", actual)


class ModelCatalogTests(unittest.TestCase):
    def setUp(self):
        self.catalog = load("model_catalog.json")

    def test_pixel_9a_selects_8gb_tier(self):
        self.assertEqual("edge_8gb", validator.select_tier(self.catalog, 8192))

    def test_pixel_10_selects_12gb_tier(self):
        self.assertEqual("edge_12gb", validator.select_tier(self.catalog, 12288))

    def test_gemma4_candidates_are_bound_to_official_litert_artifacts(self):
        model = next(item for item in self.catalog["models"]
                     if item["id"] == "gemma4-e2b-mobile-text")
        model["reference_artifact"]["sha256"] = "0" * 64

        with self.assertRaisesRegex(
                validator.ValidationError, "pinned LiteRT-LM artifact"):
            validator.validate_catalog(self.catalog)

    def test_every_single_file_reference_has_an_exact_size_lock(self):
        model = next(item for item in self.catalog["models"]
                     if item["id"] == "whisper-base-multilingual-quantized")
        model["reference_artifact"].pop("size_bytes")

        with self.assertRaisesRegex(
                validator.ValidationError, "HTTPS URL, size, and digest"):
            validator.validate_catalog(self.catalog)

    def test_high_memory_tier_exposes_ordered_independent_fallbacks(self):
        roles = validator.tier_candidate_roles(self.catalog, "edge_16gb_plus")

        self.assertEqual([
            "gemma4-e4b-mobile-text",
            "gemma4-e4b-mobile-multimodal",
            "supertonic3-en-es-int8",
            "whisper-small-multilingual-quantized",
            "whisper-base-multilingual-quantized",
            "gemma4-e2b-mobile-text",
            "gemma4-e2b-mobile-multimodal",
        ], list(roles))
        self.assertEqual("text_model", roles["gemma4-e2b-mobile-text"])
        self.assertEqual("media_model", roles["gemma4-e2b-mobile-multimodal"])

    def test_official_pixel_10_family_maps_by_measured_ram(self):
        expected = {
            "Pixel 10": ("frankel", "edge_12gb"),
            "Pixel 10 Pro": ("blazer", "edge_16gb_plus"),
            "Pixel 10 Pro XL": ("mustang", "edge_16gb_plus"),
            "Pixel 10 Pro Fold": ("rango", "edge_16gb_plus"),
            "Pixel 10a": (None, "edge_8gb"),
        }
        actual = {
            device["marketing_name"]: (
                device["codename"], device["expected_tier"])
            for device in self.catalog["known_devices"]
            if device["marketing_name"].startswith("Pixel 10")
        }
        self.assertEqual(expected, actual)
        for device in self.catalog["known_devices"]:
            if device["marketing_name"].startswith("Pixel 10"):
                self.assertIsNone(device["build_lane"])
                self.assertTrue(device["enablement_status"].startswith(
                    "catalog_only_"))

    def test_speculative_pixel_11_is_not_preenabled(self):
        names = {item["marketing_name"] for item in self.catalog["known_devices"]}
        self.assertNotIn("Pixel 11", names)

    def test_unknown_codename_cannot_escape_catalog_only_state(self):
        catalog = copy.deepcopy(self.catalog)
        pixel_10a = next(item for item in catalog["known_devices"]
                         if item["marketing_name"] == "Pixel 10a")
        pixel_10a["enablement_status"] = "supported"
        with self.assertRaisesRegex(validator.ValidationError, "catalog-only"):
            validator.validate_catalog(catalog)

    def test_official_codename_does_not_enable_device_without_build_lane(self):
        catalog = copy.deepcopy(self.catalog)
        pixel_10 = next(item for item in catalog["known_devices"]
                        if item["marketing_name"] == "Pixel 10")
        pixel_10["enablement_status"] = "supported"
        with self.assertRaisesRegex(validator.ValidationError,
                                    "without a build lane"):
            validator.validate_catalog(catalog)

    def test_fixed_memory_budget_is_rejected(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["tiers"][0]["max_foreground_model_mb"] = 100
        with self.assertRaisesRegex(validator.ValidationError, "fixed model-memory"):
            validator.validate_catalog(catalog)

    def test_fallback_cycle_is_rejected(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["tiers"][0]["fallback_tier"] = "edge_16gb_plus"
        with self.assertRaisesRegex(validator.ValidationError, "fallback"):
            validator.validate_catalog(catalog)


class ModelAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.catalog = load("model_catalog.json")

    def test_pending_pixel_9a_profile_is_valid(self):
        validator.validate_model_admission(ROOT)

    def test_catalog_only_pixel_10_codenames_have_pending_research_profiles(self):
        policy = load("model_admission.json")
        profiles_by_device = {
            device: profile
            for profile in policy["profiles"]
            for device in profile["devices"]
        }
        catalog_only = {
            device["codename"]
            for device in self.catalog["known_devices"]
            if device["codename"] is not None
            and device["build_lane"] is None
        }
        self.assertEqual({"frankel", "blazer", "mustang", "rango"},
                         catalog_only)
        self.assertTrue(catalog_only <= set(profiles_by_device))
        for codename in catalog_only:
            profile = profiles_by_device[codename]
            self.assertEqual("benchmark_pending", profile["status"])
            self.assertEqual([], profile["admitted_models"])
            self.assertEqual([], profile["evidence"])

    def test_catalog_only_device_cannot_gain_release_admission(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            config = temporary / "config"
            evidence_dir = temporary / "evidence" / "model-admission"
            config.mkdir()
            evidence_dir.mkdir(parents=True)
            for name in ("model_catalog.json", "model_admission.json",
                         "model_benchmark_suite.json"):
                shutil.copy(ROOT / "config" / name,
                            config / name)
            evidence_path = evidence_dir / "pixel-10-test.json"
            evidence_path.write_text(json.dumps(passing_admission_evidence(
                load("model_catalog.json"),
                load("model_benchmark_suite.json"),
                tier_id="edge_12gb",
                profile_id="pixel_10_frankel",
                device_codename="frankel",
                total_ram_mb=12288,
            )), encoding="utf-8")
            generated = admission_generator.generate(
                config / "model_catalog.json",
                config / "model_admission.json",
                [evidence_path],
                config / "generated-admission.json",
                temporary,
            )
            (config / "model_admission.json").write_text(
                json.dumps(generated), encoding="utf-8")

            with self.assertRaisesRegex(validator.ValidationError,
                                        "research-only"):
                validator.validate_model_admission(temporary)

    def test_known_device_cannot_lose_its_fail_closed_profile(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            shutil.copy(ROOT / "config" / "model_catalog.json",
                        temporary / "config" / "model_catalog.json")
            shutil.copy(ROOT / "config" / "model_benchmark_suite.json",
                        temporary / "config" / "model_benchmark_suite.json")
            value = load("model_admission.json")
            value["profiles"][0]["devices"] = ["wrong-device"]
            (temporary / "config" / "model_admission.json").write_text(
                json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "known device lacks"):
                validator.validate_model_admission(temporary)

    def test_admission_profile_cannot_span_device_codenames(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            for name in ("model_catalog.json", "model_admission.json",
                         "model_benchmark_suite.json"):
                shutil.copy(ROOT / "config" / name,
                            temporary / "config" / name)
            value = load("model_admission.json")
            value["profiles"][0]["devices"].append("another-device")
            (temporary / "config" / "model_admission.json").write_text(
                json.dumps(value), encoding="utf-8")

            with self.assertRaisesRegex(validator.ValidationError,
                                        "exactly one device codename"):
                validator.validate_model_admission(temporary)
            evidence_dir = temporary / "evidence" / "model-admission"
            evidence_dir.mkdir(parents=True)
            evidence_path = evidence_dir / "multi-device.json"
            evidence_path.write_text(json.dumps(passing_admission_evidence(
                load("model_catalog.json"),
                load("model_benchmark_suite.json"),
            )), encoding="utf-8")
            with self.assertRaisesRegex(admission_generator.AdmissionError,
                                        "exactly one device codename"):
                admission_generator.generate(
                    temporary / "config" / "model_catalog.json",
                    temporary / "config" / "model_admission.json",
                    [evidence_path],
                    temporary / "config" / "generated-admission.json",
                    temporary,
                )

    def test_generator_binds_passes_to_exact_artifacts_and_evidence(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            config = temporary / "config"
            evidence_dir = temporary / "evidence" / "model-admission"
            config.mkdir()
            evidence_dir.mkdir(parents=True)
            for name in ("model_catalog.json", "model_admission.json",
                         "model_benchmark_suite.json"):
                shutil.copy(ROOT / "config" / name, config / name)
            catalog = json.loads((config / "model_catalog.json").read_text())
            tier = next(item for item in catalog["tiers"]
                        if item["id"] == "edge_8gb")
            suite = json.loads((config / "model_benchmark_suite.json").read_text())
            model_ids = {
                tier["text_model"], tier["media_model"], tier["tts_model"],
                *tier["asr_candidates"],
            }
            evidence = passing_admission_evidence(catalog, suite)
            evidence_path = evidence_dir / "pixel-9a-test.json"
            evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
            output = config / "generated-admission.json"
            generated = admission_generator.generate(
                config / "model_catalog.json",
                config / "model_admission.json",
                [evidence_path],
                output,
                temporary,
            )
            profile = generated["profiles"][0]
            self.assertEqual("supported", profile["status"])
            self.assertEqual(model_ids,
                             {item["model_id"] for item in profile["admitted_models"]})
            (config / "model_admission.json").write_text(
                json.dumps(generated), encoding="utf-8")
            validator.validate_model_admission(temporary)

            evidence_path.write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "evidence digest"):
                validator.validate_model_admission(temporary)

    def test_generator_combines_primary_and_fallback_artifact_evidence(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            config = temporary / "config"
            evidence_dir = temporary / "evidence" / "model-admission"
            config.mkdir()
            evidence_dir.mkdir(parents=True)
            for name in ("model_catalog.json", "model_admission.json",
                         "model_benchmark_suite.json"):
                shutil.copy(ROOT / "config" / name, config / name)
            catalog = json.loads((config / "model_catalog.json").read_text())
            suite = json.loads((config / "model_benchmark_suite.json").read_text())
            policy = json.loads((config / "model_admission.json").read_text())
            roles = validator.tier_candidate_roles(catalog, "edge_12gb")
            policy["profiles"].append({
                "id": "future_12gb_test",
                "devices": ["future-test"],
                "catalog_tier": "edge_12gb",
                "min_total_ram_mb": 11264,
                "max_total_ram_mb": 13312,
                "status": "benchmark_pending",
                "research_candidate_models": list(roles),
                "admitted_models": [],
                "evidence": [],
            })
            (config / "model_admission.json").write_text(
                json.dumps(policy), encoding="utf-8")
            primary_ids = {
                "gemma4-e4b-mobile-text",
                "gemma4-e4b-mobile-multimodal",
                "supertonic3-en-es-int8",
                "whisper-small-multilingual-quantized",
            }
            fallback_ids = {
                "gemma4-e2b-mobile-text",
                "gemma4-e2b-mobile-multimodal",
                "supertonic3-en-es-int8",
                "whisper-base-multilingual-quantized",
            }
            evidence_paths = []
            for label, selected_ids in (
                    ("primary", primary_ids), ("fallback", fallback_ids)):
                evidence = passing_admission_evidence(
                    catalog,
                    suite,
                    tier_id="edge_12gb",
                    profile_id="future_12gb_test",
                    device_codename="future-test",
                    total_ram_mb=12288,
                    selected_ids=selected_ids,
                )
                path = evidence_dir / f"{label}.json"
                path.write_text(json.dumps(evidence), encoding="utf-8")
                evidence_paths.append(path)

            output = config / "generated-admission.json"
            fallback_original = evidence_paths[1].read_text(encoding="utf-8")
            mismatched = json.loads(fallback_original)
            mismatched["build_fingerprint_sha256"] = "2" * 64
            evidence_paths[1].write_text(
                json.dumps(mismatched), encoding="utf-8")
            with self.assertRaisesRegex(
                    admission_generator.AdmissionError,
                    "share device, RAM, and build fingerprint"):
                admission_generator.generate(
                    config / "model_catalog.json",
                    config / "model_admission.json",
                    evidence_paths,
                    output,
                    temporary,
                )
            evidence_paths[1].write_text(
                fallback_original, encoding="utf-8")
            generated = admission_generator.generate(
                config / "model_catalog.json",
                config / "model_admission.json",
                evidence_paths,
                output,
                temporary,
            )
            profile = next(item for item in generated["profiles"]
                           if item["id"] == "future_12gb_test")
            self.assertEqual("supported", profile["status"])
            self.assertEqual(primary_ids | fallback_ids,
                             {item["model_id"]
                              for item in profile["admitted_models"]})
            self.assertEqual(2, len(profile["evidence"]))
            (config / "model_admission.json").write_text(
                json.dumps(generated), encoding="utf-8")
            validator.validate_model_admission(temporary)

            fallback_evidence = json.loads(fallback_original)
            fallback_evidence["build_fingerprint_sha256"] = "2" * 64
            evidence_paths[1].write_text(
                json.dumps(fallback_evidence), encoding="utf-8")
            fallback_entry = next(
                item for item in profile["evidence"]
                if item["path"].endswith("fallback.json"))
            old_digest = fallback_entry["sha256"]
            new_digest = hashlib.sha256(evidence_paths[1].read_bytes()).hexdigest()
            fallback_entry["sha256"] = new_digest
            fallback_entry["build_fingerprint_sha256"] = "2" * 64
            for admission in profile["admitted_models"]:
                if admission["evidence_sha256"] == old_digest:
                    admission["evidence_sha256"] = new_digest
            (config / "model_admission.json").write_text(
                json.dumps(generated), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "one build fingerprint"):
                validator.validate_model_admission(temporary)

    def test_generator_rejects_incomplete_tier_measurements(self):
        catalog = load("model_catalog.json")
        suite = load("model_benchmark_suite.json")
        tier = next(item for item in catalog["tiers"] if item["id"] == "edge_8gb")
        evidence = passing_admission_evidence(catalog, suite)
        evidence["results"] = [item for item in evidence["results"]
                               if item["model_id"] != tier["media_model"]]
        with self.assertRaisesRegex(admission_generator.AdmissionError,
                                    "text, media, TTS"):
            admission_generator.validate_evidence(catalog, suite, evidence)

    def test_generator_recomputes_gate_decisions(self):
        catalog = load("model_catalog.json")
        suite = load("model_benchmark_suite.json")
        evidence = passing_admission_evidence(catalog, suite)
        text = next(item for item in evidence["results"]
                    if item["model_id"] == "gemma4-e2b-mobile-text")
        text["metrics"]["p95_first_token_ms"] = 999999
        with self.assertRaisesRegex(admission_generator.AdmissionError,
                                    "decision disagrees with suite gates"):
            admission_generator.validate_evidence(catalog, suite, evidence)

    def test_generator_rejects_unobserved_pss(self):
        catalog = load("model_catalog.json")
        suite = load("model_benchmark_suite.json")
        evidence = passing_admission_evidence(catalog, suite)
        evidence["results"][0]["metrics"]["peak_rss_mb"] = 0
        with self.assertRaisesRegex(admission_generator.AdmissionError,
                                    "PSS observation"):
            admission_generator.validate_evidence(catalog, suite, evidence)


class ModelCatalogValidationTests(unittest.TestCase):
    def setUp(self):
        self.catalog = load("model_catalog.json")

    def test_memory_policy_cannot_gain_a_fixed_cap(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["memory_policy"]["fixed_model_limit_mb"] = 2000
        with self.assertRaisesRegex(validator.ValidationError, "pressure-adaptive"):
            validator.validate_catalog(catalog)

    def test_unknown_model_is_rejected(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["tiers"][0]["text_model"] = "not-a-model"
        with self.assertRaisesRegex(validator.ValidationError, "unknown model"):
            validator.validate_catalog(catalog)

    def test_default_backend_must_be_allowed(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["models"][0]["default_backend"] = "npu"
        with self.assertRaisesRegex(validator.ValidationError, "default backend"):
            validator.validate_catalog(catalog)

    def test_tts_bundle_member_cannot_escape_archive_root(self):
        catalog = copy.deepcopy(self.catalog)
        model = next(item for item in catalog["models"]
                     if item["id"] == "supertonic3-en-es-int8")
        model["reference_bundle"]["members"][0]["path"] = "../model.onnx"
        with self.assertRaisesRegex(validator.ValidationError, "unique and flat"):
            validator.validate_catalog(catalog)


class RuntimeCatalogTests(unittest.TestCase):
    def test_runtime_catalog_is_valid(self):
        validator.validate_runtime_catalog(ROOT)

    def test_pixel_9a_npu_cannot_be_enabled_without_evidence(self):
        value = load("runtime_catalog.json")
        tegu = next(profile for profile in value["device_profiles"]
                    if "tegu" in profile["devices"])
        tegu["runtime_backends"]["litert_lm"].insert(0, "npu")
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            shutil.copy(ROOT / "config" / "model_catalog.json",
                        temporary / "config" / "model_catalog.json")
            (temporary / "config" / "runtime_catalog.json").write_text(
                json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError, "NPU"):
                validator.validate_runtime_catalog(temporary)

    def test_pixel_10_family_uses_one_debug_only_cpu_gpu_profile(self):
        value = load("runtime_catalog.json")
        profile = next(item for item in value["device_profiles"]
                       if item["id"] == "pixel_10_tensor_g5_planned")

        self.assertEqual({"frankel", "blazer", "mustang", "rango"},
                         set(profile["devices"]))
        self.assertTrue(profile["debuggable_only"])
        self.assertEqual(["gpu", "cpu"],
                         profile["runtime_backends"]["litert_lm"])
        self.assertNotIn("npu", profile["runtime_backends"]["litert_lm"])

    def test_official_pixel_codename_cannot_fall_through_to_model_free(self):
        value = load("runtime_catalog.json")
        profile = next(item for item in value["device_profiles"]
                       if item["id"] == "pixel_10_tensor_g5_planned")
        profile["devices"].remove("frankel")
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            shutil.copy(ROOT / "config" / "model_catalog.json",
                        temporary / "config" / "model_catalog.json")
            (temporary / "config" / "runtime_catalog.json").write_text(
                json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "every officially identified Pixel"):
                validator.validate_runtime_catalog(temporary)


class IntegrationStructureTests(unittest.TestCase):
    def test_generated_blueprints_are_not_treated_as_source_modules(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            source = temporary / "services" / "demo"
            cached = temporary / ".cache" / "runtime-pack"
            generated = temporary / "generated" / "runtimepack"
            source.mkdir(parents=True)
            cached.mkdir(parents=True)
            generated.mkdir(parents=True)
            (source / "Android.bp").write_text(
                'android_app { name: "aios_source" }', encoding="utf-8")
            (cached / "Android.bp").write_text(
                'android_app_import { name: "aios_cached" }', encoding="utf-8")
            (generated / "Android.bp").write_text(
                'android_app_import { name: "aios_generated" }', encoding="utf-8")

            self.assertEqual(
                {"aios_source"}, validator.discover_blueprint_modules(temporary))

    def test_review_complete_patch_series_is_valid(self):
        validator.validate_patch_series(ROOT)

    def test_gsi_patch_keeps_exact_upstream_wrapper_scope(self):
        series = load("../patches/series.json")
        patch = next(item for item in series["patches"]
                     if item["project"] == "build/make")
        self.assertEqual([
            "target/board/BoardConfigGsiCommon.mk",
            "target/product/gsi/Android.bp",
        ], patch["paths"])
        text = (ROOT / "patches" / patch["file"]).read_text(encoding="utf-8")
        self.assertIn('name: "aios_gsi_system_image"', text)
        self.assertIn('"aios_product_policy"', text)
        self.assertIn('avb_private_key: ":avb_testkey_rsa2048"', text)
        self.assertIn('avb_algorithm: "SHA256_RSA2048"', text)
        self.assertNotIn("AiosPhone", text)
        self.assertNotIn("aios_model_", text)

    def test_declared_patch_footprint_cannot_drift(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            copy_patch_contract_fixture(temporary)
            series_path = temporary / "patches" / "series.json"
            series = json.loads(series_path.read_text(encoding="utf-8"))
            series["patches"][0]["paths"] = ["Android.bp"]
            series_path.write_text(json.dumps(series), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError, "footprint"):
                validator.validate_patch_series(temporary)

    def test_patch_review_metadata_is_mandatory(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            copy_patch_contract_fixture(temporary)
            series_path = temporary / "patches" / "series.json"
            series = json.loads(series_path.read_text(encoding="utf-8"))
            del series["patches"][0]["rebase_notes"]
            series_path.write_text(json.dumps(series), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError, "schema v2"):
                validator.validate_patch_series(temporary)

    def test_aosp_overlay_contract_is_valid(self):
        validator.validate_aosp_overlay(ROOT)

    def test_xml_files_are_well_formed(self):
        validator.validate_xml_files(ROOT)

    def test_privileged_permissions_are_narrow(self):
        validator.validate_security_surface(ROOT)

    def test_release_configuration_is_valid(self):
        validator.validate_release_configuration(ROOT)

    def test_native_emulator_provider_evidence_is_bilingual_and_nonphysical(self):
        common = {
            "schema_version": 1,
            "aios_revision": "a" * 40,
            "tracked_source_clean": True,
            "qemu": True,
            "api_level": 36,
            "abi": "x86_64",
            "signature_permission_rejected_shell": True,
            "invalid_request_error_verified": True,
            "product_model_path_confinement_verified": True,
            "provider_survived_rejected_model": True,
            "temporary_fixture_files_remaining": 0,
            "arm64_provider_evidence": False,
            "physical_gate_evidence": False,
        }
        asr = dict(common,
                   gate="integration.emulator_bilingual_asr_provider",
                   runtime_id="whisper_cpp",
                   real_native_asr_executed=True,
                   production_whisper_provider_bound_cross_process=True,
                   english_language_detected=True,
                   spanish_language_detected=True,
                   nonempty_final_transcripts_verified=True,
                   fixture_content_markers_verified=True,
                   call_rx_pipeline_verified=True,
                   emulator_real_time_gate=False)
        tts = dict(common,
                   gate="integration.emulator_bilingual_tts_provider",
                   runtime_id="sherpa_onnx_tts",
                   real_native_tts_executed=True,
                   production_tts_provider_bound_cross_process=True,
                   english_pcm_verified=True,
                   spanish_pcm_verified=True,
                   pcm_metadata_matches_stream=True)

        validator.validate_emulator_provider_evidence(asr, "asr")
        validator.validate_emulator_provider_evidence(tts, "tts")

        asr["physical_gate_evidence"] = True
        with self.assertRaisesRegex(validator.ValidationError, "overclaims"):
            validator.validate_emulator_provider_evidence(asr, "asr")

        tts["spanish_pcm_verified"] = False
        with self.assertRaisesRegex(validator.ValidationError, "bilingual PCM"):
            validator.validate_emulator_provider_evidence(tts, "tts")

    def test_emulator_integration_evidence_cannot_claim_physical_runtime(self):
        cases = {
            "context": ("integration.emulator_context_lifecycle", 1),
            "retention": ("integration.emulator_call_retention", 1),
            "model": ("integration.emulator_model_admission", 1),
            "media": ("integration.emulator_media_pipeline", 3),
            "messaging": ("integration.emulator_messaging", 1),
            "telecom": ("integration.emulator_telecom", 2),
        }
        for kind, (gate, schema) in cases.items():
            with self.subTest(kind=kind):
                record = {
                    "schema_version": schema,
                    "gate": gate,
                    "aios_revision": "b" * 40,
                    "tracked_source_clean": True,
                    "qemu": True,
                    "api_level": 36,
                    "physical_gate_evidence": True,
                }
                with self.assertRaisesRegex(validator.ValidationError, "overclaims"):
                    validator.validate_emulator_integration_evidence(record, kind)

    def test_passed_release_gate_requires_evidence(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            copy_patch_contract_fixture(temporary)
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "model_catalog.json",
                         "release_gates.json", "release_status.json"):
                (temporary / "config" / name).write_text(
                    (ROOT / "config" / name).read_text(encoding="utf-8"),
                    encoding="utf-8",
                )
            shutil.copytree(ROOT / "evidence", temporary / "evidence")
            value = json.loads((temporary / "config" / "release_status.json")
                               .read_text(encoding="utf-8"))
            value["statuses"]["boot.first_boot"]["status"] = "passed"
            (temporary / "config" / "release_status.json").write_text(
                json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError, "requires evidence"):
                validator.validate_release_configuration(temporary)

    def test_first_boot_evidence_must_bind_checked_in_build_record(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            copy_patch_contract_fixture(temporary)
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "model_catalog.json", "release_gates.json",
                         "release_status.json"):
                shutil.copy(ROOT / "config" / name,
                            temporary / "config" / name)
            shutil.copytree(ROOT / "evidence", temporary / "evidence")
            status = json.loads(
                (temporary / "config" / "release_status.json")
                .read_text(encoding="utf-8")
            )
            boot_reference = status["statuses"][
                "integration.android_latest_first_boot"
            ]["evidence"]
            self.assertEqual(len(boot_reference), 1)
            boot_path = temporary / boot_reference[0]
            boot = json.loads(boot_path.read_text(encoding="utf-8"))
            boot["build_evidence_sha256"] = "0" * 64
            boot_path.write_text(json.dumps(boot), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "not bound to its build"):
                validator.validate_release_configuration(temporary)

    def test_gsi_avb_evidence_must_bind_checked_in_build_record(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            copy_patch_contract_fixture(temporary)
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "model_catalog.json", "release_gates.json",
                         "release_status.json"):
                shutil.copy(ROOT / "config" / name,
                            temporary / "config" / name)
            shutil.copytree(ROOT / "evidence", temporary / "evidence")
            status = json.loads(
                (temporary / "config" / "release_status.json")
                .read_text(encoding="utf-8")
            )
            build_reference = status["statuses"][
                "integration.android_gsi_arm64_userdebug_succeeds"
            ]["evidence"]
            self.assertEqual(1, len(build_reference))
            avb_path = (temporary / build_reference[0]).parent / "avb-verification.json"
            avb = json.loads(avb_path.read_text(encoding="utf-8"))
            avb["build_evidence_sha256"] = "0" * 64
            avb_path.write_text(json.dumps(avb), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "AVB evidence is not bound"):
                validator.validate_release_configuration(temporary)

    def test_gsi_dsu_payload_must_bind_checked_in_system_image(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            copy_patch_contract_fixture(temporary)
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "model_catalog.json", "release_gates.json",
                         "release_status.json"):
                shutil.copy(ROOT / "config" / name,
                            temporary / "config" / name)
            shutil.copytree(ROOT / "evidence", temporary / "evidence")
            status = json.loads(
                (temporary / "config" / "release_status.json")
                .read_text(encoding="utf-8")
            )
            build_reference = status["statuses"][
                "integration.android_gsi_arm64_userdebug_succeeds"
            ]["evidence"]
            self.assertEqual(1, len(build_reference))
            payload_path = (temporary / build_reference[0]).parent / "dsu-payload.json"
            payload = json.loads(payload_path.read_text(encoding="utf-8"))
            payload["source_image"]["sha256"] = "0" * 64
            payload_path.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "DSU payload evidence is not bound"):
                validator.validate_release_configuration(temporary)

    def test_gsi_system_interface_must_bind_checked_in_system_image(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            copy_patch_contract_fixture(temporary)
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "model_catalog.json", "release_gates.json",
                         "release_status.json"):
                shutil.copy(ROOT / "config" / name,
                            temporary / "config" / name)
            shutil.copytree(ROOT / "evidence", temporary / "evidence")
            status = json.loads(
                (temporary / "config" / "release_status.json")
                .read_text(encoding="utf-8")
            )
            build_reference = status["statuses"][
                "integration.android_gsi_arm64_userdebug_succeeds"
            ]["evidence"]
            self.assertEqual(1, len(build_reference))
            interface_path = (
                temporary / build_reference[0]
            ).parent / "system-interface.json"
            interface = json.loads(interface_path.read_text(encoding="utf-8"))
            interface["system_image"]["sha256"] = "0" * 64
            interface_path.write_text(json.dumps(interface), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "system-interface evidence is not bound"):
                validator.validate_release_configuration(temporary)

    def test_avd_first_boot_cannot_pass_before_its_build(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            copy_patch_contract_fixture(temporary)
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "model_catalog.json", "release_gates.json",
                         "release_status.json"):
                shutil.copy(ROOT / "config" / name,
                            temporary / "config" / name)
            shutil.copytree(ROOT / "evidence", temporary / "evidence")
            status_path = temporary / "config" / "release_status.json"
            status = json.loads(status_path.read_text(encoding="utf-8"))
            status["statuses"]["integration.android_avd_userdebug_succeeds"] = {
                "status": "not_run",
                "evidence": [],
            }
            existing = next((temporary / "evidence" / "cuttlefish")
                            .rglob("cuttlefish-first-boot.json"))
            reference = existing.relative_to(temporary).as_posix()
            status["statuses"]["integration.android_avd_first_boot"] = {
                "status": "passed",
                "evidence": [reference],
            }
            status_path.write_text(json.dumps(status), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "cannot pass before its build"):
                validator.validate_release_configuration(temporary)

    def test_avd_first_boot_must_bind_its_exact_build(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            copy_patch_contract_fixture(temporary)
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "model_catalog.json", "release_gates.json",
                         "release_status.json"):
                shutil.copy(ROOT / "config" / name,
                            temporary / "config" / name)
            shutil.copytree(ROOT / "evidence", temporary / "evidence")
            fixture_dir = temporary / "evidence" / "avd-test"
            fixture_dir.mkdir()
            source_build = next((temporary / "evidence" / "cuttlefish")
                                .rglob("soong-build-evidence.json"))
            build = json.loads(source_build.read_text(encoding="utf-8"))
            build.update({
                "lane": "android_avd_integration",
                "kind": "virtual_emulator",
                "product": "aios_sdk_phone_x86_64",
                "target_device": "emu64x",
                "android_release": "17",
                "lane_eligible_for_physical_gates": False,
                "proves_physical_runtime_gate": False,
            })
            build_path = fixture_dir / "build.json"
            build_path.write_text(json.dumps(build), encoding="utf-8")
            source_boot = next((temporary / "evidence" / "cuttlefish")
                               .rglob("cuttlefish-first-boot.json"))
            boot = json.loads(source_boot.read_text(encoding="utf-8"))
            boot.update({
                "gate": "integration.android_avd_first_boot",
                "lane": "android_avd_integration",
                "kind": "virtual_emulator",
                "product": "aios_sdk_phone_x86_64",
                "target_device": "emu64x",
                "aios_revision": build["aios_revision"],
                "build_fingerprint": build["build_fingerprint"],
                "build_evidence_sha256": "0" * 64,
                "lane_eligible_for_physical_gates": False,
                "proves_physical_runtime_gate": False,
            })
            boot_path = fixture_dir / "boot.json"
            boot_path.write_text(json.dumps(boot), encoding="utf-8")
            status_path = temporary / "config" / "release_status.json"
            status = json.loads(status_path.read_text(encoding="utf-8"))
            status["statuses"]["integration.android_avd_userdebug_succeeds"] = {
                "status": "passed",
                "evidence": [build_path.relative_to(temporary).as_posix()],
            }
            status["statuses"]["integration.android_avd_first_boot"] = {
                "status": "passed",
                "evidence": [boot_path.relative_to(temporary).as_posix()],
            }
            status_path.write_text(json.dumps(status), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "not bound to its build"):
                validator.validate_release_configuration(temporary)

    def test_enabled_device_must_reference_declared_hardware_lane(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            copy_patch_contract_fixture(temporary)
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "model_catalog.json", "release_gates.json",
                         "release_status.json"):
                shutil.copy(ROOT / "config" / name,
                            temporary / "config" / name)
            shutil.copytree(ROOT / "evidence", temporary / "evidence")
            catalog_path = temporary / "config" / "model_catalog.json"
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
            pixel_9a = next(item for item in catalog["known_devices"]
                            if item["marketing_name"] == "Pixel 9a")
            pixel_9a["build_lane"] = "misspelled_tegu_lane"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError,
                                        "declared hardware lanes"):
                validator.validate_release_configuration(temporary)


class ModelPackTests(unittest.TestCase):
    def test_generates_and_reverifies_locked_multifile_bundle(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            archive = temporary / "voice.tar.bz2"
            payloads = {
                "model.onnx": b"test-only-model-component",
                "LICENSE": b"test-only-license",
            }
            with tarfile.open(archive, "w:bz2") as bundle:
                for name, payload in payloads.items():
                    info = tarfile.TarInfo(f"voice-root/{name}")
                    info.size = len(payload)
                    bundle.addfile(info, io.BytesIO(payload))

            catalog = load("model_catalog.json")
            model = next(item for item in catalog["models"]
                         if item["id"] == "supertonic3-en-es-int8")
            license_payload = b"test-only-openrail-license"
            license_file = temporary / "MODEL_LICENSE.OpenRAIL-M.txt"
            license_file.write_bytes(license_payload)
            model["packaged_license"] = {
                "filename": license_file.name,
                "size_bytes": len(license_payload),
                "sha256": hashlib.sha256(license_payload).hexdigest(),
                "soong_license_kinds": ["legacy_restricted"],
            }
            model["reference_bundle"] = {
                "url": "https://example.invalid/voice.tar.bz2",
                "source_format": "tar_bz2",
                "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "size_bytes": archive.stat().st_size,
                "archive_root": "voice-root",
                "members": [{
                    "path": name,
                    "size_bytes": len(payload),
                    "sha256": hashlib.sha256(payload).hexdigest(),
                } for name, payload in payloads.items()],
            }
            catalog_path = temporary / "catalog.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            acceptance = temporary / "acceptance.json"
            acceptance.write_text(json.dumps({
                "schema_version": 1,
                "accepted": [{
                    "model_id": model["id"],
                    "license_url": model["license_url"],
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test",
                }],
            }), encoding="utf-8")

            output = temporary / "pack"
            with self.assertRaisesRegex(packager.PackError,
                                        "packaged model license missing"):
                packager.generate(
                    catalog_path,
                    acceptance,
                    [packager.Source(model["id"], None, archive)],
                    temporary / "missing-license-pack",
                )
            manifest = packager.generate(
                catalog_path,
                acceptance,
                [packager.Source(model["id"], None, archive)],
                output,
                [packager.LicenseSource(model["id"], license_file)],
            )
            artifact = manifest["artifacts"][0]
            self.assertEqual("bundle", artifact["artifact_format"])
            self.assertEqual(2, len(artifact["bundle_members"]))
            self.assertEqual(hashlib.sha256(license_payload).hexdigest(),
                             artifact["packaged_license"]["sha256"])
            self.assertIn("aios/models/supertonic3-en-es-int8",
                          (output / "Android.bp").read_text(encoding="utf-8"))
            self.assertIn('license_kinds: ["legacy_restricted"]',
                          (output / "Android.bp").read_text(encoding="utf-8"))
            self.assertIn("aios_model_supertonic3_en_es_int8_model_license_terms",
                          (output / "Android.bp").read_text(encoding="utf-8"))
            packaged_license = output / "assets" / model["id"] / license_file.name
            packaged_license.write_bytes(b"tampered-license")
            with self.assertRaisesRegex(packager.PackError,
                                        "size mismatch|digest mismatch"):
                packager.verify_generated_pack(output)
            packaged_license.write_bytes(license_payload)
            tampered = output / "assets" / model["id"] / "model.onnx"
            tampered.write_bytes(b"tampered")
            with self.assertRaisesRegex(packager.PackError,
                                        "size mismatch|digest mismatch"):
                packager.verify_generated_pack(output)

    def test_source_can_select_an_explicit_allowed_backend(self):
        model_path = (Path(tempfile.gettempdir()) / "models" /
                      "gemma.litertlm").resolve()
        parsed = packager.parse_source(
            f"gemma4-e2b-mobile-text:cpu={model_path}")
        self.assertEqual("gemma4-e2b-mobile-text", parsed.model_id)
        self.assertEqual("cpu", parsed.backend)
        self.assertEqual(model_path, parsed.path)

    def test_whisper_ggml_bin_is_allowed_only_by_catalog_mapping(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            model = temporary / "ggml-base-q5_1.bin"
            model.write_bytes(b"test-only-ggml")
            acceptance = temporary / "acceptance.json"
            acceptance.write_text(json.dumps({
                "schema_version": 1,
                "accepted": [{
                    "model_id": "whisper-base-multilingual-quantized",
                    "license_url": "https://github.com/openai/whisper/blob/main/LICENSE",
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test"
                }]
            }), encoding="utf-8")
            catalog = load("model_catalog.json")
            whisper = next(item for item in catalog["models"]
                           if item["id"] == "whisper-base-multilingual-quantized")
            whisper.pop("reference_artifact")
            catalog_path = temporary / "catalog.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            manifest = packager.generate(
                catalog_path,
                acceptance,
                [packager.Source(
                    "whisper-base-multilingual-quantized", "cpu", model)],
                temporary / "pack",
            )
            self.assertEqual("cpu", manifest["artifacts"][0]["backend"])

    def test_reference_model_digest_is_enforced(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            model = temporary / "ggml-base-q5_1.bin"
            model.write_bytes(b"not-the-official-reference")
            acceptance = temporary / "acceptance.json"
            acceptance.write_text(json.dumps({
                "schema_version": 1,
                "accepted": [{
                    "model_id": "whisper-base-multilingual-quantized",
                    "license_url": "https://github.com/openai/whisper/blob/main/LICENSE",
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test"
                }]
            }), encoding="utf-8")
            catalog = load("model_catalog.json")
            whisper = next(item for item in catalog["models"]
                           if item["id"] == "whisper-base-multilingual-quantized")
            whisper["reference_artifact"]["size_bytes"] = model.stat().st_size
            catalog_path = temporary / "catalog.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            with self.assertRaisesRegex(packager.PackError, "reference artifact digest"):
                packager.generate(
                    catalog_path,
                    acceptance,
                    [packager.Source(
                        "whisper-base-multilingual-quantized", "cpu", model)],
                    temporary / "pack",
                )

    def test_generates_digest_manifest_and_deduplicates_shared_weights(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            model = temporary / "model.litertlm"
            model.write_bytes(b"test-only-model-bytes")
            fixture_digest = hashlib.sha256(model.read_bytes()).hexdigest()
            catalog = load("model_catalog.json")
            for item in catalog["models"]:
                if item["id"] in {
                        "gemma4-e2b-mobile-text",
                        "gemma4-e2b-mobile-multimodal"}:
                    item["reference_artifact"]["sha256"] = fixture_digest
                    item["reference_artifact"]["size_bytes"] = model.stat().st_size
            catalog_path = temporary / "catalog.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            acceptance = temporary / "acceptance.json"
            acceptance.write_text(json.dumps({
                "schema_version": 1,
                "accepted": [{
                    "model_id": "gemma4-e2b-mobile-text",
                    "license_url": "https://ai.google.dev/gemma/apache_2",
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test"
                }, {
                    "model_id": "gemma4-e2b-mobile-multimodal",
                    "license_url": "https://ai.google.dev/gemma/apache_2",
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test"
                }]
            }), encoding="utf-8")
            output = temporary / "pack"
            manifest = packager.generate(
                catalog_path,
                acceptance,
                [
                    packager.Source("gemma4-e2b-mobile-text", None, model),
                    packager.Source("gemma4-e2b-mobile-multimodal", None, model),
                ],
                output,
                [
                    packager.LicenseSource(
                        "gemma4-e2b-mobile-text", ROOT / "LICENSE"),
                    packager.LicenseSource(
                        "gemma4-e2b-mobile-multimodal", ROOT / "LICENSE"),
                ],
            )
            self.assertEqual(2, len(manifest["artifacts"]))
            artifact, media_artifact = manifest["artifacts"]
            self.assertEqual(hashlib.sha256(model.read_bytes()).hexdigest(), artifact["sha256"])
            self.assertEqual(model.stat().st_size, artifact["size_bytes"])
            self.assertEqual("gpu", artifact["backend"])
            self.assertEqual(artifact["relative_path"], media_artifact["relative_path"])
            self.assertEqual(
                ["gemma4-e2b-mobile-text.litertlm"],
                [item.name for item in (output / "assets").glob("*.litertlm")],
            )
            self.assertEqual(
                hashlib.sha256((ROOT / "LICENSE").read_bytes()).hexdigest(),
                artifact["packaged_license"]["sha256"],
            )
            self.assertEqual(
                (ROOT / "LICENSE").read_bytes(),
                (output / "assets" / "gemma4-e2b-mobile-text"
                 / "LICENSE.Apache-2.0.txt").read_bytes(),
            )
            self.assertIn("aios_model_gemma4_e2b_mobile_text",
                          (output / "Android.bp").read_text(encoding="utf-8"))
            self.assertIn('name: "aios_model_pack_anchor"',
                          (output / "Android.bp").read_text(encoding="utf-8"))
            self.assertIn('phony {',
                          (output / "Android.bp").read_text(encoding="utf-8"))
            self.assertIn('required: [',
                          (output / "Android.bp").read_text(encoding="utf-8"))
            self.assertIn("SPDX-license-identifier-Apache-2.0",
                          (output / "Android.bp").read_text(encoding="utf-8"))
            self.assertIn("aios_model_artifacts",
                          (output / "aios_model_pack.mk").read_text(encoding="utf-8"))

    def test_detects_artifact_tampering_after_generation(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            model = temporary / "model.litertlm"
            model.write_bytes(b"original")
            acceptance = temporary / "acceptance.json"
            acceptance.write_text(json.dumps({
                "schema_version": 1,
                "accepted": [{
                    "model_id": "gemma4-e2b-mobile-text",
                    "license_url": "https://ai.google.dev/gemma/apache_2",
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test"
                }]
            }), encoding="utf-8")
            output = temporary / "pack"
            catalog = load("model_catalog.json")
            catalog_model = next(
                item for item in catalog["models"]
                if item["id"] == "gemma4-e2b-mobile-text")
            catalog_model["reference_artifact"]["sha256"] = hashlib.sha256(
                model.read_bytes()).hexdigest()
            catalog_model["reference_artifact"]["size_bytes"] = model.stat().st_size
            catalog_path = temporary / "catalog.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            packager.generate(
                catalog_path,
                acceptance,
                [packager.Source("gemma4-e2b-mobile-text", None, model)],
                output,
                [packager.LicenseSource("gemma4-e2b-mobile-text", ROOT / "LICENSE")],
            )
            (output / "assets" / "gemma4-e2b-mobile-text.litertlm").write_bytes(b"tampered")
            with self.assertRaisesRegex(packager.PackError, "size mismatch|digest mismatch"):
                packager.verify_generated_pack(output)

    def test_rejects_license_url_mismatch(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            model = temporary / "model.litertlm"
            model.write_bytes(b"test")
            acceptance = temporary / "acceptance.json"
            acceptance.write_text(json.dumps({
                "schema_version": 1,
                "accepted": [{
                    "model_id": "gemma4-e2b-mobile-text",
                    "license_url": "https://example.invalid/wrong",
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test"
                }]
            }), encoding="utf-8")
            with self.assertRaisesRegex(packager.PackError, "license URL mismatch"):
                packager.generate(
                    ROOT / "config" / "model_catalog.json",
                    acceptance,
                    [packager.Source("gemma4-e2b-mobile-text", None, model)],
                    temporary / "pack",
                )


class RuntimePackTests(unittest.TestCase):
    @staticmethod
    def write_apk(path, catalog, omit_notice=None):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"binary-manifest-placeholder")
            archive.writestr("classes.dex", b"dex\n035\0test")
            archive.writestr("lib/arm64-v8a/liblitertlm_jni.so", b"elf-placeholder")
            for provider in catalog["providers"]:
                for notice in provider["required_apk_entries"]:
                    if notice["path"] != omit_notice:
                        archive.writestr(
                            notice["path"],
                            f"test notice: {notice['path']}".encode(),
                        )

    @staticmethod
    def write_test_catalog(path):
        catalog = load("runtime_catalog.json")
        for provider in catalog["providers"]:
            for notice in provider["required_apk_entries"]:
                payload = f"test notice: {notice['path']}".encode()
                notice["size_bytes"] = len(payload)
                notice["sha256"] = hashlib.sha256(payload).hexdigest()
        path.write_text(json.dumps(catalog), encoding="utf-8")
        return catalog

    @staticmethod
    def provenance():
        provider = load("runtime_catalog.json")["providers"][0]
        primary = provider["maven_artifact"]
        dependencies = [{
            "coordinate": primary["coordinate"],
            "sha256": primary["sha256"],
            "size_bytes": primary["size_bytes"],
        }]
        for index, coordinate in enumerate(provider["direct_dependencies"], 1):
            dependencies.append({
                "coordinate": coordinate,
                "sha256": hashlib.sha256(coordinate.encode()).hexdigest(),
                "size_bytes": index,
            })
        return {
            "schema_version": 1,
            "runtime": provider["runtime"],
            "provider_package": provider["package"],
            "provider_service": provider["service_class"],
            "implementation_version": provider["implementation_version"],
            "source_repository": provider["source_repository"],
            "source_revision": provider["source_revision"],
            "reproducible_build_command": "./gradlew --offline assembleRelease",
            "dependency_verification_sha256": "a" * 64,
            "resolved_dependencies": dependencies,
        }

    def test_generates_platform_resigned_runtime_pack(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            catalog_path = temporary / "runtime_catalog.json"
            catalog = self.write_test_catalog(catalog_path)
            apk = temporary / "provider.apk"
            self.write_apk(apk, catalog)
            provenance = temporary / "provenance.json"
            provenance.write_text(json.dumps(self.provenance()), encoding="utf-8")
            output = temporary / "pack"
            manifest = runtime_packager.generate(
                catalog_path,
                "litert_lm", apk, provenance, output)
            self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(),
                             manifest["provider_apk"]["sha256"])
            blueprint = (output / "Android.bp").read_text(encoding="utf-8")
            self.assertIn('certificate: "platform"', blueprint)
            self.assertIn("AiosRuntimeProvider_litert_lm", blueprint)

    def test_rejects_incomplete_dependency_closure(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            catalog_path = temporary / "runtime_catalog.json"
            catalog = self.write_test_catalog(catalog_path)
            apk = temporary / "provider.apk"
            self.write_apk(apk, catalog)
            value = self.provenance()
            value["resolved_dependencies"].pop()
            provenance = temporary / "provenance.json"
            provenance.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(runtime_packager.PackError,
                                        "misses direct dependencies"):
                runtime_packager.generate(
                    catalog_path,
                    "litert_lm", apk, provenance, temporary / "pack")

    def test_generates_native_source_runtime_pack(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            catalog_path = temporary / "runtime_catalog.json"
            catalog = self.write_test_catalog(catalog_path)
            apk = temporary / "provider.apk"
            self.write_apk(apk, catalog)
            provider = load("runtime_catalog.json")["providers"][1]
            provenance_value = {
                "schema_version": 1,
                "runtime": provider["runtime"],
                "provider_package": provider["package"],
                "provider_service": provider["service_class"],
                "implementation_version": provider["implementation_version"],
                "source_repository": provider["source_repository"],
                "source_revision": provider["source_revision"],
                "reproducible_build_command":
                    "gradle --offline :app:writeRuntimeProvenance",
                "dependency_verification_sha256": "b" * 64,
                "resolved_dependencies": [{
                    "coordinate": "org.jetbrains.kotlin:kotlin-stdlib:2.2.21",
                    "sha256": "c" * 64,
                    "size_bytes": 123,
                }],
            }
            provenance = temporary / "provenance.json"
            provenance.write_text(json.dumps(provenance_value), encoding="utf-8")
            manifest = runtime_packager.generate(
                catalog_path,
                "whisper_cpp", apk, provenance, temporary / "pack")
            self.assertEqual("whisper_cpp", manifest["runtime"])

    def test_generates_binary_release_runtime_pack(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            catalog_path = temporary / "runtime_catalog.json"
            catalog = self.write_test_catalog(catalog_path)
            provider = next(item for item in catalog["providers"]
                            if item["runtime"] == "sherpa_onnx_tts")
            primary = provider["binary_artifact"]
            provenance_value = {
                "schema_version": 1,
                "runtime": provider["runtime"],
                "provider_package": provider["package"],
                "provider_service": provider["service_class"],
                "implementation_version": provider["implementation_version"],
                "source_repository": provider["source_repository"],
                "source_revision": provider["source_revision"],
                "reproducible_build_command":
                    "gradle --offline :app:writeRuntimeProvenance",
                "dependency_verification_sha256": "d" * 64,
                "resolved_dependencies": [{
                    "coordinate": primary["coordinate"],
                    "sha256": primary["sha256"],
                    "size_bytes": primary["size_bytes"],
                }],
            }
            apk = temporary / "provider.apk"
            self.write_apk(apk, catalog)
            provenance = temporary / "provenance.json"
            provenance.write_text(json.dumps(provenance_value), encoding="utf-8")
            manifest = runtime_packager.generate(
                catalog_path, "sherpa_onnx_tts", apk, provenance,
                temporary / "pack")
            self.assertEqual("sherpa_onnx_tts", manifest["runtime"])
            self.assertEqual(primary["coordinate"],
                             manifest["resolved_dependencies"][0]["coordinate"])

    def test_detects_runtime_apk_tampering(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            catalog_path = temporary / "runtime_catalog.json"
            catalog = self.write_test_catalog(catalog_path)
            apk = temporary / "provider.apk"
            self.write_apk(apk, catalog)
            provenance = temporary / "provenance.json"
            provenance.write_text(json.dumps(self.provenance()), encoding="utf-8")
            output = temporary / "pack"
            runtime_packager.generate(
                catalog_path,
                "litert_lm", apk, provenance, output)
            generated = output / "assets" / "aios-runtime-litert_lm.apk"
            generated.write_bytes(b"tampered")
            with self.assertRaisesRegex(runtime_packager.PackError,
                                        "size mismatch|digest mismatch"):
                runtime_packager.verify_generated_pack(output)

    def test_rejects_apk_without_catalog_pinned_notice(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            catalog_path = temporary / "runtime_catalog.json"
            catalog = self.write_test_catalog(catalog_path)
            missing = catalog["providers"][0]["required_apk_entries"][0]["path"]
            apk = temporary / "provider.apk"
            self.write_apk(apk, catalog, omit_notice=missing)
            provenance = temporary / "provenance.json"
            provenance.write_text(json.dumps(self.provenance()), encoding="utf-8")
            with self.assertRaisesRegex(runtime_packager.PackError,
                                        "lacks required notice"):
                runtime_packager.generate(
                    catalog_path, "litert_lm", apk, provenance, temporary / "pack")


if __name__ == "__main__":
    unittest.main()
