# ASIFBOT Backend

This is the real backend API for the ASIFBOT Android app and the VPS/EA bridge.

It is dependency-free Node.js so it can run on a simple VPS without Android Studio, Gradle, or npm packages.

For live hosting steps, read `VPS_DEPLOYMENT.md`.

## What It Supports

- Email/password register and login.
- 3-day trial state.
- Mock Google Play subscription verification for staging.
- Multiple MT4/MT5 trading accounts per user.
- Delete linked account.
- User ownership isolation.
- Bot ON command.
- Bot OFF + close ASIFBOT trades command.
- Emergency close command.
- VPS bridge heartbeat.
- VPS bridge command polling.
- VPS bridge command completion.
- Password hashing with PBKDF2.
- Signed auth tokens.
- Bridge token hashing.
- JSON audit log records.

## Run Locally

From this folder:

```powershell
Copy-Item .env.example .env
node server.js
```

Open:

```text
http://127.0.0.1:8080/health
```

Expected response:

```json
{"ok":true}
```

## Important Production Settings

Edit `.env`:

```text
PORT=8080
ASIFBOT_TOKEN_SECRET=make-this-a-long-random-secret
ASIFBOT_DATA_DIR=./data
ASIFBOT_CORS_ORIGIN=*
ASIFBOT_BILLING_MODE=mock
ASIFBOT_TOKEN_TTL_DAYS=30
```

For production, `ASIFBOT_TOKEN_SECRET` must be long and private. Do not keep the example secret.

## Android Connection

When the backend is deployed to a real HTTPS domain:

1. Open `app/build.gradle.kts`.
2. Set:

```kotlin
buildConfigField("String", "API_BASE_URL", "\"https://your-domain.com\"")
buildConfigField("boolean", "DEMO_MODE", "false")
```

3. Push to GitHub.
4. Download the new APK/AAB from GitHub Actions.

## Backend API

The Android app uses:

```text
POST   /auth/register
POST   /auth/login
GET    /me
POST   /billing/google/verify
GET    /accounts
POST   /accounts
DELETE /accounts/{accountId}
GET    /accounts/{accountId}/dashboard
GET    /accounts/{accountId}/trades/open
POST   /accounts/{accountId}/bot/on
POST   /accounts/{accountId}/bot/off
POST   /accounts/{accountId}/bot/emergency-close
```

The VPS/EA bridge uses:

```text
POST /bridge/heartbeat
POST /bridge/commands/{commandId}/complete
```

## Bridge Token

When the Android app creates a trading account in real mode, the backend returns a one-time `bridgeToken`.

Save:

- Account ID
- Bridge Token

These two values must be placed on the VPS bridge.

The backend stores only a hash of the bridge token. If the token is lost, rotate the bridge token and update the VPS.

## VPS Bridge Test

After creating an account and saving the bridge token:

```powershell
$env:ASIFBOT_API_BASE_URL="http://127.0.0.1:8080"
$env:ASIFBOT_ACCOUNT_ID="acc_your_id"
$env:ASIFBOT_BRIDGE_TOKEN="your_bridge_token"
node bridge-example.js
```

The bridge example simulates MetaTrader status. It does not place or close real trades.

## Real MT4/MT5 Bridge Behavior

The real bridge or EA must:

1. Send heartbeat every 1-3 seconds.
2. Report balance, equity, margin level, floating profit, open trades, total lots, and worst trade loss.
3. Receive pending backend commands.
4. For `TURN_ON`, allow ASIFBOT EA entries.
5. For `TURN_OFF_CLOSE_TRADES`, stop new entries, delete ASIFBOT pending orders, and close ASIFBOT trades.
6. For `EMERGENCY_CLOSE`, close ASIFBOT trades immediately with retries.
7. Complete the command using `/bridge/commands/{commandId}/complete`.

OFF and emergency close must filter by:

- account
- symbol
- magic number
- EA comment/name when available

They must not close manual trades or another EA's trades.

## Production Database Note

This backend stores data in `backend/data/db.json`. That is good for VPS testing and a small private launch.

For a larger Play Store launch, move the same data model to PostgreSQL or MySQL. The API shape can stay the same.
