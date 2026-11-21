plugins {
    // AGP 9 Kotlin desteğini kendi içinde getiriyor; ayrıca kotlin-android eklentisi gerekmiyor.
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
