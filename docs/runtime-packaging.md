# Runtime-provider packaging

Native ML runtimes execute in separate provider APK processes. Model Broker
never loads their JNI libraries, so a native crash cannot kill the broker or a
telephony client. The broker binds only an exact package/service from the
AVB-protected runtime catalog, requires a system app holding the signature-only
provider permission, and checks provider API, runtime, implementation version,
and backend identity before advertising a capability.

The public-SDK `preview:runtimeprovidercheck` app can exercise the separately
built production LiteRT-LM provider on an x86_64 emulator. It runs under the
exact Model Broker package identity, defines the same signature permission for
the temporary two-APK install, and binds the provider's secondary process. The
fixture verifies transport identity, request/backend rejection, model-path
confinement, bounded terminal callbacks, and provider survival using disposable
plain-text bytes. An optional real-inference mode copies a host-provided
`.litertlm` file into the debug provider's private directory, which is admitted
only when both the debug build flag and a QEMU/Goldfish device check pass. It
executes CPU generation, checks contiguous streamed chunks against the terminal
JSON result, deletes the model before uninstall, and never records generated
text. Release builds compile the private-directory flag to `false` and continue
to admit only digest- and size-bound files below `/product/etc/aios/models`.

For a reproducible native-execution check, use LiteRT-LM v0.15.0's upstream
`runtime/testdata/test_lm.litertlm` fixture (48,696,498 bytes, SHA-256
`36c6cc10f140e5e3526c0838ebb5ce74142b3c0ce8d1356c7d6d0ff50de6a288`):

```powershell
scripts/emulator-runtime-provider-smoke.ps1 `
  -InferenceModel .cache/LiteRT-LM-v0.15.0/runtime/testdata/test_lm.litertlm
```

This proves real LiteRT-LM engine creation and streamed inference across the
production provider's Binder boundary. The small upstream test model is not a
Gemma quality test, a release known-answer artifact, or evidence for arm64,
Tensor acceleration, AVB packaging, physical Pixel behavior, or model admission.

The broker distinguishes an ordinary provider-process disconnect, for which
Android retains and reconnects the existing binding, from terminal
`onBindingDied` and `onNullBinding` callbacks. Terminal bindings are unbound and
replaced, failed binds retry with a one-second-to-one-minute bounded exponential
backoff, and a 15-second watchdog repairs bindings that never deliver a
connection callback. Provider package and permission checks run before every
attempt. Active sessions fail immediately on loss, and a session created across
a provider-generation race is cancelled instead of entering the broker's live
session set.

Runtime fallback is request-scoped. With `allowFallback=false`, only the first
admitted capability/language artifact may be opened. With `allowFallback=true`,
the broker tries the full admitted candidate chain in order when a runtime is
absent or rejects creation. Inputs remain bounded in the broker until an attempt
is accepted. Attempt-specific callbacks reject synchronous failures and stale
events from abandoned providers, and result validation is bound to the artifact
that actually accepted the session. The Android-free activation state has direct
host tests for ordered rejection, synchronous callbacks during open, stale
callbacks, unresolved-attempt skipping, and exact-chain exhaustion.

Runtime-provider API version 2 adds `attachAudioOutput`: the broker transfers
the writable end of a reliable PCM pipe to a `speech_synthesis` provider while
the authorized client retains only the read end. The broker accepts exactly one
mono PCM16 output per synthesis session at 16, 22.05, 24, or 48 kHz and closes
its descriptor on rejection, cancellation, completion, or provider failure.
Non-TTS providers reject and close this method. No raw model path or unbounded
audio buffer crosses the public Binder API.

Model Broker and all three provider projects compile the same Android-free
`RuntimeMemoryTrimPolicy`. Android's running-process and cached-process trim
constants are separate families, so the Broker preempts background work and
providers release an idle native model only for `RUNNING_LOW`,
`RUNNING_CRITICAL`, or legacy `BACKGROUND`-and-stronger callbacks. `UI_HIDDEN`
does not cancel media or unload a warm Whisper, Gemma, or TTS engine. Active
sessions remain protected by each provider's own idle check. The shared policy
has a Soong host test and the local `preview:runtimecommoncheck` lane so the
independently built components cannot silently drift back to numeric comparison.

LiteRT-LM is pinned to `0.15.0` and source revision
`2117fc4314670e00047bc8469783f02a68c33f0c`. The official Android AAR has
SHA-256 `b398c4745934a6035d192ffce5fdaf4f72a0009830a97b73c017c21f2a92b5bd`
and size 19,827,303 bytes. Do not use `latest.release`; it makes a release
impossible to reproduce. The provider build must also lock and verify all
transitive Maven artifacts, not only the LiteRT-LM AAR.

AIOS provider implementation `0.15.1` uses one vision-capable engine for text,
image, and video requests because the catalog aliases resolve to the same
complete digest-locked Gemma package. This costs an estimated 260–300 MB over a
text-only engine, but avoids retaining separate text and vision engines, a
second native initialization, and mode switching between calls, MMS photos, and
camera work. Audio remains a separate engine mode until it has physical-device
evidence. The process retains at most those two initialized modes. Android
running-low/critical memory callbacks still close every idle engine, and Model
Broker can preempt background media before admitting live call work.
After one complete SHA-256 pass, the provider may reuse that verification only
while canonical path, expected digest, size, modification time, and filesystem
file key all remain identical; it rechecks the identity after hashing to reject
a concurrent replacement. Cache hits and full verification are logged without
paths or prompt content.

The provider project is in `runtime/litertlmprovider`. It uses AGP 8.10.1,
Kotlin 2.2.21, and requires Gradle 8.11.1 plus JDK 17. Its AIDL source sets point
at the same broker/runtime contracts compiled by Soong. The one-time bootstrap
command is deliberately gated because it writes dependency locks; review those
changes before treating them as release inputs. Normal builds are offline and
strictly verified. Each provider bootstrap resolves both `dependencies` and
`assembleRelease`, because AGP selects AAPT2 through a detached configuration
that a dependency report alone does not cover. The checked-in verification
metadata includes the exact Windows artifact used for local validation and the
exact Linux artifact required on the AOSP host:

```text
cd vendor/aios/runtime/litertlmprovider
ALLOW_DEPENDENCY_LOCK_UPDATE=1 ./bootstrap_dependency_locks.sh
# Review and commit app/gradle.lockfile and gradle/verification-metadata.xml.
./build_provider.sh
```

The build emits the unsigned provider APK and a provenance JSON containing the
exact resolved dependency closure and verification-file digest. Then package it
on the Linux AOSP host:

```text
python3 vendor/aios/tools/generate_runtime_pack.py \
  --runtime litert_lm \
  --apk vendor/aios/runtime/litertlmprovider/app/build/outputs/apk/release/app-release-unsigned.apk \
  --provenance vendor/aios/runtime/litertlmprovider/build/runtime-provenance.json
```

The generator validates the primary AAR against the catalog lock, requires the
three direct POM dependencies in the resolved closure, verifies the APK shape
and every catalog-pinned license/notice entry, copies it into the ignored
generated tree, and emits an `android_app_import`. The LiteRT provider extracts
the exact `LICENSE` and 2 MB `THIRD_PARTY_NOTICE.txt` from the digest-pinned AAR
into APK assets; their own sizes and SHA-256 values are pinned in the runtime
catalog. The whisper.cpp provider carries the MIT text from its exact source
revision in the same asset namespace. Soong re-signs the APK with that build's
platform key. The generated make fragment is optional: without it, or when
provider identity checks fail, Model Broker remains model-free.

The bilingual call-speech provider follows the same APK provenance flow but
uses a digest-pinned Sherpa-ONNX release AAR whose bundled ONNX Runtime version
and license are also catalogued. Its multi-file Supertonic model is generated by
the separate licensed-model packer and each member is rehashed by both broker
and provider. See `docs/tts-runtime.md` for the exact inputs, commands, stream
contract, and physical-call validation boundary.

Runtime activation is userdebug-only for the Pixel 9a until performance,
thermal, accuracy, crash-recovery, and call-preemption gates have device
evidence. Tensor G4 NPU is deliberately absent from the allowlist. The official
Pixel 10-family codenames share a userdebug-only Tensor G5 profile so a future
compatible port can collect evidence through the production GPU/CPU paths.
Their NPU remains disabled, and runtime eligibility must not be confused with a
build lane, release model admission, or tested device support.

## Pixel 9a shared-engine result

Provider `0.15.1` was tested twice on a physical Pixel 9a with the catalog's
2,588,147,712-byte Gemma E2B package. A clean cold run verified the digest in
3.379 seconds, initialized one vision-capable GPU engine in 5.665 seconds, and
completed the bounded text request in 12.626 seconds. The immediately following
image request reused both the verified digest and the same engine: identity was
ready in 3 ms, first image output arrived in 2.972 seconds, and no second engine
was initialized or evicted.

Both runs completed without OOM, fatal signal, or AIOS runtime failure. Android
did kill low-priority cached/background processes during model initialization,
so this evidence supports sharing the multimodal engine but does not justify
pinning it indefinitely on an 8 GB device. Broker memory-pressure handling and
live-call preemption remain required. The checked-in non-admission summary is
in `evidence/physical/20260814-pixel9a-multimodal-b1cbca9`.

Android binding lifecycle contract:
[`ServiceConnection`](https://developer.android.com/reference/android/content/ServiceConnection).
