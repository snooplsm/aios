# Pixel 9a bilingual audio smoke — 2026-08-14

This is a non-admission physical-device smoke summary captured on the AIOS
`tegu` userdebug build with the exact fixed English and Spanish fixtures in
`ModelAdmissionBenchmarkTest`. It contains no device serial, raw build
fingerprint, PCM, prompts, or transcripts. The full path-redacted diagnostic
record remains an ignored local artifact.

The run proves that the Pixel 9a Whisper Base Q5 candidate can stay ahead of
the supplied bilingual audio: four runs completed without a provider crash,
p95 real-time factor was 0.546, English WER was 8.3%, and Spanish WER was 9.1%.
The broker held `BIND_IMPORTANT` only during active sessions, native logs
reported DOTPROD and MATMUL_INT8, and the two-second live windows used an audio
context of 256 rather than Whisper's 1500-position default.

This does not promote the model to production. The cold p95 partial latency is
still 3.336 seconds, the fixture cohort is intentionally tiny, and the run does
not cover call-acoustic noise, echo, carrier codecs, thermal soak, or long-call
queue stability. Those remain part of the model-admission gate.

Supertonic completed both language fixtures, but it remains the audio-path
bottleneck: p95 time to first audio was 7.527 seconds and p95 real-time factor
was 1.520. Its current callback still delivers the completed waveform rather
than useful incremental audio.

See `audio-smoke-summary.json` for exact hashes and aggregate measurements.
