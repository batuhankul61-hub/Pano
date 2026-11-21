plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.pano.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pano.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 kodu küçültür ve optimize eder; Compose release'de debug'a göre belirgin şekilde akıcı çalışır.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Şimdilik debug anahtarıyla imzalanıyor; mağazaya çıkmadan önce gerçek anahtar eklenmeli.
            signingConfig = signingConfigs.getByName("debug")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Harita: MapLibre (açık kaynak) + OpenFreeMap (ücretsiz, anahtar gerektirmez)
    implementation("org.maplibre.gl:android-sdk:13.7.1")
}
