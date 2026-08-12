# Repo integration

This Git repository is intended to appear at `vendor/aios` inside an AOSP
checkout. Copy `local_manifest.xml.example` to the AOSP checkout's
`.repo/local_manifests/aios.xml` and sync. The example points at the canonical
public AIOS repository; a private development mirror should override only that
remote while retaining the same project path.

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
