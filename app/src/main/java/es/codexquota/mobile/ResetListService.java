package es.codexquota.mobile;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import es.codexquota.mobile.core.QuotaSnapshot;
import es.codexquota.mobile.core.ResetCredit;
import es.codexquota.mobile.core.ResetCredits;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Compact dates remain visible in one row; accessibility retains the full explanation. */
public final class ResetListService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(),
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    private static final class Row {
        final String text;
        final String description;
        Row(String text, String description) {
            this.text = text;
            this.description = description;
        }
        Row(String text) { this(text, text); }
    }

    private static final class Factory implements RemoteViewsFactory {
        private final Context context;
        private final int widgetId;
        private volatile List<Row> rows = Collections.emptyList();

        Factory(Context context, int widgetId) {
            this.context = context;
            this.widgetId = widgetId;
        }

        @Override public void onCreate() { onDataSetChanged(); }

        @Override public void onDataSetChanged() {
            List<Row> updated = new ArrayList<>();
            QuotaRepository repository = QuotaRepository.get(context);
            QuotaSnapshot snapshot = repository.snapshot();
            if (repository.signedIn() && snapshot != null && snapshot.resetCredits != null) {
                ResetCredits credits = snapshot.resetCredits;
                int index = 0;
                for (ResetCredit credit : credits.availableByExpiry(System.currentTimeMillis())) {
                    index++;
                    String date = credit.expiresAtMillis == null ? "Sin fecha"
                            : "Caduca " + new SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
                                    .format(new Date(credit.expiresAtMillis));
                    updated.add(new Row(index + ". " + date,
                            "Reset " + index + ". " + Formatters.creditDate(credit)));
                }
                if (credits.missingDetailCount(snapshot.fetchedAtMillis) > 0)
                    updated.add(new Row("Faltan fechas · abre la app",
                            "Hay resets sin fecha conocida. Abre la aplicación para actualizar los detalles."));
                if (updated.isEmpty()) updated.add(new Row("Sin resets disponibles"));
            } else {
                updated.add(new Row(repository.signedIn() ? "Actualiza para consultar las fechas"
                        : "Conecta tu cuenta en la app"));
            }
            rows = updated;
        }

        @Override public RemoteViews getViewAt(int position) {
            List<Row> current = rows;
            if (position < 0 || position >= current.size()) return null;
            Row row = current.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_reset_row);
            views.setTextViewText(R.id.reset_row, row.text);
            views.setContentDescription(R.id.reset_row, row.description);
            views.setTextColor(R.id.reset_row, WidgetTheme.get(context, widgetId).ink);
            return views;
        }

        @Override public int getCount() { return rows.size(); }
        @Override public long getItemId(int position) { return position; }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public boolean hasStableIds() { return false; }
        @Override public void onDestroy() { rows = Collections.emptyList(); }
    }
}
