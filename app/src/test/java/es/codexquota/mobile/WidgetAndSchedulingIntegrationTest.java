package es.codexquota.mobile;

import android.app.AlertDialog;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.util.ReflectionHelpers;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {OfflineIntegrationFixture.OfflineTokens.class, OfflineIntegrationFixture.OfflineQuotaClient.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class WidgetAndSchedulingIntegrationTest extends OfflineIntegrationFixture {
    @Test public void periodicScheduleUsesChosenIntervalAndIsShared() {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo initial = jobs.getPendingJob(201);
        assertNotNull(initial);
        assertEquals(TimeUnit.MINUTES.toMillis(30), initial.getIntervalMillis());
        assertTrue(initial.isPersisted());
        assertEquals(JobInfo.NETWORK_TYPE_ANY, initial.getNetworkType());
        assertTrue(repository.setRefreshIntervalMinutes(90));
        new QuotaWidget().onUpdate(context, AppWidgetManager.getInstance(context), new int[0]);
        new MiniQuotaWidget().onUpdate(context, AppWidgetManager.getInstance(context), new int[0]);
        JobInfo changed = jobs.getPendingJob(201);
        assertEquals(TimeUnit.MINUTES.toMillis(90), changed.getIntervalMillis());
        assertEquals(1, jobs.getAllPendingJobs().stream().filter(JobInfo::isPeriodic).count());
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void repeatedRefreshFromEitherWidgetCoalescesIntoOneReadJob() {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        String fullRefresh = ReflectionHelpers.getStaticField(QuotaWidget.class, "REFRESH");
        String miniRefresh = ReflectionHelpers.getStaticField(MiniQuotaWidget.class, "REFRESH");
        new QuotaWidget().onReceive(context, new Intent(fullRefresh));
        new MiniQuotaWidget().onReceive(context, new Intent(miniRefresh));
        new QuotaWidget().onReceive(context, new Intent(fullRefresh));
        assertNotNull(jobs.getPendingJob(202));
        assertEquals(1, jobs.getAllPendingJobs().stream().filter(job -> job.getId() == 202).count());
        assertEquals(0, Server.fetches);
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void widgetResetButtonLaunchesConfirmationActivity() throws Exception {
        ShadowAppWidgetManager manager = shadowOf(AppWidgetManager.getInstance(context));
        int widgetId = manager.createWidget(QuotaWidget.class, R.layout.quota_widget);
        QuotaWidget.renderAll(context);
        View widget = manager.getViewFor(widgetId);
        assertNotNull(widget.findViewById(R.id.widget_reset));
        widget.findViewById(R.id.widget_reset).performClick();
        Intent launched = shadowOf((android.app.Application) context).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(MainActivity.SHOW_RESET, launched.getAction());
        assertEquals(MainActivity.class.getName(), launched.getComponent().getClassName());
        assertTrue(Server.requestIds.isEmpty());
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class, launched)
                .create().start().resume().visible();
        try {
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(confirmation);
            assertTrue(confirmation.isShowing());
            assertTrue(Server.requestIds.isEmpty());
            confirmation.cancel();
            finishIo();
            assertTrue(Server.requestIds.isEmpty());
        } finally {
            activity.pause().stop().destroy();
        }
    }

    @Test public void widgetWarningColourFollowsConfiguredThreshold() {
        QuotaAlerts.setThreshold(context, 55);
        QuotaAlerts.setEnabled(context, 1, true);
        // Keep this assertion explicit: this fixture has 35 percent remaining.
        assertEquals(35.0, repository.snapshot().windows.get(0).remainingPercent(), 0.0);
        ShadowAppWidgetManager manager = shadowOf(AppWidgetManager.getInstance(context));
        int widgetId = manager.createWidget(QuotaWidget.class, R.layout.quota_widget);
        QuotaWidget.renderAll(context);
        View widget = manager.getViewFor(widgetId);
        assertEquals(View.VISIBLE, widget.findViewById(R.id.widget_primary_bar_amber).getVisibility());
        assertEquals(View.GONE, widget.findViewById(R.id.widget_primary_bar_red).getVisibility());
        assertEquals(View.GONE, widget.findViewById(R.id.widget_primary_bar).getVisibility());
    }

    @Test public void oldWidgetDataIsMarkedEvenWithoutNetworkError() {
        long oldTime = now - TimeUnit.MINUTES.toMillis(45);
        ReflectionHelpers.setField(repository, "snapshot", snapshot(oldTime, "earliest", now + TimeUnit.HOURS.toMillis(3)));
        ReflectionHelpers.setField(repository, "error", "");
        ShadowAppWidgetManager manager = shadowOf(AppWidgetManager.getInstance(context));
        int widgetId = manager.createWidget(QuotaWidget.class, R.layout.quota_widget);
        QuotaWidget.renderAll(context);
        View widget = manager.getViewFor(widgetId);
        String text = ((TextView) widget.findViewById(R.id.widget_status)).getText().toString().toLowerCase(java.util.Locale.ROOT);
        assertTrue(text, text.contains("antigu") || text.contains("sin actualizar"));
        assertTrue(Server.requestIds.isEmpty());
    }
    @Test public void localAgeingRedrawMarksStaleWithoutCreatingAReadJob() {
        ShadowAppWidgetManager manager = shadowOf(AppWidgetManager.getInstance(context));
        int widgetId = manager.createWidget(QuotaWidget.class, R.layout.quota_widget);
        QuotaWidget.renderAll(context);
        android.app.AlarmManager alarms = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm ageing = shadowOf(alarms).getScheduledAlarms()
                .stream().filter(alarm -> "es.codexquota.mobile.WIDGET_STALE_RENDER".equals(
                        shadowOf(alarm.operation).getSavedIntent().getAction())).findFirst().orElse(null);
        assertNotNull("A cached widget must eventually mark its data old", ageing);
        assertTrue(ageing.triggerAtTime >= now + TimeUnit.MINUTES.toMillis(30));
        ReflectionHelpers.setField(repository, "snapshot", snapshot(now - TimeUnit.HOURS.toMillis(1),
                "earliest", now + TimeUnit.HOURS.toMillis(3)));
        new QuotaWidget().onReceive(context, new Intent("es.codexquota.mobile.WIDGET_STALE_RENDER"));
        String text = ((TextView) manager.getViewFor(widgetId).findViewById(R.id.widget_status)).getText().toString();
        assertTrue(text, text.contains("antiguos"));
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        assertNull(jobs.getPendingJob(202));
        assertEquals(0, Server.fetches);
        assertTrue(Server.requestIds.isEmpty());
    }

}
