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
