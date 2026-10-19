#!/usr/bin/env bash
# Headless Android emulator in a container, for local viewer tests. Run on a Docker host with /dev/kvm.
#
#   build/emulator/run.sh up       # build image, install SDK parts once, create AVD, boot, wait
#   build/emulator/run.sh down     # stop and remove the container (the SDK volume stays)
#   build/emulator/run.sh shell    # adb shell on the emulator
#   build/emulator/run.sh adb ARGS # any adb command, for example: run.sh adb logcat -d
#
#   EMU_PROFILE=car build/emulator/run.sh up    # Android Automotive OS instead of the phone
#
# The car profile boots an Android Automotive OS (AAOS) emulator in its own container
# (artifact-mcp-android-car, adb serial emulator-5556), so the car media center renders the app's
# browse tree, covers and now-playing buttons (test-car.sh). Both profiles share the SDK volume.
#
# The container uses --network host so adb (port 5037) and `adb reverse` reach this host's loopback.
# Nothing listens beyond loopback: the emulator console and adb ports bind to 127.0.0.1.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BUILD_TAG=$(sha256sum "$ROOT/build/Dockerfile" | cut -c1-12)
BUILD_IMAGE="artifact-mcp-android-build:$BUILD_TAG"
EMU_TAG=$(cat "$ROOT/build/Dockerfile" "$ROOT/build/emulator/Dockerfile" | sha256sum | cut -c1-12)
EMU_IMAGE="artifact-mcp-android-emulator:$EMU_TAG"
VOLUME=artifact-mcp-android-emulator-sdk
PROFILE=${EMU_PROFILE:-phone}
if [ "$PROFILE" = car ]; then
  # Newest AAOS image with Google APIs (API 35). Landscape 1408x792 at 160 dpi, the size of the
  # Android Studio "Automotive (1408p landscape)" device.
  NAME=${EMU_CONTAINER:-artifact-mcp-android-car}
  SYSTEM_IMAGE=${EMU_SYSTEM_IMAGE:-"system-images;android-35-ext15;android-automotive;x86_64"}
  DEVICE=${EMU_DEVICE:-automotive_1408p_landscape}
  AVD=${EMU_AVD:-car-test}
  TARGET=android-35
  TAG_ID=android-automotive
  TAG_DISPLAY="Android Automotive"
  LCD_W=1408 LCD_H=792 LCD_DENSITY=160 MANUFACTURER=Google
  PORTS=5556,5557
else
  NAME=${EMU_CONTAINER:-artifact-mcp-android-emulator}
  # targetSdk is 37. The google_apis image has a Google WebView with WebMessageListener support.
  SYSTEM_IMAGE=${EMU_SYSTEM_IMAGE:-"system-images;android-37.0;google_apis;x86_64"}
  DEVICE=${EMU_DEVICE:-pixel_7}
  AVD=${EMU_AVD:-viewer-test}
  TARGET=android-37
  TAG_ID=google_apis
  TAG_DISPLAY="Google APIs"
  LCD_W=1080 LCD_H=2400 LCD_DENSITY=420 MANUFACTURER=Google
  PORTS=5554,5555
fi

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
      # directly: a Pixel 7 sized phone (1080x2400, 420 dpi) with a software navigation bar, or
      # the 1408x792 car display.
      sysdir=system-images/$(echo "$SYSTEM_IMAGE" | cut -d';' -f2-4 | tr ';' '/')/
      mkdir -p /emu-sdk/avd/$AVD.avd
      printf 'avd.ini.encoding=UTF-8\npath=/emu-sdk/avd/$AVD.avd\npath.rel=avd/$AVD.avd\ntarget=$TARGET\n' > /emu-sdk/avd/$AVD.ini
      cat > /emu-sdk/avd/$AVD.avd/config.ini <<CFG
AvdId=$AVD
avd.ini.displayname=$AVD
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
image.sysdir.1=\$sysdir
tag.id=$TAG_ID
tag.display=$TAG_DISPLAY
hw.device.name=$DEVICE
hw.device.manufacturer=$MANUFACTURER
hw.lcd.width=$LCD_W
hw.lcd.height=$LCD_H
hw.lcd.density=$LCD_DENSITY
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
  if [ "$PROFILE" != car ]; then
    # Realistic insets: a hole-punch camera cutout (taller status bar) and the three-button
    # navigation bar, as on the phone the edge-to-edge bug was reported from.
    adb_ shell cmd overlay enable com.android.internal.display.cutout.emulation.hole
    adb_ shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
  fi
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
      -e ANDROID_SDK_ROOT=/emu-sdk -e ANDROID_HOME=/emu-sdk -e ANDROID_SERIAL="emulator-${PORTS%%,*}" \
      -v "$VOLUME":/emu-sdk -v "$ROOT":/src \
      "$EMU_IMAGE" bash -c "adb start-server && exec emulator -avd '$AVD' -no-window -no-audio \
        -gpu swiftshader_indirect -no-snapshot -no-boot-anim -no-metrics -camera-back none -camera-front none \
        -ports $PORTS" > /dev/null
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
