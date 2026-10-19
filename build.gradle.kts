// Top-level build file. Plugins are declared here and applied in app/build.gradle.kts.
//
// AGP 9 has Kotlin support built in, so there is NO "kotlin-android" plugin here.
// AGP 9 uses Kotlin 2.2.10 by default. We use a newer Kotlin, and the Android docs say to
// request it like this (https://developer.android.com/build/releases/agp-9-0-0-release-notes).
// IMPORTANT: this version must be the same as `kotlin` in gradle/libs.versions.toml
// (the version catalog cannot be used inside a buildscript block).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
