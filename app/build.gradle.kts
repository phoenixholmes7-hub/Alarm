import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.relentless.alarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.relentless.alarm"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed key so each new APK installs as an update over the previous one
    // (keeps your alarms). This is a sideload-only key, not a secret.
    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "relentless"
            keyAlias = "relentless"
            keyPassword = "relentless"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Barcode scanning: CameraX + Google ML Kit (bundled model, works offline, no Play Services needed)
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.lifecycle:lifecycle-runtime:2.6.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
