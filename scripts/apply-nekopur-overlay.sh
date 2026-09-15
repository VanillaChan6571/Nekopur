#!/usr/bin/env sh
set -eu

# This script invoked :purpur-server:applyNekopurOverlay, which is not registered in this build.
# No build script references nekopur-overlay/ either, so the overlay it describes is not applied
# by anything. Failing loudly beats failing with Gradle's "task not found" and sending the reader
# looking for a typo.

cat >&2 <<'MSG'
apply-nekopur-overlay.sh does nothing: :purpur-server:applyNekopurOverlay is not a registered task,
and no build script wires nekopur-overlay/ into the build.

Run `./gradlew nekopurWhere` for where each kind of Nekopur change actually belongs, and
`./gradlew nekopurBuild` to build the server jar.
MSG
exit 1
