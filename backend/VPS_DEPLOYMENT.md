# ASIFBOT Backend VPS Deployment

This is the practical path to make ASIFBOT real.

For the permanent DNS, Nginx, HTTPS, and systemd setup, use:

```text
backend/PERMANENT_DNS_VPS_PUBLISH.md
```

## 1. Choose Where Backend Runs

Recommended production layout:

```text
Phone App
  -> HTTPS Backend Domain
    -> VPS/EA Bridge
      -> MT4/MT5 Terminal
```

The backend can run:

- on the same VPS as MetaTrader, or
- on a separate VPS/cloud server.

For security and reliability, a separate small Linux VPS is best. For early testing, the same Windows VPS as MetaTrader is acceptable.

## 2. Requirements

Install Node.js 18 or newer on the backend VPS.

The backend has no npm dependencies, so no heavy install is needed.

On Ubuntu, the fastest setup is:

```bash
sudo bash deploy/install-ubuntu.sh api.asifbot.com
sudo certbot --nginx -d api.asifbot.com
```

## 3. Upload Backend

Upload this folder to the VPS:

```text
ASIFBOT_App/backend
```

Or clone the GitHub repository and enter:

```powershell
cd ASIFBOT_App\backend
```

## 4. Configure Environment

Create `.env` from the example:

```powershell
Copy-Item .env.example .env
```

Edit `.env`:

```text
PORT=8080
ASIFBOT_TOKEN_SECRET=put-a-long-random-secret-here
ASIFBOT_DATA_DIR=./data
ASIFBOT_CORS_ORIGIN=*
ASIFBOT_BILLING_MODE=mock
ASIFBOT_TOKEN_TTL_DAYS=30
ASIFBOT_SMTP_HOST=smtp.gmail.com
ASIFBOT_SMTP_PORT=465
ASIFBOT_SMTP_USER=your-gmail-address@gmail.com
ASIFBOT_SMTP_PASS=your-gmail-app-password
ASIFBOT_SMTP_FROM=your-gmail-address@gmail.com
ASIFBOT_RESET_CODE_TTL_MINUTES=15
```

Important:

- Keep `ASIFBOT_TOKEN_SECRET` private.
- Keep `ASIFBOT_SMTP_PASS` private. Use a Gmail App Password, not your normal Gmail password.
- Do not push `.env` to GitHub.
- Keep `backend/data/db.json` backed up.

## 5. Start Backend

```powershell
node server.js
```

Test:

```text
http://YOUR_SERVER_IP:8080/health
```

Expected:

```json
{"ok":true}
```

## 6. HTTPS Domain

For Play Store and real phone usage, use HTTPS:

```text
https://api.asifbot.com
```

You can put Nginx, Cloudflare Tunnel, or your hosting panel in front of the Node server.

Do not release the app pointing at plain `http://` for real users.

## 7. Connect Android App

Edit:

```text
app/build.gradle.kts
```

Set:

```kotlin
buildConfigField("String", "API_BASE_URL", "\"https://api.asifbot.com\"")
buildConfigField("boolean", "DEMO_MODE", "false")
```

The app also supports passing this during build:

```powershell
gradle :app:assembleDebug -PASIFBOT_API_BASE_URL=https://api.asifbot.com -PASIFBOT_DEMO_MODE=false
```

Then push to GitHub and download the new APK/AAB from Actions.

## 8. Connect VPS/EA Bridge

In the Android app:

1. Login.
2. Add MT4/MT5 account.
3. Save the shown:
   - Bridge Account ID
   - Bridge Token

On the VPS bridge:

```powershell
$env:ASIFBOT_API_BASE_URL="https://api.asifbot.com"
$env:ASIFBOT_ACCOUNT_ID="BRG-6M8Q2A"
$env:ASIFBOT_BRIDGE_TOKEN="BOT-3F7K-9D2M-Q8LP"
node bridge-example.js
```

The example bridge only simulates MetaTrader data. The final bridge must read real MT4/MT5 data and execute real commands.

## 9. Real OFF Behavior

When the app sends OFF:

1. Backend stores command `TURN_OFF_CLOSE_TRADES`.
2. Bridge receives it on next heartbeat.
3. Bridge disables new entries.
4. Bridge deletes ASIFBOT pending orders.
5. Bridge closes ASIFBOT open trades.
6. Bridge reports command completion.
7. App dashboard shows OFF and updated open trades.

The bridge must filter by:

- trading account
- symbol
- magic number
- EA comment/name

## 10. Data Security

This backend already:

- hashes user passwords
- signs auth tokens
- stores only bridge token hashes
- isolates accounts by user ID
- supports delete linked account
- keeps audit log records

For a larger public launch:

- move JSON storage to PostgreSQL/MySQL
- enable real Google Play Developer API verification
- add rate limiting
- add automatic encrypted backups
- add server firewall rules
- use HTTPS only
