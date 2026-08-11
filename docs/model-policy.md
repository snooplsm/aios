# Model and workload policy

Model selection is provisional until benchmarked on production-like calls and
media. The catalog names candidates; it does not claim that parameter count alone
predicts quality or speed.

## Workload decomposition

- Voice activity and acoustic quality: tiny deterministic or specialized model.
- Language identification: compact English/Spanish classifier.
- Streaming ASR: dedicated multilingual model with partial hypotheses.
- Spam risk: deterministic context plus small classifiers; Gemma may explain but
  is not the sole decision maker.
- Receptionist reasoning and call summary: Gemma text model.
- TTS: the current candidate is the digest-locked bilingual Supertonic 3 int8
  bundle through the replaceable Sherpa-ONNX provider.
- Photo/video understanding: Gemma multimodal model; videos use one bounded
  twenty-keyframe storyboard and all media work is preemptible.
- Video subtitles: the selected Whisper ASR model under a lower-priority
  `media_background` lease, using the same English/Spanish weights as calls.

Using a dedicated ASR path keeps first words and spam cues available while Gemma
is cold or media inference is unloading.

## Capability tiers

### Edge 8 GB — Pixel 9a baseline and Pixel 10a candidate

- Gemma 4 E2B mobile text-only for receptionist reasoning.
- Compact multilingual streaming ASR; benchmark Whisper `base` and `small`
  quantized candidates against 8 kHz telephony audio.
- Media understanding uses Gemma 4 E2B multimodal only as a background lease.
- Keep any combination that improves quality and still passes real call latency,
  memory-pressure, and thermal tests; there is no product-imposed RAM ceiling.

Google estimates the Gemma 4 E2B mobile footprint at about 0.84 GB text-only and
1.1 GB multimodal before workload-dependent context/runtime overhead. AIOS still
uses measured resident memory and thermal behavior as the gate. Pixel 10a also
has 8 GB RAM and Tensor G4, but remains a catalog-only candidate until its exact
build lane and device identity are verified.

Resident-memory values are observations and planning estimates, not admission
limits. AIOS prefers quality while Android reports healthy headroom. On a trim
signal it first cancels background media and releases idle runtime engines; it
does not evict the active incoming-audio path merely to satisfy an arbitrary
numeric budget. Sustained paging, LMKD kills, missed audio, UI jank, or thermal
throttling fail a model/device benchmark even though the catalog has no fixed
cap.

### Edge 12 GB — Pixel 10 candidate

- Gemma 4 E4B mobile text-only is eligible for receptionist reasoning.
- Gemma 4 E4B multimodal is eligible for deferred media processing.
- Fall back independently to E2B if call latency, memory pressure, or thermal
  limits fail.

Pixel 10 has 12 GB RAM and Tensor G5. Google estimates Gemma 4 E4B mobile at about
2.2 GB text-only and 2.5 GB multimodal before context/runtime overhead. This is
an expected catalog tier only until an exact platform/device/vendor input set
and build lane are locked and benchmarked.

### Edge 16 GB+ — Pixel 10 Pro-family candidates

- E4B remains the default interactive model unless a larger candidate beats it
  within the latency, power, and memory-pressure gates.
- Larger models are optional charging-only workloads, never assumed merely
  because a future phone has a newer product name.

Pixel 10 Pro, Pixel 10 Pro XL, and Pixel 10 Pro Fold have 16 GB RAM and Tensor
G5. The extra memory is measured headroom, not permission to select an
unbenchmarked or proprietary model.

## Required benchmark gates

- ASR: partial latency, final latency, real-time factor, word error rate by
  English/Spanish/noisy-call cohorts, peak RSS, and energy per call minute.
- Receptionist: time to first token, tokens/second, tool-call validity, task
  completion, unsafe-action rate, peak RSS, and energy.
- Media: cold and warm time/image, twenty-keyframe extraction time, storyboard
  inference time, full-audio decode/ASR real-time factor, subtitle timestamp
  validity, videos/hour, thermal throttling, peak RSS, source-digest preservation,
  and temporary-storyboard cleanup.
- System: missed audio frames and UI jank while a call preempts media inference.

No device/model mapping graduates from `candidate` to `supported` without these
measurements. RAM selects a catalog tier only. The separate device-admission
policy binds a pass to the device codename, build fingerprint, backend, and
exact artifact SHA-256; release builds deny unbenchmarked or unknown profiles.
See `model-admission.md`.

Tier fallback is an ordered runtime contract, not just planning prose. Model
Broker starts with the highest memory-eligible tier, then follows its declared
`fallback_tier` chain. For each capability, the first verified and admitted
artifact wins. Shared artifacts are de-duplicated, so a larger model or faster
ASR remains preferred while an independently measured smaller artifact can stay
available when the preferred artifact is not packaged, verified, admitted, or
served by a ready runtime/backend combination.
A fallback artifact receives no trust from the preferred model's result: every
backend/digest combination must have its own passing evidence.

## Request-time pressure policy

Startup RAM tiering and device admission define the exact artifacts that may be
used; transient pressure never admits a different model. Before every new
session, Model Broker samples Android's current low-memory flag and thermal
status. A healthy device preserves the benchmarked quality order. Under low
memory or severe thermal pressure, call requests that opted into fallback
stable-sort their admitted candidates by the catalog's resident-memory estimate.
An exact request (`allowFallback=false`) remains bound to its selected artifact,
and an already-running call session is never migrated between models.

New background-media sessions return a retryable busy result while either
signal is constrained. They also fail closed when a pressure signal cannot be
read, while call work prefers the smallest admitted candidate so incoming audio
can continue. Resident-memory estimates are used only for relative ordering;
they are not a fixed RAM cap. Android trim callbacks preempt background media at
the `RUNNING_LOW`, `RUNNING_CRITICAL`, and legacy background-or-stronger levels.
`UI_HIDDEN` is intentionally not treated as memory pressure because Android's
trim-level families are not one monotonic severity scale.

## Sources

- Gemma 4 model card and license:
  https://ai.google.dev/gemma/docs/core/model_card_4
- Gemma 4 mobile formats and memory estimates:
  https://ai.google.dev/gemma/docs/core
- LiteRT-LM mobile execution overview:
  https://ai.google.dev/gemma/docs/run
- Pixel 9a and Pixel 10-family hardware specifications:
  https://support.google.com/pixelphone/answer/7158570?hl=en
- Official Pixel device codenames:
  https://source.android.com/docs/setup/reference/build-numbers
- Pixel 10 Pro and Pro XL specifications:
  https://store.google.com/product/pixel_10_pro_specs
- Supertonic 3 runtime integration: `tts-runtime.md`.
