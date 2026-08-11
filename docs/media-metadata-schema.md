# AIOS portable media metadata v1

Namespace: `https://aios.dev/ns/media/1.0/`

The encrypted index is authoritative. The portable XMP projection contains only:

- `schemaVersion`
- `caption`
- a bag of `tags`
- BCP-47 `language`
- `modelId` and `modelDigest`
- UTC `inferredAt`
- numeric `confidence`

It deliberately excludes raw prompts, chain-of-thought, embeddings, recognized
identity, inferred precise location, OCR text, call information, and business
profile data.

## Commit protocol

The current implementation deliberately writes only structurally simple JPEG
files and valid, non-animated still PNG files. WebP, HEIF/HEIC, raw, Motion
Photo, MPF, Ultra HDR/gain-map JPEG, APNG, digitally signed PNG, JPEG files with
unknown APP payloads, and PNG files with bad CRC/order or unknown critical
chunks remain in the encrypted index until there is a container-specific writer
with equivalent preservation tests.

1. Open the item through `MediaStore` and record its generation, byte digest,
   MIME type, dimensions, and recognized container features.
2. Refuse portable mutation for an unsupported format, pending item, changed
   generation, Motion Photo, Ultra HDR, raw asset, or unknown auxiliary payload.
3. Save and fsync an exact source backup plus an app-private write-ahead journal.
4. Produce a format-specific candidate without decoding or recompressing pixels:
   insert one standard AIOS XMP APP1 segment in JPEG, or one uncompressed UTF-8
   `iTXt` chunk named `XML:com.adobe.xmp` immediately before PNG's first `IDAT`.
   Verify that removing only the inserted segment/chunk reproduces the source
   byte-for-byte; PNG also verifies every source CRC and preserves all original
   chunks, including the complete `IDAT` stream.
5. Replace through `MediaStore`, then reread and verify the exact candidate and
   its one AIOS packet with the journaled MIME-specific parser. Recover an
   interrupted commit on boot; version-1 JPEG journals remain readable, and
   unknown concurrent bytes are preserved before restoring the original.
6. Mark `portable_metadata_written=1` in the index only after verification.

The private index retains at most the inference-input digest and the verified
post-XMP digest as aliases for that same canonical job. This lets an explicitly
sent picker item match whether it was selected immediately before or after the
portable commit. Aliases never leave Media Intelligence. If one digest maps to
more than one current MediaStore job, conversation association fails closed.

No code may equate `ExifInterface` read support with write safety. AndroidX
documents broad read support but write support only for JPEG, PNG, and WebP, and
advanced Android photo formats can contain additional images and offset-bearing
metadata.

## Video inference and device gate

Videos never enter the portable commit protocol. The worker opens the original
read-only, seeks twenty nearest-sync keyframes at uniformly spaced segment
midpoints, and builds one app-private 5×4 JPEG storyboard. Each frame has a
maximum 224-pixel edge. Only that bounded storyboard is submitted under
`video_understanding`, and it is erased when inference completes, is cancelled,
or fails. Boot recovery and the next video attempt also erase leftovers from a
process crash. The authoritative record is still bound to the original video's
MediaStore ID, generation, and SHA-256 digest.

All twenty cells are required. A null decode at any requested position rejects
the storyboard instead of silently replacing claimed video context with a black
cell. The model therefore never receives a partial grid under a prompt that says
it contains twenty sampled frames.

The worker also selects the primary/default audio track and decodes its complete
timeline without remuxing the source. Decoded buffers are downmixed and resampled
to streaming PCM16 mono at 16 kHz, with presentation-time gaps represented as
silence so subtitle offsets remain aligned to the video. The existing
English/Spanish Whisper provider consumes four-second bounded media windows under the
lower-priority `media_background` workload. Only final segments are committed,
with language, start/end milliseconds, bounded text, and confidence. A missing
audio track and an audio track with no detected speech are distinct states.

Subtitle rows live in an app-private FTS4 external-content index keyed by the
media job. Source deletion, trash/volume reconciliation, or generation
replacement deletes both rows and search postings by foreign-key cascade. Full
subtitle text is never added to the portable photo XMP packet or to a source
video as an indexing side effect.

### Owner-created enhanced MP4

After a video has completed both inference passes, the Android share sheet offers
**Create AI-enhanced copy** for a canonical `MediaStore` `video/mp4`. A dialog
explains that this creates a new file and that generic players may ignore AIOS's
custom subtitle track. Confirmation starts an internal foreground export; there
is no automatic video mutation and no ZIP or sidecar output.

The service revalidates the source generation and SHA-256 against the indexed
record, creates an `IS_PENDING=1` item under `Movies/AIOS`, and remuxes without a
decoder or encoder. All supported original audio, video, and metadata samples
retain their encoded bytes and timestamps. The output adds:

- one `application/vnd.aios.video-description+json` ISO-BMFF `mett` track with
  caption, tags, language/confidence, source generation/digest, model identities,
  inference time, and subtitle state; and
- when speech exists, one `application/vnd.aios.subtitle+json` `mett` track with
  timestamped `cue` and `clear` events. Each sample is at most 64 KiB.

The timed events are transcript metadata, not a presentation feature. AIOS does
not render them and never burns text into the video frames. This is intentionally
not WebVTT/`tx3g`: Android's platform MP4 muxer can author application metadata
tracks, but not a standard text-subtitle sample entry. Ordinary players play the
unchanged encoded media while ignoring the custom track.

Authorized AIOS services may read the timed transcript through the exported
`com.aios.media.ENHANCED_VIDEO_METADATA_SERVICE`, protected by the signature
permission `com.aios.permission.READ_ENHANCED_VIDEO_METADATA`. The API does not
accept file paths or descriptors. It accepts only a query-free canonical
`content://media/<volume>/video/media/<id>` URI whose published row is owned by
Media Intelligence and still has the generation returned with the description.
The service rejects duplicate AIOS tracks, non-video containers, more than 34
tracks, timelines over 24 hours, samples over 64 KiB, noncanonical JSON, or any
description/cue/event mismatch. It returns description and provenance as a
bounded parcelable and subtitle cues in pages of at most 16, keeping each Binder
transaction comfortably below the platform limit. A generation change requires
the client to fetch fresh info before requesting another page.

Before publication, a fresh extractor verifies the exact embedded samples and a
per-track fingerprint over every original sample's presentation timestamp, sync
flag, encoded size, and encoded bytes. Unsupported/encrypted/partial samples,
source changes, service timeout, or any verification error delete the pending
copy. Self-write suppression prevents the derived video from entering the
inference queue.

Publication is also crash-safe across process death. A private version-8 SQLite
journal records a random export token, source generation, and destination volume
before the MediaStore insert. The `IS_PENDING=1` row stores the token in its
description until its verified publish update atomically clears both fields. The
output URI is attached to the journal immediately after insert. Recovery runs at
export-service startup and boot. It includes pending rows explicitly in every
MediaStore query/update/delete, since Android filters them by default. An owned
MP4 whose pending marker matches is deleted; the narrow insert-before-URI-attach
case is found by token and volume. An absent target is forgotten, an untrusted
target is never deleted, and an already published owned MP4 is preserved while
self-write suppression is repaired. A bounded recovery batch remains journaled
for retry if its volume or provider is unavailable.

The `media.simple_jpeg_xmp_round_trip` and `media.simple_png_xmp_round_trip`
physical-device gates decode before/after fixtures, compare rendered pixels,
and verify the expected XMP while checking the exact container-preservation
rules above. Host parser tests are necessary but do not satisfy those gates.

The `media.video_storyboard_indexed` and `media.video_subtitles_indexed`
physical-device gates use known short English and Spanish videos and verify
chronological keyframe coverage, complete timestamped subtitles from the primary
audio track, an unchanged generation and digest, an index-only commit, and no remaining
`aios_video_storyboard_*.jpg` cache file. Repeat while unplugging, dropping below
80%, starting a call, and using an undecodable video; work must respectively
retry or fail without modifying the source.

The `media.enhanced_video_copy_round_trip` physical-device gate shares a known
English and Spanish MP4 through the real confirmation UI, checks that the
original generation/digest is unchanged, verifies encoded-sample fingerprints
and both embedded MIME tracks, exercises audio-less video, and confirms playback,
failure cleanup, notification behavior, and no self-requeue on a Pixel build.

For faster platform regression, `scripts/emulator-media-smoke.ps1` runs the same
production capture grouping, battery/call gates, and Android job-constraint
builder. It also baselines an existing image, stops the package, adds the first
frame of a burst, restarts the production observer, adds the second frame, and
checks that recovery defers both frames together without importing the baseline
image. The runner then exercises the mux/verifier and metadata reader against a
temporary API-35+ emulator MP4, including attached-pending, unattached-pending,
and published-output recovery. It refuses physical serials, cleans its
MediaStore sources and APK, and emits only ignored, explicitly non-physical JSON
evidence. This verifies Android runtime policy and container behavior without
claiming Pixel playback, carrier behavior, rendered subtitles, or any physical
release gate.

The separate `media.enhanced_video_interrupted_export_recovery` gate kills the
export process before insert, after insert but before URI attachment, during
remux, and immediately after publication. Reboot/startup recovery must leave no
pending row or journal for the first three cases, preserve the verified published
copy in the last case, refuse mismatched owner/marker fixtures, and never modify
the source.

The `media.enhanced_video_metadata_reader` gate opens the published English,
Spanish, no-speech, and no-audio fixtures through the signature-only Binder API,
compares every returned field and cue with the indexed source, checks multi-page
reads, and proves that a foreign owner, pending/trashed row, query-bearing URI,
duplicate/custom malformed track, stale generation, oversized request, and
caller without the signature permission all fail closed.
