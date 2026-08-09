# Call Intelligence

Call Intelligence is the only component granted telephony audio capture. The
AIOS-flavored AOSP Dialer binds to its signature-protected API to evaluate an
incoming call and to bracket the lifetime of an answered call.

This separation keeps call state changes inside the default dialer/Telecom while
audio capture, model I/O, artifact retention, and receptionist policy remain in a
small auditable privileged service.

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
transcript. Caller words are explicitly treated as untrusted prompt data, model
JSON is schema-checked, requests time out, and the merged assessment is advisory
only. Neither scorer owns any Telecom action. Two whisper sessions and one call
agent session are reserved so classification can run without delaying incoming
audio.

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
