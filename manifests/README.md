# Repo integration

This Git repository is intended to appear at `vendor/aios` inside an AOSP
checkout. Copy `local_manifest.xml.example` to the AOSP checkout's
`.repo/local_manifests/aios.xml` and copy `launcher3-android17.xml.example` to
`.repo/local_manifests/aios-launcher.xml` before syncing. The first manifest
points at the canonical public AIOS repository. The second replaces either the
AOSP or GrapheneOS Launcher3 project with Google's exact Android 17 release tag;
see `docs/launcher.md` for the isolated Android 18 rebase procedure.

Do not add model weights or Pixel vendor binaries to this manifest. Both are
licensed local build inputs handled outside source control.

After every successful `repo sync`, record and validate an immutable manifest
for the selected build lane with:

```text
vendor/aios/scripts/capture-aosp-lock.sh \
  /absolute/aosp-root \
  android_latest_integration \
  /absolute/release-artifacts/run-id
```

This emits the resolved XML plus a digest-bearing JSON contract and refuses
symbolic project revisions, missing lane projects, dirty tracked AIOS sources,
or evidence overwrite. The moving `android-latest-release` name is useful for
integration, not reproducibility.

The same latest-release checkout supports `android_avd_integration` and
`android_gsi_arm64`; pass the exact lane used to the lock command. The Pixel 9a
full-device lane uses the separately pinned and signature-verified GrapheneOS
release manifest. Run `adevtool generate-all -d tegu` before capturing its final
lock. A GSI lock proves only the generic ARM64 source set, not compatibility with
a phone.
