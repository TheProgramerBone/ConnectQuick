# Guía de pruebas — del código automático al PC y el móvil reales

Esta guía tiene dos partes: **(A)** las pruebas automáticas que se pueden ejecutar en cualquier máquina y
**(B)** la prueba manual con un PC Windows y un móvil Android reales. La parte B es la que **todavía no se ha
hecho para esta versión**: todo lo que toca Windows de verdad (inyección de teclado/ratón, portapapeles, captura de
pantalla, teclas multimedia, energía, la interfaz WinForms) y toda la app Android (interfaz, cámara, vídeo) se ha
compilado y probado por lógica, pero **nunca se ha ejecutado en un dispositivo**. Léelo como una lista de verificación para
descubrir qué falla la primera vez, no como una demostración de que funciona. Estado exacto en `Docs/STATUS.md`.

---

## A. Pruebas automáticas

Ejecútalas en este orden; si una falla, las siguientes no te dirán nada fiable.

```sh
cd Backend      && npm install && npm test            # 95 pruebas (servidor real, cripto real) + tabla de cobertura
cd Backend      && npm run loadtest                    # opcional: carga del relé (latencia, CPU, memoria)
cd WindowsHost  && dotnet test src/ConnectQuick.WindowsHost.Tests     # 156 pruebas (en Linux/macOS también)
cd Android      && ./gradlew -p controller-core test   # 89 pruebas JVM (lanza el backend real)
cd Tests        && npm install && npm test             # 40 escenarios entre procesos reales (ver Tests/README.md)
```

Qué demuestra cada una, y qué **no**:

| Suite | Demuestra | No demuestra |
|---|---|---|
| Backend | autenticación, pairing, relé E2E, límites, ICE/TURN, endurecimiento | nada de los clientes |
| WindowsHost.Tests | sesión cifrada, validación, permisos, entrada, portapapeles, multimedia, archivos (nombres hostiles), energía, streaming (ICE/DTLS/RTP en bucle local) | efecto real en un escritorio Windows |
| controller-core | handshake y verificación de firmas, gestos del touchpad, clientes de funciones, reconexión, textos de estado | la app Android |
| Tests/ | PC (.NET) ↔ relé ↔ móvil (Kotlin) como procesos reales: pairing, todas las funciones, revocación, caída del PC, reinicio del backend, cambio de red, partición de red silenciosa | Win32, GDI/VP8, libwebrtc, interfaces |

Para compilar la interfaz Windows hace falta Windows (`dotnet build WindowsHost/ConnectQuick.WindowsHost.sln`); en Linux/macOS esa
parte no compila (falta el SDK de escritorio) y el resto sí.

---

## B. Prueba manual: PC Windows + móvil Android

### Requisitos

- PC con Windows 10/11, **.NET 8 SDK** y **Node.js ≥ 22.5**.
- Móvil Android 8.0+ (el APK es `arm64-v8a`). Compílalo con `cd Android && ./gradlew assembleDebug` (JDK 17 + Android SDK 34) e instálalo con `adb install`
  o por archivo. **La primera compilación real puede dar errores que el entorno de desarrollo no pudo detectar**: arréglalos y repórtalos.
- PC y móvil en la **misma Wi-Fi** para la prueba básica. La compilación *debug* acepta `ws://`; una compilación *release* exige `wss://`.

### 1. IP del PC y backend

```powershell
ipconfig                     # apunta tu IPv4, p. ej. 192.168.1.23  (la llamaremos <IP-PC>)
cd Backend; npm install; npm run dev        # ws://0.0.0.0:8443 (texto plano: solo desarrollo)
```

Abre el puerto si Windows no te pregunta (PowerShell como administrador):

```powershell
New-NetFirewallRule -DisplayName "ConnectQuick Backend (8443)" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow -Profile Private,Domain
```

### 2. Arranca el Host

```powershell
cd WindowsHost
$env:CONNECTQUICK_RELAY = "ws://<IP-PC>:8443"      # nunca localhost: el móvil no podría llegar
dotnet run --project src/ConnectQuick.WindowsHost
```

Debe aparecer el icono de ConnectQuick en la bandeja (junto al reloj) y, al hacer clic, la ventana "ConnectQuick" con **Estado**. Comprueba:

- [ ] El estado pasa a **«Listo · esperando a tu teléfono»** (forma de círculo relleno) en unos segundos. Si dice «Sin conexión con el servidor», revisa backend/firewall.
- [ ] Cerrar la ventana la oculta pero el icono sigue; abrir una segunda copia avisa de que ya se está ejecutando.
- [ ] Con *Windows → Accesibilidad → Contraste alto* la ventana sigue legible (colores del sistema).

### 3. Vincula el móvil

1. PC: **Vincular un teléfono** → aparece el QR con cuenta atrás (60 s).
2. Móvil: **Vincular un PC** → **Escanear código QR** → permiso de cámara → apunta al QR.
3. El móvil muestra un **código de 6 cifras**; el PC muestra la ventana «quiere controlar este PC» con **otro código**.
   - [ ] **Los dos códigos son idénticos.** Si no lo son, rechaza: es exactamente el ataque que protege esta comprobación.
4. En el PC elige qué conceder (la energía **no** viene marcada; Enter no autoriza). **Para probar Energía, marca «Bloquear, suspender y apagar»** → **Autorizar**. Sin ese permiso el botón Energía del móvil dirá «Sin permiso: se concede en el PC» (comportamiento correcto).
- [ ] El móvil vuelve a Inicio y dice **Conectado a «tu PC»** (círculo verde). En el PC, **Dispositivos** lista el teléfono como «Conectado ahora».

### 4. Lista de comprobación por función

Marca lo que funciona y anota el síntoma de lo que no. "PC" = Windows; "Móvil" = Android.

| Función | Qué hacer | Esperado |
|---|---|---|
| Touchpad | Móvil → Touchpad: desliza; toca; toca con dos dedos; dos dedos arriba/abajo; toca y arrastra sobre una ventana | El puntero se mueve; clic izquierdo; clic derecho; desplazamiento; selección/arrastre |
| Botones de clic | Mantén «Clic izquierdo» y arrastra con otro dedo | Arrastre; al soltar se suelta el botón |
| Teclado | Escribe `hola ñandú ✓` en el campo; Esc, Tab, flechas, Supr, ⌫, ↵; activa Ctrl y pulsa una letra | El texto aparece en la aplicación enfocada del PC; atajos funcionan |
| **Liberación** | Mantén Shift activado y **cierra la app / apaga el Wi-Fi del móvil** | En ≤ 1 minuto (inmediato si cierras la sesión) Shift deja de estar pulsada en el PC — comprueba escribiendo en el PC |
| Pantalla | Móvil → Pantalla (misma Wi-Fi) | Imagen del escritorio; toca para mover el puntero, toca para clic, mantén pulsado para clic derecho; cambia Calidad |
| Pantalla protegida | En el PC lanza algo que pida UAC o bloquea (Win+L) | El móvil dice «pantalla protegida» (no se ve negro como si nada); al volver, se reanuda |
| Portapapeles | Copia texto en el PC → móvil «Traer lo copiado en el PC»; copia en el móvil → «Enviar al PC» y pega en el PC | Llega en ambos sentidos; texto vacío/no-texto lo explica |
| Multimedia | Reproduce música/vídeo; ⏯ ⏭ ⏮ − + Silenciar | El PC reacciona; el móvil dice «Enviado», **nunca** «reproduciendo» |
| Archivos →PC | Móvil → Archivos → Enviar un archivo | Aparece en `Descargas\ConnectQuick` del PC con el contenido idéntico; repetir el mismo archivo se detecta |
| Archivos ←PC | Copia un archivo a `~\ConnectQuick\Outbox` → «Ver archivos que comparte el PC» → pulsa | Llega a *Descargas/ConnectQuick* del móvil |
| Nombres hostiles | (opcional) En Outbox crea un archivo con nombre largo o con caracteres raros | El nombre llega saneado; nunca fuera de la carpeta |
| Energía (bloquear) | (con el permiso de energía concedido) Móvil → Energía → Bloquear | El PC se bloquea (con la política por defecto «solo bloquear») |
| Energía (resto) | Pulsa Apagar con la política por defecto | El móvil explica que el PC solo permite bloquear |
| Energía (confirmación) | En el PC, Permisos y ajustes → Energía → «Bloquear, suspender, reiniciar y apagar» (confirma el aviso); móvil → **Suspender** | Aparece un diálogo en el móvil que nombra la acción; **Cancelar** no hace nada; solo tras confirmar el PC actúa. Prueba con **Reiniciar** y cancela durante el margen de gracia |

### 5. Controles del PC (la persona frente al PC manda)

- [ ] Apaga «Ratón» en Permisos y ajustes: el touchpad deja de funcionar **de inmediato** y la app lo explica (sin volver a vincular).
- [ ] «Desconectar el teléfono» en Estado corta la sesión al instante; el móvil se reconecta solo (puedes pausar con «Aceptar conexiones»).
- [ ] «Preguntarme en cada conexión»: al reconectar el móvil aparece el aviso con cuenta atrás; **Denegar** (o dejarlo caducar) rechaza.
- [ ] Dispositivos → **Revocar acceso**: la sesión se corta y el móvil muestra «Acceso revocado» y ofrece vincular de nuevo.

### 6. Resiliencia manual

- [ ] Cierra ConnectQuick en el PC desde la bandeja (Salir): el móvil pasa a «no está disponible» y se conecta solo al reabrirlo.
- [ ] Apaga y enciende el Wi-Fi del móvil durante una sesión: reconecta sin intervención (la pantalla/archivos en curso terminan, el control vuelve).
- [ ] Reinicia el backend (`Ctrl+C`, `npm run dev`): ambos extremos se reconectan; no hace falta volver a vincular.

### 7. Pantalla desde otra red (necesita TURN)

Sin un servidor TURN, desde datos móviles es normal que el vídeo no conecte: la app lo dirá («no hay servidor de relevo»). Para probarlo: monta coturn y arranca el backend con
`TURN_URLS` / `TURN_SECRET` (`Docs/ARCHITECTURE.md`, *Running TURN*), usa `wss://` y repite el punto «Pantalla» con el móvil en datos móviles.
**Esta ruta no se ha probado nunca contra un TURN real.**

### Solución de problemas

| Síntoma | Causa probable |
|---|---|
| El móvil se queda en «Conectando con el servidor…» | Firewall (puerto 8443), IP equivocada, o PC y móvil en redes distintas. Una compilación *release* además rechaza `ws://`. |
| «Sin permiso: se concede en el PC» en un botón | El permiso no se concedió al vincular o está apagado en *Permisos y ajustes*. Vincula de nuevo o actívalo. |
| El teclado no escribe en una ventana concreta | Esa ventana se ejecuta como administrador (Windows lo impide a propósito). |
| Pantalla negra / «pantalla protegida» | UAC o pantalla de bloqueo: Windows no permite capturarla. |
| «No se pudo mostrar la pantalla… sin servidor de relevo» | Redes distintas sin TURN. Misma Wi-Fi, o monta TURN. |
| El vídeo no aparece en la misma Wi-Fi | Firewall de Windows bloqueando UDP al proceso `dotnet`/ConnectQuick; revisa también aislamiento de clientes en el router. |

### Qué adjuntar al reportar un fallo

El registro (*Registro → Copiar registro*, o `%APPDATA%\ConnectQuick\logs\connectquick.log`; no contiene claves ni contenido), la salida del backend, el modelo de móvil y versión de Android,
y la captura de pantalla de la app. Los fallos de *aspecto* en la primera ejecución son esperables: ninguna de las dos interfaces se ha visto nunca en pantalla.
