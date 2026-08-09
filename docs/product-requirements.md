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

For an AI-handled call, incoming audio has the highest inference priority. The
minimum v1 pipeline is voice activity detection, language identification,
English/Spanish streaming transcription, spam-risk scoring, receptionist policy,
and a live non-obtrusive owner surface. Uplink audio is captured and transcribed,
but may lag behind the incoming stream.

The product is expected to speak to callers. The TTS engine and initial greeting
copy remain a product decision; the architecture treats TTS as a replaceable
model capability.

## Local call data

Audio, partial transcripts, final transcripts, and derived summaries stay on the
device in app-private encrypted storage. Each artifact receives an absolute
expiry timestamp at creation. The default and maximum prototype retention is 24
hours. Cleanup runs at boot, after a call, and periodically; expired records are
not recoverable through the application.

Raw call audio is never exposed through the shared-media filesystem. Other apps
receive scoped model APIs, not access to call artifacts or model files.

## Media intelligence

The media service observes completed additions to `MediaStore` regardless of the
camera application that created them.

- A single settled photo may be processed promptly when the call pipeline is
  idle and the device is thermally healthy.
- Bursts, sustained capture sessions, and all video jobs are deferred.
- Deferred jobs require external power and battery level of at least 80%.
- Every ringing, dialing, active, waiting, held, or conferenced call preempts all
  media inference, whether or not AI processing is enabled.
- Originals remain viewable if inference fails or is interrupted.

The service produces a versioned structured record containing a caption, tags,
language, model identifier, model digest, inference timestamp, and confidence.
The encrypted AIOS media index is authoritative. A portable XMP projection may
be embedded only for formats that pass a lossless round-trip validator. HEIC,
AVIF, DNG, Motion Photo, and Ultra HDR assets remain index-only until a
format-specific writer proves byte-structure preservation. Videos use an XMP
sidecar or container-safe writer; they are never remuxed merely to add a caption.

## Model platform

AIOS packages licensed open-weight model artifacts as build inputs and exposes
capabilities through a signature-protected Binder service. Apps request a
capability such as `streaming_asr`, `text_generation`, `image_understanding`, or
`speech_synthesis`; they do not open weight files directly.

The broker chooses a model using measured memory, supported acceleration
backends, thermal state, current workload, and the model catalog. Call inference
always preempts background media inference.

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
5. Call artifacts disappear no later than 24 hours after creation.
6. A new JPEG is indexed, a burst is deferred, and a deferred media job does not
   run below 80% battery or off charger.
7. Airplane mode demonstrates that the entire feature set remains on-device.
