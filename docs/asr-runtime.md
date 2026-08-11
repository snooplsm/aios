# ASR runtime

Call and deferred video transcription use a dedicated whisper.cpp provider, not Gemma. The
provider is pinned to whisper.cpp `v1.9.2` at
`306c88f4d1286aec1bf96e544632897886af5501` and NDK `28.2.13676358`. Release
builds contain only `arm64-v8a`; a debug-only `x86_64` variant exists for guarded
QEMU integration tests. JNI and the model execute in the provider's own process.

Pixel 9a starts with the multilingual Whisper base Q5_1 candidate. Pixel 10 and
12 GB devices prefer small Q5_1 only if their call benchmarks pass. The catalog
records the official reference artifact URLs and SHA-256 values; weights remain
licensed local inputs and are never committed.

The provider continuously drains each 16 kHz mono PCM pipe through 100 ms voice
activity frames. Live-call speech is decoded in windows no longer than two
seconds, while offline media retains four-second windows. Six hundred
milliseconds of trailing silence ends a conversational turn. Long-turn updates
carry the complete current turn as a replaceable revision; the silence endpoint
emits the same turn with `isFinal=true`. This contract gives the UI and advisory
spam heuristic fresh context every few words while preventing the receptionist
from answering a partial utterance, and it matches the Dialer's UDF transcript
reducer. A corrected partial replaces its provisional heuristic evidence; only
the final turn enters durable communication context or starts a model reply.
The cumulative partial/final state machine is a pure production component with
host-side regression tests. A silence endpoint preserves the last decoded audio
boundary, a decoded residual extends that boundary before finalization, and every
final emission resets state so text cannot leak into the next conversational turn.

Low-energy frames outside a turn are skipped. A single priority decode queue
services both directions: incoming/downlink work is always scheduled before
outgoing/uplink work. Each session is bounded to four queued decode items;
falling behind closes that AI stream rather than blocking authoritative local
capture or telephony. English and Spanish are auto-detected per window; other
detected languages fail the prototype's declared language policy.

The 100 ms VAD hot path scans little-endian PCM16 energy directly and allocates
no float array; float conversion happens only when a speech-bearing decode
window is admitted. A separate production endpoint state machine ignores
leading silence, resets its silence run when speech resumes, and ends a turn on
the sixth consecutive 100 ms silent frame. Host tests pin the PCM threshold
boundary and the exact 600 ms transition so continuous two-direction capture
cannot regain per-frame allocation churn or drift to a different endpoint
cadence unnoticed.

Broker cancellation also reaches an already-running native decode. Every
`whisper_full` call owns an opaque cancellation token, and the pinned
whisper.cpp `abort_callback` polls its atomic state before ggml computation.
The JVM fence serializes attach, cancellation, and destruction so a late Binder
cancel cannot touch a freed token. Therefore a preempted video window does not
hold the sole decode thread until its normal four-second-window completion; its
queued windows and reader are cancelled by the existing session teardown, and
the next priority item can be incoming call audio. Model loading itself remains
a measured cold-start boundary because whisper.cpp does not expose the same
abort hook for context construction.

An idle Whisper context stays warm across `UI_HIDDEN` callbacks to avoid a cold
start merely because the provider has no visible activity. Exact running-low,
running-critical, and cached-process pressure callbacks release it; an active
stream is never unloaded by trim handling.

Call Intelligence gates each session on the first successfully stored PCM frame
from both telephony directions, with a bounded startup timeout. This prevents an
AI-answered call from greeting a caller when the privileged downlink tap is
present in policy but unavailable for that particular call.

Call ASR is explicitly lifecycle-bound rather than assigned a short generation
deadline: the stream may last for the call's full Telecom lifetime. Pipe EOF,
explicit cancellation, callback-process death, preemption, broker shutdown, and
the call artifact's absolute retention cleanup all terminate it. The broker
accepts this mode only for `streaming_asr`; classifier, dialogue, TTS, vision,
and other finite requests always retain an elapsed-realtime terminal deadline.
The broker does not apply its finite 4,096-chunk ceiling to a lifecycle call.
Instead it permits 64 startup callbacks plus one callback per 100 ms of source
audio and rejects a source timestamp more than ten seconds ahead of elapsed
session time. Normal two-second call updates sit far below that safety ceiling.

## Real bilingual emulator proof

The weight-free checkout can bootstrap an exact ignored test set and exercise
the production Binder provider on an API-35+ x86-64 emulator:

```text
powershell -ExecutionPolicy Bypass -File scripts/bootstrap-emulator-asr-fixtures.ps1
powershell -ExecutionPolicy Bypass -File scripts/emulator-whisper-provider-smoke.ps1 -Serial emulator-5554
```

The bootstrap script reads the Pixel 9a catalog candidate, then verifies the
multilingual base Q5_1 model as
`422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`.
It also pins whisper.cpp's English JFK WAV as
`59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e`
and the CC0 Wikimedia Commons Spanish fixture as
`70ef4a2b564905d07f626af2adc2df958f9de584c120f3b9d2278158712d1d70`.
The weights and audio remain under ignored `.cache`; none are redistributed by
the repository.

The runner refuses physical devices and existing package installs. It verifies
the provider APK's x86-64 native library, signature-permission rejection of the
shell, request validation, canonical model-path confinement, and provider
survival after rejection. It then streams both fixtures as 100 ms `call_rx`
chunks through the real cross-process API and requires nonempty final transcript
content, fixture-specific content markers, and `en` and `es` decisions. The
expected markers distinguish transcription from an empty or unrelated model
response without calculating WER. Actual text exists only in the smoke process
for assertions: neither logs nor the ignored JSON evidence record it. All
model, audio, staging, and APK fixtures are removed afterward.

The debug provider admits private weights only when both the debug BuildConfig
flag and QEMU/generic hardware checks pass. Release keeps that flag false and
accepts models only from `/product/etc/aios/models`. In JNI, `language="auto"`
is passed with `detect_language=false`: whisper.cpp uses that combination to
select a language and transcribe, whereas `detect_language=true` is a detect-only
mode that returns before text generation.

The x86 emulator cannot meet the provider's live-call queue bound with this
Pixel candidate, so the test feeds each 100 ms source chunk every 250 ms. The
evidence therefore sets `emulator_real_time_gate=false`; it proves native
English/Spanish execution and the production streaming path, not real-time
factor, WER, arm64 behavior, Tensor performance, or the physical Pixel gate.

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

The two-second call cadence, four-second media cadence, and 600 ms endpoint are
provisional, not passed latency claims. Device gates must measure partial/final
latency, endpoint misses, real-time factor, WER for English and Spanish telephony
cohorts, thermal throttling, and queue lag. Tune windowing only from recorded
evidence; do not silently drop incoming audio to improve benchmark numbers.

## Deferred video mode

Media Intelligence may request the same `streaming_asr` capability only with the
`media_background` workload and `media` direction. The broker gives that lease
the lowest priority and cancels it when any call becomes active. Unlike a live
call stream, an offline video reader applies bounded backpressure instead of
failing merely because decoding can supply PCM faster than real time. Each
speech-bearing four-second window is final, which bounds subtitle size and gives
stable video-timeline offsets. The worker streams the complete primary audio
track; it does not sample audio alongside the twenty visual keyframes.
The video stream uses the same lifecycle-bound broker mode because clip length
is not an inference-turn timeout. Its PCM EOF, two-minute post-feed completion
timeout, one-second constraint checks, Binder death, and call preemption provide
the terminating conditions.
