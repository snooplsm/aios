# Product requirements

Status: working baseline, 2026-08-09.

## Product statement

AIOS turns a Pixel phone into an on-device personal receptionist for people who
cannot stop work to handle every call. It answers according to owner policy,
understands the caller in English or Spanish, filters spam, gathers actionable
information, and brings the owner into the call when appropriate.

## Call experience

The AIOS dialer is the preinstalled default phone application and provides the
complete dialing, ringing, in-call, contact lookup, and call-log experience.

Every incoming PSTN call is evaluated against an owner-configured policy. Policy
modes are:

- `off`: never answer with AI.
- `missed_only`: ring the owner first, then let AI answer after a timeout.
- `unknown_only`: AI answers numbers not matched to an allowed contact rule.
- `all`: AI answers every non-emergency incoming call.

Emergency numbers, emergency callback mode, active emergency calls, and carrier
supplementary-service dialogs bypass AI handling.
This applies to both incoming and outgoing calls. If Telecom identifies an
emergency or emergency-callback state after a call was admitted, AIOS must
immediately stop capture, transcription, classification, receptionist output,
and context preparation, then erase any artifact already created for that call.
The carrier call itself continues under Telecom control.

For an AI-handled call, incoming audio has the highest inference priority. The
minimum v1 pipeline is voice activity detection, language identification,
English/Spanish streaming transcription, spam-risk scoring, receptionist policy,
and a live non-obtrusive owner surface. Uplink audio is captured and transcribed,
but may lag behind the incoming stream.

While the call UI is not foregrounded, the low-priority ongoing call
notification shows a bounded live preview of the latest incoming speech and
retains AI/risk state as secondary text. Transcript content is private lock-
screen content, never public notification text, and updates do not alert
repeatedly. Partial-ASR notification refreshes are coalesced while the in-call
transcript remains unthrottled. Ringing notifications never contain transcript
text.

The owner surface presents one of four explicit advisory states: **Likely
legitimate**, **Still evaluating**, **Suspicious call**, or **High-risk call**.
It includes a short human explanation, numeric score, and whether the result came
from deterministic on-device signals or the on-device model. Known contacts get
an initial legitimacy assessment as soon as capture starts; subsequent updates
must be revisioned so stale classifier callbacks cannot replace newer evidence.
For owner-handled calls, every non-final incoming ASR hypothesis must replace the
current provisional classifier context rather than append to it. The on-device
classifier may evaluate these bounded snapshots on a debounce, but its result is
publishable only if the exact transcript revision is still current. Provisional
model evidence must retract on the next hypothesis; final-turn evidence may
remain part of the call assessment. AI-handled calls reserve the model slot for
turn-final receptionist work instead of running a competing partial classifier.

While AI is handling an active call, both the in-call surface and ongoing call
notification must say so and expose **Take over**. Takeover is a one-way owner
action: it stops queued and in-flight AI speech without disconnecting the caller,
then keeps live two-direction transcription and advisory spam classification
running. A stale service callback must not restore the AI-handling presentation.

The product is expected to speak to callers. The TTS engine and initial greeting
copy remain a product decision; the architecture treats TTS as a replaceable
model capability.

## Local call data

Audio, partial transcripts, final transcripts, and derived summaries stay on the
device in app-private encrypted storage. Each artifact receives wall-clock and
same-boot monotonic expiry timestamps at creation. The default and maximum
prototype retention is 24 hours. Wall-clock rollback may not extend that bound;
because monotonic time resets at reboot, previous-boot artifacts are deleted
fail-closed instead of being granted a new window. Cleanup runs at boot, after a
call, and periodically; expired records are not recoverable through the
application.
If the dialer dies while Telecom keeps a call connected, Call Intelligence stops
the orphaned capture immediately. A reconnected dialer may start a fresh capture
session for the same opaque call ID, but it must append to the existing artifact
and preserve the original expiry rather than granting another 24-hour window.
An explicit release of the final Telecom-presence token must also stop unfinished
capture/model/context work. A release performed after successful call
finalization must not erase the finalized context record.
All asynchronous call results must be bound to an unforgeable in-process
request/session identity in addition to the opaque call ID. A restarted session
must reject delayed ASR, classifier, receptionist, context, TTS, and caller-audio
events produced for its predecessor.
Finalized caller segments that arrive during receptionist reasoning or speech
must be preserved in order within a bounded coalesced turn. They must not create
overlapping replies, and a failed model submission must not discard speech that
was queued concurrently.
If Model Broker dies or its package binding is replaced while Call Intelligence
continues recording, the carrier call and app-private PCM capture remain active.
All stale broker callbacks and inference sinks are detached immediately. Call
Intelligence must recreate terminal/null/stalled bindings with bounded backoff,
then attach fresh downlink and uplink ASR streams to each still-live call without
restarting its artifact or extending its expiry.
If that loss interrupts receptionist reasoning, the exact finalized turn must
remain occupied and retry through the replacement binding under its original
absolute deadline. Recovery must change callback identity, must not append the
caller text to history again, and must not allow a predecessor callback to
produce speech or advance the queued next turn.
If Broker loss or a provider error occurs after TTS audio is attached, Call
Intelligence must close the exact matching synthesis/uplink pair and advance the
assistant queue at most once. Normal synthesis completion must allow buffered
PCM to drain. A stale TTS or uplink callback may not stop newer audio, and AIOS
must not automatically replay speech that the caller may have heard partially.
Call Intelligence must not submit caller-facing text to the TTS provider until
the exact synthesis object and telephony-uplink stream are registered as the
active pair. Synchronous provider callbacks, submission exceptions, and uplink
failure must share the same one-shot completion fence.

Raw call audio is never exposed through the shared-media filesystem. Other apps
receive scoped model APIs, not access to call artifacts or model files.

## Media intelligence

The media service observes completed additions to `MediaStore` regardless of the
camera application that created them.

- New photos and videos are discovered automatically; choosing an attachment in
  the system Photo Picker is not an inference trigger.
- A per-volume MediaStore generation cursor reconciles additions missed during
  service death or reboot without processing the pre-install historical library.
- Cursor progress is committed only after durable queue insertion, and may not
  skip an item that is still pending publication by its camera app.
- Deleting or trashing a source must cascade out its private index result.
  Restart recovery checks sources in bounded volume batches, retains data for
  unmounted volumes, and purges URI-keyed results if MediaProvider's database
  identity changes.
- A single settled photo may be processed promptly when the call pipeline is
  idle and the device is thermally healthy.
- Bursts, sustained capture sessions, and all video jobs are deferred.
- Photo capture sessions are derived from chronological MediaStore observation
  times, not from reconciliation page size: consecutive photos no more than five
  seconds apart form one session. Unrelated singleton photos in the same
  complete recovery scan remain eligible for prompt work. If a scan has an
  unknown preceding or following page, all photos in that page fail closed to
  deferred because generation order does not prove capture-time order.
- Deferred jobs require external power and battery level of at least 80%.
- Each video uses twenty nearest-sync keyframes sampled uniformly across time
  and composed into one bounded 5×4 storyboard; AIOS never sends every frame.
- The complete primary video audio track is streamed through on-device bilingual
  ASR in bounded windows and stored as private timestamped, full-text-searchable
  subtitles. Missing audio and no detected speech are recorded distinctly.
- Every ringing, dialing, active, waiting, held, or conferenced call preempts all
  media inference, whether or not AI processing is enabled.
- Originals remain viewable if inference fails or is interrupted.
- Model Broker loss, JobScheduler cancellation, and late callbacks must return
  the claimed item to its durable queue immediately. Only the exact active
  attempt may own a Broker session or final result, and `onStopJob()` must fence
  the encrypted-index commit so a cancelled worker cannot publish afterward.
- Automatic processing never modifies or duplicates a source video. After a
  completed MP4 is indexed, an explicit share-sheet action may create a new
  `Movies/AIOS` MP4 containing the AIOS description and timed subtitle tracks.
  The path must show owner confirmation, copy encoded audio/video samples without
  recompression, verify the remux before publication, and delete partial output
  on failure. A durable pre-insert journal and marker must recover process death
  at startup or boot without deleting a published copy or trusting a reassigned
  URI. AIOS must not render subtitles or burn them into video frames. The custom
  timed transcript track exists only for search and AI context through the
  bounded metadata API; ordinary players may ignore it.

The service produces a versioned structured record containing a caption, tags,
language, model identifier, model digest, inference timestamp, and confidence.
The encrypted AIOS media index is authoritative. A portable XMP projection may
be embedded only for formats that pass a lossless round-trip validator. HEIC,
AVIF, DNG, Motion Photo, and Ultra HDR assets remain index-only until a
format-specific writer proves byte-structure preservation. Videos remain
index-only during automatic processing and are never silently remuxed merely to
add a caption. Their temporary storyboards are app-private and are erased after
inference. Full subtitles stay private unless the owner confirms creation of the
separate enhanced MP4 described above.

## Messaging and communication context

AIOS supplies an original Kotlin/Compose messaging app with system, light, and
dark themes. The user explicitly selects the SMS role. The app must persist an
incoming SMS before acknowledging delivery, persist accepted outbound SMS in the
Telephony provider, support multipart text and respond-via-message, open AIOS
Phone through a standard dial intent, and use the read-only system Photo Picker.
Invalid or future PDU timestamps must be stored at local receipt time instead of
reordering the inbox; valid older timestamps for delayed SMS must be preserved.
Composer controls must remain actionable above gesture and three-button system
navigation insets.
The communication index must reconcile the complete authoritative provider in
bounded restartable pages after process death, restore, external provider
mutation, and deletion. It may store keyed change fingerprints but not raw
numbers or message bodies in its reconciliation ledger. Losing the SMS role must
remove all Messaging-owned context with a monotonic source watermark.

For devices with multiple active subscriptions, the composer must show the
outgoing SIM and pass its exact subscription ID to both SMS and MMS transports.
A valid saved choice or system default may be selected automatically, as may a
single active SIM; ambiguous multi-SIM routing must stop for an explicit choice.

Debuggable research builds may send photo drafts only through the durable MMS
test transport: the PDU must be persisted to the Telephony provider, bounded to
the selected subscription's carrier limits, and completed by the carrier
callback before it is indexed as sent or received. Release `user` builds may not
admit MMS until send/download/provider persistence has passed real carrier and
multi-SIM testing. Unsupported MMS must fail visibly; the product may not label
or count an unpersisted PDU as delivered.
The Photo Picker grants Messaging read access to a user-selected attachment; it
does not discover new camera captures or initiate duplicate model inference.

Phone numbers and contact relationships feed a shared on-device retrieval layer
through per-install opaque identities. Only source-owning AIOS packages may
write. Queries return at most eight bounded snippets. SMS deletion writes a
monotonic tombstone, contact membership is re-resolved on every query identity,
photo metadata must disappear when its source is deleted, and call transcripts
or summaries may not remain retrievable after the 24-hour call-artifact expiry.
Caller history retrieval must be optional, asynchronous, identifier-free at the
model boundary, and unable to delay Telecom answer or capture. Historical
snippets are private context and may not be quoted or disclosed to a caller.
Phone must expose an independent, default-off caller-history setting. Disabling
it must stop new raw caller identities at the Phone process boundary, invalidate
pending retrieval, and clear prepared history from subsequent receptionist
turns without disabling call processing or transcription.

## Model platform

AIOS packages licensed open-weight model artifacts as build inputs and exposes
capabilities through a signature-protected Binder service. Apps request a
capability such as `streaming_asr`, `text_generation`, `image_understanding`,
`video_understanding`, or `speech_synthesis`; they do not open weight files
directly.

The broker chooses a model using measured memory, supported acceleration
backends, thermal state, current workload, and the model catalog. Call inference
always preempts background media inference. Each new request must sample current
Android memory and thermal pressure. Under pressure, an opted-in call request
prefers the lowest-resident-memory admitted fallback, while background media
receives a retryable busy result. Missing pressure telemetry fails closed;
existing call sessions are not migrated, and resident-memory estimates do not
create a fixed product RAM limit.

The initial product policy allows three active broker sessions: two shared by
incoming and outgoing call ASR, and one shared by call reasoning and TTS.
Capacity is parsed from the installed product policy at broker startup and any
missing, malformed, or unsupported value denies client inference. Queued work
counts against its caller's quota but not active class capacity, and a saturated
class may not prevent another eligible class from being promoted.

## Prototype release posture

The initial posture is a research prototype for personally owned, bootloader-
unlocked Pixel devices. This matters because Google's published Pixel hardware
support binaries are licensed for personal-device use and generally may not be
redistributed. A commercial distribution would require a separate vendor/GMS,
carrier-certification, update-signing, privacy, and compliance workstream.

## Definition of the first device milestone

The Pixel 9a milestone is complete only when all of the following work on a real
device:

1. AIOS boots and can be safely returned to a factory image.
2. The AIOS dialer places and receives normal and emergency calls.
3. The configured AI policy can answer a non-emergency incoming call.
4. Downlink English and Spanish transcription meets the latency target defined
   by benchmark data; uplink transcript arrives without blocking the call.
5. The owner can take over an AI-handled call from the call UI or notification;
   remote audio contains no queued or later AI speech, while transcription
   continues and the carrier call remains connected.
6. Call artifacts disappear no later than 24 hours after creation.
7. A new JPEG is indexed, a burst is deferred, and a deferred media job does not
   run below 80% battery or off charger. Known English and Spanish videos produce
   one indexed visual result from twenty chronological keyframes plus complete
   timestamped primary-audio subtitles, without changing the original or
   retaining temporary media.
8. Airplane mode demonstrates that the entire feature set remains on-device.
