# Call Intelligence

Call Intelligence is the only component granted telephony audio capture. AIOS
Phone—or the temporary AIOS-flavored AOSP Dialer fallback—binds to its
signature-protected API to publish the full Telecom lifecycle, evaluate an
incoming call, and bracket optional processing for an answered call.

This separation keeps call state changes inside the default dialer/Telecom while
audio capture, model I/O, artifact retention, and receptionist policy remain in a
small auditable privileged service.

Telecom presence is deliberately separate from AI processing. The selected
dialer reports opaque IDs for all ringing, dialing, active, waiting, held, and
conferenced calls under a process-owned Binder token. The service enforces token
UID ownership and a bounded concurrent-call count, links the token to death, and
keeps Model Broker's call-priority lease active until the last reported call is
gone. Live calls are replayed after a service rebind. If either dialer or Call
Intelligence dies, Binder cleanup releases priority without relying on a final
callback; normal Telecom behavior remains independent.

The service implements deterministic answer decisions, two-direction PCM
capture, private call-session storage, 24-hour cleanup, and streaming PCM pipes
to Model Broker. If the broker or ASR adapter is unavailable, the best-effort
pipe is dropped while local PCM capture and the normal call continue unaffected.
Retention eligibility is evaluated at the exact expiry boundary by a host-tested
policy. Malformed session metadata is deleted fail-closed, and the preinstalled
service requests an exact, idle-capable elapsed-realtime wakeup so later
wall-clock rollback cannot extend the timer. The physical release gate still
measures actual deletion timing on the target build.
Capture is not declared started until both `VOICE_DOWNLINK` and `VOICE_UPLINK`
have delivered PCM into their authoritative local sinks. Initialization,
recording-state, first-frame, and sink failures tear down the optional AI
session; the Telecom call itself remains untouched and the Dialer explicitly
tells the owner that the connected call has been handed back to them.

If the service process or package binding is replaced during a live call, AIOS
Phone first reasserts the call's death-linked Telecom presence and then invokes
the explicit resume operation. Capture, ASR, risk, and AI-handling state restart
against the original 24-hour artifact deadline. A resumed AI receptionist does
not repeat its greeting; it waits for the next finalized caller turn. Delayed
callbacks from an older binding generation cannot affect the restored call.

Call Intelligence independently recovers its Model Broker dependency. Its ASR,
classifier, receptionist, and TTS clients share the same tested retry semantics:
ordinary process disconnects retain Android's reconnectable binding, while
terminal/null/failed/stalled bindings are replaced with bounded backoff and a
15-second initialization watchdog. Broker loss detaches old ASR callback
identities and only the inference branches of the PCM fanouts. Local recording
continues, and fresh English/Spanish downlink and uplink streams are attached to
every still-live session after capability discovery succeeds. The existing call
artifact and its original expiry are unchanged.
If loss occurs during a finalized receptionist turn, the exact already-built
prompt is retained as one semantic request. A replacement request gets a new
callback/session identity but keeps the original absolute 15-second deadline;
repeated reconnects cannot renew that budget. The assistant turn queue remains
occupied during recovery, so later finalized speech stays bounded and ordered.
Only the recovered request may produce caller-facing text, and timeout or another
terminal result releases the queue.
Provider chunk sequences are scoped to one ASR session and restart at zero after
that recovery. Call Intelligence maps them onto a call-global monotonic revision
clock before risk or classifier consumers see them. Detaching a stream rejects
late callbacks and invalidates its outstanding classifier revision; the first
chunk from the replacement stream therefore resumes at a collision-free newer
revision without discarding finalized transcript history. Stream loss also
retracts interrupted provisional heuristic/model evidence immediately while
leaving every finalized risk signal intact.
Replacement timestamp zero is also stream-local. Each inference sink records the
exact byte offset of the uninterrupted authoritative 16 kHz PCM capture when it
is attached; transcript start/end values are shifted by that offset before they
reach the artifact or Phone UI, so recovery cannot jump backward in the call.

Downlink transcript segments first pass through an explainable English/Spanish
heuristic scorer with deduplicated high-risk signals. A debounced Gemma
`call_classification` request can provide a second opinion from a bounded 4 KiB
live snapshot. Each roughly two-second Whisper partial replaces the current
provisional turn instead of appending duplicate words; finalized turns form the
snapshot history. Requests are limited to one in flight and at most one every
four seconds. A result is accepted only while its exact transcript revision is
still current, and a newer revision is scheduled automatically after an older
request finishes. Provisional model risk retracts when the ASR hypothesis
changes, while final-turn model evidence remains durable for the call. Caller
words are explicitly treated as untrusted prompt data, model JSON is
schema-checked, requests time out, and the merged assessment is advisory only.
Neither scorer owns any Telecom action. Two whisper sessions and one call-agent
session are reserved so classification cannot delay incoming audio.
The private 24-hour artifact keeps the revisioned assessment audit stream, but
the communication-context/RAG summary retains only the latest assessment so a
retracted provisional false alarm cannot survive as durable caller context.

For an AI-answered call, `onCallAnswered` starts capture immediately. There is no
mandatory spoken disclosure. English or Spanish receptionist responses are
synthesized through a bounded Model Broker PCM pipe, converted to the Pixel
in-call format, and routed to the explicit telephony-TX device. The playing
`AudioTrack` must report the telephony route and drain all queued audio before a
response is considered delivered.
The TTS `Speech` object and telephony-uplink `Stream` are retained as one
identity-bound delivery pair. A normal model-completion callback leaves the PCM
reader alive until playback drains. A provider error or Broker disconnect stops
only the matching pair immediately and advances the assistant queue exactly
once; a racing late uplink callback is rejected. AIOS does not automatically
replay possibly partial caller-facing speech after that failure.
Session creation and PCM-output attachment are preparation only. Call
Intelligence registers the resulting `Speech` and uplink `Stream`, starts the
uplink reader, consumes the pair's one-shot start gate, and only then submits
text to the TTS provider. A synchronous callback or submission exception cannot
precede identity registration or release a successor turn from the catch path.
The speech object separately admits only its first terminal source across model
completion, provider error, Broker disconnect, and owner closure. Completion
keeps the uplink drain authoritative even if a broken provider later reports an
error; closure makes all later callbacks silent.

Receptionist reasoning begins on finalized caller segments, while partial ASR
continues updating live transcript and risk. Reasoning and speech are serialized
to prevent overlapping or stale replies. If more finalized segments arrive
while the assistant is busy, their text is coalesced in order into one bounded
pending turn; a language change uses the latest detected response language
without discarding the earlier words. Failed model submission drains any speech
that raced into the queue instead of silently dropping it, and the failed
current turn remains in bounded conversation history for the next request.

The AIDL also exposes a validated owner-policy read/update API for the Dialer
settings screen. The caller-audio transport is implemented but explicitly
unvalidated. The checked-in product property
`ro.aios.call_uplink_validated=false` therefore keeps automatic and manual AI
answering locked until a physical Pixel carrier-call test proves complete remote
audibility. English/Spanish TTS availability and a live telephony-route probe
remain additional runtime gates.

The Soong host-test target explicitly lists every Android-free production source
needed by its tests. For faster drift detection outside a full AOSP checkout,
`preview:callservicecheck` stages the entire privileged service and its call,
model, and context AIDL into a public-SDK Gradle build. Only the narrow immutable
product-property adapter is replaced, with a fail-closed implementation; the
production Soong module still builds the real platform adapter. This compile lane
does not replace a Soong build or any physical telephony release gate.
