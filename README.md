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
post-dial/proximity behavior. A platform-signed product resource overlay makes
AIOS Phone the dialer for fresh users while preserving Android's standard,
owner-changeable dialer role. The stock AOSP Dialer remains installed as a
recovery alternative, but emergency routing and UI are not called validated
until the physical-device gate passes.
The in-call activity, Telecom service, and call-notification actions are Direct
Boot aware, so ordinary call controls can run after reboot before first unlock.
Only theme and role-prompt preferences use device-encrypted storage; assistant
policy, AI services, call artifacts, and communication context stay unavailable
until credential storage unlocks.
Call risk now crosses Binder as a typed, revisioned assessment: initial known-
contact legitimacy is published immediately, late listeners receive the current
state, stale concurrent updates are ignored, and Compose shows human-readable
legitimate/evaluating/suspicious/high-risk cards in both themes.
Both telephony taps must continue delivering PCM after startup. The first
unexpected downlink, uplink, or authoritative-storage loss atomically removes
the exact AI session, stops its inference and caller speech, and hands the
already-connected call back to ordinary Telecom controls without hanging up.
AI-handled active calls also expose a typed, revisioned handling state. The owner
can take over from either the in-call surface or its ongoing notification; Call
Intelligence stops queued and in-flight AI speech while keeping both capture
directions, live transcription, and advisory risk active.
An interrupted finalized receptionist turn survives Model Broker replacement as
one immutable prompt with a fresh callback identity and its original 15-second
deadline; recovery cannot duplicate history, renew the response budget, or
release later queued caller speech early.
After TTS starts, Broker loss or provider error stops only the matching
synthesis/uplink pair and advances the dialogue once. Normal completion still
lets buffered PCM drain, while stale callbacks cannot stop newer caller audio
and possibly partial speech is never replayed automatically.
The Broker session and PCM pipe are prepared first, but caller-facing text is
not submitted until Call Intelligence has attached the exact synthesis/uplink
pair. A synchronous provider failure during start therefore races through the
same one-shot identity gate instead of escaping setup or advancing twice.
Each speech request also publishes only its first terminal condition. Completion
therefore protects buffered PCM from a later erroneous error/disconnect, while
owner closure suppresses every delayed provider callback.
The low-priority ongoing call notification carries a bounded live preview of the
latest incoming speech with AI/risk status beneath it. Transcript previews are
private lock-screen content, sanitized for control characters, and never appear
on the ringing notification.
The production phone sources also build and lint against the Android 16 public
SDK, and an emulator-only managed-call fixture now verifies the real dialer-role,
`InCallService`, ringing notification, incoming controls, and a Compose-originated
outgoing call through connected mute, hold, bounded DTMF, redacted post-dial,
waiting-call, conference merge/separate, multi-account selection, and hang-up
controls. Emulator results are explicitly excluded from physical-device release
evidence.
Simple JPEGs and non-animated PNGs have conservative, byte-preserving AIOS XMP
writers with backup, verification, crash recovery, and self-write suppression;
complex containers remain index-only. Deferred videos are represented by twenty
nearest-sync keyframes sampled across the clip and composed into one private 5×4
JPEG storyboard; the original video is never rewritten, and the storyboard is
erased after its bounded vision request. The complete primary audio track is separately
decoded as streaming 16 kHz mono PCM and passed through the same bilingual
Whisper runtime used for calls, producing private timestamped subtitles in a
source-linked full-text index. Automatic indexing never rewrites a video.
Whenever a call becomes present, Broker cancellation reaches an active
native video Whisper decode through a race-fenced abort token, freeing the sole
decode lane for incoming speech without allowing stale cancellation to touch a
call stream. From
the system share sheet, the owner can explicitly choose **Create AI-enhanced
copy** to publish a new MP4 in `Movies/AIOS`; it copies the encoded audio/video
samples without recompression and embeds bounded AIOS description and timed
transcript-metadata tracks. The camera original remains untouched. AIOS does not
render or burn these cues into the video; authorized services read them for
search and context, while ordinary players simply play the unchanged media.
Device/model policy and host validators are also present. Debug builds can export
bounded, identifier-free photo/video timing, including full-audio pipeline time
and real-time factor, so ETAs are based on the actual Pixel, build, model, and
runtime rather than desktop estimates.
Repository validation and upstream tracking are intentionally local:
maintainers run the dependency-free policy validator, complete Python contract
suite, non-blocking release-status report, and explicit AOSP tracking check
before committing. AIOS has no hosted continuous-integration or automation
workflows. Local checks do not stand in for Soong, emulator, or physical Pixel
evidence.
Media capture discovery is automatic and camera-independent: live MediaStore
observation is backed by durable per-volume generation cursors that recover
missed additions after process death or reboot without importing the existing
photo library. The Photo Picker is only a Messaging attachment boundary and
never triggers duplicate inference.
The private media index now follows source deletion or trash through exact
notifications and bounded restart sweeps. Unmounted volumes fail closed without
losing their index, while a MediaProvider database identity change purges unsafe
URI-keyed results before baselining again.
JobScheduler callbacks are bound to the exact media worker-run identity. A stop
or finish from an older immediate/deferred delivery cannot interrupt a newer
photo/video run, reset its commit fence, or complete its `JobParameters`.
AIOS Messaging now compiles as an SMS-role candidate with real SMS provider
paths, respond-via-message, call launching, and read-only Photo Picker drafts.
An emulator-only runner assigns the SMS role temporarily, injects a PDU through
the emulator's authenticated modem-control endpoint, verifies the production
receiver/provider/Compose path, submits an SMS through the production composer,
and restores the prior role and database exactly. This evidence never claims
carrier delivery, MMS, physical-device, or multi-SIM release coverage.
Its AOSP-only debug transport persists outbound and inbound MMS through the
Telephony provider, uses `SmsManager` carrier send/download callbacks, transcodes
photos to measured carrier limits, and journals each operation so process death
cannot silently resubmit a message. Release `user` builds still fail closed until
the physical carrier/device gate passes; the public-SDK preview uses a disabled
transport fixture and does not claim carrier support. SMS and MMS share an
explicit composer SIM selector: a valid owner choice or system default is used,
a lone active SIM is automatic, and ambiguous dual-SIM routing fails closed.
Its communication index now reconciles the authoritative SMS/MMS provider in
bounded pages after restart and provider changes. A keyed private ledger detects
edits without storing message text or numbers; deletions and SMS-role loss
produce monotonic context watermarks rather than leaving stale RAG entries.
An outgoing Photo Picker attachment is now staged through a signature-only file-
descriptor bridge using an opaque conversation identity. Draft selection and
carrier submission cannot publish photo context: the durable MMS journal carries
an association token, and Media Intelligence publishes its reviewed caption/tags
only after the carrier-confirmed Sent transition finds exactly one matching local
media result. Message deletion, photo deletion/trash, provider reconciliation,
context-store replacement, process restart, and SMS-role loss all converge on a
durable deletion or rebuild. The live association client retains bounded keyed
work across Media Intelligence service loss and acknowledges the carrier-success
journal only after durable admission. Cloud-backed or byte-identical ambiguous
photos fail closed instead of being linked to the wrong source.
See `docs/mms-transport.md`.
Incoming calls now resolve their presented number transiently into the same
opaque communication identity used by Messaging. The receptionist can consume
at most eight identifier-free historical snippets without delaying Telecom, and
the owner can independently admit messages, previous-call/contact context, and
sent-photo descriptions; the context service applies that allowlist before
ranking and the result limit, while scope narrowing clears prepared prompts.
The normal teardown publishes a final-only bounded call summary with the existing
artifact's 24-hour expiry. The client replaces failed, null, terminal, and
stalled context-service bindings and replays bounded preparation or final-index
work only while the call identity and artifact lifetime remain current. A
dedicated public-SDK module compiles and tests this Binder client; physical
behavior remains a Pixel release gate.
Call-derived retrieval rows now carry the artifact's exact wall and monotonic
creation/expiry pairs plus its original internal boot identity. The nearest
deadline has its own local idle-capable alarm, and reboot, service startup, or an
unsafe legacy deadline deletes the row fail-closed instead of waiting for a
later search or trusting a rolled-back wall clock.

It has not yet been compiled by Soong or flashed. Android 17's official manifest
does not contain the Pixel 9a `device/google/tegu` project, so the build strategy
has three explicit lanes: continuously compile on Cuttlefish with
`aios_cf_x86_64_phone`, boot a complete standard Android Emulator image with
`aios_sdk_phone_x86_64`, and separately admit the `aios_tegu` hardware lane only
after pinning one compatible platform/device/vendor/kernel/firmware set. An
exact-base Android 17 Dialer lifecycle patch exists, but it and the generated,
dependency-locked runtime provider must be built and tested on the Linux lane.
Build evidence now rejects stale or empty outputs by matching every core AIOS APK
to the size and digest in AOSP's current `installed-files-product.json`.
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
without imposing a fixed model-memory ceiling. New Broker sessions also sample
live Android memory and thermal pressure: opted-in call work prefers a smaller
admitted fallback when constrained, while background media waits and retries. A
test-only instrumentation APK
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

The same checkout can build the standard Android Emulator product and launch
the resulting full AIOS image:

```text
vendor/aios/scripts/build-aosp-lane.sh /absolute/path/to/aosp android_avd_integration /safe/evidence/avd-build-id 4
cd /absolute/path/to/aosp
source build/envsetup.sh
lunch aios_sdk_phone_x86_64-aosp_current-userdebug
emulator -wipe-data -no-snapshot
```

After boot completes, bind the running image to its exact Soong evidence:

```text
python3 vendor/aios/tools/capture_avd_boot_evidence.py --serial emulator-5554 --build-evidence /safe/evidence/avd-build-id/soong-build-evidence.json --output /safe/evidence/avd-build-id/avd-first-boot.json
```

This is a Goldfish x86-64 AVD, even if its screen profile resembles a Pixel 9a;
it does not prove Pixel hardware, modem, camera, or accelerator behavior. See
`docs/emulator-bringup.md`.

When Google's moving manifest ref changes, refresh its reviewed observation
before syncing projects:

```text
vendor/aios/scripts/refresh-aosp-integration.sh /absolute/path/to/aosp
git diff -- config/aosp_tracking.json
```

Commit that small tracking change, then run `repo sync -c` and the locked build
above. The build refuses an unreviewed manifest-head change.

## Upstream strategy

AIOS tracks AOSP's `android-latest-release` manifest on an integration branch and
keeps product work under `vendor/aios` wherever Android interfaces allow it. A
framework change is accepted only when a privileged app or stable system API
cannot provide the capability. See `docs/upstream-strategy.md`.
