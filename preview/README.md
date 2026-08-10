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

With an API-35+ emulator running, `scripts/emulator-media-smoke.ps1` installs the
compile-check APK, records a unique temporary MP4, discovers its canonical
MediaStore URI, and runs the real no-reencode mux/read plus export-recovery
fixtures. The script rejects non-emulator serials, marks its JSON as non-physical
evidence, does not exercise a subtitle renderer, and removes the APK, source,
derived rows, journals, and cached output when it finishes.

`telecomsmoke` packages those same sources as `com.aios.phone` solely for an
AOSP emulator. It is debug-signed, cannot use the signature-protected AIOS
services, and must never be installed on a physical phone or treated as a
release artifact. Its purpose is to verify the real `ROLE_DIALER` and
`InCallService` callback path. The current emulator console no longer exposes
the legacy `gsm call` command, so a debug-source-set-only managed
`ConnectionService` injects a synthetic call through Android Telecom. The
fixture checks for emulator hardware at runtime and is absent from the product
and `prodcheck` builds.

`scripts/emulator-telecom-smoke.ps1` binds its ignored JSON evidence to the
exact APK size/SHA-256 and refuses API levels below 35, physical serials, QEMU
misreports, or an existing `com.aios.phone` installation. A passing run also
proves the synthetic call and account were removed, the original dialer role and
screen state were restored, the temporary APK was uninstalled unless the
explicit `-KeepInstalled` debugging switch was used, and the remote screenshot
was erased. It remains explicitly non-physical evidence.

The runner also drives the labeled production Compose controls. **Ignore** must
retain the ringing call on the silent channel, **Answer** must produce a live
Telecom call owned by the `phoneCall` foreground `AiosInCallService`, and
**Decline** must remove a second managed call. The **AI** action remains disabled
because an emulator cannot satisfy the caller-audio release gate.

For the outgoing path, the runner temporarily selects the fixture account,
opens AIOS Phone through a standard `ACTION_DIAL` intent, activates **Call**,
verifies `DIALING` then `ACTIVE` in Telecom and the automatic in-call surface,
round-trips **Mute**/**Unmute** and **Hold**/**Resume**, and opens **Keypad** to
verify one bounded `5` DTMF pulse. The debug fixture records only its stop/play/
stop callbacks in app-private cache; the runner deletes that audit and asserts
it is absent before activating **End call**. The exact original outgoing account
is restored in cleanup, including the no-account case. These checks prove
Telecom wiring, not carrier-side DTMF reception or physical call audio.

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
