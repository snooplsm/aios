# Model Broker API

This directory starts with the Binder contract so the dialer, call pipeline, and
media pipeline can be developed against one stable boundary.

The API intentionally transfers inputs through pipes/file descriptors and returns
typed chunks. It never returns a path to weights. The eventual service checks the
calling UID and signature for every session, assigns priority from caller policy
rather than trusting the requested priority, and closes all descriptors on
cancellation or binder death.

Capabilities are semantic (`streaming_asr`, `text_generation`,
`text_embedding`, `image_understanding`, `speech_synthesis`). Model IDs are
diagnostic data, not a contract that callers may pin indefinitely.

Embedding transport is typed and deliberately separate from generation JSON.
An embedding request fixes `embeddingTask` to `query` or `document`, carries no
generation-token budget, and accepts exactly one complete, non-empty text input
of at most 4,096 UTF-16 code units. The isolated provider owns the exact task
prefix and tokenizer pinned by the model bundle; the client submits unprefixed
text. The provider cannot stream generation chunks. Completion must contain
exactly 256 finite, L2-normalized floats and no JSON; every other
capability must contain JSON and no vector. The broker rejects mixed payloads,
wrong dimensions, non-finite values, and non-normalized output before invoking
the client callback. Capability discovery exposes the exact selected bundle
SHA-256 so a retrieval client can reject or reindex vectors from another space.

Current startup code verifies the locally generated artifact manifest, confines
canonical paths to `/product/etc/aios/models`, recomputes exact size/SHA-256,
cross-checks runtime/capabilities/languages against the catalog, selects the
highest measured-RAM tier followed by its ordered, lower-memory fallback chain,
applies device admission evidence, and enforces
package/capability/workload quotas. Runtime providers are discovered only from
the exact system-package/version/backend allowlist; verified weights alone never
make a capability active.

Active-session capacity is loaded fail closed from the AVB-protected product
policy rather than duplicated in service code. RX and TX transcription share a
two-stream ASR pool, while reasoning and speech synthesis share one call-agent
lane; all work also consumes one of three global slots. Per-client quotas count
both active and queued sessions. A class-saturated queued request cannot block
promotion of another class that still has capacity. Interactive context-query
embedding shares the call-agent lane, while asynchronous context indexing shares
the fully preemptible media-background class and cannot run during a call.

Selection remains primary-first within that chain and de-duplicates shared
models. A fallback is usable only when its exact artifact digest was separately
admitted for the device profile; merely declaring `fallback_tier` never admits
weights in a release build.

Capability discovery and session selection evaluate runtime/backend readiness
in that same order. A ready primary artifact wins; otherwise the first ready
admitted fallback is exposed and opened. If no matching runtime is ready,
discovery keeps the preferred model visible with `available=false`, and session
creation returns `ERROR_NOT_READY` instead of escaping to an unverified model path.

Release admission is also build-specific. The broker SHA-256 hashes the running
`Build.FINGERPRINT` and compares it with the fingerprint digest attached to the
admitted model's benchmark evidence. A mismatch exposes no release model from
that evidence. Debuggable pending profiles remain available only for collecting
new measurements on a known device.

All broker policy JSON is read through one Android-compatible 2 MiB bounded
reader. Missing, empty, oversized, truncated, or concurrently growing files fail
closed before parsing. `preview:modelservicecheck` stages the entire broker and
both AIDL surfaces into a public-SDK build, with only `ro.debuggable` access
replaced by a compile-only adapter that always returns false. The production
Soong app retains the platform adapter. This lane catches Android API drift but
does not replace the locked Soong build, provider smoke tests, or Pixel model
admission benchmarks.

The debug APK also has an emulator-only admission fixture. It uses disposable
plain-text bytes—not model weights—to execute the production artifact verifier,
catalog, device/fingerprint admission, and client-policy code on Android. It then
binds the production service on a stock emulator and requires the missing
`/product/etc/aios` policy to deny capability access. This distinguishes a
working fail-closed Binder boundary from real inference, which still requires a
flashed product policy, a verified model pack, and an eligible runtime provider.

Finite sessions are registered in an elapsed-realtime deadline queue. The broker
expires queued or running work with `ERROR_DEADLINE_EXCEEDED` (6), closes the
runtime lease and pending descriptors, and prevents a racing provider completion
from delivering a second terminal callback. Long-lived `streaming_asr` may use
one explicit lifecycle-bound sentinel; finite capabilities fail closed if they
request an expired or greater-than-five-minute horizon. The pure mode and
deadline-order policies are covered by both Soong and Gradle host tests. Android
scheduling and real provider cancellation still require device integration
evidence.

Runtime output is also workload-aware. Finite and offline-media sessions have
aggregate chunk-count/text bounds. Lifecycle call ASR has no arbitrary total
transcript ceiling; its callbacks are instead bounded against the captured-audio
timeline, while every individual chunk remains size-, sequence-, language-,
confidence-, and timestamp-validated.

`text_embedding` is a reserved, fail-closed API contract at present. It is not
listed for any authorized client and no catalog model advertises it until the
gated EmbeddingGemma model, tokenizer, preprocessing prefix contract, LiteRT
runtime, notices, and exact bundle manifest have been accepted and reproducibly
packaged. Adding the typed fields does not make semantic retrieval available.
