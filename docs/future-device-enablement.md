# Pixel 10 and future-device enablement

AIOS has two independent compatibility decisions:

1. Can the AOSP product boot and preserve normal phone functionality on this
   device with a compatible device tree, vendor image, kernel, bootloader, radio,
   and carrier configuration?
2. Which measured AI capability tier passes latency, memory, power, and thermal
   gates?

Google's official hardware specifications now provide these planning inputs:

| Device | Official codename | RAM | SoC | Candidate tier |
| --- | --- | ---: | --- | --- |
| Pixel 9a | `tegu` | 8 GB | Tensor G4 | `edge_8gb` |
| Pixel 10a | not yet published | 8 GB | Tensor G4 | `edge_8gb` |
| Pixel 10 | `frankel` | 12 GB | Tensor G5 | `edge_12gb` |
| Pixel 10 Pro | `blazer` | 16 GB | Tensor G5 | `edge_16gb_plus` |
| Pixel 10 Pro XL | `mustang` | 16 GB | Tensor G5 | `edge_16gb_plus` |
| Pixel 10 Pro Fold | `rango` | 16 GB | Tensor G5 | `edge_16gb_plus` |

Sources: [Google Pixel hardware specifications](https://support.google.com/pixelphone/answer/7158570?hl=en)
and [official Android device codenames](https://source.android.com/docs/setup/reference/build-numbers).

These are catalog expectations, not device enablement. The Pixel 10-family
entries deliberately have no AIOS build lane, product wrapper, or admission
profile. Four official codenames are known, but a codename is identity—not proof
that a compatible build input set exists. Pixel 10a remains without a published
codename in the official codename table checked on 2026-08-09. The official
Android 17 `android-latest-release` manifest includes Cuttlefish but no Pixel 10
device projects, so AIOS cannot truthfully name a buildable upstream Pixel 10
target from that manifest. The exact compatible platform/device/vendor/kernel
set used for a physical lane must be locked and verified before a wrapper is
added. See the
[official AOSP manifest](https://android.googlesource.com/platform/manifest/+/refs/heads/android-latest-release/default.xml).

RAM makes a tier eligible; it does not authorize its models. Pixel 10 may try the
E4B tier, while the 16 GB Pro models may try the same interactive E4B model with
more concurrency/headroom. Every model/backend/artifact combination must still
pass while streaming ASR and handling a call, and may independently fall back to
E2B or a smaller ASR candidate.

A future Pixel 11 profile will be added only when official hardware facts and a
reproducibly validated platform/device/vendor/kernel build lane exist. As of
2026-08-09, the official sources above do not provide a Pixel 11 hardware or AOSP
build target, so the catalog intentionally contains no speculative Pixel 11
entry. It will not receive a model based on the name "Pixel 11."
The runtime measures total memory, backend availability, model smoke tests,
thermal behavior, and current workload. A newer NPU that the open runtime cannot
address does not count as usable acceleration.

Capability tiers choose candidates; they do not impose fixed RAM ceilings or
runtime permission. `model_admission.json` separately admits exact benchmarked
artifact digests for a real device codename. Debuggable builds may use the
explicit research candidates of a known pending profile; user builds and
unknown devices fail closed. A
device may keep larger or additional models resident whenever measured system
pressure, call latency, and thermals remain healthy, and sheds idle/background
models only when Android or the active workload requires it.

Each new device needs:

- an additive AIOS product wrapper inheriting its upstream AOSP product;
- matching licensed vendor/kernel/firmware inputs;
- an immutable manifest lock;
- a baseline telephony matrix before AI is enabled;
- model benchmarks and a tier/fallback decision; and
- an evidence-backed device admission binding exact artifact digests; and
- the complete release-gate suite.

This keeps device enablement separate from product logic and prevents hardware-
specific conditionals from spreading through the dialer, Call Intelligence,
Model Broker, or Media Intelligence.

AIOS packages open-weight Gemma candidates. Google's Gemini phone models and
Pixel Launcher/Google application features are not open AOSP build inputs; a
newer Pixel does not give this project redistribution rights or an API to package
those proprietary model weights.
