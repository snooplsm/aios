# Bilingual speech-synthesis runtime

AIOS uses speech synthesis so the on-device receptionist can speak its own
short responses to a caller: greet the caller, ask why they are calling, request
a name or callback number, clarify a transcription, and acknowledge that a
message was captured. It is not used for a mandatory recording disclosure.

## Locked implementation

The provider in `runtime/ttsprovider` embeds the official Sherpa-ONNX Android
runtime at version `1.13.4`; AIOS provider implementation `1.13.8` uses the int8
Supertonic 3 model bundle. Call synthesis uses the pinned integration's
five-denoising-step default rather than the upstream model's higher-quality
eight-step default. It also limits a native synthesis chunk to 64 Unicode code
points so the first short sentence can reach call playback before the rest of a
multi-sentence response finishes. Both are candidate settings until physical
bilingual listening, intelligibility, and playback-continuity gates pass.
The runtime AAR, source revision, file size, SHA-256, ONNX Runtime version, and
packaged license texts are locked in `config/runtime_catalog.json`. The model
archive and every member's size and SHA-256 are separately locked in
`config/model_catalog.json`. Release builds contain only `arm64-v8a`; a
debug-only `x86_64` variant exists for guarded QEMU integration tests.

Supertonic is an open-weight model, but its model license is not the same as the
AIOS source-code license. The Sherpa release archive contains the upstream
project's MIT code license, not the weights' OpenRAIL-M license. A builder must
therefore review and record acceptance of the immutable catalogued model
license and supply that exact 15,007-byte file to the packer. The packer checks
its SHA-256 and installs it beside the model as
`MODEL_LICENSE.OpenRAIL-M.txt`. Neither the 128 MB model archive,
the model-license input, nor the 49 MB runtime AAR is committed here.

Primary upstream references:

- [Sherpa-ONNX Supertonic integration](https://k2-fsa.github.io/sherpa/onnx/tts/supertonic.html)
- [Sherpa-ONNX source](https://github.com/k2-fsa/sherpa-onnx)
- [Supertonic source and model documentation](https://github.com/supertone-inc/supertonic)
- [Supertonic 3 model license at the accepted revision](https://huggingface.co/Supertone/supertonic-3/blob/724fb5abbf5502583fb520898d45929e62f02c0b/LICENSE)

## Runtime contract

The signature-protected Model Broker is the only accepted caller. It creates a
`speech_synthesis` / `call_agent` session, attaches exactly one 44.1 kHz mono
PCM16 pipe, and submits one bounded final text request in English or Spanish.
The provider streams each native PCM chunk into the pipe as it is generated and
logs its ordinal, elapsed time, and sample count without logging the response
text. Pipe backpressure bounds queued audio instead of accumulating an entire
utterance in Binder or Java memory. The current direct writer deliberately
provides bounded behavior; physical tests must measure whether playback
backpressure creates a gap between native chunks before this candidate ships.
Caller playback converts that native 44.1 kHz mono stream directly to Telecom's
48 kHz stereo uplink, avoiding a redundant intermediate resample.

For an automatic AI answer, Call Intelligence creates the bounded greeting
session as soon as incoming-call policy authorizes AI handling. Provider session
creation queues digest verification and engine initialization immediately, while
the call is still in its configured 1--4 second ringing delay; it consumes no
text and emits no PCM before Telecom reports the call connected. The exact
prepared session is then transferred to the greeting path. A replaced decision,
owner answer, emergency transition, call end, dialer death, or failed admission
cancels it and closes both pipe ends. Preparation failure removes AI-answer
authority and leaves the call ringing for the owner. Runtime diagnostics report
both session-relative and post-text first-audio timing so hidden pre-answer work
cannot make connected-call latency ambiguous.

When the prepared greeting provider reports synthesis complete, its Broker lease
has been released even though already-buffered PCM may still be draining to the
caller. Call Intelligence uses that exact transition to open a no-input,
call-scoped Gemma text-generation session. The two live ASR streams retain their
higher-priority slots and the configured three-active-session ceiling is not
raised. When the caller's first finalized turn arrives, Call Intelligence
cancels the preparation session before opening the real, independently bounded
15-second receptionist request. LiteRT-LM serializes that cancellation with the
new session, so completed or in-progress engine initialization is reused without
submitting synthetic text, generating a hidden response, or adding anything to
conversation memory. Call end, owner takeover, emergency transition, Broker
replacement, and service teardown all revoke the preparation identity.

The provider repeats all security checks before native initialization:

- the descriptor is confined beneath `/product/etc/aios/models`;
- descriptor size and SHA-256 match the broker-owned artifact record;
- model ID and source-archive digest match the compiled provider lock;
- every bundle member has the expected relative path, size, and SHA-256;
- the requested backend is CPU and the language is exactly `en` or `es`.

Release accepts descriptors only below `/product/etc/aios/models`. The debug
variant may additionally use its private `files/emulator-config` tree only when
the BuildConfig fixture flag and QEMU/generic-hardware checks both pass. The
same canonical configuration root owns the descriptor and every locked member,
so a private debug descriptor cannot redirect a member outside that tree.

Generation stops when the broker cancels, the client dies, the PCM reader
closes, or the elapsed-realtime deadline expires. The engine remains resident
for call latency and is released only when idle under exact Android running-low,
running-critical, or cached-process pressure; `UI_HIDDEN` keeps it warm;
there is no fixed AIOS RAM ceiling. Speaker selection and conversational voice
quality still require physical-device evaluation.

## Real bilingual emulator proof

After reviewing the immutable OpenRAIL-M model license, bootstrap the exact
ignored model bundle and run the production provider on an API-35+ x86-64
emulator:

```text
powershell -ExecutionPolicy Bypass -File scripts/bootstrap-emulator-tts-fixtures.ps1 -AcceptModelLicense
powershell -ExecutionPolicy Bypass -File scripts/emulator-tts-provider-smoke.ps1 -Serial emulator-5554
```

Evidence capture requires a clean tracked source tree and records the exact
40-character AIOS revision. Generated model inputs and build outputs remain
ignored, but staged or unstaged source changes make the smoke refuse to run.
The resulting gate is emulator-only and cannot satisfy an ARM64 or physical
Pixel gate.

The bootstrap refuses to download until `-AcceptModelLicense` is explicit. It
then verifies the 128,774,318-byte archive at
`82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427`,
extracts only the catalogued members, reverifies each size and digest, and
generates the same locked descriptor shape used by the product pack. All model
bytes remain under ignored `.cache` and are never committed.

The smoke runner refuses physical devices and existing package installs. It
checks all four x86-64 Sherpa/ONNX native libraries, rejects the shell at the
signature permission, proves canonical descriptor confinement and provider
survival, then synthesizes one fixed English and one fixed Spanish receptionist
utterance through the real cross-process API. The client drains PCM only in
memory and requires bounded, aligned, non-silent 44.1 kHz mono output whose sample
count matches terminal metadata. It records neither the generated PCM nor any
caller content, and removes all model, staging, and APK fixtures afterward.

This proves native bilingual synthesis and the production pipe contract on x86;
the evidence remains explicitly ineligible for arm64, voice-quality, real-time,
caller-uplink, or physical-Pixel gates.

## Reproducible local inputs

On the Linux AOSP build host, fetch and verify the catalogued runtime inputs:

```text
cd vendor/aios/runtime/ttsprovider
./bootstrap_artifacts.sh
ALLOW_DEPENDENCY_LOCK_UPDATE=1 ./bootstrap_dependency_locks.sh
# Review and commit app/gradle.lockfile and gradle/verification-metadata.xml.
./build_provider.sh
```

The reviewed provider build uses Gradle 8.11.1, AGP 8.10.1, Kotlin 2.2.21,
Java bytecode target 17, and an arm64-only release APK. `build_provider.sh` performs an
offline strict-verification build after the lock bootstrap. Bootstrap resolves
both the declared dependency graph and an actual release assembly so detached
AGP tools are included. Verification metadata carries reviewed Windows and
Linux AAPT2 digests; the latter is required by the documented AOSP build host.

Generate the independently licensed model pack from the exact downloaded
archive:

```text
python3 vendor/aios/tools/generate_model_pack.py \
  --acceptance /secure/local/model_acceptance.json \
  --source supertonic3-en-es-int8:cpu=/secure/models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2 \
  --license-file supertonic3-en-es-int8=/secure/licenses/Supertonic-3-OpenRAIL-M.txt
```

Then turn the unsigned, dependency-verified APK into a product prebuilt that
Soong re-signs with the current platform key:

```text
python3 vendor/aios/tools/generate_runtime_pack.py \
  --runtime sherpa_onnx_tts \
  --apk vendor/aios/runtime/ttsprovider/app/build/outputs/apk/release/app-release-unsigned.apk \
  --provenance vendor/aios/runtime/ttsprovider/build/runtime-provenance.json
```

The resulting generated directories remain ignored. A release image advertises
speech synthesis only when both packs are installed and all broker/provider
identity checks pass. Actual caller hearing is a separate hardware gate: the
telephony uplink remains disabled in source policy until a real Pixel carrier
call proves route selection, timing, cancellation, and audio quality.
