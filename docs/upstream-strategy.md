# Upstream and device strategy

## AOSP baseline

The integration branch follows the `android-latest-release` Repo manifest. As of
2026-08-10, that manifest points to `android17-release`. A separate lock file will
record the exact manifest revision used for each reproducible build; moving the
tracking branch never silently changes a released image.

The latest-release manifest does not include `device/google/tegu`. AIOS therefore
uses four non-interchangeable lanes recorded in `config/aosp_lanes.json`:

- `android_latest_integration` inherits the official Cuttlefish x86-64 phone and
  continuously compiles the full additive AIOS product against incoming AOSP.
  Its results can prove source/build compatibility but never a physical gate.
- `android_avd_integration` inherits AOSP's `sdk_phone64_x86_64` Goldfish product
  so the complete AIOS image can boot in the standard Android Emulator. An AVD
  hardware profile may resemble a Pixel 9a but does not emulate Tensor, modem,
  camera, accelerator, carrier, or thermal behavior.
- `android_gsi_arm64` inherits AOSP's official `gsi_arm64` product and keeps the
  additive AIOS system payload buildable against incoming AOSP. It is a generic
  research artifact, not the Pixel 9a release or flashing route.
- `pixel9a_tegu_hardware` is the physical product and release target. It pins a
  signed GrapheneOS release manifest and uses its `adevtool` pipeline to produce
  one coherent Pixel platform, generated device/vendor support, kernel,
  firmware, SELinux policy, target-files package, and factory image. AIOS remains
  an additive overlay and small reviewable patch queue on that pinned base.

The overlay repository is checked out at `vendor/aios`. Upstream projects are
not forked until a real patch is needed. Any necessary AOSP change lives as a
small topic branch plus an exported patch and contains:

- reason the public/privileged API is insufficient;
- upstream project and base revision;
- stable owner and removal condition;
- repository paths for regression tests that fail without the change;
- an exact, sorted list of every upstream path touched; and
- rebase notes.

These fields are not review prose alone. Patch queue schema version 2 is checked
both by the repository validator and by the transactional replay tool. Each tool
parses the patch's `diff --git` headers, rejects renames or implicit paths, and
requires an exact match with the declared footprint before touching an AOSP
checkout. The replay tool also verifies that its configured project directory is
the Git toplevel at the immutable recorded commit.

This makes an upstream refresh a manifest sync followed by an automated patch
replay and test run rather than a merge of a permanently modified AOSP tree.
`scripts/build-aosp-lane.sh` enforces that flow: it locks the resolved manifest,
applies only digest-verified patches at their exact bases, records the Soong log
and installed artifact digests, and reverses the staged patch transaction on
exit. Evidence capture requires every core AIOS application to be non-empty and
to match its size and SHA-256 entry in the current build's installed-file
manifest (`installed-files-product.json` for partition products,
`installed-files-system.json` for older GSIs, or the target-files archive for a
full physical-device build); a stale APK left in `out/` cannot satisfy a new
build. The evidence also records the inventory source digest. A rebase
conflict therefore stops before a build instead of becoming an unreviewed merge.
Build-evidence schema version 2 embeds the canonical review-complete queue and
its SHA-256 after independently rehashing every payload. Together with the clean
AIOS revision and raw series-file digest, that binds every owner, base, test,
note, removal condition, payload digest, and footprint to the built image.

The moving manifest ref is itself tracked, not merely the projects emitted by
`repo manifest -r`. `scripts/refresh-aosp-integration.sh` reruns `repo init`
against Google's official manifest repository and atomically updates the
reviewed branch, exact manifest-repository commit, and observation date in
`config/aosp_tracking.json`. That policy change is committed before `repo sync`.
Every integration lock then runs the same checker in `--check` mode and records
the manifest-repository commit beside the flattened-manifest and project-set
digests. A moving ref that has not been reviewed therefore stops before patch
replay or Soong; an old `out/` directory cannot make it appear current. Release
locks are never changed by the refresh command.

The overlay deliberately has no hosted continuous-integration or automation
workflows. Maintainers run the dependency-free repository validator, complete
host contract suite, release-status report, and explicit upstream tracking check
locally before committing. No remote watcher marks a Soong, emulator, or
physical-device gate passed; those require their own immutable evidence capture.

The release matrix keeps Cuttlefish, Android Emulator, and ARM64 GSI build gates
separate from the Pixel runtime gates. A green virtual build, AVD boot, or GSI
build demonstrates that the fork still follows upstream and forms the intended
artifact; it cannot be reused as evidence that a Pixel booted or preserved
telephony behavior. See `docs/emulator-bringup.md` and `docs/gsi-bringup.md`.

## Branches

- `main`: AIOS overlay code and documentation.
- `integration/android-latest`: regularly rebuilt against the moving AOSP latest
  release manifest.
- `release/android-<version>`: immutable AOSP manifest lock plus reviewed AIOS
  commits for a device release.
- `patch/<aosp-project>/<topic>`: minimal patches to individual upstream AOSP
  projects when unavoidable.

## Pixel 9a reality

Pixel 9a is codename `tegu`, has 8 GB RAM and Tensor G4. Google no longer ships
complete Pixel targets in the public Android 16/17 AOSP manifest. Our first
physical attempt incorrectly treated a generic Android 17 GSI plus the stock
Pixel vendor stack as if it were an equivalent device build; it bootlooped.

GrapheneOS independently maintains `tegu` as a production-ready target. Its
signed release manifests pin AOSP forks and kernel prebuilts, while `adevtool`
downloads and prepares matching factory/OTA inputs and generates missing device
support. AIOS pins release `2026080500` (verified manifest commit
`d1b2739828a783bbf9bd6ba5d50c727b9329b9b7`) for the next bring-up.

This creates two clean upstreams. Google's moving `android-latest-release`
remains the early API/build integration lane for Cuttlefish, the Android
Emulator, and generic GSI. A reviewed, signed GrapheneOS stable tag is the
production Pixel device base. AIOS code stays in `vendor/aios`, with unavoidable
platform changes in a digest-verified patch queue rebased separately for each
upstream lane.

Every full-device release keeps platform, generated vendor tree, kernel,
bootloader, radio, target-files, and signing-key identities together. Extracted
vendor or firmware inputs remain local and are never committed or redistributed.
A complete signed factory image and the physical test matrix are still required
before we claim telephony, camera, accelerator, or update support.

AIOS is an independent derivative and is not GrapheneOS or endorsed by the
GrapheneOS project. Each imported or forked project retains its upstream license
and notices. In particular, the pinned `adevtool` revision
`e87c5a26d045ab48ca0f9989dbe03367ba95f312` is MIT-licensed and its copyright
and permission notice must remain with redistributed copies or substantial
portions.

## Normal phone functionality

Core AOSP Telecom and telephony functionality is in scope. Google Phone, Pixel
Call Assist, Google Camera, RCS through Google services, and other proprietary
features are not inherited. VoLTE, VoWiFi, eSIM, emergency calling, Bluetooth,
and carrier switching are release gates because they span AOSP, vendor binaries,
modem firmware, carrier configuration, and sometimes proprietary services.

The first build is a research image, not a promise of commercial carrier
certification.

## Host requirement

AOSP builds run on supported 64-bit Linux. This repository can be edited and
validated on Windows, but `scripts/bootstrap-aosp.sh`, source synchronization,
Soong builds, image signing, and flashing validation run from a dedicated Linux
workstation or VM with hardware resources sized for current AOSP.
