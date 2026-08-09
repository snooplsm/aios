#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "AOSP must be built on a supported 64-bit Linux host." >&2
  exit 2
fi

if [[ "$(getconf LONG_BIT)" != "64" ]]; then
  echo "A 64-bit host is required." >&2
  exit 2
fi

missing=()
for command in git python3 repo; do
  if ! command -v "$command" >/dev/null 2>&1; then
    missing+=("$command")
  fi
done
if (( ${#missing[@]} > 0 )); then
  echo "Missing required commands: ${missing[*]}" >&2
  exit 2
fi

memory_kib="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
memory_gib="$(( memory_kib / 1024 / 1024 ))"
available_kib="$(df -Pk . | awk 'NR==2 {print $4}')"
available_gib="$(( available_kib / 1024 / 1024 ))"
cpu_count="$(getconf _NPROCESSORS_ONLN)"

echo "Linux AOSP host preflight"
echo "  CPUs: $cpu_count"
echo "  RAM: ${memory_gib} GiB"
echo "  Free workspace storage: ${available_gib} GiB"

if (( memory_gib < 32 )); then
  echo "Warning: current AOSP builds are likely to struggle below 32 GiB RAM." >&2
fi
if (( available_gib < 400 )); then
  echo "Warning: reserve substantially more workspace storage before syncing/building." >&2
fi

echo "Preflight complete. Warnings are not a substitute for current AOSP requirements."
