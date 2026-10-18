plugins {
    alias(libs.plugins.android.application) apply false
}

// `build/` holds the container build scripts, so the root project writes elsewhere.
layout.buildDirectory.set(file(".gradle-root-build"))
