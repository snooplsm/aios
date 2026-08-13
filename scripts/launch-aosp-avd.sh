#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "The AOSP emulator must be launched from a Linux host." >&2
  exit 2
fi
if [[ $# -lt 2 ]]; then
  echo "Usage: $0 /absolute/aosp-root EVEN_CONSOLE_PORT [emulator arguments...]" >&2
  exit 2
fi

aosp_root="$1"
console_port="$2"
shift 2

case "$aosp_root" in
  /*) ;;
  *) echo "AOSP root must be absolute: $aosp_root" >&2; exit 2 ;;
esac
if [[ ! "$console_port" =~ ^[0-9]+$ ]] ||
   (( console_port < 5554 || console_port > 5682 || console_port % 2 != 0 )); then
  echo "Console port must be an even number from 5554 through 5682." >&2
  exit 2
fi
if [[ ! -d "$aosp_root/.repo" ]]; then
  echo "Not a Repo checkout: $aosp_root" >&2
  exit 2
fi
if [[ ! -s "$aosp_root/out/target/product/emu64x/system.img" ]]; then
  echo "Build aios_sdk_phone_x86_64 before launching its AVD." >&2
  exit 2
fi

cd "$aosp_root"
# shellcheck disable=SC1091
source build/envsetup.sh >/dev/null
lunch aios_sdk_phone_x86_64-aosp_current-userdebug >/dev/null

exec emulator -port "$console_port" -no-snapshot "$@"
