package es.codexquota.mobile;

import android.Manifest;
import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.util.ReflectionHelpers;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

/** Offline render artifacts for visual review; these are not screenshots of a physical phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "es-rES-w411dp-h891dp-xhdpi", shadows = {
        OfflineIntegrationFixture.OfflineTokens.class, OfflineIntegrationFixture.OfflineQuotaClient.class})
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VisualPreviewIntegrationTest extends OfflineIntegrationFixture {
    @Test public void renderOfflineAppAndWidgetsForVisualReview() throws Exception {
        java.util.Locale originalLocale = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-ES"));
        try {
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        QuotaAlerts.setThreshold(context, 55);
        QuotaAlerts.setEnabled(context, 1, true);
        QuotaAlerts.setExpiryHours(context, 1);
        QuotaAlerts.setEnabled(context, 2, true);
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible();
        try {
            shadowOf(Looper.getMainLooper()).idle();
            capture(main.get().getWindow().getDecorView(), "main", 411, 891);
            ReflectionHelpers.callInstanceMethod(main.get(), "confirmReset");
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            capture(dialog.getWindow().getDecorView(), "reset-confirmation", 360, 500);
            dialog.cancel();
        } finally {
            main.pause().stop().destroy();
        }
        ActivityController<AlertSettingsActivity> alerts = Robolectric.buildActivity(AlertSettingsActivity.class)
                .create().start().resume().visible();
        try {
            shadowOf(Looper.getMainLooper()).idle();
            capture(alerts.get().getWindow().getDecorView(), "alerts", 411, 891);
        } finally {
            alerts.pause().stop().destroy();
        }
        captureWidget(QuotaWidget.class, R.layout.quota_widget, "widget-full", 250, 178);
        captureWidget(MiniQuotaWidget.class, R.layout.mini_quota_widget, "widget-mini", 140, 78);
        assertTrue(Server.requestIds.isEmpty());
        } finally {
            java.util.Locale.setDefault(originalLocale);
        }
    }

    private void captureWidget(Class<? extends android.appwidget.AppWidgetProvider> provider,
            int layout, String name, int widthDp, int heightDp) throws Exception {
        AppWidgetManager real = AppWidgetManager.getInstance(context);
        ShadowAppWidgetManager manager = shadowOf(real);
        int id = manager.createWidget(provider, layout);
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
        real.updateAppWidgetOptions(id, options);
        QuotaWidget.renderAll(context);
        View widget = manager.getViewFor(id);
        if (provider == QuotaWidget.class) {
            // Robolectric does not bind launcher collection services automatically.
            // Supply the real factory's RemoteViews through an offline host adapter.
            ResetListService service = Robolectric.buildService(ResetListService.class).create().get();
            android.widget.RemoteViewsService.RemoteViewsFactory factory = service.onGetViewFactory(
                    new android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
            factory.onCreate();
            android.widget.ListView list = widget.findViewById(R.id.widget_reset_list);
            list.setAdapter(new android.widget.BaseAdapter() {
                @Override public int getCount() { return factory.getCount(); }
                @Override public Object getItem(int position) { return position; }
                @Override public long getItemId(int position) { return factory.getItemId(position); }
                @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                    return factory.getViewAt(position).apply(context, parent);
                }
            });
            try { capture(widget, name, widthDp, heightDp); }
            finally { factory.onDestroy(); service.onDestroy(); }
        } else {
            capture(widget, name, widthDp, heightDp);
        }
    }

    private void capture(View view, String name, int widthDp, int heightDp) throws Exception {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        view.requestLayout();
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File directory = new File("build/visuals");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        if (name.equals("widget-full")) {
            StringBuilder report = new StringBuilder();
            for (int id : new int[] {R.id.widget_refresh, R.id.widget_reset,
                    R.id.widget_primary_label, R.id.widget_secondary_label}) {
                android.widget.TextView text = view.findViewById(id);
                report.append(text.getText()).append(" w=").append(text.getWidth()).append(" h=")
                    .append(text.getHeight()).append(" color=").append(text.getCurrentTextColor())
                    .append(" baseline=").append(text.getBaseline()).append(" vis=").append(text.getVisibility())
                    .append(" padding=").append(text.getPaddingTop()).append(",").append(text.getPaddingBottom())
                    .append(" lines=").append(text.getLayout() == null ? -1 : text.getLayout().getLineCount())
                    .append(" size=").append(text.getTextSize())
                    .append(" layoutW=").append(text.getLayout() == null ? -1 : text.getLayout().getWidth())
                    .append(" lineW=").append(text.getLayout() == null ? -1 : text.getLayout().getLineWidth(0))
                    .append(" ellipsis=").append(text.getLayout() == null ? -1 : text.getLayout().getEllipsisCount(0))
                    .append("\n");
                if (id == R.id.widget_refresh || id == R.id.widget_reset) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    text.getDrawingRect(bounds);
                    ((android.view.ViewGroup) view).offsetDescendantRectToMyCoords(text, bounds);
                    int inkPixels = 0;
                    for (int y = Math.max(0, bounds.top); y < Math.min(height, bounds.bottom); y++) {
                        for (int x = Math.max(0, bounds.left); x < Math.min(width, bounds.right); x++) {
                            int color = bitmap.getPixel(x, y);
                            if (android.graphics.Color.alpha(color) > 128 && android.graphics.Color.red(color) < 100
                                    && android.graphics.Color.green(color) < 100 && android.graphics.Color.blue(color) < 100) inkPixels++;
                        }
                    }
                    assertTrue("Footer label must be visibly drawn: " + text.getText(), inkPixels > 15);
                }
            }
            java.nio.file.Files.write(new File(directory, name + "-layout.txt").toPath(),
                    report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        bitmap.recycle();
        assertTrue(output.length() > 1000);
    }
}
