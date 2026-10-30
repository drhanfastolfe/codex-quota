package es.codexquota.mobile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import es.codexquota.mobile.core.QuotaPresentation;
import es.codexquota.mobile.core.QuotaSnapshot;
import es.codexquota.mobile.core.QuotaWindow;
import es.codexquota.mobile.core.RefreshPolicy;
import es.codexquota.mobile.core.ResetCredits;
import es.codexquota.mobile.core.WidgetLayoutPolicy;

/** The full widget is a view of the shared cache. It never redeems a reset. */
public final class QuotaWidget extends AppWidgetProvider {
    private static final String REFRESH = "es.codexquota.mobile.WIDGET_REFRESH";
    private static final String STALE_RENDER = "es.codexquota.mobile.WIDGET_STALE_RENDER";
    private static final int PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (REFRESH.equals(intent.getAction())) {
            QuotaRepository.get(context).scheduleManual();
            renderAll(context);
        } else if (STALE_RENDER.equals(intent.getAction())) {
            // Local ageing changes the displayed state only; it is never a network request.
            renderAll(context);
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        renderAll(context);
        QuotaRepository.get(context).schedulePeriodic();
        QuotaRepository.get(context).scheduleManualIfStale();
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
            int widgetId, Bundle options) {
        renderAll(context);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        for (int id : ids) WidgetTheme.remove(context, id);
        renderAll(context);
    }

    public static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        MiniQuotaWidget.render(context, manager);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, QuotaWidget.class));
        QuotaRepository repository = QuotaRepository.get(context);
        QuotaSnapshot snapshot = repository.snapshot();
        boolean hasWidgets = ids.length > 0 || manager.getAppWidgetIds(
                new ComponentName(context, MiniQuotaWidget.class)).length > 0;
        scheduleAgeingRender(context, repository, snapshot, hasWidgets);
        if (ids.length == 0) return;
        long now = System.currentTimeMillis();
        int threshold = QuotaAlerts.visualWarningThreshold(context);
        for (int id : ids) {
            Bundle options = manager.getAppWidgetOptions(id);
            int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
            int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 178);
            WidgetTheme theme = WidgetTheme.get(context, id);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.quota_widget);
            views.setInt(R.id.widget_root, "setBackgroundResource", theme.background);
            int padding = dp(context, 10);
            views.setViewPadding(R.id.widget_root, padding, dp(context, 5), padding, dp(context, 5));
            views.setViewVisibility(R.id.widget_title,
                    WidgetLayoutPolicy.showFullHeading(height) ? View.VISIBLE : View.GONE);
            for (int viewId : new int[] {R.id.widget_title, R.id.widget_primary, R.id.widget_secondary,
                    R.id.widget_refresh, R.id.widget_reset}) views.setTextColor(viewId, theme.ink);
            for (int viewId : new int[] {R.id.widget_primary_label, R.id.widget_secondary_label,
                    R.id.widget_primary_reset, R.id.widget_secondary_reset,
                    R.id.widget_credits, R.id.widget_status}) views.setTextColor(viewId, theme.muted);
            for (int viewId : new int[] {R.id.widget_refresh, R.id.widget_reset, R.id.widget_theme})
                views.setInt(viewId, "setBackgroundResource", theme.buttonBackground);
            views.setInt(R.id.widget_theme, "setColorFilter", theme.muted);

            if (repository.signedIn() && snapshot != null) {
                QuotaWindow first = window(snapshot, 0);
                QuotaWindow second = window(snapshot, 1);
                bindWindow(views, first, threshold, theme, R.id.widget_primary_label,
                        R.id.widget_primary, R.id.widget_primary_reset, R.id.widget_primary_bar,
                        R.id.widget_primary_bar_amber, R.id.widget_primary_bar_red);
                bindWindow(views, second, threshold, theme, R.id.widget_secondary_label,
                        R.id.widget_secondary, R.id.widget_secondary_reset, R.id.widget_secondary_bar,
                        R.id.widget_secondary_bar_amber, R.id.widget_secondary_bar_red);
                views.setViewVisibility(R.id.widget_quota_right, second == null ? View.GONE : View.VISIBLE);
                ResetCredits credits = snapshot.resetCredits;
                long available = credits == null ? 0 : credits.displayAvailableCount(now, snapshot.fetchedAtMillis);
                views.setTextViewText(R.id.widget_credits, credits == null ? "RESETS · sin datos"
                        : "RESETS · " + available + (available > 1 ? " · desliza" : " disponible"));
                boolean stale = QuotaPresentation.isStale(snapshot.fetchedAtMillis, now,
                        repository.refreshIntervalMinutes());
                String status = repository.busy() ? "Actualizando…" : stale || !repository.error().isEmpty()
                        ? "Datos antiguos · " + Formatters.date(snapshot.fetchedAtMillis)
                        : "Consulta " + Formatters.date(snapshot.fetchedAtMillis);
                views.setTextViewText(R.id.widget_status, status);
                views.setContentDescription(R.id.widget_status, status + ". Pulsa para abrir la aplicación.");
                views.setTextViewText(R.id.widget_reset, repository.consuming() ? "Aplicando…" : "Reset…");
            } else {
                views.setViewVisibility(R.id.widget_quota_right, View.GONE);
                views.setTextViewText(R.id.widget_primary_label, "CUOTA DISPONIBLE");
                views.setTextViewText(R.id.widget_primary, repository.signedIn() ? "Pendiente" : "Conectar");
                views.setTextViewText(R.id.widget_primary_reset, repository.signedIn()
                        ? "Actualiza para consultar" : "Abre Cuota Codex Beta");
                views.setTextViewText(R.id.widget_status, repository.error().isEmpty()
                        ? "Sin consulta todavía" : "No se pudo actualizar · abre la app");
            }
            views.setTextViewText(R.id.widget_refresh, repository.busy() ? "Consultando…" : "↻ Actualizar");
            Intent adapter = new Intent(context, ResetListService.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(Uri.parse("codexquota://reset-list/" + id));
            views.setRemoteAdapter(R.id.widget_reset_list, adapter);
            PendingIntent open = PendingIntent.getActivity(context, 0,
                    new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP), PENDING_FLAGS);
            for (int viewId : new int[] {R.id.widget_title, R.id.widget_quota_left,
                    R.id.widget_quota_right, R.id.widget_credits, R.id.widget_status})
                views.setOnClickPendingIntent(viewId, open);
            // Explicit activity routing: only MainActivity's confirmation can authorize a reset.
            views.setOnClickPendingIntent(R.id.widget_reset, PendingIntent.getActivity(context, 1,
                    new Intent(context, MainActivity.class).setAction(MainActivity.SHOW_RESET)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), PENDING_FLAGS));
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 2,
                    new Intent(context, QuotaWidget.class).setAction(REFRESH), PENDING_FLAGS));
            views.setOnClickPendingIntent(R.id.widget_theme, themeIntent(context, id));
            manager.updateAppWidget(id, views);
        }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_reset_list);
    }

    static QuotaWindow window(QuotaSnapshot snapshot, int index) {
        return snapshot.windows.size() > index ? snapshot.windows.get(index) : null;
    }

    static String shortLabel(QuotaWindow window) {
        if (window == null) return "Cuota";
        if (window.windowSeconds != null && window.windowSeconds == 18000L) return "5 horas";
        if (window.windowSeconds != null && window.windowSeconds == 604800L) return "Semanal";
        return window.label;
    }

    static void bindWindow(RemoteViews views, QuotaWindow window, int threshold, WidgetTheme theme,
            int label, int percent, int renewal, int bar, int amberBar, int redBar) {
        int level = window == null ? 0 : QuotaPresentation.colorLevel(window.remainingPercent(), threshold);
        views.setTextViewText(label, (window != null && level > 0 ? "! " : "") + shortLabel(window) + " · libre");
        views.setTextViewText(percent, window == null ? "—" : Formatters.remaining(window));
        views.setTextViewText(renewal, Formatters.compactRenewal(window));
        bindQuotaBar(views, window, percent, theme, bar, amberBar, redBar, threshold);
    }

    static void bindQuotaBar(RemoteViews views, QuotaWindow window, int percent, WidgetTheme theme,
            int normalBar, int amberBar, int redBar, int threshold) {
        int level = window == null ? -1 : QuotaPresentation.colorLevel(window.remainingPercent(), threshold);
        int value = window == null ? 0 : (int) Math.round(Math.max(0, Math.min(100, window.remainingPercent())));
        int[] bars = {normalBar, amberBar, redBar};
        for (int index = 0; index < bars.length; index++) {
            views.setViewVisibility(bars[index], index == level ? View.VISIBLE : View.GONE);
            views.setProgressBar(bars[index], 100, value, false);
        }
        views.setTextColor(percent, theme.ink);
        if (window != null) views.setContentDescription(percent, Formatters.windowTitle(window)
                + (level > 0 ? ". Cuota baja." : ""));
    }

    static PendingIntent themeIntent(Context context, int id) {
        return PendingIntent.getActivity(context, id,
                new Intent(context, WidgetThemeActivity.class)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .setData(Uri.parse("codexquota://theme/" + id)), PENDING_FLAGS);
    }

    static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static void scheduleAgeingRender(Context context, QuotaRepository repository,
            QuotaSnapshot snapshot, boolean hasWidgets) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent redraw = PendingIntent.getBroadcast(context, 410,
                new Intent(context, QuotaWidget.class).setAction(STALE_RENDER), PENDING_FLAGS);
        long now = System.currentTimeMillis();
        if (!hasWidgets || !repository.signedIn() || snapshot == null
                || QuotaPresentation.isStale(snapshot.fetchedAtMillis, now, repository.refreshIntervalMinutes())) {
            alarms.cancel(redraw);
            return;
        }
        // RTC (not RTC_WAKEUP): Android may defer this inexpensive, inexact display update.
        long due = snapshot.fetchedAtMillis + RefreshPolicy.intervalMillis(repository.refreshIntervalMinutes());
        alarms.set(AlarmManager.RTC, due, redraw);
    }
}
