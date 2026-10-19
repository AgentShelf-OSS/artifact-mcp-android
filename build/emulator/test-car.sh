#!/usr/bin/env bash
# Android Automotive OS test of the car design (browse tabs, covers, now-playing buttons, search,
# offline and signed-out states), with screenshots of the car media center.
#
#   build/build.sh assembleAutomotive assembleDebugAndroidTest
#   EMU_PROFILE=car build/emulator/run.sh up
#   CAR_SEED=1 ARTIFACT_MCP_SRC=/path/to/artifact-mcp build/emulator/server.sh up
#   build/emulator/test-car.sh
#
# Installs the `automotive` debug variant: AAOS lists a media app in its media center only when it
# has no launcher activity, so that variant drops the phone launcher entry. Needs Node 22+ on the
# Docker host. Evidence: /tmp/emulator-test/car/.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=${OUT:-/tmp/emulator-test}
NAME=${CAR_CONTAINER:-artifact-mcp-android-car}

for apk in app/build/outputs/apk/automotive/app-automotive.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
  [ -f "$ROOT/$apk" ] || { echo "missing $apk: build/build.sh assembleAutomotive assembleDebugAndroidTest" >&2; exit 2; }
done
[ -f "$OUT/car-published.txt" ] || { echo "start the server with the car fixtures: CAR_SEED=1 build/emulator/server.sh up" >&2; exit 2; }
docker ps -q -f name="^${NAME}$" | grep -q . || { echo "start the car emulator first: EMU_PROFILE=car build/emulator/run.sh up" >&2; exit 2; }
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 22 ? 0 : 1)' \
  || { echo "Node 22 or later is required" >&2; exit 2; }

exec node "$HERE/test-car.mjs"
