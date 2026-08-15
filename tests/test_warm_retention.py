import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "evaluate_warm_retention", ROOT / "tools" / "evaluate_warm_retention.py"
)
evaluator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(evaluator)


def source_record(warm: bool) -> dict:
    health = {
        "low_memory_event_count": 0,
        "low_memory_kill_count": 0,
        "aios_low_memory_kill_count": 0,
        "background_low_memory_kill_count": 0,
        "oom_event_count": 0,
        "fatal_event_count": 0,
        "max_runtime_thermal_status": -1,
    }
    diagnostics = {
        "schema_version": 2,
        "tts_engine_events": [] if warm else [{"prepare_elapsed_ms": 100}],
        "litert_lm_engine_events": [] if warm else [{"initialize_elapsed_ms": 100}],
        "whisper_model_events": [] if warm else [{"initialize_elapsed_ms": 100}],
        "residency_events": [
            {"runtime": "sherpa_onnx_tts", "action": "cache_hit"},
            {"runtime": "litert_lm", "action": "cache_hit"},
            {"runtime": "litert_lm", "action": "cache_hit"},
            {"runtime": "whisper_cpp", "action": "cache_hit"},
        ] if warm else [],
        "artifact_verification_events": [
            {"runtime": "litert_lm", "action": "digest_cache_hit"},
            {"runtime": "litert_lm", "action": "digest_cache_hit"},
            {"runtime": "whisper_cpp", "action": "digest_cache_hit"},
        ] if warm else [
            {"runtime": "litert_lm", "action": "digest_verified"},
            {"runtime": "whisper_cpp", "action": "digest_verified"},
        ],
        "system_health": health,
    }
    latencies = {
        "speech_synthesis": 1000,
        "streaming_asr": 1000,
        "text_generation": 500,
        "image_understanding": 3000,
    }
    return {
        "schema_version": 1,
        "evidence_kind": "pixel_aios_single_model_diagnostic",
        "suite_version": 4,
        "device_codename": "tegu",
        "serial_sha256": "a" * 64,
        "total_ram_mb": 7322,
        "build_fingerprint_sha256": "b" * 64,
        "completed_at": "2026-08-15T20:00:00Z",
        "host_resource_sampling": {
            "memory_before": {"MemAvailable_mb": 900.0},
            "memory_after": {"MemAvailable_mb": 800.0},
        },
        "runtime_phase_diagnostics": diagnostics,
        "results": [
            {
                "capability": capability,
                "metrics": {
                    "succeeded": True,
                    "error": "",
                    "first_output_ms": latency,
                    "thermal_status_max": 1,
                    "details": {"transcript": "must not enter evidence"},
                },
            }
            for capability, latency in latencies.items()
        ],
    }


class WarmRetentionEvaluationTests(unittest.TestCase):
    def evaluate(self, cold: dict, warm: dict) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            cold_path = temporary / "cold.json"
            warm_path = temporary / "warm.json"
            output_path = temporary / "evidence.json"
            cold_path.write_text(json.dumps(cold), encoding="utf-8")
            warm_path.write_text(json.dumps(warm), encoding="utf-8")
            return evaluator.evaluate(
                ROOT / "config" / "warm_retention_benchmark.json",
                cold_path,
                warm_path,
                output_path,
            )

    def test_passing_pair_is_build_and_device_bound_and_sanitized(self):
        evidence = self.evaluate(source_record(False), source_record(True))
        self.assertEqual("passed", evidence["decision"])
        self.assertEqual("a" * 64, evidence["serial_sha256"])
        self.assertNotIn("transcript", json.dumps(evidence))

    def test_mismatched_device_is_rejected(self):
        warm = source_record(True)
        warm["serial_sha256"] = "c" * 64
        with self.assertRaisesRegex(evaluator.WarmRetentionError, "same build/device"):
            self.evaluate(source_record(False), warm)

    def test_missing_cache_hit_fails_evidence(self):
        warm = source_record(True)
        warm["runtime_phase_diagnostics"]["residency_events"] = []
        evidence = self.evaluate(source_record(False), warm)
        self.assertEqual("failed", evidence["decision"])
        self.assertIn("warm_cache_hit:litert_lm", evidence["failed_gates"])

    def test_missing_digest_cache_hit_fails_evidence(self):
        warm = source_record(True)
        warm["runtime_phase_diagnostics"]["artifact_verification_events"] = []
        evidence = self.evaluate(source_record(False), warm)
        self.assertIn("warm_digest_cache_hit:whisper_cpp", evidence["failed_gates"])

    def test_release_and_background_kill_fail_evidence(self):
        warm = source_record(True)
        diagnostics = warm["runtime_phase_diagnostics"]
        diagnostics["residency_events"].append({
            "runtime": "litert_lm", "action": "released", "reason": "memory_trim_10"
        })
        diagnostics["system_health"]["background_low_memory_kill_count"] = 1
        evidence = self.evaluate(source_record(False), warm)
        self.assertIn("release_or_eviction", evidence["failed_gates"])
        self.assertIn("background_low_memory_kills", evidence["failed_gates"])

    def test_latency_regression_fails_without_rejecting_source(self):
        warm = source_record(True)
        next(result for result in warm["results"]
             if result["capability"] == "speech_synthesis")["metrics"][
                 "first_output_ms"] = 1501
        evidence = self.evaluate(source_record(False), warm)
        self.assertIn("first_output:speech_synthesis", evidence["failed_gates"])

    def test_malformed_diagnostics_are_rejected(self):
        warm = source_record(True)
        del warm["runtime_phase_diagnostics"]["system_health"]["oom_event_count"]
        with self.assertRaisesRegex(evaluator.WarmRetentionError, "system-health"):
            self.evaluate(source_record(False), warm)


if __name__ == "__main__":
    unittest.main()
