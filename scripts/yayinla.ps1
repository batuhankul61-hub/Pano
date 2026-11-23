# Pano'nun yeni sürümünü yayımlar: release APK'yı derler, Pano-APK klasörüne kopyalar ve GitHub'da
# "v<versionCode>" etiketli bir sürüm (Release) oluşturur. Araçtaki Pano, Ayarlar > Güncelleme'den bunu görür.
#
# Kullanım (proje kökünde, main dalında, değişiklikler commit'lenmiş olarak):
#   .\scripts\yayinla.ps1 "Bu sürümde neler var"
param([string]$Notlar = "")

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$gh = "C:\Program Files\GitHub CLI\gh.exe"

if (git status --porcelain --untracked-files=no) { throw "Commit'lenmemiş değişiklik var; önce commit'le (sürüm kodu commit sayısından gelir)." }
if (-not (Test-Path keystore.properties)) { throw "keystore.properties yok: release anahtarıyla imzalanamaz, güncelleme üstüne kurulmaz." }

$kod = [int](git rev-list --count HEAD)
$kisa = git rev-parse --short HEAD
Write-Host "Sürüm v$kod ($kisa) derleniyor…"
.\gradlew.bat :app:assembleRelease -q
if ($LASTEXITCODE -ne 0) { throw "Derleme başarısız" }

$apk = "app\build\outputs\apk\release\app-release.apk"
$ad = "Pano-0.2.$kod.apk"
$hedef = Join-Path (Split-Path (Get-Location) -Parent) "Pano-APK\$ad"
Copy-Item $apk $hedef
Copy-Item $apk (Join-Path $env:TEMP $ad) -Force

git push origin HEAD
if (-not $Notlar) { $Notlar = (git log -1 --format=%s) }
& $gh release create "v$kod" (Join-Path $env:TEMP $ad) --title "Pano 0.2.$kod" --notes $Notlar --target (git rev-parse HEAD)
Write-Host "Yayımlandı: v$kod → $hedef"
