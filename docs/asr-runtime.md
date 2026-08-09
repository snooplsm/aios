# Call ASR runtime

Call transcription uses a dedicated whisper.cpp provider, not Gemma. The
provider is pinned to whisper.cpp `v1.9.2` at
`306c88f4d1286aec1bf96e544632897886af5501` and builds only `arm64-v8a` with
NDK `28.2.13676358`. JNI and the model execute in the provider's own process.

Pixel 9a starts with the multilingual Whisper base Q5_1 candidate. Pixel 10 and
12 GB devices prefer small Q5_1 only if their call benchmarks pass. The catalog
records the official reference artifact URLs and SHA-256 values; weights remain
licensed local inputs and are never committed.

The provider continuously drains each 16 kHz mono PCM pipe into four-second
windows. Low-energy windows are skipped. A single priority decode queue services
both directions: incoming/downlink work is always scheduled before
outgoing/uplink work. Each session is bounded to four queued windows; falling
behind closes that AI stream rather than blocking authoritative local capture or
telephony. English and Spanish are auto-detected per window; other detected
languages fail the prototype's declared language policy.

Build on Linux:

```text
cd vendor/aios/runtime/whisperprovider
./bootstrap_source.sh
ALLOW_DEPENDENCY_LOCK_UPDATE=1 ./bootstrap_dependency_locks.sh
# Review and commit dependency locks, then:
./build_provider.sh
python3 ../../tools/generate_runtime_pack.py \
  --runtime whisper_cpp \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --provenance build/runtime-provenance.json
```

For a combined product, include both generated runtime make fragments under
distinct output directories. The generic packager intentionally refuses a
non-empty output directory.

Four seconds is a provisional window, not a passed latency claim. Device gates
must measure partial latency, real-time factor, WER for English and Spanish
telephony cohorts, thermal throttling, and queue lag. Tune windowing only from
recorded evidence; do not silently drop incoming audio to improve benchmark
numbers.
