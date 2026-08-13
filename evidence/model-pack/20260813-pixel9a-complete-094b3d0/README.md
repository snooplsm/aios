# Complete Pixel 9a candidate model-pack verification

This record was captured from the external Pixel 9a candidate pack at AIOS
revision `094b3d003daa33931fa7d5f28f9e049571ef02cb`.

The pack contains four logical roles: Gemma 4 E2B text, Gemma 4 E2B
multimodal, quantized multilingual Whisper base ASR, and bilingual Supertonic 3
TTS. The verifier rehashed one deduplicated Gemma payload, the Whisper payload,
all eight allowlisted Supertonic bundle members, and the catalog-locked model
license files. Model weights, private external paths, and the builder-acceptance
record are not stored here.

This proves reproducible packaging only. It does not prove provider APK
packaging, native model initialization, inference quality or latency, telephony
audio behavior, or operation on a physical Pixel 9a.
