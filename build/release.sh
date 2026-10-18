#!/usr/bin/env bash
# Signed release build.
#
# Run on a host that has the homelab-secrets client (pve1) and SSH access to the Docker host:
#
#   build/release.sh
#
# Flow (no signing material is written to the repository, the NAS, argv or container config):
#   1. homelab-secrets exec fetches /ARTIFACT_ANDROID_KEYSTORE_B64 and
#      /ARTIFACT_ANDROID_KEYSTORE_PASSWORD into this script's environment only.
#   2. The two values are streamed over SSH stdin to build/release-container.sh on the Docker host.
#   3. That script pipes them into `docker run -i`; build/release-inner.sh reads them from stdin,
#      decodes the keystore into a container tmpfs and builds from a tmpfs copy of the sources.
#   4. Only the signed APK and its SHA-256 are written back, to dist/.
#
# Environment:
#   ARTIFACT_ANDROID_DOCKER_HOST  SSH target of the Docker host (default root@192.168.0.110, VM310)
#   ARTIFACT_ANDROID_REMOTE_ROOT  repository path on that host (default: this path; shared NAS mount)
#   HOMELAB_SECRETS_CONFIG        reader profile (default: the client's default profile)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
HOST=${ARTIFACT_ANDROID_DOCKER_HOST:-root@192.168.0.110}
REMOTE_ROOT=${ARTIFACT_ANDROID_REMOTE_ROOT:-$ROOT}

if [ -z "${ARTIFACT_ANDROID_KEYSTORE_B64:-}" ] || [ -z "${ARTIFACT_ANDROID_KEYSTORE_PASSWORD:-}" ]; then
  config=()
  if [ -n "${HOMELAB_SECRETS_CONFIG:-}" ]; then config=(--config "$HOMELAB_SECRETS_CONFIG"); fi
  exec homelab-secrets "${config[@]}" exec \
    --secret /ARTIFACT_ANDROID_KEYSTORE_B64=ARTIFACT_ANDROID_KEYSTORE_B64 \
    --secret /ARTIFACT_ANDROID_KEYSTORE_PASSWORD=ARTIFACT_ANDROID_KEYSTORE_PASSWORD \
    -- "$0" "$@"
fi

# printf is a shell builtin: the values never appear in a process argument list.
{
  printf '%s\n' "$ARTIFACT_ANDROID_KEYSTORE_B64"
  printf '%s\n' "$ARTIFACT_ANDROID_KEYSTORE_PASSWORD"
} | ssh -o BatchMode=yes "$HOST" "cd '$REMOTE_ROOT' && build/release-container.sh"
