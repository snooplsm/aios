# Pixel 9a bring-up runbook

This runbook is intentionally conservative. Flashing unlocks/wipes the device and
an incorrect bootloader, radio, vendor, or anti-rollback combination can leave it
unbootable. Use a personally owned development Pixel 9a, back up first, and keep
a matching factory image available.

## 1. Linux build host

Use a supported 64-bit Linux host and run `scripts/preflight-linux.sh`. First
prove that AIOS still compiles on the moving Android release using the official
Cuttlefish lane:

```text
scripts/bootstrap-aosp.sh \
  --lane android_latest_integration \
  /absolute/path/to/aosp-latest
```

The same latest-release checkout can build the full standard Android Emulator
through `android_avd_integration` and an ARM64 Generic System Image through
`android_gsi_arm64`; see `docs/emulator-bringup.md` and `docs/gsi-bringup.md`.
Those are generic integration artifacts. The GSI is no longer the Pixel 9a
release route after the August 2026 boot failure.

Before syncing, place this repository at `vendor/aios` through a real local Repo
manifest as described in `manifests/README.md`. After sync, create the immutable
source lock and enforce the integration-lane project contract:

```text
vendor/aios/scripts/capture-aosp-lock.sh \
  /absolute/path/to/aosp-latest \
  android_latest_integration \
  /safe/release-artifacts/android-latest-run-id
```

Do not initialize the Pixel lane from Google's `android-latest-release` manifest:
Android 17's official manifest does not include complete Pixel targets. AIOS
instead pins GrapheneOS's signed `2026080500` release manifest, which supports
`tegu` on Android 17 and carries the maintained device, kernel, firmware, vendor,
and SELinux integration missing from the generic AOSP checkout. Initialize the
separate physical checkout with:

```text
scripts/bootstrap-aosp.sh \
  --lane pixel9a_tegu_hardware \
  /home/ryan/grapheneos-aios
```

The bootstrap verifies the tag against GrapheneOS's published allowed-signers
file and requires manifest commit
`d1b2739828a783bbf9bd6ba5d50c727b9329b9b7`. After the full sync, prepare the
device support set exactly as the pinned build guide requires:

```text
yarn --cwd vendor/adevtool/ install
adevtool generate-all -d tegu
```

Then expose this repository at `vendor/aios`, capture the resolved source lock,
and port the small AIOS patch queue to the pinned GrapheneOS fork revisions.
The full-device lane is mandatory for physical releases; never graft a device
tree from another release or substitute a partial factory-product overlay.

## 2. Pixel hardware inputs

The signed GrapheneOS release manifest plus `adevtool` form the source of truth
for the full `tegu` support set. Confirm all of the following are generated or
selected by that same pinned release family:

- AOSP platform and `device/google/tegu` revision;
- Pixel vendor image;
- Pixel kernel artifacts;
- bootloader and radio firmware; and
- SELinux/vendor interface expectations.

GrapheneOS documents `tegu` as a production-ready target and uses `adevtool` to
download, extract, and prepare matching factory/OTA inputs. The tool also fills
device support omitted by public AOSP, including properties, vendor interface
declarations, overlays, sysconfig, and SELinux extensions. Locally accepted and
extracted factory inputs stay private; never commit or redistribute them.

The pinned manifest includes `device/google/tegu-kernels/6.1` at an immutable
commit and `vendor/adevtool` at an immutable commit. The resolved manifest,
adevtool state, downloaded-input digests, generated vendor-tree digest, target
files, and signing-key identity must be captured together for every candidate.

## 3. Build order

Build the latest-integration lane first. The wrapper captures the immutable
manifest, validates the lane, transactionally stages the exact patch queue,
runs the full product build, verifies each required AIOS APK against AOSP's
current `installed-files-product.json`, digests that manifest plus the installed
artifacts and images, and then restores the upstream projects:

```text
vendor/aios/scripts/build-aosp-lane.sh \
  /absolute/path/to/aosp-latest \
  android_latest_integration \
  /safe/release-artifacts/android-latest-build-id \
  <safe-job-count>
```

Start with a low parallelism chosen for available RAM. Preserve the complete
Soong log, installed-file manifest, and build fingerprint. Evidence capture
fails if Context Intelligence, Messaging, Phone, Call Intelligence, Media
Intelligence, Model Broker, or the framework-defaults overlay is missing, empty,
or differs from the product image's installed-file record. The first compile is
expected to reveal any Android 17 API/module drift in this scaffold; fix it in
`vendor/aios`, not by making unrecorded edits throughout AOSP.

The ARM64 GSI may still be built from the generic checkout. Its evidence
recorder verifies the redirected AIOS artifacts in Android's installed-file
manifest (`installed-files.json` on Android 17, or the older
`installed-files-system.json`) and digests `pvmfw.img`, `system.img`, and
`vbmeta.img`. It is not a Pixel 9a deployment artifact:

```text
vendor/aios/scripts/build-aosp-lane.sh \
  /absolute/path/to/aosp-latest \
  android_gsi_arm64 \
  /safe/release-artifacts/android-gsi-build-id \
  <safe-job-count>
```

Only after device generation and the AIOS product/patch port pass the
`pixel9a_tegu_hardware` lane contract should the wrapper target the pinned
physical checkout:

```text
vendor/aios/scripts/build-aosp-lane.sh \
  /home/ryan/grapheneos-aios \
  pixel9a_tegu_hardware \
  /safe/release-artifacts/pixel9a-build-id \
  <safe-job-count>
```

Before flashing, run the host validator and confirm no model or vendor artifacts
are tracked. Model-enabled images additionally require a signed artifact manifest
and known-answer runtime smoke test.

## 4. Device preflight

With the phone still on its factory image, enable developer options and collect a
read-only inventory using an explicit serial:

```text
powershell -File scripts/device-inventory.ps1 \
  -Serial <adb-serial> \
  -Output C:\safe\release-artifacts\pixel-9a-factory.json
```

Record carrier, SIM/eSIM,
current build, bootloader/radio versions, both slot states, and factory behavior
for VoLTE, VoWiFi, Bluetooth, incoming/outgoing calls, DTMF, and conference calls.

Do not place uncoordinated live calls to 911 to test a research image. Emergency
behavior requires an approved carrier/device lab procedure; the local gate tests
the emergency UI, routing bypass, and that AIOS never intercepts the flow.

## 5. Flash and recovery safety

Follow the official Pixel factory/AOSP instructions for the exact build. Do not
relock a bootloader around an unsigned or test-key custom image. Keep bootloader
and radio partitions matched to the vendor build.

Google documents a special May 2026 update hazard: after first booting the May
2026 Android 16 update or newer on affected Pixels, ensure the inactive slot also
contains a bootable updated image before relying on seamless-update fallback.
Follow the current official instructions rather than copying old commands from
this document.

## 6. Validation sequence

Run gates in this order:

1. Boot, reboot, slot fallback, adb, encryption, lock screen, and factory restore.
   After a reboot, do not unlock the owner profile. Place a controlled incoming
   call and verify ringing UI plus answer, ignore, decline, and hang-up controls.
   Confirm AI answering and transcript/context surfaces remain unavailable and
   that no credential-encrypted call artifact is created. Unlock while a second
   controlled call remains active; verify the same Telecom call stays connected,
   optional AI services reconnect without duplicate call state, and record this
   physical evidence for `dialer.direct_boot_call_controls`.
2. Basic cellular/data, incoming/outgoing call, eSIM, VoLTE, VoWiFi, Bluetooth,
   DTMF, and conference-call baselines with all AIOS features off.
3. On a fresh user, verify `cmd role get-role-holders --user 0
   android.app.role.DIALER` returns `com.aios.phone`. Confirm
   `AiosFrameworkDefaultsOverlay` is enabled with `cmd overlay list android`,
   then verify the resolved `android:string/config_defaultDialer` value with
   `cmd overlay lookup android android:string/config_defaultDialer`. Change the
   dialer role to AOSP Dialer and back to prove owner choice still works. Only
   then run the dialer policy and controlled emergency-path gates; do not mark
   `dialer.preloaded_default_emergency_path` passed from emulator evidence.
4. SMS-role selection, two-way multipart SMS, then the controlled MMS matrix in
   `docs/mms-transport.md` using a dedicated second handset and non-sensitive
   photos. Reboot once after carrier submission but before callback observation,
   verify that no duplicate is sent, and inspect both the Telephony provider and
   the private operation journal before recording evidence. With a physical SIM
   and eSIM active, unset the system SMS default, verify Send remains disabled,
   then select each SIM in turn and confirm the exact provider subscription ID
   and carrier path for both SMS and MMS.
5. Downlink/uplink capture with synthetic, consented English and Spanish calls.
   During an active AI-handled call, kill Call Intelligence and confirm the
   carrier call and ordinary controls remain usable. Keep speaking while AI is
   absent. Verify Phone cancels any pending auto-answer work, reconnects with a
   new binding generation, replays Telecom presence, and resumes both capture
   directions against the artifact's original expiry. The remote endpoint must
   not hear the receptionist greeting a second time. Repeat with an owner-
   handled call and with terminal/null test bindings for
   `call.telephony_survives_ai_crash`.
   With a fault-injection build, force a post-first-PCM read failure in each
   direction and a write failure in the authoritative private sink. Confirm the
   exact AI session stops once, an AI-handled call changes to owner handling,
   no later transcript or caller speech appears, the partial artifact retains
   its original expiry, and the carrier call plus ordinary controls stay live.
   Record this separately as `call.capture_loss_fail_open`.
6. Airplane-mode ASR and model-broker failure injection. For
   `model.runtime_dependency_lock_verified`, use a fresh isolated Gradle user
   home on the Linux build host to bootstrap LiteRT-LM, Whisper, and TTS locks;
   review the generated metadata, deny network access, and run each provider's
   `build_provider.sh`. Confirm strict verification accepts the reviewed Linux
   AAPT2 digest and each emitted provenance digest matches its committed
   verification file before packaging any APK.
   For
   `call.telephony_survives_ai_crash`, first keep a consented call active while
   killing Model Broker. Confirm the carrier call, local two-direction PCM, and
   ordinary call controls continue; stale ASR callbacks stop updating the UI.
   After Android reconnects, confirm new downlink and uplink stream identities
   are attached to the same artifact and transcription resumes without another
   greeting or a new 24-hour deadline. Repeat by replacing the exact Broker APK,
   with a null-binding test build, and with a capability query held beyond the
   15-second watchdog; retries must cap at one minute.
   For `model.call_preempts_media`, start a speech-bearing deferred-video audio
   window and place a call only after native Whisper decode begins. Confirm the
   media session's active native compute aborts, its durable job returns to the
   retryable queue, and the first downlink partial meets the call latency gate.
   Repeat at the cancellation/next-window boundary and confirm no stale media
   token cancels either call ASR stream.
   For
   `model.build_fingerprint_admission_enforced`, verify the admitted models load
   under the benchmarked build fingerprint, then boot an image with a different
   build fingerprint but the old admission policy. Confirm release inference
   stays unavailable until new evidence is installed.
   For `model.runtime_fallback_selection`, use exact admitted artifacts where
   the preferred model's backend is withheld while the fallback backend remains
   advertised. Confirm capability discovery and a new session select the
   fallback, then restore the preferred backend and confirm new work returns to
   it without interrupting an already-running fallback session. Next make the
   preferred provider advertise readiness but reject `createSession`. Confirm a
   request with `allowFallback=true` activates the next admitted candidate and
   ignores a delayed callback from the rejected attempt. Repeat with
   `allowFallback=false` and confirm the request fails without opening the
   fallback.
   For `model.runtime_provider_recovery`, run an admitted inference session,
   kill the provider process and confirm the active session fails while a new
   session succeeds after Android reconnects it. Then replace the provider APK
   with the exact same admitted/version-pinned build, confirm the terminal
   binding is explicitly recreated, and prove new inference succeeds without
   restarting Model Broker. Repeat with a test provider that returns a null
   binding and one that crashes during creation; recovery must remain bounded
   and the broker process must stay alive.
7. Retention using a test-only shortened clock/injected time: arm cleanup, roll
   wall time backward, and confirm deletion still occurs at the persisted
   elapsed-realtime deadline for `retention.expiry_24_hours`. Reboot before an
   unexpired test artifact's deadline, unlock credential-encrypted storage, and
   confirm previous-boot artifacts are purged for `retention.boot_cleanup`.
   Follow both with the real 24-hour soak.
8. Media queue constraints and original-preservation corpus. During both a
   single-photo vision request and the full-audio pass of a video, kill Model
   Broker and verify the worker wakes promptly rather than waiting two minutes,
   the claimed row returns to pending, the original remains unchanged, and the
   next eligible run completes it once. Repeat with binding-death/null-binding
   injection and with `cmd jobscheduler` stopping the job immediately before a
   delayed success callback; no result may commit after the stop fence.
9. Thermal, battery, memory-pressure, repeated-call, reboot, and crash tests.
   For `context.bounded_local_retrieval`, seed more than eight newer rows in an
   excluded category plus older rows in each enabled category. Place calls while
   independently toggling Messages, Previous calls, and Sent photo descriptions;
   confirm only enabled sources appear and exclusions are applied before the
   eight-result limit. Narrow a source during an active AI-handled call and
   confirm already-prepared context is cleared before the next receptionist
   turn. Empty, duplicate, and unknown test scopes must fail closed.

Every item in `config/release_gates.json` needs evidence. A failure in ordinary
telephony, emergency bypass, retention, artifact integrity, or offline operation
blocks the research release.

## Official references

- AOSP latest release setup: https://source.android.com/docs/setup/start
- Pixel driver binaries: https://developers.google.com/android/drivers/
- Pixel factory images and current flashing warnings:
  https://developers.google.com/android/images
- Pixel kernel build mapping:
  https://source.android.com/docs/setup/build/building-pixel-kernels
