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

The observer is the live trigger; the system Photo Picker is not an inference
trigger. At startup and after settled capture groups, a bounded recovery scan
uses each external volume's MediaStore version and `(GENERATION_ADDED, _ID)`
cursor to durably enqueue additions missed during process death or reboot. A
new install or MediaProvider database rebuild baselines the current generation
instead of importing the owner's historical library. A pending camera write is
never crossed, and a queue insertion must be durable before the cursor advances.

The SQLite queue survives process death and reboot. The worker atomically claims
one item, verifies its `MediaStore` generation around content hashing and again
after inference, submits a read-only descriptor to Model Broker, validates a
strict result schema, and commits the encrypted result/index state in one SQLite
transaction. Broker absence is retryable; changed/deleted media becomes stale;
invalid model output fails closed.

The same result transaction stores privacy-minimized timing for the latest 100
photos and 100 videos. A debug-only dump exposes fixed p50/p95 counters for
queueing, preparation, Broker inference, and end-to-end indexing; it never emits
media identifiers or inferred content. See `docs/media-performance.md` for the
physical Pixel evidence procedure.

Metadata has two layers:

- The credential-encrypted AIOS index is authoritative and may contain rich
  results.
- `XmpProjection` creates a deliberately small portable packet. A format-specific
  writer may commit it only after it validates that advanced container features
  and decoded content remain intact.

JPEG, PNG, and WebP are candidates for the first safe writer. HEIC/HEIF, AVIF,
DNG, Motion Photo, Ultra HDR, and video remain index-only until dedicated
round-trip tests pass.
