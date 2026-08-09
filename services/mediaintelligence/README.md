# Media Intelligence

Media Intelligence observes settled `MediaStore` additions from any camera app.
It coalesces rapid captures for five seconds: an isolated image is immediate,
while a burst or any video is deferred. Deferred work is scheduled only while
charging. Before work starts and once per second during model inference, the
worker rechecks battery >=80%, active-call state, and thermal state. Unplugging,
falling below the threshold, a new call, or severe thermal pressure cancels the
background Broker session and returns the durable queue item to retryable state.
Immediate isolated photos do not require external power, but calls and severe
thermal pressure preempt them too.

The SQLite queue survives process death and reboot. The worker atomically claims
one item, verifies its `MediaStore` generation around content hashing and again
after inference, submits a read-only descriptor to Model Broker, validates a
strict result schema, and commits the encrypted result/index state in one SQLite
transaction. Broker absence is retryable; changed/deleted media becomes stale;
invalid model output fails closed.

Metadata has two layers:

- The credential-encrypted AIOS index is authoritative and may contain rich
  results.
- `XmpProjection` creates a deliberately small portable packet. A format-specific
  writer may commit it only after it validates that advanced container features
  and decoded content remain intact.

JPEG, PNG, and WebP are candidates for the first safe writer. HEIC/HEIF, AVIF,
DNG, Motion Photo, Ultra HDR, and video remain index-only until dedicated
round-trip tests pass.
