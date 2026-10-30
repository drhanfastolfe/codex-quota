package es.codexquota.mobile;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

final class WidgetTheme {
    static final String[] NAMES = {"Sistema", "Claro", "Oscuro", "Grafito"};
    final int background;
    final int buttonBackground;
    final int ink;
    final int line;
    final int muted;

    private WidgetTheme(int i, int i2, int i3, int i4, int i5) {
        this.background = i;
        this.buttonBackground = i2;
        this.ink = i3;
        this.muted = i4;
        this.line = i5;
    }

    static int choice(Context context, int i) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("widget_themes", 0);
        return Math.max(0, Math.min(3, sharedPreferences.getInt("theme_" + i, sharedPreferences.getInt("default", 0))));
    }

    static WidgetTheme get(Context context, int i) {
        return palette(context, choice(context, i));
    }

    static WidgetTheme palette(Context context, int i) {
        boolean z = (context.getResources().getConfiguration().uiMode & 48) == 32;
        if (i == 1 || (i == 0 && !z)) {
            return new WidgetTheme(R.drawable.widget_background_light, R.drawable.widget_button_light, Color.rgb(24, 24, 24), Color.rgb(99, 99, 99), Color.rgb(226, 226, 226));
        }
        if (i == 3) {
            return new WidgetTheme(R.drawable.widget_background_graphite, R.drawable.widget_button_graphite, Color.rgb(244, 244, 244), Color.rgb(186, 186, 186), Color.rgb(81, 81, 81));
        }
        return new WidgetTheme(R.drawable.widget_background, R.drawable.widget_button, Color.rgb(245, 245, 245), Color.rgb(163, 163, 163), Color.rgb(58, 58, 58));
    }

    static void save(Context context, int i, int i2) {
        context.getSharedPreferences("widget_themes", 0).edit().putInt("theme_" + i, i2).apply();
        QuotaWidget.renderAll(context);
    }

    static void saveAll(Context context, int i) {
        SharedPreferences.Editor editorPutInt = context.getSharedPreferences("widget_themes", 0).edit().putInt("default", i);
        for (int i2 : AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, (Class<?>) QuotaWidget.class))) {
            editorPutInt.remove("theme_" + i2);
        }
        for (int i3 : AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, (Class<?>) MiniQuotaWidget.class))) {
            editorPutInt.remove("theme_" + i3);
        }
        editorPutInt.apply();
        QuotaWidget.renderAll(context);
    }

    static void remove(Context context, int i) {
        context.getSharedPreferences("widget_themes", 0).edit().remove("theme_" + i).apply();
    }
}
