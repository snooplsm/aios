# Android Emulator system-image bring-up

AIOS has a standard Android Emulator lane in addition to Cuttlefish. The
`android_avd_integration` lane wraps AOSP's `sdk_phone_x86_64` product with the
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
