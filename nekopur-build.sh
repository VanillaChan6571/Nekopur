#!/usr/bin/env bash
set -euo pipefail

# Build Nekopur (Mojmap bundler jar)
# Usage: ./nekopur-build.sh

# Prefer NEKOPUR_JAVA_HOME or a known JDK 21 path, even if JAVA_HOME is set to an older JDK.
if [ -n "${NEKOPUR_JAVA_HOME:-}" ] && [ -d "${NEKOPUR_JAVA_HOME}" ]; then
  export JAVA_HOME="${NEKOPUR_JAVA_HOME}"
elif [ -d "/c/Java/jdk-25.0.1" ]; then
  export JAVA_HOME="/c/Java/jdk-25.0.1"
elif [ -d "C:/Java/jdk-25.0.1" ]; then
  export JAVA_HOME="C:/Java/jdk-25.0.1"
fi

# Gradle honors ORG_GRADLE_JAVA_HOME if set
if [ -n "${JAVA_HOME:-}" ]; then
  export ORG_GRADLE_JAVA_HOME="$JAVA_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "Using JAVA_HOME=${JAVA_HOME:-<unset>}"
java -version 2>&1 | head -n 1 || true

MAX_RETRIES=${NEKOPUR_BUILD_RETRIES:-5}
RETRY_DELAY_SEC=${NEKOPUR_BUILD_RETRY_DELAY_SEC:-5}

attempt=1
while true; do
  echo
  echo "Build attempt ${attempt}/${MAX_RETRIES}..."
  if ./gradlew :purpur-server:createMojmapBundlerJar --no-daemon; then
    break
  fi

  if [ "${attempt}" -ge "${MAX_RETRIES}" ]; then
    echo
    echo "Build failed after ${MAX_RETRIES} attempts."
    echo "Press Enter to close."
    read -r _
    exit 1
  fi

  echo "Build failed. Retrying in ${RETRY_DELAY_SEC}s..."
  sleep "${RETRY_DELAY_SEC}"
  attempt=$((attempt + 1))
done
