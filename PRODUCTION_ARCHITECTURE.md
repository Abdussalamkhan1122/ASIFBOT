# ASIFBOT Production VPS + Security Architecture

This document explains how ASIFBOT should move from demo mode to a real application that securely controls MT4/MT5 bots running on a VPS.

## 1. Production Structure

The phone must never connect directly to MetaTrader. The safe structure is:

```text
Android App
   |
   | HTTPS + JWT token
   v
ASIFBOT Backend API
   |
   | command queue + telemetry API
   v
VPS Bridge / EA Connector
   |
   v
MT4/MT5 Expert Advisor
```

### Android App

- Handles login, signup, account list, subscription screen, dashboard, ON/OFF buttons, open trade display, and delete-account action.
- Sends only user commands to the backend.
- Stores only the login token and basic UI session locally.
- Does not store broker passwords.

### Backend API

- Owns all real data.
- Authenticates users.
- Validates Google Play subscription and 3-day trial.
- Stores each user's linked MT4/MT5 accounts.
- Stores command queue records like `TURN_ON`, `TURN_OFF_CLOSE_TRADES`, `EMERGENCY_CLOSE`.
- Receives live account/trade telemetry from the VPS bridge.
- Enforces that user A can never see or control user B's accounts.

### VPS Bridge / EA Connector

- Runs on the same VPS where MetaTrader is running.
- Polls backend every 1-3 seconds or uses WebSocket.
- Downloads only commands for its assigned trading account.
- Sends account status, equity, balance, margin, open trades, and command results back to backend.
- Closes only ASIFBOT-managed trades using symbol + magic number + comment filtering.

## 2. User and Account Behavior

### Multiple MT4/MT5 Accounts Per User

One ASIFBOT login can own multiple trading accounts:

- Exness Gold Cent Account 1
- Exness Gold Cent Account 2
- Demo test account
- Real account

Each linked account has its own:

- platform: MT4 or MT5
- broker server
- account number
- symbol
- magic number
- bot status
- bridge connection status

### Delete Account

When the user taps `Delete Account`:

1. App asks for confirmation.
2. Backend verifies the account belongs to the logged-in user.
3. Backend deletes the linked ASIFBOT account record.
4. Backend invalidates any bridge token for that account.
5. It does not delete the real broker account.

Recommended safety rule: ask the user to turn OFF the bot before delete if the account is still connected.

### New User Login Isolation

Every API query must filter by `user_id`.

Correct behavior:

- User A logs in: only User A accounts show.
- User B logs in: only User B accounts show.
- If the app switches user, selected account ID must be cleared.
- Old cached dashboard must not be reused for a new email.

## 3. ON/OFF Command Behavior

### Turn ON

The app sends:

```json
{
  "command": "TURN_ON",
  "accountId": "acc_123"
}
```

Backend stores a pending command. The VPS bridge reads it, enables EA trading for that account, then reports completion.

### Turn OFF

The requested ASIFBOT behavior is:

- Stop opening new EA trades.
- Delete ASIFBOT pending orders.
- Close ASIFBOT open trades.
- Show the final result in the app.

The app sends:

```json
{
  "command": "TURN_OFF_CLOSE_TRADES",
  "accountId": "acc_123",
  "scope": "asifbot_only",
  "closeOpenTrades": true,
  "deletePendingOrders": true
}
```

The bridge must close only matching ASIFBOT trades:

- same trading account
- same symbol
- same magic number
- same EA comment prefix if available

It must not close manual trades or trades from another EA.

### Emergency Close

Emergency close should use the same filtering, but it should retry more aggressively and report failed tickets if any broker close attempt fails.

## 4. Subscription and Trial Rules

Access is checked on login, signup, and every protected backend command.

Rules:

- New signup receives a 3-day trial.
- Trial start and trial end are stored on backend.
- Google Play subscription must be verified by backend using the Google Play Developer API.
- The Android app must not decide subscription access by itself.
- If trial/subscription is expired, app can show dashboard read-only, but ON/OFF commands should be blocked.

Recommended backend fields:

```text
users.trial_started_at
users.trial_ends_at
subscriptions.provider = google_play
subscriptions.product_id
subscriptions.purchase_token_hash
subscriptions.status
subscriptions.expires_at
```

## 5. Data Model

Recommended database tables:

```text
users
  id
  email
  password_hash
  trial_started_at
  trial_ends_at
  created_at
  updated_at

subscriptions
  id
  user_id
  provider
  product_id
  purchase_token_hash
  status
  expires_at
  created_at
  updated_at

trading_accounts
  id
  user_id
  platform
  broker
  account_number
  label
  symbol
  magic_number
  bridge_token_hash
  deleted_at
  created_at
  updated_at

bot_commands
  id
  user_id
  trading_account_id
  command_type
  payload_json
  status
  result_json
  created_at
  completed_at

trade_snapshots
  id
  trading_account_id
  balance
  equity
  margin_level
  floating_profit
  open_trade_count
  open_trades_json
  captured_at

audit_logs
  id
  user_id
  trading_account_id
  action
  ip_address
  user_agent
  details_json
  created_at
```

## 6. Security Rules

### Authentication

- Use HTTPS only.
- Use JWT access tokens with short expiry.
- Use refresh tokens stored securely.
- Hash passwords with Argon2id or bcrypt.
- Never log plaintext passwords or tokens.

### Authorization

Every backend query must include ownership checks:

```text
WHERE trading_accounts.user_id = current_user.id
```

Never trust an account ID sent from the app without checking ownership.

### App Storage

Android should store:

- JWT/session token
- email
- access status
- selected account ID

Android should not store:

- broker account password
- VPS password
- Google purchase token in plaintext longer than required
- bridge secret tokens

### Broker/VPS Secrets

- Broker passwords should remain on the VPS/MetaTrader setup only.
- The backend can store a bridge token hash, not the raw token.
- If a user deletes an account, rotate/invalidate its bridge token.

### Trading Safety

- OFF and emergency close must filter by magic number and symbol.
- Commands must be idempotent using a command ID, so repeated network retries do not duplicate dangerous actions.
- Save full audit logs for ON, OFF, emergency close, delete account, and subscription changes.

## 7. Real Deployment Steps

1. Keep Android `DEMO_MODE=true` for phone UI testing.
2. Build backend API using Node.js, Laravel, Django, or Spring Boot.
3. Add PostgreSQL database.
4. Add Google Play Developer API verification.
5. Build the VPS bridge/EA connector.
6. Set Android `DEMO_MODE=false`.
7. Set `API_BASE_URL` to the real backend domain.
8. Test with one demo MT4/MT5 account.
9. Test OFF closes only ASIFBOT trades.
10. Add release signing for Play Store AAB.

