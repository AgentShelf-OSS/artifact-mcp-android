#!/usr/bin/env bash
# Local Artifact MCP server for the emulator test, with a fixed test identity.
#
#   ARTIFACT_MCP_SRC=/path/to/artifact-mcp build/emulator/server.sh up    # build, start, publish
#   build/emulator/server.sh down
#
# Production login (Cloudflare Access email one-time PIN) cannot run in an emulator. Instead the
# dev server runs with TRUST_ACCESS_HEADERS=1 on 127.0.0.1:3480, and identity-proxy.mjs on
# 127.0.0.1:8480 adds `Cf-Access-Authenticated-User-Email: tester@emulator.test` to every request.
# Both bind to loopback only (--network host). The emulator reaches the proxy as
# http://localhost:8480 through `adb reverse` (see test-viewer.sh); localhost is a secure context,
# so the Geolocation API is available. Only a debug build of the app accepts an http://localhost origin.
#
# Listen speech: fake-tts.mjs on 127.0.0.1:3490 stands in for the Kokoro speech worker (a quiet
# tone per request), so the viewer's Listen player and the Android media mirror can be tested.
#
# Writes the published artifact ids to /tmp/emulator-test/artifacts.env.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SRC=${ARTIFACT_MCP_SRC:-}
PLANNER=${PLANNER_HTML:-/mnt/nas/Dev/repos/road-trip/planner/dist/index.html}
SERVER=artifact-mcp-emu-server
PROXY=artifact-mcp-emu-proxy
TTS=artifact-mcp-emu-tts
TTS_PORT=3490
SERVER_PORT=3480
PROXY_PORT=8480
OUT=/tmp/emulator-test
EMAIL=tester@emulator.test
ORG=emulator

cmd=${1:-}
case "$cmd" in
  up)
    [ -n "$SRC" ] || { echo "set ARTIFACT_MCP_SRC to an artifact-mcp checkout" >&2; exit 2; }
    [ -f "$PLANNER" ] || { echo "planner HTML missing: $PLANNER (run python3 build.py in planner/)" >&2; exit 2; }
    mkdir -p "$OUT"
    rev=$(git -C "$SRC" rev-parse --short HEAD)
    image="artifact-mcp-emu-dev:$rev"
    docker image inspect "$image" > /dev/null 2>&1 || docker build -q -t "$image" "$SRC" > /dev/null
    # A throwaway publish key for this run only; never printed.
    key=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
    audit_key=$(head -c 32 /dev/urandom | base64)
    webhook_key=$(head -c 32 /dev/urandom | base64)
    docker rm -f "$SERVER" "$PROXY" "$TTS" > /dev/null 2>&1 || true
    # Throwaway worker token for this run; shared with the server through a tmpfs file.
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$OUT/fake-tts.token"
    chmod 644 "$OUT/fake-tts.token"
    docker run -d --name "$TTS" --network host -v "$HERE":/harness:ro -v "$OUT/fake-tts.token":/run/fake-tts.token:ro \
      -e LISTEN_PORT=$TTS_PORT -e TOKEN_FILE=/run/fake-tts.token "$image" node /harness/fake-tts.mjs > /dev/null
    docker run -d --name "$SERVER" --network host --tmpfs /data \
      -v "$OUT/fake-tts.token":/run/fake-tts.token:ro \
      -e TTS_ENABLED=1 -e TTS_WORKER_URL=http://127.0.0.1:$TTS_PORT -e TTS_WORKER_TOKEN_FILE=/run/fake-tts.token \
      -e PORT=$SERVER_PORT -e LISTEN_HOST=127.0.0.1 -e TRUST_ACCESS_HEADERS=1 -e DATA_DIR=/data \
      -e NODE_ENV=development -e PUBLIC_BASE_URL=http://localhost:$PROXY_PORT \
      -e ORG_EMAIL_DOMAINS="emulator.test:$ORG" -e ARTIFACT_API_KEYS="emu:$ORG:$key" \
      -e AUDIT_LEDGER_HMAC_KEY="$audit_key" -e WEBHOOK_ENC_KEY="$webhook_key" \
      "$image" > /dev/null
    docker run -d --name "$PROXY" --network host -v "$HERE":/harness:ro \
      -e LISTEN_PORT=$PROXY_PORT -e UPSTREAM_PORT=$SERVER_PORT -e TEST_EMAIL=$EMAIL \
      "$image" node /harness/identity-proxy.mjs > /dev/null
    for _ in $(seq 1 60); do
      curl -fsS -o /dev/null "http://127.0.0.1:$PROXY_PORT/" 2>/dev/null && break
      sleep 1
    done
    curl -fsS -o /dev/null "http://127.0.0.1:$PROXY_PORT/" || { docker logs --tail 40 "$SERVER" >&2; exit 1; }
    cp "$PLANNER" "$OUT/planner.html"
    cp "$HERE/geo-probe.html" "$OUT/geo-probe.html"
    cp "$HERE/story.html" "$OUT/story.html"
    docker run --rm --network host -v "$HERE":/harness:ro -v "$OUT":/out:ro \
      -e MCP_URL=http://127.0.0.1:$SERVER_PORT/mcp -e MCP_KEY="$key" -w /out \
      "$image" node /harness/publish.mjs "geo-probe.html:emu/geo-probe:Geo probe" \
      "planner.html:emu/road-trip-planner:Road trip planner" "story.html:emu/listen-story:Listen story" > "$OUT/published.txt"
    {
      echo "PROBE_ID=$(awk '$1=="emu/geo-probe"{print $2}' "$OUT/published.txt")"
      echo "PLANNER_ID=$(awk '$1=="emu/road-trip-planner"{print $2}' "$OUT/published.txt")"
      echo "STORY_ID=$(awk '$1=="emu/listen-story"{print $2}' "$OUT/published.txt")"
      echo "ORIGIN=http://localhost:$PROXY_PORT"
    } > "$OUT/artifacts.env"
    cat "$OUT/artifacts.env"
    ;;
  down)
    docker rm -f "$SERVER" "$PROXY" "$TTS" > /dev/null 2>&1 || true
    echo "removed $SERVER $PROXY $TTS"
    ;;
  *)
    echo "usage: ARTIFACT_MCP_SRC=... $0 up | $0 down" >&2
    exit 2
    ;;
esac
