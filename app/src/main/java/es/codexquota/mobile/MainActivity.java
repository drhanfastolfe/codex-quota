package es.codexquota.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import es.codexquota.mobile.core.ApiException;
import es.codexquota.mobile.core.DeviceLogin;
import es.codexquota.mobile.core.PendingReset;
import es.codexquota.mobile.core.QuotaPresentation;
import es.codexquota.mobile.core.QuotaSnapshot;
import es.codexquota.mobile.core.QuotaWindow;
import es.codexquota.mobile.core.RefreshPolicy;
import es.codexquota.mobile.core.ResetCredit;
import es.codexquota.mobile.core.ResetCredits;
import java.util.List;

/** Native screen shared by launcher and widget entry points. */
public final class MainActivity extends Activity {
    public static final String SHOW_RESET = "es.codexquota.mobile.SHOW_RESET";

    private QuotaRepository repo;
    private LinearLayout page;
    private ScrollView pageScroll;
    private AlertDialog resetDialog;
    private boolean pendingResetDialog;
    private boolean registered;
    private boolean resumed;
    private int ink, muted, surface, border, buttonColor, primaryText, warning, critical;
    private final Handler displayHandler = new Handler(Looper.getMainLooper());
    private final Runnable displayTick = new Runnable() {
        @Override public void run() {
            if (!resumed || isFinishing() || isDestroyed()) return;
            render();
            displayHandler.postDelayed(this, 60_000L);
        }
    };
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repo = QuotaRepository.get(this);
        pendingResetDialog = SHOW_RESET.equals(getIntent().getAction());
        render();
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (SHOW_RESET.equals(intent.getAction())) {
            pendingResetDialog = true;
            render();
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (!registered) {
            ContextCompat.registerReceiver(this, updates, new IntentFilter(QuotaRepository.CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
        resumed = true;
        displayHandler.removeCallbacks(displayTick);
        displayHandler.postDelayed(displayTick, 60_000L);
        repo.resumeLogin();
        render();
        repo.schedulePeriodic();
        repo.refreshIfStale();
    }

    @Override public void onPause() {
        if (registered) unregisterReceiver(updates);
        registered = false;
        resumed = false;
        displayHandler.removeCallbacks(displayTick);
        super.onPause();
    }

    @Override public void onDestroy() {
        displayHandler.removeCallbacks(displayTick);
        if (resetDialog != null) resetDialog.dismiss();
        super.onDestroy();
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        final int scrollPosition = pageScroll == null ? 0 : pageScroll.getScrollY();
        boolean dark = (getResources().getConfiguration().uiMode & 48) == 32;
        ink = dark ? Color.rgb(245, 245, 245) : Color.rgb(24, 24, 24);
        muted = dark ? Color.rgb(177, 177, 177) : Color.rgb(96, 96, 96);
        surface = dark ? Color.rgb(32, 32, 32) : Color.WHITE;
        border = dark ? Color.rgb(58, 58, 58) : Color.rgb(229, 229, 229);
        buttonColor = dark ? Color.rgb(48, 48, 48) : Color.rgb(237, 237, 237);
        primaryText = dark ? Color.rgb(24, 24, 24) : Color.WHITE;
        warning = dark ? Color.rgb(230, 180, 86) : Color.rgb(137, 89, 8);
        critical = dark ? Color.rgb(255, 143, 136) : Color.rgb(174, 46, 42);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(dark ? Color.rgb(23, 23, 23) : Color.rgb(247, 247, 247));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page);
        setContentView(scroll);
        pageScroll = scroll;
        scroll.post(() -> scroll.scrollTo(0, scrollPosition));
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            scroll.requestApplyInsets();
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView brand = text("›_  CODEX · BETA", 14, ink, true);
        brand.setTypeface(Typeface.MONOSPACE);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        Button menu = button("⋯", false, () -> {});
        menu.setTextSize(24);
        menu.setContentDescription("Abrir menú de ajustes");
        menu.setPadding(0, 0, 0, 0);
        menu.setOnClickListener(this::showMenu);
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);
        TextView title = text("Tu cuota", 28, ink, true);
        heading(title);
        margin(title, 12, 0);
        page.addView(title);

        if (repo.signingIn()) renderChallenge();
        else if (repo.signedIn()) renderQuota();
        else renderSignedOut();

        if (!repo.error().isEmpty()) {
            LinearLayout error = card();
            error.addView(text(repo.error(), 14, warning, false));
            if (repo.signedIn()) {
                Button retry = button("Volver a consultar", false, () -> repo.refresh(null));
                retry.setEnabled(!repo.busy() && !repo.consuming());
                error.addView(retry);
            }
            page.addView(error);
        }
        if (pendingResetDialog && !repo.signingIn() && !repo.busy() && !repo.consuming()) {
            page.post(() -> {
                if (pendingResetDialog && !repo.busy() && !repo.consuming()
                        && !isFinishing() && !isDestroyed()) {
                    pendingResetDialog = false;
                    confirmReset();
                }
            });
        }
    }

    private void renderSignedOut() {
        LinearLayout card = card();
        card.addView(text("Conecta tu cuenta", 21, ink, true));
        TextView description = text("Autoriza este móvil con un código en la página de OpenAI y vuelve aquí. No necesitas un ordenador.", 15, muted, false);
        margin(description, 10, 4);
        card.addView(description);
        card.addView(button("Iniciar sesión con ChatGPT", true, () -> repo.beginLogin()));
        page.addView(card);
    }

    private void renderChallenge() {
        LinearLayout card = card();
        card.addView(text("Autoriza este móvil", 21, ink, true));
        DeviceLogin.Challenge challenge = repo.challenge();
        if (challenge == null) {
            card.addView(text("Preparando el código de acceso…", 15, muted, false));
            ProgressBar progress = new ProgressBar(this);
            margin(progress, 16, 12);
            card.addView(progress);
        } else {
            TextView steps = text("1. Copia el código.\n2. Abre OpenAI y autorízalo.\n3. Vuelve aquí para completar el acceso.", 15, muted, false);
            margin(steps, 12, 12);
            card.addView(steps);
            TextView code = text(challenge.userCode, 29, ink, true);
            code.setTextIsSelectable(true);
            code.setTypeface(Typeface.MONOSPACE);
            card.addView(code);
            card.addView(button("Copiar código", false, () -> copyCode(challenge.userCode)));
            card.addView(button("Abrir OpenAI en el navegador", true, this::openAuthorization));
            TextView status = text(repo.loginStatus() + "\nCódigo válido hasta " + Formatters.date(challenge.expiresAtMillis), 13, muted, false);
            margin(status, 12, 0);
            card.addView(status);
            card.addView(button("Ya he autorizado · comprobar", false, () -> repo.resumeLogin()));
        }
        card.addView(button("Cancelar acceso", false, () -> repo.cancelLogin()));
        page.addView(card);
    }

    private void copyCode(String code) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText("Código de acceso a Codex", code);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        toast("Código copiado");
    }

    private void openAuthorization() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://auth.openai.com/codex/device")));
        } catch (Exception ignored) {
            toast("Abre auth.openai.com/codex/device en tu navegador.");
        }
    }

    private void renderQuota() {
        QuotaSnapshot snapshot = repo.snapshot();
        boolean stale = snapshot != null && QuotaPresentation.isStale(snapshot.fetchedAtMillis,
                System.currentTimeMillis(), repo.refreshIntervalMinutes());
        String status = repo.busy() ? "Actualizando…" : snapshot == null ? "Tu cuenta está conectada"
                : stale ? "Datos antiguos · actualiza para comprobar" : "Datos actualizados";
        TextView statusView = text(status, 14, stale ? warning : muted, stale);
        margin(statusView, 8, 0);
        page.addView(statusView);
        if (snapshot != null) {
            TextView consulted = text(Formatters.updated(snapshot.fetchedAtMillis) + " · "
                    + Formatters.date(snapshot.fetchedAtMillis), 13, muted, false);
            margin(consulted, 4, 0);
            page.addView(consulted);
            if (snapshot.plan != null) {
                TextView plan = text("Plan " + snapshot.plan, 13, muted, false);
                margin(plan, 4, 0);
                page.addView(plan);
            }
            if (Boolean.FALSE.equals(snapshot.ordinaryUsageAllowed)) {
                TextView blocked = text("La última consulta indica que el uso incluido está bloqueado.", 14, warning, false);
                margin(blocked, 12, 0);
                page.addView(blocked);
            }
            for (QuotaWindow window : snapshot.windows) renderWindow(window);
            if (snapshot.windows.isEmpty()) {
                LinearLayout empty = card();
                empty.addView(text("OpenAI no ha facilitado las ventanas de cuota para esta cuenta.", 15, muted, false));
                page.addView(empty);
            }
            renderResets(snapshot);
        } else if (repo.hasPendingReset()) {
            LinearLayout pending = card();
            pending.addView(text("Reset pendiente", 17, ink, true));
            pending.addView(text("Puedes comprobar la solicitud anterior conservando el mismo crédito.", 14, muted, false));
            pending.addView(resetButton("Reintentar reset…", true));
            page.addView(pending);
        }
        Button update = button(repo.busy() ? "Actualizando…" : "Actualizar ahora", true, () -> repo.refresh(null));
        update.setEnabled(!repo.busy() && !repo.consuming());
        margin(update, 18, 0);
        page.addView(update);
    }

    private void renderWindow(QuotaWindow window) {
        LinearLayout card = card();
        TextView label = text(window.label, 16, muted, true);
        heading(label);
        card.addView(label);
        int level = QuotaPresentation.colorLevel(window.remainingPercent(), QuotaAlerts.visualWarningThreshold(this));
        int color = level == QuotaPresentation.CRITICAL ? critical : level == QuotaPresentation.WARNING ? warning : ink;
        TextView amount = text(Formatters.remaining(window) + " restante", 30, ink, true);
        margin(amount, 5, 0);
        card.addView(amount);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) Math.round(window.remainingPercent()));
        bar.setProgressTintList(ColorStateList.valueOf(color));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(border));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams barLayout = new LinearLayout.LayoutParams(-1, dp(6));
        barLayout.topMargin = dp(10);
        card.addView(bar, barLayout);
        if (level != QuotaPresentation.NORMAL) {
            TextView threshold = text(level == QuotaPresentation.CRITICAL ? "Cuota muy baja" : "Cuota baja", 13, color, true);
            margin(threshold, 7, 0);
            card.addView(threshold);
        }
        TextView renewal = text(Formatters.renewal(window), 14, muted, false);
        margin(renewal, 10, 0);
        card.addView(renewal);
        page.addView(card);
    }

    private void renderResets(QuotaSnapshot snapshot) {
        LinearLayout card = card();
        TextView label = text("Resets disponibles", 20, ink, true);
        heading(label);
        card.addView(label);
        ResetCredits credits = snapshot.resetCredits;
        long now = System.currentTimeMillis();
        long count = credits == null ? 0 : credits.displayAvailableCount(now, snapshot.fetchedAtMillis);
        TextView countView = text(credits == null ? "Sin datos" : count + " disponibles", 16, muted, false);
        margin(countView, 6, 0);
        card.addView(countView);
        if (credits != null) {
            List<ResetCredit> available = credits.availableByExpiry(now);
            for (int index = 0; index < available.size(); index++) {
                ResetCredit credit = available.get(index);
                TextView title = text(Formatters.creditTitle(credit) + (index == 0 ? " · próximo a usar" : ""), 15, ink, true);
                margin(title, 14, 3);
                card.addView(title);
                card.addView(text(Formatters.creditDate(credit), 14, muted, false));
                if (credit.grantedAtMillis != null) {
                    card.addView(text("Concedido " + Formatters.date(credit.grantedAtMillis), 13, muted, false));
                }
            }
            if (credits.missingDetailCount(snapshot.fetchedAtMillis) > 0) {
                TextView missing = text("Faltan fechas de algunos resets. Actualiza para obtener los detalles.", 13, muted, false);
                margin(missing, 10, 0);
                card.addView(missing);
            }
            if (credits.detailsError != null) card.addView(text(credits.detailsError, 13, warning, false));
        }
        boolean pending = repo.hasPendingReset();
        String action = repo.consuming() ? "Aplicando…" : pending ? "Reintentar reset…" : "Usar reset…";
        card.addView(resetButton(action, pending || count > 0));
        page.addView(card);
    }

    private Button resetButton(String label, boolean available) {
        Button action = button(label, false, this::confirmReset);
        action.setEnabled(available && !repo.busy() && !repo.consuming());
        return action;
    }

    /** The only UI path to consumeReset is the explicit positive dialog button below. */
    private void confirmReset() {
        if (resetDialog != null && resetDialog.isShowing()) return;
        if (!repo.signedIn()) {
            toast("Inicia sesión para usar un reset.");
            return;
        }
        if (repo.busy() || repo.consuming()) {
            pendingResetDialog = true;
            return;
        }
        try {
            final QuotaRepository.ResetApproval approval = repo.prepareReset();
            PendingReset request = approval.request;
            String explanation = approval.retry
                    ? "Se reintentará la misma solicitud pendiente, conservando el crédito elegido para evitar un doble canje."
                    : "Se usará 1 reset: el que caduca antes. OpenAI aplicará la renovación a los límites que permita restablecer.";
            if (approval.retry && request.creditId == null) {
                explanation = "Se comprobará la solicitud pendiente de la versión anterior. Se conserva su selección original para evitar un doble canje.";
            }
            String expiry = request.expiresAtMillis == null ? "Sin caducidad indicada"
                    : "Caduca " + Formatters.date(request.expiresAtMillis);
            String message = explanation + "\n\n" + (request.title == null ? "Reset de Codex" : request.title) + "\n" + expiry;
            String renewals = QuotaPresentation.renewalContext(repo.snapshot(), System.currentTimeMillis(), repo.refreshIntervalMinutes());
            if (!renewals.isEmpty()) message += "\n\n" + renewals;
            resetDialog = new AlertDialog.Builder(this)
                    .setTitle(approval.retry ? "¿Reintentar el reset?" : "¿Aplicar el reset?")
                    .setMessage(message)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton(approval.retry ? "Reintentar" : "Usar 1 reset", (dialog, which) ->
                            repo.consumeReset(approval, result -> runOnUiThread(() -> showResetResult(result))))
                    .create();
            resetDialog.show();
        } catch (Exception error) {
            toast(error instanceof ApiException ? error.getMessage() : "No se pudo preparar el reset. Actualiza la cuota.");
        }
    }

    private void showResetResult(String result) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Resultado del reset").setMessage(result)
                .setPositiveButton("Aceptar", null).show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Avisos");
        menu.getMenu().add(0, 2, 1, "Frecuencia de actualización");
        menu.getMenu().add(0, 3, 2, "Añadir widget completo");
        menu.getMenu().add(0, 4, 3, "Añadir widget mini");
        menu.getMenu().add(0, 5, 4, "Temas de los widgets");
        menu.getMenu().add(0, 6, 5, "Cómo funciona");
        if (repo.signedIn()) menu.getMenu().add(0, 7, 6, "Cerrar sesión");
        menu.setOnMenuItemClickListener(this::handleMenu);
        menu.show();
    }

    private boolean handleMenu(MenuItem item) {
        switch (item.getItemId()) {
            case 1: startActivity(new Intent(this, AlertSettingsActivity.class)); return true;
            case 2: startActivity(new Intent(this, RefreshSettingsActivity.class)); return true;
            case 3: pinWidget(false); return true;
            case 4: pinWidget(true); return true;
            case 5: chooseWidgetTheme(); return true;
            case 6:
                new AlertDialog.Builder(this).setTitle("Cuota Codex Beta · " + BuildConfig.VERSION_NAME)
                        .setMessage("Los porcentajes indican cuota disponible. Los colores siguen tu umbral de aviso.\n\nEl reset manual siempre pide confirmación y elige el que caduca antes. Antes de confirmar puedes consultar cuánto falta para la renovación automática.\n\nLa frecuencia elegida es de " + RefreshPolicy.label(repo.refreshIntervalMinutes()) + ". Android puede retrasar las consultas. Los datos antiguos se señalan sin ocultar la última consulta.\n\nLos avisos de cuota se comprueban al consultar. Los recordatorios de caducidad también usan las fechas guardadas y pueden retrasarse por Android. El aviso de recuperación requiere una consulta que confirme que puedes volver a usar Codex.\n\nApp independiente, sin afiliación a OpenAI. Sesión cifrada en este móvil y conexión directa con OpenAI.")
                        .setPositiveButton("Entendido", null).show();
                return true;
            case 7:
                new AlertDialog.Builder(this).setTitle("Cerrar sesión")
                        .setMessage("Se borrarán la sesión, la cuota guardada y los avisos pendientes de esta aplicación.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Cerrar sesión", (dialog, which) -> repo.logout()).show();
                return true;
            default: return false;
        }
    }

    private void chooseWidgetTheme() {
        int[] choice = {WidgetTheme.choice(this, -1)};
        new AlertDialog.Builder(this).setTitle("Tema para todos los widgets")
                .setSingleChoiceItems(WidgetTheme.NAMES, choice[0], (dialog, which) -> choice[0] = which)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar", (dialog, which) -> WidgetTheme.saveAll(this, choice[0])).show();
    }

    private void pinWidget(boolean mini) {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (manager.isRequestPinAppWidgetSupported()) {
            manager.requestPinAppWidget(new ComponentName(this, mini ? MiniQuotaWidget.class : QuotaWidget.class), null, null);
        } else toast("Pantalla de inicio → Mantener pulsado → Widgets → Cuota Codex Beta.");
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = background(surface, 14);
        background.setStroke(dp(1), border);
        card.setBackground(background);
        margin(card, 12, 0);
        return card;
    }

    private TextView text(String content, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(content);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(dp(3), 1f);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private void heading(TextView view) {
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        button.setTextColor(primary ? primaryText : ink);
        button.setBackground(background(primary ? ink : buttonColor, 10));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        margin(button, 12, 0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void margin(View view, int top, int bottom) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(top);
        layout.bottomMargin = dp(bottom);
        view.setLayoutParams(layout);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
