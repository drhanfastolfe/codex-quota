package es.codexquota.mobile;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import es.codexquota.mobile.core.QuotaSnapshot;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlarmManager;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {OfflineIntegrationFixture.OfflineTokens.class, OfflineIntegrationFixture.OfflineQuotaClient.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class AlertDeliveryIntegrationTest extends OfflineIntegrationFixture {
    private NotificationManager notifications;
    private AlarmManager alarms;

    @Before public void allowNotifications() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        shadowOf(notifications).setNotificationsEnabled(true);
        QuotaAlerts.ensureChannels(context);
    }

    private ShadowAlarmManager.ScheduledAlarm peekAlarm() {
        java.util.List<ShadowAlarmManager.ScheduledAlarm> scheduled = shadowOf(alarms).getScheduledAlarms();
        return scheduled.isEmpty() ? null : scheduled.get(0);
    }

    private void enableExpiry() {
        repository.setRefreshIntervalMinutes(360);
        QuotaAlerts.setExpiryHours(context, 1);
        QuotaAlerts.setEnabled(context, 2, true);
        QuotaAlerts.onSnapshot(context, Server.identity, Server.snapshot);
    }

    private void makeKnownSnapshotDue() {
        // Persisted cache fixture represents a query made two hours earlier. Wall-clock
        // System.currentTimeMillis in production is intentionally not replaced by a test hook.
        QuotaSnapshot old = snapshot(now - TimeUnit.HOURS.toMillis(2), "earliest",
                now + TimeUnit.MINUTES.toMillis(30));
        context.getSharedPreferences("quota_alert_state", Context.MODE_PRIVATE).edit()
                .putString("known_snapshot", old.toJson()).commit();
    }

    private QuotaSnapshot allowed(Boolean allowed, long observed) {
        QuotaSnapshot base = Server.snapshot;
        return new QuotaSnapshot(base.plan, base.windows, base.resetCredits, observed, allowed);
    }

    @Test public void expiryAlarmPrecedesLongPollingIntervalAndNeedsNoNetwork() {
        enableExpiry();
        ShadowAlarmManager.ScheduledAlarm alarm = peekAlarm();
        assertNotNull(alarm);
        assertEquals(now + TimeUnit.HOURS.toMillis(2), alarm.triggerAtTime);
        assertTrue(alarm.triggerAtTime < now + TimeUnit.HOURS.toMillis(6));
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type);
        assertEquals(ResetExpiryReceiver.class.getName(), shadowOf(alarm.operation).getSavedIntent().getComponent().getClassName());
        assertEquals(0, Server.fetches);
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void localExpiryReminderUsesKnownDatesAndLabelsTheirAge() {
        enableExpiry();
        assertNull(shadowOf(notifications).getNotification(302));
        makeKnownSnapshotDue();
        new ResetExpiryReceiver().onReceive(context, new Intent("es.codexquota.mobile.KNOWN_EXPIRY"));
        Notification notification = shadowOf(notifications).getNotification(302);
        assertNotNull("Reminder at " + System.currentTimeMillis() + " (start " + now + "): "
                + context.getSharedPreferences("quota_alert_diagnostics", 0).getAll(), notification);
        String text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString();
        assertTrue(text, text.contains("Según la última consulta"));
        assertEquals(0, Server.fetches);
        assertTrue(Server.requestIds.isEmpty());
        notifications.cancel(302);
        QuotaAlerts.onKnownExpiry(context);
        assertNull("A local reminder must not repeat for the same credit", shadowOf(notifications).getNotification(302));
    }

    @Test public void disablingExpiryCancelsScheduledReminder() {
        enableExpiry();
        assertNotNull(peekAlarm());
        QuotaAlerts.setEnabled(context, 2, false);
        assertNull(peekAlarm());
        makeKnownSnapshotDue();
        QuotaAlerts.onKnownExpiry(context);
        assertNull(shadowOf(notifications).getNotification(302));
    }

    @Test public void bootRestoresKnownReminderWithoutQueryingOrRedeeming() {
        enableExpiry();
        ShadowAlarmManager.ScheduledAlarm initial = peekAlarm();
        alarms.cancel(initial.operation);
        assertNull(peekAlarm());
        new QuotaBootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNotNull(peekAlarm());
        assertEquals(0, Server.fetches);
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void reminderFromAnotherAccountIsDiscarded() {
        enableExpiry();
        Server.identity = "another-offline-user|another-account";
        makeKnownSnapshotDue();
        QuotaAlerts.onKnownExpiry(context);
        assertNull(shadowOf(notifications).getNotification(302));
        assertNull(peekAlarm());
        assertEquals(0, Server.fetches);
    }

    @Test public void recoveryRequiresServerBlockedThenAllowedAndDoesNotRepeat() {
        QuotaAlerts.setEnabled(context, QuotaAlerts.RECOVERED, true);
        QuotaAlerts.onSnapshot(context, Server.identity, allowed(true, now));
        assertNull(shadowOf(notifications).getNotification(303));
        QuotaAlerts.onSnapshot(context, Server.identity, allowed(false, now + 1));
        assertNull(shadowOf(notifications).getNotification(303));
        QuotaAlerts.onSnapshot(context, Server.identity, allowed(null, now + 2));
        assertNull("Unknown availability must not be called recovery", shadowOf(notifications).getNotification(303));
        QuotaAlerts.onSnapshot(context, Server.identity, allowed(true, now + 3));
        assertNotNull(shadowOf(notifications).getNotification(303));
        notifications.cancel(303);
        QuotaAlerts.onSnapshot(context, Server.identity, allowed(true, now + 4));
        assertNull(shadowOf(notifications).getNotification(303));
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void passingRenewalTimeAloneNeverAnnouncesRecovery() {
        QuotaAlerts.setEnabled(context, QuotaAlerts.RECOVERED, true);
        QuotaAlerts.onSnapshot(context, Server.identity, allowed(false, now));
        QuotaSnapshot original = Server.snapshot;
        QuotaSnapshot renewedByClockOnly = new QuotaSnapshot(original.plan,
                java.util.Collections.singletonList(new es.codexquota.mobile.core.QuotaWindow(
                        "5 horas", "codex:primary", 100, now - 1000, 18000L)),
                original.resetCredits, now + 1, Boolean.FALSE);
        QuotaAlerts.onSnapshot(context, Server.identity, renewedByClockOnly);
        QuotaAlerts.onKnownExpiry(context);
        assertNull(shadowOf(notifications).getNotification(303));
        assertEquals(0, Server.fetches);
    }

    @Test public void permissionDeniedDoesNotConsumeLowQuotaNotification() {
        QuotaAlerts.setThreshold(context, 55);
        QuotaAlerts.setEnabled(context, 1, true);
        shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        QuotaAlerts.onSnapshot(context, Server.identity, Server.snapshot);
        assertNull(shadowOf(notifications).getNotification(301));
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        QuotaAlerts.onSnapshot(context, Server.identity, allowed(true, now + 1));
        assertNotNull(shadowOf(notifications).getNotification(301));
        notifications.cancel(301);
        QuotaAlerts.onSnapshot(context, Server.identity, allowed(true, now + 2));
        assertNull(shadowOf(notifications).getNotification(301));
    }
    @Test public void confirmedCreditIsRemovedFromKnownRemindersEvenWhenRefreshFails() throws Exception {
        enableExpiry();
        long previousAlarm = peekAlarm().triggerAtTime;
        QuotaRepository.ResetApproval approved = repository.prepareReset();
        Server.failFetch = true;
        consume(approved);
        assertEquals(1, Server.creditIds.size());
        assertEquals("earliest", Server.creditIds.get(0));
        assertFalse(repository.hasPendingReset());
        QuotaSnapshot cached = repository.snapshot();
        assertNotNull(cached);
        assertEquals("later", cached.resetCredits.earliestForRedemption(System.currentTimeMillis()).id);
        assertEquals(now, cached.fetchedAtMillis);
        String storedJson = context.getSharedPreferences("quota_cache", 0).getString("snapshot", null);
        assertNotNull(storedJson);
        QuotaSnapshot persisted = QuotaSnapshot.fromJson(storedJson);
        assertEquals("later", persisted.resetCredits.earliestForRedemption(System.currentTimeMillis()).id);
        assertEquals(now, persisted.fetchedAtMillis);
        String knownJson = context.getSharedPreferences("quota_alert_state", 0).getString("known_snapshot", null);
        assertNotNull(knownJson);
        QuotaSnapshot known = QuotaSnapshot.fromJson(knownJson);
        assertFalse(known.resetCredits.credits.stream().anyMatch(credit -> "earliest".equals(credit.id)));
        assertTrue(known.resetCredits.credits.stream().anyMatch(credit -> "later".equals(credit.id)));
        assertNotNull(peekAlarm());
        assertTrue(peekAlarm().triggerAtTime > previousAlarm);
    }

    @Test public void resolvedLegacyCreditWithUnknownIdentityClearsKnownReminder() {
        enableExpiry();
        QuotaAlerts.onCreditResolved(context, Server.identity, null);
        assertNull(context.getSharedPreferences("quota_alert_state", 0).getString("known_snapshot", null));
        assertNull(peekAlarm());
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void creditResolvedForAnotherAccountCannotAlterCurrentReminder() {
        enableExpiry();
        String original = context.getSharedPreferences("quota_alert_state", 0).getString("known_snapshot", null);
        long trigger = peekAlarm().triggerAtTime;
        QuotaAlerts.onCreditResolved(context, "another-account", "earliest");
        assertEquals(original, context.getSharedPreferences("quota_alert_state", 0).getString("known_snapshot", null));
        assertNotNull(peekAlarm());
        assertEquals(trigger, peekAlarm().triggerAtTime);
    }

}
