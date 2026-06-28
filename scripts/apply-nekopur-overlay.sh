#!/usr/bin/env sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ -x "$ROOT_DIR/gradlew" ]; then
  GRADLE="$ROOT_DIR/gradlew"
else
  echo "gradlew not found in $ROOT_DIR" >&2
  exit 1
fi

exec "$GRADLE" :purpur-server:applyNekopurOverlay "$@"