# Guía de pruebas — WindowsHost + Android, en dos dispositivos reales

Esta guía es para probar **de verdad** el vínculo (pairing) entre el PC y el móvil: el backend
real, el WindowsHost real, y la app Android real, uno hablando con el otro. Es justo lo que
`Docs/STATUS.md` marca como pendiente ("WindowsHost y Android nunca se han ejecutado el uno contra
el otro" — hasta ahora cada uno se probó por separado contra un simulador del otro).

## Qué vas a poder probar (y qué no)

✅ Vincular el móvil con el PC por código QR: ver el QR, escanearlo, autorizar, y que el móvil
confirme "Vinculado con...".

⛔ Todo lo demás **no existe todavía**: no hay pantalla remota, ni ratón/teclado, ni portapapeles,
ni archivos, ni apagar/reiniciar el PC. Eso son fases futuras (`Docs/STATUS.md`, Fases 4-9). Si
después de vincular no pasa nada más, es lo esperado, no un fallo.

## Requisitos

- El PC con **.NET 8 SDK** y **Node.js ≥ 22.5** instalados (ya los tienes si seguiste las guías
  anteriores).
- Un móvil Android con el APK instalado (te lo he enviado por el chat; si prefieres compilarlo tú,
  ver `Android/README.md`).
- **El PC y el móvil en la misma red Wi-Fi/LAN.** El backend de pruebas habla `ws://` sin cifrar —
  no es accesible desde fuera de tu red, y no debe usarse así en producción (ver
  `Backend/README.md`, sección "Production notes").

## Paso 1 — Averigua la IP de tu PC en la red local

El móvil no puede usar `localhost` para llegar al backend — `localhost` en el móvil es el propio
móvil. Necesitas la IP de tu PC en la LAN:

```powershell
ipconfig
```

Busca el adaptador Wi-Fi/Ethernet que estés usando y anota su "Dirección IPv4" (algo como
`192.168.1.23`). La llamaremos `<IP-PC>` en el resto de esta guía.

## Paso 2 — Arranca el backend

```sh
cd Backend
npm install     # solo la primera vez
npm run dev
```

Verás un aviso `WARN ... serving plaintext ws://` (normal, es desarrollo local) y luego
`listening` con el puerto (por defecto `8443`). Déjalo corriendo en esta terminal.

**Primera vez**: es posible que Windows te pregunte si permites que `node.exe` se comunique en
redes — acepta al menos para "Redes privadas". Si no aparece el aviso (lo más común: Windows no
siempre lo muestra) necesitas abrir el puerto tú mismo, o el móvil se quedará conectando para
siempre aunque la IP sea correcta:

1. Abre PowerShell **como administrador** (clic derecho → "Ejecutar como administrador").
2. Ejecuta:
   ```powershell
   New-NetFirewallRule -DisplayName "ConnectQuick Backend (8443)" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow -Profile Private,Domain
   ```
3. No hace falta reiniciar nada; la regla queda activa de inmediato.

Esto solo hace falta la primera vez en cada PC — la regla queda guardada permanentemente.

## Paso 3 — Arranca el WindowsHost apuntando a tu IP (no a localhost)

En otra terminal:

```powershell
cd WindowsHost
$env:CONNECTQUICK_RELAY = "ws://<IP-PC>:8443"
dotnet run --project src/ConnectQuick.WindowsHost
```

(Sustituye `<IP-PC>` por la IP del Paso 1, por ejemplo `ws://192.168.1.23:8443`.)

No se abre ninguna ventana — busca el icono de ConnectQuick en la bandeja del sistema (junto al
reloj; puede estar oculto tras la flecha "▲" de iconos ocultos).

## Paso 4 — Genera el código QR

Clic derecho en el icono de la bandeja → **"Vincular nuevo dispositivo"**. Se abre una ventana con
el QR y una cuenta atrás de 60 segundos (pasado ese tiempo el código caduca y hay que generar uno
nuevo).

## Paso 5 — Escanea desde el móvil

Abre la app **ConnectQuick** en el Android → **"Escanear código QR"** → concede el permiso de
cámara la primera vez → apunta al QR de la pantalla del PC.

## Paso 6 — Autoriza en el PC

En cuanto el móvil escanea, en el PC aparece una ventana **"¿Autorizar?"** con el nombre del
dispositivo y un código de 6 dígitos (SAS). Pulsa **"Sí, autorizar"**.

## Paso 7 — Comprueba el resultado

- En el **móvil**: el texto de estado cambia a `Vinculado con "<nombre-del-PC>" ✓`.
- En el **PC**: el icono de la bandeja muestra un aviso ("... ha sido autorizado") y el
  dispositivo aparece en el menú **"Dispositivos vinculados"** (clic derecho en el icono).

Si ves eso en ambos lados, el pairing real de dos dispositivos ha funcionado de extremo a extremo.

## Solución de problemas

| Síntoma | Causa probable |
|---|---|
| El móvil se queda en "Conectando..." y no pasa de ahí | Casi siempre es el Firewall de Windows bloqueando el puerto 8443 en silencio (sin aviso). Aplica el comando `New-NetFirewallRule` del Paso 2. Si ya lo hiciste, confirma que el móvil y el PC están en la misma red (no datos móviles) y que `<IP-PC>` es la correcta (vuelve a correr `ipconfig`, la IP puede cambiar entre reinicios). |
| Error de "cleartext"/conexión rechazada nada más escanear | Estás usando un APK viejo (anterior a este mensaje) sin el permiso de tráfico `ws://` sin cifrar. Instala el APK que te acabo de enviar. |
| El QR caduca antes de escanear | Tienes 60 segundos desde que se genera. Vuelve a pulsar "Vincular nuevo dispositivo". |
| Pulsas "Sí, autorizar" y no pasa nada en el móvil | Revisa que el móvil siga conectado (si cambió de red Wi-Fi a datos móviles a mitad de proceso, perderá la conexión al backend LAN). |
| El WindowsHost no aparece en la bandeja | Revisa la terminal donde hiciste `dotnet run` por si hay un error (por ejemplo, el backend no está arrancado todavía). |

## Antes de probar en real: pruebas automáticas (opcional pero recomendado)

Si algo falla en las pruebas manuales, estos comandos aíslan si el problema es de red/entorno o de
código — ambos pasan hoy sin necesitar dos dispositivos:

```sh
cd Backend && npm test
cd WindowsHost && dotnet test src/ConnectQuick.WindowsHost.Tests/ConnectQuick.WindowsHost.Tests.csproj
cd Android && ./gradlew testDebugUnitTest
```

Si estos tres pasan pero la prueba manual falla, el problema casi seguro es de red local (IP
equivocada, Firewall, redes distintas), no del código.

## Qué hacer si todo funciona

✅ Ya verificado (11-sep-2026): el pairing real entre WindowsHost y Android, con dos dispositivos
físicos distintos en la misma LAN, funciona de extremo a extremo — QR generado por el PC, escaneado
por el móvil real, autorizado, y "Vinculado con..." confirmado en ambos lados. Ver `Docs/STATUS.md`
para el detalle actualizado.

Después de vincular, la app Android no tiene nada más que un botón de "Escanear otro QR" — **eso
es lo esperado, no un fallo**: no hay pantalla remota ni control de ratón/teclado todavía (Fases
4-9 de `Docs/STATUS.md`, sin empezar). El siguiente hito de desarrollo sería la Fase 4 (ratón +
teclado), la pieza más pequeña que haría que el sistema controle algo de verdad, antes de abordar
el streaming de escritorio (Fase 5), que es un esfuerzo mayor por requerir WebRTC.
