# Licensed model packaging

Model weights are local build inputs and remain ignored by Git. AIOS packages
them only after the builder records acceptance of the exact catalog license URL.

Download a catalog-pinned single-file model into a directory outside the source
checkout. The bootstrap resumes a `.partial` transfer, verifies SHA-256 before
an atomic publish, refuses symlink targets and concurrent writers, and leaves a
digest-mismatched partial file uninstalled for inspection:

```text
python3 vendor/aios/tools/bootstrap_reference_model.py \
  --model-id gemma4-e2b-mobile-text \
  --output-directory /secure/models
```

The E2B text and multimodal catalog entries intentionally reference the same
published Gemma file, so this download runs once and that path is supplied for
both logical roles below. Downloading is not license acceptance; the separate
local acceptance record remains required.

Record acceptance with the exact catalog URL in a private path outside the
checkout. The command refuses an unknown model, an altered license URL, a
symlink destination, or an output path inside the source tree. Repeating an
already identical acceptance is idempotent and retains its original timestamp:

```text
python3 vendor/aios/tools/record_model_acceptance.py \
  --output /secure/local/model_acceptance.json \
  --accepted-by local-builder \
  --accept gemma4-e2b-mobile-text=https://ai.google.dev/gemma/apache_2 \
  --accept gemma4-e2b-mobile-multimodal=https://ai.google.dev/gemma/apache_2
```

Example on the Linux build host:

```text
python3 vendor/aios/tools/generate_model_pack.py \
  --acceptance /secure/local/model_acceptance.json \
  --source gemma4-e2b-mobile-text:gpu=/secure/models/gemma-4-E2B-it.litertlm \
  --source gemma4-e2b-mobile-multimodal:gpu=/secure/models/gemma-4-E2B-it.litertlm \
  --source whisper-base-multilingual-quantized:cpu=/secure/models/ggml-base-q5_1.bin \
  --source supertonic3-en-es-int8:cpu=/secure/models/supertonic3.tar.bz2 \
  --license-file gemma4-e2b-mobile-text=vendor/aios/LICENSE \
  --license-file gemma4-e2b-mobile-multimodal=vendor/aios/LICENSE \
  --license-file supertonic3-en-es-int8=/secure/licenses/Supertonic-3-OpenRAIL-M.txt
```

The generator refuses unknown IDs, mismatched licenses, unsupported formats,
duplicate IDs, and non-empty output directories. It copies artifacts into the
ignored `generated/modelpack` tree, computes SHA-256 and exact size, produces an
artifact manifest, and generates Soong modules plus the product make fragment.
When separate logical text and media entries use the same verified Gemma file,
the manifest preserves both capability records but the generated product stores
one physical weight file. Deduplication is allowed only when digest, size,
format, runtime, backend, license URL, and packaged-license lock all match.
For a catalogued bundle such as Supertonic 3, it first verifies the archive's
exact size and SHA-256, rejects links or unsafe member paths, extracts only the
flat allowlisted members without using a general archive extractor, and then
verifies each member's independently catalogued size and digest. The generated
descriptor and all member records are reverified before packaging completes.
When a catalog entry declares `packaged_license`, generation also requires an
explicit local license-file input whose size and SHA-256 match the immutable
catalog lock. It is copied into that model's product directory, represented in
the signed artifact manifest, emitted as its own Soong module, and included in
post-generation tamper verification. This prevents an archive's code license
from being mistaken for the separate license governing its weights.
The generator also emits a per-model Soong `license` module from the catalogued
license kinds and explicitly attaches it to the descriptor, every weight-file
prebuilt, and the installed license text. Restricted weights therefore cannot
silently inherit AIOS's Apache-2.0 source-code metadata. Gemma 4 is itself
Apache-2.0, but its model artifact still gets a separately verified license copy
rather than relying on the surrounding repository license by implication.

The generated manifest and files land together on the verified `/product`
partition. Model Broker still recomputes size and SHA-256 before activation.
The provider repeats confinement, size, and digest verification before native
model initialization. `.bin` is accepted only for a catalog model whose logical
format is `ggml`; generic binary blobs are not accepted for other models.
Artifact verification does not itself authorize a model on release hardware.
Model Broker also requires `model_admission.json` to match the device codename,
measured RAM range, backend, and the exact packaged artifact SHA-256. Pending
profiles expose research candidates only on debuggable builds. See
`model-admission.md` for benchmark-evidence promotion.
Verified Boot therefore protects both the expected digest and the artifact for a
release image; later downloadable model updates will need an additional signed
update envelope and rollback protection.

Never commit `model_acceptance.json`: it may identify a builder. The example file
is a shape reference only and does not constitute acceptance.

After generation, capture a small verification record that contains public
artifact identities but no weights or private acceptance data:

```text
python3 vendor/aios/tools/capture_model_pack_evidence.py \
  --pack /secure/output/modelpack \
  --output evidence/model-pack/e2b.json
```

Capture requires a clean checkout, re-verifies every generated file, binds the
manifest to the checked-in catalog and immutable Git revision, records physical
model-payload deduplication, and explicitly does not claim inference or
physical-device proof.
