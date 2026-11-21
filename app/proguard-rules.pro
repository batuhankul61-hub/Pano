# MapLibre kendi kurallarını AAR içinde getiriyor; ek olarak yerel (JNI) çağrılar için sınıfları koru.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Bildirim dinleyicisi manifest'ten adıyla başlatılıyor.
-keep class app.pano.launcher.media.MediaListenerService { *; }
