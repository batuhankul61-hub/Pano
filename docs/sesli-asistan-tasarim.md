# Pano'nun kendi sesli asistanı: tasarım notu

Durum: **öneri, onay bekliyor.** Kod yazılmadı. 1. aşama (dock'taki "Sesli asistan" düğmesi BYD'nin
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
