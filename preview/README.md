# AIOS Phone visual preview

This Android Studio project renders the same immutable phone contract, Compose
theme, home/recents/voicemail screens, multi-call screen, RTT and negotiated-video
states, transcript/risk surface, audio endpoint controls, and assistant settings
used by the AOSP product module.

`app` is deliberately harmless: it declares no call permissions, does not
implement `InCallService`, cannot request the dialer role, and uses simulated
call state. It can be installed on a stock phone for visual iteration without
replacing that phone's dialer.

`prodcheck` compiles and lints the actual `apps/phone` sources and Call
Intelligence AIDL against the installed public Android SDK. Its local unit-test
source set also runs the Android-free dialer policy state machines. It must not
be installed. The authoritative product build and host-test target remain the
platform-signed Soong modules inside the locked AOSP tree.

`callcontextcheck` stages the public-SDK Call Intelligence context client, the
communication-context service/store and API, and their pure tests into generated
build directories. It compiles both Binder sides, runs policy, revision,
formatter, and accumulator tests, assembles, and lints without maintaining
duplicate production source files.
It is a compile check, not a replacement for the platform Soong or device gates.

`mediascancheck` stages the production MediaStore generation scanner, durable
queue/store, and pure cursor/policy tests. It proves the missed-media recovery
path compiles against the public Android SDK without duplicating source files.

`telecomsmoke` packages those same sources as `com.aios.phone` solely for an
AOSP emulator. It is debug-signed, cannot use the signature-protected AIOS
services, and must never be installed on a physical phone or treated as a
release artifact. Its purpose is to verify the real `ROLE_DIALER` and
`InCallService` callback path. The current emulator console no longer exposes
the legacy `gsm call` command, so a debug-source-set-only managed
`ConnectionService` injects a synthetic call through Android Telecom. The
fixture checks for emulator hardware at runtime and is absent from the product
and `prodcheck` builds.

Open this directory in Android Studio, or run:

```text
gradle :app:assembleDebug :prodcheck:testDebugUnitTest :prodcheck:lintDebug \
  :callcontextcheck:testDebugUnitTest :callcontextcheck:lintDebug \
  :mediascancheck:testDebugUnitTest :mediascancheck:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aios.phone.preview/.PreviewActivity
```

The preview follows the device theme by default and also provides explicit
Light and Dark selections in Settings. Simulated voicemail never opens a real
provider URI, and simulated video surfaces never access the camera.
