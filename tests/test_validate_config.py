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


def passing_admission_evidence(catalog, suite):
    tier = next(item for item in catalog["tiers"] if item["id"] == "edge_8gb")
    roles = {
        tier["text_model"]: "text_model",
        tier["media_model"]: "media_model",
        tier["tts_model"]: "tts_model",
        **{item: "asr_candidate" for item in tier["asr_candidates"]},
    }
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
        "profile_id": "pixel_9a_tegu",
        "catalog_tier": "edge_8gb",
        "device_codename": "tegu",
        "total_ram_mb": 8192,
        "build_fingerprint_sha256": "1" * 64,
        "completed_at": "2026-08-09T12:00:00Z",
        "results": results,
    }


class ProductPolicyTests(unittest.TestCase):
    def test_repository_configuration_is_valid(self):
        validator.validate(ROOT)

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
        policy["media"]["index_only_mime_types"].remove("image/png")
        policy["media"]["writable_mime_types"].append("image/png")
        with self.assertRaisesRegex(validator.ValidationError, "simple-JPEG"):
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

    def test_fixed_memory_budget_is_rejected(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["tiers"][0]["max_foreground_model_mb"] = 100
        with self.assertRaisesRegex(validator.ValidationError, "fixed model-memory"):
            validator.validate_catalog(catalog)


class ModelAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.catalog = load("model_catalog.json")

    def test_pending_pixel_9a_profile_is_valid(self):
        validator.validate_model_admission(ROOT)

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


class IntegrationStructureTests(unittest.TestCase):
    def test_empty_patch_series_is_valid(self):
        validator.validate_patch_series(ROOT)

    def test_aosp_overlay_contract_is_valid(self):
        validator.validate_aosp_overlay(ROOT)

    def test_xml_files_are_well_formed(self):
        validator.validate_xml_files(ROOT)

    def test_privileged_permissions_are_narrow(self):
        validator.validate_security_surface(ROOT)

    def test_release_configuration_is_valid(self):
        validator.validate_release_configuration(ROOT)

    def test_passed_release_gate_requires_evidence(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            (temporary / "config").mkdir()
            shutil.copytree(ROOT / "patches", temporary / "patches")
            for name in ("aosp_tracking.json", "aosp_lanes.json",
                         "release_gates.json", "release_status.json"):
                (temporary / "config" / name).write_text(
                    (ROOT / "config" / name).read_text(encoding="utf-8"),
                    encoding="utf-8",
                )
            value = json.loads((temporary / "config" / "release_status.json")
                               .read_text(encoding="utf-8"))
            value["statuses"]["boot.first_boot"]["status"] = "passed"
            (temporary / "config" / "release_status.json").write_text(
                json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(validator.ValidationError, "requires evidence"):
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
        parsed = packager.parse_source(
            "gemma4-e2b-mobile-text:cpu=C:\\models\\gemma.litertlm")
        self.assertEqual("gemma4-e2b-mobile-text", parsed.model_id)
        self.assertEqual("cpu", parsed.backend)

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
            with self.assertRaisesRegex(packager.PackError, "reference artifact digest"):
                packager.generate(
                    ROOT / "config" / "model_catalog.json",
                    acceptance,
                    [packager.Source(
                        "whisper-base-multilingual-quantized", "cpu", model)],
                    temporary / "pack",
                )

    def test_generates_digest_manifest_and_soong_files(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            model = temporary / "model.litertlm"
            model.write_bytes(b"test-only-model-bytes")
            acceptance = temporary / "acceptance.json"
            acceptance.write_text(json.dumps({
                "schema_version": 1,
                "accepted": [{
                    "model_id": "gemma4-e2b-mobile-text",
                    "license_url": "https://ai.google.dev/gemma/terms",
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test"
                }]
            }), encoding="utf-8")
            output = temporary / "pack"
            manifest = packager.generate(
                ROOT / "config" / "model_catalog.json",
                acceptance,
                [packager.Source("gemma4-e2b-mobile-text", None, model)],
                output,
            )
            artifact = manifest["artifacts"][0]
            self.assertEqual(hashlib.sha256(model.read_bytes()).hexdigest(), artifact["sha256"])
            self.assertEqual(model.stat().st_size, artifact["size_bytes"])
            self.assertEqual("gpu", artifact["backend"])
            self.assertIn("aios_model_gemma4_e2b_mobile_text",
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
                    "license_url": "https://ai.google.dev/gemma/terms",
                    "accepted_at": "2026-08-09T00:00:00Z",
                    "accepted_by": "unit-test"
                }]
            }), encoding="utf-8")
            output = temporary / "pack"
            packager.generate(
                ROOT / "config" / "model_catalog.json",
                acceptance,
                [packager.Source("gemma4-e2b-mobile-text", None, model)],
                output,
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
