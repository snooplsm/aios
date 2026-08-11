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
measured-RAM tier, applies device admission evidence, and enforces
package/capability/workload quotas. Runtime providers are discovered only from
the exact system-package/version/backend allowlist; verified weights alone never
make a capability active.

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
