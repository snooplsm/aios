# Media performance evidence

AIOS does not publish a Pixel photo or video ETA from desktop simulation. Model
latency and end-to-end latency are different measurements and both must come from
the actual build, model artifact, runtime backend, and phone being admitted.

The model-admission benchmark reports `first_image_latency_ms` (the cold first
request), `p50_warm_image_latency_ms`, `p95_image_latency_ms`, and
`p95_video_storyboard_inference_ms`. Those isolate the bounded Model Broker
request. They do not include the five-second capture-settle window, queue time,
content hashing, JPEG preparation, twenty-keyframe video extraction, storyboard
composition, metadata commit, or time intentionally deferred while a call is
active or the phone is not charging at 80% or higher.

Media Intelligence separately retains the latest 100 completed photo timings and
100 completed video timings. Its debug-only snapshot reports p50 and p95 for:

- observed-to-index: capture observation through the durable index commit;
- queue-to-start: observation through worker claim, including intentional delay;
- processing: worker claim through commit;
- input preparation: media validation plus image/video input preparation; and
- model request: Broker session creation through the final model callback.

Only counts and integer durations are exported. The snapshot contains no media
URI, filename, phone number, prompt, caption, transcript, contact, or model
output. User builds do not expose the dump endpoint.

## Pixel evidence procedure

1. Install a debuggable AIOS build with the exact model pack being evaluated.
2. Reboot, leave the model cold, and take one isolated photo. Wait for indexing.
3. Take at least nine more isolated photos, waiting for each to settle and index.
4. While plugged in at 80% battery or higher, record a representative video and
   wait for its deferred twenty-keyframe storyboard job to finish.
5. Repeat video capture until the timing group has a useful sample size. Do not
   place calls during the nominal run; separately verify that a call preempts the
   media job and leaves it retryable.
6. Capture model evidence with `scripts/capture-model-benchmark.ps1` and media
   timing with:

   `powershell -File scripts/capture-media-timing.ps1 -Output evidence/media-timing/pixel-9a.json`

The generated evidence binds results to the device codename and a SHA-256 hash
of the build fingerprint. Review the cold/warm Broker metrics beside the
end-to-end percentiles before making an ETA claim. Deferred video
observed-to-index time is intentionally workload and charging dependent, so the
processing and preparation fields are the useful compute-cost measurements.
