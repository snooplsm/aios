#!/usr/bin/env bash
set -euo pipefail

if [[ "${ALLOW_DEPENDENCY_LOCK_UPDATE:-}" != "1" ]]; then
  echo "Set ALLOW_DEPENDENCY_LOCK_UPDATE=1 after reviewing the pinned build files." >&2
  exit 1
fi

if [[ "$(gradle --version | sed -n 's/^Gradle \([0-9.]*\)$/\1/p' | head -n1)" != "8.11.1" ]]; then
  echo "Gradle 8.11.1 is required to update the dependency locks." >&2
  exit 1
fi

gradle --no-daemon \
  --write-locks \
  --write-verification-metadata sha256 \
  :app:dependencies \
  :app:assembleRelease

gradle --offline --no-daemon --dependency-verification=strict \
  :app:writeRuntimeProvenance

echo "Review app/gradle.lockfile, gradle/verification-metadata.xml, and build/runtime-provenance.json."
