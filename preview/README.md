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

Its debug source set adds one QEMU-guarded provider audit/cleanup activity that
is absent from the product. The host-only `emulatorcontrol` module is a minimal
authenticated gRPC client for the Android Emulator's `sendSms` endpoint; it
reads the emulator discovery file without printing its bearer token.
`scripts/emulator-messaging-smoke.ps1` refuses physical serials, existing fixture
installs, API levels below 35, and QEMU misreports. It temporarily owns the SMS
role, verifies modem-to-`SMS_DELIVER`-to-provider-to-Compose behavior, drives the
production Send button, recognizes the emulator radio's exact sent-plus-inbox
loopback shape, and then removes the tokenized rows, private audit, APK, and
remote artifacts while restoring the original role. Its ignored JSON explicitly
sets carrier, MMS, multi-SIM, and physical evidence to false.

`callcontextcheck` stages every Communication Context service/API source, AIDL
contract, pure test, production resource, and the real application manifest. It
compiles, tests, assembles, and lints the complete opaque-identity, contact-
membership, revision/tombstone, retrieval, retention, and boot-cleanup boundary.
Call-side context clients are covered by the full `callservicecheck` lane below,
so this module has no duplicate partial Call Intelligence source list. It is a
compile check, not a replacement for the platform Soong or device gates.

Its debug-only fixture can be driven by
`scripts/emulator-context-lifecycle-smoke.ps1`. On API-35+ QEMU it binds the
production AIDL service as an authorized Call Intelligence client, resolves
stable opaque identities, and exercises the real SQLite/FTS store across SMS,
MMS, call-event, expiring call-artifact, and photo-metadata rows. It verifies
bounded/source-scoped retrieval, revision replacement, tombstones, bulk-delete
watermarks, 24-hour expiry, absence of raw caller addresses, and cleanup of its
private state. The runner refuses to replace any existing package and leaves no
APK installed. This is Android service/storage evidence, not physical-Pixel or
cross-package signature-grant evidence.

`callservicecheck` stages every production Call Intelligence Java source and all
three Binder APIs, then compiles, tests, assembles, and lints the complete service.
Its sole substitute is a source-set-local `CallProductProperties` adapter that
always returns `false`; the AOSP app continues to compile the production adapter
against `android.os.SystemProperties`. The compile-check APK must not be shipped
or used as evidence that privileged capture works on a Pixel.

Its debug-only retention activity can also be driven by
`scripts/emulator-call-retention-smoke.ps1`. On API-35+ QEMU it creates the real
private RX/TX, transcript, assessment, and assistant artifacts; proves the exact
24-hour deadline; deletes an expired active writer and unreadable metadata;
preserves a fresh resumed call without extending its deadline; exercises the
nearest alarm and empty-store cancellation; and leaves no private artifact or
APK installed. This is Android storage/alarm evidence, not call-audio or physical
Pixel evidence.

`modelservicecheck` stages every Model Broker Java source plus the model and
runtime-provider AIDL contracts. It compiles, tests, assembles, and lints the
complete shared inference boundary. Its local `BrokerProductProperties` adapter
always reports a non-debuggable build, so research-only admission cannot be
enabled by the compile-check APK. Production continues to read the immutable
`ro.debuggable` property through the platform adapter.

Its debug-only fixture can be driven by
`scripts/emulator-model-admission-smoke.ps1`. On API-35+ QEMU it binds the real
Broker service and proves that a stock install without `/product/etc/aios`
remains model-free. Separately, temporary non-model bytes exercise the production
SHA-256 verifier, canonical model-directory confinement, RAM-tier catalog,
release device/build-fingerprint admission, debug research gating, and exact
benchmark-client quota. The runner refuses to replace an existing package and
removes every temporary byte and APK. It does not claim real inference, runtime-
provider activation, AVB protection, or physical-Pixel admission evidence.

`runtimeprovidercheck` is the separate broker-identity client for
`scripts/emulator-runtime-provider-smoke.ps1`. The runner installs the actual
LiteRT-LM provider APK beside that client on an x86_64 API-35+ emulator. Across a
real secondary-process Binder boundary it verifies provider/version/backend
identity, signature-permission rejection of the shell, invalid request typing,
backend allowlisting, `/product/etc/aios/models` confinement, one bounded and
path-redacted terminal callback, and provider survival. Its input is disposable
plain text; real inference, weights, arm64, and physical hardware remain false.
Build the provider first with its required Gradle 8.11.1 offline/strict command,
then build `:runtimeprovidercheck:assembleDebug`; the runner hashes both exact
APKs before installation and records those digests in ignored local evidence.

`mediascancheck` stages every production Media Intelligence Java source, test,
Binder contract, resource, and the real application manifest. Its debug overlay
adds emulator fixtures for the production capture-grouping, 80%-while-charging,
active-call-preemption, and Android `JobInfo` policies; platform MP4 sample round
trips; attached-pending deletion; insert-before-URI-attachment recovery; and
published-output preservation. The lane therefore compiles, tests, assembles,
and lints the whole MediaStore observer, inference, subtitle-index,
metadata-write, enhanced-copy, reader, and recovery boundary without duplicate
implementation files. It also checks that the deliberately privileged media
permissions and no-backup policy are explicit. Actual Pixel MP4 remux/playback
and process-kill recovery remain physical-device release gates.

With an API-35+ emulator running, `scripts/emulator-media-smoke.ps1` installs the
compile-check APK and executes those production policy paths. It also baselines a
historical image, stops the app, creates the first frame of a burst, restarts the
real observer, creates a second frame, and verifies both frames are deferred
together without importing the historical image. The runner then records a
unique temporary MP4, discovers its canonical MediaStore URI, and runs the real
no-reencode mux/read plus export-recovery fixtures. The script rejects
non-emulator serials, marks its JSON as non-physical evidence, does not exercise
a subtitle renderer, and removes the APK, source, derived rows, journals, and
cached output when it finishes.

`telecomsmoke` packages those same sources as `com.aios.phone` solely for an
AOSP emulator. It is debug-signed and must never be installed on a physical
phone or treated as a release artifact. Its purpose is to verify the real `ROLE_DIALER` and
`InCallService` callback path. The current emulator console no longer exposes
the legacy `gsm call` command, so a debug-source-set-only managed
`ConnectionService` injects a synthetic call through Android Telecom. The
fixture checks for emulator hardware at runtime and is absent from the product
and `prodcheck` builds.

`callassistantsmoke` is its separate, same-debug-signature Binder peer under the
production `com.aios.callintelligence` identity. It stages the production answer
scope/delay policy classes while deliberately implementing no capture, model,
ASR, TTS, or caller-uplink path. Its service returns no binder on physical
hardware and its control activity also refuses non-emulators. This lets the real
Phone client and Telecom mutation exercise controlled automatic-answer decisions
without weakening the production image's caller-audio admission gate.

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

In its controlled companion phase, the same runner verifies fixed 1/2/3/4-second
and inclusive-random 1.01–3.99-second decisions through the production Handler
timer into exactly one managed `Connection.onAnswer`. Owner **Answer** and
**Decline** must revoke the pending reservation, while **Ignore** preserves it.
Service replacement must invalidate the old deadline and give a reconnected
decision a fresh complete delay. A synthetic emergency presentation must never
reach policy evaluation. `-AutomaticAnswerOnly` runs this matrix separately and
records unexecuted baseline fields as null; neither mode is physical/carrier or
real caller-audio evidence.

The focused matrix first drives the production Compose Settings screen rather
than configuring the service fixture directly: it enables processing and
automatic answer, chooses every non-emergency call with a three-second delay,
and saves. The companion must audit the exact Binder policy update. After the
companion is force-stopped, the next Telecom call must reload that persisted
policy and answer once after the full selected delay. UI hierarchy dumps are
bounded and retried, and repeated SHOW intents rely on control discovery rather
than `am start -W`, so a constrained AVD cannot silently strand cleanup behind
an Android activity-manager wait.

For the outgoing path, the runner temporarily selects the fixture account,
opens AIOS Phone through a standard `ACTION_DIAL` intent, activates **Call**,
verifies `DIALING` then `ACTIVE` in Telecom and the automatic in-call surface,
round-trips **Mute**/**Unmute** and **Hold**/**Resume**, and opens **Keypad** to
verify one bounded `5` DTMF pulse. The debug fixture records only its stop/play/
stop callbacks in app-private cache; the runner deletes that audit and asserts
it is absent before activating **End call**. The exact original outgoing account
is restored in cleanup, including the no-account case. These checks prove
Telecom wiring, not carrier-side DTMF reception or physical call audio.

The fixture also emits a post-dial wait containing a unique remaining sequence.
The production surface must show generic **Continue** and **Cancel** controls
without exposing that sequence, and both choices must reach
`onPostDialContinue`. The callback audit is deleted before success; carrier/PBX
post-dial delivery remains a physical gate.

The same outgoing connection is retained while a second fixture call rings. A
pass requires Compose to select the waiting call, answering it to hold the first
connection, **Merge calls** to reach the managed `onConference` callback, and
**Separate call** to reach `onSeparate`; both participants are then ended from
the production surface. This is deterministic multi-call wiring evidence, not a
substitute for carrier call-waiting or conference testing.

A final transaction enables a second emulator-only PhoneAccount and clears the
temporary outgoing default. Telecom must enter `SELECT_PHONE_ACCOUNT`, the
production Compose surface must offer both fixture labels, and selecting the
secondary label must deliver its exact handle to `onCreateOutgoingConnection`.
The original outgoing account is restored afterward. This is account-chooser
wiring evidence only, not physical dual-SIM/eSIM evidence.

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

For rapid visual testing on an API-35+ emulator, use the emulator-only launcher.
It builds and installs only the harmless preview package, opens one deterministic
screen, verifies the expected Compose content, and saves an ignored screenshot:

```powershell
./scripts/show-phone-preview.ps1 -Scenario incoming -Theme dark
./scripts/show-phone-preview.ps1 -Scenario incoming-spam -Theme dark
./scripts/show-phone-preview.ps1 -Scenario active-ai -Theme light
./scripts/show-phone-preview.ps1 -Scenario active-spanish -Theme light
./scripts/show-phone-preview.ps1 -Scenario recents -SkipBuild
```

Supported scenarios are `home`, `recents`, `voicemail`, `settings`, `incoming`,
`incoming-spam`, `active-ai`, and `active-spanish`. The incoming visual scenarios
deliberately enable the **AI** button so its transition can be designed;
`incoming-spam` renders the production high-risk classifier surface and
`active-spanish` renders a complete Spanish caller/assistant transcript. These
are simulated UI states and do not claim stock-Android caller-audio access. The
real emulator Telecom smoke test continues to fail the AI action closed until
the physical caller-uplink gate passes.

The preview follows the device theme by default and also provides explicit
Light and Dark selections in Settings. Simulated voicemail never opens a real
provider URI, and simulated video surfaces never access the camera.

The same harmless APK now exposes the production AIOS Messages Compose screen
with deterministic inbox, conversation, and cross-app context states:

```powershell
./scripts/show-messages-preview.ps1 -Scenario inbox -Theme dark
./scripts/show-messages-preview.ps1 -Scenario conversation -Theme light
./scripts/show-messages-preview.ps1 -Scenario context-photo -Theme light
```

`conversation` shows the phone and photo snippets retrieved for one customer;
`context-photo` also stages a photo and message draft so the MMS composer can be
designed directly. Calls, role changes, carrier sends, and the Photo Picker are
simulated inside this preview. The separate Messaging smoke runner remains the
evidence for production SMS-role, provider, receiver, and composer wiring.
