package es.codexquota.mobile;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import es.codexquota.mobile.core.QuotaPresentation;
import es.codexquota.mobile.core.QuotaSnapshot;
import es.codexquota.mobile.core.QuotaWindow;
import es.codexquota.mobile.core.WidgetLayoutPolicy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A compact two-window view. Small layouts use whole quota columns as touch targets. */
public final class MiniQuotaWidget extends AppWidgetProvider {
    private static final String REFRESH = "es.codexquota.mobile.MINI_WIDGET_REFRESH";
    private static final int PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (REFRESH.equals(intent.getAction())) {
            QuotaRepository.get(context).scheduleManual();
            QuotaWidget.renderAll(context);
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        QuotaWidget.renderAll(context);
        QuotaRepository.get(context).schedulePeriodic();
        QuotaRepository.get(context).scheduleManualIfStale();
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
            int id, Bundle options) {
        QuotaWidget.renderAll(context);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        for (int id : ids) WidgetTheme.remove(context, id);
        QuotaWidget.renderAll(context);
    }

    static void render(Context context, AppWidgetManager manager) {
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MiniQuotaWidget.class));
        if (ids.length == 0) return;
        QuotaRepository repository = QuotaRepository.get(context);
        QuotaSnapshot snapshot = repository.snapshot();
        int threshold = QuotaAlerts.visualWarningThreshold(context);
        long now = System.currentTimeMillis();
        for (int id : ids) {
            WidgetTheme theme = WidgetTheme.get(context, id);
            Bundle options = manager.getAppWidgetOptions(id);
            int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.mini_quota_widget);
            views.setInt(R.id.mini_root, "setBackgroundResource", theme.background);
            views.setViewPadding(R.id.mini_root, QuotaWidget.dp(context, 8), QuotaWidget.dp(context, 4),
                    QuotaWidget.dp(context, 8), QuotaWidget.dp(context, 4));
            for (int viewId : new int[] {R.id.mini_primary, R.id.mini_secondary}) {
                views.setTextColor(viewId, theme.ink);
            }
            for (int viewId : new int[] {R.id.mini_title, R.id.mini_primary_label,
                    R.id.mini_secondary_label, R.id.mini_primary_reset,
                    R.id.mini_secondary_reset, R.id.mini_refresh}) views.setTextColor(viewId, theme.muted);
            views.setInt(R.id.mini_theme, "setColorFilter", theme.muted);
            views.setViewVisibility(R.id.mini_refresh,
                    WidgetLayoutPolicy.showMiniRefresh(width) ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.mini_theme,
                    WidgetLayoutPolicy.showMiniTheme(width) ? View.VISIBLE : View.GONE);
            if (repository.signedIn() && snapshot != null) {
                QuotaWindow first = QuotaWidget.window(snapshot, 0);
                QuotaWindow second = QuotaWidget.window(snapshot, 1);
                bind(views, first, threshold, theme, R.id.mini_primary_label, R.id.mini_primary,
                        R.id.mini_primary_reset, R.id.mini_primary_bar,
                        R.id.mini_primary_bar_amber, R.id.mini_primary_bar_red);
                bind(views, second, threshold, theme, R.id.mini_secondary_label, R.id.mini_secondary,
                        R.id.mini_secondary_reset, R.id.mini_secondary_bar,
                        R.id.mini_secondary_bar_amber, R.id.mini_secondary_bar_red);
                views.setViewVisibility(R.id.mini_quota_right, second == null ? View.GONE : View.VISIBLE);
                boolean stale = QuotaPresentation.isStale(snapshot.fetchedAtMillis, now,
                        repository.refreshIntervalMinutes()) || !repository.error().isEmpty();
                String timestamp = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                        .format(new Date(snapshot.fetchedAtMillis));
                String status = repository.busy() ? "Actualizando…" : (stale ? "Antiguos · " : "Libre · ") + timestamp;
                views.setTextViewText(R.id.mini_title, status);
                views.setContentDescription(R.id.mini_title, (stale ? "Datos antiguos. " : "Cuota disponible. ")
                        + "Última consulta " + Formatters.date(snapshot.fetchedAtMillis)
                        + ". Pulsa para abrir la aplicación.");
            } else {
                views.setViewVisibility(R.id.mini_quota_right, View.GONE);
                views.setTextViewText(R.id.mini_primary_label, "CUOTA CODEX");
                views.setTextViewText(R.id.mini_primary, repository.signedIn() ? "Pendiente" : "Conectar");
                views.setTextViewText(R.id.mini_primary_reset, repository.signedIn()
                        ? "Actualiza la cuota" : "Abre la app");
                views.setTextViewText(R.id.mini_title, "›_ CODEX BETA");
            }
            views.setTextViewText(R.id.mini_refresh, repository.busy() ? "…" : "↻");
            PendingIntent open = PendingIntent.getActivity(context, 0,
                    new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP), PENDING_FLAGS);
            for (int viewId : new int[] {R.id.mini_root, R.id.mini_title,
                    R.id.mini_quota_left, R.id.mini_quota_right}) views.setOnClickPendingIntent(viewId, open);
            views.setOnClickPendingIntent(R.id.mini_refresh, PendingIntent.getBroadcast(context, 3,
                    new Intent(context, MiniQuotaWidget.class).setAction(REFRESH), PENDING_FLAGS));
            views.setOnClickPendingIntent(R.id.mini_theme, QuotaWidget.themeIntent(context, id));
            manager.updateAppWidget(id, views);
        }
    }

    private static void bind(RemoteViews views, QuotaWindow window, int threshold, WidgetTheme theme,
            int label, int percent, int renewal, int normalBar, int amberBar, int redBar) {
        int level = window == null ? 0 : QuotaPresentation.colorLevel(window.remainingPercent(), threshold);
        String title = QuotaWidget.shortLabel(window);
        if ("5 horas".equals(title)) title = "5 h";
        views.setTextViewText(label, (level > 0 ? "! " : "") + title);
        views.setTextViewText(percent, window == null ? "—" : Formatters.remaining(window));
        String renewalText = Formatters.compactRenewal(window);
        views.setTextViewText(renewal, renewalText.startsWith("Renueva ") ? renewalText.substring(8) : renewalText);
        QuotaWidget.bindQuotaBar(views, window, percent, theme, normalBar, amberBar, redBar, threshold);
    }
}
