#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "AIOS Soong host tests require the Linux build host." >&2
  exit 2
fi
if [[ $# -lt 3 ]]; then
  echo "Usage: $0 /absolute/aosp-root LANE TEST [TEST ...]" >&2
  exit 2
fi

aosp_root="$1"
lane="$2"
shift 2
tests=("$@")

case "$aosp_root" in
  /*) ;;
  *) echo "AOSP worktree path must be absolute." >&2; exit 2 ;;
esac
for test_name in "${tests[@]}"; do
  if [[ ! "$test_name" =~ ^[A-Za-z0-9_.:+-]+$ ]]; then
    echo "unsafe Soong test target: $test_name" >&2
    exit 2
  fi
done
if [[ ! -d "$aosp_root/.repo" ]]; then
  echo "Not a Repo checkout: $aosp_root" >&2
  exit 2
fi
for required_command in git python3; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "Missing required command: $required_command" >&2
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

readarray -t lane_configuration < <(python3 - "$aios_root/config/aosp_lanes.json" "$lane" <<'PY'
import json
import sys

configuration = json.load(open(sys.argv[1], encoding="utf-8"))
matches = [item for item in configuration["lanes"] if item["id"] == sys.argv[2]]
if len(matches) != 1:
    raise SystemExit(f"unknown or duplicate AOSP lane: {sys.argv[2]}")
print(matches[0]["lunch_target"])
print("pixel9a-series.json" if sys.argv[2] == "pixel9a_tegu_hardware" else "series.json")
PY
)
if [[ "${#lane_configuration[@]}" -ne 2 ]]; then
  echo "Could not resolve a unique lunch target and patch series for $lane" >&2
  exit 2
fi
lunch_target="${lane_configuration[0]}"
patch_series="${lane_configuration[1]}"

patches_applied=false
cleanup() {
  status=$?
  trap - EXIT INT TERM
  if [[ "$patches_applied" == true ]]; then
    if ! python3 "$aios_root/tools/verify_patch_series.py" \
      --root "$aios_root" --aosp-root "$aosp_root" \
      --series "$patch_series" --revert; then
      echo "Patch cleanup failed; the exact staged series remains visible for manual recovery." >&2
      status=1
    fi
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

python3 "$aios_root/tools/verify_patch_series.py" \
  --root "$aios_root" --aosp-root "$aosp_root" \
  --series "$patch_series" --apply
patches_applied=true

cd "$aosp_root"
# shellcheck disable=SC1091
source build/envsetup.sh
lunch "$lunch_target"
if [[ "${TARGET_PRODUCT:-}" != "${lunch_target%%-*}" ]]; then
  echo "Lunch selected unexpected TARGET_PRODUCT=${TARGET_PRODUCT:-unset}" >&2
  exit 2
fi

# --host is a hard safety boundary: this wrapper never selects, updates, or
# communicates with an attached Android device.
atest --host "${tests[@]}"
echo "AIOS Soong host tests passed for $lane: ${tests[*]}"
