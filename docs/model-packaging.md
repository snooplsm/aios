# Licensed model packaging

Model weights are local build inputs and remain ignored by Git. AIOS packages
them only after the builder records acceptance of the exact catalog license URL.

Example on the Linux build host:

```text
python3 vendor/aios/tools/generate_model_pack.py \
  --acceptance /secure/local/model_acceptance.json \
  --source gemma4-e2b-mobile-text:gpu=/secure/models/gemma4-e2b-mobile-text.litertlm \
  --source whisper-base-multilingual-quantized:cpu=/secure/models/ggml-base-q5_1.bin
```

The generator refuses unknown IDs, mismatched licenses, unsupported formats,
duplicate IDs, and non-empty output directories. It copies artifacts into the
ignored `generated/modelpack` tree, computes SHA-256 and exact size, produces an
artifact manifest, and generates Soong modules plus the product make fragment.

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
