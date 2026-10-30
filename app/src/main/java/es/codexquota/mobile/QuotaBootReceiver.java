package es.codexquota.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores jobs and date reminders after system lifecycle events. */
public final class QuotaBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            QuotaRepository.get(context).schedulePeriodic();
            QuotaAlerts.rescheduleKnown(context);
            QuotaWidget.renderAll(context);
        }
    }
}
