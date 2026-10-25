# ConnectQuick Protocol — v1

Este documento es la fuente de verdad del protocolo de mensajes entre **Host (Windows)**,
**Controller (Android)** y el **Backend (relay/signaling)**. Las tres implementaciones
(`/Backend`, `/WindowsHost`, `/Android`) deben ajustarse a esta especificación y a los
[JSON Schemas](./schemas) que la acompañan. Los [vectores de prueba](./test-vectors) garantizan
que la criptografía produce resultados idénticos bit a bit en las tres plataformas.

## 1. Principios

- **Versionado explícito**: todo mensaje lleva `v` (versión de protocolo, entero). Un receptor que
  no entienda una versión mayor debe rechazar el mensaje, no intentar interpretarlo parcialmente.
- **Envelope único**: todo mensaje sobre WebSocket tiene la misma forma exterior; el campo `type`
  determina el esquema de `payload`.
- **Tamaño máximo**: 64 KiB por mensaje de control (JSON). Los mensajes mayores se rechazan sin
  procesarlos.
- **Validación estricta**: cualquier campo desconocido, tipo incorrecto o `type` no soportado se
  rechaza con `error` tipado; nunca se ejecuta "lo que se entienda" de un mensaje inválido.
- **Sin comandos genéricos**: no existe un mensaje tipo `ExecuteShellCommand`. Cada acción posible
  en el Host tiene su propio tipo de mensaje explícito y validado (ver §7, catálogo de futuras
  fases, ya reservado en el esquema para que no haga falta romper compatibilidad).
- **El backend nunca ve texto claro sensible**: los payloads de pairing llevan únicamente material
  público (claves públicas, nonces, firmas). La clave de sesión derivada (ECDH) nunca se transmite.

## 2. Envelope

```json
{
  "v": 1,
  "id": "b6f1c1b0-6c1a-4e9b-9a1a-6f6b6b8f9a11",
  "ts": 1735689600000,
  "type": "pairing.init",
  "payload": { }
}
```

| Campo     | Tipo    | Descripción                                                             |
|-----------|---------|--------------------------------------------------------------------------|
| `v`       | int     | Versión de protocolo. Esta spec define `v = 1`.                         |
| `id`      | string  | UUIDv4 único por mensaje (deduplicación / correlación de respuesta).    |
| `ts`      | int     | Unix epoch ms en el emisor. Se usa para detectar relojes muy desviados. |
| `type`    | string  | Tipo del mensaje, ver catálogo.                                        |
| `payload` | object  | Cuerpo específico del tipo, validado por su JSON Schema.                |

Esquema: [`schemas/envelope.schema.json`](./schemas/envelope.schema.json).

## 3. Identidad de dispositivo

Cada Host y cada Controller genera, en el primer arranque, un par de claves **Ed25519**
(identidad/firma, de largo plazo) y deriva su `deviceId` como
`base64url(SHA-256("connectquick:device:" || ed25519Pub))[0:22]`.

La clave privada nunca sale del dispositivo:
- Windows: cifrada en disco con DPAPI (`ProtectedData`, ámbito de usuario actual).
- Android: cifrada con una clave AES-256-GCM respaldada por Android Keystore
  (`StrongBox`/TEE si el hardware lo soporta).

El backend solo almacena **claves públicas**, nunca privadas.

## 4. Catálogo de mensajes del envelope (señalización y relay)

### Sesión de control (Host/Controller ↔ Backend)

| `type`                     | Emisor     | Descripción                                                       |
|-----------------------------|------------|--------------------------------------------------------------------|
| `hello.challenge`            | backend    | Primer frame tras abrir el WS: reto aleatorio de 32 bytes.        |
| `hello`                     | ambos      | Cliente prueba posesión de su clave privada firmando el reto.     |
| `hello.ack`                 | backend    | Confirma autenticación, entrega `capabilities` del backend.        |
| `heartbeat.ping`             | ambos      | Late/presencia. Cada 15s.                                          |
| `heartbeat.pong`             | ambos      | Respuesta, usada para medir RTT.                                   |
| `error`                     | backend    | Rechazo de un mensaje inválido (`code`, `message`).                |

**Autenticación de la conexión (challenge-response, no solo pairing)**: al abrir el WS, el backend
envía `hello.challenge` con un nonce aleatorio. El cliente responde `hello` firmando ese nonce con
su clave Ed25519 de identidad — así prueba posesión de la clave sin transmitirla nunca. El
`hostId`/`deviceId` es autocertificante: `deviceId = base64url(SHA-256("connectquick:device:" ||
identityPub))[0:22]`, así que un atacante no puede reclamar un id ya registrado sin la clave
privada correspondiente (el backend fija la clave pública la primera vez que ve un id y la exige
igual en cada reconexión — TOFU acotado por criptografía, no por confianza en el transporte). Esta
autenticación de transporte es independiente de la **autorización** de un Controller concreto sobre
un Host concreto, que solo se concede a través del flujo de pairing (tabla `authorized_devices`).

### Pairing (§6-7 del encargo)

| `type`                        | Emisor     | Descripción                                                             |
|--------------------------------|------------|---------------------------------------------------------------------------|
| `pairing.create`               | Host → BE  | Host pide un `pairingId` nuevo (botón "Vincular nuevo dispositivo").      |
| `pairing.created`              | BE → Host  | Backend devuelve `pairingId`, `nonce`, `expiresAt` (TTL 60s).             |
| `pairing.init`                 | Ctrl → BE  | Controller, tras escanear el QR, inicia el handshake con sus claves.     |
| `pairing.incoming`              | BE → Host  | Backend reenvía la petición al Host (para el diálogo "¿Autorizar?").     |
| `pairing.decision`             | Host → BE  | Host autoriza o rechaza, firmado con su clave de identidad.              |
| `pairing.result`                | BE → Ctrl  | Resultado final (éxito + datos del Host, o rechazo/expiración).          |
| `pairing.revoke`                | Host → BE  | Revoca un dispositivo ya autorizado.                                     |
| `pairing.revoked`               | BE → Ctrl  | Notifica al dispositivo revocado (si está conectado) y cierra su sesión. |
| `registry.sync`                 | Host → BE  | El Host declara el conjunto **completo** de teléfonos en los que confía (`devices`: `deviceId`, `devicePub`, `deviceName`, `permissions`). Permite que un segundo relay (el del propio PC en la LAN, o un servidor nuevo) atienda a teléfonos vinculados en otro sin repetir el QR. Autoritativo: los teléfonos que ese backend conocía para el Host y no aparecen se revocan. Cada `deviceId` debe derivar de su `devicePub`. Solo rol Host, siempre sobre su propio `hostId`; el Host sigue verificando cada `session.connect` por su cuenta (§9). |
| `registry.synced`               | BE → Host  | Acuse: `authorized` y `revoked` (cantidades). |

### Presencia, sesión E2E e ICE (ver §7 y §9)

| `type`               | Emisor     | Descripción                                                        |
|-----------------------|------------|------------------------------------------------------------------------|
| `presence.update`     | BE → Ctrl  | Cambia el estado online/offline de un Host vinculado.               |
| `session.connect`     | Ctrl → BE, BE → Host | Controller pide iniciar una sesión con un Host ya vinculado; el backend reenvía el mismo `type` al Host, añadiendo `deviceId`/`deviceName` del Controller (no presentes en el mensaje original Ctrl→BE) para que el Host sepa quién pide entrar. |
| `session.accept`      | Host → BE, BE → Ctrl | El Host acepta con su clave efímera, los permisos efectivos y una firma que cubre ambas claves (§7.2). |
| `session.reject`      | Host → BE, BE → Ctrl | El Host rechaza con un motivo (`not_paired`, `user_denied`, …). |
| `session.data`        | ambos, vía BE | Frame cifrado opaco `{sessionId, seq, ct}`; el backend solo lo enruta (§7.4). |
| `session.end`         | ambos, BE → ambos | Termina la sesión con un motivo; ambos extremos sueltan todo lo retenido. |
| `ice.request` / `ice.config` | cualquiera ↔ BE | Servidores STUN y credenciales TURN efímeras para WebRTC (§9). |
| `session.state`       | Host → BE, BE → Ctrl | El Host reporta cambios de estado de la sesión que el backend relaya a cada Controller enlazado a esa sesión (ver §6 máquina de estados). El backend también emite `session.state` a un Controller directamente cuando el Host solicitado está offline o cuando el Host se desconecta durante una sesión activa. |

### Firma de la decisión de pairing

`pairing.decision.hostSignature` firma (Ed25519, con la clave de identidad de largo plazo del
Host) los bytes UTF-8: `` `${pairingId}|${deviceId}|${approved ? 1 : 0}|${permissions.join(",")}` ``.

- Si `approved = true` y el Host omite `permissions`, se asume la lista completa por defecto —
  `remote_desktop,mouse,keyboard,clipboard,files,media,apps,power` en ese orden — y la firma debe
  cubrir esa misma lista implícita.
- Si `approved = false`, `permissions` no aplica: la lista firmada es siempre vacía (`""`)
  independientemente de lo que el mensaje incluya en ese campo — un rechazo no concede nada, así
  que el Host no necesita decidir qué permisos "hubieran" aplicado.

Un Host que firme una lista distinta a la que el backend reconstruye produce una firma que se
rechaza (`forbidden`), nunca una que se acepte con permisos distintos a los firmados.

## 5. QR de pairing

Contenido (JSON compacto, codificado en el QR como texto):

```json
{
  "v": 1,
  "pairingId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "relay": "wss://relay.example.com/ws",
  "hostId": "3n9F0m1z...",
  "hostName": "PC Gaming",
  "hostPub": "base64url(ed25519 pub, 32 bytes)",
  "hostEphPub": "base64url(x25519 pub, 32 bytes)",
  "nonce": "base64url(16 bytes aleatorios)",
  "exp": 1735689660,
  "relays": ["ws://192.168.1.50:8443", "wss://relay.example.com/ws"]
}
```

`relay` es donde ocurre este pairing. `relays` (opcional, máx. 4 en `ctl.caps`) lista, de mejor a peor, todas las
direcciones por las que se llega a ese PC; el teléfono las prueba a la vez en cada conexión y usa la primera
que responde (en casa, la LAN; fuera, la nube). El Host también la repite dentro de `ctl.caps` (cifrado)
para que teléfonos ya vinculados la aprendan sin volver a escanear.

**Nunca contiene**: contraseñas, claves privadas, tokens permanentes o reutilizables. `exp` es
informativo para la UI; el backend aplica el TTL de forma autoritativa e independiente.

## 6. Máquina de estados de sesión

```
CONNECTING → AUTHENTICATING → CONNECTED → RECONNECTING → CONNECTED
                    │                          │
                    ▼                          ▼
                REJECTED                 DISCONNECTED
```

- `CONNECTING`: socket WS abriéndose.
- `AUTHENTICATING`: `hello`/`hello.ack` en curso (firma Ed25519 del challenge).
- `CONNECTED`: sesión autenticada, heartbeat activo.
- `RECONNECTING`: se perdió el socket, reintento con backoff exponencial + jitter (máx 30s).
- `DISCONNECTED`: cierre limpio o backoff agotado.
- `REJECTED`: autenticación o autorización rechazada (dispositivo revocado, firma inválida).

## 7. Capa de sesión extremo a extremo (E2E)

Desde esta versión el backend **no ve el contenido** de ninguna sesión de control: solo enruta
frames opacos `session.data`. Todo lo que mueve ratón, teclado, portapapeles, archivos, energía o
señalización de vídeo viaja cifrado entre Controller y Host.

### 7.1 Modelo de confianza

| Quién | Puede | No puede |
|---|---|---|
| Backend / relay | Saber *quién* habla con *quién*, cuándo, cuántos bytes. Cortar o retrasar. | Leer, modificar sin que se detecte, ni inyectar mensajes; suplantar al Host o al Controller. |
| Endpoint comprometido (PC o teléfono) | Todo lo que sus permisos permiten. | Superar los permisos que el *otro* extremo concedió; reutilizar una sesión pasada. |

El Controller **no confía** en la respuesta del backend: verifica con la clave de identidad del Host
que guardó al vincular (§3, §5). El Host **no confía** en que el backend diga "este dispositivo está
autorizado": comprueba su propio registro local de dispositivos aprobados en ese PC.

### 7.2 Handshake (1 RTT, claves efímeras por sesión)

1. **Controller → BE → Host `session.connect`**: `{hostId, sessionId (UUID), ephPub (X25519, 32 B), issuedAt (ms), sig}`.
   `sig` = Ed25519 (clave de identidad del Controller) sobre los bytes UTF-8 de
   `connectquick:session.connect:v1|{sessionId}|{hostId}|{deviceId}|{ephPub}|{issuedAt}`.
   El backend verifica la firma, la ventana temporal (±60 s), que `sessionId` no se haya usado, y que el
   dispositivo esté autorizado para ese Host; añade `deviceId`, `deviceName` e `identityPub` antes de
   reenviar. **El Host repite todas esas comprobaciones por su cuenta**: `deviceId` = hash de
   `identityPub`, firma válida, `issuedAt` fresco, `sessionId` no repetido, dispositivo aprobado
   *en este PC*, y que el PC acepte conexiones.
2. **Host → BE → Controller `session.accept`**: `{sessionId, ephPub (X25519), permissions, sig}`.
   `permissions` = lo que el usuario concedió al vincular **∩** lo que el PC tiene habilitado ahora
   (nunca más). `sig` = Ed25519 (identidad del Host) sobre
   `connectquick:session.accept:v1|{sessionId}|{hostId}|{deviceId}|{ctrlEphPub}|{hostEphPub}|{permissions separados por coma}`.
   Cubre **ambas** claves efímeras y los permisos: un relay que cambie una clave, añada un permiso o
   suplante al Host no puede producir esa firma. El Controller la verifica contra la clave del Host
   guardada en el pairing, y rechaza una lista de permisos que no sea subconjunto de la vinculada.
3. **Rechazo**: `session.reject {sessionId, reason}` (p. ej. `not_paired`, `bad_signature`,
   `stale_request`, `replayed_session`, `host_not_accepting`, `user_denied`).

### 7.3 Derivación de claves

```
shared     = X25519(efímera propia, efímera del par)      # se rechaza el secreto todo-ceros
transcript = SHA-256("connectquick:session.transcript:v1|" + sessionId|hostId|deviceId|
                     ctrlEphPub|hostEphPub|permissions)
prk        = HKDF-Extract(salt = transcript, IKM = shared)       # SHA-256
key(C→H)   = HKDF-Expand(prk, "connectquick:session:v1:c2h", 32)
key(H→C)   = HKDF-Expand(prk, "connectquick:session:v1:h2c", 32)
```

Dos claves distintas, una por dirección. Las claves privadas efímeras se destruyen al terminar la
sesión: comprometer una identidad de largo plazo **no** descifra sesiones pasadas (forward secrecy).

### 7.4 Frames `session.data`

`{sessionId, seq, ct}` con `ct = base64url(AES-256-GCM(clave de la dirección, nonce, plaintext, aad) ‖ tag 16 B)`.

- `nonce` (96 bit) = `00 00 00 00 ‖ uint64_be(seq)`. Una clave por dirección + `seq` único ⇒ nonce jamás repetido.
- `aad` = UTF-8 `cq1|{sessionId}|{c2h|h2c}|{seq}`: ata el frame a su sesión, dirección y número.
- `seq` empieza en 0, **estrictamente creciente** por dirección (un frame repetido, antiguo o
  reordenado se descarta), máximo 2³²−1; al agotarse hay que abrir sesión nueva.
- El plaintext es un JSON UTF-8 de **≤ 40 000 bytes**. `ct` ≤ 64 KiB en total por envelope.
- Un frame que no autentica **no mueve** el contador del receptor y cuenta como infracción; 25
  infracciones en una sesión la terminan (`protocol_violations`).

### 7.5 Confirmación de claves y ciclo de vida

La sesión solo se considera establecida cuando el Controller descifra el **primer frame del Host**
(`ctl.caps`, que declara permisos efectivos y funciones disponibles). Si no llega o no autentica,
se aborta (`key_confirmation_failed`). Después, `ctl.ping`/`ctl.pong` cada ~10 s detectan un par
mudo aunque el socket siga abierto (`host_unresponsive`).

`session.end {sessionId, reason}` o la pérdida del socket de señalización **terminan** la sesión.
Al terminar, el Host suelta toda tecla/botón retenido, cancela transferencias y detiene el vídeo
(§9). Razones habituales: `user_disconnect`, `revoked`, `signaling_lost`, `idle_timeout`,
`host_shutdown`, `protocol_violations`, `replaced`, `peer_gone`, `peer_too_slow`.

### 7.6 Límites aplicados por el backend (sin leer el contenido)

Por sesión: 400 frames/s y 8 MiB/s (configurables), cola máxima por receptor 4 MiB (`peer_too_slow`),
200 infracciones consecutivas ⇒ `rate_limit_abuse`. Una sesión sin tráfico 60 s se cierra
(`idle`), una sin aceptar 30 s también, y ninguna dura más de 12 h. Un dispositivo ⇒ una sesión;
un Host ⇒ una sesión (la misma persona reconectando sustituye a la anterior; otra persona recibe
`host_busy`). Un cliente duplicado con la misma identidad recibe el cierre WebSocket `4010`.

## 8. Mensajes internos (dentro de `session.data`)

El backend **no puede validarlos**; cada extremo valida *todo* mensaje entrante contra
[`schemas/session-messages.schema.json`](schemas/session-messages.schema.json) antes de actuar, y un
mensaje inválido se descarta (y cuenta como infracción). Dirección: C = Controller (Android),
H = Host (Windows). "Permiso" = permiso de pairing requerido; el Host además lo cruza con sus
ajustes locales y responde `ctl.error` `forbidden` / `disabled_by_host`.

| `t` | Dir. | Permiso | Campos (`?` = opcional) |
|---|---|---|---|
| `ctl.ping` | ↔ | — | `n` |
| `ctl.pong` | ↔ | — | `n` |
| `ctl.caps` | H→C | — | `hostVersion`, `permissions`, `features`, `screen`?, `maxFileBytes`?, `clipboardSync`?, `relays`? |
| `ctl.error` | H→C | — | `id`?, `code`, `message` |
| `input.mouse_move` | C→H | mouse | `dx`, `dy` |
| `input.mouse_abs` | C→H | mouse | `x`, `y` |
| `input.mouse_button` | C→H | mouse | `button`, `down` |
| `input.mouse_wheel` | C→H | mouse | `dx`, `dy` |
| `input.key` | C→H | keyboard | `code`, `down` |
| `input.text` | C→H | keyboard | `text` |
| `input.release_all` | C→H | mouse o keyboard | — |
| `stream.start` | C→H | remote_desktop | `id`, `maxWidth`, `maxHeight`, `maxFps`, `maxBitrateKbps` |
| `stream.stop` | C→H | remote_desktop | `id`? |
| `stream.state` | H→C | — | `id`?, `state`, `width`?, `height`?, `fps`?, `bitrateKbps`?, `reason`? |
| `stream.stats` | H→C | — | `width`?, `height`?, `fps`, `bitrateKbps`, `rttMs`? |
| `rtc.offer` | H→C | — | `sdp` |
| `rtc.answer` | C→H | remote_desktop | `sdp` |
| `rtc.candidate` | ↔ | remote_desktop | `candidate`, `sdpMid`?, `sdpMLineIndex`? |
| `rtc.end` | ↔ | remote_desktop | `reason`? |
| `clipboard.push` | ↔ | clipboard | `id`, `text`, `sha256` |
| `clipboard.ack` | H→C | — | `id`, `ok`, `reason`? |
| `clipboard.request` | C→H | clipboard | `id` |
| `media.control` | C→H | media | `id`, `action`, `steps`? |
| `media.result` | H→C | — | `id`, `ok`, `confirmed`, `reason`? |
| `file.offer` | ↔ | files | `transferId`, `name`, `size`, `sha256`, `chunkSize` |
| `file.accept` | ↔ | files | `transferId`, `offset` |
| `file.reject` | ↔ | files | `transferId`, `reason` |
| `file.chunk` | ↔ | files | `transferId`, `offset`, `data` |
| `file.ack` | ↔ | files | `transferId`, `received` |
| `file.done` | ↔ | files | `transferId` |
| `file.result` | ↔ | — | `transferId`, `ok`, `reason`?, `savedAs`? |
| `file.cancel` | ↔ | files | `transferId`, `reason`? |
| `file.list` | C→H | files | `id` |
| `file.listing` | H→C | — | `id`, `files` |
| `file.request` | C→H | files | `id`, `transferId`, `name` |
| `power.request` | C→H | power | `id`, `action` |
| `power.challenge` | H→C | — | `id`, `action`, `token`, `expiresInMs`, `delaySeconds` |
| `power.confirm` | C→H | power | `id`, `token` |
| `power.result` | H→C | — | `id`, `ok`, `action`, `reason`?, `delaySeconds`? |

Notas de comportamiento (verificadas por tests de integración, ver `Docs/GUIA_DE_PRUEBAS.md`):

- **Entrada**: movimiento relativo en píxeles tras sensibilidad; absoluto normalizado 0..65535 sobre
  el monitor principal; rueda en unidades de 1/120; teclas por *scancode* físico (`code`, no
  carácter); `input.text` teclea Unicode tal cual (sin caracteres de control). Todo lo retenido se
  suelta con `input.release_all` y automáticamente al terminar la sesión.
- **Portapapeles**: solo texto UTF-8 ≤ 16 KiB con `sha256` verificado por el receptor; el eco de lo
  que acabas de recibir no se reenvía; los datos marcados por Windows como "excluir del
  historial/monitorización" (gestores de contraseñas) no se envían de forma automática.
- **Archivos**: ofertas con `sha256`; el receptor decide (tope, espacio, duplicado, destino) y
  responde el `offset` para reanudar; chunks estrictamente secuenciales ≤ 24 KiB con ventana de
  ack; si llegan más bytes de los anunciados, fuera de orden o el hash final no coincide, se
  descarta todo. Nunca viaja una ruta: solo nombre (saneado por el receptor), tamaño y bytes. Las
  peticiones del teléfono se resuelven contra el listado de la carpeta "para compartir" del PC.
- **Energía**: `lock` y `cancel` actúan al instante; `sleep`/`restart`/`shutdown` devuelven un
  `power.challenge` con token de un solo uso y caducidad corta, y solo se ejecutan cuando el
  teléfono lo devuelve en `power.confirm` tras una confirmación explícita del usuario. No existe
  mensaje alguno para ejecutar comandos arbitrarios.
- **Multimedia**: `media.result.confirmed` es siempre `false` en Windows (no hay confirmación del
  reproductor): la UI dice "enviado", nunca "reproduciendo".

## 9. Vídeo de escritorio (WebRTC)

El relay **nunca transporta vídeo**. La señalización (oferta/respuesta SDP y candidatos ICE) viaja
dentro del canal cifrado de §7 (`rtc.*`); el medio fluye por UDP directo (DTLS-SRTP, VP8) entre el
PC y el teléfono, o a través de un servidor TURN si la red lo exige.

1. C → H `stream.start {id, maxWidth, maxHeight, maxFps, maxBitrateKbps}`. El Host recorta la petición
   a sus propios límites y responde `stream.state {state: starting|streaming|error|stopped, …}`.
2. H → C `rtc.offer`; C → H `rtc.answer`; ambos `rtc.candidate` (el Host envía como máximo 40; el Controller retiene como máximo 60 hasta tener la oferta); H → C
   `stream.stats` cada ~2 s (fps, kbps, RTT) — el Host adapta (AIMD) resolución, fps y bitrate a la
   pérdida/RTT dentro de los límites pedidos.
3. Si Windows no permite leer el escritorio (UAC, pantalla de bloqueo, Ctrl+Alt+Supr) el Host
   informa `stream.state {state: streaming, reason: secure_desktop}` en vez de enviar negro; ConnectQuick
   **no** intenta saltarse UAC ni el escritorio seguro.
4. Cada extremo pide `ice.request` al backend (envelope, no E2E) y recibe `ice.config`: STUN configurados y, si el operador
   definió TURN, credenciales **efímeras por dispositivo** (REST de coturn: usuario = expiración:deviceId,
   contraseña = HMAC-SHA1 con secreto compartido, TTL configurable). Sin TURN, redes con NAT simétrico
   o CGNAT pueden no conectar: la app lo dice (`ice_failed_no_relay`) en lugar de quedarse colgada.
5. `stream.stop`/`rtc.end`, el fin de la sesión o el cierre de la pantalla de vídeo detienen captura
   y codificación y liberan el `PeerConnection` en ambos extremos.

## 10. Capacidades y funciones no implementadas

`ctl.caps` (primer frame cifrado del Host) declara `permissions` efectivos y `features` realmente
presentes. La app **solo** habilita una función si hay sesión verificada **y** el permiso está
concedido; si no, explica por qué (sin conexión / sin permiso / desactivado en el PC).

`apps` (lanzar aplicaciones) está reservado en el contrato de pairing por compatibilidad, **no tiene
implementación** y nunca se concede ni se muestra. Reservado sin implementar: `input.hotkey` (los
atajos se componen con `input.key`), audio del escritorio, múltiples monitores.
