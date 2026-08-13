#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "AIOS AOSP bootstrap requires a supported 64-bit Linux host." >&2
  exit 2
fi

if ! command -v repo >/dev/null 2>&1; then
  echo "Android repo tool is required: https://source.android.com/docs/setup/download" >&2
  exit 2
fi

lane="android_latest_integration"
revision=""
manifest_url="https://android.googlesource.com/platform/manifest"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --lane)
      [[ $# -ge 2 ]] || { echo "--lane requires a value" >&2; exit 2; }
      lane="$2"
      shift 2
      ;;
    --revision)
      [[ $# -ge 2 ]] || { echo "--revision requires a value" >&2; exit 2; }
      revision="$2"
      shift 2
      ;;
    --*)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 [--lane LANE] [--revision IMMUTABLE_REVISION] /absolute/path/to/aosp-worktree" >&2
  exit 2
fi

aosp_dir="$1"
case "$aosp_dir" in
  /*) ;;
  *) echo "AOSP worktree path must be absolute." >&2; exit 2 ;;
esac

case "$lane" in
  android_latest_integration|android_avd_integration|android_gsi_arm64)
    if [[ -n "$revision" && "$revision" != "android-latest-release" ]]; then
      echo "Virtual integration lanes must track android-latest-release." >&2
      exit 2
    fi
    revision="android-latest-release"
    ;;
  pixel9a_tegu_hardware)
    if [[ -z "$revision" ]]; then
      revision="2026080500"
    fi
    revision="${revision#refs/tags/}"
    if [[ "$revision" != "2026080500" ]]; then
      echo "Pixel 9a is pinned to reviewed GrapheneOS release tag 2026080500." >&2
      echo "Review and commit a newer signed tag before changing this input." >&2
      exit 2
    fi
    manifest_url="https://github.com/GrapheneOS/platform_manifest.git"
    revision="refs/tags/$revision"
    ;;
  *)
    echo "Unknown AOSP lane: $lane" >&2
    exit 2
    ;;
esac

mkdir -p "$aosp_dir"
cd "$aosp_dir"

repo init \
  --partial-clone \
  --no-use-superproject \
  -b "$revision" \
  -u "$manifest_url"

if [[ "$lane" == "pixel9a_tegu_hardware" ]]; then
  command -v curl >/dev/null 2>&1 || {
    echo "curl is required to fetch the GrapheneOS allowed-signers file." >&2
    exit 2
  }
  signers_file="$aosp_dir/.repo/aios-grapheneos-allowed-signers"
  curl --fail --silent --show-error \
    https://grapheneos.org/allowed_signers \
    --output "$signers_file"
  git -C "$aosp_dir/.repo/manifests" \
    -c "gpg.ssh.allowedSignersFile=$signers_file" \
    verify-tag "${revision#refs/tags/}"
  resolved_manifest_commit="$(git -C "$aosp_dir/.repo/manifests" \
    rev-parse "${revision#refs/tags/}^{commit}")"
  if [[ "$resolved_manifest_commit" != \
      "d1b2739828a783bbf9bd6ba5d50c727b9329b9b7" ]]; then
    echo "Signed Pixel manifest resolved to an unreviewed commit: $resolved_manifest_commit" >&2
    exit 2
  fi
fi

cat <<'MESSAGE'
AOSP manifest initialized but not synced.

Review the manifest revision and available disk/RAM, then run:
  repo sync -c -j <safe-job-count>

After sync, ensure AIOS is present at vendor/aios and capture a resolved lock:
  vendor/aios/scripts/capture-aosp-lock.sh <aosp-root> <lane> <evidence-directory>

AIOS intentionally does not automate acceptance or download of Pixel vendor
binaries or model weights. Those are separate licensed inputs.
MESSAGE

if [[ "$lane" == "pixel9a_tegu_hardware" ]]; then
  cat <<'MESSAGE'

Pixel 9a source is pinned to a verified GrapheneOS release manifest. After sync,
prepare the complete device support set before lunching the target:
  yarn --cwd vendor/adevtool/ install
  adevtool generate-all -d tegu

The physical lane builds full tegu target-files/factory images. Do not reuse the
generic GSI flashing procedure for this checkout.
MESSAGE
fi
