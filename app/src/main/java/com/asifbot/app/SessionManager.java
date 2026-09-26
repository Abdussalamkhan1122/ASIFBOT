package com.asifbot.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

final class SessionManager {
    private static final String PREFS = "session";
    private static final String TOKEN = "token";
    private static final String EMAIL = "email";
    private static final String SUB_ACTIVE = "subscriptionActive";
    private static final String TRIAL_ENDS = "trialEndsAtMs";
    private static final String BOT_ENABLED = "botEnabled";
    private static final String ACCOUNT_KEY = "accountKey";

    private final SharedPreferences prefs;

    SessionManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String token() {
        return prefs.getString(TOKEN, "");
    }

    boolean isSignedIn() {
        return !token().isEmpty();
    }

    String accountKey() {
        String key = prefs.getString(ACCOUNT_KEY, "");
        if (key.isEmpty()) {
            key = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(ACCOUNT_KEY, key).apply();
        }
        return key;
    }

    ApiClient.AccountState state() {
        ApiClient.AccountState state = new ApiClient.AccountState();
        state.token = token();
        state.email = prefs.getString(EMAIL, "");
        state.subscriptionActive = prefs.getBoolean(SUB_ACTIVE, false);
        state.trialEndsAtMs = prefs.getLong(TRIAL_ENDS, 0L);
        state.botEnabled = prefs.getBoolean(BOT_ENABLED, false);
        return state;
    }

    void save(ApiClient.AccountState state) {
        SharedPreferences.Editor editor = prefs.edit();
        if (state.token != null && !state.token.isEmpty()) {
            editor.putString(TOKEN, state.token);
        }
        editor.putString(EMAIL, state.email == null ? "" : state.email);
        editor.putBoolean(SUB_ACTIVE, state.subscriptionActive);
        editor.putLong(TRIAL_ENDS, state.trialEndsAtMs);
        editor.putBoolean(BOT_ENABLED, state.botEnabled);
        editor.apply();
    }

    void saveBot(ApiClient.BotState state) {
        prefs.edit().putBoolean(BOT_ENABLED, state.enabled).apply();
    }

    void clear() {
        String key = accountKey();
        prefs.edit().clear().putString(ACCOUNT_KEY, key).apply();
    }
}
