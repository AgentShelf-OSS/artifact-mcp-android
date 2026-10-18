#!/usr/bin/env bash
# Headless Android emulator in a container, for local viewer tests. Run on a Docker host with /dev/kvm.
#
#   build/emulator/run.sh up       # build image, install SDK parts once, create AVD, boot, wait
#   build/emulator/run.sh down     # stop and remove the container (the SDK volume stays)
#   build/emulator/run.sh shell    # adb shell on the emulator
#   build/emulator/run.sh adb ARGS # any adb command, for example: run.sh adb logcat -d
#
# The container uses --network host so adb (port 5037) and `adb reverse` reach this host's loopback.
# Nothing listens beyond loopback: the emulator console and adb ports bind to 127.0.0.1.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BUILD_TAG=$(sha256sum "$ROOT/build/Dockerfile" | cut -c1-12)
BUILD_IMAGE="artifact-mcp-android-build:$BUILD_TAG"
EMU_TAG=$(cat "$ROOT/build/Dockerfile" "$ROOT/build/emulator/Dockerfile" | sha256sum | cut -c1-12)
EMU_IMAGE="artifact-mcp-android-emulator:$EMU_TAG"
NAME=${EMU_CONTAINER:-artifact-mcp-android-emulator}
VOLUME=artifact-mcp-android-emulator-sdk
# targetSdk is 37. The google_apis image has a Google WebView with WebMessageListener support.
SYSTEM_IMAGE=${EMU_SYSTEM_IMAGE:-"system-images;android-37.0;google_apis;x86_64"}
DEVICE=${EMU_DEVICE:-pixel_7}
AVD=${EMU_AVD:-viewer-test}

adb_() { docker exec -i "$NAME" adb "$@"; }

build_images() {
  if ! docker image inspect "$BUILD_IMAGE" > /dev/null 2>&1; then
    docker build -t "$BUILD_IMAGE" "$ROOT/build"
  fi
  if ! docker image inspect "$EMU_IMAGE" > /dev/null 2>&1; then
    docker build --build-arg BUILD_IMAGE="$BUILD_IMAGE" -t "$EMU_IMAGE" "$ROOT/build/emulator"
  fi
}

# Install the emulator, platform-tools and the system image into the cached volume, then
# create the AVD if it is missing. Idempotent.
prepare_sdk() {
  docker run --rm --network bridge -v "$VOLUME":/emu-sdk "$EMU_IMAGE" bash -euc "
    if [ ! -x /emu-sdk/emulator/emulator ] || [ ! -d /emu-sdk/system-images/$(echo "$SYSTEM_IMAGE" | cut -d';' -f2) ]; then
      yes | sdkmanager --sdk_root=/emu-sdk --licenses > /dev/null
      sdkmanager --sdk_root=/emu-sdk --install emulator platform-tools '$SYSTEM_IMAGE' > /dev/null
    fi
    mkdir -p /emu-sdk/avd /emu-sdk/home
    if [ ! -f /emu-sdk/avd/$AVD.ini ]; then
      # avdmanager in this cmdline-tools build cannot resolve system images, so the AVD is written
      # directly: a Pixel 7 sized phone (1080x2400, 420 dpi) with a software navigation bar.
      sysdir=system-images/$(echo "$SYSTEM_IMAGE" | cut -d';' -f2-4 | tr ';' '/')/
      mkdir -p /emu-sdk/avd/$AVD.avd
      printf 'avd.ini.encoding=UTF-8\npath=/emu-sdk/avd/$AVD.avd\npath.rel=avd/$AVD.avd\ntarget=android-37\n' > /emu-sdk/avd/$AVD.ini
      cat > /emu-sdk/avd/$AVD.avd/config.ini <<CFG
AvdId=$AVD
avd.ini.displayname=$AVD
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
image.sysdir.1=\$sysdir
tag.id=google_apis
tag.display=Google APIs
hw.device.name=$DEVICE
hw.device.manufacturer=Google
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.keyboard=no
hw.mainKeys=no
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.ramSize=4096
vm.heapSize=512
disk.dataPartition.size=6G
hw.gps=yes
hw.sensors.orientation=yes
hw.accelerometer=yes
showDeviceFrame=no
fastboot.forceColdBoot=yes
CFG
    fi
    sdkmanager --sdk_root=/emu-sdk --list_installed 2>/dev/null | grep -E 'emulator|platform-tools|system-images'
  "
}

wait_boot() {
  local deadline=$((SECONDS + 600))
  timeout 120 docker exec "$NAME" adb wait-for-device || { docker logs --tail 40 "$NAME" >&2; exit 1; }
  until [ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    if [ $SECONDS -gt $deadline ]; then
      echo "emulator did not boot in 10 minutes" >&2
      docker logs --tail 50 "$NAME" >&2
      exit 1
    fi
    if ! docker ps -q -f name="^${NAME}$" | grep -q .; then
      echo "emulator container exited" >&2
      docker logs --tail 80 "$NAME" >&2 || true
      exit 1
    fi
    sleep 5
  done
  # Fewer moving parts in screenshots and UI dumps.
  adb_ shell settings put global window_animation_scale 0
  adb_ shell settings put global transition_animation_scale 0
  adb_ shell settings put global animator_duration_scale 0
  # Realistic insets: a hole-punch camera cutout (taller status bar) and the three-button
  # navigation bar, as on the phone the edge-to-edge bug was reported from.
  adb_ shell cmd overlay enable com.android.internal.display.cutout.emulation.hole
  adb_ shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
  adb_ shell svc power stayon true
  adb_ shell input keyevent KEYCODE_WAKEUP
  adb_ shell wm dismiss-keyguard || true
  echo "booted: $(adb_ shell getprop ro.build.version.release | tr -d '\r') (API $(adb_ shell getprop ro.build.version.sdk | tr -d '\r'))"
}

cmd=${1:-}
shift || true
case "$cmd" in
  up)
    [ -e /dev/kvm ] || { echo "/dev/kvm is missing" >&2; exit 1; }
    build_images
    prepare_sdk
    docker rm -f "$NAME" > /dev/null 2>&1 || true
    docker run -d --name "$NAME" --network host --device /dev/kvm \
      -e ANDROID_SDK_ROOT=/emu-sdk -e ANDROID_HOME=/emu-sdk \
      -v "$VOLUME":/emu-sdk -v "$ROOT":/src \
      "$EMU_IMAGE" bash -c "adb start-server && exec emulator -avd '$AVD' -no-window -no-audio \
        -gpu swiftshader_indirect -no-snapshot -no-boot-anim -no-metrics -camera-back none -camera-front none \
        -ports 5554,5555" > /dev/null
    wait_boot
    ;;
  down)
    docker rm -f "$NAME" > /dev/null 2>&1 || true
    echo "removed $NAME (volume $VOLUME kept)"
    ;;
  shell)
    exec docker exec -it "$NAME" adb shell "$@"
    ;;
  adb)
    adb_ "$@"
    ;;
  wait)
    wait_boot
    ;;
  *)
    echo "usage: $0 up|down|shell|adb ARGS|wait" >&2
    exit 2
    ;;
esac
