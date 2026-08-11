# AIOS Phone integration

## Product application

AIOS Phone is an original Apache-2.0 Kotlin/Jetpack Compose application in
`apps/phone`. It implements Android's dialer role using `InCallService` and the
public/privileged Telecom surface available to an AOSP product app. Compose owns
presentation only; mutable framework `Call` objects remain inside a main-thread
registry.

The application uses unidirectional data flow:

```text
Telecom callbacks -> CallRegistry -> immutable PhoneUiState -> Compose
Compose event -> PhoneAction -> PhoneRuntime controller -> Telecom mutation
```

The registry maintains an identity map for every simultaneous `Call`. Its UI
snapshots include parent, children, conferenceable peers, capabilities,
properties, direction, video state, and account-independent opaque IDs. Actions
always target an ID. There is intentionally no global `currentCall` variable.

AIOS Phone supports call waiting, conferences, DTMF, hold, mute, multi-SIM
selection, post-dial waits, proximity blanking, and Android's modern
`CallEndpoint` routing API. Incoming and ongoing notifications use distinct
ringing, silenced, and ongoing `Notification.CallStyle` channels; incoming calls
can present the full-screen in-call UI. The home, in-call, and settings surfaces
share one UDF store and support system, light, and dark appearance.

The ongoing notification consumes the same bounded transcript UDF state as the
call screen. It shows the newest incoming partial/final speech as a normalized,
160-character caller preview, keeps AI/risk state as secondary text, uses a
private lock-screen channel, and updates with `onlyAlertOnce`. Ringing
notifications never include transcript text. Phone coalesces partial-ASR
notification refreshes to a 350 ms cadence while continuing to reduce every
segment immediately into the Compose state. Downlink and uplink each own one
replaceable provisional row, so a cumulative caller revision still replaces its
turn when an owner-speech callback arrived between the two caller callbacks. A
final revision replaces that same row; the next turn appends a new bounded row.

RTT uses `Call.RttCall` behind a single serialized worker. The UI receives only
bounded local and remote text snapshots. It supports RTT-at-dial-time, mid-call
requests, remote request acceptance, and explicit termination. A failed or
closed RTT stream is detached instead of polling indefinitely.

Video calls use Telecom session-modification requests and responses. Mutable
`VideoCall` and `Surface` instances remain in `CallRegistry`; Compose contributes
short-lived remote and local `SurfaceView` surfaces through typed effects.
Outgoing camera transmission requires a runtime camera grant, while answering a
video invitation always offers an audio-only choice. Video capability and
physical carrier interoperability remain release-gated.

Recents are a bounded read-only projection. Voicemail is separately projected
from `VoicemailContract`, filters OMTP rows to the active visual-voicemail source,
and exposes opaque UI identifiers. Playback streams the provider URI directly;
AIOS does not copy voicemail audio into its storage. Missing content is fetched
only after an explicit owner action, targeted to the recorded source package.

## Safe transition and emergency behavior

`AiosFrameworkDefaultsOverlay` is a platform-signed, product-specific static
resource overlay targeting `android`. It sets `config_defaultDialer` to
`com.aios.phone`, so a fresh AIOS user receives AIOS Phone through Android's
ordinary dialer-role initialization. This is product configuration rather than
a framework or Permission Controller patch. The owner can still replace AIOS
Phone through the standard `ROLE_DIALER` flow.

The upstream AOSP Dialer remains installed as an owner-selectable recovery
alternative, but it is not described as an automatic emergency fallback once
AIOS Phone is preloaded. AIOS Phone excludes emergency calls from every AI path;
the end-to-end emergency UI and routing behavior must pass the controlled
physical-device gate before release.

### Locked boot

AIOS Phone declares only the components required for a live call as Direct Boot
aware: `AiosInCallService`, `InCallActivity`, and notification actions. While
that process is alive it dynamically registers for `ACTION_USER_UNLOCKED`, as
required by Android; home and settings activities remain credential-gated. Before
the first unlock after reboot, the owner must still be able to answer, decline,
ignore, hang up, and use ordinary Telecom call controls; AI answering is
unavailable because Call Intelligence and Model Broker are intentionally not
Direct Boot aware.

The Phone process stores only theme and role-prompt preferences in
device-encrypted storage. Existing credential-encrypted UI preferences migrate
once after unlock, without replacing values already written to the device store.
Call logs, contact-derived context, assistant policy, transcripts, audio, model
state, and reconciliation secrets remain credential-encrypted. The unlock
broadcast initializes the deferred context client and retries the optional AI
binding immediately while preserving every live `Call` identity.

The exact Android 17 AOSP Dialer topic in `patches/` remains a temporary bridge
for Call Intelligence while AIOS Phone is under validation. It should be removed
after AIOS Phone passes role selection, incoming/outgoing, emergency routing,
call waiting, conference, Bluetooth/audio endpoint, DTMF, RTT, video, voicemail,
VoLTE, VoWiFi, eSIM, and AI-service-crash gates. Until then, AOSP Dialer and AIOS Phone may both use
the narrow `aios_call_api`; only the selected role holder receives live Telecom
calls.

Both implementations publish the full Telecom call set—not only calls selected
for AI processing—through `setTelecomCallPresent`. The process-owned Binder token
makes the assertion self-cleaning on dialer death, while opaque per-call IDs keep
ringing, outgoing, active, waiting, held, and conference calls independently
balanced. A Call Intelligence rebind replays the current set before new policy
requests. This call-presence signal is what preempts media inference; capture and
transcription sessions remain optional work within that protected interval.
AIOS Phone uses generation-specific service connections, bounded exponential
retry, and a 15-second no-callback watchdog. Terminal/null bindings are unbound;
an ordinary process disconnect is first left for Android to reconnect. Every
disconnect revokes a pending AI-answer timer. Once the replacement service has
registered its listener and loaded policy, the Phone queues full presence replay
before `onCallResumed` for each active call, restarting capture without replaying
the receptionist greeting.
Each connection registers a distinct listener. Phone accepts transcript, risk,
assistant-state, status, policy, takeover, and incoming-decision completions only
from the currently active connection and service proxy. An explicit remote-call
failure immediately replaces that exact binding instead of retaining a dead
proxy until the watchdog expires. Because Call Intelligence wire revisions begin
again after a process restart, Phone maps each generation's risk and assistant
revisions onto one monotonic owner-visible sequence. The Compose reducer can
therefore retain the live call UI while accepting fresh state from the restarted
service without admitting a late callback from its predecessor.
Call IDs cannot be claimed by two different UIDs. Evaluation and capture require
the calling UID to own the live call, and each capture session retains that UID
for takeover and terminal cleanup. The dialer asks Call Intelligence to finalize
the artifact before releasing its presence assertion; Binder death remains the
fallback that clears a crashed dialer's full presence set. When the final token
for a call dies, Call Intelligence first removes the session from callback
routing, then closes capture/ASR/caller audio and ends classifier, receptionist,
and pending context work. It leaves any non-emergency partial artifact under the
original 24-hour maximum rather than pretending the carrier call disconnected.
Telecom itself remains connected and a restarted dialer can replay the live call.
The new capture streams append to the same opaque artifact directory and reuse
its original creation/expiry timestamps; a restart cannot refresh the TTL.
The same orphan check runs for an explicit `present=false` assertion. Releasing
one of several same-UID tokens leaves the call running; releasing the final
token stops any unfinished AI work. In the normal terminal sequence,
`onCallEnded` finalizes/indexes the call first, so the following release is an
idempotent presence update and does not delete that finalized context.
After both directions pass the first-PCM startup gate, a capture thread is not
allowed to disappear silently. The first unexpected read or authoritative-sink
failure is identity-checked against the current call session, transitions an
AI-handled call to owner handling, closes only optional AI work, and reports a
bounded failure to Phone. Phone keeps the Telecom surface and call controls
alive and tells the owner processing stopped. Intentional teardown and a stale
failure from an already-replaced session cannot affect the current call.
Call IDs are stable only for Telecom correlation; they are not callback
capabilities. Call Intelligence binds ASR, classifier, receptionist, prior-context,
TTS/uplink, and status callbacks to the exact session/request generation. A
replayed live call therefore cannot consume queued output from the pre-crash
session, even when both sessions use the same call ID and artifact directory.

## Live call assessment

Call Intelligence sends a typed `CallRiskAssessment` rather than a display
string. It carries a bounded label/reason/source contract plus a monotonic
per-call revision and observation time. The service persists the same revision
with the local assessment artifact, publishes the initial known-contact or
unknown state when capture starts, and replays the latest value to a newly
registered listener.

AIOS Phone rejects unknown labels, inconsistent score ranges, malformed reason
codes, invalid sources, and non-positive revisions. Its UDF reducer accepts only
a revision newer than the one already displayed. Compose converts the safe
typed value into a light/dark-theme-aware card headed **Likely legitimate**,
**Still evaluating**, **Suspicious call**, or **High-risk call**; raw model reason
codes are never shown to the owner. Classification is advisory and never invokes
a Telecom mutation.

Call Intelligence separately publishes a typed `CallAssistantState` containing
the opaque call ID, `aiHandling`, observation time, and a monotonic per-call
revision. It publishes the initial value with capture, persists each state in the
same 24-hour artifact boundary, and replays the latest value after listener
reconnection. AIOS Phone validates the value and reduces only newer revisions.
When `aiHandling=true`, the Compose in-call card and the ongoing notification
offer **Take over**. Both dispatch the same typed UDF action and signature-
protected Binder transaction. Success stops current/queued assistant speech and
switches subsequent caller turns to advisory classification while leaving the
carrier call, capture, ASR, and transcript UI running. This is deliberately not
a Telecom answer, hold, or disconnect mutation.

## Owner-selected automatic answer

Phone settings expose an opt-in **Auto AI answer** switch. When enabled, the
owner chooses **After I don't answer**, **Unknown callers**, or **Every non-
emergency call**. The ring-first mode offers explicit 5, 10, 15, 20, 30, 45,
and 60 second owner-ring intervals and answers only if Telecom still reports the
call as ringing. The direct-answer modes use the separately requested 1, 2, 3,
or 4 second delay or `Random`; random is sampled anew for each eligible call
from the inclusive 1,010–3,990 ms range. Both delay values are persisted by Call
Intelligence, which returns the applicable resolved delay with its call-handling
decision; the dialer never conflates or invents a separate delay.

Emergency calls and emergency callback mode always bypass AI. Automatic answer
also fails closed unless call processing is enabled and the service reports that
the caller-audio interaction transport is ready. The setting may be configured
in advance, but it cannot override those runtime safety gates.

Phone settings separately expose a default-off **Use caller history** switch.
Only when both call processing and this switch are enabled may Phone send the
presented address transiently to Call Intelligence for background retrieval.
Turning it off invalidates pending lookups and clears prepared history from
future receptionist turns in live sessions; it does not turn off transcription
or AI answering. Retrieval remains fail-open for Telecom and never delays answer
or capture. When enabled, three persistent switches independently admit
messages, previous-call/contact context, and carrier-confirmed sent-photo
descriptions. Disabled categories are excluded by the context-service query
before its eight-result limit. Narrowing a category also clears any already-
prepared prompt context immediately.

Emergency protection is direction-independent. AIOS Phone latches the network
emergency property, emergency callback mode, and the platform emergency-number
check before it admits outgoing or incoming processing. If Telecom supplies a
stronger emergency property after admission, the Phone client invalidates any
in-flight number check, cancels automatic answer, and invokes the owner-bound
emergency transaction. Call Intelligence stops capture and receptionist audio,
discards pending context, and immediately erases the opaque call-artifact
directory while leaving the Telecom call connected.

The readiness check is part of each incoming-call decision, before the dialer
schedules or performs a Telecom answer. If readiness disappears after that
decision, `onCallAnswered` checks it again before starting receptionist audio.
This closes both the pre-answer admission window and the post-answer race.

Owner intent wins over every delayed automatic-answer callback. AIOS Phone
revokes the per-call reservation synchronously before its **Answer** or
**Decline** Telecom mutation; manual **AI** consumes the same pending timer. For
video answer, the reservation is revoked before opening Android's camera
permission dialog, so a 1–4 second AI timer cannot expire behind that dialog.
The queued callback must still consume its exact reservation before answering,
which makes a callback already ready on the main queue harmless after owner
cancellation. Hardware/headset answer remains part of the physical Telecom
release matrix because it originates outside the application action path.

Call Intelligence now implements a bounded synthesis pipe and an explicit
telephony-TX `AudioTrack` route. AI-answered calls start capture immediately
after pickup; the first synthesized audio is the receptionist's actual response,
not a mandatory disclosure. Route selection is checked while audio is playing
with `AudioTrack.getRoutedDevice()`; a preferred-device request alone is not
treated as proof.

Implementation is not release evidence. `ro.aios.call_uplink_validated` remains
`false`, so both automatic policy answers and the manual **AI** button stay
locked. The property may become true for a device product only after a physical
carrier call proves that a remote test endpoint heard the complete synthesized
AI response, capture began immediately after answer, teardown stopped audio, and
speaker/Bluetooth/handset endpoint changes remained correct. Compatible
English/Spanish `streaming_asr`, `text_generation`, and `speech_synthesis`
runtimes must also be packaged and ready.
See `caller-audio-uplink.md` for the gate and evidence contract.

## Open-source survey decision

The following projects demonstrate that a Kotlin/Compose default dialer is
practical, but AIOS does not copy their code:

- Amadz: <https://github.com/msusman1/Amadz>, audited at commit
  `483f8d74238c5c1677549734215ce15a74384195` (Apache-2.0). Its current-call
  singleton and limited audio routing are unsuitable for AIOS multi-call parity.
- NovaDial: <https://github.com/dhilipmpms/NovaDial> (GPL-3.0), a Fossify-derived
  Compose dialer. Its license is intentionally not introduced into this
  Apache-2.0 product module.
- simple-phone: <https://github.com/arekolek/simple-phone>, a useful small
  Telecom reference rather than a parity baseline.

Android's dialer-role requirements are the source of truth:
<https://developer.android.com/develop/connectivity/telecom/dialer-app>.

## Emulator verification

`preview/telecomsmoke` compiles the production phone sources as a debug-signed
APK for emulator verification only. Its debug source set supplies a managed
`ConnectionService` fixture because current Android Emulator releases no longer
expose the legacy `gsm call` console command. The fixture injects a synthetic
incoming call through Telecom, causing Android to bind the production
`AiosInCallService`, post the production `CallStyle` notification, and render the
production Compose activity.

`scripts/emulator-telecom-smoke.ps1` refuses non-emulator serials and verifies
`ro.kernel.qemu=1` before installation. It preserves and restores the original
dialer role, disconnects the synthetic call, unregisters its phone account, and
marks generated evidence `physical_gate_evidence=false`. This is useful
integration coverage, but it does not exercise the cellular modem, IMS,
emergency routing, carrier video/RTT, or a Pixel build and cannot pass any
physical-device release gate.

The evidence records the exact smoke APK size and SHA-256. The runner also
refuses to overwrite an existing AIOS Phone installation and, after the visual
checks, asserts that the original role holders and screen state returned, the
fixture account disappeared, and the temporary package was removed unless the
explicit `-KeepInstalled` debugging switch was used. Remote screenshot
filenames are unique and their deletion is verified after pulling. These
postconditions make repeated emulator runs auditable without turning them into
release claims.

The separate `preview/callassistantsmoke` APK uses the exact
`com.aios.callintelligence` package/action and the same debug signing identity as
the Phone fixture. It stages the production `CallPolicyEngine` and
`AnswerDelayPolicy`, but supplies no audio capture, ASR, model, or caller-uplink
implementation. Its Binder and shell control activity both refuse physical
hardware. With the transport unavailable, the ordinary Telecom run still proves
the production **AI** action fails closed. The controlled phase then enables the
emulator AIDL peer and leaves scheduling plus `Call.answer()` entirely inside
the production Phone code.

Before the timer matrix, the runner opens the production Compose Settings
screen from `MainActivity`, toggles **Process and transcribe calls** and **Auto
AI answer**, selects **Every non-emergency call** plus **3s**, and activates
**Save assistant settings**. The companion audit must observe the resulting
`updatePolicy` Binder transaction. The runner then force-stops that companion
and requires the next Telecom call to reload the persisted policy and reach its
managed `Connection.onAnswer` no earlier than the selected three-second delay.
This proves the Settings/UDF/Binder/persistence/Telecom chain; it does not prove
caller-audio capture or physical-device behavior.

That phase measures one automatic Telecom answer for each exact fixed delay and
for one newly sampled inclusive 1,010–3,990 ms random delay. It waits beyond the
four-second deadline after owner **Answer** and **Decline** to reject stale
callbacks. **Ignore** is intentionally different: it silences the owner-facing
notification while preserving the ringing call and its eligible receptionist
timer. A forced service replacement must revoke the old reservation; if Android
rebinds while the call is still ringing, the replacement decision receives a
fresh complete delay. A synthetic `911` presentation must remain ringing without
ever reaching the assistant decision callback. Emulator Telecom does not model
carrier emergency UI/routing, so that last call is fixture-disconnected and all
three release gates remain `not_run` until the physical matrix passes.

Use `-AutomaticAnswerOnly` to run just this focused matrix and write
`aios-emulator-auto-answer-smoke.json`. This is useful on desktop AVDs that
cannot remain stable for the combined baseline-plus-timer run; omitted baseline
fields are written as JSON `null`, never as successful observations. Both debug
APKs, both private audits, the synthetic accounts/calls, and the temporary role
are still removed and verified.

UI hierarchy collection is device-side time-bounded and retried, and fixture
SHOW intents do not use `am start -W`; readiness comes from finding the actual
Compose control. This prevents a low-memory AVD or an already-running activity
from stranding a live synthetic call behind an unbounded ADB wait.

The same run locates the labeled Compose controls through UI Automator rather
than fixed coordinates. It verifies that **Ignore** leaves the call ringing on
the silent channel, **Answer** reaches Telecom `ACTIVE`, and **Decline** removes
the second ringing call. Android 16 requires the answered call's `CallStyle`
notification to belong to a `phoneCall` foreground service; the runner asserts
the production `AiosInCallService` is foreground with type `0x4` and the ongoing
channel. The **AI** control must remain disabled because emulator evidence cannot
unlock the physical caller-audio gate.

The fixture also becomes the emulator's selected outgoing account for one
transaction, then restores the exact previous account. A standard `ACTION_DIAL`
intent populates the production Compose number field; the labeled **Call**
control must create a fixture-backed `DIALING` call and automatically open
`InCallActivity`. The fixture advances that same connection to `ACTIVE`, the
runner captures the connected surface, and then verifies the production
**Mute**/**Unmute** and **Hold**/**Resume** round trips against Telecom state. It
opens **Keypad**, sends `5`, and requires the fixture to observe the runtime's
exact safe sequence: stop any prior tone, play `5`, then stop it after the
bounded pulse. That callback audit exists only in the debug APK's private cache
and is deleted and checked absent before the run can pass. Finally, the labeled
**End call** control must remove the connection without restarting AIOS Phone.
This proves app/Telecom wiring only; radio, IMS, carrier routing, remote DTMF
recognition, call audio, and emergency behavior remain physical device gates.

For post-dial waits, the fixture passes a unique remaining sequence through
`Connection.setPostDialWait`. AIOS stores only the fact that owner input is
required, selects that call, and renders generic **Continue** and **Cancel**
choices; the remaining digits are neither copied into `PhoneUiState` nor exposed
in the UI hierarchy. The runner requires those buttons to reach
`Call.postDialContinue(true)` and `Call.postDialContinue(false)` respectively,
observed as fixture `onPostDialContinue` callbacks, then deletes the private
audit. Actual PBX/carrier post-dial delivery remains a physical gate.

The run then injects another incoming call while the outgoing call is active.
The registry must select the ringing call immediately—even if Telecom first adds
it in another state and reports `RINGING` later—while non-ringing background
calls must not steal the owner's current selection. Answering must leave exactly
one active and one held connection. The selected call must expose **Merge
calls**, reach the fixture's real `ConnectionService.onConference` callback,
then expose **Separate call** and reach `Conference.onSeparate`. Both separated
participants are ended through the production Compose control. This closes a
repeatable emulator wiring gap, but real carrier supplementary-service behavior
for call waiting and conferencing remains release-gated.

Finally, the debug package registers a second disabled managed PhoneAccount. A
dedicated transaction enables it, clears the temporary outgoing default, and
places a new call. Telecom must put that call in `SELECT_PHONE_ACCOUNT`; Compose
must display both fixture labels, and choosing the secondary label must pass its
exact opaque handle through `Call.phoneAccountSelected` to
`ConnectionService.onCreateOutgoingConnection`. The runner activates and ends
that connection, deletes its private audit, and restores the exact original
outgoing account. This proves chooser wiring without claiming physical dual-SIM,
eSIM, carrier-subscription, or emergency-routing behavior.

The separate `preview:prodcheck` lane compiles the complete role-capable Phone
app against the public SDK using the production sources, tests, manifest,
resources, and both AIOS Binder boundaries. Its backup policy excludes every
private app-data domain. In particular, the per-install address-hashing salt and
call-event context ledger/revision clock must never migrate independently from
Communication Context, or a restored dialer could suppress valid reconciliation
or reuse an identity secret on another installation. This lane catches source,
component, resource, and public-API drift; it does not prove privileged grants,
Telecom behavior, or any carrier/Pixel gate.

## Update strategy

Because AIOS Phone is additive under `vendor/aios`, routine AOSP merges do not
require replaying a UI fork. Track API changes at the Telecom contract boundary,
compile against the new platform, and rerun the physical telephony matrix. Keep
the stock system dialer until all required gates have fresh evidence on every
supported device.
