# ConnectQuick — Pendientes y plan para la próxima sesión

Última actualización: 24-oct-2026. Rama de trabajo: **`main`** (se trabaja siempre directo en `main`).
Contexto: `Docs/STATUS.md` (estado técnico detallado), `CLAUDE.md` (memoria del proyecto),
`Docs/SERVIDOR_GRATIS_GOOGLE_CLOUD.md` (servidor en la nube).

## Dónde estamos

| Qué | Estado real |
|---|---|
| Vincular por QR, control (ratón, teclado), pantalla, portapapeles, multimedia, archivos, energía | **Funciona en uso real** (confirmado por el dueño, en casa y desde otra red) |
| Servidor propio gratis en Google Cloud (e2-micro, `wss://35-209-191-155.sslip.io`) | **Funcionando** desde el 24-oct-2026. PC y celular en redes distintas (datos móviles) confirmado |
| Temas oscuro/claro y estética «liquid glass» (PC y móvil) | Instalado; sin confirmación explícita de cada pantalla |
| Arreglo del QR (la ventana del PC pasa al código de 6 cifras al escanear) | Instalado; sin confirmación explícita |
| Mejoras del móvil (zoom, pantalla completa, perfiles Auto/Texto/Normal/Video/Juego/Ahorro, gestos de 3 dedos, pellizco, modo touchpad con puntero local, bloqueo de arrastre, atajos, F1–F12, barra de teclas, captura, vibración) | Instaladas; **sin confirmación una por una** |
| Relevo de vídeo embebido para Tailscale Funnel (`-Funnel`) | Escrito y con tests, **nunca probado en real** (ya no hace falta con el servidor de Google) |

---

## 0. Revisar ya (sin programar)

- [ ] **Gasto en Google Cloud**: Facturación → Informes, 1–2 días después de crear la máquina. Lo esperado: 0 o céntimos.
  Si aparece un cargo fijo (por ejemplo «IP externa», ~3–4 USD/mes), decidir: aceptarlo, liberar la IP estática
  (cambiaría la dirección y habría que volver a vincular) o mover el servidor.
- [ ] Confirmar una por una las mejoras del móvil de la tabla de arriba y anotar lo que se sienta torpe
  (gestos, zoom, modo touchpad, perfil Auto). Ajustar con datos reales.
- [ ] La alerta de presupuesto de 1 USD sigue activa.

---

## A. Conexión estilo AnyDesk (siguiente bloque recomendado)

Análisis completo hecho el 24-oct-2026 leyendo todo el código de conexión. Orden por valor/esfuerzo:

### A1. Detectar cambios de red en el PC + latido rápido durante una sesión — *pequeño* — ✅ escrito 24-oct, **sin compilar** (sin `dotnet` en la nube; lo valida el CI de C1 o tu PC)
- Hecho: `HostRuntime` escucha `NetworkChange.NetworkAddressChanged` (1 reconexión por ráfaga, 3 s); `BackendClient.SessionActive` usa ping cada 5 s y corte a 12 s mientras hay sesión.
- **Problema:** `NotifyNetworkChanged()` existe en `WindowsHost/.../Net/BackendClient.cs` pero **nadie lo llama**: si el PC
  (portátil) cambia de Wi-Fi tarda hasta 40 s en reconectar. En ambos clientes el latido es cada 15 s y el enlace se da
  por muerto a los 40 s, también con una sesión activa: un corte silencioso congela el control hasta 40 s.
- **Plan:** suscribirse a `NetworkChange.NetworkAddressChanged` en el host; con sesión activa, ping cada 5 s y corte a
  los 12 s (fuera de sesión se queda como está, por batería/tráfico).
- **Ojo:** C# no se puede compilar en el sandbox de la nube (no hay `dotnet`); el dueño compila primero.

### A2. Lista de relays, el más rápido primero — *medio* — ✅ escrito 25-oct (etapas 1–3 + script), **pendiente de verificar con el CI y en real**
- Hecho: backend `registry.sync` (el PC copia sus teléfonos a otro relay; probado, 106 tests); el PC se conecta a varios relays (`relay.txt` línea 1 = principal, líneas 2+ = extra, p. ej. `ws://{lan}:8443`); el QR y `ctl.caps` llevan `relays`; el móvil prueba todas a la vez (`BackendClient.pickUrl`) y usa la primera que responde; los teléfonos ya vinculados aprenden la lista dentro de la sesión. `Scripts/usar-servidor.ps1 -ConServidorLocal` lo activa. Escenario cruzado nuevo: `Tests/integration/relays.test.ts`.
- Límites conocidos: el APK de publicación solo permite `wss://`, así que la ruta LAN `ws://` solo funciona en el APK debug; si el relay local contesta pero el PC no está conectado a él, el móvil lo reintenta en bucle; el vídeo (ICE) sigue pidiendo su configuración al relay principal.
- Plan original (para referencia):
- **Problema:** un solo relay fijado al vincular (`PairedHost.relay`, `relay.txt`). En casa todo da la vuelta por Google
  Cloud; si cambia la dirección del servidor hay que volver a vincular; no hay respaldo.
- **Plan:** el QR lleva varias direcciones (p. ej. LAN + nube); el móvil las prueba a la vez y usa la primera que
  autentica; el PC envía la lista actualizada dentro de la sesión cifrada para que el móvil la guarde.
- **Nota:** para que la ruta LAN sirva con el servidor en la nube, el PC tendría que alojar también un relay local, o
  añadir una conexión directa LAN entre PC y móvil. Decidir el diseño antes de programar.

### A3. El vídeo se recupera solo — *pequeño/medio* — ✅ escrito 24-oct, **sin ejecutar** (Gradle bloqueado por 429 en la nube)
- Hecho (sin tocar el PC): si el vídeo ya había conectado y pasa 5 s en DISCONNECTED o cae en FAILED, el móvil vuelve a pedir `stream.start` (hasta 3 veces; el PC sustituye el stream). Si nunca conectó, sigue mostrando el error de siempre. Test nuevo en `StreamControllerTest`.
- **Problema:** `StreamController.onRtcState` cierra el vídeo si WebRTC falla; al pasar de Wi-Fi a datos solo vuelve
  cuando se rehace toda la sesión.
- **Plan:** reinicio de ICE al cambiar de red o tras unos segundos en DISCONNECTED; reintento automático tras ERROR.

### A4. Relevo de vídeo por el puerto 443 — *medio*
- Redes muy restrictivas (empresas, hoteles) solo dejan pasar 443: el control funciona, el relevo de vídeo (3478) no.
- **Plan:** coturn con TLS en 443 compartido con Caddy por SNI (segundo nombre `sslip.io`) o por ALPN.

### A5. Vídeo de emergencia por el relay — *grande*
- Si WebRTC no conecta de ninguna forma, el PC manda la imagen comprimida dentro de la sesión cifrada por WebSocket
  (menos fps). Los límites del backend (8 MB/s, 400 mensajes/s por sesión) lo permiten. Siempre habría imagen.

### A6. Ratón por canal directo WebRTC (DataChannel) — *grande*
- Movimientos sin reintentos y sin pasar por el relay cuando el vídeo está conectado; el relay queda de respaldo.
  Quita los tirones de TCP en datos móviles con pérdidas. Cambio de protocolo en las tres plataformas.

### A7. Reanudar la sesión tras un microcorte — *grande*
- Hoy la sesión muere con el socket (`activeSessions.ts`). Un token de reanudación evitaría rehacer el handshake y
  renegociar el vídeo. Cambio de protocolo en las tres plataformas.

---

## B. Funcionalidad nueva

### B1. Un celular con varios PC
- **Estado (25-oct):** ✅ backend (clave `(device_id, host_id)` con migración, `test/unit/migration.test.ts`) y ✅ Android escrito: `HostBook` (lista de PC + cuál se usa; lee el formato de un solo PC), vincular otro PC lo añade y lo activa, tarjeta «Tus otros PC» en la pantalla principal para cambiar, «Olvidar este PC» activa otro si queda. Sin probar en el móvil; tests JVM en `HostBookTest`.
- **Problema:** `authorized_devices` usa `device_id` como clave única en el backend; la app Android guarda un solo PC.
- **Plan:** clave `(device_id, host_id)` con migración; pruebas de vincular/revocar por par; lista de PC en Android con
  selector; documentarlo en el protocolo.

### B2. Pantalla: elegir monitor y audio del escritorio
- Varios monitores (captura en el host, selector en el móvil) y audio del PC en el móvil. Trabajo grande en C# (host).

### B3. Mandos y juegos
- Perfiles de teclado virtual por juego (WASD, flechas, joystick), editor y cambio automático por programa activo.
- Mando físico conectado al celular → teclas; después mando virtual en el PC (ViGEmBus/vJoy: comprobar si siguen
  mantenidos). Riesgo: anticheat.

### B4. Varios amigos en un PC (cooperativo)
- Cada celular con su puesto de jugador y permisos limitados ("solo mando"). Depende de B3.

### B5. Utilidades
- Control de presentaciones, atajos para OBS/Discord (como atajos fijos tipados, nunca comandos genéricos).

---

## C. Calidad, distribución y seguridad

### C1. CI en GitHub Actions (Windows) — ✅ `.github/workflows/ci.yml` escrito 24-oct; primera ejecución pendiente (esperar fallos de primera vez)
- `windows-latest`: compilar el host (incluida la app WinForms), pruebas .NET, backend y Kotlin; publicar instalador y
  APK como artefactos. Cubre lo que el sandbox de la nube no puede compilar (C#).

### C2. Accesibilidad y pantallas
- TalkBack/Narrador, tamaños de letra grandes, DPI altos; capturas para el sistema de diseño.

### C3. Release
- APK de publicación: R8, firma propia, solo TLS (ya se usa `wss://`). Instalador firmado y auto-actualización.

### C4. Seguridad
- Revisión externa / fuzzing del protocolo propio antes de compartir la app con más gente.
- Bloqueo opcional de la app en el móvil (huella/PIN).
- Mantener el servidor actualizado: `sudo bash /opt/connectquick/Deploy/gcp/update.sh` en la máquina tras cada cambio
  en `main` que toque `Backend/` o `Deploy/`.

### C5. Limpieza
- Las ramas `claude/*` antiguas siguen en GitHub (el sandbox no pudo borrarlas: 403). Borrarlas desde la web de GitHub.
- Si ya no se usa Tailscale: desinstalarlo del PC. El código de `-Funnel` y el TURN embebido pueden quedarse como
  alternativa documentada.

---

## D. Orden sugerido para la próxima sesión

1. Bloque 0 (gasto, confirmar mejoras del móvil).
2. A1 + A3 (reconexión rápida y vídeo que se recupera): pequeños y se notan mucho.
3. C1 (CI en Windows) para poder compilar el C# sin depender de tu PC.
4. A2 (lista de relays) tras decidir el diseño de la ruta LAN.
5. B1 (varios PC) si se necesita; luego A4–A7 según cómo vaya el uso real.
