package com.asifbot.app;

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

    static final class BotState {
        boolean enabled;
        String serverStatus;
        String lastSeen;
        String account;
    }

    private final String baseUrl;
    private final boolean demoMode;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean demoBotEnabled;

    ApiClient(String baseUrl, boolean demoMode) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.demoMode = demoMode;
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
            callback.onSuccess(demoAccount(emailFromDemoToken(token), false, demoBotEnabled));
            return;
        }
        get("/me", token, json -> callback.onSuccess(parseAccount(json)), callback);
    }

    void loadBot(String token, Callback<BotState> callback) {
        if (demoMode) {
            callback.onSuccess(demoBot(demoBotEnabled));
            return;
        }
        get("/bot/status", token, json -> callback.onSuccess(parseBot(json)), callback);
    }

    void setBotEnabled(String token, boolean enabled, Callback<BotState> callback) {
        if (demoMode) {
            demoBotEnabled = enabled;
            callback.onSuccess(demoBot(demoBotEnabled));
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("enabled", enabled);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        post("/bot/control", token, body, json -> callback.onSuccess(parseBot(json)), callback);
    }

    void verifyPurchase(String token, String productId, String purchaseToken, Callback<AccountState> callback) {
        if (demoMode) {
            callback.onSuccess(demoAccount(emailFromDemoToken(token), true, true));
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

    boolean isDemoMode() {
        return demoMode;
    }

    private interface JsonHandler {
        void handle(JSONObject json) throws JSONException;
    }

    private void get(String path, String token, JsonHandler handler, Callback<?> callback) {
        io.execute(() -> runRequest("GET", path, token, null, handler, callback));
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

    private static AccountState parseAccount(JSONObject json) throws JSONException {
        AccountState state = new AccountState();
        state.token = json.optString("token", null);
        state.email = json.optString("email", "");
        state.subscriptionActive = json.optBoolean("subscriptionActive", false);
        state.trialEndsAtMs = json.optLong("trialEndsAtMs", 0L);
        state.botEnabled = json.optBoolean("botEnabled", false);
        return state;
    }

    private void demoLogin(String email, Callback<AccountState> callback) {
        String safeEmail = email == null || email.trim().isEmpty() ? "demo@asifbot.local" : email.trim();
        callback.onSuccess(demoAccount(safeEmail, false, false));
    }

    private static AccountState demoAccount(String email, boolean subscriptionActive, boolean botEnabled) {
        AccountState state = new AccountState();
        state.email = email == null || email.isEmpty() ? "demo@asifbot.local" : email;
        state.token = "demo:" + encode(state.email);
        state.subscriptionActive = subscriptionActive;
        state.trialEndsAtMs = System.currentTimeMillis() + 3L * 24L * 60L * 60L * 1000L;
        state.botEnabled = botEnabled;
        return state;
    }

    private static BotState demoBot(boolean enabled) {
        BotState state = new BotState();
        state.enabled = enabled;
        state.serverStatus = "demo mode";
        state.lastSeen = "local test only";
        state.account = "backend not connected";
        return state;
    }

    private static String emailFromDemoToken(String token) {
        if (token != null && token.startsWith("demo:")) {
            return decode(token.substring(5));
        }
        return "demo@asifbot.local";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static BotState parseBot(JSONObject json) {
        BotState state = new BotState();
        state.enabled = json.optBoolean("enabled", false);
        state.serverStatus = json.optString("serverStatus", "unknown");
        state.lastSeen = json.optString("lastSeen", "not connected");
        state.account = json.optString("account", "not linked");
        return state;
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
