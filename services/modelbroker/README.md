# Model Broker API

This directory starts with the Binder contract so the dialer, call pipeline, and
media pipeline can be developed against one stable boundary.

The API intentionally transfers inputs through pipes/file descriptors and returns
typed chunks. It never returns a path to weights. The eventual service checks the
calling UID and signature for every session, assigns priority from caller policy
rather than trusting the requested priority, and closes all descriptors on
cancellation or binder death.

Capabilities are semantic (`streaming_asr`, `text_generation`,
`image_understanding`, `speech_synthesis`). Model IDs are diagnostic data, not a
contract that callers may pin indefinitely.

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
promotion of another class that still has capacity, and background media uses
only idle capacity outside a live call.

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
