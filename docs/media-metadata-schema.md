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
files. PNG, WebP, HEIF/HEIC, raw, Motion Photo, MPF, Ultra HDR/gain-map JPEG,
files with unknown APP payloads, and videos remain in the encrypted index until
there is a container-specific writer with equivalent preservation tests.

1. Open the item through `MediaStore` and record its generation, byte digest,
   MIME type, dimensions, and recognized container features.
2. Refuse portable mutation for an unsupported format, pending item, changed
   generation, Motion Photo, Ultra HDR, raw asset, or unknown auxiliary payload.
3. Save and fsync an exact source backup plus an app-private write-ahead journal.
4. Produce a candidate by inserting one standard AIOS XMP APP1 segment without
   decoding or recompressing pixels. Verify removing that segment reproduces the
   source byte-for-byte.
5. Replace through `MediaStore`, then reread and verify the exact candidate and
   its one AIOS packet. Recover an interrupted commit on boot; preserve unknown
   concurrent bytes before restoring the original.
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

The worker also selects the primary/default audio track and decodes its complete
timeline without remuxing the source. Decoded buffers are downmixed and resampled
to streaming PCM16 mono at 16 kHz, with presentation-time gaps represented as
silence so subtitle offsets remain aligned to the video. The existing
English/Spanish Whisper provider consumes four-second bounded windows under the
lower-priority `media_background` workload. Only final segments are committed,
with language, start/end milliseconds, bounded text, and confidence. A missing
audio track and an audio track with no detected speech are distinct states.

Subtitle rows live in an app-private FTS4 external-content index keyed by the
media job. Source deletion, trash/volume reconciliation, or generation
replacement deletes both rows and search postings by foreign-key cascade. Full
subtitle text is never added to the portable XMP packet. Any future SRT/VTT
export must be an explicit owner action rather than an indexing side effect.

The `media.video_storyboard_indexed` and `media.video_subtitles_indexed`
physical-device gates use known short English and Spanish videos and verify
chronological keyframe coverage, complete timestamped subtitles from the primary
audio track, an unchanged generation and digest, an index-only commit, and no remaining
`aios_video_storyboard_*.jpg` cache file. Repeat while unplugging, dropping below
80%, starting a call, and using an undecodable video; work must respectively
retry or fail without modifying the source.
