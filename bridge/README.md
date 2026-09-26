# ASIFBOT MetaTrader Bridge

These bridge EAs connect the Android/backend system to MetaTrader:

- `ASIFBOT_Bridge_MT4.mq4`
- `ASIFBOT_Bridge_MT5.mq5`

They do not open trades. Their job is:

1. Send account metrics and open ASIFBOT trades to the backend.
2. Receive app commands from the backend.
3. Turn the shared ASIFBOT control variable ON/OFF.
4. Delete ASIFBOT pending orders on OFF/emergency.
5. Close ASIFBOT open trades on OFF/emergency.

## Required Inputs

Use the ASIFBOT Android app in real mode:

1. Login.
2. Add MT4/MT5 account.
3. Save the shown `Bridge Account ID`.
4. Save the shown `Bridge Token`.

Then attach the bridge EA on the VPS and set:

```text
BackendUrl    = https://your-backend-domain.com
AccountId     = BRG-6M8Q2A
BridgeToken   = BOT-3F7K-9D2M-Q8LP
ManagedMagic  = the magic number used by your trading bot
ManagedSymbol = XAUUSDc or leave empty for chart symbol
```

## MetaTrader WebRequest Setup

In MT4/MT5:

1. Open `Tools`.
2. Open `Options`.
3. Open `Expert Advisors`.
4. Enable `Allow WebRequest for listed URL`.
5. Add your backend URL, for example:

```text
https://api.yourdomain.com
```

If testing locally:

```text
http://127.0.0.1:8080
```

## Important Control Variable

The bridge sets a MetaTrader Global Variable:

```text
ASIFBOT_ENABLED_<accountNumber>_<magicNumber>
```

Example:

```text
ASIFBOT_ENABLED_12345678_7777
```

Value:

- `1` = app says bot is ON.
- `0` = app says bot is OFF.

For complete control, your trading EA should check this global variable before opening new entries:

```mql4
bool AsifBotEnabled()
{
   string key = "ASIFBOT_ENABLED_" + IntegerToString(AccountNumber()) + "_" + IntegerToString(Magic_Number);
   if(!GlobalVariableCheck(key)) return(false);
   return(GlobalVariableGet(key) > 0.5);
}
```

Then before opening any new trade:

```mql4
if(!AsifBotEnabled()) return;
```

MT5 version:

```mql5
bool AsifBotEnabled()
{
   string key = "ASIFBOT_ENABLED_" + IntegerToString((int)AccountInfoInteger(ACCOUNT_LOGIN)) + "_" + IntegerToString(Magic);
   if(!GlobalVariableCheck(key)) return(false);
   return(GlobalVariableGet(key) > 0.5);
}
```

If your trading EA does not check this variable, the bridge can still close current trades, but the trading EA may open new trades again.

## Trade Safety

The bridge closes/deletes only orders matching:

- `ManagedMagic`
- `ManagedSymbol` or current chart symbol
- optional comment prefix if `RequireCommentPrefix=true`

It does not close manual trades unless they use the same magic number and symbol.
