#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "AOSP manifest capture requires the Linux build host." >&2
  exit 2
fi
if [[ $# -ne 3 ]]; then
  echo "Usage: $0 /absolute/aosp-root LANE /absolute/evidence-directory" >&2
  exit 2
fi

aosp_root="$1"
lane="$2"
evidence_dir="$3"
for path in "$aosp_root" "$evidence_dir"; do
  case "$path" in
    /*) ;;
    *) echo "AOSP and evidence paths must be absolute: $path" >&2; exit 2 ;;
  esac
done

if [[ ! -d "$aosp_root/.repo" ]]; then
  echo "Not a Repo checkout: $aosp_root" >&2
  exit 2
fi
for command in git python3 repo; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Missing required command: $command" >&2
    exit 2
  }
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
aios_root="$(cd "$script_dir/.." && pwd -P)"
expected_aios_root="$(cd "$aosp_root" && pwd -P)/vendor/aios"
if [[ "$aios_root" != "$expected_aios_root" ]]; then
  echo "AIOS must be checked out at $expected_aios_root, found $aios_root" >&2
  exit 2
fi

if [[ -n "$(git -C "$aios_root" status --porcelain --untracked-files=all)" ]]; then
  echo "Refusing to lock a manifest with uncommitted AIOS sources." >&2
  exit 2
fi

mkdir -p "$evidence_dir"
manifest="$evidence_dir/aosp-manifest.xml"
lock="$evidence_dir/aosp-manifest-lock.json"
if [[ -e "$manifest" || -e "$lock" ]]; then
  echo "Refusing to overwrite existing manifest evidence in $evidence_dir" >&2
  exit 2
fi

cd "$aosp_root"
manifest_revision="$(git -C "$aosp_root/.repo/manifests" \
  rev-parse --verify 'HEAD^{commit}')"
if [[ "$lane" == "android_latest_integration" || "$lane" == "android_avd_integration" || "$lane" == "android_gsi_arm64" ]]; then
  python3 "$aios_root/tools/refresh_aosp_tracking.py" \
    --root "$aios_root" \
    --manifest-repo "$aosp_root/.repo/manifests" \
    --check
fi
repo manifest -r -o "$manifest"
python3 "$aios_root/tools/check_aosp_manifest.py" \
  --root "$aios_root" \
  --manifest "$manifest" \
  --lane "$lane" \
  --manifest-revision "$manifest_revision" \
  --output "$lock"
python3 "$aios_root/tools/validate_config.py"

echo "Resolved AOSP source lock captured:"
echo "  $manifest"
echo "  $lock"
echo "This proves source immutability only; it does not prove a successful build or physical behavior."
