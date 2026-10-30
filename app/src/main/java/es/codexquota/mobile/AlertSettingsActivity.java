package es.codexquota.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import es.codexquota.mobile.core.AlertPolicy;
import java.util.HashMap;
import java.util.Map;

/** The ordinary settings stay visible; test tools and diagnostics expand on demand. */
public final class AlertSettingsActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, TextView> summaries = new HashMap<>();
    private final Map<Integer, TextView> diagnostics = new HashMap<>();
    private final boolean[] available = new boolean[4];
    private int ink, muted, surface, border, buttonColor;
    private int expandedMask;
    private boolean registered, lowDirty, expiryDirty, tracking;
    private LinearLayout page;
    private SeekBar lowSlider, expirySlider;
    private TextView lowValue, expiryValue, permissionStatus;
    private final Runnable commitValues = () -> commitPending(true);
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateAvailability(); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) expandedMask = saved.getInt("expanded", 0);
        QuotaAlerts.ensureChannels(this);
        for (int kind = 1; kind <= 3; kind++) available[kind] = QuotaAlerts.categoryEnabled(this, kind);
        render();
        updateAvailability();
    }

    @Override public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(QuotaRepository.CHANGED);
        ContextCompat.registerReceiver(this, updates, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
        boolean regained = refreshPermissions();
        QuotaAlerts.rescheduleKnown(this);
        updateAvailability();
        if (regained) refreshIfEnabled();
    }

    @Override public void onPause() {
        if (registered) unregisterReceiver(updates);
        registered = false;
        handler.removeCallbacks(commitValues);
        commitPending(true);
        tracking = false;
        super.onPause();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(commitValues);
        super.onDestroy();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putInt("expanded", expandedMask);
        super.onSaveInstanceState(state);
    }

    private void render() {
        boolean dark = (getResources().getConfiguration().uiMode & 48) == 32;
        ink = dark ? Color.rgb(245, 245, 245) : Color.rgb(24, 24, 24);
        muted = dark ? Color.rgb(175, 175, 175) : Color.rgb(90, 90, 90);
        surface = dark ? Color.rgb(32, 32, 32) : Color.WHITE;
        border = dark ? Color.rgb(58, 58, 58) : Color.rgb(229, 229, 229);
        buttonColor = dark ? Color.rgb(48, 48, 48) : Color.rgb(237, 237, 237);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(dark ? Color.rgb(23, 23, 23) : Color.rgb(247, 247, 247));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(page);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        page.addView(button("‹ Volver", this::finish));
        TextView brand = text("›_  CODEX BETA", 14, ink, true);
        brand.setTypeface(Typeface.MONOSPACE);
        margin(brand, 18);
        page.addView(brand);
        TextView title = text("Avisos", 28, ink, true);
        margin(title, 16);
        page.addView(title);
        permissionStatus = text("Android bloquea los avisos. Activa uno para dar permiso o abre los ajustes.", 14, muted, false);
        margin(permissionStatus, 12);
        page.addView(permissionStatus);

        AlertPolicy.Config settings = QuotaAlerts.config(this);
        LinearLayout low = categoryCard("Cuota baja", settings.lowEnabled, 1);
        lowValue = text("", 24, ink, true);
        lowValue.setTypeface(Typeface.MONOSPACE);
        margin(lowValue, 12);
        low.addView(lowValue);
        lowSlider = slider(1, 100, settings.lowThresholdPercent, 1);
        low.addView(lowSlider, new LinearLayout.LayoutParams(-1, dp(48)));
        low.addView(endpoints("1 % restante", "100 % restante"));
        addDetails(low, 1);
        page.addView(low);

        LinearLayout expiry = categoryCard("Resets que van a caducar", settings.expiryEnabled, 2);
        expiryValue = text("", 24, ink, true);
        expiryValue.setTypeface(Typeface.MONOSPACE);
        margin(expiryValue, 12);
        expiry.addView(expiryValue);
        expirySlider = slider(1, 168, settings.expiryHours, 2);
        expiry.addView(expirySlider, new LinearLayout.LayoutParams(-1, dp(48)));
        expiry.addView(endpoints("1 hora antes", "7 días antes"));
        TextView note = text("También se programa con las fechas conocidas, aunque no toque consultar. Android puede retrasarlo.", 13, muted, false);
        margin(note, 10);
        expiry.addView(note);
        addDetails(expiry, 2);
        page.addView(expiry);

        LinearLayout recovery = categoryCard("Cuota recuperada", QuotaAlerts.recoveryEnabled(this), 3);
        TextView description = text("Un aviso cuando una consulta confirme que puedes volver a usar Codex después de un bloqueo de cuota.", 14, muted, false);
        margin(description, 10);
        recovery.addView(description);
        addDetails(recovery, 3);
        page.addView(recovery);
        page.addView(button("Permisos de Android", () -> openSettings(0)));
        TextView footer = text("Los avisos no consumen resets. Las consultas mantienen la frecuencia que has elegido.", 13, muted, false);
        margin(footer, 20);
        page.addView(footer);
        updateSliderLabels();
    }

    private LinearLayout categoryCard(String title, boolean checked, int kind) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(18));
        GradientDrawable background = background(surface, 14);
        background.setStroke(dp(1), border);
        card.setBackground(background);
        margin(card, 14);
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextColor(ink);
        toggle.setTextSize(17);
        toggle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toggle.setMinHeight(dp(52));
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            handler.removeCallbacks(commitValues);
            commitPending(false);
            QuotaAlerts.setEnabled(this, kind, enabled);
            if (enabled && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST);
            } else if (enabled) refreshIfEnabled();
            updateAvailability();
        });
        card.addView(toggle);
        TextView summary = text("", 13, muted, false);
        summaries.put(kind, summary);
        card.addView(summary);
        return card;
    }

    private void addDetails(LinearLayout card, int kind) {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        TextView diagnostic = text("", 13, muted, false);
        margin(diagnostic, 12);
        diagnostics.put(kind, diagnostic);
        details.addView(diagnostic);
        details.addView(button("Probar este aviso", () -> {
            handler.removeCallbacks(commitValues);
            commitPending(false);
            Toast.makeText(this, QuotaAlerts.sendTest(this, kind), Toast.LENGTH_LONG).show();
            updateAvailability();
        }));
        details.addView(button("Ajustes de este aviso", () -> openSettings(kind)));
        boolean expanded = (expandedMask & (1 << kind)) != 0;
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        Button expand = button(expanded ? "Ocultar detalles" : "Detalles y prueba", () -> {});
        expand.setOnClickListener(view -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            expandedMask = show ? expandedMask | (1 << kind) : expandedMask & ~(1 << kind);
            expand.setText(show ? "Ocultar detalles" : "Detalles y prueba");
        });
        card.addView(expand);
        card.addView(details);
    }

    private SeekBar slider(int min, int max, int value, int kind) {
        SeekBar slider = new SeekBar(this);
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgress(value);
        slider.setKeyProgressIncrement(1);
        slider.setProgressTintList(ColorStateList.valueOf(ink));
        slider.setThumbTintList(ColorStateList.valueOf(ink));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(border));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean user) {
                if (lowSlider == null || expirySlider == null) return;
                updateSliderLabels();
                if (!user) return;
                if (kind == 1) lowDirty = true; else expiryDirty = true;
                handler.removeCallbacks(commitValues);
                if (!tracking) handler.postDelayed(commitValues, 350);
            }
            @Override public void onStartTrackingTouch(SeekBar view) {
                tracking = true;
                handler.removeCallbacks(commitValues);
            }
            @Override public void onStopTrackingTouch(SeekBar view) {
                tracking = false;
                handler.removeCallbacks(commitValues);
                commitPending(true);
            }
        });
        return slider;
    }

    private void updateSliderLabels() {
        if (lowSlider == null || expirySlider == null) return;
        int percent = lowSlider.getProgress(), hours = expirySlider.getProgress();
        lowValue.setText(percent + " % restante o menos");
        expiryValue.setText(hours + (hours == 1 ? " hora antes" : " horas antes"));
        lowSlider.setContentDescription("Cuota restante para avisar: " + percent + " por ciento");
        expirySlider.setContentDescription("Antelación del aviso de reset: " + hours + " horas");
    }

    private void commitPending(boolean refresh) {
        if (lowSlider == null || expirySlider == null) return;
        AlertPolicy.Config settings = QuotaAlerts.config(this);
        boolean lowChanged = lowDirty && lowSlider.getProgress() != settings.lowThresholdPercent;
        boolean expiryChanged = expiryDirty && expirySlider.getProgress() != settings.expiryHours;
        if (lowChanged) QuotaAlerts.setThreshold(this, lowSlider.getProgress());
        if (expiryChanged) QuotaAlerts.setExpiryHours(this, expirySlider.getProgress());
        lowDirty = expiryDirty = false;
        updateAvailability();
        if (refresh && ((lowChanged && settings.lowEnabled) || (expiryChanged && settings.expiryEnabled))) refreshIfEnabled();
    }

    private boolean refreshPermissions() {
        boolean regained = false;
        AlertPolicy.Config settings = QuotaAlerts.config(this);
        for (int kind = 1; kind <= 3; kind++) {
            boolean current = QuotaAlerts.categoryEnabled(this, kind);
            boolean enabled = kind == 1 ? settings.lowEnabled : kind == 2 ? settings.expiryEnabled : QuotaAlerts.recoveryEnabled(this);
            if (current && !available[kind] && enabled) {
                // Keep the recovery transition armed when restoring a permission.
                if (kind != 3) QuotaAlerts.rearm(this, kind);
                regained = true;
            }
            available[kind] = current;
        }
        return regained;
    }

    private void updateAvailability() {
        if (permissionStatus == null) return;
        permissionStatus.setVisibility(QuotaAlerts.systemEnabled(this) ? View.GONE : View.VISIBLE);
        for (int kind = 1; kind <= 3; kind++) {
            TextView summary = summaries.get(kind), diagnostic = diagnostics.get(kind);
            if (summary != null) summary.setText(QuotaAlerts.compactStatus(this, kind));
            if (diagnostic != null) diagnostic.setText(QuotaAlerts.checkStatus(this, kind));
        }
    }

    private void refreshIfEnabled() {
        AlertPolicy.Config settings = QuotaAlerts.config(this);
        QuotaRepository repository = QuotaRepository.get(this);
        if ((settings.lowEnabled || settings.expiryEnabled || QuotaAlerts.recoveryEnabled(this))
                && QuotaAlerts.systemEnabled(this) && repository.signedIn()) repository.refresh(null);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == PERMISSION_REQUEST) {
            boolean regained = refreshPermissions();
            QuotaAlerts.rescheduleKnown(this);
            updateAvailability();
            if (regained) refreshIfEnabled();
        }
    }

    private void openSettings(int kind) {
        try { startActivity(kind == 0 ? QuotaAlerts.androidSettings(this) : QuotaAlerts.androidChannelSettings(this, kind)); }
        catch (RuntimeException error) { Toast.makeText(this, "Abre Ajustes → Aplicaciones → Cuota Codex Beta → Notificaciones.", Toast.LENGTH_LONG).show(); }
    }

    private LinearLayout endpoints(String left, String right) {
        LinearLayout row = new LinearLayout(this);
        TextView start = text(left, 12, muted, false), end = text(right, 12, muted, false);
        end.setGravity(android.view.Gravity.END);
        row.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(end, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setLineSpacing(dp(3), 1);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title); button.setTextColor(ink); button.setTextSize(15); button.setAllCaps(false);
        button.setMinHeight(dp(48)); button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setBackground(background(buttonColor, 10)); margin(button, 12);
        button.setOnClickListener(view -> action.run());
        return button;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private void margin(View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top); view.setLayoutParams(params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
