# Device model admission

RAM tiers identify models worth testing; they do not make a model safe or fast
enough for a phone. `config/model_admission.json` is the separate, AVB-protected
decision layer consumed by Model Broker.

The checked-in Pixel 9a (`tegu`) and officially identified Pixel 10-family
profiles are `benchmark_pending`. A debuggable AIOS build may run each device's
explicit tier and fallback research candidates so measurements can be
collected. A non-debuggable build admits none of them. Unknown device codenames
always receive no models, even if they report enough RAM to match a catalog
tier. Pixel 10 research profiles do not create a product wrapper, build lane,
release admission, or physical-device support claim.

## Evidence contract

A benchmark evidence file under `evidence/model-admission/` binds all of these:

- exact device codename, measured total RAM, and hashed build fingerprint;
- benchmark-suite version and completion timestamp;
- exact model ID, runtime, backend, and packaged artifact SHA-256;
- the canonical-JSON SHA-256 of `config/model_benchmark_suite.json`;
- the required gates, failed gates, and measured numeric/boolean metrics; and
- an explicit `passed` or `failed` decision consistent with those gates.

Promotion also requires passes for text, media, and TTS plus at least one ASR
candidate from the profile's declared tier/fallback chain. This prevents a
nominally "supported" device profile from silently shipping without a complete
receptionist/media path. It also lets one physical-device run measure a complete
preferred configuration and another run on the same device/build measure a
complete fallback configuration;
the admission generator can merge those evidence files into one profile. Every
admitted model still points to the exact evidence file that passed its backend
and artifact digest, and conflicting results for the same model are rejected.

The suite gates English and Spanish quality, call-time latency, throughput, and
crash-free execution. Peak RSS and maximum thermal status are mandatory
observations, but RSS is deliberately not a fixed pass/fail ceiling. AIOS uses
adaptive system-pressure handling rather than pretending one static RAM number
fits every call, camera workload, device, and future model.

The on-device collection path uses the userdebug-only
`AiosModelBenchmarkTests` instrumentation. It invokes the production Model
Broker sequentially for text, a generated red JPEG, bilingual Supertonic TTS,
and the selected Whisper ASR candidate. TTS output is resampled and looped into
ASR as a deterministic bilingual integration/performance fixture. ASR runs once
with source-timed 100 ms writes to measure a real non-final revision, chunk-
relative processing lag, English/Spanish auto-detection, and successful silence
endpoint finalization plus delay, then once without pacing to measure decode
real-time factor. Every ASR request uses `language=und`, matching the production
provider contract; expected English or Spanish is used only to score the final
detected language and transcript. The runner
refuses to start during a live call, samples Android thermal status throughout
each invocation, and emits measurements without pass/fail fields. Android may
hide cross-UID process PSS from the instrumentation package even on a
platform-signed userdebug build. The host collector therefore runs the exact
admission method explicitly and, every 500 ms, conservatively sums RSS for
Model Broker and every AIOS runtime provider. It writes the highest observed
sum into each sequential result rather than accepting an unavailable zero PSS
sample. The evaluator still rejects a missing or zero observation.
Every benchmark request sets `allowFallback` to false. A measurement is therefore
evidence for the exact artifact/runtime/backend tuple named by that test, never
for an unreported lower-tier model selected after activation failure.
The host evaluator rejects a zero/unavailable PSS sample and thermal values
outside Android's defined `0..6` status range; it still applies no fixed RAM
ceiling.

With the target connected over ADB, run the instrumentation, bind its output to
the real device identity, and evaluate the gates:

```text
powershell -File scripts/capture-model-benchmark.ps1 `
  -Output evidence\model-admission\pixel-9a-build-id.json
```

The capture script refuses non-debuggable builds, resolves the checked-in
profile from the actual device codename, reads measured total RAM, and stores
only a SHA-256 of the build fingerprint. `tools/evaluate_model_benchmark.py`
derives pass/fail decisions from the checked-in thresholds; the measurement
producer cannot supply its own decisions or gate list. Keep device serials,
prompts, audio, photos, and raw fingerprints outside the repository.

Before the longer admission suite, run one English invocation per selected role:

```text
powershell -File scripts/capture-realtime-smoke.ps1 `
  -Serial <physical-device-serial> `
  -Mode single `
  -Output out\pixel-single-model.json
```

This focused result is never admission evidence. It records 500 ms host RSS
samples, available/swap memory before and after, provider diagnostics, and
OOM/fatal/AIOS-low-memory-kill flags. `instrumentation_runtime_pss_available`
states whether Android exposed the cross-UID PSS values; the host samples remain
present either way. For TTS, `first_output_ms` is the first PCM byte observed on
the audio pipe, not a text callback or the request timeout. The structured
`runtime_phase_diagnostics` section also separates TTS engine readiness from
first-chunk generation, Gemma artifact verification and engine readiness from
first-token generation, and Whisper model initialization from per-window decode
time. Raw lifecycle logs remain attached for diagnosis but contain no prompts,
transcripts, PCM, photos, phone numbers, or serials.

To prove that the second pass actually reuses resident engines, run the
build/device-bound cold/warm harness after obtaining the expected serial and
build-fingerprint hashes from an authorized physical-device preflight:

```text
powershell -File scripts/capture-warm-retention.ps1 `
  -Serial <authorized-physical-device-serial> `
  -ExpectedSerialSha256 <authorized-serial-sha256> `
  -ExpectedBuildFingerprintSha256 <authorized-fingerprint-sha256> `
  -OutputDirectory out\pixel-warm-retention
```

The harness force-stops only Broker and its model providers, captures one cold
and one immediately following warm invocation of each selected role, and then
evaluates `config/warm_retention_benchmark.json`. A valid warm pass requires
explicit TTS, Whisper, and Gemma cache-hit logs, no warm reinitialization or
release/eviction request, and digest-cache hits for the large Whisper and Gemma
artifacts. It also requires no low-memory kill, OOM, or fatal event, thermal
status below severe, at least 512 MB available-memory headroom, and the
checked-in first-output targets. Cold and warm source files must have the same
hashed serial and build fingerprint. The derived `evaluation.json`
intentionally omits diagnostic logs, transcripts, responses, PCM, and image
content.

`-Measurements C:\safe\measurements.json` remains available for importing the
same strict raw schema from a separately reviewed runner. The deterministic
TTS-to-ASR loop is not representative human-speech proof. Before either
`call.english_streaming_asr` or `call.spanish_streaming_asr` can pass for a
release, run consented human and noisy telephony cohorts on physical hardware as
required by `docs/pixel9a-bringup.md`; those release gates remain independent of
model admission.

Generate a review candidate with:

```text
python3 tools/generate_model_admission.py \
  --evidence evidence/model-admission/pixel-9a-<build>.json \
  --output generated/model_admission/model_admission.json
```

Repeat `--evidence` to combine independently captured preferred and fallback
configurations for the same profile. Every file must identify the same device,
measured RAM, and hashed build fingerprint. Each file must also provide a
complete text/media/TTS/ASR configuration; partial evidence files cannot
collectively hide a configuration that was never tested end to end.

Review the evidence and generated diff before replacing the checked-in policy.
The generator copies the evidence digest into every admitted model. At boot,
Model Broker again hashes the running `Build.FINGERPRINT` and requires it, the
verified packaged artifact's backend, and the artifact digest to match that
admission. Re-quantizing, rebuilding, replacing weights, or installing a system
build with a different fingerprint makes the old benchmark admission unusable
by design. An AOSP update therefore remains model-free in a release build until
that build fingerprint is benchmarked and its admission policy is regenerated.
The physical negative test for this behavior is
`model.build_fingerprint_admission_enforced`.

An officially published codename and hardware record may create only a
debuggable, benchmark-pending research profile. Release admission still requires
a reproducible product/build lane plus exact device/build/artifact evidence. A
marketing name or RAM estimate alone never creates runtime admission.
