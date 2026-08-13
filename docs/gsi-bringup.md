# ARM64 GSI bring-up

The `android_gsi_arm64` lane is AIOS's forward-compatible physical-device
candidate for Treble-capable ARM64 Pixels. It tracks `android-latest-release`
and inherits AOSP's official `gsi_arm64` product. It does not claim that an
untested Pixel is compatible.

Google stopped including complete Pixel device targets in the Android 16 AOSP
manifest and directed public AOSP experimentation toward Cuttlefish and GSI.
This lane therefore complements rather than replaces `pixel9a_tegu_hardware`:

- the GSI replaces only the generic Android `system` partition and preserves
  the phone's bootloader, radio, kernel, vendor, and ODM partitions;
- the full `tegu` lane remains available for an exact platform/device/vendor
  set when one is legally and technically usable; and
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

Build evidence must find each required artifact in
`installed-files-system.json` at its redirected path and digest both
`system.img` and `vbmeta.img`. Merely producing a file named `system.img` is not
evidence that AIOS was packaged into it.

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

Review at least:

- exact codename, factory fingerprint, Android release, and security patch;
- ARM64 ABI, Treble state, vendor API/VNDK levels, and dynamic-partition state;
- bootloader, baseband, active slot, AVB state, and lock state;
- whether DSU is advertised; and
- the matching current factory image and recovery procedure.

The GSI security patch must not be older than the running system for DSU. The
preflight also parses the read-only `df -k /data` capture and requires free
space for the exact non-sparse `system.img`, an 8 GiB DSU userdata image, and
1 GiB of headroom. This is deliberately stricter than applying the generic
10 GiB recommendation to an AIOS image whose packaged models make it larger
than a typical GSI.

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
