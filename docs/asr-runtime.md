# ASR runtime

Call and deferred video transcription use a dedicated whisper.cpp provider, not Gemma. The
provider is pinned to whisper.cpp `v1.9.2` at
`306c88f4d1286aec1bf96e544632897886af5501` and builds only `arm64-v8a` with
NDK `28.2.13676358`. JNI and the model execute in the provider's own process.

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
