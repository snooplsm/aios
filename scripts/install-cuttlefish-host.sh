#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Cuttlefish host support requires Linux." >&2
  exit 2
fi
if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root and pass the non-root Cuttlefish user." >&2
  exit 2
fi
if [[ $# -ne 1 || ! "$1" =~ ^[a-z_][a-z0-9_-]*$ ]]; then
  echo "Usage: $0 LINUX_USER" >&2
  exit 2
fi

cuttlefish_user="$1"
if ! id "$cuttlefish_user" >/dev/null 2>&1; then
  echo "Unknown Linux user: $cuttlefish_user" >&2
  exit 2
fi

temporary_dir="$(mktemp -d /tmp/aios-cuttlefish-host.XXXXXX)"
cleanup() {
  rm -rf -- "$temporary_dir"
}
trap cleanup EXIT INT TERM

curl -fsSL \
  https://us-apt.pkg.dev/doc/repo-signing-key.gpg \
  -o "$temporary_dir/artifact-registry.asc"
install -m 0644 \
  "$temporary_dir/artifact-registry.asc" \
  /etc/apt/trusted.gpg.d/artifact-registry.asc
printf '%s\n' \
  'deb https://us-apt.pkg.dev/projects/android-cuttlefish-artifacts android-cuttlefish main' \
  >"$temporary_dir/android-cuttlefish-artifacts.list"
install -m 0644 \
  "$temporary_dir/android-cuttlefish-artifacts.list" \
  /etc/apt/sources.list.d/android-cuttlefish-artifacts.list

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y \
  cuttlefish-base cuttlefish-user

for group in kvm cvdnetwork render; do
  if ! getent group "$group" >/dev/null; then
    echo "Cuttlefish package installation did not create group: $group" >&2
    exit 1
  fi
done
usermod -aG kvm,cvdnetwork,render "$cuttlefish_user"

dpkg-query -W -f='${binary:Package} ${Version} ${Status}\n' \
  cuttlefish-base cuttlefish-user
id "$cuttlefish_user"
echo "Restart the WSL distribution before launching Cuttlefish."
