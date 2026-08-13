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

These are catalog expectations, not release-device enablement. The Pixel
10-family entries deliberately have no device-specific AIOS build lane or
product wrapper. They may use the generic `android_gsi_arm64` candidate, but a
shared system image is not evidence of compatibility with any named device. Four
official codenames are known, but a codename is identity—not proof that a
compatible build input set exists. Pixel 10a remains without a published
codename in the official codename table checked on 2026-08-11. The official
Android 17 `android-latest-release` manifest includes Cuttlefish but no Pixel 10
device projects, so AIOS cannot truthfully name a buildable upstream Pixel 10
target from that manifest. The exact compatible platform/device/vendor/kernel
set used for a physical lane must be locked and verified before a wrapper is
added. See the
[official AOSP manifest](https://android.googlesource.com/platform/manifest/+/refs/heads/android-latest-release/default.xml).

The four codenames do have explicit `benchmark_pending` model-admission records
and one shared Tensor G5 runtime profile. This is research enablement only: on a
debuggable build it permits the production Broker path to try digest-verified
GPU/CPU candidates and collect the evidence needed for promotion. A user build
still selects no research candidates, every release admission list remains
empty, the NPU remains absent, and the model profile does not imply that an
AIOS image can yet be built or safely flashed for that device.

RAM makes a tier eligible; it does not authorize its models. Pixel 10 may try the
E4B tier, while the 16 GB Pro models may try the same interactive E4B model with
more concurrency/headroom. Every model/backend/artifact combination must still
pass while streaming ASR and handling a call, and may independently fall back to
E2B or a smaller ASR candidate. The broker and admission tooling already follow
the catalog's ordered fallback chain. A Pixel 10 release profile is not
activated until the missing product/build lane and physical-device evidence
exist.

The catalog-pinned E4B LiteRT-LM artifact has passed host download, digest,
license, pack-generation, and physical-payload deduplication checks; the
weight-free record is under `evidence/model-pack/`. This proves only that the
candidate can be packaged reproducibly. It does not prove LiteRT initialization,
Tensor G5 acceleration, latency, quality, thermal behavior, or device boot.

A future Pixel 11 catalog and research profile will be added only after its
hardware facts and codename are official. Release activation will additionally
require a reproducibly validated platform/device/vendor/kernel build lane. As of
2026-08-11, the official sources above do not provide a Pixel 11 hardware or AOSP
build target, so the catalog intentionally contains no speculative Pixel 11
entry. A generic GSI may be evaluated without predeclaring its identity, but it
will not receive a model based only on the name "Pixel 11."
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
