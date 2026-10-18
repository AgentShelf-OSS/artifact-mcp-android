#!/usr/bin/env bash
# Runs inside the build container. Reads the keystore (base64, one line) and password from stdin.
set -euo pipefail
umask 077

IFS= read -r ks_b64 || true
IFS= read -r ks_pw || true
if [ -z "${ks_b64:-}" ] || [ -z "${ks_pw:-}" ]; then
  echo "release-inner: signing input missing on stdin" >&2
  exit 2
fi
printf '%s' "$ks_b64" | base64 -d > /run/keys/release.p12
unset ks_b64
export ARTIFACT_ANDROID_KEYSTORE_FILE=/run/keys/release.p12
export ARTIFACT_ANDROID_KEYSTORE_PASSWORD="$ks_pw"
export ARTIFACT_ANDROID_KEY_ALIAS=artifact-mcp-android
unset ks_pw
trap 'rm -f /run/keys/release.p12' EXIT

# Build from a tmpfs copy so intermediates and Gradle's project cache never reach the host.
mkdir -p /work/src
(cd /src && tar --exclude=./app/build --exclude=./.gradle --exclude=./.gradle-root-build \
  --exclude=./dist --exclude=./.git --exclude=./.kotlin -cf - .) | tar -xf - -C /work/src
cd /work/src

# AGP creates unit-test tasks only for the debug build type, so the release gate runs those.
./gradlew --no-daemon --console=plain --no-build-cache --project-cache-dir /work/project-cache \
  lintRelease testDebugUnitTest assembleRelease

version=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
apk=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$apk" ]; then
  echo "release-inner: signed APK not produced" >&2
  exit 3
fi
apksigner verify --verbose --print-certs "$apk" >&2
name="artifact-mcp-android-$version.apk"
cp "$apk" "/out/$name"
(cd /out && sha256sum "$name" > "$name.sha256" && cat "$name.sha256")
