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
- TTS: benchmark a locally converted Kokoro 82M ONNX model with licensed English
  and Spanish voices; keep the runtime replaceable.
- Photo/video understanding: Gemma multimodal model, preemptible and normally
  deferred.

Using a dedicated ASR path keeps first words and spam cues available while Gemma
is cold or media inference is unloading.

## Capability tiers

### Edge 8 GB — Pixel 9a baseline

- Gemma 4 E2B mobile text-only for receptionist reasoning.
- Compact multilingual streaming ASR; benchmark Whisper `base` and `small`
  quantized candidates against 8 kHz telephony audio.
- Media understanding uses Gemma 4 E2B multimodal only as a background lease.
- Keep any combination that improves quality and still passes real call latency,
  memory-pressure, and thermal tests; there is no product-imposed RAM ceiling.

Google estimates the Gemma 4 E2B mobile footprint at about 0.84 GB text-only and
1.1 GB multimodal before workload-dependent context/runtime overhead. AIOS still
uses measured resident memory and thermal behavior as the gate.

Resident-memory values are observations and planning estimates, not admission
limits. AIOS prefers quality while Android reports healthy headroom. On a trim
signal it first cancels background media and releases idle runtime engines; it
does not evict the active incoming-audio path merely to satisfy an arbitrary
numeric budget. Sustained paging, LMKD kills, missed audio, UI jank, or thermal
throttling fail a model/device benchmark even though the catalog has no fixed
cap.

### Edge 12 GB — Pixel 10 baseline

- Gemma 4 E4B mobile text-only is eligible for receptionist reasoning.
- Gemma 4 E4B multimodal is eligible for deferred media processing.
- Fall back independently to E2B if call latency, memory pressure, or thermal
  limits fail.

Pixel 10 has 12 GB RAM and Tensor G5. Google estimates Gemma 4 E4B mobile at about
2.2 GB text-only and 2.5 GB multimodal before context/runtime overhead.

### Edge 16 GB+

- E4B remains the default interactive model unless a larger candidate beats it
  within the latency, power, and memory-pressure gates.
- Larger models are optional charging-only workloads, never assumed merely
  because a future phone has a newer product name.

## Required benchmark gates

- ASR: partial latency, final latency, real-time factor, word error rate by
  English/Spanish/noisy-call cohorts, peak RSS, and energy per call minute.
- Receptionist: time to first token, tokens/second, tool-call validity, task
  completion, unsafe-action rate, peak RSS, and energy.
- Media: time/image, video minutes processed/hour, thermal throttling, peak RSS,
  and metadata round-trip preservation.
- System: missed audio frames and UI jank while a call preempts media inference.

No device/model mapping graduates from `candidate` to `supported` without these
measurements.

## Sources

- Gemma 4 model sizes and mobile memory estimates:
  https://ai.google.dev/gemma/docs/core
- LiteRT-LM mobile execution overview:
  https://ai.google.dev/gemma/docs/run
- Pixel 9a specifications:
  https://store.google.com/us/product/pixel_9a_specs
- Pixel 10 specifications:
  https://store.google.com/product/pixel_10_specs
- Kokoro 82M model/license and multilingual voices:
  https://huggingface.co/hexgrad/Kokoro-82M
