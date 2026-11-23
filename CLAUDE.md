# Pano

Araç ekranları (BYD DiLink gibi Android head unit'ler) için Jetpack Compose ile yazılmış bir Android launcher. Paket: `app.pano.launcher`, minSdk 26, targetSdk 36.

## Derleme ve çalıştırma (Windows)

`JAVA_HOME` sistemde tanımlı değil; Android Studio'nun JDK'sı kullanılıyor:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:compileDebugKotlin   # hızlı derleme kontrolü
.\gradlew.bat :app:installDebug         # emülatöre kur
```

- Emülatörler (adb: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`; kullanıcının kendi PowerShell'inde bu yol `C:\Users\batuh\AppData\Local\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Local\Android\Sdk\...`):
  - **`PanoCar10` (`-port 5556` → `emulator-5556`): araca en yakın olan.** Android 10 (API 29, google_apis), 1280×720, 160 dpi; araçtaki gibi `enable_freeform_support=1`, `force_resizable_activities=1`, Pano'ya SYSTEM_ALERT_WINDOW ve WRITE_SECURE_SETTINGS verildi. Sağ panel, sistem çubukları, bölünmüş ekran burada denenir. Başlat: `emulator.exe -avd PanoCar10 -port 5556`. İkisi açıkken kurulum için `$env:ANDROID_SERIAL = "emulator-5556"`.
  - `PanoCar` (`emulator-5554`): Android 15, 1920×1080, 240 dpi; pencere/çubuk davranışı araçtan farklı.
- Araç (BYD Atto 2, DiLink 3.0, Android 10, 1280×720): ana ekran aracın kendi launcher'ıdır, Pano normal uygulama olarak çalışır. Freeform açık. Araçta Google servisleri ve TTS yok. Spotify'ı `com.vivid.music.byd` kabuğu açar.
- Ekran görüntüsü: `adb shell screencap -p /sdcard/x.png` + `adb pull` (PowerShell'de `exec-out ... >` ikili dosyayı bozar).
- Ayarları değiştirmek için (yalnızca debug sürümde): `adb shell run-as app.pano.launcher ...` ile `shared_prefs/pano_style.xml`.
- Emülatör yavaş: kare süresi ~28 ms civarında takılıyor ve bu uygulamadan kaynaklanmıyor. Performansı gerçek cihazda değerlendir.
- Release sürümünde R8 açık (`app/proguard-rules.pro`) ve proje kökündeki `keystore.properties` ile imzalanıyor (şablon: `keystore.properties.example`; dosya yoksa uyarıyla debug anahtarına düşer). Anahtar (`pano-release.jks`) depoya girmez, kaybolursa güncelleme yapılamaz: yedekle.

## Mimari

- `MainActivity.kt`: `PanoApp` ekranı seçer (`Screen`: Home, Climate, Apps, Settings). Her şey `ProvideMapHolder` ile sarılıdır; geliştirici modunda seçilen `LayoutStyle`'a göre `CarPlayApp` ya da `ClassicApp` çizilir.
- `ui/Components.kt`: `DesignCanvas` her şeyi **1600×900 dp'lik tasarım tuvaline** ölçekler. Ölçüleri bu tuvale göre yaz. Ayrıca paylaşılan bileşenler (`PanoCard`, `Segmented`, `PanoSwitch`, `RoundIconButton`…) buradadır.
- `ui/CarPlayScreen.kt`: iOS / CarPlay havasındaki düzen. Renkli duvar kağıdı, yarı saydam cam paneller (`glass()` renkleri, `Modifier.glass`), solda uygulama çubuğu, altta klima şeridi.
- `ui/HomeScreen.kt` + `ui/Dock.kt`: klasik düzen.
- `ui/MapCard.kt`: MapLibre + OpenFreeMap (anahtarsız). Uygulamada **tek bir paylaşılan MapView** var (`MapHolder`); stil yalnızca değişince yeniden yüklenir. Atıf yazısı lisans gereği görünür kalmalı.
- `ui/AppIcon.kt`: iOS tarzı squircle uygulama ikonu. `settings/FavoritesStore.kt`: favori uygulamalar.
- `ui/PanoIcons.kt`: kendi ikon setimiz (24×24, ince yuvarlak çizgi, SF Symbols havası). Yeni ikonlar aynı stilde eklenir.
- `ui/theme/Theme.kt`: `PanoColors` (gece/gündüz), `activeColor()`, `linkColor()`.
- `settings/StyleSettings.kt`: stil ayarları (SharedPreferences). Kalıcı ayarlar için örnek alınacak yapı.
- Veri katmanları `StateFlow` ile sunulur: `media/MediaHub.kt` (çalan müzik, bildirim dinleyicisi `MediaListenerService`), `navigation/NavHub.kt` (Maps/Yandex/Waze bildiriminden manevra), `weather/WeatherHub.kt` (Open-Meteo), `vehicle/ClimateStore.kt` (klima kalıcılığı).
- `vehicle/VehicleHub.kt`: araç verisi (`state`, türetilmiş `isDriving`). Sıra: BYD veri yolu (`vehicle/BydBus.kt`, ESMER-UI'den MIT ile uyarlandı) → hız için GPS → örnek değerler + `isPreview = true`. Alanlar null = bilinmiyor. `Vehicle.kt`'deki `VehicleSource` yalnızca henüz bağlanmamış CarPlay için duruyor.
  - BYD veri yolu ek izin istemiyor (ESMER-UI'ye göre); logcat etiketi `VehicleHub`/`BydBus`.
  - Emülatörde `adb emu geo fix` GNSS'e ulaşmıyor; GPS hızını test sağlayıcıyla dene: `adb shell appops set com.android.shell android:mock_location allow`, `adb shell cmd location providers add-test-provider gps`, `... set-test-provider-enabled gps true`, sonra saniyede bir `... set-test-provider-location gps --location 41.0082,28.9784` (her seferinde konumu biraz kaydır). Bitince `remove-test-provider gps`.
  - Worktree'de `local.properties` yok; derlemeden önce ana checkout'tan kopyala.
- `system/SidePanel.kt` + `system/PanelFrame.kt`: CarPlay düzeninde uygulamalar Pano'nun sağ panelinde **freeform pencere** olarak açılır (`Launch.sideBySide`). Android 10 kuralları: Pano'ya dokunmak pencereyi arkaya atar (SidePanel geri getirir); yeni pencere eski bir pencereyle köşe çakışırsa 80 px kaydırılır (paket adından yuva); freeform görünürken sistem çubukları zorla ve opak gösterilir (Pano o zaman çubuklara yer açar). Çerçeve "diğer uygulamaların üzerinde" katmanlarıdır.
- `system/SystemActions.kt`: dock'taki Geri / Son uygulamalar / Yarım ekran (erişilebilirlik hizmeti; BYD'de Erişilebilirlik menüsü yok, Pano izinle kendini açar) ve döndürme (`byd.intent.action.DIRECT_ROTATION`). `system/SystemBars.kt`: `policy_control=immersive.full=*`. BYD SystemUI yayınları: sesli asistan `com.intent.action.StartVoice_From_Launcher` (HardKeyReceiver), bölünmüş ekranda taraf değiştir `byd.intent.action.DOUBLE_FINGER_SWAP`.
- `assistant/`: Pano Asistan (Gemini Live `gemini-3.8-live`, sesli, function calling). `LiveProtocol` mesaj biçimi, `PanoAssistant` oturum, `AssistantTools` araç tanımları + `PanoBackend` (hub'lara bağlantı), `AssistantKey` (Keystore ile şifreli API anahtarı), `AssistantOverlay` gösterge. Ayrıntı ve maliyet: `docs/sesli-asistan-tasarim.md`. Anahtarı asla loglama ya da commit'leme.
- `navigation/PanoNav.kt` + `RouteEngine.kt`: Pano'nun kendi navigasyonu (Photon arama, FOSSGIS Valhalla Türkçe rota, ilerleme, yeniden rota, TTS).

## Kurallar

- Arayüz metinleri ve kod yorumları **Türkçe**.
- Uydurma veri gösterme. Kaynağı olmayan bilgi için ya boş durum ya da açıkça "Önizleme" yaz.
- Sürüşte kullanılacak kontroller büyük olmalı (dokunma alanı ≥ ~56 dp).
- Klasik düzeni bozmadan çalış. CarPlay'e özgü stil `CarPlayScreen.kt`'de kalsın.
- Ayrı session'lar kendi worktree/dalında çalışır. **Dalları kullanıcı onayı olmadan `main`'e birleştirme**, uzak depoya push etme.
- Commit mesajları Türkçe ve anlamlı olsun.
