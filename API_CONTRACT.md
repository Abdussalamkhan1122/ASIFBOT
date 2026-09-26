# ASIFBOT API Contract

The Android app is wired for a backend at `https://api.asifbot.com`. Change `API_BASE_URL` in `app/build.gradle.kts` when your real backend is ready.

For phone testing, `DEMO_MODE=true` keeps everything local inside the app.

## Authentication

### Register

`POST /auth/register`

Request:

```json
{
  "email": "user@example.com",
  "password": "secret123",
  "trialDays": 3
}
```

Response:

```json
{
  "token": "jwt-or-session-token",
  "email": "user@example.com",
  "subscriptionActive": false,
  "trialEndsAtMs": 1790438400000,
  "botEnabled": false
}
```

### Login

`POST /auth/login`

Request:

```json
{
  "email": "user@example.com",
  "password": "secret123"
}
```

Response is the same account-state JSON as register.

### Current User

`GET /me`

Header:

```text
Authorization: Bearer <token>
```

Response:

```json
{
  "token": "jwt-or-session-token",
  "email": "user@example.com",
  "subscriptionActive": true,
  "trialEndsAtMs": 1790438400000,
  "botEnabled": true
}
```

## Linked MT4/MT5 Accounts

Every endpoint below must filter by the logged-in `user_id`. A user must never see or control another user's trading account.

### List Accounts

`GET /accounts`

Response:

```json
{
  "accounts": [
    {
      "id": "acc_123",
      "label": "Exness Gold Cent",
      "platform": "MT4",
      "broker": "Exness-MT4Real",
      "accountNumber": "12345678",
      "symbol": "XAUUSDc",
      "magicNumber": 7777,
      "connected": true,
      "botStatus": "ON"
    }
  ]
}
```

### Create Account

`POST /accounts`

Request:

```json
{
  "label": "Exness Gold Cent",
  "platform": "MT4",
  "broker": "Exness-MT4Real",
  "accountNumber": "12345678",
  "symbol": "XAUUSDc",
  "magicNumber": 7777
}
```

Response:

```json
{
  "account": {
    "id": "acc_123",
    "label": "Exness Gold Cent",
    "platform": "MT4",
    "broker": "Exness-MT4Real",
    "accountNumber": "12345678",
    "symbol": "XAUUSDc",
    "magicNumber": 7777,
    "connected": false,
    "botStatus": "OFF",
    "bridgeToken": "shown-only-once-save-this-on-vps"
  }
}
```

`bridgeToken` is returned only when the account is created or when the bridge token is rotated. The backend stores only its hash.

### Delete Account

`DELETE /accounts/{accountId}`

Behavior:

- Verify this account belongs to the logged-in user.
- Delete or soft-delete the ASIFBOT linked account record.
- Invalidate its VPS/EA bridge token.
- Do not delete the real broker account.

Response:

```json
{
  "accounts": []
}
```

## Dashboard

### Account Dashboard

`GET /accounts/{accountId}/dashboard`

Response:

```json
{
  "account": {
    "id": "acc_123",
    "label": "Exness Gold Cent",
    "platform": "MT4",
    "broker": "Exness-MT4Real",
    "accountNumber": "12345678",
    "symbol": "XAUUSDc",
    "magicNumber": 7777,
    "connected": true,
    "botStatus": "ON"
  },
  "metrics": {
    "balance": 50.0,
    "equity": 51.2,
    "marginLevel": 350.5,
    "floatingProfit": 1.2,
    "openTradeCount": 3,
    "totalLots": 0.09,
    "worstTradeLoss": -0.35
  },
  "trades": [
    {
      "ticket": "123456",
      "symbol": "XAUUSDc",
      "side": "BUY",
      "lots": 0.03,
      "openPrice": 4314.126,
      "currentPrice": 4315.226,
      "profit": 0.33,
      "openTime": "2026-09-26 21:10"
    }
  ]
}
```

### Open Trades

`GET /accounts/{accountId}/trades/open`

Response:

```json
{
  "trades": []
}
```

## Bot Commands

### Turn ON

`POST /accounts/{accountId}/bot/on`

Request:

```json
{}
```

Response: dashboard JSON.

### Turn OFF and Close ASIFBOT Trades

`POST /accounts/{accountId}/bot/off`

Request:

```json
{
  "closeOpenTrades": true,
  "deletePendingOrders": true,
  "scope": "asifbot_only"
}
```

Backend behavior:

- Create command `TURN_OFF_CLOSE_TRADES`.
- VPS bridge stops new entries.
- VPS bridge deletes ASIFBOT pending orders.
- VPS bridge closes only ASIFBOT trades for that account, symbol, and magic number.
- Backend returns latest dashboard state.

Response: dashboard JSON.

### Emergency Close

`POST /accounts/{accountId}/bot/emergency-close`

Request:

```json
{
  "scope": "asifbot_only"
}
```

Response: dashboard JSON.

## Google Play Subscription Verification

`POST /billing/google/verify`

Request:

```json
{
  "platform": "google_play",
  "productId": "asifbot_monthly",
  "purchaseToken": "purchase-token-from-google-play"
}
```

Response:

```json
{
  "token": "jwt-or-session-token",
  "email": "user@example.com",
  "subscriptionActive": true,
  "trialEndsAtMs": 1790438400000,
  "botEnabled": true
}
```

The backend must verify the purchase token with the Google Play Developer API before enabling access.

## VPS / EA Bridge APIs

The bridge must authenticate with the linked account ID and bridge token. It can send the token either as:

```text
Authorization: Bridge <bridgeToken>
```

or:

```text
X-Bridge-Token: <bridgeToken>
```

### Bridge Heartbeat and Command Poll

`POST /bridge/heartbeat`

Request:

```json
{
  "accountId": "acc_123",
  "botStatus": "ON",
  "metrics": {
    "balance": 50.0,
    "equity": 50.84,
    "marginLevel": 420.5,
    "floatingProfit": 0.84,
    "openTradeCount": 2,
    "totalLots": 0.04,
    "worstTradeLoss": -0.11
  },
  "trades": []
}
```

Response:

```json
{
  "ok": true,
  "accountId": "acc_123",
  "serverTime": "2026-09-27T10:00:00.000Z",
  "desiredBotStatus": "ON",
  "commands": [
    {
      "id": "cmd_123",
      "type": "TURN_OFF_CLOSE_TRADES",
      "payload": {
        "closeOpenTrades": true,
        "deletePendingOrders": true,
        "scope": "asifbot_only"
      },
      "status": "SENT"
    }
  ]
}
```

### Complete Bridge Command

`POST /bridge/commands/{commandId}/complete`

Request:

```json
{
  "accountId": "acc_123",
  "success": true,
  "message": "Closed ASIFBOT trades",
  "closedTrades": 3,
  "deletedPendingOrders": 2,
  "metrics": {},
  "trades": []
}
```

Response:

```json
{
  "ok": true,
  "command": {
    "id": "cmd_123",
    "type": "TURN_OFF_CLOSE_TRADES",
    "status": "COMPLETED"
  }
}
```
