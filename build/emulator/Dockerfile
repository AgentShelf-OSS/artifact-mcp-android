# Headless Android emulator for local viewer tests (see build/emulator/README section in README.md).
# Based on the pinned build image, which already has the JDK and the SDK command-line tools.
# The emulator, platform-tools and the system image are NOT baked in: run.sh installs them into the
# named volume artifact-mcp-android-emulator-sdk on first use, so they are downloaded once.
ARG BUILD_IMAGE
FROM ${BUILD_IMAGE}

# Shared libraries the emulator binary loads even with -no-window.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 libxdamage1 libxrandr2 \
      libxkbfile1 libxkbcommon0 libxkbcommon-x11-0 libasound2t64 libdbus-1-3 libgl1 libegl1 \
      libfontconfig1 libx11-xcb1 libxcb-cursor0 libbsd0 xxd \
 && rm -rf /var/lib/apt/lists/*

ENV EMU_SDK=/emu-sdk \
    ANDROID_AVD_HOME=/emu-sdk/avd \
    ANDROID_EMULATOR_HOME=/emu-sdk/home
ENV PATH=/emu-sdk/platform-tools:/emu-sdk/emulator:${PATH}
WORKDIR /src
