package com.asifbot.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApiClient {
    interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    static final class AccountState {
        String token;
        String email;
        boolean subscriptionActive;
        long trialEndsAtMs;
        boolean botEnabled;

        boolean hasAccess() {
            return subscriptionActive || System.currentTimeMillis() < trialEndsAtMs;
        }

        String accessLabel() {
            if (subscriptionActive) {
                return "Subscription active";
            }
            long remainingMs = trialEndsAtMs - System.currentTimeMillis();
            if (remainingMs > 0) {
                long days = Math.max(1, (long) Math.ceil(remainingMs / 86400000.0));
                return "Trial active: " + days + " day(s) left";
            }
            return "Subscription required";
        }
    }

    static final class TradingAccount {
        String id;
        String label;
        String platform;
        String broker;
        String accountNumber;
        String symbol;
        int magicNumber;
        boolean connected;
        String botStatus;
        String bridgeToken;
    }

    static final class Metrics {
        double balance;
        double equity;
        double marginLevel;
        double floatingProfit;
        int openTradeCount;
        double totalLots;
        double worstTradeLoss;
        String commandStatus;
        String lastUpdate;
    }

    static final class TradePosition {
        long ticket;
        String symbol;
        String side;
        double lots;
        double openPrice;
        double currentPrice;
        double profit;
        String openTime;
    }

    static final class DashboardState {
        TradingAccount account;
        Metrics metrics;
        ArrayList<TradePosition> trades = new ArrayList<>();
    }

    private interface JsonHandler {
        void handle(JSONObject json) throws JSONException;
    }

    private final String baseUrl;
    private final boolean demoMode;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, ArrayList<TradingAccount>> demoAccountsByUser = new HashMap<>();
    private final Map<String, Boolean> demoBotState = new HashMap<>();
    private final Map<String, String> demoCommandState = new HashMap<>();

    ApiClient(String baseUrl, boolean demoMode) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.demoMode = demoMode;
    }

    boolean isDemoMode() {
        return demoMode;
    }

    void login(String email, String password, Callback<AccountState> callback) {
        if (demoMode) {
            demoLogin(email, callback);
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        post("/auth/login", null, body, json -> callback.onSuccess(parseAccount(json)), callback);
    }

    void register(String email, String password, Callback<AccountState> callback) {
        if (demoMode) {
            demoLogin(email, callback);
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
            body.put("trialDays", 3);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        post("/auth/register", null, body, json -> callback.onSuccess(parseAccount(json)), callback);
    }

    void loadMe(String token, Callback<AccountState> callback) {
        if (demoMode) {
            callback.onSuccess(demoAccount(emailFromDemoToken(token), false, anyDemoBotEnabled(token)));
            return;
        }
        get("/me", token, json -> callback.onSuccess(parseAccount(json)), callback);
    }

    void verifyPurchase(String token, String productId, String purchaseToken, Callback<AccountState> callback) {
        if (demoMode) {
            callback.onSuccess(demoAccount(emailFromDemoToken(token), true, anyDemoBotEnabled(token)));
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("platform", "google_play");
            body.put("productId", productId);
            body.put("purchaseToken", purchaseToken);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        post("/billing/google/verify", token, body, json -> callback.onSuccess(parseAccount(json)), callback);
    }

    void listAccounts(String token, Callback<ArrayList<TradingAccount>> callback) {
        if (demoMode) {
            ArrayList<TradingAccount> accounts = demoAccountsForToken(token);
            callback.onSuccess(copyAccounts(accounts));
            return;
        }
        get("/accounts", token, json -> callback.onSuccess(parseAccounts(json)), callback);
    }

    void createAccount(String token, TradingAccount account, Callback<TradingAccount> callback) {
        if (demoMode) {
            ArrayList<TradingAccount> accounts = demoAccountsForToken(token);
            account.id = userKey(token) + "-account-" + (accounts.size() + 1);
            account.connected = true;
            account.botStatus = "OFF";
            accounts.add(account);
            demoBotState.put(account.id, false);
            demoCommandState.put(account.id, "Ready");
            callback.onSuccess(copyAccount(account));
            return;
        }
        post("/accounts", token, accountToJson(account), json -> callback.onSuccess(parseTradingAccount(json.optJSONObject("account"))), callback);
    }

    void deleteAccount(String token, String accountId, Callback<ArrayList<TradingAccount>> callback) {
        if (demoMode) {
            ArrayList<TradingAccount> accounts = demoAccountsForToken(token);
            for (int i = accounts.size() - 1; i >= 0; i--) {
                if (accounts.get(i).id.equals(accountId)) {
                    accounts.remove(i);
                }
            }
            demoBotState.remove(accountId);
            demoCommandState.remove(accountId);
            callback.onSuccess(copyAccounts(accounts));
            return;
        }
        delete("/accounts/" + encodePath(accountId), token, json -> callback.onSuccess(parseAccounts(json)), callback);
    }

    void loadDashboard(String token, String accountId, Callback<DashboardState> callback) {
        if (demoMode) {
            callback.onSuccess(demoDashboard(token, accountId));
            return;
        }
        get("/accounts/" + encodePath(accountId) + "/dashboard", token, json -> callback.onSuccess(parseDashboard(json, accountId)), callback);
    }

    void loadOpenTrades(String token, String accountId, Callback<ArrayList<TradePosition>> callback) {
        if (demoMode) {
            callback.onSuccess(demoDashboard(token, accountId).trades);
            return;
        }
        get("/accounts/" + encodePath(accountId) + "/trades/open", token, json -> callback.onSuccess(parseTrades(json.optJSONArray("trades"))), callback);
    }

    void turnBotOn(String token, String accountId, Callback<DashboardState> callback) {
        if (demoMode) {
            demoBotState.put(accountId, true);
            demoCommandState.put(accountId, "ON confirmed by demo bridge");
            callback.onSuccess(demoDashboard(token, accountId));
            return;
        }
        JSONObject body = new JSONObject();
        post("/accounts/" + encodePath(accountId) + "/bot/on", token, body, json -> callback.onSuccess(parseDashboard(json, accountId)), callback);
    }

    void turnBotOffCloseTrades(String token, String accountId, Callback<DashboardState> callback) {
        if (demoMode) {
            demoCommandState.put(accountId, "OFF completed: trades closed and pending orders deleted");
            demoBotState.put(accountId, false);
            callback.onSuccess(demoDashboard(token, accountId));
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("closeOpenTrades", true);
            body.put("deletePendingOrders", true);
            body.put("scope", "asifbot_only");
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        post("/accounts/" + encodePath(accountId) + "/bot/off", token, body, json -> callback.onSuccess(parseDashboard(json, accountId)), callback);
    }

    void emergencyClose(String token, String accountId, Callback<DashboardState> callback) {
        if (demoMode) {
            demoCommandState.put(accountId, "Emergency close completed");
            demoBotState.put(accountId, false);
            callback.onSuccess(demoDashboard(token, accountId));
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("scope", "asifbot_only");
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        post("/accounts/" + encodePath(accountId) + "/bot/emergency-close", token, body, json -> callback.onSuccess(parseDashboard(json, accountId)), callback);
    }

    private void get(String path, String token, JsonHandler handler, Callback<?> callback) {
        io.execute(() -> runRequest("GET", path, token, null, handler, callback));
    }

    private void delete(String path, String token, JsonHandler handler, Callback<?> callback) {
        io.execute(() -> runRequest("DELETE", path, token, null, handler, callback));
    }

    private void post(String path, String token, JSONObject body, JsonHandler handler, Callback<?> callback) {
        io.execute(() -> runRequest("POST", path, token, body, handler, callback));
    }

    private void runRequest(String method, String path, String token, JSONObject body, JsonHandler handler, Callback<?> callback) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }

            int code = connection.getResponseCode();
            String response = readBody(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + response);
            }
            handler.handle(response.isEmpty() ? new JSONObject() : new JSONObject(response));
        } catch (Exception e) {
            callback.onError(e.getMessage() == null ? "Network error" : e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static AccountState parseAccount(JSONObject json) {
        AccountState state = new AccountState();
        state.token = json.optString("token", null);
        state.email = json.optString("email", "");
        state.subscriptionActive = json.optBoolean("subscriptionActive", false);
        state.trialEndsAtMs = json.optLong("trialEndsAtMs", 0L);
        state.botEnabled = json.optBoolean("botEnabled", false);
        return state;
    }

    private static ArrayList<TradingAccount> parseAccounts(JSONObject json) {
        return parseAccountArray(json.optJSONArray("accounts"));
    }

    private static ArrayList<TradingAccount> parseAccountArray(JSONArray array) {
        ArrayList<TradingAccount> accounts = new ArrayList<>();
        if (array == null) {
            return accounts;
        }
        for (int i = 0; i < array.length(); i++) {
            TradingAccount account = parseTradingAccount(array.optJSONObject(i));
            if (account != null) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    private static TradingAccount parseTradingAccount(JSONObject json) {
        if (json == null) {
            return null;
        }
        TradingAccount account = new TradingAccount();
        account.id = json.optString("id", json.optString("accountId", ""));
        account.label = json.optString("label", "Trading Account");
        account.platform = json.optString("platform", "MT4");
        account.broker = json.optString("broker", "");
        account.accountNumber = json.optString("accountNumber", "");
        account.symbol = json.optString("symbol", "XAUUSDc");
        account.magicNumber = json.optInt("magicNumber", 0);
        account.connected = json.optBoolean("connected", false);
        account.botStatus = json.optString("botStatus", "OFF");
        account.bridgeToken = json.optString("bridgeToken", "");
        return account;
    }

    private static Metrics parseMetrics(JSONObject json) {
        Metrics metrics = new Metrics();
        if (json == null) {
            json = new JSONObject();
        }
        metrics.balance = json.optDouble("balance", 0.0);
        metrics.equity = json.optDouble("equity", 0.0);
        metrics.marginLevel = json.optDouble("marginLevel", 0.0);
        metrics.floatingProfit = json.optDouble("floatingProfit", 0.0);
        metrics.openTradeCount = json.optInt("openTradeCount", 0);
        metrics.totalLots = json.optDouble("totalLots", 0.0);
        metrics.worstTradeLoss = json.optDouble("worstTradeLoss", 0.0);
        metrics.commandStatus = json.optString("commandStatus", "Ready");
        metrics.lastUpdate = json.optString("lastUpdate", nowLabel());
        return metrics;
    }

    private static DashboardState parseDashboard(JSONObject json, String fallbackAccountId) {
        DashboardState state = new DashboardState();
        state.account = parseTradingAccount(json.optJSONObject("account"));
        if (state.account == null) {
            state.account = new TradingAccount();
            state.account.id = fallbackAccountId;
            state.account.label = "Trading Account";
            state.account.platform = "MT4";
            state.account.symbol = "XAUUSDc";
            state.account.botStatus = json.optString("botStatus", "OFF");
        }
        state.metrics = parseMetrics(json.optJSONObject("metrics"));
        state.trades = parseTrades(json.optJSONArray("trades"));
        if (state.metrics.openTradeCount == 0) {
            state.metrics.openTradeCount = state.trades.size();
        }
        return state;
    }

    private static ArrayList<TradePosition> parseTrades(JSONArray array) {
        ArrayList<TradePosition> trades = new ArrayList<>();
        if (array == null) {
            return trades;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            TradePosition trade = new TradePosition();
            trade.ticket = json.optLong("ticket", 0L);
            trade.symbol = json.optString("symbol", "XAUUSDc");
            trade.side = json.optString("side", "BUY");
            trade.lots = json.optDouble("lots", 0.01);
            trade.openPrice = json.optDouble("openPrice", 0.0);
            trade.currentPrice = json.optDouble("currentPrice", 0.0);
            trade.profit = json.optDouble("profit", 0.0);
            trade.openTime = json.optString("openTime", "");
            trades.add(trade);
        }
        return trades;
    }

    private static JSONObject accountToJson(TradingAccount account) {
        JSONObject json = new JSONObject();
        try {
            json.put("label", account.label);
            json.put("platform", account.platform);
            json.put("broker", account.broker);
            json.put("accountNumber", account.accountNumber);
            json.put("symbol", account.symbol);
            json.put("magicNumber", account.magicNumber);
        } catch (JSONException ignored) {
        }
        return json;
    }

    private void demoLogin(String email, Callback<AccountState> callback) {
        String safeEmail = email == null || email.trim().isEmpty() ? "demo@asifbot.local" : email.trim();
        String token = "demo:" + encode(safeEmail);
        demoAccountsForToken(token);
        callback.onSuccess(demoAccount(safeEmail, false, anyDemoBotEnabled(token)));
    }

    private AccountState demoAccount(String email, boolean subscriptionActive, boolean botEnabled) {
        AccountState state = new AccountState();
        state.email = email == null || email.isEmpty() ? "demo@asifbot.local" : email;
        state.token = "demo:" + encode(state.email);
        state.subscriptionActive = subscriptionActive;
        state.trialEndsAtMs = System.currentTimeMillis() + 3L * 24L * 60L * 60L * 1000L;
        state.botEnabled = botEnabled;
        return state;
    }

    private ArrayList<TradingAccount> demoAccountsForToken(String token) {
        String key = userKey(token);
        ArrayList<TradingAccount> existing = demoAccountsByUser.get(key);
        if (existing != null) {
            return existing;
        }
        ArrayList<TradingAccount> accounts = new ArrayList<>();
        TradingAccount account = new TradingAccount();
        account.id = key + "-gold-1";
        account.label = "Exness Gold Cent";
        account.platform = "MT4";
        account.broker = "Exness-MT4 Trial";
        account.accountNumber = "12345678";
        account.symbol = "XAUUSDc";
        account.magicNumber = 7777;
        account.connected = true;
        account.botStatus = "ON";
        accounts.add(account);
        demoAccountsByUser.put(key, accounts);
        demoBotState.put(account.id, true);
        demoCommandState.put(account.id, "ON confirmed by demo bridge");
        return accounts;
    }

    private DashboardState demoDashboard(String token, String accountId) {
        TradingAccount account = findDemoAccount(token, accountId);
        boolean enabled = Boolean.TRUE.equals(demoBotState.get(account.id));
        account.botStatus = enabled ? "ON" : "OFF";

        DashboardState state = new DashboardState();
        state.account = copyAccount(account);
        state.trades = enabled ? demoTrades(account) : new ArrayList<>();
        state.metrics = demoMetrics(account, state.trades);
        return state;
    }

    private Metrics demoMetrics(TradingAccount account, ArrayList<TradePosition> trades) {
        Metrics metrics = new Metrics();
        metrics.balance = 500.00;
        metrics.floatingProfit = 0.0;
        metrics.totalLots = 0.0;
        metrics.worstTradeLoss = 0.0;
        for (TradePosition trade : trades) {
            metrics.floatingProfit += trade.profit;
            metrics.totalLots += trade.lots;
            metrics.worstTradeLoss = Math.min(metrics.worstTradeLoss, trade.profit);
        }
        metrics.equity = metrics.balance + metrics.floatingProfit;
        metrics.marginLevel = trades.isEmpty() ? 0.0 : 385.4;
        metrics.openTradeCount = trades.size();
        metrics.commandStatus = demoCommandState.containsKey(account.id) ? demoCommandState.get(account.id) : "Ready";
        metrics.lastUpdate = nowLabel();
        return metrics;
    }

    private ArrayList<TradePosition> demoTrades(TradingAccount account) {
        ArrayList<TradePosition> trades = new ArrayList<>();
        trades.add(demoTrade(88234101L, account.symbol, "BUY", 0.03, 4314.126, 4313.841, -0.86, "M1 today"));
        trades.add(demoTrade(88234144L, account.symbol, "BUY", 0.04, 4313.726, 4313.841, 0.46, "M1 today"));
        trades.add(demoTrade(88234208L, account.symbol, "SELL", 0.02, 4313.420, 4313.841, -0.84, "M1 today"));
        return trades;
    }

    private static TradePosition demoTrade(long ticket, String symbol, String side, double lots, double openPrice, double currentPrice, double profit, String openTime) {
        TradePosition trade = new TradePosition();
        trade.ticket = ticket;
        trade.symbol = symbol;
        trade.side = side;
        trade.lots = lots;
        trade.openPrice = openPrice;
        trade.currentPrice = currentPrice;
        trade.profit = profit;
        trade.openTime = openTime;
        return trade;
    }

    private TradingAccount findDemoAccount(String token, String id) {
        ArrayList<TradingAccount> accounts = demoAccountsForToken(token);
        for (TradingAccount account : accounts) {
            if (account.id.equals(id)) {
                return account;
            }
        }
        if (accounts.isEmpty()) {
            TradingAccount account = new TradingAccount();
            account.id = userKey(token) + "-empty";
            account.label = "No Account";
            account.platform = "MT4";
            account.broker = "";
            account.accountNumber = "";
            account.symbol = "XAUUSDc";
            account.magicNumber = 0;
            account.connected = false;
            account.botStatus = "OFF";
            return account;
        }
        return accounts.get(0);
    }

    private boolean anyDemoBotEnabled(String token) {
        for (TradingAccount account : demoAccountsForToken(token)) {
            if (Boolean.TRUE.equals(demoBotState.get(account.id))) {
                return true;
            }
        }
        return false;
    }

    private static String userKey(String token) {
        String email = emailFromDemoToken(token);
        return encode(email.toLowerCase(Locale.US)).replace("+", "_").replace("%", "_");
    }

    private static ArrayList<TradingAccount> copyAccounts(ArrayList<TradingAccount> source) {
        ArrayList<TradingAccount> copy = new ArrayList<>();
        for (TradingAccount account : source) {
            copy.add(copyAccount(account));
        }
        return copy;
    }

    private static TradingAccount copyAccount(TradingAccount source) {
        TradingAccount account = new TradingAccount();
        account.id = source.id;
        account.label = source.label;
        account.platform = source.platform;
        account.broker = source.broker;
        account.accountNumber = source.accountNumber;
        account.symbol = source.symbol;
        account.magicNumber = source.magicNumber;
        account.connected = source.connected;
        account.botStatus = source.botStatus;
        account.bridgeToken = source.bridgeToken;
        return account;
    }

    private static String emailFromDemoToken(String token) {
        if (token != null && token.startsWith("demo:")) {
            return decode(token.substring(5));
        }
        return "demo@asifbot.local";
    }

    private static String nowLabel() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String readBody(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
