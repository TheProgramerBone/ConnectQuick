# Crear el .exe y el APK, y qué falta por confirmar

Todo se hace desde un **PC con Windows**. Ya tienes el código si hiciste `git checkout claude/intelligent-newton-upxevj && git pull`.
Hay tres scripts en `Scripts/` (PowerShell; abre PowerShell en la carpeta del repositorio).

> Si Windows bloquea los scripts: `Set-ExecutionPolicy -Scope Process Bypass` y vuelve a ejecutar.

## 1. Instalar una sola vez

| Para | Instala | Comprobar |
|---|---|---|
| El `.exe` | **.NET 8 SDK** — https://dotnet.microsoft.com/download/dotnet/8.0 | `dotnet --list-sdks` muestra `8.x` |
| El servidor | **Node.js 22.5 o superior** — https://nodejs.org | `node --version` |
| El APK | **Android Studio** (trae JDK 17 y el SDK). Ábrelo una vez y deja que instale el SDK 34 (SDK Manager → Android 14 / API 34) | existe `%LOCALAPPDATA%\Android\Sdk` |
| Instalar el APK por cable (opcional) | Depuración USB en el móvil + `adb` (viene con Android Studio) | `adb devices` lista tu móvil |

## 2. Generar el `.exe` del PC

```powershell
.\Scripts\build-windows.ps1          # añade -Test para ejecutar antes los tests
```

Resultado: carpeta `dist\ConnectQuick-Windows\` con `ConnectQuick.WindowsHost.exe` y todo lo que necesita (no requiere tener .NET para ejecutarlo).
Es una **carpeta**, no un único archivo: se copia entera. La primera vez, SmartScreen avisa porque la app no está firmada ("Más información → Ejecutar de todas formas").

## 3. Generar el APK del móvil

```powershell
.\Scripts\build-android.ps1          # añade -Test para ejecutar antes los tests del núcleo
```

Resultado: `dist\ConnectQuick-debug.apk`. Instálalo con `adb install -r dist\ConnectQuick-debug.apk` o pasa el archivo al móvil y ábrelo
(permite "instalar apps desconocidas" para el explorador de archivos). La primera compilación descarga dependencias y tarda varios minutos.

**Si Gradle da error, es esperable en esta primera compilación**: nunca se pudo ejecutar en el entorno de desarrollo. Copia la primera línea de `What went wrong` / `error:` y compártela.

## 4. Probar (versión corta)

1. Terminal 1: `.\Scripts\start-backend.ps1` → muestra la IP de tu PC. Abre el puerto si no te lo pregunta Windows:
   `New-NetFirewallRule -DisplayName "ConnectQuick 8443" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow -Profile Private,Domain` (PowerShell como administrador).
2. Ejecuta `dist\ConnectQuick-Windows\Iniciar-ConnectQuick.cmd` y escribe `ws://<IP-PC>:8443`.
3. Instala el APK, abre la app → **Vincular un PC** → escanea el QR → compara el código de 6 cifras → **Autorizar** en el PC.
4. Sigue la lista completa de `Docs/GUIA_DE_PRUEBAS.md` (sección B, punto 4).

El APK *debug* acepta `ws://` (red local). Un APK *release* exige `wss://` (HTTPS en el servidor), así que para pruebas usa el debug.

## 5. Qué falta por confirmar

Nada de esto se ha ejecutado nunca; es la lista de lo que **tu primera prueba va a confirmar o desmentir**. Marca cada punto:

**Compilación**
- [ ] `build-windows.ps1` termina sin errores (primera vez que se compila la interfaz WinForms de verdad).
- [ ] `build-android.ps1` termina sin errores (primera vez que corre Gradle con el módulo `controller-core`, WebRTC y la pantalla de escaneo).

**Windows (adaptadores reales)**
- [ ] Ratón y teclado se inyectan de verdad (incluye Unicode como `ñandú ✓`).
- [ ] Soltar teclas al cortar la sesión (Shift no queda pulsada).
- [ ] Portapapeles en ambos sentidos.
- [ ] Teclas multimedia y volumen.
- [ ] Bloquear el PC; y, con la política completa, suspender/reiniciar con confirmación y cancelación.
- [ ] Recibir archivos en `Descargas\ConnectQuick` y compartir desde `~\ConnectQuick\Outbox`.
- [ ] El identificador del PC se guarda cifrado (DPAPI) y sobrevive a reiniciar la app.

**Interfaz**
- [ ] Windows: bandeja, centro de control, diálogos, QR legible, Contraste alto, aspecto en tu escala de pantalla (100 %/125 %/150 %).
- [ ] Android: aspecto, texto grande, giro de pantalla, lector de pantalla (TalkBack) en Inicio y Pairing.

**Vídeo**
- [ ] La pantalla del PC se ve en el móvil en la misma Wi-Fi (captura GDI + VP8 + libwebrtc).
- [ ] Toque directo sobre la imagen mueve el puntero y hace clic en el sitio correcto.
- [ ] Pantalla bloqueada / UAC: el móvil lo indica.
- [ ] Fluidez y calidad (Baja/Media/Alta) y uso de CPU del PC.
- [ ] Desde datos móviles: sin TURN debe decir "sin servidor de relevo"; con coturn configurado debe conectar (**nunca probado**).

**Seguridad y robustez**
- [ ] Los dos códigos de 6 cifras coinciden al vincular.
- [ ] Revocar desde el PC corta la sesión y el móvil muestra "Acceso revocado".
- [ ] Reconexión automática al apagar/encender Wi-Fi, cerrar y abrir el PC, y reiniciar el servidor.
- [ ] Funciones apagadas en el PC (Permisos y ajustes) dejan de funcionar al instante.

**Fuera de alcance por ahora (no es un fallo si no está)**: varios monitores, audio del PC, instalador y firma de código, varios PC en el móvil, bloqueo de la app con biometría, servicio en segundo plano en Android.

## 6. Qué mandarme si algo falla

- Texto del error de compilación (primer error, no todo el log).
- Registro del PC (*Registro → Copiar registro*) y la salida de la ventana del servidor.
- Modelo de móvil y versión de Android; qué paso de la lista falló y qué viste.
