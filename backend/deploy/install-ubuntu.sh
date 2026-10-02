#!/usr/bin/env bash
set -euo pipefail

DOMAIN="${1:-api.asifbot.com}"
APP_USER="${ASIFBOT_APP_USER:-asifbot}"
APP_DIR="${ASIFBOT_APP_DIR:-/opt/asifbot/backend}"
DATA_DIR="${ASIFBOT_DATA_DIR:-/var/lib/asifbot}"
SERVICE_NAME="asifbot-backend"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [ ! -f "${SOURCE_DIR}/server.js" ]; then
  echo "Run this from the uploaded backend folder, for example:"
  echo "  cd /opt/asifbot/backend && sudo bash deploy/install-ubuntu.sh ${DOMAIN}"
  exit 1
fi

if ! command -v sudo >/dev/null 2>&1; then
  echo "sudo is required."
  exit 1
fi

echo "Installing ASIFBOT backend for domain: ${DOMAIN}"

sudo apt update
sudo apt install -y curl ca-certificates nginx certbot python3-certbot-nginx

NODE_MAJOR="$(node -p 'Number(process.versions.node.split(".")[0])' 2>/dev/null || echo 0)"
if [ "${NODE_MAJOR}" -lt 18 ]; then
  echo "Installing Node.js 20 LTS because Node.js 18+ is required."
  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
  sudo apt install -y nodejs
fi

if ! id "${APP_USER}" >/dev/null 2>&1; then
  sudo useradd --system --home /opt/asifbot --shell /usr/sbin/nologin "${APP_USER}"
fi

sudo mkdir -p "${APP_DIR}" "${DATA_DIR}"
SOURCE_REAL="$(readlink -f "${SOURCE_DIR}")"
APP_REAL="$(readlink -f "${APP_DIR}")"
if [ "${SOURCE_REAL}" != "${APP_REAL}" ]; then
  sudo cp -a "${SOURCE_DIR}/." "${APP_DIR}/"
fi
sudo chown -R "${APP_USER}:${APP_USER}" /opt/asifbot "${DATA_DIR}"

if [ ! -f "${APP_DIR}/.env" ]; then
  SECRET="$(openssl rand -hex 32 2>/dev/null || tr -dc 'A-Za-z0-9' </dev/urandom | head -c 64)"
  sudo tee "${APP_DIR}/.env" >/dev/null <<EOF
PORT=8080
ASIFBOT_TOKEN_SECRET=${SECRET}
ASIFBOT_DATA_DIR=${DATA_DIR}
ASIFBOT_CORS_ORIGIN=*
ASIFBOT_BILLING_MODE=mock
ASIFBOT_TOKEN_TTL_DAYS=30
ASIFBOT_SMTP_HOST=smtp.gmail.com
ASIFBOT_SMTP_PORT=465
ASIFBOT_SMTP_USER=your-gmail-address@gmail.com
ASIFBOT_SMTP_PASS=your-gmail-app-password
ASIFBOT_SMTP_FROM=your-gmail-address@gmail.com
ASIFBOT_RESET_CODE_TTL_MINUTES=15
EOF
  sudo chown "${APP_USER}:${APP_USER}" "${APP_DIR}/.env"
  sudo chmod 600 "${APP_DIR}/.env"
fi

sudo cp "${APP_DIR}/deploy/${SERVICE_NAME}.service" "/etc/systemd/system/${SERVICE_NAME}.service"
sudo systemctl daemon-reload
sudo systemctl enable "${SERVICE_NAME}"
sudo systemctl restart "${SERVICE_NAME}"

TMP_NGINX="$(mktemp)"
sed "s/server_name api\.asifbot\.com;/server_name ${DOMAIN};/" "${APP_DIR}/deploy/nginx-asifbot.conf" > "${TMP_NGINX}"
sudo cp "${TMP_NGINX}" /etc/nginx/sites-available/asifbot
rm -f "${TMP_NGINX}"

if [ ! -e /etc/nginx/sites-enabled/asifbot ]; then
  sudo ln -s /etc/nginx/sites-available/asifbot /etc/nginx/sites-enabled/asifbot
fi

sudo nginx -t
sudo systemctl reload nginx

echo
echo "Backend service:"
sudo systemctl --no-pager --lines=8 status "${SERVICE_NAME}" || true

echo
echo "Local health check:"
curl -fsS http://127.0.0.1:8080/health || true

echo
echo "Next commands for HTTPS:"
echo "  sudo certbot --nginx -d ${DOMAIN}"
echo "  curl https://${DOMAIN}/health"
