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

Before syncing, place this repository at `vendor/aios` through a real local Repo
manifest as described in `manifests/README.md`. After sync, create the immutable
source lock and enforce the integration-lane project contract:

```text
vendor/aios/scripts/capture-aosp-lock.sh \
  /absolute/path/to/aosp-latest \
  android_latest_integration \
  /safe/release-artifacts/android-latest-run-id
```

Do not initialize the Pixel lane from `android-latest-release`: Android 17's
official manifest does not include `device/google/tegu`. Once section 2 has
identified a genuinely compatible immutable platform/device/vendor set,
initialize a separate checkout with:

```text
scripts/bootstrap-aosp.sh \
  --lane pixel9a_tegu_hardware \
  --revision <immutable-compatible-tag-or-commit> \
  /absolute/path/to/aosp-tegu
```

The resolved lock digest belongs in release evidence. Do not replace the moving
tracking branch with a guessed tag or graft a device tree from another release.

## 2. Pixel hardware inputs

Confirm all of these describe one compatible release family:

- AOSP platform and `device/google/tegu` revision;
- Pixel vendor image;
- Pixel kernel artifacts;
- bootloader and radio firmware; and
- SELinux/vendor interface expectations.

Google's public AOSP driver page currently lists `tegu` vendor images only for
Android 15, while the Android 17 manifest omits the `tegu` device project. That
is a platform-selection gate, not something to paper over. Prefer an official
matching driver package. A personally owned research build may use a locally
accepted and extracted matching factory image only under its license; never
commit or redistribute extracted files.

The documented factory-supported Pixel 9a kernel line currently maps to
`android-gs-tegu-6.1-android16`. Record the exact kernel commit and artifacts used
with the release manifest.

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
Intelligence, or Model Broker is missing, empty, or differs from the product
image's installed-file record. The first compile is expected to reveal any
Android 17 API/module drift in this scaffold; fix it in `vendor/aios`, not by
making unrecorded edits throughout AOSP.

Only after the Pixel compatibility set and its resolved manifest pass the
`pixel9a_tegu_hardware` lane contract should the same wrapper target that
checkout:

```text
vendor/aios/scripts/build-aosp-lane.sh \
  /absolute/path/to/aosp-tegu \
  pixel9a_tegu_hardware \
  /safe/release-artifacts/pixel9a-build-id \
  <safe-job-count>
```

Before flashing, run the host validator and confirm no model or vendor artifacts
are tracked. Model-enabled images additionally require a signed artifact manifest
and known-answer runtime smoke test.

## 4. Device preflight

With the phone still on its factory image, enable developer options and collect a
read-only inventory using `scripts/device-inventory.ps1`. Record carrier, SIM/eSIM,
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
2. Basic cellular/data, incoming/outgoing call, eSIM, VoLTE, VoWiFi, Bluetooth,
   DTMF, and conference-call baselines with all AIOS features off.
3. Dialer policy and emergency bypass.
4. SMS-role selection, two-way multipart SMS, then the controlled MMS matrix in
   `docs/mms-transport.md` using a dedicated second handset and non-sensitive
   photos. Reboot once after carrier submission but before callback observation,
   verify that no duplicate is sent, and inspect both the Telephony provider and
   the private operation journal before recording evidence. With a physical SIM
   and eSIM active, unset the system SMS default, verify Send remains disabled,
   then select each SIM in turn and confirm the exact provider subscription ID
   and carrier path for both SMS and MMS.
5. Downlink/uplink capture with synthetic, consented English and Spanish calls.
6. Airplane-mode ASR and model-broker failure injection. For
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
8. Media queue constraints and original-preservation corpus.
9. Thermal, battery, memory-pressure, repeated-call, reboot, and crash tests.

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
