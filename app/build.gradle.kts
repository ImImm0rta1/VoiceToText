plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.offline.stt"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.offline.stt"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DGGML_OPENMP=OFF") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true; buildConfig = true }
    flavorDimensions += "net"
    productFlavors {
        create("offline") {
            dimension = "net"
            buildConfigField("boolean", "CAN_DOWNLOAD", "false")
            manifestPlaceholders["appLabel"] = "Offline Dictation"
        }
        create("online") {
            dimension = "net"
            applicationIdSuffix = ".online"
            buildConfigField("boolean", "CAN_DOWNLOAD", "true")
            manifestPlaceholders["appLabel"] = "Offline Dictation (DL)"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
