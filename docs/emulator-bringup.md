# Android Emulator system-image bring-up

AIOS has a standard Android Emulator lane in addition to Cuttlefish. The
`android_avd_integration` lane wraps AOSP's `sdk_phone64_x86_64` product with the
same additive AIOS packages used by the Pixel product. It produces a complete
Goldfish-backed operating-system image, not only installable preview APKs.

This virtual device cannot reproduce Pixel 9a Tensor G4, GPU/NPU performance,
modem firmware, carrier behavior, camera hardware, secure elements, or thermal
limits. Its evidence must never satisfy a physical Pixel release gate. A Pixel
9a-shaped Android Studio hardware profile changes display and input properties;
it does not change that boundary.

## Build on Linux

Initialize or reuse the same `android-latest-release` checkout used by the
Cuttlefish lane, with AIOS checked out at `vendor/aios`:

```text
vendor/aios/scripts/bootstrap-aosp.sh \
  --lane android_avd_integration \
  /absolute/path/to/aosp-latest

cd /absolute/path/to/aosp-latest
repo sync -c -j <safe-job-count>

vendor/aios/scripts/build-aosp-lane.sh \
  /absolute/path/to/aosp-latest \
  android_avd_integration \
  /safe/release-artifacts/android-avd-build-id \
  <safe-job-count>
```

The normal build wrapper locks the resolved manifest, verifies and temporarily
applies the reviewed AOSP patch queue, builds the product, checks every required
AIOS artifact against `installed-files-product.json`, captures image digests,
and restores the upstream projects.

## Launch the built image

After the build succeeds, launch its selected product from the same Linux shell:

```text
cd /absolute/path/to/aosp-latest
source build/envsetup.sh
lunch aios_sdk_phone_x86_64-aosp_current-userdebug
emulator -wipe-data -no-snapshot
```

`-wipe-data` affects only that emulator's disposable data image. Omit it when
you intentionally want to retain the AVD's local test state. Do not point the
emulator at Pixel factory images or `aios_tegu` artifacts; those images require
the physical Pixel boot and vendor stack.

The first successful Soong build and boot must be recorded separately as
`integration.android_avd_userdebug_succeeds` and
`integration.android_avd_first_boot`. Until those evidence-backed gates pass,
the product definition proves intent and configuration—not a booted image.

## Capture first-boot evidence

Once `sys.boot_completed` is `1`, use a second terminal to bind the running AVD
to the exact build evidence produced above:

```text
python3 vendor/aios/tools/capture_avd_boot_evidence.py \
  --serial emulator-5554 \
  --build-evidence /safe/release-artifacts/android-avd-build-id/soong-build-evidence.json \
  --output /safe/release-artifacts/android-avd-build-id/avd-first-boot.json
```

The capture tool refuses any serial that is not `emulator-NNNN`, then requires
QEMU, completed boot, the AIOS product and build fingerprint, a debuggable
`userdebug` build, a valid boot ID and uptime, and all six privileged AIOS
packages installed from `/product/priv-app`. The output includes the SHA-256 of the Soong
build evidence and is permanently marked ineligible for physical runtime gates.
It refuses to overwrite an existing evidence file.

Review the two evidence documents before changing either release gate to
`passed`. A build record alone cannot satisfy first boot, and an emulator boot
record cannot satisfy any Pixel 9a gate.
