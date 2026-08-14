# Pixel 9a chunked bilingual audio smoke — 2026-08-14

This non-admission physical-device smoke measures the five-step Supertonic
candidate after limiting native synthesis chunks to 64 Unicode code points. It
contains no device serial, raw fingerprint, PCM, prompts, or transcripts. The
full diagnostic capture remains an ignored local artifact.

Supertonic emitted two PCM callbacks for each fixed bilingual fixture. P95 time
to first audio improved from the earlier five-step result of 6.062 seconds to
4.189 seconds, a 28.9% reduction. Both English and Spanish requests completed
with non-silent PCM and neither AIOS runtime crashed. P95 synthesis real-time
factor was still 1.390, so this does not yet satisfy continuous real-time call
speech. Direct pipe writing can also delay generation of the next chunk while a
real telephony `AudioTrack` drains; a physical carrier-call continuity test is
still required.

The shorter generated chunks contain a pause that caused Whisper to finalize
two caller turns. The benchmark now accumulates non-overlapping finalized turns
for scoring while live partials remain replaceable. Its host tests reject stale
or overlapping finals. With that corrected scoring, Whisper completed all four
runs with p95 real-time factor 0.464, English WER 16.7%, and Spanish WER 18.2%.
The higher WER than the unchunked fixture is a candidate-quality regression,
not a production admission result.

The runtime identity mismatch seen during the first deployment attempt was
caused by upgrading the provider and artifact manifest before the broker-owned
runtime catalog. The matching catalog was built and deployed; the broker then
connected `sherpa_onnx_tts` 1.13.7 without weakening identity verification.

This small clean-fixture cohort does not cover carrier codecs, echo, background
noise, thermal soak, playback gaps, or long-call queue stability. See
`audio-smoke-summary.json` for aggregate measurements and exact deployed
artifact hashes.
