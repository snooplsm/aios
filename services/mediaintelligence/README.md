# Media Intelligence

Media Intelligence observes settled `MediaStore` additions from any camera app.
It coalesces rapid captures for five seconds: an isolated image is immediate,
while a burst or any video is deferred. Deferred work is scheduled only while
charging. Before work starts and once per second during model inference, the
worker rechecks battery >=80%, active-call state, and thermal state. Unplugging,
falling below the threshold, a new call, or severe thermal pressure cancels the
background Broker session and returns the durable queue item to retryable state.

Every video gets two source-read-only passes: twenty chronological keyframes in
one private 5×4 storyboard for vision, and the complete primary audio track as
streaming PCM16 mono through the bilingual Whisper provider. Final timestamped
subtitle segments are stored in an app-private FTS index and cascade-delete with
the source. Neither subtitle text nor raw audio is written during automatic
indexing.
Immediate isolated photos do not require external power, but calls and severe
thermal pressure preempt them too.

The observer is the live trigger; the system Photo Picker is not an inference
trigger. At startup and after settled capture groups, a bounded recovery scan
uses each external volume's MediaStore version and `(GENERATION_ADDED, _ID)`
cursor to durably enqueue additions missed during process death or reboot. A
new install or MediaProvider database rebuild baselines the current generation
instead of importing the owner's historical library. A pending camera write is
never crossed, and a queue insertion must be durable before the cursor advances.

The encrypted index follows source lifetime. Exact delete or trash notifications
remove all generations of that canonical media URI. A 128-row, per-volume liveness
sweep runs after service restart and advances in bounded pages; an unmounted or
failed volume is never interpreted as mass deletion. Each batch also verifies
that the provider version and generation stayed stable across its Files query.
If MediaProvider's database identity changes or its generation counter regresses,
AIOS purges that volume's URI-keyed results before establishing a new baseline.
A successful replacement inference removes superseded generations for the same
source.
Only `GENERATION_ADDED` drives new inference. Favorite, trash, and unrelated
metadata changes can wake reconciliation but cannot themselves create a job.

The SQLite queue survives process death and reboot. The worker atomically claims
one item, verifies its `MediaStore` generation around content hashing and again
after inference, submits a read-only descriptor to Model Broker, validates a
strict result schema, and commits the encrypted result/index state in one SQLite
transaction. Broker absence is retryable; changed/deleted media becomes stale;
invalid model output fails closed.

The same result transaction stores privacy-minimized timing for the latest 100
photos and 100 videos. A debug-only dump exposes fixed p50/p95 counters for
queueing, preparation, Broker inference, end-to-end indexing, complete source-
audio duration, full audio-pipeline time, and audio real-time factor; it never
emits media identifiers or inferred content. See `docs/media-performance.md` for
the physical Pixel evidence procedure.

Metadata has two layers:

- The credential-encrypted AIOS index is authoritative and may contain rich
  results.
- `XmpProjection` creates a deliberately small portable packet. A format-specific
  writer may commit it only after it validates that advanced container features
  and decoded content remain intact.

Simple JPEG and non-animated PNG have format-specific, byte-preserving XMP
writers. PNG CRC/order failures, APNG, signed PNG, unknown critical chunks,
WebP, HEIC/HEIF, AVIF, DNG, Motion Photo, and Ultra HDR remain index-only until
dedicated round-trip tests pass. A video also remains read-only during automatic
indexing. The owner may later share one completed MP4 to the exported **Create
AI-enhanced copy** activity. After a confirmation dialog, an internal foreground
service creates a pending `Movies/AIOS` MP4, copies the original encoded audio,
video, and supported metadata samples without codecs, and adds two ISO-BMFF
`mett` tracks: one bounded description JSON sample and, when speech exists,
bounded timed subtitle-event JSON samples. The copy is published only after a
second extractor proves that every source sample timestamp, sync flag, size, and
digest matches and that the embedded samples round-trip exactly. Failures delete
the pending copy; the original is never opened for writing. Before MediaStore
insertion, a version-8 database journal records a UUID and output volume. The
pending row carries that UUID until publication, then atomically clears it with
`IS_PENDING`. Service startup and boot recovery delete only an owned, marked,
still-pending row, including the insert-before-URI-attachment window; an already
published verified copy is preserved and its self-write suppression is repaired.
AIOS understands the custom subtitle MIME, but ordinary players are not required
to display it. AIOS UI surfaces bind to a separate signature-permission reader.
That service accepts only canonical, published `MediaStore` MP4s owned by Media
Intelligence, revalidates their generation, and parses only the two exact AIOS
track MIME types. It returns bounded description/provenance fields and at most
16 subtitle cues per Binder page; it never returns a raw descriptor, model
artifact, source path, or arbitrary media bytes.

`preview:mediascancheck` stages this entire production source tree, every pure
unit test, both Media Intelligence AIDL surfaces, the production manifest, and
production resources into one public-SDK Android build. A debug manifest overlay
adds only the emulator smoke entry points. This catches source-list drift,
component/resource mistakes, and unacknowledged privileged-permission lint
failures; it does not prove platform grants, MediaStore behavior, or MP4 support
on a physical Pixel. Media Intelligence disables backup and device transfer for
every private app-data domain because those records and recovery artifacts are
source- and device-bound.

`scripts/emulator-media-smoke.ps1` provides the reproducible platform smoke
entry point. It is hard-guarded to a QEMU serial, creates a temporary screen-
recorded MP4, and executes both debug activities through its MediaStore URI. A
passing run proves that Android's actual extractor/muxer round-trips unchanged
encoded samples plus the AIOS description/timed-metadata tracks, and that the
attached/unattached/published recovery cases behave as designed. It explicitly
records `subtitle_renderer_exercised=false` and `physical_gate_evidence=false`.
