# AIOS Home

AIOS Home is a narrow derivative of AOSP Launcher3. Its upstream is the exact
Google `android-17.0.0_r1` tag, resolved to project commit
`c612e6ece389f21c40f8cb9cd9a4b44239f00009`. The Pixel device-support manifest
remains independently pinned; Launcher3 is replaced by the official AOSP
project through `manifests/launcher3-android17.xml.example`.

The launcher is not vendored into `vendor/aios`. AIOS changes live in
`patches/0004-launcher3-aios-home.patch`, and both build-lane patch queues bind
that patch to the immutable upstream commit and its SHA-256. The initial topic:

- brands the launcher as **AIOS Home** without changing its package identity;
- retains Launcher3 Quickstep, Recents, gestures, widgets, and workspace data;
- places the configured Android assistant in the center of a new 5-column
  phone hotseat, with the stock Maps/Music choices as safe fallbacks; and
- does not link Launcher3 directly to Model Broker or model implementation APIs.

AIOS Phone and Messaging use adaptive icons with monochrome layers. This avoids
the legacy icon being wrapped in a second white circle and lets Android's
Material You themed-icon pipeline color the glyphs consistently. Third-party
and inherited system applications retain their own artwork; AIOS does not copy
Google's proprietary Pixel application icons.

Full-color adaptive icons are the AIOS default. Launcher3's optional themed-icon
preference remains off for a fresh profile, as it is in the Android 17 base;
the monochrome layers are used only if the owner explicitly enables that style.
The framework defaults also assign both Dialer and SMS roles to the AIOS apps on
a fresh user so inherited Graphene communication icons are not placed instead.

The workspace change only affects a newly created launcher database. Existing
users keep their chosen layout across an update.

## Checkout and build

Copy both manifest examples into `.repo/local_manifests/` before `repo sync`:

```text
cp vendor/aios/manifests/local_manifest.xml.example \
  .repo/local_manifests/aios.xml
cp vendor/aios/manifests/launcher3-android17.xml.example \
  .repo/local_manifests/aios-launcher.xml
repo sync packages/apps/Launcher3 vendor/aios
```

Then use the normal transactional module builder. It verifies the checkout is
at the configured base, applies the digest-bound patch, builds, and restores the
clean upstream tree:

```text
vendor/aios/scripts/build-aosp-modules.sh \
  /absolute/aosp-root pixel9a_tegu_hardware Launcher3QuickStep
```

## Android 18 update

Launcher upgrades are deliberate, reviewable rebases rather than merges of a
long-lived fork:

1. Change only the launcher manifest revision to the official
   `android-18.0.0_r1` tag and sync Launcher3.
2. Verify the resolved project commit and record the tag and commit in
   `config/aosp_tracking.json`.
3. Reapply the two-file AIOS topic. If either hunk conflicts, reproduce its
   behavior using Android 18's native extension points instead of copying the
   Android 17 implementation.
4. Export the refreshed patch, update its digest and base revision in both
   patch queues, and run the repository validation suite.
5. Build `Launcher3QuickStep` in the Android integration lane and the pinned
   Pixel lane. Exercise clean-profile setup, existing-workspace migration,
   Recents, gesture navigation, widgets, rotation, and assistant fallback.
6. Commit the manifest tag, resolved commit, patch, validation, and build
   evidence together.

No model weights, inference runtime, or AIOS service implementation belongs in
Launcher3. The launcher should depend on stable Android intents or a small AIOS
contract so an Android rebase never drags model code through SystemUI/Quickstep.

The first Pixel 9a compile, boot, role-resolution, and colored-icon smoke result
is recorded in `evidence/launcher/pixel9a-aios-home-20260817.json`. It is
developer overlayfs evidence, not a substitute for a signed image or OTA gate.
