#!/usr/bin/env bash
# Runs on the Docker host. Expects two lines on stdin: the base64 keystore and its password.
# Called by build/release.sh; do not run it with values on the command line.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG=$(sha256sum "$ROOT/build/Dockerfile" | cut -c1-12)
IMAGE="artifact-mcp-android-build:$TAG"

if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  docker build -t "$IMAGE" "$ROOT/build" >&2
fi
mkdir -p "$ROOT/dist"

exec docker run -i --rm --network bridge \
  --tmpfs /run/keys:rw,noexec,nosuid,size=1m,mode=0700 \
  --tmpfs /work:rw,exec,size=4g \
  -v "$ROOT":/src:ro \
  -v "$ROOT/dist":/out \
  -v artifact-mcp-android-gradle:/gradle-home \
  "$IMAGE" bash /src/build/release-inner.sh
