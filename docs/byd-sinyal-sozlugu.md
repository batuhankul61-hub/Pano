# BYD araç veri yolu sinyal sözlüğü

Bu belge, BYD Atto 2'nin kendi OTA firmware'inden (`13.1.33.2511080.1`, DiLink 3.0,
Android 10/SDK 29, platform `msm8953_64`) çıkarılan araç veri yolu sinyallerinin
sözlüğüdür. Amaç hem **cam okuma sorununu** çözmek hem de gelecekteki özellikler
(kapı, klima, batarya, sürüş…) için doğru sinyal adlarının ve ölçeklerinin
başvuru kaynağını oluşturmak.

> Kaynak: firmware `payload.bin` → `system.img` → `system/framework/framework.jar`
> içindeki `android.hardware.bydauto` paketi (jadx ile derlemeden geri çevrildi).
> Firmware imajları repoya **girmez**; çalışma klasörü repo dışındadır.

## Nasıl çalışıyor (mimari)

BYD'nin tüm araç API'si `android.hardware.bydauto` paketindedir ve platformun
`framework.jar`'ına gömülüdür (ayrı bir `byd-*.jar` yoktur). Pano bunlara
`vehicle/BydBus.kt` üzerinden yansımayla erişir.

- **`BYDAutoDeviceManager.getInstance(context)`** — tüm cihazların kök yöneticisi.
  Okuma: `getInt(deviceType, featureId)`, `getDouble(...)`; yazma: `setInt(...)`,
  `setIntArray(...)`.
- **`BYDAuto<Grup>Device`** sarmalayıcıları (ör. `bodywork.BYDAutoBodyworkDevice`).
  Her cihazın sabit bir **cihaz türü** numarası vardır (`getType()`), okurken
  `get(deviceType, featureId)` çağrılır. Cihaz türleri aşağıda.
- **`BYDAutoFeatureIds`** — tüm sinyal kimlikleri. **Kimlikler derleme-zamanı sabiti
  DEĞİL;** statik başlatmada araç protokolüne göre hesaplanır:

  ```java
  isCanFD  = SystemProperties.get("sys.car.protocol").equals("CANFD")
          || SystemProperties.get("persist.sys.protocol.record").equals("CANFD");
  isToyota = ... .equals("TOYOTA") ...;
  // örnek:
  BODYWORK_WINDOW_LEFT_FRONT_PERCENT = isCanFD ? 947912728 : isToyota ? 52080 : 947912720;
  ```

  Pano kimliği yansımayla okuduğu için (`BYDAutoFeatureIds$Bodywork.<alan>`), Pano'nun
  işlemindeki `SystemProperties` aynı aracın değerini verir → **Pano doğru kimliği
  alır.** Yani "yanlış ad/kimlik" sorunu yok; sorun sinyalin yayınlanıp
  yayınlanmamasında (aşağıya bakın).

  Aracın protokolünü öğrenmek için (araçta):

  ```
  adb shell getprop sys.car.protocol
  adb shell getprop persist.sys.protocol.record
  ```

  `CANFD` → `isCanFD=true`, boş/başka → standart CAN (Atto 2 büyük olasılıkla
  CAN-FD, ama araçta doğrulanmalı).

### "Okuma yok" işaretleri

| Değer | Anlamı |
|------:|--------|
| `65535` | Sinyal tanımlı ama araç o an bu veriyi **yayınlamıyor** (UNAVAILABLE). `hasFeature` de bunu böyle yorumluyor. |
| `-2147482648` (`0x80000008`) | Komut/okuma hatası (yönetici yok vb.) |
| `-2147482645` | Geçersiz parametre (ör. bilinmeyen alan kodu) |

Pano `BydBus.readInt` bunları (ve `-10011`'i) null'a çeviriyor.

### Cihaz türleri (`getType()`)

| Tür | Cihaz | Tür | Cihaz |
|----:|-------|----:|-------|
| 1000 | Ac (klima) | 1023 | Setting |
| 1001 | **Bodywork (gövde: kapı/cam/kaput)** | 1025 | Radar |
| 1002 | Audio | 1026 | Reminder |
| 1004 | Light | 1028 | FuncNotice |
| 1005 | Power | 1029 | Phone |
| 1006 | Energy | 1030 | CpuTemprature |
| 1007 | Instrument | 1031 | Panorama |
| 1009 | Charging | 1032 | Ota |
| 1010 | Security | 1033 | Mqtt |
| 1011 | Gearbox (vites) | 1037 | Signal |
| 1012 | Engine | 1038 | ADAS |
| 1013 | Speed | 1040 | Rescue |
| 1014 | Statistic | 1041 | DoorLock |
| 1015 | Collision | 1042 | SafetyBelt |
| 1017 | Location | 1043 | Sensor |
| 1019 | Aux | 1045 | Dtc |
| 1020 | Motor | 1047 | RearViewMirror |

> Not: Pano `getInt`'e `deviceType=0` (DEVICE_ANY) geçiyor ve sürücü camı/kapılar bununla
> okunabiliyor, yani okumada cihaz türü zorunlu değil. Yine de bir sinyal
> `DEVICE_ANY` ile gelmezse ilgili cihaz türüyle (gövde = 1001) denemek mantıklı.

---

## 1. Camlar (öncelik) — Bodywork, tür 1001

**İki ayrı okuma yolu var; Pano şu an yalnız yüzdeyi okuyor.**

### 1a. Açıklık yüzdesi — `getWindowOpenPercent(alan)`

0–100 yüzde. Alan kodları: 1=sol ön, 2=sağ ön, 3=sol arka, 4=sağ arka,
5=panorama tavan, 6=güneşlik. Kullanılan kimlikler:

| Cam | `BYDAutoFeatureIds` alanı | CAN-FD (ondalık / hex) | Standart CAN | Pano'daki ad |
|-----|---------------------------|------------------------|--------------|--------------|
| Sol ön (sürücü) | `BODYWORK_WINDOW_LEFT_FRONT_PERCENT`  | 947912728 / `0x38800018` | 947912720 / `0x38800010` | ✔ aynı |
| Sağ ön | `BODYWORK_WINDOW_RIGHT_FRONT_PERCENT` | 1267728400 / `0x4B900010` | 947912728 / `0x38800018` | ✔ aynı |
| Sol arka | `BODYWORK_WINDOW_LEFT_REAR_PERCENT`   | **947912736 / `0x38800020`** | 947912736 / `0x38800020` | ✔ aynı |
| Sağ arka | `BODYWORK_WINDOW_RIGHT_REAR_PERCENT` | 1267728408 / `0x4B900018` | 947912744 / `0x38800028` | ✔ aynı |
| Panorama tavan | `BODYWORK_MOON_ROOF_OPEN_PERCENT` | 1101004808 / `0x41A00008` | 947912760 / `0x38800038` | ✔ aynı |
| Güneşlik panel | `BODYWORK_SUNSHADE_PANEL_PERCENT` | 1101004816 / `0x41A00010` | 947912768 / `0x38800040` | (Pano okumuyor) |

> **Firmware tuhaflığı:** CAN-FD'de sağ ön / sağ arka yeni `0x4B90xxxx` bloğuna,
> sol ön bile `0x38800018`'e taşınırken **sol arka yüzde** CAN-FD dalına sahip
> değil; her iki protokolde de eski `0x38800020`'de kalıyor
> (`(!isCanFD && isToyota) ? 872415264 : 947912736`). Araç CAN-FD ise sol arka
> yüzdenin yayınlanmaması muhtemelen bundan.

**Sorunun özü:** Pano'nun kullandığı adlar ve çözülen kimlikler **doğru.** Sürücü
camı okunuyor ama yolcu camları `65535` (UNAVAILABLE) döndürüyor — yani bu araç
o camların **yüzdesini veri yoluna yayınlamıyor** (pek çok araçta yalnız sürücü
kapı modülü yüzde yayınlar). Dolayısıyla yolcu camları için yüzde yolu çıkmaz;
durum yolu (aşağıda) denenmeli.

### 1b. Açık/kapalı durumu — `getWindowState(alan)`

Yüzdeden **ayrı kimlikler.** Pano bunları `openings()` içinde yalnız log'a yazıyor
(satır 311–313), gösterimde kullanmıyor.

`BYDAutoBodyworkDevice` içindeki `WINDOW_OPEN_FULL=1`, `WINDOW_CLOSE=2`,
`WINDOW_OPEN_HALF=4` sabitleri **komut (yazma) kodlarıdır**, okuma ölçeği değil.
Araçtan canlı okumada sürücü camı durumu kapalıyken `0` döndü (yüzde de `0`), yani
**durum okuması da konum/0–100 gibi davranıyor, 1/2/4 enum'u değil.** (Cam
session'ının araçtaki ölçümü.)

Durum kimlikleri:

| Cam | `BYDAutoFeatureIds` alanı | CAN-FD (ond. / hex) | Standart CAN |
|-----|---------------------------|---------------------|--------------|
| Sol ön | `BODYWORK_LEFT_FRONT_WINDOW`  | 947912760 / `0x38800038` | 947912712 / `0x38800008` |
| Sağ ön | `BODYWORK_RIGHT_FRONT_WINDOW` | 1267728396 / `0x4B90000C` | 947912714 / `0x3880000A` |
| Sol arka | `BODYWORK_LEFT_REAR_WINDOW`  | 947912762 / `0x3880003A` | 947912716 / `0x3880000C` |
| Sağ arka | `BODYWORK_RIGHT_REAR_WINDOW` | 1267728398 / `0x4B90000E` | 947912718 / `0x3880000E` |
| Panorama tavan | `BODYWORK_MOON_ROOF` | 1101004828 / `0x41A0001C` | 947912752 / `0x38800030` |
| Güneşlik | `BODYWORK_SUNSHADE_PANEL` | 1101004835 / `0x41A00023` | 1222639664 / `0x48E00030` |

İlgili: `BODYWORK_LEFT_FRONT_WINDOW_PERMIT` (cam izni), `BODYWORK_CLOSE_WINDOW_FOR_RAIN`
(yağmurda otomatik kapatma, 1=açık 2=kapalı), `BODYWORK_WINDOW_ANTIPINCH_CONFIG`.

### Araçtaki gerçek durum (cam session'ının ölçümü) ve sonuç

Araçta (`isCanFD=true`) canlı ölçüm:

| Cam | yüzde | durum |
|-----|------:|------:|
| Sol ön (sürücü) | `0` (geçerli) | `0` (geçerli) |
| Sağ ön | `65535` | `65535` |
| Sağ arka | `65535` | `65535` |
| Sol arka | `255` | — |

Kullanıcı yolcu camlarını fiziksel olarak açıp kapatırken `BODYWORK_` /
`INSTRUMENT_` / `DOOR_LOCK_` / `BODY_` önekli **1487 sinyalin hiçbiri değişmedi**
(aynı anda kapı olayları yakalandı, yani tarama çalışıyordu).

**Sonuç: bu araçta (Atto 2, CAN-FD) yolcu camı konumu üst API katmanına hiç
ulaşmıyor.** CAN→sinyal çözümü yerel `libbydautoservice.so` / `libbydauto.so`
içinde yapılıyor; framework (`BYDAutoDeviceManager`) yalnızca bu servise AIDL
istemcisi. Yolcu camları için sinyal orada çözülüp yayınlanmadığından ne
`getInt` taraması ne de olay-dinleyici (`registerListener` + `onWindowStateChanged`
/ `onWindowOpenPercentChanged`, aynı kimlikleri kullanır) işe yarar. Uygulamadan
erişilemeyen alt yollar (VHAL `android.hardware.automotive.vehicle@2.0`, FissionCluster'ın
kendi CAN hattı, ham CAN soketi) kök/özel izin ister, Pano için uygun değil.

Değer anlamları: `65535` = sinyal yok/çözülmemiş (UNAVAILABLE), `255` = sinyal
tanımlı ama ham değer geçersiz (sol arkada eski `0x38800020` kimliği tanınıyor
ama veri gelmiyor), `0..100` = geçerli açıklık yüzdesi.

**Pano için öneri:** Yolcu camları için ek/doğru kimlik aramak boşuna — yok.
`65535` ve `255`'i (ve `0..100` dışını) **"bilinmiyor"** say; camı yalnız geçerli
`0..100` okununca göster. Hiçbir camı "bilinmiyor" diye **"açık" varsayma** →
yanlış "tüm camlar açık" bildirimi böyle kalkar. Sürücü camı gerçek değer verir.

---

## 2. Kapı / bagaj / kaput — Bodywork, tür 1001

`getDoorState(alan)`: 1=sol ön, 2=sağ ön, 3=sol arka, 4=sağ arka, 5=kaput,
6=bagaj, 7=yakıt/şarj kapağı (ham kimlik `1336934422`). Dönüş: **1 = açık, 0 = kapalı**
(Pano'da doğrulandı), 255/65535 = tanımsız.

| Parça | Alan | CAN-FD | Standart CAN |
|-------|------|-------:|-------------:|
| Sol ön kapı | `BODYWORK_LEFT_HAND_FRONT_DOOR`  | 692060168 | 315621384 |
| Sağ ön kapı | `BODYWORK_RIGHT_HAND_FRONT_DOOR` | 692060170 | 315621385 |
| Sol arka kapı | `BODYWORK_LEFT_HAND_REAR_DOOR`  | 692060172 | 315621386 |
| Sağ arka kapı | `BODYWORK_RIGHT_HAND_REAR_DOOR` | 692060174 | 315621387 |
| Bagaj | `BODYWORK_LUGGAGE_DOOR` | 692060186 | 315621388 |
| Kaput | `BODYWORK_HOOD` | 692060188 | 315621389 |

> `isToyota` dalı da var (580911xxx); yukarıda yer kaplamasın diye atlandı,
> gerekirse `BYDAutoFeatureIds.java` satır ~12293'e bakılır.

---

## 3. Diğer gruplar (özet — ileride genişletilecek)

Pano bu grupları zaten `vehicle/BydBus.kt` + `BydSignals`'ta kullanıyor; kimlikler
yine protokole göre çözülür, yansımayla okunur.

- **Hız — Speed (1013):** `SPEED_AUTO_SPEED` (CAN-FD `-1807745016`, CAN `-1176502256`),
  `SPEED_FROM_GATEWAY` (`134217760`), `SPEED_AUTO_SPEED_FLAG`. Hız `getDouble` ile
  okunuyor (int okunursa "takılı değil" döner). Üst sınır ~282 km/s; ötesi işaret.
- **Vites — `gearbox.BYDAutoGearboxDevice` (1011):** `GEARBOX_AUTO_MODE_P=1, R=2, N=3,
  D=4, M=5, S=6`. Fren pedalı: `GEARBOX_BREAK_PADAL_PRESS=1 / NOT_PRESS=2`.
- **Klima — `ac.BYDAutoAcDevice` (1000):** komut sonuçları `AC_COMMAND_SUCCESS=0`,
  `AC_COMMAND_BUSY=-2147482647`, `AC_COMMAND_FAILED=-2147482648`,
  `AC_COMMAND_INVALID_VALUE=-2147482645`, `AC_ALREADY_IN_MAX/MIN_TEMP=5/4`.
  Ayrıntılı alanlar `ac/BYDAutoAcDevice.java`'da.
- **Enerji/istatistik — Statistic (1014), Energy (1006):** `STATISTIC_ELEC_DRIVING_RANGE`
  (menzil), `STATISTIC_ESTIMATE_SOC_V1` (SOC), `STATISTIC_DD_MILEAGE1/2`,
  `STATISTIC_AVERAGE_BATTERY_TEMP`, `STATISTIC_BATTERY_AVAILABLE_POWER`. Çoğu `getDouble`.
- **Şarj — Charging (1009):** alan adları bu belgeye henüz çıkarılmadı. Pano bunları
  çalışma anında listeleyip ada göre seçiyor (`vehicle/BydCharging.kt`); araçta şarjdayken
  logcat'teki `şarj alanları (...)` satırı gerçek listeyi verir, buraya eklenmeli.
- **Lastik — Tyre (parça: `BYDAutoTyreDevice`):** TPMS basınç/sıcaklık ve uyarılar.
- **Gövde ek:** `BODYWORK_BATTERY_VOLTAGE_LEVEL` (12V akü: 1=düşük, 2=normal),
  `BODYWORK_AUTO_VIN` (VIN), `BODYWORK_ALARM_STATE`.

Bir grubun tam alan listesi için derlenmiş kaynakta
`android/hardware/bydauto/<grup>/BYDAuto<Grup>Device.java` ve tüm kimlikler için
`BYDAutoFeatureIds.java` (iç sınıflar `BYDAutoFeatureIds$<Grup>`) incelenir.

---

## 4. Konfor: koltuk ve direksiyon — Setting, tür 1023

`BYDAutoSettingDevice` (firmware 13.1.33). Kimlikler üst sınıfta (`BYDAutoFeatureIds.SET_*`);
Pano `vehicle/BydComfort.kt` ile okur (önce tür 1023, sonra genel tür).

| Sinyal | Okuma alanı | Yazma alanı | Değerler |
|--------|-------------|-------------|----------|
| Sürücü koltuğu ısıtma | `SET_DRIVER_SEAT_HEATING_STATE` | `..._SET` | 1 kapalı, 2 düşük, 3 yüksek, 4 üç kademelide en yüksek (sürücüde 5/6 tek kademe aç/kapa komutları) |
| Yolcu koltuğu ısıtma | `SET_PASSENGER_SEAT_HEATING_STATE` | `..._SET` | 1..4 |
| Koltuk havalandırma | `SET_DRIVER/PASSENGER_SEAT_VENTILATING_STATE` | `..._SET` | 1 kapalı, 2 düşük, 3 yüksek |
| Var mı | `SET_HAS_DRIVER_SEAT_HEATING`, `SET_HAS_PASSENGER_SEAT_HEATING`, `SET_HAS_*_SEAT_VENTILATING`, `SET_HAS_SEAT_HEATING_AND_VENTILATING` | — | BYD `hasFeature`: 1/2/3 = var, 0 = yok, 65535 = bilinmiyor |
| Tek kademeli ısıtma | `SET_DRIVER_SEAT_HEATING_STATE1` = 994050114, yolcu 994050116 | aynı `_SET` (1 kapalı / 3 açık) | 1 kapalı, 2 açık (Pano okumuyor) |
| Direksiyon ısıtma | ham `1116733454` (`SET_STEERING_WHEEL_HEAT_STATE`) | `SET_STEERING_WHEEL_HEAT_STATE_SET` | 0 geçersiz, 1 kapalı, 2 açık |

> Not: `VehicleFeatures.has()` "var" için yalnız 1'i sayıyor; BYD'nin kendi `hasFeature`'ı 1/2/3'ü
> sayıyor. Klima kartındaki koltuk ısıtma görünürlüğü bu yüzden eksik kalabilir.

**Araçta doğrulanmadı (Kasım 2026):** Atto 2'nin bu sinyalleri yayınlayıp yayınlamadığı, kademe
ölçeği ve yazmanın `setInt` ile (imza izni isteyen `BYDAutoSettingDevice` olmadan) kabul edilip
edilmediği. Pano ham değerleri `BydComfort` etiketiyle, değiştikçe loglar.

### İlgili SystemUI bulguları (BYD hızlı ayarlar menüsü, `com.byd.bydqsmodeslib`)

- Parlaklık (`view/BydBrightnessView`): Android 10'da `Settings.System.screen_brightness` yazıyor;
  aralık `PowerManager.getMinimum/MaximumScreenBrightnessSetting`. Model 15/92'de gece modunda
  (`CarSettings.Global sys_day_or_night` = false) aralık %28–%60'a daralıyor.
- Ekranı kapat (`item/dilink/ScreenOffItem` → `BydScreenOffManager.startScreenOff`):
  `PowerManager.TurnBacklightOff` (`DEVICE_POWER` gerekir) + `android.intent.action.SCREEN_OFF_CLICK`
  yayını. `TurnBacklightOn` izin istemiyor. `CarSettings time_screen_saver_mode` ≠ 0 ise yerine
  `com.byd.screensaver/.AudioScreenActivity` (saat ekranı) açılıyor.
