import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "evaluate_model_benchmark", ROOT / "tools" / "evaluate_model_benchmark.py"
)
evaluator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(evaluator)


def load(name):
    return json.loads((ROOT / "config" / name).read_text(encoding="utf-8"))


def passing_metrics(role, suite):
    metrics = {"peak_rss_mb": 4096, "thermal_status_max": 3}
    for gate in suite["gate_profiles"][role]:
        threshold = gate["threshold"]
        if gate["operator"] == "gte":
            metrics[gate["metric"]] = threshold
        elif gate["operator"] == "lte":
            metrics[gate["metric"]] = threshold
        else:
            metrics[gate["metric"]] = threshold
    return metrics


def raw_benchmark():
    catalog = load("model_catalog.json")
    suite = load("model_benchmark_suite.json")
    tier = next(item for item in catalog["tiers"] if item["id"] == "edge_8gb")
    roles = {
        tier["text_model"]: "text_model",
        tier["media_model"]: "media_model",
        tier["tts_model"]: "tts_model",
        **{item: "asr_candidate" for item in tier["asr_candidates"]},
    }
    models = {item["id"]: item for item in catalog["models"]}
    return {
        "schema_version": 1,
        "suite_version": suite["suite_version"],
        "profile_id": "pixel_9a_tegu",
        "catalog_tier": tier["id"],
        "device_codename": "tegu",
        "total_ram_mb": 8192,
        "build_fingerprint_sha256": "a" * 64,
        "completed_at": "2026-08-09T18:00:00Z",
        "results": [{
            "model_id": model_id,
            "runtime": models[model_id]["runtime"],
            "backend": models[model_id]["default_backend"],
            "artifact_sha256": hashlib.sha256(model_id.encode()).hexdigest(),
            "metrics": passing_metrics(role, suite),
        } for model_id, role in roles.items()],
    }


class ModelBenchmarkEvaluationTests(unittest.TestCase):
    def evaluate(self, raw):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            raw_path = temporary / "raw.json"
            output_path = temporary / "evidence.json"
            raw_path.write_text(json.dumps(raw), encoding="utf-8")
            return evaluator.evaluate(
                ROOT / "config" / "model_catalog.json",
                ROOT / "config" / "model_benchmark_suite.json",
                raw_path,
                output_path,
            )

    def test_passing_measurements_produce_suite_bound_evidence(self):
        evidence = self.evaluate(raw_benchmark())
        self.assertEqual(2, evidence["schema_version"])
        self.assertEqual(
            evaluator.canonical_sha256(load("model_benchmark_suite.json")),
            evidence["suite_sha256"],
        )
        self.assertTrue(all(result["decision"] == "passed"
                            for result in evidence["results"]))

    def test_latency_regression_fails_without_rejecting_evidence(self):
        raw = raw_benchmark()
        text = next(item for item in raw["results"]
                    if item["model_id"] == "gemma4-e2b-mobile-text")
        text["metrics"]["p95_first_token_ms"] = 2001
        evidence = self.evaluate(raw)
        result = next(item for item in evidence["results"]
                      if item["model_id"] == text["model_id"])
        self.assertEqual("failed", result["decision"])
        self.assertEqual(["first_token_latency"], result["failed_gates"])

    def test_missing_thermal_observation_is_rejected(self):
        raw = raw_benchmark()
        del raw["results"][0]["metrics"]["thermal_status_max"]
        with self.assertRaisesRegex(evaluator.BenchmarkError,
                                    "missing observations"):
            self.evaluate(raw)

    def test_incomplete_tier_cannot_be_evaluated(self):
        raw = raw_benchmark()
        raw["results"].pop()
        with self.assertRaisesRegex(evaluator.BenchmarkError,
                                    "every model"):
            self.evaluate(raw)

    def test_boolean_cannot_satisfy_numeric_gate(self):
        raw = raw_benchmark()
        raw["results"][0]["metrics"]["measured_runs"] = True
        evidence = self.evaluate(raw)
        first = next(item for item in evidence["results"]
                     if item["model_id"] == raw["results"][0]["model_id"])
        self.assertIn("measured_runs", first["failed_gates"])


if __name__ == "__main__":
    unittest.main()
