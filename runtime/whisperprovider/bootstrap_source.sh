#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
source_dir="$project_dir/third_party/whisper.cpp"
revision="306c88f4d1286aec1bf96e544632897886af5501"

if [[ ! -d "$source_dir/.git" ]]; then
  mkdir -p "$project_dir/third_party"
  git clone --depth 1 --branch v1.9.2 \
    https://github.com/ggml-org/whisper.cpp.git "$source_dir"
fi

actual="$(git -C "$source_dir" rev-parse HEAD)"
if [[ "$actual" != "$revision" ]]; then
  echo "whisper.cpp revision mismatch: expected $revision, found $actual" >&2
  exit 1
fi
if [[ -n "$(git -C "$source_dir" status --porcelain --untracked-files=no)" ]]; then
  echo "whisper.cpp source checkout has local modifications." >&2
  exit 1
fi

echo "Verified whisper.cpp v1.9.2 at $revision"
