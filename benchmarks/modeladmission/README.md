# Model admission device benchmark

`AiosModelBenchmark` and `AiosModelBenchmarkTests` are installed only through
`PRODUCT_PACKAGES_DEBUG`, so neither APK belongs on an AIOS `user` image. The
target APK is platform-signed, privileged, and test-only because the runner must
use the signature Model Broker API, refuse execution during a live call, and
sample the PSS of isolated runtime processes.

The benchmark client cannot control the Broker's call-active gate. Only Call
Intelligence owns that authority, so a benchmark cannot make background or
media work runnable during a call.

The instrumentation runs five English and five Spanish iterations for each
selected capability:

- a known-answer text prompt through LiteRT-LM;
- a deterministic red JPEG through both the image and explicit sampled-video
  storyboard capabilities of multimodal LiteRT-LM;
- a fixed receptionist phrase through Supertonic TTS; and
- the resampled TTS output through the selected Whisper ASR model, once paced
  at real time to measure source-relative partial/final lag and endpoint delay,
  and once fast-fed to measure decode real-time factor independently.

It publishes one base64-encoded raw JSON document in the instrumentation result
bundle under `aios_measurements_base64`. Raw output has model/artifact identity
and numeric measurements but contains no gate list or decision. PSS and thermal
status are sampled throughout each invocation, rather than only after it. The
host-side evaluator owns gate fields and decisions.

The paced ASR pass submits 100 ms PCM frames at their source time, uses the same
lifecycle-bound deadline mode and `language=und` auto-detection contract as a
call, and requires at least one non-final revision whose source span is no more
than 2.1 seconds. English and Spanish runs must each report the correct final
detected language, and every paced run must emit a final endpoint. Partial and
final latency
are processing lag after the chunk's source audio became available—not listening
time. Endpoint delay is measured from the end of speech and therefore includes
the 600 ms silence endpoint plus decode lag. The fast pass keeps throughput
measurement separate so pacing cannot manufacture a real-time factor near one.

Media output reports `p95_image_latency_ms` separately from
`p95_video_storyboard_inference_ms`; the former is the current photo ETA metric.
It also reports `first_image_latency_ms` as the cold-start observation and
`p50_warm_image_latency_ms` after excluding that first invocation. These are
Broker/model times. The physical media timing path additionally measures
MediaStore observation, queueing, hashing, twenty-keyframe preparation, and
indexing for end-to-end ETA.

Normally run the complete capture path from the repository root:

```text
powershell -File scripts/capture-model-benchmark.ps1 `
  -Output evidence\model-admission\pixel-9a-build-id.json
```

The deterministic TTS-to-ASR loop is useful for repeatable integration and
performance comparisons. It is not proof of accuracy for real human callers,
background noise, accents, codecs, or carrier audio. The separate English and
Spanish physical-call release gates require consented human/noisy cohorts.

Do not add recordings, generated audio, prompts containing customer data,
device serials, raw build fingerprints, or model weights to this directory.
