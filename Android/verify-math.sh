#!/usr/bin/env bash
# Verify TiltFoldMath.kt against docs/ALGORITHM.md. Needs only kotlinc (brew install kotlin).
# Exits non-zero if any value disagrees with the specification.
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "kotlinc not found. Install it with:  brew install kotlin" >&2
  exit 127
fi

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

echo "Compiling the pure-maths layer..."
kotlinc \
  tiltfold/src/main/kotlin/dev/tiltfold/TiltFoldMath.kt \
  verify/Check.kt \
  -include-runtime -d "$out/check.jar" 2>&1 | grep -v '^warning:' || true

echo
kotlin "$out/check.jar"
