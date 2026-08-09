# Bilingual speech-synthesis runtime

AIOS uses speech synthesis so the on-device receptionist can speak its own
short responses to a caller: greet the caller, ask why they are calling, request
a name or callback number, clarify a transcription, and acknowledge that a
message was captured. It is not used for a mandatory recording disclosure.

## Locked implementation

The provider in `runtime/ttsprovider` embeds the official Sherpa-ONNX Android
runtime at version `1.13.4` and uses the int8 Supertonic 3 model bundle. The
runtime AAR, source revision, file size, SHA-256, ONNX Runtime version, and
packaged license texts are locked in `config/runtime_catalog.json`. The model
archive and every member's size and SHA-256 are separately locked in
`config/model_catalog.json`.

Supertonic is an open-weight model, but its model license is not the same as the
AIOS source-code license. The Sherpa release archive contains the upstream
project's MIT code license, not the weights' OpenRAIL-M license. A builder must
therefore review and record acceptance of the immutable catalogued model
license and supply that exact 15,007-byte file to the packer. The packer checks
its SHA-256 and installs it beside the model as
`MODEL_LICENSE.OpenRAIL-M.txt`. Neither the 128 MB model archive,
the model-license input, nor the 49 MB runtime AAR is committed here.

Primary upstream references:

- [Sherpa-ONNX Supertonic integration](https://k2-fsa.github.io/sherpa/onnx/tts/supertonic.html)
- [Sherpa-ONNX source](https://github.com/k2-fsa/sherpa-onnx)
- [Supertonic source and model documentation](https://github.com/supertone-inc/supertonic)
- [Supertonic 3 model license at the accepted revision](https://huggingface.co/Supertone/supertonic-3/blob/724fb5abbf5502583fb520898d45929e62f02c0b/LICENSE)

## Runtime contract

The signature-protected Model Broker is the only accepted caller. It creates a
`speech_synthesis` / `call_agent` session, attaches exactly one 24 kHz mono
PCM16 pipe, and submits one bounded final text request in English or Spanish.
The provider streams PCM chunks into the pipe as they are generated, so pipe
backpressure bounds queued audio instead of accumulating an entire utterance in
Binder or Java memory.

The provider repeats all security checks before native initialization:

- the descriptor is confined beneath `/product/etc/aios/models`;
- descriptor size and SHA-256 match the broker-owned artifact record;
- model ID and source-archive digest match the compiled provider lock;
- every bundle member has the expected relative path, size, and SHA-256;
- the requested backend is CPU and the language is exactly `en` or `es`.

Generation stops when the broker cancels, the client dies, the PCM reader
closes, or the elapsed-realtime deadline expires. The engine remains resident
for call latency and is released only when idle under Android memory pressure;
there is no fixed AIOS RAM ceiling. Speaker selection and conversational voice
quality still require physical-device evaluation.

## Reproducible local inputs

On the Linux AOSP build host, fetch and verify the catalogued runtime inputs:

```text
cd vendor/aios/runtime/ttsprovider
./bootstrap_artifacts.sh
ALLOW_DEPENDENCY_LOCK_UPDATE=1 ./bootstrap_dependency_locks.sh
# Review and commit gradle.lockfile and gradle/verification-metadata.xml.
./build_provider.sh
```

The reviewed provider build uses Gradle 8.11.1, AGP 8.10.1, Kotlin 2.2.21,
Java bytecode target 17, and an arm64-only APK. `build_provider.sh` performs an
offline strict-verification build after the lock bootstrap.

Generate the independently licensed model pack from the exact downloaded
archive:

```text
python3 vendor/aios/tools/generate_model_pack.py \
  --acceptance /secure/local/model_acceptance.json \
  --source supertonic3-en-es-int8:cpu=/secure/models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2 \
  --license-file supertonic3-en-es-int8=/secure/licenses/Supertonic-3-OpenRAIL-M.txt
```

Then turn the unsigned, dependency-verified APK into a product prebuilt that
Soong re-signs with the current platform key:

```text
python3 vendor/aios/tools/generate_runtime_pack.py \
  --runtime sherpa_onnx_tts \
  --apk vendor/aios/runtime/ttsprovider/app/build/outputs/apk/release/app-release-unsigned.apk \
  --provenance vendor/aios/runtime/ttsprovider/build/runtime-provenance.json
```

The resulting generated directories remain ignored. A release image advertises
speech synthesis only when both packs are installed and all broker/provider
identity checks pass. Actual caller hearing is a separate hardware gate: the
telephony uplink remains disabled in source policy until a real Pixel carrier
call proves route selection, timing, cancellation, and audio quality.
