package com.asifbot.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.android.billingclient.api.Purchase;

public final class MainActivity extends Activity implements BillingManager.Listener {
    private static final int BG = Color.rgb(17, 20, 24);
    private static final int PANEL = Color.rgb(246, 247, 249);
    private static final int GOLD = Color.rgb(217, 164, 65);
    private static final int TEXT = Color.rgb(32, 35, 40);

    private final Handler main = new Handler(Looper.getMainLooper());
    private SessionManager session;
    private ApiClient api;
    private BillingManager billing;
    private TextView statusText;
    private Switch botSwitch;
    private boolean changingSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionManager(this);
        api = new ApiClient(BuildConfig.API_BASE_URL, BuildConfig.DEMO_MODE);
        billing = new BillingManager(this, BuildConfig.SUBSCRIPTION_PRODUCT_ID, this);
        billing.start();

        if (session.isSignedIn()) {
            showDashboard();
            refreshAccount();
        } else {
            showLogin();
        }
    }

    @Override
    protected void onDestroy() {
        billing.endConnection();
        super.onDestroy();
    }

    @Override
    public void onBillingMessage(String message) {
        main.post(() -> toast(message));
    }

    @Override
    public void onPurchaseReady(Purchase purchase) {
        String token = session.token();
        if (token.isEmpty()) {
            main.post(() -> toast("Login first, then subscribe."));
            return;
        }
        api.verifyPurchase(token, BuildConfig.SUBSCRIPTION_PRODUCT_ID, purchase.getPurchaseToken(), new UiCallback<ApiClient.AccountState>() {
            @Override
            public void success(ApiClient.AccountState value) {
                session.save(value);
                billing.acknowledge(purchase);
                renderStatus(session.state(), null);
            }
        });
    }

    private void showLogin() {
        LinearLayout card = baseScreen();
        addTitle(card, "ASIFBOT");
        addSubtitle(card, api.isDemoMode()
                ? "Test login is active. Backend connection will be added before release."
                : "Login to control your trading bot from your phone.");

        EditText email = input("Email");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(email);

        EditText password = input("Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(password);

        Button login = primaryButton("Login");
        Button register = secondaryButton("Create account with 3-day trial");
        card.addView(login);
        card.addView(register);

        login.setOnClickListener(v -> authenticate(false, email.getText().toString(), password.getText().toString()));
        register.setOnClickListener(v -> authenticate(true, email.getText().toString(), password.getText().toString()));
    }

    private void showDashboard() {
        LinearLayout card = baseScreen();
        addTitle(card, "ASIFBOT");

        statusText = new TextView(this);
        statusText.setTextColor(TEXT);
        statusText.setTextSize(15);
        statusText.setLineSpacing(8, 1);
        card.addView(statusText);

        botSwitch = new Switch(this);
        botSwitch.setText("Bot ON/OFF");
        botSwitch.setTextColor(TEXT);
        botSwitch.setTextSize(18);
        botSwitch.setPadding(0, dp(20), 0, dp(20));
        card.addView(botSwitch);

        botSwitch.setOnCheckedChangeListener(this::onBotSwitchChanged);

        Button refresh = secondaryButton("Refresh status");
        Button subscribe = primaryButton("Subscribe / start trial");
        Button logout = secondaryButton("Logout");
        card.addView(refresh);
        card.addView(subscribe);
        card.addView(logout);

        TextView terms = new TextView(this);
        terms.setText(getString(com.asifbot.app.R.string.subscription_terms));
        terms.setTextColor(Color.rgb(88, 93, 101));
        terms.setTextSize(13);
        terms.setPadding(0, dp(12), 0, 0);
        card.addView(terms);

        refresh.setOnClickListener(v -> refreshAccount());
        subscribe.setOnClickListener(v -> billing.launchSubscription(this, session.accountKey()));
        logout.setOnClickListener(v -> {
            session.clear();
            showLogin();
        });

        renderStatus(session.state(), null);
        refreshBot();
    }

    private void authenticate(boolean createAccount, String email, String password) {
        if (email.trim().isEmpty() || password.length() < 6) {
            toast("Enter email and password with at least 6 characters.");
            return;
        }

        ApiClient.Callback<ApiClient.AccountState> callback = new UiCallback<ApiClient.AccountState>() {
            @Override
            public void success(ApiClient.AccountState value) {
                session.save(value);
                showDashboard();
            }
        };

        if (createAccount) {
            api.register(email.trim(), password, callback);
        } else {
            api.login(email.trim(), password, callback);
        }
    }

    private void refreshAccount() {
        String token = session.token();
        if (token.isEmpty()) {
            return;
        }
        api.loadMe(token, new UiCallback<ApiClient.AccountState>() {
            @Override
            public void success(ApiClient.AccountState value) {
                session.save(value);
                renderStatus(session.state(), null);
                refreshBot();
            }
        });
    }

    private void refreshBot() {
        String token = session.token();
        if (token.isEmpty()) {
            return;
        }
        api.loadBot(token, new UiCallback<ApiClient.BotState>() {
            @Override
            public void success(ApiClient.BotState value) {
                session.saveBot(value);
                renderStatus(session.state(), value);
            }
        });
    }

    private void onBotSwitchChanged(CompoundButton button, boolean enabled) {
        if (changingSwitch) {
            return;
        }

        ApiClient.AccountState account = session.state();
        if (!account.hasAccess()) {
            changingSwitch = true;
            botSwitch.setChecked(false);
            changingSwitch = false;
            toast("Trial expired. Subscribe to control the bot.");
            return;
        }

        api.setBotEnabled(session.token(), enabled, new UiCallback<ApiClient.BotState>() {
            @Override
            public void success(ApiClient.BotState value) {
                session.saveBot(value);
                renderStatus(session.state(), value);
            }

            @Override
            public void error(String message) {
                super.error(message);
                changingSwitch = true;
                botSwitch.setChecked(!enabled);
                changingSwitch = false;
            }
        });
    }

    private void renderStatus(ApiClient.AccountState account, ApiClient.BotState bot) {
        if (statusText == null) {
            return;
        }

        boolean enabled = bot != null ? bot.enabled : account.botEnabled;
        changingSwitch = true;
        botSwitch.setChecked(enabled);
        botSwitch.setEnabled(account.hasAccess());
        changingSwitch = false;

        String serverStatus = bot == null ? "loading" : bot.serverStatus;
        String lastSeen = bot == null ? "loading" : bot.lastSeen;
        String tradingAccount = bot == null ? "loading" : bot.account;

        statusText.setText(
                "User: " + account.email + "\n" +
                "Access: " + account.accessLabel() + "\n" +
                "Bot switch: " + (enabled ? "ON" : "OFF") + "\n" +
                "Server: " + serverStatus + "\n" +
                "Trading account: " + tradingAccount + "\n" +
                "Last EA connection: " + lastSeen
        );
    }

    private LinearLayout baseScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(18), dp(22), dp(18), dp(22));
        scroll.addView(outer);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackgroundColor(PANEL);
        outer.addView(card, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
        return card;
    }

    private void addTitle(LinearLayout parent, String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextColor(GOLD);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(8));
        parent.addView(title);
    }

    private void addSubtitle(LinearLayout parent, String value) {
        TextView subtitle = new TextView(this);
        subtitle.setText(value);
        subtitle.setTextColor(Color.rgb(86, 92, 101));
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, 0, 0, dp(18));
        parent.addView(subtitle);
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextColor(TEXT);
        editText.setHintTextColor(Color.rgb(120, 126, 136));
        editText.setTextSize(16);
        editText.setPadding(dp(12), dp(10), dp(12), dp(10));
        editText.setLayoutParams(blockParams());
        return editText;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.rgb(17, 20, 24));
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackgroundColor(GOLD);
        button.setAllCaps(false);
        button.setLayoutParams(blockParams());
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setLayoutParams(blockParams());
        return button;
    }

    private LinearLayout.LayoutParams blockParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, dp(8));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private abstract class UiCallback<T> implements ApiClient.Callback<T> {
        @Override
        public final void onSuccess(T value) {
            main.post(() -> success(value));
        }

        @Override
        public final void onError(String message) {
            main.post(() -> error(message));
        }

        public abstract void success(T value);

        public void error(String message) {
            toast(message == null || message.isEmpty() ? "Request failed" : message);
        }
    }
}
