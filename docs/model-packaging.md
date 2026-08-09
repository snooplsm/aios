# Licensed model packaging

Model weights are local build inputs and remain ignored by Git. AIOS packages
them only after the builder records acceptance of the exact catalog license URL.

Example on the Linux build host:

```text
python3 vendor/aios/tools/generate_model_pack.py \
  --acceptance /secure/local/model_acceptance.json \
  --source gemma4-e2b-mobile-text:gpu=/secure/models/gemma4-e2b-mobile-text.litertlm \
  --source whisper-base-multilingual-quantized:cpu=/secure/models/ggml-base-q5_1.bin \
  --source supertonic3-en-es-int8:cpu=/secure/models/supertonic3.tar.bz2 \
  --license-file supertonic3-en-es-int8=/secure/licenses/Supertonic-3-OpenRAIL-M.txt
```

The generator refuses unknown IDs, mismatched licenses, unsupported formats,
duplicate IDs, and non-empty output directories. It copies artifacts into the
ignored `generated/modelpack` tree, computes SHA-256 and exact size, produces an
artifact manifest, and generates Soong modules plus the product make fragment.
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
silently inherit AIOS's Apache-2.0 source-code metadata.

The generated manifest and files land together on the verified `/product`
partition. Model Broker still recomputes size and SHA-256 before activation.
The provider repeats confinement, size, and digest verification before native
model initialization. `.bin` is accepted only for a catalog model whose logical
format is `ggml`; generic binary blobs are not accepted for other models.
Verified Boot therefore protects both the expected digest and the artifact for a
release image; later downloadable model updates will need an additional signed
update envelope and rollback protection.

Never commit `model_acceptance.json`: it may identify a builder. The example file
is a shape reference only and does not constitute acceptance.
