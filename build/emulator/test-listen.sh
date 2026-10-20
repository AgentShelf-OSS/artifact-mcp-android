#!/usr/bin/env bash
# Emulator test of the car library and the Listen media mirror (ADR-0019).
#
#   build/build.sh assembleDebug assembleDebugAndroidTest
#   build/emulator/run.sh up
#   ARTIFACT_MCP_SRC=/path/to/artifact-mcp build/emulator/server.sh up
#   build/emulator/test-listen.sh
#
# Drives the app's media library service through the legacy MediaBrowser API (the path Android
# Auto uses) from an instrumentation test, and takes screenshots of the media notification.
# Needs Node 22+ on the Docker host. Evidence: /tmp/emulator-test/listen-*.
#
# Start a fresh server (server.sh up) before each run: shells with cross-device Listen places
# resume the story where the last run stopped, which can end it before the pause checks. The
# chapter, buffering and speech-error checks need a server whose shell has `media.progress`.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=${OUT:-/tmp/emulator-test}
NAME=${EMU_CONTAINER:-artifact-mcp-android-emulator}

for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
  [ -f "$ROOT/$apk" ] || { echo "missing $apk: build/build.sh assembleDebug assembleDebugAndroidTest" >&2; exit 2; }
done
[ -f "$OUT/artifacts.env" ] || { echo "start the local server first: build/emulator/server.sh up" >&2; exit 2; }
grep -q '^STORY_ID=.' "$OUT/artifacts.env" || { echo "artifacts.env has no STORY_ID: rerun server.sh up" >&2; exit 2; }
docker ps -q -f name="^${NAME}$" | grep -q . || { echo "start the emulator first: build/emulator/run.sh up" >&2; exit 2; }
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 22 ? 0 : 1)' \
  || { echo "Node 22 or later is required" >&2; exit 2; }

exec node "$HERE/test-listen.mjs"
