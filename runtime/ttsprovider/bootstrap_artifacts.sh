#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
third_party="$project_dir/third_party"
notices="$third_party/notices/THIRD_PARTY_NOTICES"
mkdir -p "$third_party" "$notices"

fetch() {
  local url="$1"
  local destination="$2"
  local expected_size="$3"
  local expected_sha="$4"
  if [[ ! -f "$destination" ]]; then
    curl --fail --location --proto '=https' --tlsv1.2 \
      --output "$destination.part" "$url"
    mv "$destination.part" "$destination"
  fi
  local actual_size actual_sha
  actual_size="$(wc -c < "$destination" | tr -d ' ')"
  actual_sha="$(sha256sum "$destination" | cut -d' ' -f1)"
  if [[ "$actual_size" != "$expected_size" || "$actual_sha" != "$expected_sha" ]]; then
    echo "Pinned artifact mismatch: $destination" >&2
    exit 1
  fi
}

fetch \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar" \
  "$third_party/sherpa-onnx-1.13.4.aar" \
  48847529 \
  03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780
fetch \
  "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/142807252687d81b40d6315f23470a1512a00de3/LICENSE" \
  "$notices/sherpa-onnx-LICENSE.txt" \
  11358 \
  cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30
fetch \
  "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.27.0/LICENSE" \
  "$notices/onnxruntime-LICENSE.txt" \
  1073 \
  2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c

echo "Verified Sherpa-ONNX 1.13.4 runtime inputs."
