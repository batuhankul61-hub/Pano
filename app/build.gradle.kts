import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Sürüm kodu: HEAD'e kadarki commit sayısı. */
val gitCommitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "app.pano.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pano.launcher"
        minSdk = 26
        targetSdk = 36
        // Sürüm kodu = git commit sayısı: her yeni commit daha büyük; uygulama içi güncelleme
        // ("v<versionCode>" etiketli GitHub sürümü) bununla karşılaştırır. Git yoksa 1.
        versionCode = gitCommitCount
        versionName = "0.2.$gitCommitCount"
    }

    // Release imzası: proje kökündeki keystore.properties'ten okunur (depoya girmez).
    // Dosya yoksa derleme kırılmaz; debug anahtarıyla imzalanır ve uyarı basılır.
    val keystoreProps = Properties()
    val keystoreFile = rootProject.file("keystore.properties")
    val hasReleaseKey = keystoreFile.exists()
    if (hasReleaseKey) keystoreFile.inputStream().use { keystoreProps.load(it) }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 kodu küçültür ve optimize eder; Compose release'de debug'a göre belirgin şekilde akıcı çalışır.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("UYARI: keystore.properties bulunamadı; release sürümü DEBUG anahtarıyla imzalanıyor.")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
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

    // Pano Asistan: Gemini Live'a WebSocket bağlantısı
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Birim testleri (JVM): Android'in org.json'u testte boş, gerçeği eklenir.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
