# Architecture

## Design rule

The AOSP fork is a thin product integration layer. Most AIOS code lives in its
own applications, native libraries, permissions, SELinux policy, and product
configuration under `vendor/aios`. Framework patches are isolated, individually
documented, and continuously replayed onto a clean upstream checkout.

## Components

### AIOS Phone

An original preinstalled, platform-signed Kotlin/Compose dialer implements
`InCallService`. It uses unidirectional data flow: a main-thread Telecom
registry projects all simultaneous mutable `Call` objects into immutable UI
state, while typed UI actions return to a controller for Telecom mutations. It
owns the ringing and in-call UI and can answer a ringing `Call` after evaluating
owner policy. It must remain functional when every AI service is disabled or
crashed.

The dialer does not run large models. It binds to Call Intelligence and displays
state, transcript segments, risk, and suggested actions. The upstream AOSP
Dialer remains installed as an owner-selectable recovery alternative. A small,
platform-signed product resource overlay configures AIOS Phone as the dialer for
fresh users without patching the framework or Permission Controller. The owner
can still change the standard dialer role. Emergency routing and UI remain a
physical-device release gate, and emergency calls always bypass AI processing.

The minimal locked-boot surface is Direct Boot aware: Telecom may create the
`InCallService`, launch the in-call activity, and deliver notification actions
before the first owner unlock. That path initializes only ordinary call state
and non-sensitive device-encrypted UI preferences. Credential-backed context,
assistant policy, models, and call artifacts are not moved into Direct Boot
storage. `ACTION_USER_UNLOCKED` initializes those optional clients and forces an
immediate, generation-safe AI-service rebind without replacing the live Telecom
call registry.

Whichever dialer owns Telecom publishes every ringing, dialing, active, waiting,
held, and conferenced call to Call Intelligence with opaque call IDs and a
process-owned Binder token. This signal is independent of transcription and AI
settings: even a human-handled or emergency call immediately preempts background
media inference. A service rebind replays all live calls, concurrent calls keep
the gate asserted until the final call ends, and dialer process death atomically
clears its assertion. Any call ID without a surviving token is detached from
Call Intelligence before another client can register: capture, ASR, receptionist
speech, classification, and pending context stop, while Telecom and the carrier
call remain outside that failure domain. A restarted dialer may then replay the
still-live Telecom call and begin a fresh optional intelligence session. An
explicit final presence release applies the same teardown, so an orderly dialer
unbind cannot leave capture running. A release after `onCallEnded` is idempotent
and does not discard the already-finalized communication-context record.

AIOS Phone retains an ordinary `onServiceDisconnected` binding for Android to
reconnect, but explicitly replaces terminal/null bindings. Failed binds retry
with bounded one-second-to-one-minute backoff, and a 15-second watchdog replaces
a binding or initialization that never completes. Each attempt has a distinct
connection generation, so late callbacks from an abandoned binding are ignored.
Disconnect cancels delayed automatic answers. After reconnection the dialer
replays Telecom presence before resuming optional capture for already-active
calls; an AI-handled resumed call never repeats the initial greeting. A call
that is still ringing may be reevaluated, but any replacement automatic-answer
decision receives a new complete delay rather than inheriting the canceled
deadline.

Every asynchronous result is generation-bound rather than trusted by call ID
alone. ASR callbacks carry an opaque stream identity; classifier and receptionist
requests retain their exact call-state object; context queries retain an opaque
request identity; and caller-uplink completion retains its exact active session.
Replacement sessions use monotonically unique broker request IDs. Late callbacks
from a closed session are ignored and cannot append text, speak, update risk, or
attach prior context to a restarted session that reused the same opaque call ID.

### Call Intelligence service

A narrow privileged service owns the real-time call pipeline. As a preinstalled
app granted `CAPTURE_AUDIO_OUTPUT`, it requests telephony uplink and downlink
sources separately when supported by the device audio HAL. Each direction has a
bounded PCM ring buffer and independent timestamps.

The downlink path has a hard priority over uplink transcription and all other
inference. A stalled or overloaded model drops old analysis frames rather than
adding latency to telephony. The service never inserts itself into the modem
media path; failure cannot mute or terminate the call.

The two capture threads share a first-runtime-loss fence after each has produced
its first authoritative PCM. An intentional session close is marked before
`AudioRecord.stop()`, so its expected read termination is ignored. The first
unexpected read, stream, or local-storage failure removes only the exact owned
AI session, publishes owner handling before closing its artifact, and stops all
classifier, receptionist, ASR, and caller-audio work. A second direction racing
to fail is suppressed. Telecom owns the carrier call throughout and is never
asked to disconnect it.

Call Intelligence uses one generation-safe binding controller per Model Broker
client. Android may reconnect an ordinary process crash on the existing binding;
terminal death, null bindings, failed binds, and a connection/capability probe
that stalls for 15 seconds are explicitly unbound and retried with one-second to
one-minute bounded backoff. ASR broker loss atomically removes the expendable
inference sinks and their callback identities while the authoritative local PCM
sinks continue. Once the replacement broker passes its capability probe, each
live call receives new downlink and uplink pipes. Classifier, receptionist, and
speech requests also reject callbacks from the disconnected generation.
An interrupted receptionist request retains its immutable prompt while the
binding is replaced. Each retry receives a fresh callback identity but inherits
the semantic turn's original 15-second deadline, and the outer turn queue does
not advance on the non-terminal recovery status. This prevents both duplicate
speech and an outage-driven renewal of the response budget.
Once a reply reaches TTS, its synthesis object and telephony-uplink stream form
one terminal identity pair. Normal TTS completion lets buffered PCM drain;
provider error or Broker loss closes only that matching pair and advances the
turn once. A concurrent late audio callback cannot consume a newer turn, and
partially delivered speech is not replayed automatically.
TTS setup is explicitly two phase: create the Broker session and attach its PCM
output, register that speech object with its telephony-uplink stream, and only
then submit caller-facing text. The identity gate admits that start once. If a
provider calls back synchronously or `submitText` throws, callback and catch
paths compete for the same pair and only the winner may release the turn.
Within the speech object, completion, provider error, Broker disconnect, and
owner closure share a second first-terminal-wins fence. A provider cannot first
complete and then interrupt draining audio with a duplicate error, and a closed
speech cannot emit a delayed status into a later turn.

Pipeline:

```text
Telecom call -> policy -> answer/ring
                         |
Telephony RX -> VAD/endpoint -> streaming ASR -> risk/events -> dialer overlay
Telephony TX -> VAD -----------> queued ASR -----^              -> local session
Opaque number -> bounded SMS/call history -> receptionist prompt
Final caller turn -> Gemma reply+risk -> bounded TTS -> telephony TX -> caller
```

Spam classification is an ensemble of low-cost deterministic signals, number and
contact context, acoustic/transcript classifiers, and optional LLM review. An LLM
label alone can never block, terminate, or report a number. Human-answered calls
use a revision-bound classifier over a rolling transcript snapshot: each
replaceable ASR partial updates the snapshot immediately, and Gemma receives at
most one request every four seconds with only one request in flight. Results for
superseded transcript revisions are discarded, corrected hypotheses retract
provisional model risk, and final-turn model evidence remains durable within the
call. AI-answered calls instead use one strict receptionist JSON result for both
its reply and advisory risk, then release that third broker slot before opening
TTS; the two live ASR streams remain uninterrupted.
Assessment revisions remain in the expiring private call artifact for audit, but
only the newest assessment is included in the final communication-context
document used by local retrieval.

Each visible assessment is a structured Binder value containing the opaque call
ID, score, label, reason code, source, observation time, and a per-call monotonic
revision. Capture publishes the initial assessment immediately, so a known
contact is visible as `likely_legitimate` before any transcript signal arrives.
Newly registered dialer listeners receive the latest assessment for every active
session. The phone validates the label/score contract and ignores duplicate or
older revisions, which prevents a late low-risk callback from overwriting newer
high-risk evidence. These labels remain advisory UI state only.

Assistant ownership is a separate typed, revisioned per-call state. An AI-
handled call can transition only once, from `aiHandling=true` to owner handling.
The signature-protected takeover transaction atomically closes the assistant
turn queue and detaches any in-flight TTS/uplink handles before returning. It
does not close telephony capture or either ASR stream; incoming and outgoing
transcription and advisory risk continue, using the non-receptionist classifier.
The current ownership value is persisted with the 24-hour call artifact and
replayed to a newly registered dialer listener.

AI answering is fail-closed on processing, bilingual text/TTS availability, and
transport readiness. Capture begins
immediately after AI pickup, with no mandatory spoken disclosure. When the agent
has a final caller turn, Call Intelligence gives Gemma only bounded, quoted,
untrusted current-call data plus at most eight identifier-free historical
snippets. Prior context is explicitly private and may inform continuity but may
not be quoted or disclosed to the caller. The service accepts only an exact
reply/risk schema. It then
requests English or Spanish speech through Model Broker, converts the mono
provider output to the device's 48 kHz stereo in-call format, and routes it only
to `TYPE_TELEPHONY`. Reasoning and speech never overlap, and a caller turn that
arrives while the assistant is busy is queued. Multiple finalized ASR segments
are coalesced in order into one bounded pending turn, preserving their words
without producing a backlog of stale replies. The route is verified during
playback. A per-device read-only property remains false until a physical
carrier-call test proves remote audibility; static audio-policy inspection and
emulator playback cannot unlock automatic answering.

The call-local prompt design is **rolling conversation memory**, also called
hierarchical context compaction. It has four deliberately separate layers:

1. one replaceable live ASR hypothesis, used for immediate risk hints but never
   summarized or made durable;
2. a bounded verbatim window of recent finalized caller turns and assistant
   replies;
3. a versioned structured summary of the finalized prefix, preserving intent,
   people, business names, callback details, requested work, timing,
   commitments, open questions, and risk signals; and
4. separately ranked historical RAG snippets admitted through the opaque
   communication identity.

The receptionist prompt orders those layers as policy and owner profile,
structured compacted summary, recent exact turns, current finalized turn, then
bounded historical snippets. Compaction is background work only after a final
turn and after any live receptionist response has released the text model. New
caller speech or a waiting live reply preempts it. Each compaction result names
its input summary revision and exact finalized-turn range; a late or duplicate
result cannot replace newer memory. The encrypted source transcript remains the
rebuild authority for its existing 24-hour artifact lifetime, and the summary
cannot outlive that artifact. Periodic rebuilds from finalized source turns,
rather than indefinitely summarizing a summary, bound recursive drift. Until
the text runtime passes physical latency tests, deterministic bounded history
remains the fallback and semantic compaction stays disabled rather than blocking
the call path.

### Model Broker

The Model Broker is a signature-protected Binder service. It:

- verifies the caller UID, signing certificate, requested capability, and per-
  application quota;
- verifies each model artifact against a build-generated SHA-256 manifest;
- selects a compatible model/backend from the capability tier and the
  device-specific, evidence-backed artifact admission;
- owns model mappings so apps cannot copy raw weights;
- loads session limits from the verified product policy and enforces three
  global active slots, a shared two-stream RX/TX ASR pool, one serialized
  call-agent lane for reasoning or speech, and per-client active-plus-queued
  quotas;
- samples current Android low-memory and thermal state before each new session,
  preferring a smaller admitted fallback for calls and returning retryable busy
  for background media when constrained or unmeasurable;
- isolates every native runtime behind a verified service binding, fails active
  sessions on provider loss, and explicitly replaces terminal/null bindings
  with bounded backoff and a connection watchdog;
- cancels media work when a call begins or thermal pressure becomes severe,
  including aborting an in-flight native Whisper decode through its exact
  session-owned cancellation token; and
- records aggregate performance counters without recording prompts or media.

Call Intelligence converts the dialer's complete Telecom-presence set into a
process-owned Binder lease at Model Broker. Model Broker links that lease to
death and automatically returns to the non-call state when the final token dies,
so a Call Intelligence crash cannot leave media inference permanently blocked.
Foreground inference sessions also preempt media for their own bounded lifetime,
but they are not the source of truth for whether a phone call exists.
The same presence registry binds each opaque call ID to its asserting UID.
Incoming evaluation and capture require a currently owned call, while an active
capture retains that owner for takeover and teardown. A second platform-signed
dialer therefore cannot collide with or terminate another dialer's AI session.

Initial execution uses LiteRT-LM for supported Gemma mobile artifacts and a
separate runtime adapter for streaming ASR. Backends are discovered and
benchmarked; NPU availability is never inferred solely from a marketing model
name. Ordinary provider-process crashes retain Android's reconnectable binding;
package-update binding death and null bindings are explicitly unbound and
recreated. Every attempt rechecks the system-app/permission identity, and an
opening session is rejected if the provider generation changes during creation.
`ModelRequest.allowFallback` controls activation rather than merely discovery.
When it is false, the request is bound to the first admitted capability/language
candidate and fails if that exact runtime cannot open. When it is true, Model
Broker tries the complete admitted chain in policy order until one runtime
accepts the session. Each attempt has a distinct callback identity, so an error
or delayed callback from a rejected provider cannot terminate the accepted
fallback session. Call ASR, classification, receptionist dialogue, and deferred
media opt in for continuity. Benchmark requests and speech synthesis remain
exact so their evidence and PCM contract cannot silently change artifacts.
Pressure changes do not migrate a running session. Catalog resident-memory
estimates only reorder an opted-in, already admitted fallback chain; they do not
act as a fixed memory limit or authorize an artifact.

### Media Intelligence service

A system component observes `MediaStore` generations and waits until a newly
inserted item is no longer pending and its size is stable. A capture-session
coalescer groups rapid inserts into bursts. Work is persisted with the media ID,
generation, content digest, and scheduling class.

ContentObserver delivery is not treated as durable. On startup and after live
capture groups, Media Intelligence reconciles each concrete external volume
using MediaStore's database version plus a persisted `(GENERATION_ADDED, _ID)`
cursor. Scans are limited to 512 rows per pass and resume within a shared
generation, so large batches cannot be skipped. First install and a provider
database rebuild establish a baseline instead of processing the historical
library. A pending insert blocks cursor advancement, and job insertion must
succeed durably before the cursor moves. The system Photo Picker is used only
when the owner selects a Messaging attachment; it is not part of capture
discovery or model scheduling.

After process restart or boot, a baseline-only pass initializes missing or
invalid volume cursors without enqueueing historical media. Observers are then
registered before full recovery, whose first scan waits the same five-second
capture-settlement window as a live notification. Camera events during that
window restart the delay. The durable generation cursor closes the registration
race without prematurely classifying an in-progress burst as an isolated photo.

Index retention follows MediaStore source lifetime. Exact item deletion or trash
removes every queued/indexed generation of that URI. Only `GENERATION_ADDED`
creates inference work, so favorite and unrelated metadata mutations do not
reprocess a photo. Service startup also walks the job
index in 128-row pages and verifies IDs with one Files query per mounted volume;
an unmounted or failed volume is not treated as empty, and a provider generation
change during the query makes the page retry instead of deleting. A MediaProvider
database version change or generation regression invalidates and purges that
volume's URI-keyed results before a new baseline is recorded. After a replacement
result commits, older generations for that URI are deleted transactionally.

Immediate photo work requires no active call, acceptable thermal state, and a
small queue. Deferred burst/video work additionally requires external power and
an observed battery level of at least 80%. The battery threshold is checked when
the job starts and once per second while model inference runs because Android's
standard job constraint expresses charging, not an arbitrary 80% threshold. A
new call, severe thermal pressure, unplugging, an unavailable battery reading,
or a drop below 80% cancels the background Broker session and leaves the durable
job pending for retry. Immediate photos ignore charging state but remain
preemptible by calls and thermal pressure.

Each visual or video-audio request has an in-process attempt identity that owns
exactly one Broker session and exactly one terminal outcome. Broker disconnect,
terminal/null binding, timeout, constraint loss, and worker interruption wake
the wait immediately and return the claimed database row to pending state; a
late or duplicate callback cannot replace that outcome or be adopted by the
next pass. `onStopJob()` and the final encrypted-index transaction share a commit
fence, preventing a cancelled worker from publishing after JobScheduler has
revoked it.
Each JobScheduler delivery additionally owns an in-process run token. Only a
stop carrying the same opaque delivery UUID from persisted job extras may close
its Broker client, interrupt its thread, or stop the commit fence; only the
matching worker token may clear that run and call `jobFinished`. The UUID is
stable across Binder unparcelling, while stale stop/finish callbacks cannot
affect a replacement immediate or deferred run.

A video has separate bounded visual and audio passes. Media Intelligence seeks
the nearest sync frame at the midpoint of each of twenty equal-duration
segments, scales each frame to a maximum 224-pixel edge, lays the frames
chronologically into one 5×4 private JPEG storyboard, and submits that single
image using `video_understanding`. Every cell must contain a decoded frame; an
incomplete extraction fails rather than sending black cells while claiming
twenty samples. The visual prompt says it has sampled frames
and cannot infer unheard audio.

The complete primary audio track is decoded read-only with `MediaExtractor` and
`MediaCodec`, downmixed and resampled as streaming 16 kHz mono PCM, then sent to
the existing Whisper provider as a `media_background` `streaming_asr` lease.
Four-second speech windows become final English/Spanish subtitle segments with
source-timeline timestamps. PCM, decoded frames, and the storyboard are bounded
or streamed rather than accumulated for the clip. Calls preempt both passes;
charging, 80% battery, call, and thermal constraints are checked throughout.
The private subtitle rows are FTS-indexed and cascade-delete with the source
video. They are never projected to XMP or silently exported as SRT/VTT.

The storyboard is erased when its request closes; crash leftovers are removed
at boot and before the next video attempt. The source video remains read-only
and index-only. Undecodable video or primary audio fails permanently instead of
consuming an infinite retry loop.

An owner-initiated export is deliberately separate from this automatic pipeline.
Media Intelligence exposes a `video/mp4` share target that confirms creation of
a new file, then an internal data-sync foreground service writes an unpublished
`MediaStore` row under `Movies/AIOS`. `MediaExtractor` and `MediaMuxer` copy the
encoded source tracks without invoking codecs and append bounded application
metadata (`mett`) tracks for the AIOS description and timed subtitle events. A
second extraction must reproduce every source sample fingerprint and every AIOS
sample before `IS_PENDING` is cleared. Source generation/digest changes and all
write or verification failures delete the derived row. The original is never
opened writable, and self-write suppression keeps the derived item out of the
inference queue. AIOS does not render the timed transcript or burn text into the
video. Authorized services use the bounded metadata reader for search and AI
context; the custom track is not a subtitle-presentation contract.

The derived-file transaction has its own durable journal because MediaStore and
the private SQLite database cannot share one atomic transaction. A UUID/volume
record is committed before insertion, copied into the pending row, and then bound
to the returned output URI. The publish update clears `IS_PENDING` and the UUID
marker together; only afterward does the service repair self-write suppression
and remove the journal. Startup and boot recovery run under the same in-process
export lock. They explicitly include pending MediaStore rows, delete only an
owned MP4 with the expected marker, discover a crash between insert and URI
attachment by marker, preserve already-published output, and retain unresolved
journals when storage is unavailable.

Metadata writes are two-phase:

1. Store the full result in the encrypted index keyed by media ID, generation,
   and digest.
2. For an allowlisted writer, fsync an exact private backup and write-ahead
   journal, inject a compact versioned XMP packet, validate container features
   and the original digest relationship, then replace through `MediaStore` and
   reread the exact candidate before committing index state.

Only structurally simple JPEG and valid non-animated PNG are automatically
writable in place.
The PNG path verifies CRCs and ordering, rejects APNG, digital signatures, and
unknown critical chunks, and preserves every original chunk and compressed
`IDAT` byte. Read support does not imply safe write support. Complex photos and
source videos remain index-only; the explicitly requested enhanced MP4 is a new,
verified derived item rather than a source mutation.

### Retention service

Every call session stores wall-clock and elapsed-realtime creation/expiry pairs
plus the Android boot identity. Cleanup uses these authenticated-by-consistency
deadlines rather than file modification time. Deletion is
idempotent and runs at service start, after calls, and after boot. Call-session
expiry is exactly 24 hours from creation; malformed metadata fails closed and
both stored expiries must equal their overflow-safe `created_at + 24h`
calculations. During one boot, either the wall or monotonic deadline expires the
tree, so rolling the wall clock backward cannot lengthen retention. Because
elapsed realtime resets and offline duration cannot be proven after reboot,
artifacts created under a previous boot identity are purged fail-closed at boot
or the next service start rather than being granted a fresh window.
The service and alarm receiver share one process-wide storage lock, and the
cleanup path closes live PCM descriptors before unlinking the private session
tree. Normal Telecom call audio is unaffected; only optional AI capture/storage
stops at that boundary. The tree is retried on later sweeps if deletion is
interrupted. The preinstalled service declares `USE_EXACT_ALARM`, and the next
persisted monotonic expiry is used directly as an exact, idle-capable
elapsed-realtime wakeup. An
inexact fallback preserves cleanup if a product build violates that permission
contract, but such a build cannot pass the physical 24-hour release gate. The
UI cannot extend retention past the configured prototype maximum.

## Communication context

AIOS Messaging is a separate Kotlin/Compose UDF application and may hold the SMS
role only after user selection. It owns Telephony-provider SMS persistence; photo
selection uses the read-only system picker. The shared app source compiles
against the public SDK, while one explicit AOSP-only source root links the
platform's maintained `framework-mms-shared-srcs` PDU codec and persister. A
one-line exact-base visibility patch exposes that filegroup at Soong's legal
vendor-subtree boundary; Messaging is the only AIOS consumer. Debug builds can
exercise durable carrier send/download callbacks;
release `user` builds fail visibly until the carrier gate passes. It can launch
AIOS Phone through the standard dial intent without sharing either
application's private database.

Messaging observes the SMS and MMS provider only while it owns `ROLE_SMS`. A
persisted, no-network reconciliation job scans snapshot high-water IDs in
bounded pages after boot, role changes, or process restart. The small local
ledger contains provider IDs and keyed fingerprints only. Context mutations use
one durable revision clock, and role loss bulk-deletes each Messaging source
type through a source watermark so delayed Binder work cannot resurrect history
and per-message tombstones cannot grow without bound. A random context-store
instance token detects independent app-data reset and forces a provider rebuild;
it contains no conversation or device identity.

Communication retrieval crosses a signature-only Binder service. Raw numbers are
normalized transiently and represented in storage by a per-install HMAC key.
Current contact membership is resolved on every identity request into a bounded
set of opaque number keys, so contact edits invalidate grouping without storing
a reversible alias. Source-specific writers publish revisioned documents; a
tombstone prevents stale resurrection after deletion. Query consumers receive at
most eight short snippets, never database handles or model-weight access. Call
artifacts inherit the 24-hour retention boundary; normal call teardown publishes
only final bilingual transcript text, assistant replies, and validated risk
events under the opaque call-directory digest. The presented number exists only
long enough to resolve the HMAC identity and is never written to either store.
The context copy receives the artifact's original internal Android boot identity
and elapsed-realtime creation/expiry pair alongside its wall pair. Both pairs
must prove the same exact 24-hour interval. Either clock expires it; reboot or
legacy rows without a provable monotonic deadline fail closed. An exact idle-capable
local alarm targets the nearest deadline, with boot, service-start, and query
sweeps repairing missed delivery. Non-expiring SMS, MMS, call-event, and media
records retain zeroed expiry metadata and are unaffected by the call TTL alarm.
Context lookup is asynchronous and cannot block answer or capture. Its
generation-safe client replaces failed, null, terminal, and stalled bindings
with bounded backoff. It retains at most one transient preparation per active
call and one final index operation only after opaque identity resolution,
rejects stale generations, and replays only before the artifact expires. The
transient number is discarded when that call finishes or is cancelled. Durable
call events contain no transcript or recording.

## Storage boundaries

- Read-only model assets: product model directory, accessible only to Model
  Broker domain.
- Call artifacts: credential-encrypted, app-private storage.
- Media intelligence index: credential-encrypted system storage.
- Video subtitles: source-linked rows and a private full-text index inside Media
  Intelligence storage; never portable metadata.
- Communication context index: credential-encrypted, opaque conversation keys,
  revisioned source documents, and deletion tombstones.
- Portable media metadata: deliberately small XMP projection, never raw prompts,
  transcripts, embeddings, faces, or private business profile data.

## Failure boundaries

- Telecom works when AIOS services are absent.
- Model crashes return typed errors and do not restart a call.
- A media write failure leaves the original untouched and the index result valid.
- Boot cleanup does not require model availability.
- A model update cannot activate until its digest, license record, compatibility,
  and smoke test succeed.
