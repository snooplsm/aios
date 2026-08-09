# Device model admission

RAM tiers identify models worth testing; they do not make a model safe or fast
enough for a phone. `config/model_admission.json` is the separate, AVB-protected
decision layer consumed by Model Broker.

The checked-in Pixel 9a (`tegu`) profile is `benchmark_pending`. A debuggable
AIOS build may run its explicit research candidates so measurements can be
collected. A non-debuggable build admits none of them. Unknown device codenames
always receive no models, even if they report enough RAM to match a catalog
tier.

## Evidence contract

A benchmark evidence file under `evidence/model-admission/` binds all of these:

- exact device codename, measured total RAM, and hashed build fingerprint;
- benchmark-suite version and completion timestamp;
- exact model ID, runtime, backend, and packaged artifact SHA-256;
- the required gates, failed gates, and measured numeric/boolean metrics; and
- an explicit `passed` or `failed` decision consistent with those gates.

Promotion also requires passes for the tier's text, media, and TTS models plus
at least one ASR candidate. This prevents a nominally “supported” device profile
from silently shipping without a complete receptionist/media path.

Generate a review candidate with:

```text
python3 tools/generate_model_admission.py \
  --evidence evidence/model-admission/pixel-9a-<build>.json \
  --output generated/model_admission/model_admission.json
```

Review the evidence and generated diff before replacing the checked-in policy.
The generator copies the evidence digest into every admitted model. At boot,
Model Broker again requires the verified packaged artifact's backend and digest
to match that admission. Re-quantizing, rebuilding, or replacing weights makes
the old benchmark admission unusable by design.

Pixel 10 or a future Pixel gets a profile only after its real Android device
codename and product inputs are known. Marketing names and RAM estimates alone
never create runtime admission.
