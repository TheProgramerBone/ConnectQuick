<#
  Genera el APK de depuración de Android.
  Requisitos: JDK 17 y el SDK de Android 34 (lo más simple: instalar Android Studio una vez;
  trae ambos y crea el SDK en %LOCALAPPDATA%\Android\Sdk).

  Uso:
      .\Scripts\build-android.ps1            # genera dist\ConnectQuick-debug.apk
      .\Scripts\build-android.ps1 -Test      # ejecuta antes los tests del núcleo (JVM)
#>
param([switch]$Test)
$ErrorActionPreference = "Stop"
$root    = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "Android"

# Localiza el SDK si no está indicado.
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $default = Join-Path $env:LOCALAPPDATA "Android\Sdk"; if (Test-Path $default) { $sdk = $default } }
if (-not $sdk) { throw "No encuentro el SDK de Android. Instala Android Studio o define ANDROID_HOME." }
$props = Join-Path $android "local.properties"
if (-not (Test-Path $props)) { Set-Content $props ("sdk.dir=" + ($sdk -replace '\\','/')) -Encoding ASCII }

Push-Location $android
try {
    if ($Test) {
        .\gradlew.bat -p controller-core test --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Los tests del nucleo fallaron." }
    }
    .\gradlew.bat assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle fallo. Copia el primer error ('error:' o 'What went wrong') y compartelo." }
} finally { Pop-Location }

$apk = Join-Path $android "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "Gradle termino pero no esta el APK en $apk" }
New-Item -ItemType Directory -Force (Join-Path $root "dist") | Out-Null
Copy-Item $apk (Join-Path $root "dist\ConnectQuick-debug.apk") -Force
Write-Host ""
Write-Host "Listo: $(Join-Path $root 'dist\ConnectQuick-debug.apk')" -ForegroundColor Green
Write-Host "Instalalo con:  adb install -r dist\ConnectQuick-debug.apk   (o pasa el archivo al movil y abrelo)."
