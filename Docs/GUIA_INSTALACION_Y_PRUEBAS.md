# ConnectQuick — Guía de instalación, pruebas, versión portable e ideas de mejora

Documento único para dejar ConnectQuick funcionando en tu PC con Windows y en tu móvil Android, probarlo,
y saber hacia dónde mejorarlo. Cubre lo que existe **hoy** (vincular el móvil con el PC); la pantalla remota,
el ratón/teclado, etc. son fases futuras (`Docs/STATUS.md`).

> **Aviso honesto:** los scripts de esta guía (`build-all.ps1`, el instalador, la versión portable y el soporte
> de Funnel) se escribieron sin poder ejecutarlos en Windows. Es normal que algo falle en la primera corrida.
> Si pasa, copia el mensaje de error completo y se corrige. Lo que sí está verificado con pruebas reales es el
> Backend, el vínculo (pairing) y la compilación de las apps por separado (`Docs/STATUS.md`).

---

## 1. Cómo encajan las piezas

```
 [Móvil Android]  ──┐                      ┌──  [PC con Windows]
   app ConnectQuick │                      │     app ConnectQuick (icono en la bandeja)
                    ▼                      ▼
                  [ Backend ]  ← punto de encuentro: vincula y presenta, no ve tu escritorio
```

- **App del PC (Host):** programa de bandeja del sistema, con ventanas propias (QR y "¿Autorizar?").
- **App del móvil (Controller):** app Android nativa; escanea el QR.
- **Backend:** servidor sin pantalla. Los dos se conectan **hacia él**; por eso no se abren puertos en el router.

Si Backend y móvil no pueden alcanzarse, no hay vínculo. De ahí los dos modos:

| Modo | Dónde corre el Backend | Móvil | Cuándo usarlo |
|---|---|---|---|
| **A. Red local** | En tu PC | Debe estar en el **mismo Wi-Fi** | Probar en casa; no necesita cuentas |
| **B. Internet (Tailscale Funnel)** | En tu PC, publicado por Tailscale | Cualquier red, **sin instalar nada** | Usarlo fuera de casa |

Recomendación: **empieza por el modo A**. Cuando el vínculo funcione en casa, pasa al B.

---

## 2. Requisitos

- Windows 10 u 11, 64 bits, con conexión a Internet (la primera compilación descarga varios cientos de MB:
  .NET, JDK, Android SDK, Gradle, Node).
- Unos **6 GB libres** y entre **15 y 40 minutos** la primera vez.
- Permiso para instalar programas (el script usa `winget`, que viene con Windows 10/11 actuales; si no lo tienes,
  instala "Instalador de aplicación" desde la Microsoft Store).
- Un móvil Android 8.0 o superior con cámara.
- Para el modo B: una cuenta gratuita de Tailscale (<https://tailscale.com>).

No necesitas instalar a mano .NET, Java, Inno Setup, Node ni el SDK de Android: el script lo hace.

---

## 3. Paso a paso — Modo A (red local)

### 3.1 Obtener el código

La rama con todo esto es `claude/build-all-installer`.

**Opción fácil (sin Git):** descarga
<https://github.com/TheProgramerBone/ConnectQuick/archive/refs/heads/claude/build-all-installer.zip>,
extrae el ZIP en una carpeta corta como `C:\ConnectQuick` (evita rutas muy largas).

**Con Git:**
```powershell
git clone --branch claude/build-all-installer https://github.com/TheProgramerBone/ConnectQuick C:\ConnectQuick
```

### 3.2 Generar los instaladores

1. Abre la carpeta `C:\ConnectQuick`.
2. Haz **doble clic en `build-all.bat`**. (Si prefieres: `powershell -ExecutionPolicy Bypass -File .\build-all.ps1`.)
3. Espera. Verás pasos como `==> Checking .NET 8 SDK`, `==> Publishing Windows Host`, `==> Building Android APK`.
   - Si `winget` pide confirmar un instalador (ventana de Windows), acepta.
   - Si dice que hace falta abrir una terminal nueva tras instalar algo, ciérrala y vuelve a ejecutar
     `build-all.bat`: continúa donde corresponde.
4. Al terminar, en `C:\ConnectQuick\dist\` tendrás:

| Archivo | Para qué |
|---|---|
| `ConnectQuick-Setup.exe` | Instalador del PC (con el servidor opcional incluido) |
| `ConnectQuick-Portable.zip` | Lo mismo, sin instalar (ver sección 7) |
| `ConnectQuick.apk` | App para el móvil |

El script detecta la IP de tu PC en la red (por ejemplo `192.168.1.23`) y la guarda como dirección del servidor.
Si tu IP cambia más adelante (reinicio del router), vuelve a generar el instalador o edita `relay.txt`
(sección 8).

### 3.3 Instalar en el PC

1. Ejecuta `dist\ConnectQuick-Setup.exe`.
   - Windows SmartScreen puede decir "Windows protegió su PC" porque el instalador no está firmado: pulsa
     **Más información → Ejecutar de todas formas**.
   - Instalación por usuario, **sin pedir administrador**. Si la ejecutas como administrador, además crea la
     regla de firewall del puerto 8443.
2. Deja marcadas las casillas:
   - *Instalar y ejecutar el servidor ConnectQuick en este PC* (el Backend).
   - *Iniciar ConnectQuick con Windows* (opcional).
3. Termina el asistente y deja marcado *Abrir ConnectQuick ahora*.
4. **Firewall:** si no instalaste como administrador, la primera vez que arranque el servidor Windows puede
   mostrar un aviso sobre `connectquick-server`. Acepta para **redes privadas**. Si no sale ningún aviso y el móvil
   no conecta, ejecuta **una vez** en PowerShell como administrador:
   ```powershell
   New-NetFirewallRule -DisplayName "ConnectQuick Backend (8443)" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow -Profile Private,Domain
   ```
5. Busca el icono de ConnectQuick junto al reloj (puede estar tras la flecha ▲).

### 3.4 Instalar en el móvil

1. Pasa `ConnectQuick.apk` al móvil (cable USB, Drive, Telegram, correo…).
2. Ábrelo. Android pedirá permitir "instalar apps desconocidas" para la app desde la que lo abres: acéptalo.
3. Si Google Play Protect avisa de una app no reconocida, elige **Instalar de todos modos** (es normal: es tu
   propia compilación de prueba).
4. Con USB y depuración activada también sirve: `adb install dist\ConnectQuick.apk`.
5. Conecta el móvil **al mismo Wi-Fi** que el PC.

### 3.5 Probar el vínculo (la prueba que importa)

| # | Dónde | Qué hacer | Resultado esperado |
|---|---|---|---|
| 1 | PC | Clic derecho en el icono de la bandeja → **Vincular nuevo dispositivo** | Aparece una ventana con un QR y cuenta atrás de 60 s |
| 2 | Móvil | Abre ConnectQuick → **Escanear código QR** → permite la cámara → apunta al QR | El móvil pasa a "Conectando…" |
| 3 | PC | Aparece **¿Autorizar?** con el nombre del móvil y un código de 6 dígitos → **Sí, autorizar** | Aviso de que el dispositivo fue autorizado |
| 4 | Móvil | Mira el texto de estado | `Vinculado con "<nombre del PC>" ✓` |
| 5 | PC | Clic derecho en la bandeja → **Dispositivos vinculados** | El móvil aparece en la lista |

Si ves los pasos 4 y 5, **todo funciona de extremo a extremo**. Que después no haya pantalla remota ni control
del ratón es lo esperado: esas fases no existen todavía.

### 3.6 Comprobaciones útiles (si algo no cuadra)

```powershell
# ¿Está el servidor corriendo? Debe aparecer connectquick-server.exe
Get-Process connectquick-server

# ¿Escucha en el puerto 8443?
Test-NetConnection localhost -Port 8443

# ¿Cuál es la dirección que usa el instalador?
Get-Content "$env:ProgramFiles\ConnectQuick\relay.txt"   # o %LOCALAPPDATA%\Programs\ConnectQuick\relay.txt
```

(La ruta depende de si instalaste con o sin administrador.)

---

## 4. Paso a paso — Modo B (desde cualquier red, con Tailscale Funnel)

Haz primero el modo A. Después, para usarlo fuera de casa **sin instalar nada en el móvil**:

1. Crea una cuenta gratuita en <https://tailscale.com> e **instala Tailscale en el PC**. Inicia sesión.
2. En <https://login.tailscale.com/admin/dns> activa **HTTPS Certificates**.
3. En el PC, en la carpeta del proyecto, ejecuta:
   ```powershell
   .\build-all.ps1 -Funnel
   ```
   El script lee el nombre público de tu PC (`tu-pc.tu-red.ts.net`) y lo guarda como servidor
   (`wss://tu-pc.tu-red.ts.net`).
4. **Desinstala** la versión anterior si quieres y ejecuta el nuevo `ConnectQuick-Setup.exe`. Deja marcadas la casilla
   del servidor y *Activar acceso desde Internet (Tailscale Funnel)*.
5. Se abre una ventana que ejecuta `tailscale funnel --bg 8443`. Si muestra un **enlace**, ábrelo, habilita Funnel y
   vuelve a ejecutar *Activar acceso desde Internet (Funnel)* desde el menú Inicio.
6. Comprueba: `tailscale funnel status` debe listar `https://tu-pc.tu-red.ts.net → http://127.0.0.1:8443`.
7. **Prueba real:** apaga el Wi-Fi del móvil (usa datos móviles) y repite el vínculo de la sección 3.5.
   El APK es el mismo; la dirección viaja dentro del QR.

Notas del modo B:
- El Backend queda **expuesto a Internet**. Está protegido por la firma de cada dispositivo y por límites de
  solicitudes, pero si no lo usas durante un tiempo, apágalo: `tailscale funnel --https=443 off`.
- No se abre el puerto 8443 en tu red local (Funnel entrega el tráfico por `127.0.0.1`).
- Más detalle y solución de problemas: `Docs/ACCESO_REMOTO.md`.

---

## 5. Problemas frecuentes

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `winget` no se reconoce | Falta "Instalador de aplicación" | Instálalo desde la Microsoft Store y repite |
| El script dice que no encuentra .NET/Java/Inno tras instalarlo | La terminal no vio el nuevo PATH | Cierra todo y vuelve a ejecutar `build-all.bat` |
| Falla la descarga del SDK de Android o de Gradle | Red o proxy corporativo | Repite; si persiste, copia el error |
| El APK no se instala | Falta permitir "apps desconocidas", o ya hay una versión firmada distinta | Desinstala la versión anterior e instala de nuevo |
| El móvil se queda en "Conectando…" (modo A) | Firewall de Windows (lo más común), redes distintas o IP cambiada | Regla de firewall (3.3), mismo Wi-Fi, comprueba `relay.txt` |
| "Conectando…" en modo B | Funnel apagado o servidor parado | `tailscale funnel status`; `Get-Process connectquick-server` |
| El QR caduca | Dura 60 s | Pulsa **Vincular nuevo dispositivo** otra vez |
| SmartScreen bloquea el instalador | No está firmado | **Más información → Ejecutar de todas formas** |
| Cambió la IP del PC | El router asignó otra | Regenera con `build-all.ps1` o edita `relay.txt` (sección 8) |

Pruebas automáticas (opcional, aíslan si el fallo es de red o de código; requieren Node ≥ 22.5, .NET 8 y JDK 17):
```powershell
cd Backend;      npm install; npm test
cd WindowsHost;  dotnet test src/ConnectQuick.WindowsHost.Tests/ConnectQuick.WindowsHost.Tests.csproj
cd Android;      .\gradlew.bat testDebugUnitTest
```

---

## 6. Desinstalar y dónde se guardan los datos

- Desinstala desde *Configuración → Aplicaciones* (cierra el servidor y, si eras administrador, quita la regla de
  firewall). Si usaste Funnel: `tailscale funnel --https=443 off`.
- Datos del usuario (no se borran al desinstalar): `%APPDATA%\ConnectQuick\`
  - `host_identity.bin` — identidad cifrada del PC (DPAPI, ligada a tu usuario de Windows).
  - `authorized_devices.json` — móviles que autorizaste.
  - `backend.sqlite` — base de datos del servidor local.
- Para empezar de cero: borra esa carpeta (tendrás que volver a vincular el móvil).

---

## 7. Versión portable (sin instalador)

`build-all.ps1` también genera **`dist\ConnectQuick-Portable.zip`**.

1. Extrae el ZIP en cualquier carpeta (por ejemplo una memoria USB o `D:\Apps\ConnectQuick`).
2. Haz doble clic en **`Iniciar-ConnectQuick.cmd`**: arranca el servidor (minimizado, si lo incluiste) y la app de bandeja.
3. Para cerrarlo: clic derecho en el icono → Salir, y termina `connectquick-server.exe` desde el Administrador de tareas.

Estructura: `app\` (el programa del PC, con `relay.txt`), `servidor\` (Node portátil + Backend, y
`activar-funnel.cmd` si usaste `-Funnel`) e `Iniciar-ConnectQuick.cmd`.

Diferencias con el instalador:
- No crea accesos, no se inicia con Windows y no toca el firewall (en modo A tendrás que aceptar el aviso de
  Windows o crear la regla manualmente).
- No requiere permisos de administrador ni deja nada en el registro.
- **Los datos siguen en `%APPDATA%\ConnectQuick`**, no dentro de la carpeta. Mover la carpeta a otro PC no
  traslada la identidad (está ligada a tu usuario de Windows): allí hay que volver a vincular.
- Para quitar la versión portable: cierra los procesos y borra la carpeta (y `%APPDATA%\ConnectQuick` si quieres
  borrar los datos).

Para generar solo el portable sin el servidor: `build-all.bat -SkipBackend`.

---

## 8. Cambiar la dirección del servidor sin recompilar

El PC lee la dirección en este orden: variable de entorno `CONNECTQUICK_RELAY` → archivo `relay.txt` junto al
`.exe` → `ws://localhost:8443`.

- Instalado: edita `relay.txt` en la carpeta de instalación (primera línea, p. ej. `ws://192.168.1.50:8443`) y
  reinicia la app.
- Portable: `app\relay.txt`.
- El móvil **no necesita cambios**: toma la dirección del QR que muestra el PC.

---

## 9. Lluvia de ideas de mejora

Basadas en el código actual (unas 3000 líneas entre Backend, PC y Android). Son **ideas, no medidas**: antes de
optimizar, mide (tiempo de arranque, memoria, tamaño de instalador, latencia de vinculación).

### 9.1 Rendimiento

**Backend (Node + SQLite)**
- Las consultas hacen `db.prepare(...)` en cada llamada (`registry/*.ts`). Preparar cada sentencia **una vez** en
  el constructor y reutilizarla evita recompilar SQL en cada mensaje.
- Añadir `PRAGMA synchronous = NORMAL` junto a `journal_mode = WAL` (`db.ts`): menos escrituras a disco, seguro
  con WAL para este caso de uso.
- La limpieza periódica (vinculaciones caducadas y buckets del limitador de tasa) ya corre en un único
  temporizador con `unref()` (`server.ts`); no hace falta tocarla.
- Con pocos dispositivos esto no es un cuello de botella; la prioridad real aparecerá en la **fase 5** (vídeo):
  el Backend solo debe señalizar (negociar), nunca retransmitir frames, o el ancho de banda de tu PC se vuelve el límite.

**App del PC (.NET)**
- `ReceiveLoopAsync` crea un `MemoryStream` por mensaje y llama a `ToArray()` (copia). Reutilizar un buffer y
  deserializar desde `ReadOnlySpan<byte>`/`GetBuffer()` reduce asignaciones. En volúmenes de señalización es
  mínimo; importará si algún día pasan eventos de entrada por aquí.
- Arranque más rápido con **ReadyToRun** (`PublishReadyToRun=true`) a cambio de un ejecutable algo mayor.
- La app es solo bandeja: comprobar que en reposo use ~0 % de CPU (el único trabajo periódico debe ser el
  heartbeat de 15 s) y poca memoria.

**App Android**
- **Reconexión inmediata al cambiar de red:** hoy espera el retroceso exponencial (hasta 30 s). Registrar un
  `ConnectivityManager.NetworkCallback` y reconectar en cuanto cambie Wi-Fi ↔ datos mejora mucho la sensación de
  velocidad (justo el caso de la guía: salir de casa).
- **Cámara/escáner:** liberar CameraX y ML Kit en cuanto se lee el QR (cerrar el analizador) para ahorrar batería.
- Para fase 5: decodificación por hardware (`MediaCodec`) y superficie directa, nada de conversiones por CPU.

### 9.2 Tamaño y optimización de código

- **APK más pequeño:** hoy es compilación *debug*, solo `arm64-v8a`. En *release*: activar R8
  (`isMinifyEnabled = true` + `isShrinkResources = true`), y cambiar `com.google.mlkit:barcode-scanning` (modelo
  incluido en la app) por la variante de **Google Play Services** (`play-services-mlkit-barcode-scanning`), que
  descarga el modelo aparte y reduce varios MB.
- **Instalador del PC más pequeño:** la app va *self-contained* (incluye .NET, ~100+ MB). Alternativa: versión
  *framework-dependent* (unos pocos MB) si el PC ya tiene .NET 8 Desktop Runtime. Ojo: el recorte (*trimming*) no
  está soportado de forma segura con WinForms.
- **Backend empaquetado:** hoy se copia Node completo (~75 MB). Con Node ≥ 22 se puede explorar *Single
  Executable Applications* para un solo `.exe`, o aceptar el tamaño actual por simplicidad.
- **Código compartido:** las tres plataformas repiten criptografía y cliente WebSocket (probados contra los
  mismos vectores, lo cual es una buena base). Mantener `Protocol/test-vectors` como contrato y valorar generar
  los tipos de mensajes desde los JSON Schema (`Protocol/schemas`) para evitar divergencias.
- **Pruebas:** hay muchas del Backend y E2E de vinculación; faltan pruebas dedicadas de **reconexión/backoff**
  (lo reconoce `STATUS.md`) y un test de humo del script de compilación (CI en Windows con GitHub Actions
  ejecutando `build-all.ps1`, que además publicaría los artefactos de cada versión).

### 9.3 Producto y seguridad (cerca de lo ya hecho)

- **Firmar** el instalador y el APK de release (evita SmartScreen/Play Protect y permite actualizaciones).
- **Auto-actualización:** comprobar una versión en GitHub Releases y avisar en la bandeja.
- **TLS directo en el Backend** (`CERT_PATH`/`KEY_PATH`) para no depender de Funnel si algún día usas un VPS.
- **Iniciar el Backend como servicio de Windows** (en vez de acceso directo de inicio) para que sobreviva al
  cierre de sesión.
- **Datos portables reales:** una variable `CONNECTQUICK_DATA` para guardar identidad y bases dentro de la
  carpeta portable (con la salvedad de que DPAPI ata la identidad al usuario de Windows; habría que cambiar el
  cifrado, p. ej. por contraseña, para que sea transportable entre PCs).
- **Apagar Funnel automáticamente** cuando no hay móvil vinculado en uso, o añadir una lista de IPs/horarios.
- **Notificación en el móvil** cuando el PC se desconecta (el Backend ya conoce la presencia).

### 9.4 Orden sugerido

1. Probar de verdad esta guía y corregir lo que falle (ganancia inmediata).
2. Reconexión por cambio de red en Android + sentencias SQL preparadas (cambios pequeños y seguros).
3. CI en Windows que ejecute `build-all.ps1` y publique los artefactos.
4. APK release con R8 y firma; instalador firmado.
5. Fase 4 (ratón y teclado) antes de la fase 5 (vídeo): es la pieza más pequeña que hace que el sistema controle algo.

---

## 10. Resumen de comandos

| Quiero… | Comando |
|---|---|
| Generar todo (modo A) | `build-all.bat` |
| Generar con Internet vía Funnel | `build-all.bat -Funnel` |
| Generar sin servidor incluido | `build-all.bat -SkipBackend -Relay wss://mi-servidor` |
| Fijar otra dirección de servidor | `build-all.bat -Relay ws://192.168.1.50:8443` |
| Ver si Funnel está activo | `tailscale funnel status` |
| Apagar Funnel | `tailscale funnel --https=443 off` |
| Ver si el servidor corre | `Get-Process connectquick-server` |
