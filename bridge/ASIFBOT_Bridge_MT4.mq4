//+------------------------------------------------------------------+
//| ASIFBOT_Bridge_MT4.mq4                                           |
//| Connects MT4/VPS to ASIFBOT backend.                             |
//| This EA does not open trades. It reports status, receives app     |
//| commands, and closes/deletes only matching ASIFBOT trades.        |
//+------------------------------------------------------------------+
#property strict
#property version "1.00"

input string BackendUrl             = "https://api.asifbot.com";
input string AccountId              = "";
input string BridgeToken            = "";
input int    ManagedMagic           = 7777;
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

   EventSetTimer(ActualPollSeconds());
   Log("ASIFBOT MT4 bridge started. Control key: " + ControlKey());
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
      closedTrades = CloseManagedMarketOrders();
      success = (CountManagedMarketOrders() == 0 && CountManagedPendingOrders() == 0);
      message = success ? "ASIFBOT disabled and trades closed" : "Some ASIFBOT trades/orders remain open";
     }
   else if(commandType == "EMERGENCY_CLOSE")
     {
      SetBotEnabled(false);
      deletedPending = DeleteManagedPendingOrders();
      closedTrades = CloseManagedMarketOrders();
      success = (CountManagedMarketOrders() == 0 && CountManagedPendingOrders() == 0);
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

int CloseManagedMarketOrders()
  {
   int closed = 0;
   for(int attempt = 0; attempt < CloseRetryAttempts; attempt++)
     {
      bool anyLeft = false;
      for(int i = OrdersTotal() - 1; i >= 0; i--)
        {
         if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
         if(!IsManagedOrder()) continue;
         if(OrderType() != OP_BUY && OrderType() != OP_SELL) continue;

         anyLeft = true;
         RefreshRates();
         double price = (OrderType() == OP_BUY ? Bid : Ask);
         ResetLastError();
         if(OrderClose(OrderTicket(), OrderLots(), NormalizeDouble(price, Digits), SlippagePoints, clrNONE))
           {
            closed++;
            Log("Closed ASIFBOT trade #" + IntegerToString(OrderTicket()));
           }
         else
            Log("Close failed #" + IntegerToString(OrderTicket()) + " error=" + IntegerToString(GetLastError()));
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
      if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
      if(!IsManagedOrder()) continue;
      if(OrderType() == OP_BUY || OrderType() == OP_SELL) continue;

      ResetLastError();
      if(OrderDelete(OrderTicket()))
        {
         deleted++;
         Log("Deleted ASIFBOT pending #" + IntegerToString(OrderTicket()));
        }
      else
         Log("Delete pending failed #" + IntegerToString(OrderTicket()) + " error=" + IntegerToString(GetLastError()));
     }
   return(deleted);
  }

int CountManagedMarketOrders()
  {
   int count = 0;
   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
      if(IsManagedOrder() && (OrderType() == OP_BUY || OrderType() == OP_SELL)) count++;
     }
   return(count);
  }

int CountManagedPendingOrders()
  {
   int count = 0;
   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
      if(IsManagedOrder() && OrderType() != OP_BUY && OrderType() != OP_SELL) count++;
     }
   return(count);
  }

bool IsManagedOrder()
  {
   if(OrderMagicNumber() != ManagedMagic) return(false);
   if(OrderSymbol() != SymbolFilter()) return(false);
   if(RequireCommentPrefix && StringFind(OrderComment(), CommentPrefix, 0) != 0) return(false);
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

   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
      if(!IsManagedOrder()) continue;
      if(OrderType() != OP_BUY && OrderType() != OP_SELL) continue;

      double profit = OrderProfit() + OrderSwap() + OrderCommission();
      floatingProfit += profit;
      totalLots += OrderLots();
      if(openCount == 0 || profit < worstLoss) worstLoss = profit;
      openCount++;
     }

   double margin = AccountMargin();
   double marginLevel = margin > 0.0 ? AccountEquity() / margin * 100.0 : 0.0;

   string json = "{";
   json += "\"balance\":" + DoubleToString(AccountBalance(), 2) + ",";
   json += "\"equity\":" + DoubleToString(AccountEquity(), 2) + ",";
   json += "\"marginLevel\":" + DoubleToString(marginLevel, 2) + ",";
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

   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
      if(!IsManagedOrder()) continue;
      if(OrderType() != OP_BUY && OrderType() != OP_SELL) continue;
      if(added >= 100) break;

      RefreshRates();
      string side = OrderType() == OP_BUY ? "BUY" : "SELL";
      double currentPrice = OrderType() == OP_BUY ? Bid : Ask;
      double profit = OrderProfit() + OrderSwap() + OrderCommission();

      if(added > 0) json += ",";
      json += "{";
      json += "\"ticket\":" + IntegerToString(OrderTicket()) + ",";
      json += "\"symbol\":\"" + JsonEscape(OrderSymbol()) + "\",";
      json += "\"side\":\"" + side + "\",";
      json += "\"lots\":" + DoubleToString(OrderLots(), 2) + ",";
      json += "\"openPrice\":" + DoubleToString(OrderOpenPrice(), Digits) + ",";
      json += "\"currentPrice\":" + DoubleToString(currentPrice, Digits) + ",";
      json += "\"profit\":" + DoubleToString(profit, 2) + ",";
      json += "\"openTime\":\"" + JsonEscape(TimeToString(OrderOpenTime(), TIME_DATE | TIME_MINUTES)) + "\"";
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
      Log("WebRequest failed. error=" + IntegerToString(error) + ". Add backend URL to MT4: Tools > Options > Expert Advisors > Allow WebRequest.");
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
   return(ManagedSymbol == "" ? Symbol() : ManagedSymbol);
  }

string ControlKey()
  {
   return("ASIFBOT_ENABLED_" + IntegerToString(AccountNumber()) + "_" + IntegerToString(ManagedMagic));
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
