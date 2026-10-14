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
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
