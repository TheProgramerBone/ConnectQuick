# Acceso desde cualquier red con Tailscale Funnel

Para controlar tu PC desde el móvil fuera de casa, el Backend (el "punto de encuentro", ver
`Backend/README.md`) tiene que ser alcanzable desde Internet. Con **Tailscale Funnel** el Backend
sigue corriendo en tu propio PC, y Tailscale le da una dirección pública fija con HTTPS
(`wss://tu-pc.tu-red.ts.net`). **El móvil no necesita instalar nada**: la app lee esa dirección del QR.

Solo hace falta Tailscale **en el PC**.

## Qué expone y qué no

- Expone solo el puerto del Backend (8443 local → 443 público). No expone tu escritorio ni archivos:
  esas funciones ni existen todavía, y el Backend nunca las ve (`Docs/SECURITY.md`).
- Cada dispositivo se autentica con su clave Ed25519 y el Backend limita solicitudes por minuto,
  pero **es un servicio abierto a Internet**. Si no lo estás usando, puedes apagarlo
  (ver "Apagar Funnel").
- El PC debe estar encendido, con el Backend en marcha, para poder vincular o conectar.

## Pasos

1. **Cuenta y Tailscale en el PC.** Crea una cuenta gratuita en <https://tailscale.com> e instala la
   app de Tailscale en el PC (el script lo intenta con `winget`). Inicia sesión.
2. **Habilita HTTPS y Funnel** en la consola de administración (<https://login.tailscale.com/admin/dns>:
   activar *HTTPS Certificates*). La primera vez que actives Funnel, Tailscale mostrará un enlace
   para habilitarlo; ábrelo y acepta.
3. **Genera los instaladores** (en ese mismo PC, con Tailscale ya conectado):
   ```powershell
   .\build-all.ps1 -Funnel
   ```
   El script lee el nombre público de tu PC y lo guarda como relay en el instalador
   (`wss://tu-pc.tu-red.ts.net`).
4. **Instala** `dist\ConnectQuick-Setup.exe`. Deja marcada la casilla del servidor y la de
   *Activar acceso desde Internet (Tailscale Funnel)*; se abre una ventana que ejecuta
   `tailscale funnel --bg 8443`. Si muestra un enlace, ábrelo, habilita Funnel y vuelve a
   ejecutar *Activar acceso desde Internet (Funnel)* desde el menú Inicio.
5. **Instala el APK** en el móvil y vincula como siempre (QR → "¿Autorizar?"). Ahora funciona con
   datos móviles o cualquier Wi-Fi.

## Comprobar que funciona

```powershell
tailscale funnel status          # debe listar https://tu-pc.tu-red.ts.net -> http://127.0.0.1:8443
```

Desde el móvil, con datos móviles (Wi-Fi apagado), escanea el QR. Si se queda en "Conectando...":
- Revisa que el Backend esté corriendo (proceso `connectquick-server.exe` en el Administrador de tareas).
- Revisa que `tailscale funnel status` muestre la regla.
- Prueba abrir `https://tu-pc.tu-red.ts.net` en el navegador del móvil: no mostrará una página
  bonita, pero una respuesta (aunque sea un error) indica que la dirección es alcanzable.

## Apagar Funnel

```powershell
tailscale funnel --https=443 off
```

## Notas

- La instalación con `-Funnel` **no abre el puerto 8443 en el firewall de la red local**: Tailscale
  entrega el tráfico al Backend por `127.0.0.1`, así que no hace falta.
- Si cambias el nombre del PC o de tu red Tailscale, la dirección cambia: vuelve a ejecutar
  `build-all.ps1 -Funnel` y reinstala, o edita `relay.txt` junto a `ConnectQuick.WindowsHost.exe`.
- Alternativas si no quieres exponer nada desde tu casa: un VPS pequeño con el Backend, o Tailscale
  normal en PC y móvil (ver conversación del proyecto / `Docs/ARCHITECTURE.md`).
