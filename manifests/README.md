# Repo integration

This Git repository is intended to appear at `vendor/aios` inside an AOSP
checkout. Once the project has a real Git remote, copy
`local_manifest.xml.example` to the AOSP checkout's
`.repo/local_manifests/aios.xml`, replace the invalid placeholder remote, and
sync.

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
