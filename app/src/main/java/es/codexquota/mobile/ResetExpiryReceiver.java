package es.codexquota.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives only the app's inexact reminder. No usage query or credit redemption. */
public final class ResetExpiryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && "es.codexquota.mobile.KNOWN_EXPIRY".equals(intent.getAction())) {
            QuotaAlerts.onKnownExpiry(context);
        }
    }
}
