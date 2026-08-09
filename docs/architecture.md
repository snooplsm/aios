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
Telephony RX -> VAD -> language -> streaming ASR -> risk/events -> dialer overlay
Telephony TX -> VAD -----------> queued ASR -----^              -> local session
Model Broker -> bounded TTS PCM -> telephony-TX route -> remote caller
```

Spam classification is an ensemble of low-cost deterministic signals, number and
contact context, acoustic/transcript classifiers, and optional LLM review. An LLM
label alone can never block, terminate, or report a number.

AI answering is fail-closed on processing and transport readiness. Capture begins
immediately after AI pickup, with no mandatory spoken disclosure. When the agent
has a response, Call Intelligence requests English or Spanish speech through
Model Broker, converts the mono provider output to the device's 48 kHz stereo
in-call format, and routes it only to `TYPE_TELEPHONY`. The route is verified
during playback. A per-device read-only property remains false until a physical
carrier-call test proves remote audibility; static audio-policy inspection and
emulator playback cannot unlock automatic answering.

### Model Broker

The Model Broker is a signature-protected Binder service. It:

- verifies the caller UID, signing certificate, requested capability, and per-
  application quota;
- verifies each model artifact against a build-generated SHA-256 manifest;
- selects a compatible model/backend from the capability policy;
- owns model mappings so apps cannot copy raw weights;
- enforces one foreground real-time lease and bounded background leases;
- cancels media work when a call begins or thermal pressure becomes severe; and
- records aggregate performance counters without recording prompts or media.

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
the job starts and periodically while it runs because Android's standard job
constraint expresses charging, not an arbitrary 80% threshold.

Metadata writes are two-phase:

1. Store the full result in the encrypted index keyed by media ID, generation,
   and digest.
2. For an allowlisted writer, make a sibling temporary file, inject a compact
   versioned XMP packet, validate pixels/container features and the original
   digest relationship, then atomically replace through `MediaStore`.

JPEG/PNG/WebP are the first writable formats. Read support does not imply safe
write support. Complex containers remain index-only until dedicated validators
exist.

### Retention service

Every sensitive row and file has `created_at` and `expires_at`. Cleanup uses the
stored absolute expiry rather than file modification time. Deletion is
idempotent, runs on boot, and first removes content before database tombstones.
The UI cannot extend retention past the configured prototype maximum.

## Storage boundaries

- Read-only model assets: product model directory, accessible only to Model
  Broker domain.
- Call artifacts: credential-encrypted, app-private storage.
- Media intelligence index: credential-encrypted system storage.
- Portable media metadata: deliberately small XMP projection, never raw prompts,
  transcripts, embeddings, faces, or private business profile data.

## Failure boundaries

- Telecom works when AIOS services are absent.
- Model crashes return typed errors and do not restart a call.
- A media write failure leaves the original untouched and the index result valid.
- Boot cleanup does not require model availability.
- A model update cannot activate until its digest, license record, compatibility,
  and smoke test succeed.
