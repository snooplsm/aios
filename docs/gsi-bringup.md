# ARM64 GSI bring-up

The `android_gsi_arm64` lane keeps AIOS's additive system payload buildable on
the forward AOSP integration branch. It tracks `android-latest-release` and
inherits AOSP's official `gsi_arm64` product. It is a generic/virtual research
artifact, not the Pixel 9a release image or default physical deployment route.

Google stopped including complete Pixel device targets in the Android 16 AOSP
manifest and directed public AOSP experimentation toward Cuttlefish and GSI.
This lane therefore complements but never replaces `pixel9a_tegu_hardware`:

- the GSI replaces the generic Android `system` partition and the AVF `pvmfw`
  image produced by the same build, while preserving the phone's bootloader,
  radio, kernel, vendor, and ODM partitions;
- the full `tegu` lane uses a pinned signed GrapheneOS manifest plus `adevtool`
  to build the complete device support set and factory image; and
- neither lane may satisfy a physical gate until evidence is captured on the
  actual device.

Official references:

- https://source.android.com/docs/core/tests/vts/gsi
- https://source.android.com/docs/core/ota/dynamic-system-updates
- https://groups.google.com/g/android-building/c/S1G1edze3Co

## Why the complete AIOS product is in one image

AIOS applications and policy files remain `product_specific` because `/product`
is the appropriate customization boundary on full products. AOSP's GSI board
configuration deliberately redirects both product and system-ext output into
`/system/product` and `/system/system_ext` inside `system.img`. Consequently the
same Phone, Messaging, Call Intelligence, Context Intelligence, Media
Intelligence, Model Broker, permissions, configuration, and default-dialer RRO
are included without duplicate modules or a framework patch.

Build evidence must find each required artifact in Android's installed-file
manifest at its redirected path and digest `pvmfw.img`, `system.img`, and
`vbmeta.img`.
Android 17 currently emits `installed-files.json`; older branches may emit the
partition-specific `installed-files-system.json`. Merely producing a file named
`system.img` is not evidence that AIOS was packaged into it.

## Build

Use the same Android 17 checkout as the virtual integration lanes:

```text
scripts/bootstrap-aosp.sh \
  --lane android_gsi_arm64 \
  /absolute/path/to/aosp-latest

vendor/aios/scripts/build-aosp-lane.sh \
  /absolute/path/to/aosp-latest \
  android_gsi_arm64 \
  /safe/release-artifacts/gsi-build-id \
  <safe-job-count>
```

The successful record is eligible to identify a physical candidate, but its
`proves_physical_runtime_gate` field remains false.

The upstream compliance GSI group is 3 GiB. AIOS's distinct Pixel 9a reference
model payloads total about 2.79 GB before Android and the provider APKs, so the
wrapper builds inside a 6 GiB GSI group with AOSP's additional 8 MiB super
metadata allowance. This is build-container geometry only. It neither resizes
a connected Pixel nor proves that the resulting image fits its current dynamic
partition layout; the exact non-sparse `system.img` size flows into the
read-only preflight and DSU storage calculation below.

## Read-only device preflight

Do not unlock or flash first. Connect exactly one intended phone, leave it on
the factory image, and capture:

```powershell
powershell -File scripts/device-inventory.ps1 `
  -Serial <adb-serial> `
  -Output C:\safe\release-artifacts\pixel-9a-factory.json
```

The script finds `adb` on `PATH`, through `ANDROID_SDK_ROOT`/`ANDROID_HOME`, or
in Android Studio's default Windows SDK location. Use `-AdbPath <absolute-path>`
only when a different platform-tools installation is intentional.

Review at least:

- exact codename, factory fingerprint, Android release, and security patch;
- ARM64 ABI, Treble state, dated vendor/board API levels, and dynamic-partition
  state;
- bootloader, baseband, active slot, AVB state, and lock state;
- whether DSU and Android Virtualization Framework are advertised;
- the matching current factory image and recovery procedure.

The GSI security patch must not be older than the running system for DSU. The
preflight also parses the read-only `df -k /data` capture and requires free
space for the gzip copied into Downloads, the exact non-sparse `system.img`, an
8 GiB DSU userdata image, and 1 GiB of headroom. This is deliberately stricter
than applying the generic 10 GiB recommendation to an AIOS image whose packaged
models make it larger than a typical GSI. For build 5 this totals 17,772,119,947
bytes (about 16.55 GiB).

Do not require `ro.vndk.version` on a current Pixel. AOSP removed VNDK version
properties from Android 15-era vendor images. For modern images the checker
instead binds the GSI's embedded `ro.llndk.api_level` to the exact `system.img`
and requires it to be greater than or equal to the phone's dated
`ro.vendor.api_level`, following AOSP's vendor API compatibility rule.

That rollback comparison is a DSU constraint, not a claim that an unlocked
fastboot research boot is structurally impossible. The preflight therefore
disables `dsu_candidate` on an older patch while leaving `fastboot_candidate`
dependent on architecture, Treble, dynamic partitions, Android release, and
reported vendor interfaces. It still records the patch mismatch as a blocker,
keeps `safe_to_flash` false, and requires an operator-reviewed recovery plan.
Successful VINTF negotiation, partition capacity, AVB handling, and rollback
constraints still require device-specific checks. A structurally compatible
inventory is not permission to flash.

After a successful GSI build, bind that read-only inventory to its exact image
digests and enumerate the remaining blockers:

```text
python tools/check_gsi_preflight.py \
  --inventory /safe/release-artifacts/pixel-9a-factory.json \
  --build-evidence /safe/release-artifacts/gsi-build-id/soong-build-evidence.json \
  --expected-device tegu \
  --output /safe/release-artifacts/gsi-build-id/pixel-9a-preflight.json
```

On the Windows/WSL development host, the read-only wrapper performs both steps
without pushing an image or changing the phone:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/pixel9a-gsi-preflight.ps1 `
  -Serial <adb-serial> `
  -OutputDirectory C:\safe\release-artifacts\pixel9a-preflight
```

The output directory is intentionally required to be outside this repository.

Keep the build's `avb-verification.json`, `dsu-payload.json`, and
`system-interface.json` beside
`soong-build-evidence.json`. The checker rejects the candidate unless the AVB
record is digest-bound to the build record and exact image identities and the
DSU record binds the gzip back to that exact raw `system.img`. The interface
record binds the LLNDK level extracted from `/system/build.prop` inside that
same verified image.

The build and AVB records must also bind `pvmfw.img`. AOSP's Pixel GSI guidance
requires flashing it when the device advertises Android Virtualization
Framework; omitting it from deployment evidence is a preflight failure.

Do not flash `pvmfw.img` and reboot before its matching `vbmeta.img` is in
place. The two images are one AVB transaction. An intermediate reboot leaves
the selected slot with factory metadata authenticating a different firmware
image and is expected to fail verified boot.

## Pixel 9a physical result (2026-08-13)

The `20260813-gsi-build5-3c0c685-j12` image is rejected for further Pixel 9a
deployment. It was built as `CP2A.260605.016` with the 2026-06-05 security
patch, while the test phone ran factory build `CP2A.260705.006` with the
2026-07-05 patch. The old preflight excluded `system_patch_not_older` from the
fastboot decision. The image passed build, AVB, filesystem, architecture,
Treble, and coarse LLNDK checks but bootlooped before an ADB diagnostic window
was captured. Google factory image `CP2A.260705.006` restored the phone.

This result does not identify the crashing subsystem. Treat system/vendor
monthly-version skew, the non-atomic initial `pvmfw` experiment, and the later
factory-`pvmfw`/AIOS-system combination as separate unproven variables. A new
physical candidate must satisfy all of these conditions:

1. Its Android release and security patch are not older than the factory
   system on the phone.
2. Its system/vendor VINTF negotiation is exercised against the exact factory
   vendor artifacts.
3. `pvmfw`, `vbmeta`, and `system` are deployed without an intermediate boot.
4. A non-destructive trial or dedicated test slot reaches `sys.boot_completed`
   and preserves early-boot diagnostics before the image is called deployable.

As of this result, Google's public AOSP build table maps only
`CP2A.260605.016` to `android-17.0.0_r1`; it does not publish a source tag for
the Pixel factory build `CP2A.260705.006`. Do not label the moving
`android17-release` branch as an exact factory match. The next physical build
must use the pinned full `tegu` target described in `docs/pixel9a-bringup.md`;
do not repeat the raw GSI or partial factory-product-overlay experiment.

This checker can reject architecture, Treble, dynamic-partition, device-identity,
Android-version, and security-patch mismatches. It deliberately emits
`safe_to_flash: false`: VINTF execution, partition capacity, AVB, restoration,
and physical functionality cannot be proven from property strings.

## Safer trial order

1. Prefer DSU when the factory build advertises it and accepts the signed test
   image. DSU is temporary and leaves the installed system available for the
   next boot, but it still needs adequate storage and a compatible security
   patch level.
2. If DSU cannot exercise the required privileged integration, back up the
   phone, preserve the matching factory image, inventory both slots, and only
   then consider an unlocked fastboot/fastbootd deployment following current
   official GSI and Pixel recovery instructions.
3. Never copy an old bootloader, radio, vendor, kernel, or `tegu` image into the
   GSI flow. The point of this lane is to retain the phone's current hardware
   stack.
4. Do not relock around a test-key AIOS image.

After the wrapper reports `dsu_candidate: true`, use the separate start command
with the inventory and preflight paths it printed. The payload currently lives
outside Git in the WSL evidence directory and is accessible from Windows through
`\\wsl.localhost\Ubuntu-24.04\home\ryan\aios-evidence\...`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-pixel9a-dsu.ps1 `
  -Serial <adb-serial> `
  -Inventory C:\safe\release-artifacts\pixel9a-factory-<run>.json `
  -Preflight C:\safe\release-artifacts\pixel9a-gsi-preflight-<run>.json `
  -Payload \\wsl.localhost\Ubuntu-24.04\home\ryan\aios-evidence\20260813-gsi-build5-3c0c685-j12\17.aios_gsi_arm64.b7ec30e.raw.gz `
  -IUnderstandThisStartsDsu
```

The start script revalidates the connected serial, unchanged factory build,
current free space, all evidence digests, and the complete payload hash before
copying the gzip or launching Android's DSU verification activity. For a WSL
payload it makes one generated copy under the Windows temporary directory,
hashes and pushes that local file, and removes only that staging directory when
the command finishes; this avoids hashing and pushing the large file twice over
the WSL share. Override the temporary location with `-StagingDirectory` when
needed. It does not unlock, disable AVB, invoke fastboot, or reboot. Accept the
verification UI and restart only from the DSU notification after installation
completes.

After AIOS reaches the fresh-user system UI and ADB is authorized again, capture
the first-boot gate. This hashes every AIOS file represented by the build record
on the phone, including model files and runtime providers, so allow several
minutes:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/capture-pixel9a-gsi-boot.ps1 `
  -Serial <adb-serial> `
  -Inventory C:\safe\release-artifacts\pixel9a-factory-<run>.json `
  -Preflight C:\safe\release-artifacts\pixel9a-gsi-preflight-<run>.json `
  -Output C:\safe\release-artifacts\pixel9a-gsi-first-boot-<run>.json
```

The resulting record can prove the exact DSU boot and installed payload. It
explicitly does not prove telephony, inference latency, media processing, both-
slot reboot, factory restoration, or the complete physical milestone.

Validate the external record independently before reviewing it for check-in:

```text
python tools/validate_pixel9a_gsi_boot_evidence.py \
  --evidence /safe/release-artifacts/pixel9a-gsi-first-boot-<run>.json \
  --inventory /safe/release-artifacts/pixel9a-factory-<run>.json \
  --preflight /safe/release-artifacts/pixel9a-gsi-preflight-<run>.json \
  --build-evidence evidence/gsi/20260813-gsi-build5-3c0c685-j12/soong-build-evidence.json
```

Unlocking normally wipes user data. Pixel anti-rollback state may prevent
booting older firmware even when an old public AOSP target exists. The exact
phone inventory, not its marketing name, decides the allowed procedure.

## Physical evidence sequence

After the image reaches the lock screen, capture a build-bound first-boot record
before treating any application smoke as meaningful. Then validate, in order:

1. boot, encryption, reboot, recovery, and factory restoration;
2. incoming/outgoing calls, emergency bypass, VoLTE, VoWiFi, eSIM, SMS/MMS,
   Bluetooth, audio routes, DTMF, conference/call waiting, and voicemail;
3. camera capture, burst/video scheduling, metadata round trips, and original
   preservation;
4. bilingual ASR, AI receptionist latency and caller audibility; and
5. GPU/CPU model latency, memory pressure, sustained thermals, and fallback.

Only those physical results may promote model admissions or enable caller
uplink speech. A successful GSI build or boot never substitutes for this matrix.
