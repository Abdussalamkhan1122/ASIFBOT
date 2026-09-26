# Software Requirements Specification

## Project Name

ASIFBOT Mobile Trading Bot Control Application

## Version

1.0 Draft

## Purpose

ASIFBOT is an Android mobile application that allows registered users to control their MT4/MT5 trading bot accounts from a phone. The application must provide secure login, subscription/trial access, MT4 account management, live bot status, open-trade monitoring, floating profit/loss display, and ON/OFF control.

The OFF command must close active bot trades, cancel bot pending orders, and stop the bot from opening new trades.

## Scope

The Android app does not run the MT4/MT5 Expert Advisor directly. It communicates with a backend server. The backend communicates with the VPS/EA bridge or the EA itself. The EA is responsible for executing trade commands inside MT4/MT5.

System structure:

```text
ASIFBOT Android App
        |
        v
ASIFBOT Backend Server
        |
        v
VPS / EA Bridge
        |
        v
MT4 / MT5 Expert Advisor
```

## User Roles

### Normal User

A normal user can:

- Register and login with email/password.
- Use a 3-day free trial.
- Subscribe through Google Play.
- Add and manage MT4/MT5 accounts.
- View bot status.
- View open trades and floating loss/profit.
- Turn bot ON.
- Turn bot OFF and close open bot trades.

### Admin

An admin can:

- View all users.
- Enable/disable user access.
- Check subscription/trial status.
- View connected trading accounts.
- Disable a bot remotely if abuse or risk is detected.

## Core Functional Requirements

### FR-01 User Registration

The app must allow a new user to create an account using:

- Email
- Password

After registration, the user receives a 3-day free trial.

### FR-02 User Login

The app must allow existing users to login using:

- Email
- Password

The app must store the session securely and keep the user logged in until logout.

### FR-03 Subscription

The app must support Google Play subscription billing.

Requirements:

- 3-day free trial.
- Monthly subscription product.
- Backend must verify Google Play purchase token.
- User access must be blocked after trial/subscription expiry.
- Login/signup screen must clearly show trial and subscription access.

### FR-04 Add Trading Account

The user must be able to add MT4/MT5 accounts inside the app.

Minimum fields:

- Account label, for example `Exness Gold Account`
- Platform: `MT4` or `MT5`
- Broker/server name
- Trading account number
- Symbol group, for example `XAUUSDc`
- EA Magic Number

The app should not store the MT4/MT5 trading password unless absolutely required. The better professional design is that the EA/VPS bridge connects to the backend using a secure account token.

### FR-05 Account List

The app must show all linked trading accounts.

Accounts must be isolated per logged-in user. If a different email logs in, the previous user's accounts and selected dashboard must not appear.

Each account card must show:

- Account label
- Platform: MT4/MT5
- Broker/server
- Connection status: Online / Offline
- Bot status: ON / OFF / Closing / Error
- Balance
- Equity
- Delete account action

Delete removes only the linked ASIFBOT account record. It must not delete the real broker account.
- Floating profit/loss
- Open trade count

### FR-06 Bot ON Behavior

When the user taps ON:

```text
App sends ON command to backend.
Backend sets botEnabled = true.
EA/VPS bridge receives ON state.
EA is allowed to open new trades and pending orders.
App shows status = ON.
```

The ON command must not duplicate existing trades. It only allows the EA to continue trading according to its strategy.

### FR-07 Bot OFF Behavior

When the user taps OFF, the app must show a confirmation dialog:

```text
Turn bot OFF?
This will close all open ASIFBOT trades and delete pending orders for this account.
```

If the user confirms:

```text
App sends OFF command with closeOpenTrades=true.
Backend marks account state as CLOSING.
EA/VPS bridge receives the close command.
EA closes all open bot trades for the selected account and magic number.
EA deletes all bot pending orders.
EA stops opening new trades.
EA reports final result to backend.
App updates status to OFF.
```

OFF must only close trades managed by ASIFBOT, filtered by:

- Account
- Symbol
- Magic Number
- EA identifier

The app must never close unrelated manual trades unless the user explicitly enables that advanced option.

### FR-08 Open Trades Display

The app must show open trades for each linked account.

Each trade row must show:

- Ticket number
- Symbol
- Buy/Sell
- Lot size
- Open price
- Current price
- Floating profit/loss
- Swap/commission if available
- Open time

The screen must clearly show:

```text
Total Floating Profit/Loss
Total Open Trades
Worst Trade Loss
Total Lots
```

### FR-09 Live Loss Display

The dashboard must show floating loss/profit in real time or near real time.

Recommended refresh:

- Every 3-5 seconds while app is open.
- Manual refresh button.
- Push notification for large drawdown.

### FR-10 Command Status

Every ON/OFF command must have a command ID and visible status:

- Pending
- Sent to EA
- Executing
- Completed
- Failed

If OFF fails to close any trade, the app must show the failed tickets and reason.

### FR-11 Emergency Close

The app should include a separate button:

```text
Emergency Close All
```

This must be separate from the normal ON/OFF switch.

Emergency Close behavior:

- Close all ASIFBOT open trades.
- Delete all ASIFBOT pending orders.
- Keep bot OFF.
- Show final realized result.

## User Interface Requirements

### UI-01 Mobile Responsive Design

The app must be designed for mobile screens first.

Requirements:

- Must fit small Android screens.
- Must not be blocked by keyboard.
- Must use scrollable pages.
- Buttons must remain tappable.
- Text must not overflow.
- Login page must look professional on phones.
- Dashboard must show key information without needing horizontal scroll.

### UI-02 Login Screen

Login screen must contain:

- ASIFBOT logo/name
- Email input
- Password input
- Login button
- Create account button
- Forgot password link
- Trial/subscription note

### UI-03 Dashboard Screen

Dashboard must contain:

- Selected trading account
- Bot ON/OFF switch
- Floating P/L
- Balance
- Equity
- Open trades count
- Connection status
- Last update time

### UI-04 Account Management Screen

Account screen must contain:

- Add account button
- Account list
- Account details
- Token/connection status for EA/VPS bridge

### UI-05 Trades Screen

Trades screen must contain:

- Open trades list
- Total floating loss/profit
- Close bot trades action
- Refresh button

## Backend API Requirements

### Auth APIs

```text
POST /auth/register
POST /auth/login
GET  /me
POST /auth/logout
```

### Subscription APIs

```text
POST /billing/google/verify
GET  /billing/status
```

### Trading Account APIs

```text
GET    /accounts
POST   /accounts
GET    /accounts/{accountId}
PUT    /accounts/{accountId}
DELETE /accounts/{accountId}
```

### Bot Control APIs

```text
GET  /accounts/{accountId}/bot/status
POST /accounts/{accountId}/bot/on
POST /accounts/{accountId}/bot/off
POST /accounts/{accountId}/bot/emergency-close
```

OFF request body:

```json
{
  "closeOpenTrades": true,
  "deletePendingOrders": true,
  "scope": "asifbot_only"
}
```

### Trade Data APIs

```text
GET /accounts/{accountId}/trades/open
GET /accounts/{accountId}/trades/history
GET /accounts/{accountId}/metrics
```

## EA / VPS Bridge Requirements

The EA or bridge must:

- Authenticate with backend using account token.
- Poll backend or maintain websocket connection.
- Report account balance/equity/margin.
- Report open ASIFBOT trades.
- Report floating profit/loss.
- Receive ON/OFF commands.
- Close ASIFBOT trades when OFF command is received.
- Delete ASIFBOT pending orders when OFF command is received.
- Send command result back to backend.

Recommended polling interval:

```text
1-3 seconds
```

## Safety Requirements

### SR-01 Trade Scope Safety

The OFF command must close only bot-managed trades by default.

Filter required:

- Magic Number
- Symbol
- Account ID
- EA name or comment if available

### SR-02 Confirmation For Closing Trades

Because OFF closes open trades, the app must ask for confirmation before sending OFF.

### SR-03 Error Handling

If a trade close fails, the app must show:

- Ticket number
- Reason
- Time
- Retry option

### SR-04 No False Status

The app must not show OFF until the EA confirms:

- Trades closed
- Pending orders deleted
- New entries disabled

Until then, status should be:

```text
CLOSING
```

## Non-Functional Requirements

### Performance

- Login response should complete within 3 seconds under normal network.
- Dashboard refresh should complete within 5 seconds.
- ON/OFF commands should reach the backend immediately.
- EA should execute OFF command within 1-3 seconds after receiving it.

### Security

- All backend traffic must use HTTPS.
- Passwords must be hashed on backend.
- App must not store raw trading account password.
- Google Play purchase token must be verified on backend, not only in the app.
- User can only access their own accounts.

### Reliability

- If phone internet drops, bot continues according to last backend state.
- If backend is offline, app must show connection error.
- If EA is offline, app must show EA disconnected and block ON/OFF execution until reconnected.

### Compatibility

- Android minimum SDK: 23.
- Designed for Android phones first.
- Must support common screen sizes from small 720p phones to large displays.

## App Screens

### Screen 1: Login

Purpose:

Allow user to sign in or create a new trial account.

This screen must show that new users receive a 3-day trial and that continued bot control requires an active subscription.

### Screen 2: Account List

Purpose:

Show all linked MT4/MT5 accounts.

This screen must allow the user to delete a linked account after confirmation.

### Screen 3: Add Account

Purpose:

Let user add an MT4/MT5 account identity and connect EA/VPS bridge.

### Screen 4: Dashboard

Purpose:

Show selected account status and main ON/OFF control.

### Screen 5: Open Trades

Purpose:

Show active trades, floating loss/profit, and trade details.

### Screen 6: Subscription

Purpose:

Show trial/subscription status and start Google Play subscription.

## Status Flow

```text
OFF
 |
 | user taps ON
 v
STARTING
 |
 | EA confirms enabled
 v
ON
 |
 | user taps OFF and confirms
 v
CLOSING
 |
 | EA closes trades and deletes pending orders
 v
OFF
```

If close fails:

```text
CLOSING -> ERROR
```

## Acceptance Criteria

The project is acceptable when:

- User can register/login.
- Trial starts for 3 days.
- Subscription unlocks access.
- User can add MT4/MT5 account.
- User can delete a linked account.
- New login email cannot see the previous user's accounts.
- Dashboard shows bot/account status.
- Open trades and floating loss are visible.
- ON enables bot trading.
- OFF closes ASIFBOT open trades, deletes ASIFBOT pending orders, and disables new trades.
- App UI fits mobile screens cleanly.
- Backend/EA bridge confirms every command.
- App can be built into Play Store compatible AAB.

## Important Production Note

The current APK demo mode is for UI testing only. Real ON/OFF trade control requires backend and EA/VPS bridge implementation.
