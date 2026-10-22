# ConnectQuick — Pendientes y plan para la próxima sesión

Última actualización: 18-oct-2026. Rama de trabajo: `claude/build-all-installer` (sin PR abierto, `main` intacta).
Contexto general: `Docs/STATUS.md` (estado del producto) y `Docs/GUIA_INSTALACION_Y_PRUEBAS.md` (cómo instalar y probar).

## Dónde estamos

| Hecho | Estado real |
|---|---|
| Vínculo (pairing) PC ↔ móvil | Verificado con dos dispositivos reales en LAN (11-sep-2026) |
| `build-all.ps1` / `build-all.bat` (instalador + portable + APK) | **Escrito, NUNCA ejecutado en Windows** |
| Backend incluido en el instalador (modo LAN) | Solo comprobado en Linux que el Backend arranca con esa estructura de carpetas |
| Soporte Tailscale Funnel (`-Funnel`) | **Sin probar** |
| Versión portable (`ConnectQuick-Portable.zip`) | **Sin probar** |
| Control remoto (ratón, teclado, pantalla, mandos) | No existe (fases 4-9) |

Regla para la próxima sesión: **no construir encima de lo que no se ha probado**. Primero el bloque A.

---

## A. Validar lo ya escrito (prioridad máxima)

### A1. Ejecutar `build-all.bat` en tu PC y corregir lo que falle
- **Objetivo:** que `dist\` contenga `ConnectQuick-Setup.exe`, `ConnectQuick-Portable.zip` y `ConnectQuick.apk`.
- **Plan:**
  1. Seguir la sección 3 de `GUIA_INSTALACION_Y_PRUEBAS.md`.
  2. Si falla, pegar el mensaje completo (la ventana se queda abierta con `pause`).
  3. Corregir `build-all.ps1`, volver a ejecutar, repetir hasta generar los tres archivos.
- **Riesgos conocidos, sin verificar** (mirar primero aquí si falla):
  - `sdkmanager.bat` con argumentos que llevan `;` (`platforms;android-34`): si falla, entrecomillarlos.
  - Versiones fijadas: línea de comandos del SDK de Android `11076708` y Node `22.22.0`; si alguna URL da 404, actualizar.
  - IDs de `winget` (`Microsoft.DotNet.SDK.8`, `EclipseAdoptium.Temurin.17.JDK`, `JRSoftware.InnoSetup`, `Tailscale.Tailscale`).
  - Plantilla de Inno Setup (`ArchitecturesAllowed=x64compatible` pide Inno 6.3+, idioma `Spanish.isl`).
  - Rutas con espacios en el nombre de usuario de Windows.
  - Falta del *Developer Mode*/rutas largas al compilar Android si la carpeta está muy anidada (usar `C:\ConnectQuick`).
- **Hecho cuando:** los tres archivos existen y el instalador se ejecuta sin errores.
- **Alternativa que no depende de tu PC:** un workflow de GitHub Actions en `windows-latest` que ejecute el script
  (ver C1). Detecta fallos de compilación, aunque no los de instalación en tu máquina.

### A2. Prueba de vínculo real en LAN (modo A)
- **Plan:** sección 3.5 de la guía (QR → "¿Autorizar?" → "Vinculado con…").
- **Hecho cuando:** el móvil muestra `Vinculado con "<PC>" ✓` y el PC lista el dispositivo.
- **Ojo:** probar con el servidor instalado por el instalador (no con `npm run dev`), para validar el empaquetado.

### A3. Prueba de Funnel (modo B)
- **Plan:** sección 4 de la guía; comprobar con el móvil en **datos móviles**.
- **Incógnitas a confirmar:** que el PC pueda conectarse a su propia URL pública (el Host usa `wss://tu-pc.ts.net`);
  que Funnel acepte WebSocket sin problemas; que `tailscale funnel --bg 8443` reenvíe a `127.0.0.1:8443`.
- **Hecho cuando:** vínculo completo con el móvil fuera de tu Wi-Fi.

### A4. Probar la versión portable
- **Plan:** extraer el ZIP en otra carpeta, `Iniciar-ConnectQuick.cmd`, repetir A2.
- **Hecho cuando:** vínculo funciona sin haber ejecutado el instalador.

### A5. Decidir qué hacer con la rama
- Abrir PR hacia `main` (cuando A1-A4 pasen) o seguir en la rama. Nada se ha mezclado con `main` todavía.

---

## B. Funcionalidad nueva (en este orden)

### B1. Un celular con varios PC  — *pequeño, hacerlo primero*
- **Problema:** `authorized_devices` usa `device_id` como clave única. Al vincular el mismo celular con un segundo PC,
  `ON CONFLICT(device_id) DO UPDATE` no cambia `host_id`, y `isAuthorized(device, host)` lo rechaza. Además la app
  Android no guarda lista de PC.
- **Plan:**
  1. **Backend** (`db.ts`, `registry/deviceRepository.ts`): clave primaria `(device_id, host_id)`; `find`/`isAuthorized`/
     `revoke`/`touchLastConnected` por par; migración de la base existente (crear tabla nueva, copiar, renombrar).
  2. **Pruebas** (`Backend/test`): mismo celular vinculado a dos PC; revocar uno no afecta al otro; `session.connect` a ambos.
  3. **Android:** guardar lista de PC vinculados (nombre, `hostId`, relay) en almacenamiento cifrado; pantalla de lista
     para elegir PC; botón "Vincular otro PC".
  4. **Protocolo** (`Protocol/protocol-spec.md`): documentar que un Controller puede tener N Hosts.
- **Hecho cuando:** un celular vinculado a dos PC puede pedir `session.connect` a cualquiera.
- **Decisión pendiente:** ¿permitir que dos PC distintos usen servidores (relays) diferentes? Por ahora, uno solo.

### B2. Fase 4 mínima: teclado virtual con perfiles
- **Objetivo:** jugar un juego sencillo con WASD o flechas desde el celular.
- **Plan:**
  1. **Protocolo:** mensajes `input.key` (`code`, `down`), `input.state` (estado completo periódico), `input.mouse`;
     con número de secuencia. Esquemas JSON + vectores de prueba.
  2. **Host (Windows):** inyección con `SendInput` usando **scancodes** (no caracteres); contador de referencias por
     tecla (varias fuentes para la misma acción); **liberar todo** si no llega señal en 1-2 s o al desconectar.
  3. **Android:** pantalla de control horizontal con perfiles integrados (WASD, flechas, cruceta, joystick virtual);
     multitáctil; vibración.
  4. **Primera ruta:** pasar por el Backend (simple, más latencia). Medir.
- **Riesgos:** juegos ejecutados como administrador ignoran la entrada (ejecutar el Host también como admin);
  anticheat puede marcar la entrada simulada.
- **Hecho cuando:** en un juego sencillo, mantener un botón mueve al personaje y soltarlo (o cortar la conexión) lo detiene.

### B3. Editor de perfiles, compartir y cambio automático
- Perfil = archivo JSON (botones, tecla, posición, tamaño, acciones). Editor táctil; exportar/importar y por QR.
- El Host informa la ventana/proceso activo; el móvil cambia de perfil según una regla por programa.
- Capas/páginas, macros, mantener/alternar, sensibilidad y zona muerta.

### B4. Mandos de PlayStation/Xbox
- **Fase 1:** el mando físico se conecta al celular (Bluetooth/USB); Android entrega botones/ejes; se traducen a teclas
  con el perfil. No requiere drivers.
- **Fase 2:** mando virtual en el PC (ViGEmBus o vJoy; **comprobar si siguen mantenidos**), requiere instalar un driver
  y permisos de administrador.
- **Riesgo:** anticheat con drivers virtuales.

### B5. Conexión directa y pantalla remota (fase 5)
- WebRTC entre móvil y PC (el Backend solo señaliza), captura con Windows Graphics Capture, codificación por hardware,
  `MediaCodec` en Android. Pasar la entrada (B2) a un canal de datos directo para bajar latencia.
- Es el trabajo más grande: planificarlo aparte antes de empezar.

### B6. Varios amigos en un PC (cooperativo)
- Cada celular autorizado recibe un puesto de jugador (P1…P4) con su mando virtual; permisos limitados por dispositivo
  ("solo mando": sin ratón, archivos ni apagado). Un solo controlador activo de ratón; los demás, solo mando.
- Depende de B2-B4. El Backend ya admite varios celulares por PC (`activeSessions.ts`).

### B7. Utilidades para estudio
- Control de presentaciones, multimedia/volumen, atajos para OBS/Discord, bloquear/apagar el PC (fases 6-9).

---

## C. Calidad, rendimiento y distribución

### C1. CI en GitHub Actions (Windows)
- Workflow en `windows-latest`: `build-all.ps1`, pruebas del Backend/.NET/Android, y publicar los tres archivos como
  artefactos. Valida el script sin depender de tu PC. **Es lo que más ayuda a mantener A1.**

### C2. Rendimiento (medir antes y después)
- Backend: sentencias SQL preparadas una vez; `PRAGMA synchronous = NORMAL`.
- Android: reconexión inmediata al cambiar de red (`NetworkCallback`); liberar cámara/ML Kit al leer el QR.
- Host: `PublishReadyToRun`; menos asignaciones en `ReceiveLoopAsync`; confirmar ~0 % CPU en reposo.

### C3. Tamaño y release
- APK release: R8 (`isMinifyEnabled`, `isShrinkResources`), ML Kit vía Google Play Services, firma propia.
- Instalador: opción *framework-dependent*; valorar Node SEA para el Backend.

### C4. Pruebas que faltan
- Reconexión y backoff en los tres clientes (`STATUS.md` lo marca pendiente).
- Prueba de humo del script de compilación (C1).

### C5. Operación
- Backend como **servicio de Windows**; TLS directo (`CERT_PATH`/`KEY_PATH`) o VPS si se comparte con otras personas.
- Datos portables reales (`CONNECTQUICK_DATA`) — requiere cambiar el cifrado de la identidad para que no dependa del
  usuario de Windows (DPAPI).
- Firma del instalador y auto-actualización desde GitHub Releases.
- Revisar `Docs/SECURITY.md` antes de dejar Funnel abierto de forma permanente.

---

## D. Preguntas abiertas para ti

1. ¿Cuál es el caso de uso principal a corto plazo: jugar solo desde lejos (B2-B4) o jugar con amigos (B6)?
2. ¿Los juegos que te interesan tienen anticheat? Condiciona la estrategia de mandos virtuales (B4).
3. ¿Para uso personal basta Funnel, o piensas compartir la app con amigos fuera de tu red (entonces VPS/TLS, C5)?
4. ¿Prefieres abrir PR a `main` ya, o mantener todo en la rama hasta probarlo en tu PC?

## E. Orden sugerido para la próxima sesión

1. A1 → A2 (y A3/A4 si hay tiempo): con tu PC delante y los errores en la mano.
2. B1 (un celular, varios PC) con pruebas.
3. C1 (CI) para no depender de pruebas manuales del script.
4. Empezar B2 con el diseño del protocolo antes de escribir código.
