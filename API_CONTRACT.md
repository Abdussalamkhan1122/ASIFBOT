# ASIFBOT API Contract

The Android app is wired for a backend at `https://api.asifbot.com`. Override it at build time with `ASIFBOT_API_BASE_URL` when you use a different permanent domain.

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
      "id": "ACC-7K3P9D",
      "bridgeAccountId": "BRG-6M8Q2A",
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
    "id": "ACC-7K3P9D",
    "bridgeAccountId": "BRG-6M8Q2A",
    "label": "Exness Gold Cent",
    "platform": "MT4",
    "broker": "Exness-MT4Real",
    "accountNumber": "12345678",
    "symbol": "XAUUSDc",
    "magicNumber": 7777,
    "connected": false,
    "botStatus": "OFF",
    "bridgeToken": "BOT-3F7K-9D2M-Q8LP"
  }
}
```

`bridgeAccountId` and `bridgeToken` are the short values to enter in `ASIFBOT_Bridge_MT4` or `ASIFBOT_Bridge_MT5`. `bridgeToken` is returned only when the account is created or when the bridge token is rotated. The backend stores only its hash.

### Rotate / Generate Bridge Token

`POST /accounts/{accountId}/bridge-token/rotate`

Use this when the user missed the one-time token dialog or needs to connect the VPS bridge again.

Response:

```json
{
  "account": {
    "id": "ACC-7K3P9D",
    "bridgeAccountId": "BRG-6M8Q2A",
    "label": "Exness Gold Cent",
    "platform": "MT4",
    "broker": "Exness-MT4Real",
    "accountNumber": "12345678",
    "symbol": "EURUSDm",
    "magicNumber": 26091705,
    "connected": false,
    "botStatus": "OFF",
    "bridgeToken": "BOT-3F7K-9D2M-Q8LP"
  }
}
```

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
    "id": "ACC-7K3P9D",
    "bridgeAccountId": "BRG-6M8Q2A",
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
  "accountId": "BRG-6M8Q2A",
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
  "accountId": "BRG-6M8Q2A",
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
  "accountId": "BRG-6M8Q2A",
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
