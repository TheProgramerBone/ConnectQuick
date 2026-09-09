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

## 4. Catálogo de mensajes — Fase 1-3 (implementados esta sesión)

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

### Gestión de sesión / presencia

| `type`               | Emisor     | Descripción                                                        |
|-----------------------|------------|------------------------------------------------------------------------|
| `presence.update`     | BE → Ctrl  | Cambia el estado online/offline de un Host vinculado.               |
| `session.connect`     | Ctrl → BE, BE → Host | Controller pide iniciar una sesión con un Host ya vinculado; el backend reenvía el mismo `type` al Host, añadiendo `deviceId`/`deviceName` del Controller (no presentes en el mensaje original Ctrl→BE) para que el Host sepa quién pide entrar. |
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
  "exp": 1735689660
}
```

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

## 7. Reservado para fases futuras (no implementado esta sesión)

Ya reservado en el espacio de nombres de `type` para no romper compatibilidad hacia atrás:
`desktop.frame`, `input.mouse_move`, `input.mouse_click`, `input.key`, `input.hotkey`,
`clipboard.push`, `clipboard.pull`, `files.list`, `files.upload_chunk`, `files.download_chunk`,
`media.control`, `power.action`, `apps.launch`. Diseño detallado en
[`/Docs/ROADMAP.md`](../Docs/ROADMAP.md).

## 8. Capabilities

Al autenticar, el backend informa al Controller de las capacidades del Host conectado:

```json
{ "protocolVersion": 1, "hostVersion": "0.1.0", "capabilities": ["pairing", "presence"] }
```

Fases futuras añadirán `"desktop_stream"`, `"mouse"`, `"keyboard"`, `"clipboard"`, `"files"`,
`"media"`, `"power"`, `"apps"` a esta lista — la UI de Android debe ocultar cualquier función cuya
capability no esté presente (nunca mostrar un botón para algo que el Host no soporta todavía).
