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

The upstream AOSP Dialer remains installed and configured as the preloaded
system dialer. AIOS Phone is not assigned through `config_defaultDialer` or a
resource overlay. During research builds, the owner explicitly selects it using
the standard `ROLE_DIALER` prompt. Android can therefore continue to route
emergency calls through the preloaded system dialer.

The exact Android 17 AOSP Dialer topic in `patches/` remains a temporary bridge
for Call Intelligence while AIOS Phone is under validation. It should be removed
after AIOS Phone passes role selection, incoming/outgoing, emergency fallback,
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
owner chooses whether it applies to unknown callers or every non-emergency call,
then selects a 1, 2, 3, or 4 second delay or `Random`. Random is sampled anew for
each eligible call from the inclusive 1,010–3,990 ms range. The UI selection is
persisted by Call Intelligence and the service returns the resolved delay with
its call-handling decision; the dialer never invents a separate delay.

Emergency calls and emergency callback mode always bypass AI. Automatic answer
also fails closed unless call processing is enabled and the service reports that
the caller-audio interaction transport is ready. The setting may be configured
in advance, but it cannot override those runtime safety gates.

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
