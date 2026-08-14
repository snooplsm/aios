# Development defaults

AIOS development images default to visible Developer options and authenticated
USB debugging so a freshly wiped research Pixel remains reachable during bring-
up. This behavior is controlled by one product-build flag:

```text
AIOS_ENABLE_DEVELOPER_DEFAULTS=true
```

The default is `true` only for `eng` and `userdebug`, and `false` for `user`.
Explicitly forcing it to `true` on a `user` build is a build error. Set it to
`false` before lunch/build to produce a debug image with ordinary Android
defaults.

When enabled, the product includes the platform-signed
`AiosDeveloperDefaults` direct-boot receiver and publishes
`ro.aios.developer_defaults=true`. At locked boot and normal boot, the receiver
requires an `eng`/`userdebug` `Build.TYPE` and the product property before setting
`Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` and `Settings.Global.ADB_ENABLED`
to `1`. Either missing condition is a no-op.

The feature never sets or weakens `ro.adb.secure`, never installs on a normal
production image, and does not bypass the host-key authorization dialog. It is
intended for personally owned unlocked development Pixels, not a shipping
consumer default. Changing the flag requires rebuilding and reflashing; it does
not mutate an already installed image.

## Local instant provisioning

An optional second debug gate skips OS onboarding, enables GPS, Wi-Fi scanning,
the pinned privacy-proxy network-location provider, and the pinned geocoder,
then seeds a local WPA-PSK network. Its Wi-Fi passphrase is never stored in this
public repository. Generate the ignored resource overlay in the Android checkout:

```text
export AIOS_DEBUG_WIFI_PSK='<local passphrase>'
python3 vendor/aios/tools/configure_debug_provisioning.py \
  --ssid '<local SSID>' \
  --output vendor/aios/generated/debugprovisioning
```

When that overlay exists, `AIOS_ENABLE_INSTANT_PROVISIONING` defaults to `true`
for `eng` and `userdebug`; otherwise it defaults to `false`. Forcing it on in a
`user` build is a hard error. The generated overlay is embedded in the image, so
debug images containing it must not be published or shared.
