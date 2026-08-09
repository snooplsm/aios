# Pixel 10 and future-device enablement

AIOS has two independent compatibility decisions:

1. Can the AOSP product boot and preserve normal phone functionality on this
   device with a compatible device tree, vendor image, kernel, bootloader, radio,
   and carrier configuration?
2. Which measured AI capability tier passes latency, memory, power, and thermal
   gates?

Pixel 10 has 12 GB RAM and Tensor G5, so its expected model tier is `edge_12gb`
and Gemma 4 E4B is eligible. Eligibility is not enablement: the phone must still
benchmark E4B while streaming ASR and handling a call. If it misses a gate, the
broker independently falls back to E2B.

A future Pixel 11 profile will be added only when official device projects and
hardware facts exist. It will not receive a model based on the name "Pixel 11."
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
