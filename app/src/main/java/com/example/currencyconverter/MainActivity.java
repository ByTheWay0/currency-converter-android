package com.example.currencyconverter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String[] CODES = {"RUB", "USD", "EUR", "VND"};
    private static final String[] NAMES = {"Российский рубль", "Доллар США", "Евро", "Вьетнамский донг"};
    private static final String PREFS = "exchange_rates";
    private static final String RATES_KEY = "rates";
    private static final String UPDATED_KEY = "updated";
    private static final String SOURCE_KEY = "source";
    private static final String ADJUSTMENT_KEY = "adjustment_percent";
    private static final String RATE_SOURCE = "fxapi.app";
    private static final long REFRESH_INTERVAL_MS = 5L * 60L * 1000L;
    private static final int NAVY = Color.rgb(22, 33, 68);
    private static final int BLUE = Color.rgb(49, 87, 213);
    private static final int MUTED = Color.rgb(112, 123, 147);
    private static final int BG = Color.rgb(245, 247, 252);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Double> rates = new HashMap<>();
    private EditText amountInput;
    private Spinner fromSpinner;
    private Spinner toSpinner;
    private TextView resultValue;
    private TextView rateLabel;
    private TextView updateLabel;
    private TextView errorLabel;
    private TextView lastUpdatedLabel;
    private boolean ratesReady;
    private boolean isVisible;
    private boolean fetchInProgress;
    private double adjustmentPercent;
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (!isVisible) return;
            fetchRates();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildScreen();
        loadCachedRates();
        adjustmentPercent = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(ADJUSTMENT_KEY, 0f);
        updateConversion();
    }

    @Override
    protected void onStart() {
        super.onStart();
        isVisible = true;
        refreshHandler.removeCallbacks(refreshRunnable);
        fetchRates();
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        isVisible = false;
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onStop();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(24), dp(24), dp(28));
        scroll.addView(page);

        TextView eyebrow = label("КОНВЕРТЕР ВАЛЮТ", 12, BLUE, true);
        eyebrow.setLetterSpacing(0.12f);
        page.addView(eyebrow);
        TextView heading = label("Актуальный курс", 28, NAVY, true);
        LinearLayout.LayoutParams hp = params(-2, -2);
        hp.topMargin = dp(8);
        page.addView(heading, hp);
        TextView subtitle = label("Обновляется при открытии и каждые 5 минут", 14, MUTED, false);
        LinearLayout.LayoutParams sp = params(-2, -2);
        sp.topMargin = dp(6);
        page.addView(subtitle, sp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(rounded(Color.WHITE, dp(22), Color.rgb(231, 235, 244)));
        LinearLayout.LayoutParams cp = params(-1, -2);
        cp.topMargin = dp(28);
        page.addView(card, cp);

        card.addView(label("У меня есть", 13, MUTED, true));
        amountInput = new EditText(this);
        amountInput.setSingleLine(true);
        amountInput.setText("100");
        amountInput.setTextSize(28);
        amountInput.setTextColor(NAVY);
        amountInput.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        amountInput.setSelectAllOnFocus(true);
        amountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        amountInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        amountInput.setPadding(0, dp(8), 0, dp(8));
        amountInput.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams ap = params(-1, dp(58));
        card.addView(amountInput, ap);
        fromSpinner = currencySpinner();
        card.addView(fromSpinner, params(-1, dp(52)));

        TextView swap = label("⇅", 26, BLUE, true);
        swap.setGravity(Gravity.CENTER);
        swap.setBackground(rounded(Color.rgb(239, 243, 255), dp(24), Color.TRANSPARENT));
        LinearLayout.LayoutParams swp = params(dp(48), dp(48));
        swp.gravity = Gravity.CENTER_HORIZONTAL;
        swp.topMargin = dp(16);
        swp.bottomMargin = dp(16);
        card.addView(swap, swp);
        swap.setOnClickListener(v -> {
            int from = fromSpinner.getSelectedItemPosition();
            int to = toSpinner.getSelectedItemPosition();
            fromSpinner.setSelection(to);
            toSpinner.setSelection(from);
        });

        card.addView(label("Я получу", 13, MUTED, true));
        resultValue = label("—", 32, BLUE, true);
        LinearLayout.LayoutParams rp = params(-1, -2);
        rp.topMargin = dp(8);
        card.addView(resultValue, rp);
        toSpinner = currencySpinner();
        toSpinner.setSelection(1);
        LinearLayout.LayoutParams tsp = params(-1, dp(52));
        tsp.topMargin = dp(8);
        card.addView(toSpinner, tsp);

        rateLabel = label("Курс загрузится автоматически", 14, NAVY, true);
        LinearLayout.LayoutParams rlp = params(-1, -2);
        rlp.topMargin = dp(20);
        page.addView(rateLabel, rlp);
        updateLabel = label("Подключаемся к источнику курсов…", 12, MUTED, false);
        LinearLayout.LayoutParams ulp = params(-1, -2);
        ulp.topMargin = dp(6);
        page.addView(updateLabel, ulp);
        errorLabel = label("", 12, MUTED, false);
        LinearLayout.LayoutParams elp = params(-1, -2);
        elp.topMargin = dp(8);
        page.addView(errorLabel, elp);

        TextView refresh = label("↻   Обновить курс", 14, BLUE, true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(rounded(Color.rgb(232, 238, 255), dp(16), Color.TRANSPARENT));
        LinearLayout.LayoutParams fp = params(-1, dp(52));
        fp.topMargin = dp(22);
        page.addView(refresh, fp);
        refresh.setOnClickListener(v -> fetchRates());

        lastUpdatedLabel = label("Последнее обновление курса: пока нет данных", 12, MUTED, false);
        lastUpdatedLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lastUpdatedParams = params(-1, -2);
        lastUpdatedParams.topMargin = dp(20);
        page.addView(lastUpdatedLabel, lastUpdatedParams);

        TextView attribution = label("Источник: <a href=\"https://fxapi.app\">fxapi.app</a>", 12, MUTED, false);
        attribution.setText(android.text.Html.fromHtml("Источник: <a href=\"https://fxapi.app\">fxapi.app</a>", android.text.Html.FROM_HTML_MODE_LEGACY));
        attribution.setGravity(Gravity.CENTER);
        attribution.setMovementMethod(LinkMovementMethod.getInstance());
        LinearLayout.LayoutParams attributionParams = params(-1, -2);
        attributionParams.topMargin = dp(5);
        page.addView(attribution, attributionParams);

        ImageButton settingsButton = new ImageButton(this);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setContentDescription("Настройки поправки курса");
        settingsButton.setBackground(rounded(Color.rgb(232, 238, 255), dp(16), Color.TRANSPARENT));
        settingsButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams settingsParams = params(dp(48), dp(48));
        settingsParams.topMargin = dp(18);
        page.addView(settingsButton, settingsParams);
        settingsButton.setOnClickListener(v -> showAdjustmentSettings());

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateConversion(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        amountInput.addTextChangedListener(watcher);
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateConversion(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        fromSpinner.setOnItemSelectedListener(listener);
        toSpinner.setOnItemSelectedListener(listener);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                page.setPadding(dp(24), dp(24) + insets.getSystemWindowInsetTop(), dp(24), dp(28) + insets.getSystemWindowInsetBottom());
                return insets;
            });
            scroll.requestApplyInsets();
        }
    }

    private Spinner currencySpinner() {
        String[] labels = new String[CODES.length];
        for (int i = 0; i < CODES.length; i++) labels[i] = CODES[i] + "   ·   " + NAMES[i];
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(NAVY);
                view.setTextSize(15);
                view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                view.setPadding(dp(14), dp(8), dp(14), dp(8));
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(NAVY);
                view.setTextSize(15);
                view.setPadding(dp(16), dp(14), dp(16), dp(14));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(adapter);
        spinner.setBackground(rounded(Color.rgb(247, 248, 252), dp(14), Color.rgb(232, 235, 242)));
        return spinner;
    }

    private void loadCachedRates() {
        android.content.SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!RATE_SOURCE.equals(preferences.getString(SOURCE_KEY, ""))) return;
        String json = preferences.getString(RATES_KEY, null);
        if (json == null) return;
        try {
            JSONObject object = new JSONObject(json);
            for (String code : CODES) if (object.has(code)) rates.put(code, object.getDouble(code));
            ratesReady = rates.size() == CODES.length;
            updateConversion();
            long timestamp = preferences.getLong(UPDATED_KEY, 0);
            showLastUpdated(timestamp);
            updateLabel.setText("Используем сохранённый курс, обновляем онлайн");
        } catch (Exception ignored) { }
    }

    private void fetchRates() {
        if (!isVisible || fetchInProgress) return;
        fetchInProgress = true;
        errorLabel.setText("");
        updateLabel.setText("Обновляем курс онлайн…");
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://fxapi.app/api/usd.json").openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/json");
                if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
                java.io.InputStream input = connection.getInputStream();
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                input.close();
                JSONObject response = new JSONObject(output.toString("UTF-8"));
                if (!"USD".equalsIgnoreCase(response.optString("base"))) throw new Exception("Источник курса вернул неверную базовую валюту");
                JSONObject remote = response.getJSONObject("rates");
                JSONObject saved = new JSONObject();
                Map<String, Double> fetched = new HashMap<>();
                for (String code : CODES) {
                    double value = "USD".equals(code) ? 1d : remote.getDouble(code);
                    if (value <= 0) throw new Exception("Некорректный курс " + code);
                    fetched.put(code, value);
                    saved.put(code, value);
                }
                long timestamp = java.time.Instant.parse(response.getString("timestamp")).toEpochMilli();
                runOnUiThread(() -> {
                    fetchInProgress = false;
                    if (!isVisible) return;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(RATES_KEY, saved.toString())
                            .putLong(UPDATED_KEY, timestamp)
                            .putString(SOURCE_KEY, RATE_SOURCE)
                            .apply();
                    rates.clear();
                    rates.putAll(fetched);
                    ratesReady = true;
                    updateConversion();
                    showLastUpdated(timestamp);
                    updateLabel.setText("Курс обновляется каждые 5 минут, пока приложение открыто");
                    errorLabel.setText("");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    fetchInProgress = false;
                    if (!isVisible) return;
                    updateLabel.setText(ratesReady ? "Показан последний сохранённый курс" : "Нет сохранённых курсов");
                    errorLabel.setText("Не удалось загрузить курс. Проверьте подключение к интернету.");
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void updateConversion() {
        if (amountInput == null || fromSpinner == null || toSpinner == null || resultValue == null) return;
        String raw = amountInput.getText().toString().trim().replace(',', '.');
        double amount;
        try {
            amount = Double.parseDouble(raw);
            if (amount < 0 || !Double.isFinite(amount)) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            resultValue.setText("—");
            rateLabel.setText("Введите сумму больше или равную нулю");
            return;
        }
        String from = CODES[fromSpinner.getSelectedItemPosition()];
        String to = CODES[toSpinner.getSelectedItemPosition()];
        if (!ratesReady) {
            resultValue.setText("Загрузка…");
            rateLabel.setText("Курс загружается");
            return;
        }
        double exchange = rates.get(to) / rates.get(from);
        exchange *= 1d + adjustmentPercent / 100d;
        resultValue.setText(format(amount * exchange, to) + " " + to);
        String adjustment = adjustmentPercent == 0d ? "" : " · поправка " + formatPercent(adjustmentPercent) + "%";
        rateLabel.setText("1 " + from + " = " + format(exchange, to) + " " + to + adjustment);
    }

    private void showLastUpdated(long timestamp) {
        if (lastUpdatedLabel == null) return;
        String value = timestamp > 0
                ? new java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault()).format(new java.util.Date(timestamp))
                : "пока нет данных";
        lastUpdatedLabel.setText("Последнее обновление курса: " + value);
    }

    private void showAdjustmentSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), 0);
        TextView helper = label("Поправка применяется к курсу. Допустимо от −100% до +100%. Ноль — без поправки.", 14, MUTED, false);
        content.addView(helper, params(-1, -2));

        EditText percentInput = new EditText(this);
        percentInput.setSingleLine(true);
        percentInput.setHint("Например, 3");
        percentInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        percentInput.setText(formatPercent(adjustmentPercent));
        percentInput.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams inputParams = params(-1, dp(56));
        inputParams.topMargin = dp(12);
        content.addView(percentInput, inputParams);

        TextView validation = label("", 12, Color.rgb(190, 45, 45), false);
        LinearLayout.LayoutParams validationParams = params(-1, -2);
        validationParams.topMargin = dp(4);
        content.addView(validation, validationParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Поправка курса")
                .setView(content)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = percentInput.getText().toString().trim().replace(',', '.');
            double value;
            try {
                value = raw.isEmpty() ? 0d : Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                validation.setText("Введите число от −100 до +100.");
                return;
            }
            if (!Double.isFinite(value) || value < -100d || value > 100d) {
                validation.setText("Значение должно быть от −100 до +100.");
                return;
            }
            adjustmentPercent = value;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(ADJUSTMENT_KEY, (float) value).apply();
            updateConversion();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private String formatPercent(double value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.getDefault());
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("0.##", symbols);
        return (value > 0 ? "+" : "") + format.format(value);
    }

    private String format(double value, String code) {
        int digits = "VND".equals(code) ? 0 : (value >= 1000 ? 2 : 4);
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.getDefault());
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        String decimals = digits == 4 ? ".0000" : (digits == 2 ? ".00" : "");
        DecimalFormat format = new DecimalFormat("#,##0" + decimals, symbols);
        format.setGroupingSize(3);
        return format.format(value);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        executor.shutdownNow();
    }
}
