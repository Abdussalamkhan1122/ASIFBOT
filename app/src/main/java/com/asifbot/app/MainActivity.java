package com.asifbot.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.billingclient.api.Purchase;

import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity implements BillingManager.Listener {
    private static final int BG = Color.rgb(15, 18, 22);
    private static final int PANEL = Color.rgb(246, 247, 249);
    private static final int PANEL_DARK = Color.rgb(28, 33, 40);
    private static final int GOLD = Color.rgb(217, 164, 65);
    private static final int TEXT = Color.rgb(32, 35, 40);
    private static final int MUTED = Color.rgb(98, 105, 116);
    private static final int GREEN = Color.rgb(19, 133, 81);
    private static final int RED = Color.rgb(190, 55, 55);

    private final Handler main = new Handler(Looper.getMainLooper());
    private SessionManager session;
    private ApiClient api;
    private BillingManager billing;
    private ApiClient.AccountState accountState;
    private String selectedAccountId = "";
    private String currentScreen = "";
    private Runnable refreshTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionManager(this);
        api = new ApiClient(BuildConfig.API_BASE_URL, BuildConfig.DEMO_MODE);
        if (!BuildConfig.DEMO_MODE) {
            billing = new BillingManager(this, BuildConfig.SUBSCRIPTION_PRODUCT_ID, this);
            billing.start();
        }

        if (session.isSignedIn()) {
            accountState = session.state();
            showAccounts();
            refreshAccount();
        } else {
            showLogin();
        }
    }

    @Override
    protected void onDestroy() {
        stopAutoRefresh();
        if (billing != null) {
            billing.endConnection();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if ("dashboard".equals(currentScreen) || "trades".equals(currentScreen) || "add".equals(currentScreen) || "subscription".equals(currentScreen)) {
            showAccounts();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onBillingMessage(String message) {
        main.post(() -> toast(message));
    }

    @Override
    public void onPurchaseReady(Purchase purchase) {
        if (accountState == null || accountState.token == null || accountState.token.isEmpty()) {
            main.post(() -> toast("Login first, then subscribe."));
            return;
        }
        api.verifyPurchase(accountState.token, BuildConfig.SUBSCRIPTION_PRODUCT_ID, purchase.getPurchaseToken(), new UiCallback<ApiClient.AccountState>() {
            @Override
            public void success(ApiClient.AccountState value) {
                accountState = value;
                session.save(value);
                if (billing != null) {
                    billing.acknowledge(purchase);
                }
                showSubscription();
            }
        });
    }

    private void showLogin() {
        stopAutoRefresh();
        currentScreen = "login";
        LinearLayout body = baseScreen("ASIFBOT", api.isDemoMode()
                ? "Demo login is active. The real backend connects before release."
                : "Secure bot control for MT4 and MT5 accounts.");

        LinearLayout card = card();
        body.addView(card);

        EditText email = input("Email");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(label("Email"));
        card.addView(email);

        EditText password = input("Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(label("Password"));
        card.addView(password);

        Button login = primaryButton("Login");
        Button register = secondaryButton("Create account with 3-day trial");
        Button forgot = textButton("Forgot password");
        card.addView(login);
        card.addView(register);
        card.addView(forgot);

        body.addView(infoCard("3-Day Trial + Subscription",
                "New users can create an account with a 3-day free trial. After the trial, ASIFBOT control requires an active subscription."));

        login.setOnClickListener(v -> authenticate(false, email.getText().toString(), password.getText().toString()));
        register.setOnClickListener(v -> authenticate(true, email.getText().toString(), password.getText().toString()));
        forgot.setOnClickListener(v -> toast("Password reset will be available when backend email is connected."));
    }

    private void authenticate(boolean createAccount, String email, String password) {
        if (email.trim().isEmpty() || password.length() < 6) {
            toast("Enter email and password with at least 6 characters.");
            return;
        }

        ApiClient.Callback<ApiClient.AccountState> callback = new UiCallback<ApiClient.AccountState>() {
            @Override
            public void success(ApiClient.AccountState value) {
                accountState = value;
                session.save(value);
                selectedAccountId = "";
                showAccounts();
            }
        };

        if (createAccount) {
            api.register(email.trim(), password, callback);
        } else {
            api.login(email.trim(), password, callback);
        }
    }

    private void refreshAccount() {
        if (accountState == null || accountState.token == null || accountState.token.isEmpty()) {
            return;
        }
        api.loadMe(accountState.token, new UiCallback<ApiClient.AccountState>() {
            @Override
            public void success(ApiClient.AccountState value) {
                accountState = value;
                session.save(value);
            }
        });
    }

    private void showAccounts() {
        stopAutoRefresh();
        currentScreen = "accounts";
        LinearLayout body = baseScreen("Accounts", "Add MT4/MT5 accounts and open the control dashboard.");
        body.addView(loadingCard("Loading linked accounts..."));

        api.listAccounts(token(), new UiCallback<ArrayList<ApiClient.TradingAccount>>() {
            @Override
            public void success(ArrayList<ApiClient.TradingAccount> accounts) {
                renderAccounts(accounts);
            }
        });
    }

    private void renderAccounts(ArrayList<ApiClient.TradingAccount> accounts) {
        LinearLayout body = baseScreen("Accounts", accountState == null ? "" : accountState.email + " | " + accountState.accessLabel());

        if (api.isDemoMode()) {
            body.addView(infoCard("Demo Mode", "The app is using local demo data. Real MT4/MT5 control starts after backend and EA bridge are connected."));
        }

        if (accounts.isEmpty()) {
            body.addView(infoCard("No Trading Accounts", "Add your MT4/MT5 account to connect it with the ASIFBOT VPS/EA bridge."));
        }

        for (ApiClient.TradingAccount account : accounts) {
            LinearLayout accountCard = card();
            body.addView(accountCard);
            accountCard.addView(sectionTitle(account.label));
            accountCard.addView(line(account.platform + " | " + account.broker));
            accountCard.addView(line("Account: " + account.accountNumber + " | Symbol: " + account.symbol));
            accountCard.addView(line("Magic: " + account.magicNumber + " | Status: " + account.botStatus));
            accountCard.addView(statusText(account.connected ? "EA bridge online" : "EA bridge offline", account.connected ? GREEN : RED));
            Button open = primaryButton("Open Dashboard");
            Button delete = dangerOutlineButton("Delete Account");
            accountCard.addView(open);
            accountCard.addView(delete);
            open.setOnClickListener(v -> {
                selectedAccountId = account.id;
                showDashboard(account.id);
            });
            delete.setOnClickListener(v -> confirmDeleteAccount(account));
        }

        Button add = primaryButton("Add MT4/MT5 Account");
        Button subscription = secondaryButton("Trial / Subscription");
        Button logout = secondaryButton("Logout");
        body.addView(add);
        body.addView(subscription);
        body.addView(logout);

        add.setOnClickListener(v -> showAddAccount());
        subscription.setOnClickListener(v -> showSubscription());
        logout.setOnClickListener(v -> {
            session.clear();
            accountState = null;
            selectedAccountId = "";
            showLogin();
        });
    }

    private void confirmDeleteAccount(ApiClient.TradingAccount account) {
        new AlertDialog.Builder(this)
                .setTitle("Delete account?")
                .setMessage("This removes " + account.label + " from this ASIFBOT login.\n\nIt does not delete the real broker account. If the bot is running on VPS, turn it OFF first so open trades can be closed safely.")
                .setPositiveButton("Delete", (dialog, which) -> api.deleteAccount(token(), account.id, new UiCallback<ArrayList<ApiClient.TradingAccount>>() {
                    @Override
                    public void success(ArrayList<ApiClient.TradingAccount> accounts) {
                        if (account.id.equals(selectedAccountId)) {
                            selectedAccountId = "";
                        }
                        toast("Account deleted.");
                        renderAccounts(accounts);
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddAccount() {
        stopAutoRefresh();
        currentScreen = "add";
        LinearLayout body = baseScreen("Add Account", "Link the MT4/MT5 account controlled by your EA/VPS bridge.");
        LinearLayout card = card();
        body.addView(card);

        EditText labelInput = input("Example: Exness Gold Cent");
        EditText platformInput = input("MT4 or MT5");
        EditText brokerInput = input("Broker server");
        EditText numberInput = input("Trading account number");
        EditText symbolInput = input("XAUUSDc");
        EditText magicInput = input("EA Magic Number");

        platformInput.setText("MT4");
        symbolInput.setText("XAUUSDc");
        magicInput.setText("7777");
        numberInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        magicInput.setInputType(InputType.TYPE_CLASS_NUMBER);

        card.addView(label("Account label"));
        card.addView(labelInput);
        card.addView(label("Platform"));
        card.addView(platformInput);
        card.addView(label("Broker / server"));
        card.addView(brokerInput);
        card.addView(label("Account number"));
        card.addView(numberInput);
        card.addView(label("Main symbol"));
        card.addView(symbolInput);
        card.addView(label("Magic number"));
        card.addView(magicInput);

        card.addView(infoText("Trading password is not stored in the app. The EA/VPS bridge must connect with a secure account token."));

        Button save = primaryButton("Save Account");
        Button cancel = secondaryButton("Back to Accounts");
        card.addView(save);
        card.addView(cancel);

        save.setOnClickListener(v -> {
            if (labelInput.getText().toString().trim().isEmpty() || numberInput.getText().toString().trim().isEmpty()) {
                toast("Account label and account number are required.");
                return;
            }
            ApiClient.TradingAccount account = new ApiClient.TradingAccount();
            account.label = labelInput.getText().toString().trim();
            account.platform = platformInput.getText().toString().trim().toUpperCase(Locale.US);
            account.broker = brokerInput.getText().toString().trim();
            account.accountNumber = numberInput.getText().toString().trim();
            account.symbol = symbolInput.getText().toString().trim();
            account.magicNumber = parseInt(magicInput.getText().toString(), 0);
            api.createAccount(token(), account, new UiCallback<ApiClient.TradingAccount>() {
                @Override
                public void success(ApiClient.TradingAccount value) {
                    selectedAccountId = value.id;
                    if (value.bridgeToken != null && !value.bridgeToken.isEmpty()) {
                        showBridgeToken(value);
                    } else {
                        showDashboard(value.id);
                    }
                }
            });
        });
        cancel.setOnClickListener(v -> showAccounts());
    }

    private void showBridgeToken(ApiClient.TradingAccount account) {
        new AlertDialog.Builder(this)
                .setTitle("VPS Bridge Token")
                .setMessage("Save this token now. It is shown only once and is needed on the VPS/EA bridge for:\n\n"
                        + account.label + "\n\nAccount ID:\n" + account.id + "\n\nBridge Token:\n" + account.bridgeToken
                        + "\n\nKeep it private. Anyone with this token can send bridge updates for this linked account.")
                .setPositiveButton("Open Dashboard", (dialog, which) -> showDashboard(account.id))
                .setNegativeButton("Back to Accounts", (dialog, which) -> showAccounts())
                .show();
    }

    private void showDashboard(String accountId) {
        currentScreen = "dashboard";
        selectedAccountId = accountId;
        LinearLayout body = baseScreen("Dashboard", "Loading account control panel...");
        body.addView(loadingCard("Fetching bot status and open trades..."));
        loadDashboard(accountId, true);
    }

    private void loadDashboard(String accountId, boolean reschedule) {
        api.loadDashboard(token(), accountId, new UiCallback<ApiClient.DashboardState>() {
            @Override
            public void success(ApiClient.DashboardState state) {
                renderDashboard(state);
                if (reschedule) {
                    scheduleDashboardRefresh(accountId);
                }
            }
        });
    }

    private void renderDashboard(ApiClient.DashboardState state) {
        currentScreen = "dashboard";
        selectedAccountId = state.account.id;
        LinearLayout body = baseScreen("Dashboard", state.account.label + " | " + state.account.platform + " | " + state.account.symbol);

        LinearLayout status = card();
        body.addView(status);
        status.addView(sectionTitle("Bot Status"));
        status.addView(statusText(state.account.botStatus, "ON".equalsIgnoreCase(state.account.botStatus) ? GREEN : RED));
        status.addView(line(state.account.connected ? "EA bridge: Online" : "EA bridge: Offline"));
        status.addView(line("Command: " + state.metrics.commandStatus));
        status.addView(line("Last update: " + state.metrics.lastUpdate));

        LinearLayout metrics = card();
        body.addView(metrics);
        metrics.addView(sectionTitle("Account Risk"));
        metrics.addView(twoStatRow("Balance", money(state.metrics.balance), "Equity", money(state.metrics.equity), TEXT, TEXT));
        metrics.addView(twoStatRow("Floating P/L", money(state.metrics.floatingProfit), "Worst Trade", money(state.metrics.worstTradeLoss), moneyColor(state.metrics.floatingProfit), moneyColor(state.metrics.worstTradeLoss)));
        metrics.addView(twoStatRow("Open Trades", String.valueOf(state.metrics.openTradeCount), "Total Lots", String.format(Locale.US, "%.2f", state.metrics.totalLots), TEXT, TEXT));

        LinearLayout controls = card();
        body.addView(controls);
        controls.addView(sectionTitle("Bot Control"));
        controls.addView(infoText("OFF will close all open ASIFBOT trades, delete ASIFBOT pending orders, and stop new entries."));

        Button turnOn = primaryButton("Turn ON");
        Button turnOff = dangerButton("Turn OFF and Close Trades");
        Button emergency = dangerOutlineButton("Emergency Close All");
        Button trades = secondaryButton("View Open Trades");
        Button refresh = secondaryButton("Refresh");
        Button accounts = secondaryButton("Back to Accounts");

        controls.addView(turnOn);
        controls.addView(turnOff);
        controls.addView(emergency);
        controls.addView(trades);
        controls.addView(refresh);
        controls.addView(accounts);

        turnOn.setEnabled(!"ON".equalsIgnoreCase(state.account.botStatus));
        turnOff.setEnabled(state.metrics.openTradeCount > 0 || "ON".equalsIgnoreCase(state.account.botStatus));
        emergency.setEnabled(state.metrics.openTradeCount > 0 || "ON".equalsIgnoreCase(state.account.botStatus));

        turnOn.setOnClickListener(v -> turnOn(state.account.id));
        turnOff.setOnClickListener(v -> confirmCloseAndOff(state));
        emergency.setOnClickListener(v -> confirmEmergencyClose(state));
        trades.setOnClickListener(v -> showTrades(state.account.id));
        refresh.setOnClickListener(v -> loadDashboard(state.account.id, true));
        accounts.setOnClickListener(v -> showAccounts());
    }

    private void turnOn(String accountId) {
        if (!hasAccess()) {
            showSubscription();
            return;
        }
        api.turnBotOn(token(), accountId, new UiCallback<ApiClient.DashboardState>() {
            @Override
            public void success(ApiClient.DashboardState value) {
                renderDashboard(value);
                scheduleDashboardRefresh(accountId);
            }
        });
    }

    private void confirmCloseAndOff(ApiClient.DashboardState state) {
        new AlertDialog.Builder(this)
                .setTitle("Turn OFF and close trades?")
                .setMessage("This will close " + state.metrics.openTradeCount + " open ASIFBOT trade(s), delete pending orders, and stop new entries.\n\nCurrent floating P/L: " + money(state.metrics.floatingProfit))
                .setPositiveButton("Close and OFF", (dialog, which) -> api.turnBotOffCloseTrades(token(), state.account.id, new UiCallback<ApiClient.DashboardState>() {
                    @Override
                    public void success(ApiClient.DashboardState value) {
                        toast("Bot is OFF. ASIFBOT trades closed.");
                        renderDashboard(value);
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmEmergencyClose(ApiClient.DashboardState state) {
        new AlertDialog.Builder(this)
                .setTitle("Emergency close all?")
                .setMessage("Emergency close will close all ASIFBOT trades immediately and keep the bot OFF.")
                .setPositiveButton("Emergency Close", (dialog, which) -> api.emergencyClose(token(), state.account.id, new UiCallback<ApiClient.DashboardState>() {
                    @Override
                    public void success(ApiClient.DashboardState value) {
                        toast("Emergency close completed.");
                        renderDashboard(value);
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTrades(String accountId) {
        stopAutoRefresh();
        currentScreen = "trades";
        LinearLayout body = baseScreen("Open Trades", "Loading open positions and floating loss...");
        body.addView(loadingCard("Fetching positions..."));
        api.loadDashboard(token(), accountId, new UiCallback<ApiClient.DashboardState>() {
            @Override
            public void success(ApiClient.DashboardState value) {
                renderTrades(value);
            }
        });
    }

    private void renderTrades(ApiClient.DashboardState state) {
        LinearLayout body = baseScreen("Open Trades", state.account.label + " | Floating P/L " + money(state.metrics.floatingProfit));

        LinearLayout summary = card();
        body.addView(summary);
        summary.addView(sectionTitle("Trade Summary"));
        summary.addView(twoStatRow("Open Trades", String.valueOf(state.metrics.openTradeCount), "Total Lots", String.format(Locale.US, "%.2f", state.metrics.totalLots), TEXT, TEXT));
        summary.addView(twoStatRow("Floating P/L", money(state.metrics.floatingProfit), "Worst Loss", money(state.metrics.worstTradeLoss), moneyColor(state.metrics.floatingProfit), moneyColor(state.metrics.worstTradeLoss)));

        if (state.trades.isEmpty()) {
            body.addView(infoCard("No Open Trades", "There are no active ASIFBOT trades for this account."));
        } else {
            for (ApiClient.TradePosition trade : state.trades) {
                LinearLayout tradeCard = card();
                body.addView(tradeCard);
                tradeCard.addView(sectionTitle("#" + trade.ticket + " | " + trade.side + " " + trade.symbol));
                tradeCard.addView(twoStatRow("Lot", String.format(Locale.US, "%.2f", trade.lots), "P/L", money(trade.profit), TEXT, moneyColor(trade.profit)));
                tradeCard.addView(line("Open: " + price(trade.openPrice) + " | Current: " + price(trade.currentPrice)));
                tradeCard.addView(line("Opened: " + trade.openTime));
            }
        }

        Button close = dangerButton("Turn OFF and Close ASIFBOT Trades");
        Button back = secondaryButton("Back to Dashboard");
        body.addView(close);
        body.addView(back);
        close.setOnClickListener(v -> confirmCloseAndOff(state));
        back.setOnClickListener(v -> showDashboard(state.account.id));
    }

    private void showSubscription() {
        stopAutoRefresh();
        currentScreen = "subscription";
        if (accountState == null) {
            accountState = session.state();
        }
        LinearLayout body = baseScreen("Subscription", "Manage trial and Google Play access.");

        LinearLayout card = card();
        body.addView(card);
        card.addView(sectionTitle("Access"));
        card.addView(line("User: " + accountState.email));
        card.addView(line("Status: " + accountState.accessLabel()));
        card.addView(infoText(getString(R.string.subscription_terms)));

        Button subscribe = primaryButton(api.isDemoMode() ? "Activate Demo Subscription" : "Subscribe / Start Trial");
        Button back = secondaryButton("Back to Accounts");
        card.addView(subscribe);
        card.addView(back);

        subscribe.setOnClickListener(v -> {
            if (api.isDemoMode()) {
                api.verifyPurchase(token(), BuildConfig.SUBSCRIPTION_PRODUCT_ID, "demo-purchase", new UiCallback<ApiClient.AccountState>() {
                    @Override
                    public void success(ApiClient.AccountState value) {
                        accountState = value;
                        session.save(value);
                        showSubscription();
                    }
                });
            } else if (billing != null) {
                billing.launchSubscription(this, session.accountKey());
            }
        });
        back.setOnClickListener(v -> showAccounts());
    }

    private LinearLayout baseScreen(String title, String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(16), dp(18), dp(16), dp(22));
        scroll.addView(outer, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(2), 0, dp(2), dp(14));
        outer.addView(header);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(GOLD);
        titleView.setTextSize(30);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        header.addView(titleView, matchWrap());

        TextView subView = new TextView(this);
        subView.setText(subtitle == null ? "" : subtitle);
        subView.setTextColor(Color.rgb(206, 211, 218));
        subView.setTextSize(14);
        subView.setGravity(Gravity.CENTER_HORIZONTAL);
        subView.setPadding(0, dp(4), 0, 0);
        header.addView(subView, matchWrap());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        outer.addView(body, matchWrap());
        setContentView(scroll);
        return body;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(PANEL, dp(10), 0, 0));
        card.setLayoutParams(blockParams());
        return card;
    }

    private LinearLayout loadingCard(String message) {
        LinearLayout card = card();
        card.addView(line(message));
        return card;
    }

    private LinearLayout infoCard(String title, String message) {
        LinearLayout card = card();
        card.setBackground(rounded(PANEL_DARK, dp(10), 0, 0));
        TextView heading = sectionTitle(title);
        heading.setTextColor(GOLD);
        TextView body = line(message);
        body.setTextColor(Color.rgb(222, 226, 232));
        card.addView(heading);
        card.addView(body);
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(TEXT);
        text.setTextSize(18);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setPadding(0, 0, 0, dp(8));
        return text;
    }

    private TextView label(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(MUTED);
        text.setTextSize(13);
        text.setPadding(0, dp(8), 0, 0);
        return text;
    }

    private TextView line(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(TEXT);
        text.setTextSize(14);
        text.setLineSpacing(dp(3), 1.0f);
        text.setPadding(0, dp(2), 0, dp(2));
        return text;
    }

    private TextView infoText(String value) {
        TextView text = line(value);
        text.setTextColor(MUTED);
        text.setPadding(0, dp(10), 0, dp(10));
        return text;
    }

    private TextView statusText(String value, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(12), dp(7), dp(12), dp(7));
        text.setBackground(rounded(color, dp(18), 0, 0));
        LinearLayout.LayoutParams params = wrapParams();
        params.setMargins(0, dp(4), 0, dp(8));
        text.setLayoutParams(params);
        return text;
    }

    private LinearLayout twoStatRow(String leftLabel, String leftValue, String rightLabel, String rightValue, int leftColor, int rightColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(statBlock(leftLabel, leftValue, leftColor), weightedParams());
        row.addView(statBlock(rightLabel, rightValue, rightColor), weightedParams());
        return row;
    }

    private LinearLayout statBlock(String label, String value, int valueColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(rounded(Color.rgb(235, 238, 242), dp(8), 0, 0));
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(MUTED);
        labelView.setTextSize(12);
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(valueColor);
        valueView.setTextSize(18);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setPadding(0, dp(4), 0, 0);
        box.addView(labelView);
        box.addView(valueView);
        return box;
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextColor(TEXT);
        editText.setHintTextColor(Color.rgb(128, 134, 143));
        editText.setTextSize(16);
        editText.setPadding(dp(12), dp(8), dp(12), dp(8));
        editText.setLayoutParams(matchWrapWithMargin(0, dp(5), 0, dp(5)));
        return editText;
    }

    private Button primaryButton(String text) {
        return styledButton(text, GOLD, Color.rgb(16, 18, 22), true);
    }

    private Button secondaryButton(String text) {
        return styledButton(text, Color.rgb(224, 227, 232), TEXT, false);
    }

    private Button dangerButton(String text) {
        return styledButton(text, RED, Color.WHITE, true);
    }

    private Button dangerOutlineButton(String text) {
        return styledButton(text, Color.rgb(248, 229, 229), RED, true);
    }

    private Button textButton(String text) {
        Button button = styledButton(text, Color.TRANSPARENT, GOLD, false);
        return button;
    }

    private Button styledButton(String text, int bg, int fg, boolean bold) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(fg);
        button.setTextSize(15);
        button.setAllCaps(false);
        if (bold) {
            button.setTypeface(Typeface.DEFAULT_BOLD);
        }
        button.setBackground(rounded(bg, dp(8), 0, 0));
        button.setPadding(dp(10), dp(9), dp(10), dp(9));
        button.setLayoutParams(matchWrapWithMargin(0, dp(7), 0, dp(7)));
        return button;
    }

    private GradientDrawable rounded(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams blockParams() {
        return matchWrapWithMargin(0, dp(7), 0, dp(7));
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithMargin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams weightedParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private String token() {
        return accountState == null ? session.token() : accountState.token;
    }

    private boolean hasAccess() {
        if (accountState == null) {
            accountState = session.state();
        }
        return accountState.hasAccess();
    }

    private void scheduleDashboardRefresh(String accountId) {
        stopAutoRefresh();
        refreshTask = () -> {
            if ("dashboard".equals(currentScreen) && accountId.equals(selectedAccountId)) {
                api.loadDashboard(token(), accountId, new UiCallback<ApiClient.DashboardState>() {
                    @Override
                    public void success(ApiClient.DashboardState value) {
                        if ("dashboard".equals(currentScreen) && accountId.equals(selectedAccountId)) {
                            renderDashboard(value);
                            scheduleDashboardRefresh(accountId);
                        }
                    }
                });
            }
        };
        main.postDelayed(refreshTask, 5000);
    }

    private void stopAutoRefresh() {
        if (refreshTask != null) {
            main.removeCallbacks(refreshTask);
            refreshTask = null;
        }
    }

    private String money(double value) {
        return String.format(Locale.US, "$%.2f", value);
    }

    private String price(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private int moneyColor(double value) {
        if (value > 0.0) {
            return GREEN;
        }
        if (value < 0.0) {
            return RED;
        }
        return TEXT;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
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
