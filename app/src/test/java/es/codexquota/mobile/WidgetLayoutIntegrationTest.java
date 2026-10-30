package es.codexquota.mobile;

import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.content.Intent;
import android.content.res.Configuration;
import android.widget.TextView;
import android.widget.RemoteViewsService;
import android.view.ViewGroup;
import org.robolectric.Robolectric;
import org.robolectric.annotation.GraphicsMode;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAppWidgetManager;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

/** Measure the actual RemoteViews at their documented minimum footprints. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {OfflineIntegrationFixture.OfflineTokens.class, OfflineIntegrationFixture.OfflineQuotaClient.class})
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetLayoutIntegrationTest extends OfflineIntegrationFixture {
    private View render(Class<? extends android.appwidget.AppWidgetProvider> provider,
            int layout, int widthDp, int heightDp) {
        AppWidgetManager androidManager = AppWidgetManager.getInstance(context);
        ShadowAppWidgetManager manager = shadowOf(androidManager);
        int id = manager.createWidget(provider, layout);
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
        androidManager.updateAppWidgetOptions(id, options);
        QuotaWidget.renderAll(context);
        View view = manager.getViewFor(id);
        int width = dp(widthDp);
        int height = dp(heightDp);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Test public void minimumFullWidgetKeepsLargeActionsAndVisibleResetList() {
        View widget = render(QuotaWidget.class, R.layout.quota_widget, 230, 178);
        assertEquals(View.GONE, widget.findViewById(R.id.widget_title).getVisibility());
        for (int id : new int[] {R.id.widget_refresh, R.id.widget_reset, R.id.widget_theme}) {
            View action = widget.findViewById(id);
            assertTrue("Action needs a 48dp touch target", action.getHeight() >= dp(48));
            assertTrue(action.getWidth() >= dp(48));
        }
        assertTrue(widget.findViewById(R.id.widget_reset_list).getHeight() >= dp(22));
        View footer = (View) widget.findViewById(R.id.widget_reset).getParent();
        assertTrue("Footer must fit the minimum height", footer.getBottom() <= widget.getHeight());
    }

    @Test public void minimumMiniKeepsBothQuotaColumnsAndAvoidsTinyControls() {
        View widget = render(MiniQuotaWidget.class, R.layout.mini_quota_widget, 140, 78);
        assertEquals(View.GONE, widget.findViewById(R.id.mini_refresh).getVisibility());
        assertEquals(View.GONE, widget.findViewById(R.id.mini_theme).getVisibility());
        for (int id : new int[] {R.id.mini_quota_left, R.id.mini_quota_right}) {
            View column = widget.findViewById(id);
            assertEquals(View.VISIBLE, column.getVisibility());
            assertTrue(column.getHeight() >= dp(48));
            assertTrue(column.getWidth() >= dp(48));
        }
        View column = widget.findViewById(R.id.mini_quota_left);
        assertTrue("Renewal line must fit the minimum widget", widget.findViewById(R.id.mini_primary_reset).getBottom()
                <= column.getHeight());
    }

    @Test public void wideMiniAddsFullSizeRefreshAndThemeActions() {
        View widget = render(MiniQuotaWidget.class, R.layout.mini_quota_widget, 290, 110);
        for (int id : new int[] {R.id.mini_refresh, R.id.mini_theme}) {
            View action = widget.findViewById(id);
            assertEquals(View.VISIBLE, action.getVisibility());
            assertEquals(dp(48), action.getWidth());
            assertEquals(dp(48), action.getHeight());
        }
    }
    private void setFontScale(float scale) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.fontScale = scale;
        context.getResources().updateConfiguration(configuration, context.getResources().getDisplayMetrics());
    }

    private void assertTextFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.length() == 0) return;
            assertNotNull("Text must have a measured layout: " + text.getText(), text.getLayout());
            assertTrue("Text layout must have width: " + text.getText(), text.getLayout().getWidth() > 0);
            assertTrue("Text layout must have height: " + text.getText(), text.getLayout().getHeight() > 0);
            int availableHeight = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
            assertTrue("Clipped text at font scale " + context.getResources().getConfiguration().fontScale
                    + ": " + text.getText() + " layout=" + text.getLayout().getHeight() + " available=" + availableHeight,
                    text.getLayout().getHeight() <= availableHeight);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertTextFits(group.getChildAt(i));
        }
    }

    @Test public void widgetTextFitsAt130PercentSystemFontScale() {
        setFontScale(1.3f);
        assertTextFits(render(QuotaWidget.class, R.layout.quota_widget, 230, 178));
        assertTextFits(render(MiniQuotaWidget.class, R.layout.mini_quota_widget, 140, 78));
    }

    @Test public void widgetTextFitsAt150PercentSystemFontScale() {
        setFontScale(1.5f);
        assertTextFits(render(QuotaWidget.class, R.layout.quota_widget, 230, 178));
        assertTextFits(render(MiniQuotaWidget.class, R.layout.mini_quota_widget, 140, 78));
    }

    @Test public void resetRowShowsOneCompactDateAndKeepsFullAccessibleDescription() {
        setFontScale(1.5f);
        ResetListService service = Robolectric.buildService(ResetListService.class).create().get();
        RemoteViewsService.RemoteViewsFactory factory = service.onGetViewFactory(new Intent());
        factory.onCreate();
        assertTrue(factory.getCount() > 0);
        View row = factory.getViewAt(0).apply(context, null);
        row.measure(View.MeasureSpec.makeMeasureSpec(dp(210), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(22), View.MeasureSpec.EXACTLY));
        row.layout(0, 0, dp(210), dp(22));
        TextView text = (TextView) row;
        assertTrue(text.getText().toString().matches("1\\. Caduca \\d{2}/\\d{2}/\\d{2} \\d{2}:\\d{2}"));
        assertEquals(1, text.getLineCount());
        assertTrue(text.getContentDescription().toString().startsWith("Reset 1."));
        assertTrue(text.getContentDescription().length() > text.getText().length());
        assertTextFits(row);
        factory.onDestroy();
        service.onDestroy();
    }

}
