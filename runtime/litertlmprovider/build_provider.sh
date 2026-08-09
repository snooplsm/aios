#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$project_dir"

if [[ "$(gradle --version | sed -n 's/^Gradle \([0-9.]*\)$/\1/p' | head -n1)" != "8.11.1" ]]; then
  echo "Gradle 8.11.1 is required." >&2
  exit 1
fi
if [[ ! -s gradle.lockfile || ! -s gradle/verification-metadata.xml ]]; then
  echo "Reviewed dependency locks are absent; run bootstrap_dependency_locks.sh first." >&2
  exit 1
fi

gradle --offline --no-daemon --dependency-verification=strict \
  :app:writeRuntimeProvenance

apk="app/build/outputs/apk/release/app-release-unsigned.apk"
provenance="build/runtime-provenance.json"
test -s "$apk"
test -s "$provenance"

echo "Provider APK: $project_dir/$apk"
echo "Provenance: $project_dir/$provenance"
