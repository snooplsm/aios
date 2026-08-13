#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "AIOS Soong builds require the Linux build host." >&2
  exit 2
fi
if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "Usage: $0 /absolute/aosp-root LANE /absolute/evidence-directory [jobs]" >&2
  exit 2
fi

aosp_root="$1"
lane="$2"
evidence_dir="$3"
jobs="${4:-4}"
for path in "$aosp_root" "$evidence_dir"; do
  case "$path" in
    /*) ;;
    *) echo "AOSP and evidence paths must be absolute: $path" >&2; exit 2 ;;
  esac
done
if [[ ! "$jobs" =~ ^[1-9][0-9]*$ ]]; then
  echo "jobs must be a positive integer" >&2
  exit 2
fi
if [[ ! -d "$aosp_root/.repo" ]]; then
  echo "Not a Repo checkout: $aosp_root" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
aios_root="$(cd "$script_dir/.." && pwd -P)"
expected_aios_root="$(cd "$aosp_root" && pwd -P)/vendor/aios"
if [[ "$aios_root" != "$expected_aios_root" ]]; then
  echo "AIOS must be checked out at $expected_aios_root, found $aios_root" >&2
  exit 2
fi

"$aios_root/scripts/preflight-linux.sh"
"$aios_root/scripts/capture-aosp-lock.sh" "$aosp_root" "$lane" "$evidence_dir"

manifest="$evidence_dir/aosp-manifest.xml"
manifest_lock="$evidence_dir/aosp-manifest-lock.json"
build_log="$evidence_dir/soong-build.log"
build_evidence="$evidence_dir/soong-build-evidence.json"
if [[ -e "$build_log" || -e "$build_evidence" ]]; then
  echo "Refusing to overwrite existing build evidence in $evidence_dir" >&2
  exit 2
fi

patches_applied=false
cleanup() {
  status=$?
  trap - EXIT INT TERM
  if [[ "$patches_applied" == true ]]; then
    if ! python3 "$aios_root/tools/verify_patch_series.py" \
      --root "$aios_root" --aosp-root "$aosp_root" --revert; then
      echo "Patch cleanup failed; the exact staged series remains visible for manual recovery." >&2
      status=1
    fi
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

python3 "$aios_root/tools/verify_patch_series.py" \
  --root "$aios_root" --aosp-root "$aosp_root" --apply
patches_applied=true

lunch_target="$(python3 -c \
  'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["lunch_target"])' \
  "$manifest_lock")"
out_dir="${OUT_DIR:-$aosp_root/out}"
case "$out_dir" in
  /*) ;;
  *) out_dir="$aosp_root/$out_dir" ;;
esac

cd "$aosp_root"
# shellcheck disable=SC1091
source build/envsetup.sh
lunch "$lunch_target"
if [[ "${TARGET_PRODUCT:-}" != "${lunch_target%%-*}" ]]; then
  echo "Lunch selected unexpected TARGET_PRODUCT=${TARGET_PRODUCT:-unset}" >&2
  exit 2
fi

set +e
build_targets=()
if [[ "$lane" == "pixel9a_tegu_hardware" ]]; then
  build_targets=(target-files-package)
fi
m -j "$jobs" "${build_targets[@]}" 2>&1 | tee "$build_log"
build_status="${PIPESTATUS[0]}"
set -e
if [[ "$build_status" -ne 0 ]]; then
  echo "Soong build failed with exit code $build_status; log retained at $build_log" >&2
  exit "$build_status"
fi

python3 "$aios_root/tools/capture_build_evidence.py" \
  --root "$aios_root" \
  --lane "$lane" \
  --manifest "$manifest" \
  --manifest-lock "$manifest_lock" \
  --out-dir "$out_dir" \
  --build-log "$build_log" \
  --output "$build_evidence"

echo "AIOS Soong build and evidence capture passed: $build_evidence"
