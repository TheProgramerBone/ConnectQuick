# Tu propio servidor gratis en Google Cloud

Con esto el PC y el móvil se conectan **desde cualquier red** (PC en casa, móvil con datos, otra
Wi-Fi…) sin Tailscale y sin instalar nada más en el PC. Un servidor pequeño en Google Cloud hace de
**punto de encuentro**: el PC y el móvil se conectan hacia él, igual que con AnyDesk. El servidor solo
ve datos **cifrados de extremo a extremo**: no puede ver tu pantalla ni lo que escribes.

```
  PC (solo ConnectQuick) ──►  [ servidor gratis en Google Cloud ]  ◄── Móvil (solo la app)
```

Tiempo: 30–45 minutos, una sola vez. Después no hay que tocarlo.

---

## Antes de empezar: lo que es gratis y lo que no

La capa gratuita de Google Cloud incluye **una** máquina `e2-micro` en algunas regiones de EE. UU.,
30 GB de disco estándar y **1 GB de tráfico de salida al mes**. Para ConnectQuick eso significa:

- **Control** (ratón, teclado, portapapeles, archivos pequeños): gasta muy poco; cabe de sobra.
- **Pantalla en vivo**: casi siempre va **directa** entre PC y móvil y no pasa por el servidor (no gasta
  cuota). Solo cuando la red no lo permite (pasa a menudo con datos móviles), el vídeo pasa por el
  relevo del servidor. Ese tráfico **sí gasta la cuota**: el servidor lo limita a ~1,2 Mbit/s, lo que da
  para unas **2 horas** de pantalla con relevo al mes. En datos móviles la app ya usa el perfil «Auto»
  (calidad de ahorro).
- **IP pública**: Google ha cambiado varias veces lo que cobra por las IP públicas. Por eso en el paso 2
  creas una **alerta de presupuesto de 1 USD**: si algo empezara a costar dinero, te llega un correo y
  decides (por ejemplo, borrar la máquina; no hay permanencia).

Google pide una **tarjeta** para verificar tu identidad, aunque no cobra mientras uses la capa gratis.

---

## 1. Crear la cuenta y el proyecto

1. Entra en <https://console.cloud.google.com> con tu cuenta de Google y acepta la prueba/registro
   (pide la tarjeta).
2. Arriba, en el selector de proyectos → **Proyecto nuevo** → nombre `connectquick` → **Crear**, y
   selecciónalo.

## 2. Alerta de gasto (importante)

Menú ☰ → **Facturación** → **Presupuestos y alertas** → **Crear presupuesto**:
nombre `connectquick`, importe **1 USD**, deja las alertas al 50 %, 90 % y 100 % → **Finalizar**.

## 3. Crear la máquina

Menú ☰ → **Compute Engine** → **Instancias de VM** (la primera vez pide **Habilitar** la API; espera a
que termine) → **Crear instancia**:

| Campo | Valor |
|---|---|
| Nombre | `connectquick` |
| Región | **us-east1** (más cerca de España y Sudamérica) o **us-central1** (México/Centroamérica). Solo `us-east1`, `us-central1` y `us-west1` son gratis. |
| Configuración de la máquina | Serie **E2** → tipo **e2-micro** |
| Disco de arranque → Cambiar | Sistema **Debian 12**, tipo de disco **Disco persistente estándar** (el «equilibrado» NO es gratis), tamaño **30 GB** |
| Firewall | Marca **Permitir tráfico HTTP** y **Permitir tráfico HTTPS** |
| Opciones avanzadas → Redes → Etiquetas de red | `connectquick` |
| Opciones avanzadas → Redes → Interfaz de red → Dirección IPv4 externa | **Reservar dirección IP estática** (nombre `connectquick`). Así la dirección no cambia si reinicias la máquina. |
| Nivel de servicio de red | **Premium** (es el que incluye la capa gratis) |

**Crear**. En un minuto aparece con su **IP externa** (por ejemplo `34.56.78.90`).

## 4. Abrir los puertos del relevo de vídeo

Menú ☰ → **Red de VPC** → **Firewall** → **Crear regla de firewall**:

| Campo | Valor |
|---|---|
| Nombre | `connectquick-turn` |
| Destinos | **Etiquetas de destino especificadas** → `connectquick` |
| Rangos IPv4 de origen | `0.0.0.0/0` |
| Protocolos y puertos | **TCP** `3478` · **UDP** `3478,49160-49200` |

**Crear**.

> Alternativa en un solo comando (botón **Activar Cloud Shell** `>_` arriba a la derecha):
> ```sh
> gcloud compute firewall-rules create connectquick-turn --target-tags=connectquick \
>   --allow=tcp:3478,udp:3478,udp:49160-49200 --source-ranges=0.0.0.0/0
> ```

## 5. Instalar ConnectQuick en la máquina

En **Instancias de VM**, en la fila de `connectquick`, pulsa **SSH** (se abre una ventana negra en el
navegador). Pega estas tres líneas y pulsa Intro:

```sh
sudo apt-get update && sudo apt-get install -y git
sudo git clone https://github.com/TheProgramerBone/ConnectQuick.git /opt/connectquick
sudo bash /opt/connectquick/Deploy/gcp/setup.sh
```

Tarda unos minutos. Al final verás:

```
Listo. Dirección de tu servidor (cópiala):

    wss://34-56-78-90.sslip.io
```

**Copia esa dirección**: la usarás en el PC. (`sslip.io` es un nombre gratuito que apunta a tu IP; no hay
que registrar nada. El certificado HTTPS lo obtiene el servidor solo.)

**Comprobar**: abre en el navegador del móvil `https://34-56-78-90.sslip.io` (tu dirección, con
`https://`). Que salga `{"error":"not_found"}` es **correcto**: significa que el servidor responde
por HTTPS. Si no carga, espera un minuto y repite.

## 6. Cambiar el PC al servidor nuevo

En el PC (no hace falta recompilar ni reinstalar). Actualiza el código del proyecto (`git pull` en tu
carpeta de ConnectQuick) y, en PowerShell, desde esa carpeta:

```powershell
powershell -ExecutionPolicy Bypass -File Scripts\usar-servidor.ps1 -Relay wss://34-56-78-90.sslip.io
```

El script: cierra ConnectQuick y el servidor local, quita el servidor local del inicio de Windows,
guarda la dirección nueva y vuelve a abrir ConnectQuick.

<details><summary>Hacerlo a mano (sin script)</summary>

1. Bandeja del sistema → ConnectQuick → **Salir**. En el Administrador de tareas, finaliza
   `connectquick-server.exe` si aparece.
2. Pulsa `Win + R`, escribe `shell:startup`, Intro, y borra el acceso **ConnectQuick Servidor**.
3. Abre la carpeta de instalación (`%LOCALAPPDATA%\Programs\ConnectQuick`), edita `relay.txt` y deja
   solo una línea: `wss://34-56-78-90.sslip.io`.
4. Abre ConnectQuick de nuevo.
</details>

Si usabas Tailscale solo para esto, ya puedes desinstalarlo (o al menos `tailscale funnel reset`).

## 7. Volver a vincular el móvil

El servidor es nuevo, así que hay que vincular otra vez (el móvil aprende la dirección del QR):

1. PC: **Vincular un teléfono**.
2. Móvil: abre ConnectQuick → **Vincular otro PC** (o **Olvidar este PC** y luego **Vincular un PC**) →
   escanea el QR → compara el código → **Autorizar** en el PC.
3. En el PC, en **Dispositivos**, revoca la entrada antigua del móvil si sigue apareciendo.

## 8. Probar desde otra red

Apaga el Wi-Fi del móvil (solo datos) y abre ConnectQuick: debe decir **Conectado**. Prueba el
touchpad y luego **Pantalla**.

---

## Mantenimiento

- **Actualizar el servidor** cuando haya cambios en ConnectQuick (ventana SSH):
  ```sh
  sudo bash /opt/connectquick/Deploy/gcp/update.sh
  ```
- **Ver qué pasa** (ventana SSH): `sudo journalctl -u connectquick -f` (backend),
  `sudo journalctl -u caddy -f` (HTTPS), `sudo journalctl -u coturn -f` (relevo de vídeo).
- **Gasto**: Menú ☰ → Facturación → Informes. Revísalo los primeros días.
- **Borrarlo todo**: Compute Engine → Instancias de VM → `connectquick` → **Borrar**, y libera la IP
  estática en Red de VPC → Direcciones IP.

## Problemas frecuentes

| Síntoma | Qué revisar |
|---|---|
| El script dice que Caddy no arrancó, o `https://…sslip.io` no carga | Que la máquina tenga marcado **Permitir tráfico HTTP y HTTPS** (Instancias de VM → Editar). El certificado se pide por el puerto 80. |
| PC o móvil se quedan en «Conectando…» | Que la dirección del PC empiece por `wss://` y sea exactamente la que dio el script. `sudo systemctl status connectquick` en la máquina. |
| Control bien pero la pantalla dice que **no hay relevo** o no conecta | La regla de firewall del paso 4 (puertos 3478 y 49160–49200) y la etiqueta `connectquick` en la máquina. `sudo systemctl status coturn`. |
| La pantalla va muy pixelada con datos móviles | Es el límite del relevo para no pasarte de la cuota gratis. En Wi-Fi suele ir directa y con más calidad. Si aceptas gastar más, en la máquina: `sudo TURN_MAX_BPS=400000 bash /opt/connectquick/Deploy/gcp/setup.sh`. |
| Te llegó la alerta de presupuesto | Mira en Facturación → Informes qué concepto cobra. Puedes borrar la máquina en cualquier momento. |

Redes muy restrictivas (algunas empresas u hoteles que solo dejan pasar el puerto 443): el control
funciona (va por 443), pero el relevo de vídeo usa el puerto 3478 y puede quedar bloqueado.
