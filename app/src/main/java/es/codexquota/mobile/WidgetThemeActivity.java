package es.codexquota.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** Handles only widget configuration; it cannot perform account mutations. */
public final class WidgetThemeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id);
        if (info == null || (!new ComponentName(this, QuotaWidget.class).equals(info.provider)
                && !new ComponentName(this, MiniQuotaWidget.class).equals(info.provider))) {
            finish();
            return;
        }
        int[] selected = {WidgetTheme.choice(this, id)};
        new AlertDialog.Builder(this)
                .setTitle("Tema del widget")
                .setSingleChoiceItems(WidgetTheme.NAMES, selected[0], (dialog, choice) -> selected[0] = choice)
                .setNegativeButton("Cancelar", (dialog, button) -> finish())
                .setPositiveButton("Aplicar", (dialog, button) -> {
                    WidgetTheme.save(this, id, selected[0]);
                    setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
                    finish();
                })
                .setOnCancelListener(dialog -> finish())
                .show();
    }
}
