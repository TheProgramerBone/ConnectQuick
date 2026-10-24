#!/usr/bin/env bash
# ConnectQuick: turns a fresh Google Cloud e2-micro VM (Debian 12 / Ubuntu 22.04+) into the public
# meeting point for your PC and phone. Installs and configures, all on this one VM:
#   - the ConnectQuick backend (signalling + end-to-end-encrypted relay), behind
#   - Caddy, which gets a free HTTPS certificate automatically and serves wss:// on port 443,
#   - coturn, the video relay (TURN) for when the phone and the PC cannot reach each other directly.
# The hostname is <public-ip-with-dashes>.sslip.io: a free DNS name that needs no account.
#
# Run it on the VM (SSH window), from a checkout of the repository:
#   sudo bash Deploy/gcp/setup.sh
# Safe to run again (e.g. after `git pull`): it keeps the TURN secret and the database.
set -euo pipefail

NODE_VERSION="22.22.0"            # >= 22.18 runs the TypeScript sources directly
TURN_MIN_PORT=49160
TURN_MAX_PORT=49200
# Cap per relayed stream (bytes/s). The free tier includes ~1 GB/month of outbound traffic; ~150 KB/s (1.2 Mbit/s)
# fits the phone's "Ahorro"/"Auto" profiles and makes that quota last ~2 h of relayed video. 0 = no cap.
TURN_MAX_BPS="${TURN_MAX_BPS:-150000}"

if [[ $EUID -ne 0 ]]; then echo "Ejecuta con sudo: sudo bash $0" >&2; exit 1; fi
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ -f "$REPO_DIR/Backend/package.json" ]] || { echo "No encuentro Backend/ junto a este script ($REPO_DIR)" >&2; exit 1; }

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# ------------------------------------------------------------------ addresses
say "Detectando direcciones"
md() { curl -fsS -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/$1" 2>/dev/null || true; }
PUBLIC_IP="${PUBLIC_IP:-$(md access-configs/0/external-ip)}"
INTERNAL_IP="${INTERNAL_IP:-$(md ip)}"
[[ -n "$PUBLIC_IP" ]] || PUBLIC_IP="$(curl -fsS https://api.ipify.org || true)"
[[ -n "$INTERNAL_IP" ]] || INTERNAL_IP="$(hostname -I | awk '{print $1}')"
[[ "$PUBLIC_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "No pude averiguar la IP pública. Ejecuta: sudo PUBLIC_IP=x.x.x.x bash $0" >&2; exit 1; }
DOMAIN="${DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"
echo "IP pública: $PUBLIC_IP   IP interna: $INTERNAL_IP   Nombre: $DOMAIN"

# ------------------------------------------------------------------ packages
say "Instalando paquetes (Caddy, coturn)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https coturn xz-utils
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q
  apt-get install -y -q caddy
fi

# e2-micro has 1 GB of RAM: a small swap file keeps npm and the services from being killed.
if ! swapon --show | grep -q /swapfile; then
  say "Creando 1 GB de swap"
  fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# ------------------------------------------------------------------ node
if ! /usr/local/bin/node --version 2>/dev/null | grep -q "^v${NODE_VERSION}$"; then
  say "Instalando Node.js $NODE_VERSION"
  case "$(uname -m)" in x86_64) ARCH=x64 ;; aarch64) ARCH=arm64 ;; *) echo "Arquitectura no soportada" >&2; exit 1 ;; esac
  TMP="$(mktemp -d)"
  curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${ARCH}.tar.xz" -o "$TMP/node.tar.xz"
  tar -xJf "$TMP/node.tar.xz" -C /usr/local --strip-components=1
  rm -rf "$TMP"
fi

# ------------------------------------------------------------------ backend
say "Instalando el backend de ConnectQuick"
id connectquick >/dev/null 2>&1 || useradd --system --home /var/lib/connectquick --shell /usr/sbin/nologin connectquick
install -d -o connectquick -g connectquick -m 750 /var/lib/connectquick
install -d -m 750 /etc/connectquick
(cd "$REPO_DIR/Backend" && /usr/local/bin/npm ci --omit=dev --no-audit --no-fund)
chmod -R a+rX "$REPO_DIR"

SECRET_FILE=/etc/connectquick/turn-secret
[[ -s "$SECRET_FILE" ]] || { head -c 32 /dev/urandom | base64 | tr -d '\n' > "$SECRET_FILE"; chmod 600 "$SECRET_FILE"; }
TURN_SECRET="$(cat "$SECRET_FILE")"

cat > /etc/connectquick/backend.env <<ENV
PORT=8443
DB_PATH=/var/lib/connectquick/connectquick.sqlite
TRUST_PROXY=1
TURN_URLS=turn:${PUBLIC_IP}:3478?transport=udp,turn:${PUBLIC_IP}:3478?transport=tcp
TURN_SECRET=${TURN_SECRET}
LOG_LEVEL=INFO
ENV
chmod 640 /etc/connectquick/backend.env
chgrp connectquick /etc/connectquick/backend.env

cat > /etc/systemd/system/connectquick.service <<UNIT
[Unit]
Description=ConnectQuick backend (signalling + encrypted relay)
After=network-online.target
Wants=network-online.target

[Service]
User=connectquick
Group=connectquick
EnvironmentFile=/etc/connectquick/backend.env
WorkingDirectory=${REPO_DIR}/Backend
ExecStart=/usr/local/bin/node src/server.ts
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/connectquick
ProtectHome=read-only
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

# ------------------------------------------------------------------ caddy (HTTPS + wss on 443)
say "Configurando HTTPS (Caddy)"
cat > /etc/caddy/Caddyfile <<CADDY
${DOMAIN} {
	# WebSocket upgrades are proxied as-is; the backend only ever sees end-to-end-encrypted frames.
	reverse_proxy 127.0.0.1:8443
}
CADDY

# ------------------------------------------------------------------ coturn (video relay)
say "Configurando el relevo de vídeo (coturn)"
cat > /etc/turnserver.conf <<TURN
# Managed by ConnectQuick Deploy/gcp/setup.sh
listening-port=3478
listening-ip=${INTERNAL_IP}
relay-ip=${INTERNAL_IP}
external-ip=${PUBLIC_IP}/${INTERNAL_IP}
min-port=${TURN_MIN_PORT}
max-port=${TURN_MAX_PORT}
realm=${DOMAIN}
use-auth-secret
static-auth-secret=${TURN_SECRET}
fingerprint
no-tls
no-dtls
no-cli
no-multicast-peers
# Never relay into private / cloud-internal networks (only to the public addresses of your devices).
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
user-quota=4
total-quota=20
max-bps=${TURN_MAX_BPS}
syslog
simple-log
TURN
[[ -f /etc/default/coturn ]] && sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn

# ------------------------------------------------------------------ start
say "Arrancando servicios"
systemctl daemon-reload
systemctl enable --now connectquick
systemctl restart connectquick
systemctl enable coturn >/dev/null 2>&1 || true
systemctl restart coturn
systemctl enable caddy >/dev/null 2>&1 || true
systemctl restart caddy

sleep 3
ok=1
systemctl is-active --quiet connectquick || { echo "El backend no arrancó: journalctl -u connectquick -n 50" >&2; ok=0; }
systemctl is-active --quiet coturn || { echo "coturn no arrancó: journalctl -u coturn -n 50" >&2; ok=0; }
systemctl is-active --quiet caddy || { echo "Caddy no arrancó: journalctl -u caddy -n 50" >&2; ok=0; }

echo
if [[ $ok -eq 1 ]]; then
  printf '\033[1;32mListo.\033[0m Dirección de tu servidor (cópiala):\n\n    wss://%s\n\n' "$DOMAIN"
  echo "Si el primer intento de HTTPS falla, revisa que la VM tenga marcado 'Permitir tráfico HTTPS/HTTP'"
  echo "y espera un minuto: Caddy pide el certificado en segundo plano (journalctl -u caddy -f)."
else
  echo "Algo falló; los comandos de arriba muestran el motivo."
  exit 1
fi
