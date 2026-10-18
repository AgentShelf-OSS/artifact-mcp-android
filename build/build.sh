#!/usr/bin/env bash
# Build inside the pinned Android SDK container. Run on a Docker host with the repository checked out.
#
#   build/build.sh                      # lint testDebugUnitTest assembleDebug
#   build/build.sh assembleRelease      # any Gradle tasks
#
# The image tag is derived from build/Dockerfile, so editing the Dockerfile rebuilds the image.
# Gradle caches live in the named volume artifact-mcp-android-gradle. The default bridge network
# is used on purpose (new user-defined networks may fail on hosts with exhausted address pools).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG=$(sha256sum "$ROOT/build/Dockerfile" | cut -c1-12)
IMAGE="artifact-mcp-android-build:$TAG"

if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  docker build -t "$IMAGE" "$ROOT/build"
fi

if [ "$#" -eq 0 ]; then
  set -- lint testDebugUnitTest assembleDebug
fi

exec docker run --rm --network bridge \
  -v "$ROOT":/src \
  -v artifact-mcp-android-gradle:/gradle-home \
  -w /src \
  "$IMAGE" ./gradlew --no-daemon --console=plain "$@"
