#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "AIOS AOSP refresh requires the Linux integration host." >&2
  exit 2
fi
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /absolute/aosp-root" >&2
  exit 2
fi

aosp_root="$1"
case "$aosp_root" in
  /*) ;;
  *) echo "AOSP worktree path must be absolute." >&2; exit 2 ;;
esac
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
  echo "Refusing to refresh tracking with uncommitted AIOS sources." >&2
  exit 2
fi

cd "$aosp_root"
repo init \
  --partial-clone \
  --no-use-superproject \
  -b android-latest-release \
  -u https://android.googlesource.com/platform/manifest

python3 "$aios_root/tools/refresh_aosp_tracking.py" \
  --root "$aios_root" \
  --manifest-repo "$aosp_root/.repo/manifests" \
  --write
python3 "$aios_root/tools/validate_config.py"

cat <<'MESSAGE'
The moving AOSP manifest observation is updated in config/aosp_tracking.json.
Review and commit that one policy change before syncing source projects. Then run:

  repo sync -c -j <safe-job-count>
  vendor/aios/scripts/build-aosp-lane.sh <aosp-root> android_latest_integration <evidence-dir> <jobs>

The build command will refuse a manifest checkout that no longer matches the
reviewed observation. It will never update a release manifest lock implicitly.
MESSAGE
