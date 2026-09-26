//+------------------------------------------------------------------+
//| ASIFBOT_Bridge_MT5.mq5                                           |
//| Connects MT5/VPS to ASIFBOT backend.                             |
//| This EA does not open trades. It reports status, receives app     |
//| commands, and closes/deletes only matching ASIFBOT trades.        |
//+------------------------------------------------------------------+
#property strict
#property version "1.00"

#include <Trade\Trade.mqh>

CTrade trade;

input string BackendUrl             = "https://api.yourdomain.com";
input string AccountId              = "";
input string BridgeToken            = "";
input long   ManagedMagic           = 7777;
input string ManagedSymbol          = "";
input bool   RequireCommentPrefix   = false;
input string CommentPrefix          = "ASIFBOT";
input bool   StartEnabled           = false;
input int    PollSeconds            = 3;
input int    HttpTimeoutMs          = 7000;
input int    SlippagePoints         = 30;
input int    CloseRetryAttempts     = 3;
input bool   VerboseLogging         = true;

datetime g_lastPoll = 0;

int OnInit()
  {
   if(!GlobalVariableCheck(ControlKey()))
      GlobalVariableSet(ControlKey(), StartEnabled ? 1.0 : 0.0);

   trade.SetExpertMagicNumber((ulong)ManagedMagic);
   trade.SetDeviationInPoints(SlippagePoints);
   EventSetTimer(ActualPollSeconds());
   Log("ASIFBOT MT5 bridge started. Control key: " + ControlKey());
   if(AccountId == "" || BridgeToken == "")
      Log("Set AccountId and BridgeToken from ASIFBOT app before live use.");
   return(INIT_SUCCEEDED);
  }

void OnDeinit(const int reason)
  {
   EventKillTimer();
  }

void OnTimer()
  {
   PollBackend();
  }

void OnTick()
  {
   if(TimeCurrent() - g_lastPoll >= ActualPollSeconds())
      PollBackend();
  }

void PollBackend()
  {
   if(AccountId == "" || BridgeToken == "")
      return;

   g_lastPoll = TimeCurrent();

   string payload = BuildHeartbeatPayload();
   string response = "";
   int status = 0;
   if(!HttpPost("/bridge/heartbeat", payload, response, status))
     {
      Log("Heartbeat failed. HTTP status: " + IntegerToString(status));
      return;
     }

   string ids[];
   string types[];
   int total = ExtractCommands(response, ids, types);
   for(int i = 0; i < total; i++)
      ExecuteCommand(ids[i], types[i]);
  }

void ExecuteCommand(string commandId, string commandType)
  {
   int closedTrades = 0;
   int deletedPending = 0;
   bool success = true;
   string message = "Command completed";

   Log("Command received: " + commandType + " / " + commandId);

   if(commandType == "TURN_ON")
     {
      SetBotEnabled(true);
      message = "ASIFBOT enabled";
     }
   else if(commandType == "TURN_OFF_CLOSE_TRADES")
     {
      SetBotEnabled(false);
      deletedPending = DeleteManagedPendingOrders();
      closedTrades = CloseManagedPositions();
      success = (CountManagedPositions() == 0 && CountManagedPendingOrders() == 0);
      message = success ? "ASIFBOT disabled and trades closed" : "Some ASIFBOT trades/orders remain open";
     }
   else if(commandType == "EMERGENCY_CLOSE")
     {
      SetBotEnabled(false);
      deletedPending = DeleteManagedPendingOrders();
      closedTrades = CloseManagedPositions();
      success = (CountManagedPositions() == 0 && CountManagedPendingOrders() == 0);
      message = success ? "Emergency close completed" : "Emergency close incomplete";
     }
   else
     {
      success = false;
      message = "Unknown command type";
     }

   SendCommandComplete(commandId, success, message, closedTrades, deletedPending);
  }

void SendCommandComplete(string commandId, bool success, string message, int closedTrades, int deletedPending)
  {
   string payload = "{";
   payload += "\"accountId\":\"" + JsonEscape(AccountId) + "\",";
   payload += "\"success\":" + (success ? "true" : "false") + ",";
   payload += "\"message\":\"" + JsonEscape(message) + "\",";
   payload += "\"closedTrades\":" + IntegerToString(closedTrades) + ",";
   payload += "\"deletedPendingOrders\":" + IntegerToString(deletedPending) + ",";
   payload += "\"metrics\":" + BuildMetricsJson() + ",";
   payload += "\"trades\":" + BuildTradesJson();
   payload += "}";

   string response = "";
   int status = 0;
   if(!HttpPost("/bridge/commands/" + commandId + "/complete", payload, response, status))
      Log("Command complete failed. HTTP status: " + IntegerToString(status));
  }

int CloseManagedPositions()
  {
   int closed = 0;
   for(int attempt = 0; attempt < CloseRetryAttempts; attempt++)
     {
      bool anyLeft = false;
      for(int i = PositionsTotal() - 1; i >= 0; i--)
        {
         ulong ticket = PositionGetTicket(i);
         if(ticket == 0 || !PositionSelectByTicket(ticket)) continue;
         if(!IsManagedPosition()) continue;

         anyLeft = true;
         ResetLastError();
         if(trade.PositionClose(ticket))
           {
            closed++;
            Log("Closed ASIFBOT position #" + IntegerToString((long)ticket));
           }
         else
            Log("Close failed #" + IntegerToString((long)ticket) + " retcode=" + IntegerToString((int)trade.ResultRetcode()));
        }
      if(!anyLeft) break;
      Sleep(250);
     }
   return(closed);
  }

int DeleteManagedPendingOrders()
  {
   int deleted = 0;
   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      ulong ticket = OrderGetTicket(i);
      if(ticket == 0 || !OrderSelect(ticket)) continue;
      if(!IsManagedOrder()) continue;

      ResetLastError();
      if(trade.OrderDelete(ticket))
        {
         deleted++;
         Log("Deleted ASIFBOT pending #" + IntegerToString((long)ticket));
        }
      else
         Log("Delete pending failed #" + IntegerToString((long)ticket) + " retcode=" + IntegerToString((int)trade.ResultRetcode()));
     }
   return(deleted);
  }

int CountManagedPositions()
  {
   int count = 0;
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0 || !PositionSelectByTicket(ticket)) continue;
      if(IsManagedPosition()) count++;
     }
   return(count);
  }

int CountManagedPendingOrders()
  {
   int count = 0;
   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      ulong ticket = OrderGetTicket(i);
      if(ticket == 0 || !OrderSelect(ticket)) continue;
      if(IsManagedOrder()) count++;
     }
   return(count);
  }

bool IsManagedPosition()
  {
   if(PositionGetInteger(POSITION_MAGIC) != ManagedMagic) return(false);
   if(PositionGetString(POSITION_SYMBOL) != SymbolFilter()) return(false);
   if(RequireCommentPrefix && StringFind(PositionGetString(POSITION_COMMENT), CommentPrefix, 0) != 0) return(false);
   return(true);
  }

bool IsManagedOrder()
  {
   if(OrderGetInteger(ORDER_MAGIC) != ManagedMagic) return(false);
   if(OrderGetString(ORDER_SYMBOL) != SymbolFilter()) return(false);
   if(RequireCommentPrefix && StringFind(OrderGetString(ORDER_COMMENT), CommentPrefix, 0) != 0) return(false);
   return(true);
  }

string BuildHeartbeatPayload()
  {
   string payload = "{";
   payload += "\"accountId\":\"" + JsonEscape(AccountId) + "\",";
   payload += "\"botStatus\":\"" + (BotEnabled() ? "ON" : "OFF") + "\",";
   payload += "\"metrics\":" + BuildMetricsJson() + ",";
   payload += "\"trades\":" + BuildTradesJson();
   payload += "}";
   return(payload);
  }

string BuildMetricsJson()
  {
   double floatingProfit = 0.0;
   double totalLots = 0.0;
   double worstLoss = 0.0;
   int openCount = 0;

   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0 || !PositionSelectByTicket(ticket)) continue;
      if(!IsManagedPosition()) continue;

      double profit = PositionGetDouble(POSITION_PROFIT) + PositionGetDouble(POSITION_SWAP);
      floatingProfit += profit;
      totalLots += PositionGetDouble(POSITION_VOLUME);
      if(openCount == 0 || profit < worstLoss) worstLoss = profit;
      openCount++;
     }

   string json = "{";
   json += "\"balance\":" + DoubleToString(AccountInfoDouble(ACCOUNT_BALANCE), 2) + ",";
   json += "\"equity\":" + DoubleToString(AccountInfoDouble(ACCOUNT_EQUITY), 2) + ",";
   json += "\"marginLevel\":" + DoubleToString(AccountInfoDouble(ACCOUNT_MARGIN_LEVEL), 2) + ",";
   json += "\"floatingProfit\":" + DoubleToString(floatingProfit, 2) + ",";
   json += "\"openTradeCount\":" + IntegerToString(openCount) + ",";
   json += "\"totalLots\":" + DoubleToString(totalLots, 2) + ",";
   json += "\"worstTradeLoss\":" + DoubleToString(worstLoss, 2);
   json += "}";
   return(json);
  }

string BuildTradesJson()
  {
   string json = "[";
   int added = 0;

   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0 || !PositionSelectByTicket(ticket)) continue;
      if(!IsManagedPosition()) continue;
      if(added >= 100) break;

      string symbol = PositionGetString(POSITION_SYMBOL);
      long type = PositionGetInteger(POSITION_TYPE);
      string side = type == POSITION_TYPE_BUY ? "BUY" : "SELL";
      double currentPrice = type == POSITION_TYPE_BUY ? SymbolInfoDouble(symbol, SYMBOL_BID) : SymbolInfoDouble(symbol, SYMBOL_ASK);
      double profit = PositionGetDouble(POSITION_PROFIT) + PositionGetDouble(POSITION_SWAP);
      datetime openTime = (datetime)PositionGetInteger(POSITION_TIME);
      int digits = (int)SymbolInfoInteger(symbol, SYMBOL_DIGITS);

      if(added > 0) json += ",";
      json += "{";
      json += "\"ticket\":" + IntegerToString((long)ticket) + ",";
      json += "\"symbol\":\"" + JsonEscape(symbol) + "\",";
      json += "\"side\":\"" + side + "\",";
      json += "\"lots\":" + DoubleToString(PositionGetDouble(POSITION_VOLUME), 2) + ",";
      json += "\"openPrice\":" + DoubleToString(PositionGetDouble(POSITION_PRICE_OPEN), digits) + ",";
      json += "\"currentPrice\":" + DoubleToString(currentPrice, digits) + ",";
      json += "\"profit\":" + DoubleToString(profit, 2) + ",";
      json += "\"openTime\":\"" + JsonEscape(TimeToString(openTime, TIME_DATE | TIME_MINUTES)) + "\"";
      json += "}";
      added++;
     }

   json += "]";
   return(json);
  }

bool HttpPost(string endpoint, string payload, string &response, int &statusCode)
  {
   string url = BuildUrl(endpoint);
   string headers = "Content-Type: application/json\r\nAuthorization: Bridge " + BridgeToken + "\r\n";
   char data[];
   int len = StringToCharArray(payload, data, 0, WHOLE_ARRAY, CP_UTF8);
   if(len > 0) ArrayResize(data, len - 1);

   char result[];
   string resultHeaders = "";
   ResetLastError();
   statusCode = WebRequest("POST", url, headers, HttpTimeoutMs, data, result, resultHeaders);
   if(statusCode == -1)
     {
      int error = GetLastError();
      Log("WebRequest failed. error=" + IntegerToString(error) + ". Add backend URL to MT5: Tools > Options > Expert Advisors > Allow WebRequest.");
      response = "";
      return(false);
     }

   response = CharArrayToString(result, 0, -1, CP_UTF8);
   if(statusCode < 200 || statusCode >= 300)
     {
      Log("HTTP " + IntegerToString(statusCode) + ": " + response);
      return(false);
     }
   return(true);
  }

int ExtractCommands(string json, string &ids[], string &types[])
  {
   ArrayResize(ids, 0);
   ArrayResize(types, 0);

   int pos = StringFind(json, "\"commands\"");
   if(pos < 0) return(0);

   int count = 0;
   while(true)
     {
      string id = JsonStringValue(json, "id", pos);
      if(id == "") break;
      int idPos = StringFind(json, "\"id\"", pos);
      string type = JsonStringValue(json, "type", idPos);
      if(type == "") break;

      ArrayResize(ids, count + 1);
      ArrayResize(types, count + 1);
      ids[count] = id;
      types[count] = type;
      count++;

      pos = StringFind(json, "\"id\"", idPos + 4);
      if(pos < 0) break;
      if(count >= 10) break;
     }

   return(count);
  }

string JsonStringValue(string json, string key, int start)
  {
   int keyPos = StringFind(json, "\"" + key + "\"", start);
   if(keyPos < 0) return("");
   int colon = StringFind(json, ":", keyPos);
   if(colon < 0) return("");
   int firstQuote = StringFind(json, "\"", colon + 1);
   if(firstQuote < 0) return("");
   int secondQuote = firstQuote + 1;
   while(secondQuote < StringLen(json))
     {
      if(StringSubstr(json, secondQuote, 1) == "\"" && StringSubstr(json, secondQuote - 1, 1) != "\\")
         break;
      secondQuote++;
     }
   if(secondQuote >= StringLen(json)) return("");
   return(StringSubstr(json, firstQuote + 1, secondQuote - firstQuote - 1));
  }

string BuildUrl(string endpoint)
  {
   string base = BackendUrl;
   while(StringLen(base) > 0 && StringSubstr(base, StringLen(base) - 1, 1) == "/")
      base = StringSubstr(base, 0, StringLen(base) - 1);
   return(base + endpoint);
  }

string JsonEscape(string value)
  {
   string out = value;
   StringReplace(out, "\\", "\\\\");
   StringReplace(out, "\"", "\\\"");
   StringReplace(out, "\r", " ");
   StringReplace(out, "\n", " ");
   return(out);
  }

string SymbolFilter()
  {
   return(ManagedSymbol == "" ? _Symbol : ManagedSymbol);
  }

string ControlKey()
  {
   return("ASIFBOT_ENABLED_" + IntegerToString((int)AccountInfoInteger(ACCOUNT_LOGIN)) + "_" + IntegerToString((int)ManagedMagic));
  }

int ActualPollSeconds()
  {
   return(PollSeconds < 1 ? 1 : PollSeconds);
  }

bool BotEnabled()
  {
   if(!GlobalVariableCheck(ControlKey())) return(false);
   return(GlobalVariableGet(ControlKey()) > 0.5);
  }

void SetBotEnabled(bool enabled)
  {
   GlobalVariableSet(ControlKey(), enabled ? 1.0 : 0.0);
  }

void Log(string message)
  {
   if(VerboseLogging) Print("[ASIFBOT Bridge] " + message);
  }
//+------------------------------------------------------------------+
