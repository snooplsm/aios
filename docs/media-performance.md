# Media performance evidence

AIOS does not publish a Pixel photo or video ETA from desktop simulation. Model
latency and end-to-end latency are different measurements and both must come from
the actual build, model artifact, runtime backend, and phone being admitted.

The model-admission benchmark reports `first_image_latency_ms` (the cold first
request), `p50_warm_image_latency_ms`, `p95_image_latency_ms`, and
`p95_video_storyboard_inference_ms`. Those isolate the bounded Model Broker
request. They do not include the five-second capture-settle window, queue time,
content hashing, JPEG preparation, twenty-keyframe video extraction, storyboard
composition, complete primary-audio decode/Whisper transcription, subtitle-index
commit, or time intentionally deferred while a call is
active or the phone is not charging at 80% or higher.
The settle window also defines capture sessions: chronologically adjacent photos
at most five seconds apart are deferred together. Reconciliation page size is
not a burst signal, so unrelated singleton photos recovered in one complete scan
can still contribute prompt-photo latency samples. Generation order cannot prove
capture-time order across an unknown page boundary, so every photo on such a
page remains conservatively deferred.

Media Intelligence separately retains the latest 100 completed photo timings and
100 completed video timings. Its debug-only snapshot reports p50 and p95 for:

- observed-to-index: capture observation through the durable index commit;
- queue-to-start: observation through worker claim, including intentional delay;
- processing: worker claim through commit;
- input preparation: media validation plus image/video input preparation; and
- model request: Broker session creation through the final model callback;
- video-audio duration: the complete decoded primary-audio timeline;
- video-audio pipeline: primary-audio decode, streaming Whisper ASR, and final
  callback; and
- video-audio real-time factor in permille: pipeline time divided by source-audio
  duration, where 1000 is real time and 250 is four times faster than real time.

Photo audio fields are null. A video with no primary audio reports zero duration
and its measured setup/pipeline time but no real-time factor, avoiding a
divide-by-zero or a false performance claim. The strict timing payload is schema
version 2. Legacy video samples retained across the version-7 database migration
report null audio fields until newer samples replace them. Separate measured-
audio and valid-RTF sample counts accompany the percentiles, so legacy rows and
audio-less videos cannot inflate the apparent evidence denominator.

Only counts and integer durations are exported. The snapshot contains no media
URI, filename, phone number, prompt, caption, transcript, contact, or model
output. User builds do not expose the dump endpoint.

## Pixel evidence procedure

1. Install a debuggable AIOS build with the exact model pack being evaluated.
2. Reboot, leave the model cold, and take one isolated photo. Wait for indexing.
3. Take at least nine more isolated photos, waiting for each to settle and index.
4. While plugged in at 80% battery or higher, record representative English and
   Spanish videos and wait for each deferred storyboard plus full-audio subtitle
   job to finish.
5. Repeat video capture until the timing group has a useful sample size. Do not
   place calls during the nominal run. Separately place a call while a native
   four-second video-audio Whisper window is actively decoding; verify the
   decode aborts, the media job remains retryable, and incoming call ASR is not
   delayed until that window's ordinary completion.
6. Capture model evidence with `scripts/capture-model-benchmark.ps1` and media
   timing with:

   `powershell -File scripts/capture-media-timing.ps1 -Output evidence/media-timing/pixel-9a.json`

The generated evidence binds results to the device codename and a SHA-256 hash
of the build fingerprint. Review the cold/warm Broker metrics beside the
end-to-end percentiles before making an ETA claim. Deferred video
observed-to-index time is intentionally workload and charging dependent, so the
processing and preparation fields are the useful compute-cost measurements.
Use the video-audio real-time-factor percentiles to decide whether a candidate
Whisper artifact can clear a realistic deferred-video queue on that exact phone.
