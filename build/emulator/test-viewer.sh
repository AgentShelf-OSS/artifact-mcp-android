#!/usr/bin/env bash
# Emulator test of the viewer: insets (light and dark), geolocation, the native bridge, the IME.
#
#   build/build.sh assembleDebug
#   build/emulator/run.sh up
#   ARTIFACT_MCP_SRC=/path/to/artifact-mcp build/emulator/server.sh up
#   build/emulator/test-viewer.sh
#
# Needs Node 22+ on the Docker host (for the DevTools client). Evidence: /tmp/emulator-test/.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=${OUT:-/tmp/emulator-test}
NAME=${EMU_CONTAINER:-artifact-mcp-android-emulator}

[ -f "$ROOT/app/build/outputs/apk/debug/app-debug.apk" ] || { echo "build the debug APK first: build/build.sh assembleDebug" >&2; exit 2; }
[ -f "$OUT/artifacts.env" ] || { echo "start the local server first: build/emulator/server.sh up" >&2; exit 2; }
docker ps -q -f name="^${NAME}$" | grep -q . || { echo "start the emulator first: build/emulator/run.sh up" >&2; exit 2; }
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 22 ? 0 : 1)' \
  || { echo "Node 22 or later is required" >&2; exit 2; }

exec node "$HERE/test-viewer.mjs"
