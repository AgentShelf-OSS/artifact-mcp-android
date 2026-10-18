plugins {
    alias(libs.plugins.android.application)
}

// Release signing is supplied only through the environment of the release container
// (see build/release.sh). Nothing about the key lives in the repository.
val releaseKeystore: String? = System.getenv("ARTIFACT_ANDROID_KEYSTORE_FILE")
val releaseStorePassword: String? = System.getenv("ARTIFACT_ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias: String = System.getenv("ARTIFACT_ANDROID_KEY_ALIAS") ?: "artifact-mcp-android"
val releaseKeyPassword: String? = System.getenv("ARTIFACT_ANDROID_KEY_PASSWORD") ?: releaseStorePassword
val hasReleaseSigning = !releaseKeystore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank()

android {
    namespace = "ai.agentshelf.artifactmcp"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "ai.agentshelf.artifactmcp"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "2g" }
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
        // Newer dependency notices are informational; upgrades are deliberate.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
