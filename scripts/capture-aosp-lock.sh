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
elif [[ "$lane" == "pixel9a_tegu_hardware" ]]; then
  expected_manifest_commit="d1b2739828a783bbf9bd6ba5d50c727b9329b9b7"
  if [[ "$manifest_revision" != "$expected_manifest_commit" ]]; then
    echo "Pixel manifest is not the reviewed 2026080500 commit." >&2
    exit 2
  fi
  signers_file="$aosp_root/.repo/aios-grapheneos-allowed-signers"
  if [[ ! -s "$signers_file" ]]; then
    echo "Missing GrapheneOS allowed-signers file; rerun bootstrap-aosp.sh." >&2
    exit 2
  fi
  git -C "$aosp_root/.repo/manifests" \
    -c "gpg.ssh.allowedSignersFile=$signers_file" \
    verify-tag 2026080500
  for generated in \
      "$aosp_root/vendor/google_devices/tegu/tegu.mk" \
      "$aosp_root/vendor/state/tegu.json"; do
    if [[ ! -s "$generated" ]]; then
      echo "Missing generated Pixel 9a device input: $generated" >&2
      echo "Run: adevtool generate-all -d tegu" >&2
      exit 2
    fi
  done
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
