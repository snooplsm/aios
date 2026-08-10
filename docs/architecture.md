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
Dialer remains the configured system/emergency fallback until AIOS Phone passes
the physical telephony matrix; AIOS Phone becomes the normal role holder only
through an explicit owner choice.

Whichever dialer owns Telecom publishes every ringing, dialing, active, waiting,
held, and conferenced call to Call Intelligence with opaque call IDs and a
process-owned Binder token. This signal is independent of transcription and AI
settings: even a human-handled or emergency call immediately preempts background
media inference. A service rebind replays all live calls, concurrent calls keep
the gate asserted until the final call ends, and dialer process death clears its
entire assertion automatically.

### Call Intelligence service

A narrow privileged service owns the real-time call pipeline. As a preinstalled
app granted `CAPTURE_AUDIO_OUTPUT`, it requests telephony uplink and downlink
sources separately when supported by the device audio HAL. Each direction has a
bounded PCM ring buffer and independent timestamps.

The downlink path has a hard priority over uplink transcription and all other
inference. A stalled or overloaded model drops old analysis frames rather than
adding latency to telephony. The service never inserts itself into the modem
media path; failure cannot mute or terminate the call.

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
use the debounced classifier. AI-answered calls use one strict receptionist JSON
result for both its reply and advisory risk, then release that third broker slot
before opening TTS; the two live ASR streams remain uninterrupted.

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
arrives while the assistant is busy is queued. The route is verified during
playback. A per-device read-only property remains false until a physical
carrier-call test proves remote audibility; static audio-policy inspection and
emulator playback cannot unlock automatic answering.

### Model Broker

The Model Broker is a signature-protected Binder service. It:

- verifies the caller UID, signing certificate, requested capability, and per-
  application quota;
- verifies each model artifact against a build-generated SHA-256 manifest;
- selects a compatible model/backend from the capability tier and the
  device-specific, evidence-backed artifact admission;
- owns model mappings so apps cannot copy raw weights;
- enforces one foreground real-time lease and bounded background leases;
- cancels media work when a call begins or thermal pressure becomes severe; and
- records aggregate performance counters without recording prompts or media.

Call Intelligence converts the dialer's complete Telecom-presence set into a
process-owned Binder lease at Model Broker. Model Broker links that lease to
death and automatically returns to the non-call state when the final token dies,
so a Call Intelligence crash cannot leave media inference permanently blocked.
Foreground inference sessions also preempt media for their own bounded lifetime,
but they are not the source of truth for whether a phone call exists.

Initial execution uses LiteRT-LM for supported Gemma mobile artifacts and a
separate runtime adapter for streaming ASR. Backends are discovered and
benchmarked; NPU availability is never inferred solely from a marketing model
name.

### Media Intelligence service

A system component observes `MediaStore` generations and waits until a newly
inserted item is no longer pending and its size is stable. A capture-session
coalescer groups rapid inserts into bursts. Work is persisted with the media ID,
generation, content digest, and scheduling class.

Immediate photo work requires no active call, acceptable thermal state, and a
small queue. Deferred burst/video work additionally requires external power and
an observed battery level of at least 80%. The battery threshold is checked when
the job starts and once per second while model inference runs because Android's
standard job constraint expresses charging, not an arbitrary 80% threshold. A
new call, severe thermal pressure, unplugging, an unavailable battery reading,
or a drop below 80% cancels the background Broker session and leaves the durable
job pending for retry. Immediate photos ignore charging state but remain
preemptible by calls and thermal pressure.

A video is not submitted to a model as an unbounded stream. After all deferred
constraints pass, Media Intelligence seeks the nearest sync frame at the
midpoint of each of twenty equal-duration segments. It scales each frame to a
maximum 224-pixel edge, lays the frames chronologically into one 5×4 private
JPEG storyboard, and submits that single image using the explicit
`video_understanding` capability. Constraints are checked between frame seeks
and throughout inference. The storyboard is erased when the request closes;
crash leftovers are removed at boot and before the next video attempt. The
source video is read-only and remains index-only. Undecodable videos fail
permanently instead of consuming an infinite retry loop. The prompt tells the
model it has sampled frames and forbids claims about unheard audio.

Metadata writes are two-phase:

1. Store the full result in the encrypted index keyed by media ID, generation,
   and digest.
2. For an allowlisted writer, make a sibling temporary file, inject a compact
   versioned XMP packet, validate pixels/container features and the original
   digest relationship, then atomically replace through `MediaStore`.

Only structurally simple JPEG is currently writable. Read support does not
imply safe write support. Complex photos and every video remain index-only
until dedicated validators exist.

### Retention service

Every sensitive row and file has `created_at` and `expires_at`. Cleanup uses the
stored absolute expiry rather than file modification time. Deletion is
idempotent and runs at service start, after calls, and after boot. Call-session
expiry is exactly 24 hours from creation; malformed metadata fails closed and
the stored expiry must equal the overflow-safe `created_at + 24h` calculation.
The service and alarm receiver share one process-wide storage lock, and the
cleanup path closes live PCM descriptors before unlinking the private session
tree. Normal Telecom call audio is unaffected; only optional AI capture/storage
stops at that boundary. The tree is retried on later sweeps if deletion is
interrupted. The preinstalled service declares `USE_EXACT_ALARM`, and the next
absolute expiry is converted to an exact, idle-capable elapsed-realtime wakeup
so a wall-clock rollback after scheduling cannot extend the live timer. An
inexact fallback preserves cleanup if a product build violates that permission
contract, but such a build cannot pass the physical 24-hour release gate. The
UI cannot extend retention past the configured prototype maximum.

## Communication context

AIOS Messaging is a separate Kotlin/Compose UDF application and may hold the SMS
role only after user selection. It owns Telephony-provider SMS persistence; photo
selection uses the read-only system picker, and unadmitted MMS transport fails
visibly. It can launch AIOS Phone through the standard dial intent without
sharing either application's private database.

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
Context lookup is asynchronous and cannot block answer or capture. Durable call
events contain no transcript or recording.

## Storage boundaries

- Read-only model assets: product model directory, accessible only to Model
  Broker domain.
- Call artifacts: credential-encrypted, app-private storage.
- Media intelligence index: credential-encrypted system storage.
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
