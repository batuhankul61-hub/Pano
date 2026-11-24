# Pano'nun kendi sesli asistanı: tasarım notu

Durum: **B (Gemini Live) uygulandı**, bkz. 5. bölüm. 1. aşama (dock'taki "Sesli asistan" düğmesi BYD'nin
asistanını ya da Android'in sesli komutunu açıyor) yapıldı.

## 1. Araçta önce bakılacaklar (araç bağlıyken, park halinde)

Araç bu not yazılırken ağda değildi. Bağlanınca şunlara bakılmalı:

```sh
adb shell pm list packages | grep -iE "google|gms|vending|speech|tts|iflytek|cerence|nuance"
adb shell dumpsys package com.byd.vrassistant | grep -A3 -E "Activity|Action"
adb shell settings get secure voice_recognition_service
adb shell settings get secure assistant
```

`SpeechRecognizer.isRecognitionAvailable()` sonucunu Pano'nun açılışında logcat'e yazdırmak tek satırlık bir
iş. İstenirse eklenir.

Beklenti (doğrulanmadı): DiLink'in Çin dışı sürümlerinde Google Play hizmetleri genelde yok. BYD'nin
asistanı kendi motorunu (iFlytek/Cerence benzeri) kullanıyor ve dışarıya `RecognitionService` açmıyor
olabilir. Bu durumda Android'in `SpeechRecognizer`'ı araçta çalışmaz. Aşağıdaki seçim buna göre yapılmalı.

## 2. Seçenekler

| | A. Android `SpeechRecognizer` + kural tabanlı komutlar | B. Gemini Live (Mario'daki gibi) | C. Cihaz üstü tanıma (Vosk/whisper.cpp) + kurallar |
|---|---|---|---|
| Araçta çalışır mı | Ancak araçta bir tanıma servisi varsa (bkz. 1) | Evet: ham ses gönderilir, araçta tanıma servisi gerekmez | Evet, çevrimdışı |
| İnternet | Servise bağlı | Gerekli (araç modemi ya da telefon hotspot'u) | Gerekmez |
| Anlama | Sabit kalıplar ("klimayı 22 yap") | Serbest cümle, function calling, sesli cevap | Sabit kalıplar; Türkçe model ~50 MB+ |
| Maliyet | Yok | Dakika başı ses token'ı (güncel fiyat Google AI Studio sayfasından doğrulanmalı) | Yok; APK büyür |
| Gizlilik | Servise göre | Ses Google'a gider | Ses cihazdan çıkmaz |
| İş | Küçük | Orta (Mario'dan taşınır) | Orta-büyük |

## 3. mario-agent'tan neler taşınır

`batuhankul61-hub/mario-agent`: Gemini Live (`gemini-3.8-live`) + FastAPI + tarayıcı. Olduğu gibi
kullanılamaz, çünkü sunucu ev bilgisayarında çalışıyor ve araç yoldayken ona ulaşamaz. Ayrıca araçları
Windows'a özel. Taşınabilecekler:

- **Live oturum istemcisi:** WebSocket, 16 kHz PCM mikrofon akışı, gelen sesi çalma, bağlam sıkıştırma ve
  `session_resumption` ile kopmayan oturum. Bunların Kotlin'e yazılması gerekir (`AudioRecord`/`AudioTrack`
  + OkHttp WebSocket).
- **Kısa ömürlü token deseni:** API anahtarı APK'ya gömülmez. Seçenekler: (1) Mario'nun `POST /api/token`
  ucunu herkese açık küçük bir sunucuya (ör. Cloud Run) taşımak, (2) anahtarı kullanıcı ilk kurulumda
  Pano ayarlarına girer ve yalnızca araçta saklanır (tek kullanıcı için kabul edilebilir).
- **🔒 sesli onay akışı:** fiziksel hareket eden her şey için (kilit, bagaj, ayna, şarj kapağı). Sürüşte
  bunlar hiç çağrılamaz.
- **Araç araçları (function calling):** `set_climate` (`BydClimate`), `navigate_to` (geo: intent),
  `media_control` (`MediaHub`), `open_app`, `vehicle_status` (`VehicleHub`: batarya, menzil, lastikler,
  tüketim), `weather` (`WeatherHub`), `read_messages` (`MessageHub`; içerik `untrusted_content`).
- **"Mario" deyince başlama** Chrome'un konuşma tanımasına dayanıyor. Araçta yoksa uyandırma sözcüğü yerine
  dock/direksiyon düğmesi kullanılır. Sürekli dinleme gizlilik ve pil açısından da istenmez.

## 4. Öneri

1. Önce 1. bölümdeki kontrolleri araçta yap.
2. Araçta tanıma servisi **varsa**: A ile başla (ücretsiz, küçük iş). Klima, navigasyon ve müzik için ~10
   kalıp yeterli.
3. **Yoksa** (beklenen): B. Mario'nun Live istemcisini ve araç listesini Pano'ya taşı. Anahtar ilk etapta
   ayarlardan girilsin. Sürüşte yalnızca okuma ve klima/medya/navigasyon araçlarına izin verilsin.
4. Her durumda: mikrofon yalnızca düğmeye basınca açılır, ekranda dinlendiğine dair açık bir gösterge
   bulunur, konuşma kaydı tutulmaz.

Onay gereken kararlar: B'nin ücretli olması, sesin Google'a gitmesi ve API anahtarının nerede tutulacağı.

## 5. Uygulama (Kasım 2026): B seçildi — Pano Asistan

Kullanıcı kararı: Gemini Live, anahtarı kendisi Pano Ayarları → Pano Asistan'a girer; ücret ve sesin Google'a
gitmesi kabul edildi. Kod `assistant/` paketinde.

- **Uç nokta:** `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=…`
- **Model:** `models/gemini-3.8-live` (Google'ın önerdiği Live modeli; doğal ses, Türkçe dahil dili kendi seçer), ses `Kore`.
  Değiştirmek için `LiveProtocol.MODEL`.
- **Ses:** mikrofon 16 kHz PCM (`AudioRecord`, VOICE_RECOGNITION, varsa yankı/gürültü bastırma), cevap 24 kHz
  (`AudioTrack`, USAGE_ASSISTANT). Oturum boyunca ses odağı `GAIN_TRANSIENT_MAY_DUCK`: müzik kısılır.
  Hoparlör sesi mikrofona girip modelin sözünü kesmesin diye asistan konuşurken mikrofon gönderilmez.
- **Araçlar:** `set_climate`, `navigate_to`, `stop_navigation`, `media_control` (hiçbir şey çalmıyorken `play` müzik
  uygulamasını açar), `open_app`, `vehicle_status`, `weather` (bugün + 4 günlük tahmin), `set_volume` (medya ses düzeyi),
  `current_location` (Photon ters arama). Fiziksel hareket eden hiçbir şey yok. `vehicle_status` önizlemede örnek değerleri modele vermez.
- **Direksiyondaki sesli asistan tuşu** (`VoiceKey`, Ayarlar → Pano Asistan, varsayılan açık, API anahtarı yoksa
  devre dışı). Firmware'de (13.1.33) tuş BYD'ye özel kod **327**; `PhoneWindowManager` onu uygulamalara vermez,
  bırakılınca `MediaKeyHandler.sendVoiceButtonIntent` ile korumalı `android.intent.action.MEDIA_VOICE` yayınını
  gönderir. BYD asistanı (`com.byd.vrassistant`, `HardKeyReceiverEx`) da bunu dinler ama mikrofon başkasındayken
  (`AudioMicReceiver.isMicRelease`) yok sayar. Pano, Android Auto gibi `android.broadcast.voice.request.start`
  (`who=androidauto`, korumalı değil) göndererek tuşu üstlenir; kapatınca `...request.release` ile geri bırakır.
  Tuş dinleyicisi bildirim dinleyici / erişilebilirlik hizmeti / ana ekran açılınca kaydolur; Pano süreci yoksa tuş
  BYD asistanını açar. Pano ekranda değilken oturum boyunca ön plan hizmeti (`AssistantForegroundService`) çalışır
  (yoksa Android arka planda mikrofona sessizlik verir); gösterge görünmez, yalnızca ses. Emülatörde tuş:
  `adb shell am broadcast -a android.intent.action.MEDIA_VOICE` (araçta korumalı, adb ile gönderilemez).
  Araçta doğrulanacaklar: logcat `VoiceKey` ve BYD'nin `NEU_HardKeyReceiverEx` / `NEU_AudioMicReceiver` satırları;
  ikinci BYD asistanı `com.byd.vrassistant.xf` (SpeechHMI) da `MEDIA_VOICE` dinliyor, bu araçta çalışıp çalışmadığı
  ve aynı mikrofon protokolüne uyup uymadığı bilinmiyor; BYD asistanının servisi yeniden başlarsa bayrağı sıfırlanır
  (Pano her ana ekran açılışında ve her tuşta isteği tazeler).
- **Konuşma algılama (Kasım 2026):** Google'ın otomatik konuşma algılaması (`automaticActivityDetection`) kapalı.
  Açıkken arka plan gürültüsünde konuşmanın başladığını görüp bittiğini görmüyor, cevap vermeden bekliyordu
  (emülatörde 8 oturumda 3; araçta yol gürültüsüyle daha sık beklenir). Turun başını ve sonunu Pano bildirir
  (`activityStart` / `activityEnd`): `TurnDetector` mikrofon seviyesinden, ortam gürültüsüne uyan eşikle
  (oturum başında 200 ms ölçüm, sonra yavaş ortalama) konuşmayı ve 0,8 sn sessizliği bulur, başlangıçtan önceki
  0,3 sn de gönderilir. Yalnızca konuşma gönderildiği için dinleme maliyeti de düştü (oturum başına ~2 sn).
- **Cevapsız oturum:** Google ara sıra yeni açılan bir oturumu baştan tepkisiz bırakıyor (emülatörde ~5'te 1; döküm
  bile gelmiyor, aynı oturumdaki sonraki konuşmalar da cevapsız). Konuşma bittikten 3 sn içinde hiçbir şey gelmezse
  yeni bir oturum açılıp (sürdürme anahtarı **olmadan**: sürdürmek aynı tepkisiz oturuma dönüyor) aynı ses tekrar
  gönderilir; yine gelmezse ekranda "Anlayamadım, tekrar söyler misin?" yazar. 28 konuşmalık denemede 27'si
  cevaplandı.
- **Tanı:** her oturumun sonunda logcat'e ve konuşma günlüğüne özet yazılır (gönderilen ses, Google'ın döküm
  sayısı, Pano'nun algıladığı konuşma, yeniden gönderim, gürültü tabanı, en yüksek mikrofon seviyesi).
  Emülatörde bilgisayar mikrofonu için her açılışta: `adb emu avd hostmicon`.
- **Oturum:** dock'taki ✦ "Pano Asistan" düğmesi (🎤 hâlâ BYD asistanı). 12 sn konuşma olmazsa kapanır, en fazla
  10 dk. `goAway` gelince `sessionResumption` anahtarıyla yeniden bağlanır.
- **Anahtar:** Android Keystore AES-GCM ile şifreli SharedPreferences (`pano_assistant`). Loglanmaz.
- **Konuşma günlüğü** (`ConversationLog`, varsayılan kapalı): Ayarlar → Pano Asistan → Konuşma günlüğü. Açıkken
  dökümler, cevaplar ve araç çağrılarının sonuçları `files/asistan-gunlugu.txt`'ye yazılır (yalnızca cihazda,
  1 MB'ı aşınca eski yarısı atılır), Ayarlar'dan okunur. Debug'da: `adb shell run-as app.pano.launcher cat files/asistan-gunlugu.txt`.

**Maliyet hesabı** (ücretli katman, Kasım 2026; güncelini aistudio.google.com/docs/pricing'den doğrula): ses giriş
$3 / 1M token, ses çıkış $12 / 1M token, ses 25 token/sn. Yani dinlenen her dakika ≈ $0,0045, asistanın konuştuğu her
dakika ≈ $0,018. Live'da her turda konuşma geçmişi yeniden giriş token'ı sayılır, uzun oturum dakika başına
pahalılaşır. Gerçek tüketim: logcat'te `PanoAsistan` etiketli `token:` satırları ve AI Studio'daki kullanım sayfası.

**Araçta denenecekler:** mikrofonun gerçekten sürücüyü duyması (BYD'de VOICE_RECOGNITION kaynağı), hoparlör yankısı,
müziğin kısılıp geri gelmesi, hotspot'ta gecikme, Türkçe anlama, klimanın araca gitmesi (`BydClimate`), navigasyon
konumu, sağ panelde uygulama açma.
