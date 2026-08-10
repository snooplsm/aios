# AIOS Phone visual preview

This Android Studio project renders the same immutable phone contract, Compose
theme, home/recents/voicemail screens, multi-call screen, RTT and negotiated-video
states, transcript/risk surface, audio endpoint controls, and assistant settings
used by the AOSP product module.

`app` is deliberately harmless: it declares no call permissions, does not
implement `InCallService`, cannot request the dialer role, and uses simulated
call state. It can be installed on a stock phone for visual iteration without
replacing that phone's dialer.

`prodcheck` compiles, tests, assembles, and lints the complete production Phone
source/test closure, real role-capable manifest/resources, Call Intelligence
AIDL, and Communication Context API/AIDL against the installed public Android
SDK. It also enforces that the per-install address salt, call-context ledger,
revision clock, AI settings, and UI preferences cannot migrate through backup or
device transfer. It must not be installed. The authoritative product build and
host-test target remain the platform-signed Soong modules inside the locked AOSP
tree.

`messagingcheck` compiles, tests, assembles, and lints the real Messaging
manifest/resources, every shared Kotlin/Compose source and test, both context
Binder APIs, and every public-SDK-compatible platform helper. This includes the
durable MMS operation store and bounded photo transcoder. Only the production
factory and `PlatformMmsTransport` are excluded because they link AOSP's internal
MMS PDU sources; a source-set-local factory always rejects MMS instead. The lane
also verifies that provider-bound journals and context ledgers cannot migrate in
backup or device transfer. It cannot replace the Soong or physical carrier gate.

`callcontextcheck` stages every Communication Context service/API source, AIDL
contract, pure test, production resource, and the real application manifest. It
compiles, tests, assembles, and lints the complete opaque-identity, contact-
membership, revision/tombstone, retrieval, retention, and boot-cleanup boundary.
Call-side context clients are covered by the full `callservicecheck` lane below,
so this module has no duplicate partial Call Intelligence source list. It is a
compile check, not a replacement for the platform Soong or device gates.

`callservicecheck` stages every production Call Intelligence Java source and all
three Binder APIs, then compiles, tests, assembles, and lints the complete service.
Its sole substitute is a source-set-local `CallProductProperties` adapter that
always returns `false`; the AOSP app continues to compile the production adapter
against `android.os.SystemProperties`. The compile-check APK must not be shipped
or used as evidence that privileged capture works on a Pixel.

`modelservicecheck` stages every Model Broker Java source plus the model and
runtime-provider AIDL contracts. It compiles, tests, assembles, and lints the
complete shared inference boundary. Its local `BrokerProductProperties` adapter
always reports a non-debuggable build, so research-only admission cannot be
enabled by the compile-check APK. Production continues to read the immutable
`ro.debuggable` property through the platform adapter.

`mediascancheck` stages every production Media Intelligence Java source, test,
Binder contract, resource, and the real application manifest. Its debug overlay
only adds emulator fixtures for platform MP4 sample round trips plus attached-
pending deletion, insert-before-URI-attachment recovery, and published-output
preservation. The lane therefore compiles, tests, assembles, and lints the whole
MediaStore observer, inference, subtitle-index, metadata-write, enhanced-copy,
reader, and recovery boundary without duplicate implementation files. It also
checks that the deliberately privileged media permissions and no-backup policy
are explicit. Actual Pixel MP4 remux/playback and process-kill recovery remain
physical-device release gates.

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
  :callservicecheck:testDebugUnitTest :callservicecheck:lintDebug \
  :modelservicecheck:testDebugUnitTest :modelservicecheck:lintDebug \
  :mediascancheck:testDebugUnitTest :mediascancheck:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aios.phone.preview/.PreviewActivity
```

The preview follows the device theme by default and also provides explicit
Light and Dark selections in Settings. Simulated voicemail never opens a real
provider URI, and simulated video surfaces never access the camera.
