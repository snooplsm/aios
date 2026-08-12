# Emulator integration evidence

These compact records were captured from a clean AIOS tree at revision
`d3f4d5420f10f744e87bf11a2452752f1d337c1f` on an Android 16/API 36 x86_64
emulator. The debug APKs were assembled from the current source before the
smokes ran.

The records cover Communication Context lifecycle and deletion, exact 24-hour
call retention, Model Broker admission, photo/video scheduling and metadata,
SMS role/provider/Compose behavior, and the full synthetic Telecom state matrix.
Telecom account setup waits for observed registered/enabled state because its
`dumpsys` output intentionally redacts account IDs.

No APK, model weight, fixture audio, SMS content, phone number, screenshot,
transcript, or synthesized PCM is checked in. These virtual runs do not prove
carrier SMS/MMS, physical call audio, ARM64 behavior, real-time performance, or
any Pixel hardware gate.
