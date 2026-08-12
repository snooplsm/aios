# Native bilingual provider smoke evidence

These records were captured from a clean AIOS tree at revision
`35ad83afe91208933a302d89049ae2f757fc3b4c` on a headless Android 16/API 36
x86_64 emulator.

Before execution, the runtime smoke client was assembled with offline Gradle
9.4.1/AGP 9.2.0. The Whisper and Sherpa-ONNX providers were assembled with
offline Gradle 8.11.1 and strict dependency verification. The smoke commands
were:

```powershell
$env:ANDROID_ADB_SERVER_PORT = "5038"
powershell -ExecutionPolicy Bypass -File scripts/emulator-whisper-provider-smoke.ps1 -Serial emulator-5554
powershell -ExecutionPolicy Bypass -File scripts/emulator-tts-provider-smoke.ps1 -Serial emulator-5554
```

The alternate ADB server port isolated this stock emulator from the concurrently
running WSL Cuttlefish server. It is not material to the Android test results.

The ASR record proves real native English/Spanish inference, content-checked
final transcripts, the call-RX streaming path, signature-permission rejection,
model-path confinement, and cleanup. The TTS record proves real native
English/Spanish synthesis, non-silent PCM plus stream-metadata validation, the
same protected provider boundary, and cleanup.

These are deliberately not real-time, ARM64, call-uplink, voice-quality, or
physical Pixel claims. Model weights, audio fixtures, synthesized PCM, and APKs
are not checked in.
