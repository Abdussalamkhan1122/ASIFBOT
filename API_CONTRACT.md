# ASIFBOT API Contract

The Android app is ready to call a backend at `https://api.asifbot.com`. Change this in `app/build.gradle.kts` if your backend URL is different.

## Authentication

`POST /auth/register`

Request:

```json
{
  "email": "user@example.com",
  "password": "secret123",
  "trialDays": 3
}
```

`POST /auth/login`

Request:

```json
{
  "email": "user@example.com",
  "password": "secret123"
}
```

Both endpoints return:

```json
{
  "token": "jwt-or-session-token",
  "email": "user@example.com",
  "subscriptionActive": false,
  "trialEndsAtMs": 1790438400000,
  "botEnabled": false
}
```

## Account Status

`GET /me`

Header:

```text
Authorization: Bearer <token>
```

Response is the same account JSON as login.

## Bot Control

`GET /bot/status`

Response:

```json
{
  "enabled": true,
  "serverStatus": "online",
  "lastSeen": "2026-09-26T14:30:00Z",
  "account": "XAUUSDc VPS 1"
}
```

`POST /bot/control`

Request:

```json
{
  "enabled": false
}
```

Response is the same bot status JSON.

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
