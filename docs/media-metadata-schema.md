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

No code may equate `ExifInterface` read support with write safety. AndroidX
documents broad read support but write support only for JPEG, PNG, and WebP, and
advanced Android photo formats can contain additional images and offset-bearing
metadata.
