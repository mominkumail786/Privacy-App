plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.privacyguard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.privacyguard"
        minSdk = 26
        targetSdk = 35 // Android 15
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service) // LifecycleService, so CameraX can bind to our service

    // Compose UI (the BOM picks matching versions for all Compose libraries)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Face detection (ML Kit, bundled model: works offline) and face embedding (TensorFlow Lite)
    implementation(libs.mlkit.face.detection)
    implementation(libs.tflite)

    // Unit tests that run on your computer (./gradlew testDebugUnitTest)
    testImplementation(libs.junit)
}
