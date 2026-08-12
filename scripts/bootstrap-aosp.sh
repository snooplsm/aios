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
    if [[ -z "$revision" || "$revision" == "android-latest-release" ]]; then
      echo "Pixel 9a requires an explicit immutable compatible platform revision." >&2
      echo "Select it only after matching device, vendor, kernel, bootloader, and radio inputs." >&2
      exit 2
    fi
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
  -u https://android.googlesource.com/platform/manifest

cat <<'MESSAGE'
AOSP manifest initialized but not synced.

Review the manifest revision and available disk/RAM, then run:
  repo sync -c -j <safe-job-count>

After sync, ensure AIOS is present at vendor/aios and capture a resolved lock:
  vendor/aios/scripts/capture-aosp-lock.sh <aosp-root> <lane> <evidence-directory>

AIOS intentionally does not automate acceptance or download of Pixel vendor
binaries or model weights. Those are separate licensed inputs.
MESSAGE
