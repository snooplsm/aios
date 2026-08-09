# Model-admission evidence

Place reviewable, non-content benchmark JSON here. Do not include prompts, call
audio, transcripts, photos, device serial numbers, account identifiers, or raw
build fingerprints. The schema stores only a SHA-256 of the build fingerprint
and aggregate numeric/boolean measurements.

No model is currently admitted for a non-debuggable Pixel build. The checked-in
Pixel 9a profile remains `benchmark_pending`; this directory is not evidence of
a passed gate by itself.

See `../../docs/model-admission.md` and validate a proposed evidence file with
`tools/generate_model_admission.py` before changing the device policy.
