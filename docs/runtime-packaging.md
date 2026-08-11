# Runtime-provider packaging

Native ML runtimes execute in separate provider APK processes. Model Broker
never loads their JNI libraries, so a native crash cannot kill the broker or a
telephony client. The broker binds only an exact package/service from the
AVB-protected runtime catalog, requires a system app holding the signature-only
provider permission, and checks provider API, runtime, implementation version,
and backend identity before advertising a capability.

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

The provider project is in `runtime/litertlmprovider`. It uses AGP 8.10.1,
Kotlin 2.2.21, and requires Gradle 8.11.1 plus JDK 17. Its AIDL source sets point
at the same broker/runtime contracts compiled by Soong. The one-time bootstrap
command is deliberately gated because it writes dependency locks; review those
changes before treating them as release inputs. Normal builds are offline and
strictly verified:

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

Android binding lifecycle contract:
[`ServiceConnection`](https://developer.android.com/reference/android/content/ServiceConnection).
