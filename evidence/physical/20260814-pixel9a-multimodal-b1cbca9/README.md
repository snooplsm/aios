# Pixel 9a shared multimodal Gemma diagnostic - 2026-08-14

This non-admission physical-device diagnostic verifies AIOS LiteRT-LM provider
`0.15.1` after routing text, image, and video requests through one
vision-capable Gemma engine. It contains no device serial, raw fingerprint,
prompts, generated text, or image bytes. The full focused captures remain
ignored local artifacts.

Two independent runs completed all four selected model roles without an OOM,
fatal signal, runtime crash, or invocation failure. In the clean cold rerun,
the provider verified the 2,588,147,712-byte Gemma package once in 3.379
seconds and initialized one `vision=true`, `audio=false` GPU engine in 5.665
seconds. The cold text request returned the expected bounded answer in 12.626
seconds.

The following image request resolved to the same artifact digest. Verification
was a cache hit in 3 ms and engine admission was an `ENGINE_CACHE_HIT` with no
second initialization or eviction. Its first output arrived in 2.972 seconds
and the generated 64-by-64 red fixture was described correctly. The first run
showed the same one-initialization/one-cache-hit behavior.

The Pixel reported 7,322 MB total RAM. Android's low-memory killer removed 12
low-priority cached/background processes during the clean rerun and 20 during
the earlier run; none was an AIOS runtime. A post-run snapshot of the cached
LiteRT-LM process reported 154,562 KB PSS, 185,708 KB RSS, and 118,080 KB swap
PSS, but that is not a peak and does not account reliably for every GPU-backed
allocation. The benchmark's in-run cross-process PSS fields were zero and must
not be treated as valid memory measurements.

The result supports one shared multimodal engine identity, not permanently
pinning the engine against Android memory pressure. Live-call work remains the
highest-priority Broker lease; deferred photo/video work must yield to it. Cold
start still requires prewarming or another latency reduction before Gemma can
generate conversational call responses.

See `single-model-summary.json` for aggregate timings and exact deployed
artifact hashes.
