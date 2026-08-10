# AIOS

AIOS is an AOSP-based research operating system for Pixel phones. Its primary
product is an on-device AI receptionist built into the default dialer. The same
privileged model broker also supports deferred photo and video understanding for
authorized applications.

AIOS also includes a first-party Compose messaging client and a signature-only,
on-device communication context service. SMS, calls, contacts, and selected photo
metadata converge through opaque per-install conversation identities and bounded
retrieval rather than giving apps direct access to one unrestricted database.

The first hardware target is Pixel 9a (`tegu`). The architecture is capability
based so devices with more memory and better accelerators can select stronger
models without maintaining a different product fork.

## Current state

This repository is the small AIOS overlay and integration repository, not a copy
of AOSP. It now contains the AOSP product wrapper, Binder contracts, fail-closed
Model Broker policy, crash-isolated runtime transport, and session arbitration,
Call Intelligence policy/capture/
retention plus Broker ASR streaming, and a durable MediaStore inference worker.
The call path also has a digest-locked, arm64 Sherpa-ONNX/Supertonic 3 provider
that streams 24 kHz English/Spanish receptionist speech through the broker.
The owner-facing phone application is now an original Kotlin/Jetpack Compose
`InCallService` using immutable UDF state, typed actions, and a multi-call
Telecom registry; it includes system/light/dark themes, bounded recents and
visual voicemail, RTT, negotiated video surfaces, multi-SIM selection, and
post-dial/proximity behavior. The stock AOSP Dialer remains the system/emergency
fallback until the replacement passes device gates.
Call risk now crosses Binder as a typed, revisioned assessment: initial known-
contact legitimacy is published immediately, late listeners receive the current
state, stale concurrent updates are ignored, and Compose shows human-readable
legitimate/evaluating/suspicious/high-risk cards in both themes.
AI-handled active calls also expose a typed, revisioned handling state. The owner
can take over from either the in-call surface or its ongoing notification; Call
Intelligence stops queued and in-flight AI speech while keeping both capture
directions, live transcription, and advisory risk active.
The production phone sources also build and lint against the Android 16 public
SDK, and an emulator-only managed-call fixture now verifies the real dialer-role,
`InCallService`, ringing notification, and Compose in-call UI path. Emulator
results are explicitly excluded from physical-device release evidence.
Simple JPEGs have a conservative, byte-preserving AIOS XMP writer with backup,
verification, crash recovery, and self-write suppression; complex containers
remain index-only. Deferred videos are represented by twenty nearest-sync
keyframes sampled across the clip and composed into one private 5×4 JPEG
storyboard; the original video is never rewritten, and the storyboard is erased
after its bounded vision request. Device/model policy and host validators are
also present. Debug builds can export bounded, identifier-free photo/video timing
evidence so ETAs are based on the actual Pixel, build, model, and runtime rather
than desktop estimates.
AIOS Messaging now compiles as an SMS-role candidate with real SMS provider
paths, respond-via-message, call launching, and read-only Photo Picker drafts.
MMS remains visibly blocked and is a required carrier/device gate; the app does
not claim that photo messaging is complete.

It has not yet been compiled by Soong or flashed. Android 17's official manifest
does not contain the Pixel 9a `device/google/tegu` project, so the build strategy
has two explicit lanes: continuously compile the overlay on Android latest using
`aios_cf_x86_64_phone`, and separately admit the `aios_tegu` hardware lane only
after pinning one compatible platform/device/vendor/kernel/firmware set. An
exact-base Android 17 Dialer lifecycle patch exists, but it and the generated,
dependency-locked runtime provider must be built and tested on the Linux lane.
See `docs/model-packaging.md` and
`docs/runtime-packaging.md`; the dedicated call path is in
`docs/asr-runtime.md`, speech output is in `docs/tts-runtime.md`, and the
physical caller-audio gate is in `docs/caller-audio-uplink.md`.
Messaging and local retrieval boundaries are in
`docs/communications-context.md`.

RAM tiers nominate model candidates but do not authorize them on a release
image. `config/model_admission.json` permits research candidates only for known
devices on debuggable builds; `tools/generate_model_admission.py` promotes exact
artifact/backend pairs only from checked-in benchmark evidence. See
`docs/model-admission.md`. The checked-in bilingual latency/quality suite and
ADB capture path derive decisions from measured Pixel runs while observing RAM
without imposing a fixed model-memory ceiling. A test-only instrumentation APK
now drives the production Broker paths on eng/userdebug images and is excluded
from production `user` builds.

Important constraints are explicit:

- AOSP builds require a supported 64-bit Linux host; this Windows checkout is for
  the overlay, documentation, and host-side validation.
- Google vendor binaries and model weights are fetched locally after accepting
  their licenses. They are never committed or redistributed here.
- Google Phone, Google Camera, Play services, and many Pixel-branded features are
  not part of AOSP. AIOS must supply its own dialer and must verify carrier
  features on real hardware.
- The research image does not play a mandatory spoken disclosure. Call
  recording/transcription rules still vary by jurisdiction and deployment, so
  shipping policy requires qualified legal review.

## Repository layout

- `config/` contains machine-readable product, model, and capability policies.
- `docs/` records product requirements and architectural decisions.
- `scripts/` contains Linux AOSP bootstrap and host validation entry points.
- `tools/` contains dependency-free configuration validation.
- `tests/` contains host-side contract tests.

## Validate this repository

From PowerShell or Linux with Python 3.11+:

```text
python tools/validate_config.py
python -m unittest discover -s tests -v
python tools/release_report.py
```

`config/release_status.json` starts with every physical/build gate marked
`not_run`. A gate may be changed to `passed` only with an evidence path or URL;
`python tools/release_report.py --require-pass` is the release-blocking check.

On a synced AOSP tree, capture and validate the immutable lane lock, then verify
every maintenance topic before applying it:

```text
vendor/aios/scripts/capture-aosp-lock.sh /absolute/path/to/aosp android_latest_integration /safe/evidence/run-id
python vendor/aios/tools/verify_patch_series.py --aosp-root /absolute/path/to/aosp
vendor/aios/scripts/build-aosp-lane.sh /absolute/path/to/aosp android_latest_integration /safe/evidence/build-id 4
```

## Upstream strategy

AIOS tracks AOSP's `android-latest-release` manifest on an integration branch and
keeps product work under `vendor/aios` wherever Android interfaces allow it. A
framework change is accepted only when a privileged app or stable system API
cannot provide the capability. See `docs/upstream-strategy.md`.
