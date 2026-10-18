<#
  Genera la app de Windows lista para ejecutar (carpeta autocontenida con ConnectQuick.WindowsHost.exe).
  No hace falta tener .NET instalado para ejecutar el resultado, solo para generarlo (.NET 8 SDK).

  Uso (PowerShell, desde cualquier carpeta):
      .\Scripts\build-windows.ps1            # genera dist\ConnectQuick-Windows
      .\Scripts\build-windows.ps1 -Test      # ejecuta antes los tests del Host
#>
param([switch]$Test)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out  = Join-Path $root "dist\ConnectQuick-Windows"

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw "No se encontró 'dotnet'. Instala el .NET 8 SDK: https://dotnet.microsoft.com/download/dotnet/8.0"
}
if ((dotnet --list-sdks) -notmatch "^8\.") { throw "Hace falta el SDK de .NET 8 (dotnet --list-sdks no muestra ninguno 8.x)." }

if ($Test) {
    dotnet test (Join-Path $root "WindowsHost\src\ConnectQuick.WindowsHost.Tests") --nologo
    if ($LASTEXITCODE -ne 0) { throw "Los tests fallaron; no se genera la app." }
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
dotnet publish (Join-Path $root "WindowsHost\src\ConnectQuick.WindowsHost\ConnectQuick.WindowsHost.csproj") `
    -c Release -r win-x64 --self-contained true -o $out --nologo
if ($LASTEXITCODE -ne 0) { throw "dotnet publish falló. Copia el error y compártelo." }

# Lanzador que pregunta la dirección del servidor (la IP del PC donde corre el backend).
$launcher = @'
@echo off
setlocal
if "%CONNECTQUICK_RELAY%"=="" (
  set /p CONNECTQUICK_RELAY=Direccion del servidor, por ejemplo ws://192.168.1.23:8443 :
)
start "" "%~dp0ConnectQuick.WindowsHost.exe"
'@
Set-Content -Path (Join-Path $out "Iniciar-ConnectQuick.cmd") -Value $launcher -Encoding ASCII

Write-Host ""
Write-Host "Listo: $out" -ForegroundColor Green
Write-Host "Ejecuta Iniciar-ConnectQuick.cmd (o ConnectQuick.WindowsHost.exe con la variable CONNECTQUICK_RELAY definida)."
Write-Host "Windows SmartScreen avisara la primera vez: la app no esta firmada (Mas informacion > Ejecutar de todas formas)."
