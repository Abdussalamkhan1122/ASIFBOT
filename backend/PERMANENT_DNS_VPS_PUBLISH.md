# ASIFBOT Permanent DNS + VPS Publish Guide

Use this path when the Android app must stop using temporary tunnel links and always connect to a permanent backend domain.

Recommended production URL:

```text
https://api.asifbot.com
```

You can replace `api.asifbot.com` with any domain or subdomain you own.

## 1. DNS Setup

In your domain DNS panel, create:

```text
Type: A
Name: api
Value: YOUR_BACKEND_VPS_PUBLIC_IP
TTL: Auto or 300
Proxy: DNS only at first
```

Wait until this works from your PC:

```powershell
nslookup api.asifbot.com
```

It should return your VPS public IP.

## 2. Backend VPS Setup

Use an Ubuntu VPS for the backend if possible. The MetaTrader VPS can stay Windows; it only needs to reach the backend URL.

### Fast Install

Create a backend zip from Windows:

```powershell
.\backend\deploy\make-backend-zip.ps1 -OutputPath .\asifbot-backend.zip
```

Upload the zip or the `backend` folder to the Ubuntu VPS, then run:

```bash
cd /path/to/uploaded/backend
sudo bash deploy/install-ubuntu.sh api.asifbot.com
```

After the script finishes, issue HTTPS:

```bash
sudo certbot --nginx -d api.asifbot.com
curl https://api.asifbot.com/health
```

The manual steps below are the same setup written out for troubleshooting or custom VPS layouts.

### Manual Install

Install Node.js 18+ and Nginx:

```bash
sudo apt update
sudo apt install -y nodejs npm nginx certbot python3-certbot-nginx
node --version
```

Create the app user and folders:

```bash
sudo useradd --system --home /opt/asifbot --shell /usr/sbin/nologin asifbot || true
sudo mkdir -p /opt/asifbot/backend /var/lib/asifbot
sudo chown -R asifbot:asifbot /opt/asifbot /var/lib/asifbot
```

Upload this folder to the VPS:

```text
ASIFBOT_App/backend
```

Put it at:

```text
/opt/asifbot/backend
```

Create production env:

```bash
cd /opt/asifbot/backend
sudo cp .env.production.example .env
sudo nano .env
```

Set a real secret:

```text
ASIFBOT_TOKEN_SECRET=use-a-long-random-secret-never-share-this
ASIFBOT_DATA_DIR=/var/lib/asifbot
```

## 3. Run Backend Permanently

Install the systemd service:

```bash
sudo cp /opt/asifbot/backend/deploy/asifbot-backend.service /etc/systemd/system/asifbot-backend.service
sudo systemctl daemon-reload
sudo systemctl enable --now asifbot-backend
sudo systemctl status asifbot-backend
```

Test local backend on the VPS:

```bash
curl http://127.0.0.1:8080/health
```

Expected:

```json
{"ok":true}
```

## 4. Add Nginx + HTTPS

Edit the Nginx config before copying if your domain is not `api.asifbot.com`:

```bash
sudo cp /opt/asifbot/backend/deploy/nginx-asifbot.conf /etc/nginx/sites-available/asifbot
sudo ln -s /etc/nginx/sites-available/asifbot /etc/nginx/sites-enabled/asifbot
sudo nginx -t
sudo systemctl reload nginx
```

Issue the SSL certificate:

```bash
sudo certbot --nginx -d api.asifbot.com
```

Now test from your PC or phone browser:

```text
https://api.asifbot.com/health
```

## 5. Build Android App With Permanent Domain

The app now reads the backend URL from `ASIFBOT_API_BASE_URL`. If not provided, it defaults to:

```text
https://api.asifbot.com
```

For GitHub Actions, add repository variables or secrets:

```text
ASIFBOT_API_BASE_URL=https://api.asifbot.com
ASIFBOT_DEMO_MODE=false
```

For a local build:

```powershell
gradle :app:assembleDebug -PASIFBOT_API_BASE_URL=https://api.asifbot.com -PASIFBOT_DEMO_MODE=false
```

After deployment, test the live API from Windows:

```powershell
.\backend\deploy\health-check.ps1 -ApiBaseUrl https://api.asifbot.com
```

## 6. Link MT4/MT5 VPS Bridge

In the Android app:

1. Login.
2. Add the trading account.
3. Save the shown `Bridge Account ID`.
4. Save the shown `Bridge Token`.

In MetaTrader on the VPS, attach:

```text
bridge/ASIFBOT_Bridge_MT4.mq4
```

or:

```text
bridge/ASIFBOT_Bridge_MT5.mq5
```

Set inputs:

```text
BackendUrl    = https://api.asifbot.com
AccountId     = BRG-XXXXXX
BridgeToken   = BOT-XXXX-XXXX-XXXX
ManagedMagic  = your EA magic number
ManagedSymbol = XAUUSDc
```

In MetaTrader, allow WebRequest for:

```text
https://api.asifbot.com
```

When the bridge is connected, the app dashboard should show the VPS bridge as online within about 45 seconds.

For the Node bridge example on a Windows VPS, copy `backend/deploy/bridge-env-template.ps1`, fill in the real Bridge Account ID and Bridge Token, then run it from the `backend` folder.

## 7. Quick Production Checklist

- DNS `A` record points `api.asifbot.com` to the backend VPS IP.
- `https://api.asifbot.com/health` returns JSON.
- `systemctl status asifbot-backend` shows running.
- Android `DEMO_MODE=false`.
- Android `API_BASE_URL=https://api.asifbot.com`.
- MetaTrader WebRequest allows the same HTTPS URL.
- Bridge Account ID and Bridge Token are copied exactly.
- `/var/lib/asifbot/db.json` is backed up regularly.
