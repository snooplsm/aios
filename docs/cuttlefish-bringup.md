# Cuttlefish integration bring-up

The `android_latest_integration` lane continuously proves that the additive
AIOS product still builds and boots on the Cuttlefish device tree in Google's
moving Android release. It is virtual x86-64 evidence and cannot satisfy Pixel,
modem, camera, ARM runtime, Tensor accelerator, or model-performance gates.

Build the image and its immutable evidence record from the AOSP checkout:

```text
vendor/aios/scripts/build-aosp-lane.sh \
  /absolute/path/to/aosp \
  android_latest_integration \
  /safe/evidence/cuttlefish-build-id \
  12
```

The host must expose `/dev/kvm`, have Google's Cuttlefish host support installed,
and use the host tools built with the same source checkout. On a new Linux/WSL
host, install the official Artifact Registry packages and required group
membership, then restart the distribution so the membership is active:

```text
sudo vendor/aios/scripts/install-cuttlefish-host.sh "$USER"
```

Launch one clean instance after selecting the AIOS product:

```text
cd /absolute/path/to/aosp
source build/envsetup.sh
lunch aios_cf_x86_64_phone-aosp_current-userdebug
launch_cvd --daemon --report_anonymous_usage_stats=n
adb devices
```

Wait for `sys.boot_completed=1`. Bind the running virtual device to the exact
build record, using the local TCP serial reported by `adb devices`:

```text
python3 vendor/aios/tools/capture_cuttlefish_boot_evidence.py \
  --serial 0.0.0.0:6520 \
  --build-evidence \
    /safe/evidence/cuttlefish-build-id/soong-build-evidence.json \
  --output /safe/evidence/cuttlefish-build-id/cuttlefish-first-boot.json
```

The collector refuses USB and remote serials. It requires the exact product,
device, fingerprint, userdebug identity, AIOS version, privileged product APK
locations, and discoverable call-intelligence, context, and model-broker service
actions. It records only boot identity, package paths, resolved components, and
the digest of the immutable build evidence. It never promotes this virtual lane
to physical-runtime evidence.

Only after that JSON exists may
`integration.android_latest_first_boot` be marked `passed` with its evidence
path. Stop the instance using `stop_cvd` from the same selected build
environment.
