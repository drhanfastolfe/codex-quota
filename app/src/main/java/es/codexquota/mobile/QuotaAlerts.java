package es.codexquota.mobile;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import es.codexquota.mobile.core.AlertPolicy;
import es.codexquota.mobile.core.ExpirySchedulePolicy;
import es.codexquota.mobile.core.RecoveryPolicy;
import es.codexquota.mobile.core.QuotaSnapshot;
import es.codexquota.mobile.core.QuotaWindow;
import es.codexquota.mobile.core.ResetCredit;
import es.codexquota.mobile.core.ResetCredits;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class QuotaAlerts {
    public static final int RECOVERED = 3;
    private static final String RECOVERY_CHANNEL = "codex_quota_recovered";
    private static final int RECOVERY_ID = 303;
    private static final int RECOVERY_TEST_ID = 313;
    private static final int EXPIRY_ALARM_REQUEST = 321;
    private static final String EXPIRY_ALARM_ACTION = "es.codexquota.mobile.KNOWN_EXPIRY";
    private static final long LOCAL_RETRY_DELAY = 15 * 60_000L;
    private static final String DIAGNOSTIC_PREFS = "quota_alert_diagnostics";
    private static final String EXPIRY_CHANNEL = "codex_reset_expiry";
    private static final int EXPIRY_ID = 302;
    private static final int EXPIRY_TEST_ID = 312;
    private static final String LOW_CHANNEL = "codex_quota_low";
    private static final int LOW_ID = 301;
    private static final int LOW_TEST_ID = 311;
    private static final String SETTINGS_PREFS = "quota_alert_settings";
    private static final String STATE_PREFS = "quota_alert_state";

    static AlertPolicy.Config config(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(SETTINGS_PREFS, 0);
        return new AlertPolicy.Config(sharedPreferences.getBoolean("low_enabled", false), sharedPreferences.getInt("low_threshold", 20), sharedPreferences.getBoolean("expiry_enabled", false), sharedPreferences.getInt("expiry_hours", 24));
    }

    public static int visualWarningThreshold(Context context) {
        AlertPolicy.Config settings = config(context);
        return settings.lowEnabled ? settings.lowThresholdPercent : 20;
    }

    static boolean recoveryEnabled(Context context) {
        return context.getSharedPreferences(SETTINGS_PREFS, 0).getBoolean("recovery_enabled", false);
    }

    static synchronized void setEnabled(Context context, int kind, boolean enabled) {
        AlertPolicy.Config settings = config(context);
        boolean previous = kind == 1 ? settings.lowEnabled
                : kind == 2 ? settings.expiryEnabled : recoveryEnabled(context);
        String key = kind == 1 ? "low_enabled" : kind == 2 ? "expiry_enabled" : "recovery_enabled";
        context.getSharedPreferences(SETTINGS_PREFS, 0).edit().putBoolean(key, enabled).apply();
        if (enabled && !previous) rearm(context, kind);
        if (previous != enabled) clearCheck(context, kind);
        if (!enabled) cancel(context, notificationId(kind));
        if (kind == 2) rescheduleKnown(context);
        updateWidgets(context);
    }

    static synchronized void setThreshold(Context context, int i) {
        int iClampThresholdPercent = AlertPolicy.clampThresholdPercent(i);
        boolean z = config(context).lowThresholdPercent != iClampThresholdPercent;
        context.getSharedPreferences(SETTINGS_PREFS, 0).edit().putInt("low_threshold", iClampThresholdPercent).apply();
        if (z) {
            rearm(context, 1);
            updateWidgets(context);
        }
    }

    static synchronized void setExpiryHours(Context context, int i) {
        int iClampExpiryHours = AlertPolicy.clampExpiryHours(i);
        boolean z = config(context).expiryHours != iClampExpiryHours;
        context.getSharedPreferences(SETTINGS_PREFS, 0).edit().putInt("expiry_hours", iClampExpiryHours).apply();
        if (z) {
            rearm(context, 2);
            cancel(context, EXPIRY_ID);
            rescheduleKnown(context);
        }
    }

    public static String summary(Context context) {
        AlertPolicy.Config settings = config(context);
        boolean recovered = recoveryEnabled(context);
        if (!settings.lowEnabled && !settings.expiryEnabled && !recovered) return "Avisos desactivados";
        if (!systemEnabled(context)) return "Avisos bloqueados en Android";
        List<String> enabled = new ArrayList<>();
        if (settings.lowEnabled) enabled.add("cuota al " + settings.lowThresholdPercent + " %");
        if (settings.expiryEnabled) enabled.add("resets " + settings.expiryHours + " h antes");
        if (recovered) enabled.add("cuota recuperada");
        return "Avisos: " + String.join(" · ", enabled);
    }

    static boolean systemEnabled(Context context) {
        NotificationManager notificationManagerManager = manager(context);
        return notificationManagerManager != null && notificationManagerManager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED);
    }

    static boolean categoryEnabled(Context context, int i) {
        if (systemEnabled(context)) {
            if (channelEnabled(context, channel(i))) {
                return true;
            }
        }
        return false;
    }

    static Intent androidSettings(Context context) {
        return new Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", context.getPackageName());
    }

    static Intent androidChannelSettings(Context context, int i) {
        return new Intent("android.settings.CHANNEL_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", context.getPackageName()).putExtra("android.provider.extra.CHANNEL_ID", channel(i));
    }

    static synchronized void rearm(Context context, int kind) {
        SharedPreferences saved = context.getSharedPreferences(STATE_PREFS, 0);
        if (kind == RECOVERED) {
            saved.edit().remove("recovery_observed_at").remove("recovery_blocked").commit();
        } else {
            AlertPolicy.State state = AlertPolicy.State.fromJson(saved.getString("state", null));
            SharedPreferences.Editor edit = saved.edit().putString("state", (kind == 1 ? state.rearmLowQuota()
                    : state.rearmExpiringResets()).toJson());
            if (kind == 2) edit.putInt("local_expiry_failures", 0);
            edit.commit();
        }
        clearCheck(context, kind);
    }

    static void ensureChannels(Context context) {
        NotificationManager notificationManagerManager = manager(context);
        if (notificationManagerManager == null) {
            return;
        }
        NotificationChannel notificationChannel = new NotificationChannel(LOW_CHANNEL, "Cuota de Codex baja", NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setDescription("Aviso al quedar poca cuota; solo después de actualizar.");
        NotificationChannel notificationChannel2 = new NotificationChannel(EXPIRY_CHANNEL, "Resets próximos a caducar", NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel2.setDescription("Aviso antes de que caduque un reset disponible.");
        notificationManagerManager.createNotificationChannel(notificationChannel);
        notificationManagerManager.createNotificationChannel(notificationChannel2);
        NotificationChannel recovery = new NotificationChannel(RECOVERY_CHANNEL, "Cuota de Codex recuperada", NotificationManager.IMPORTANCE_DEFAULT);
        recovery.setDescription("Solo cuando una consulta confirma que puedes volver a usar Codex.");
        notificationManagerManager.createNotificationChannel(recovery);
    }

    public static synchronized void onSnapshot(Context context, String identity, QuotaSnapshot snapshot) {
        Context app = context.getApplicationContext();
        if (identity == null || identity.isEmpty() || snapshot == null) return;
        try {
            String account = fingerprint(identity);
            SharedPreferences saved = app.getSharedPreferences(STATE_PREFS, 0);
            if (!account.equals(saved.getString("account", null))) {
                clear(app);
                saved.edit().putString("account", account).commit();
            }
            ensureChannels(app);
            AlertPolicy.Config settings = config(app);
            long now = System.currentTimeMillis();
            AlertPolicy.Evaluation evaluation = AlertPolicy.evaluate(snapshot, settings,
                    AlertPolicy.State.fromJson(saved.getString("state", null)), now);
            if (!evaluation.fresh) {
                recordCheck(app, 1, "STALE");
                recordCheck(app, 2, "STALE");
                return;
            }
            saved.edit().putString("known_snapshot", snapshot.toJson())
                    .putInt("local_expiry_failures", 0).commit();
            cancelOutdated(app, snapshot, settings);
            List<AlertPolicy.Event> low = new ArrayList<>();
            List<AlertPolicy.Event> expiry = new ArrayList<>();
            for (AlertPolicy.Event event : evaluation.events) {
                (event.kind == 1 ? low : expiry).add(event);
            }
            evaluateDelivery(app, low, 1, settings.lowEnabled,
                    hasLowCondition(snapshot, settings, now), evaluation, snapshot, settings);
            evaluateDelivery(app, expiry, 2, settings.expiryEnabled,
                    hasExpiryCondition(snapshot, settings, now), evaluation, snapshot, settings);
            saved.edit().putString("state", evaluation.state.toJson()).commit();
            evaluateRecovery(app, snapshot, now);
            scheduleKnown(app, snapshot, evaluation.state, 0);
        } catch (Exception error) {
            recordCheck(app, 1, "ERROR");
            recordCheck(app, 2, "ERROR");
        }
    }

    private static void evaluateDelivery(Context context, List<AlertPolicy.Event> list, int i, boolean z, boolean z2, AlertPolicy.Evaluation evaluation, QuotaSnapshot quotaSnapshot, AlertPolicy.Config config) {
        if (!z) {
            recordCheck(context, i, "DISABLED");
            return;
        }
        String strBlockedReason = blockedReason(context, i);
        if (strBlockedReason != null) {
            recordCheck(context, i, strBlockedReason);
            return;
        }
        if (list.isEmpty()) {
            String str = z2 ? "BELOW_NOTIFIED" : "ABOVE";
            if (!z2 && i == 1) {
                Iterator<QuotaWindow> it = quotaSnapshot.windows.iterator();
                while (it.hasNext()) {
                    if (it.next().remainingPercent() <= config.lowThresholdPercent) {
                        str = "EXPIRED";
                        break;
                    }
                }
            }
            if (i == 2 && (quotaSnapshot.resetCredits == null || quotaSnapshot.resetCredits.credits == null)) {
                str = "UNKNOWN";
            }
            recordCheck(context, i, str);
            return;
        }
        String strDeliver = deliver(context, quotaSnapshot, config, i);
        recordCheck(context, i, strDeliver);
        if ("SENT".equals(strDeliver)) {
            evaluation.markDelivered(i);
        }
    }

    public static synchronized void clear(Context context) {
        cancel(context, LOW_ID);
        cancel(context, EXPIRY_ID);
        cancel(context, LOW_TEST_ID);
        cancel(context, EXPIRY_TEST_ID);
        cancel(context, RECOVERY_ID);
        cancel(context, RECOVERY_TEST_ID);
        cancelExpiryAlarm(context);
        context.getSharedPreferences(STATE_PREFS, 0).edit().clear().commit();
        context.getSharedPreferences(DIAGNOSTIC_PREFS, 0).edit().clear().commit();
    }

    private static String deliver(Context context, QuotaSnapshot snapshot, AlertPolicy.Config config, int kind) {
        return deliver(context, snapshot, config, kind, false);
    }

    private static String deliver(Context context, QuotaSnapshot quotaSnapshot, AlertPolicy.Config config, int i, boolean knownDate) {
        String str;
        boolean z;
        String strBlockedReason = blockedReason(context, i);
        if (strBlockedReason != null) {
            return strBlockedReason;
        }
        try {
            StringBuilder sb = new StringBuilder();
            long jCurrentTimeMillis = System.currentTimeMillis();
            long jMax = 0;
            if (i == 1) {
                List<QuotaWindow> listLowWindows = lowWindows(quotaSnapshot, config, jCurrentTimeMillis);
                if (listLowWindows.isEmpty()) {
                    return "EXPIRED";
                }
                str = listLowWindows.size() == 1 ? "Te queda poca cuota de Codex" : "Cuotas de Codex próximas al límite";
                z = true;
                for (QuotaWindow quotaWindow : listLowWindows) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(Formatters.windowTitle(quotaWindow));
                    if (quotaWindow.resetAtMillis == null) {
                        z = false;
                    } else {
                        sb.append(" · Renueva ");
                        sb.append(Formatters.date(quotaWindow.resetAtMillis.longValue()));
                        jMax = Math.max(jMax, quotaWindow.resetAtMillis.longValue());
                    }
                }
            } else {
                List<ResetCredit> listExpiringCredits = expiringCredits(quotaSnapshot, config, jCurrentTimeMillis);
                if (listExpiringCredits.isEmpty()) {
                    return "EXPIRED";
                }
                str = listExpiringCredits.size() == 1 ? "Un reset de Codex va a caducar" : listExpiringCredits.size() + " resets de Codex van a caducar";
                for (ResetCredit resetCredit : listExpiringCredits) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(Formatters.creditTitle(resetCredit));
                    sb.append(" · Caduca ");
                    sb.append(Formatters.date(resetCredit.expiresAtMillis.longValue()));
                    jMax = Math.max(jMax, resetCredit.expiresAtMillis.longValue());
                }
                z = true;
            }
            if (knownDate) {
                sb.append("\nSegún la última consulta: ").append(Formatters.date(quotaSnapshot.fetchedAtMillis));
                sb.append(". Abre la app para confirmar que siguen disponibles.");
            }
            String string = sb.toString();
            PendingIntent activity = PendingIntent.getActivity(context, i == 1 ? 31 : 32, new Intent(context, (Class<?>) MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String str2 = channel(i);
            Notification.Builder publicVersion = new Notification.Builder(context, str2).setSmallIcon(R.drawable.ic_notification).setContentTitle(str).setContentText(string).setStyle(new Notification.BigTextStyle().bigText(string)).setContentIntent(activity).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(new Notification.Builder(context, str2).setSmallIcon(R.drawable.ic_notification).setContentTitle("Cuota Codex").setContentText("Tienes un aviso de cuota o de resets.").build());
            if (z && jMax > jCurrentTimeMillis) {
                publicVersion.setTimeoutAfter(jMax - jCurrentTimeMillis);
            }
            manager(context).notify(notificationId(i), publicVersion.build());
            return "SENT";
        } catch (RuntimeException unused) {
            return "ERROR";
        }
    }

    private static void cancelOutdated(Context context, QuotaSnapshot quotaSnapshot, AlertPolicy.Config config) {
        long jCurrentTimeMillis = System.currentTimeMillis();
        boolean z = config.lowEnabled && hasLowCondition(quotaSnapshot, config, jCurrentTimeMillis);
        boolean z2 = config.expiryEnabled && hasExpiryCondition(quotaSnapshot, config, jCurrentTimeMillis);
        if (!z) {
            cancel(context, LOW_ID);
        }
        if (z2) {
            return;
        }
        if (config.expiryEnabled) {
            if (quotaSnapshot.resetCredits == null) {
                return;
            }
            if (quotaSnapshot.resetCredits.availableCount != 0 && quotaSnapshot.resetCredits.credits == null) {
                return;
            }
        }
        cancel(context, EXPIRY_ID);
    }

    private static boolean hasLowCondition(QuotaSnapshot quotaSnapshot, AlertPolicy.Config config, long j) {
        return !lowWindows(quotaSnapshot, config, j).isEmpty();
    }

    private static List<QuotaWindow> lowWindows(QuotaSnapshot quotaSnapshot, AlertPolicy.Config config, long j) {
        List<QuotaWindow> arrayList = new ArrayList<>();
        for (QuotaWindow quotaWindow : quotaSnapshot.windows) {
            if (quotaWindow.remainingPercent() <= config.lowThresholdPercent && (quotaWindow.resetAtMillis == null || quotaWindow.resetAtMillis.longValue() > j)) {
                arrayList.add(quotaWindow);
            }
        }
        return arrayList;
    }

    private static boolean hasExpiryCondition(QuotaSnapshot quotaSnapshot, AlertPolicy.Config config, long j) {
        return !expiringCredits(quotaSnapshot, config, j).isEmpty();
    }

    private static List<ResetCredit> expiringCredits(QuotaSnapshot quotaSnapshot, AlertPolicy.Config config, long j) {
        List<ResetCredit> arrayList = new ArrayList<>();
        if (quotaSnapshot.resetCredits != null && quotaSnapshot.resetCredits.availableCount > 0 && quotaSnapshot.resetCredits.credits != null) {
            HashSet<String> hashSet = new HashSet<>();
            for (ResetCredit resetCredit : quotaSnapshot.resetCredits.availableByExpiry(j)) {
                if (resetCredit.id != null && !resetCredit.id.trim().isEmpty() && resetCredit.expiresAtMillis != null && resetCredit.expiresAtMillis.longValue() - j <= ((long) (config.expiryHours * 3600)) * 1000) {
                    if (hashSet.add(resetCredit.id + "|" + resetCredit.expiresAtMillis)) {
                        arrayList.add(resetCredit);
                    }
                }
            }
        }
        return arrayList;
    }

    static synchronized String sendTest(Context context, int i) {
        String strBlockedReason;
        try {
            ensureChannels(context);
            strBlockedReason = blockedReason(context, i);
            if (strBlockedReason == null) {
                String str = channel(i);
                String str2 = i == 1 ? "Prueba de aviso de cuota baja" : i == 2
                        ? "Prueba de aviso de caducidad" : "Prueba de cuota recuperada";
                manager(context).notify(i == 1 ? LOW_TEST_ID : i == 2 ? EXPIRY_TEST_ID : RECOVERY_TEST_ID, new Notification.Builder(context, str).setSmallIcon(R.drawable.ic_notification).setContentTitle(str2).setContentText("Los avisos de este tipo usan este mismo canal de Android.").setContentIntent(PendingIntent.getActivity(context, 40 + i, new Intent(context, (Class<?>) MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(new Notification.Builder(context, str).setSmallIcon(R.drawable.ic_notification).setContentTitle("Cuota Codex").setContentText("Prueba de notificaciones.").build()).setTimeoutAfter(AlertPolicy.MAX_SNAPSHOT_AGE_MILLIS).build());
                strBlockedReason = "SENT";
            }
        } catch (RuntimeException unused) {
            strBlockedReason = "ERROR";
        }
        recordTest(context, i, strBlockedReason);
        if ("SENT".equals(strBlockedReason)) {
            return "Aviso de prueba enviado a Android. Revisa el panel de notificaciones.";
        }
        if ("GLOBAL_BLOCKED".equals(strBlockedReason)) {
            return "Android bloquea las notificaciones. Activa un aviso para dar permiso o abre Permisos de Android.";
        }
        return "CHANNEL_BLOCKED".equals(strBlockedReason) ? "Este tipo de aviso está bloqueado. Abre Ajustes de este aviso." : "Android no pudo crear el aviso. Prueba de nuevo o revisa sus ajustes.";
    }

    static String checkStatus(Context context, int kind) {
        SharedPreferences saved = context.getSharedPreferences(DIAGNOSTIC_PREFS, 0);
        String prefix = prefix(kind);
        String result = saved.getString(prefix + "_check_result", "WAITING");
        String message;
        switch (result) {
            case "EXPIRED": message = "Ese periodo ya terminó; no se avisa con datos vencidos."; break;
            case "CHANNEL_BLOCKED": message = "Este tipo de aviso está bloqueado en Android."; break;
            case "GLOBAL_BLOCKED": message = "Android bloquea las notificaciones."; break;
            case "SENT": message = "Aviso enviado a Android."; break;
            case "LOCAL_SENT": message = "Recordatorio enviado con la fecha de la última consulta."; break;
            case "ABOVE": message = kind == 1 ? "La cuota no ha alcanzado el umbral."
                    : kind == 2 ? "Ningún reset con fecha ha alcanzado el plazo."
                    : "Aún no se ha confirmado una recuperación de cuota."; break;
            case "ERROR": message = "No se pudo preparar o enviar el aviso."; break;
            case "STALE": message = "Sin datos nuevos para evaluar el aviso."; break;
            case "UNKNOWN": message = "Faltan los detalles de caducidad de los resets."; break;
            case "DISABLED": message = "Aviso desactivado."; break;
            case "BELOW_NOTIFIED": message = "Ya se avisó para este ajuste; no se repite hasta recuperarse o renovar."; break;
            case "WAITING_RECOVERY": message = "Esperando una consulta que confirme cuota recuperada."; break;
            case "CREDIT_RESOLVED": message = "El reset canjeado ya no tiene un recordatorio pendiente."; break;
            default: message = "Pendiente de la próxima consulta.";
        }
        long checked = saved.getLong(prefix + "_check_at", 0);
        if (checked > 0) message = "Comprobado " + Formatters.date(checked) + " · " + message;
        long sent = saved.getLong(prefix + "_sent_at", 0);
        if (sent > 0 && !"SENT".equals(result) && !"LOCAL_SENT".equals(result))
            message += "\nÚltimo aviso enviado a Android: " + Formatters.date(sent) + ".";
        if (kind == 2) {
            long scheduled = saved.getLong("expiry_scheduled_at", 0);
            if (scheduled > 0) message += "\nRecordatorio previsto desde " + Formatters.date(scheduled)
                    + "; Android puede retrasarlo.";
        }
        long tested = saved.getLong(prefix + "_test_at", 0);
        if (tested > 0) {
            String test = saved.getString(prefix + "_test_result", "ERROR");
            message += "\nPrueba " + Formatters.date(tested) + " · "
                    + ("SENT".equals(test) ? "enviada a Android."
                    : "GLOBAL_BLOCKED".equals(test) || "CHANNEL_BLOCKED".equals(test)
                    ? "bloqueada en Android." : "no se pudo enviar.");
        }
        return message;
    }

    private static String blockedReason(Context context, int i) {
        if (!systemEnabled(context)) {
            return "GLOBAL_BLOCKED";
        }
        if (channelEnabled(context, channel(i))) {
            return null;
        }
        return "CHANNEL_BLOCKED";
    }

    private static String prefix(int i) {
        return i == 1 ? "low" : i == 2 ? "expiry" : "recovery";
    }

    private static void clearCheck(Context context, int i) {
        context.getSharedPreferences(DIAGNOSTIC_PREFS, 0).edit().remove(prefix(i) + "_check_at").remove(prefix(i) + "_check_result").apply();
    }

    private static void recordCheck(Context context, int i, String str) {
        try {
            long jCurrentTimeMillis = System.currentTimeMillis();
            String strPrefix = prefix(i);
            SharedPreferences.Editor editorPutString = context.getSharedPreferences(DIAGNOSTIC_PREFS, 0).edit().putLong(strPrefix + "_check_at", jCurrentTimeMillis).putString(strPrefix + "_check_result", str);
            if ("SENT".equals(str) || "LOCAL_SENT".equals(str)) {
                editorPutString.putLong(strPrefix + "_sent_at", jCurrentTimeMillis);
            }
            editorPutString.apply();
        } catch (RuntimeException unused) {
        }
    }

    private static void recordTest(Context context, int i, String str) {
        try {
            context.getSharedPreferences(DIAGNOSTIC_PREFS, 0).edit().putLong(prefix(i) + "_test_at", System.currentTimeMillis()).putString(prefix(i) + "_test_result", str).apply();
        } catch (RuntimeException unused) {
        }
    }

    private static boolean channelEnabled(Context context, String str) {
        NotificationManager notificationManagerManager = manager(context);
        NotificationChannel notificationChannel = notificationManagerManager == null ? null : notificationManagerManager.getNotificationChannel(str);
        return notificationChannel == null || notificationChannel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static void cancel(Context context, int i) {
        try {
            NotificationManager notificationManagerManager = manager(context);
            if (notificationManagerManager != null) {
                notificationManagerManager.cancel(i);
            }
        } catch (RuntimeException unused) {
        }
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static String fingerprint(String str) throws Exception {
        byte[] bArrDigest = MessageDigest.getInstance("SHA-256").digest(str.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bArrDigest) {
            sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
        }
        return sb.toString();
    }

    private static String channel(int kind) {
        return kind == 1 ? LOW_CHANNEL : kind == 2 ? EXPIRY_CHANNEL : RECOVERY_CHANNEL;
    }

    private static int notificationId(int kind) {
        return kind == 1 ? LOW_ID : kind == 2 ? EXPIRY_ID : RECOVERY_ID;
    }

    static String compactStatus(Context context, int kind) {
        AlertPolicy.Config settings = config(context);
        boolean enabled = kind == 1 ? settings.lowEnabled : kind == 2
                ? settings.expiryEnabled : recoveryEnabled(context);
        if (!enabled) return "Desactivado";
        if (!categoryEnabled(context, kind)) return "Bloqueado en Android · abre los detalles";
        String description = kind == 1 ? settings.lowThresholdPercent + " % restante" : kind == 2
                ? settings.expiryHours + " h antes" : "cuando el servidor confirme la recuperación";
        long checked = context.getSharedPreferences(DIAGNOSTIC_PREFS, 0)
                .getLong(prefix(kind) + "_check_at", 0L);
        return "Activo · " + description + (checked > 0
                ? "\nComprobado " + Formatters.date(checked) : "\nPendiente de comprobar");
    }

    private static void evaluateRecovery(Context context, QuotaSnapshot snapshot, long now) {
        SharedPreferences saved = context.getSharedPreferences(STATE_PREFS, 0);
        boolean enabled = recoveryEnabled(context);
        RecoveryPolicy.State previous = new RecoveryPolicy.State(
                saved.getLong("recovery_observed_at", 0), saved.getBoolean("recovery_blocked", false));
        RecoveryPolicy.Result result = RecoveryPolicy.evaluate(previous, enabled, snapshot, now);
        RecoveryPolicy.State state = result.state;
        String delivery = enabled ? "WAITING_RECOVERY" : "DISABLED";
        if (Boolean.FALSE.equals(snapshot.ordinaryUsageAllowed)) cancel(context, RECOVERY_ID);
        if (result.shouldNotify) {
            delivery = deliverRecovery(context);
            if ("SENT".equals(delivery)) state = RecoveryPolicy.delivered(state);
        }
        saved.edit().putLong("recovery_observed_at", state.lastObservedAt)
                .putBoolean("recovery_blocked", state.blocked).commit();
        recordCheck(context, RECOVERED, delivery);
    }

    private static String deliverRecovery(Context context) {
        String blocked = blockedReason(context, RECOVERED);
        if (blocked != null) return blocked;
        try {
            PendingIntent open = PendingIntent.getActivity(context, 33,
                    new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, RECOVERY_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Ya puedes volver a usar Codex")
                    .setContentText("La última consulta confirma que vuelves a tener uso disponible.")
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setTimeoutAfter(3_600_000L)
                    .setPublicVersion(new Notification.Builder(context, RECOVERY_CHANNEL)
                            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Cuota Codex Beta")
                            .setContentText("Tienes un aviso de cuota.").build()).build();
            manager(context).notify(RECOVERY_ID, notification);
            return "SENT";
        } catch (RuntimeException error) {
            return "ERROR";
        }
    }

    /** Re-arm using known dates after settings/permissions changes, boot or package update. */
    public static synchronized void rescheduleKnown(Context context) {
        Context app = context.getApplicationContext();
        try {
            ensureChannels(app);
            QuotaSnapshot snapshot = validatedKnownSnapshot(app);
            if (snapshot == null) {
                cancelExpiryAlarm(app);
                return;
            }
            AlertPolicy.State state = AlertPolicy.State.fromJson(
                    app.getSharedPreferences(STATE_PREFS, 0).getString("state", null));
            scheduleKnown(app, snapshot, state, 0);
        } catch (Exception error) {
            cancelExpiryAlarm(app);
            recordCheck(app, 2, "ERROR");
        }
    }

    /** Local reminder only: no network refresh or credit redemption. */
    public static synchronized void onKnownExpiry(Context context) {
        Context app = context.getApplicationContext();
        try {
            ensureChannels(app);
            QuotaSnapshot snapshot = validatedKnownSnapshot(app);
            AlertPolicy.Config settings = config(app);
            if (snapshot == null || !settings.expiryEnabled) {
                cancelExpiryAlarm(app);
                return;
            }
            SharedPreferences saved = app.getSharedPreferences(STATE_PREFS, 0);
            long now = System.currentTimeMillis();
            AlertPolicy.Evaluation evaluation = AlertPolicy.evaluateKnownExpiry(snapshot, settings,
                    AlertPolicy.State.fromJson(saved.getString("state", null)), now);
            String delivery = "ABOVE";
            if (!evaluation.events.isEmpty()) {
                delivery = deliver(app, snapshot, settings, 2, true);
                if ("SENT".equals(delivery)) evaluation.markDelivered(2);
                recordCheck(app, 2, "SENT".equals(delivery) ? "LOCAL_SENT" : delivery);
            }
            saved.edit().putString("state", evaluation.state.toJson()).commit();
            int failures = "ERROR".equals(delivery) ? saved.getInt("local_expiry_failures", 0) + 1 : 0;
            saved.edit().putInt("local_expiry_failures", failures).commit();
            if (failures >= 3) {
                cancelExpiryAlarm(app);
                return;
            }
            scheduleKnown(app, snapshot, evaluation.state,
                    "ERROR".equals(delivery) ? now + LOCAL_RETRY_DELAY : 0);
        } catch (Exception error) {
            cancelExpiryAlarm(app);
            recordCheck(app, 2, "ERROR");
        }
    }

    private static QuotaSnapshot validatedKnownSnapshot(Context context) throws Exception {
        QuotaRepository repository = QuotaRepository.get(context);
        String identity = repository.sessionIdentity();
        if (!repository.signedIn() || identity == null) return null;
        SharedPreferences saved = context.getSharedPreferences(STATE_PREFS, 0);
        if (!fingerprint(identity).equals(saved.getString("account", null))) return null;
        String json = saved.getString("known_snapshot", null);
        return json == null ? null : QuotaSnapshot.fromJson(json);
    }

    /** Confirmed redemption invalidates its known reminder even if the following GET fails. */
    public static synchronized void onCreditResolved(Context context, String identity, String creditId) {
        Context app = context.getApplicationContext();
        try {
            if (identity == null) return;
            SharedPreferences saved = app.getSharedPreferences(STATE_PREFS, 0);
            if (!fingerprint(identity).equals(saved.getString("account", null))) return;
            QuotaSnapshot known = validatedKnownSnapshot(app);
            if (known == null) return;
            if (creditId == null || creditId.trim().isEmpty()) {
                // An older pending request has no selected ID. Do not guess which credit
                // was consumed; suspend known-date reminders until a new successful query.
                saved.edit().remove("known_snapshot").commit();
                cancelExpiryAlarm(app);
                cancel(app, EXPIRY_ID);
                recordCheck(app, 2, "UNKNOWN");
                return;
            }
            if (known.resetCredits == null || known.resetCredits.credits == null) return;
            List<ResetCredit> remaining = new ArrayList<>();
            boolean removed = false;
            for (ResetCredit credit : known.resetCredits.credits) {
                if (creditId.equals(credit.id)) removed = true;
                else remaining.add(credit);
            }
            if (!removed) return;
            ResetCredits credits = new ResetCredits(Math.max(0, known.resetCredits.availableCount - 1),
                    remaining, known.resetCredits.detailsError);
            QuotaSnapshot updated = known.withResetCredits(credits);
            cancelExpiryAlarm(app);
            cancel(app, EXPIRY_ID);
            if (!saved.edit().putString("known_snapshot", updated.toJson()).commit()) return;
            AlertPolicy.State state = AlertPolicy.State.fromJson(saved.getString("state", null));
            scheduleKnown(app, updated, state, 0);
            recordCheck(app, 2, "CREDIT_RESOLVED");
        } catch (Exception error) {
            cancelExpiryAlarm(app);
            cancel(app, EXPIRY_ID);
        }
    }

    public static void onCreditResolved(Context context, String creditId) {
        onCreditResolved(context, QuotaRepository.get(context).sessionIdentity(), creditId);
    }

    private static void scheduleKnown(Context context, QuotaSnapshot snapshot,
                                      AlertPolicy.State state, long retryNotBefore) {
        cancelExpiryAlarm(context);
        AlertPolicy.Config settings = config(context);
        if (!settings.expiryEnabled || !categoryEnabled(context, 2) || snapshot.resetCredits == null
                || snapshot.resetCredits.availableCount <= 0 || snapshot.resetCredits.credits == null) return;
        long now = System.currentTimeMillis();
        List<ResetCredit> pending = new ArrayList<>();
        for (ResetCredit credit : snapshot.resetCredits.availableByExpiry(now)) {
            if (!state.expiryDelivered(credit, settings.expiryHours)) pending.add(credit);
        }
        Long next = ExpirySchedulePolicy.nextTriggerAt(pending, settings.expiryHours, now, retryNotBefore);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (next == null || alarms == null) return;
        // This is deliberately inexact and requires no exact-alarm permission.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, expiryAlarm(context, false));
        context.getSharedPreferences(DIAGNOSTIC_PREFS, 0).edit()
                .putLong("expiry_scheduled_at", next).apply();
    }

    private static PendingIntent expiryAlarm(Context context, boolean existingOnly) {
        int flags = PendingIntent.FLAG_IMMUTABLE
                | (existingOnly ? PendingIntent.FLAG_NO_CREATE : PendingIntent.FLAG_UPDATE_CURRENT);
        return PendingIntent.getBroadcast(context, EXPIRY_ALARM_REQUEST,
                new Intent(context, ResetExpiryReceiver.class).setAction(EXPIRY_ALARM_ACTION), flags);
    }

    private static void cancelExpiryAlarm(Context context) {
        try {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pending = expiryAlarm(context, true);
            if (alarms != null && pending != null) alarms.cancel(pending);
            if (pending != null) pending.cancel();
            context.getSharedPreferences(DIAGNOSTIC_PREFS, 0).edit().remove("expiry_scheduled_at").apply();
        } catch (RuntimeException ignored) {
            // A later boot, settings change or successful query retries registration.
        }
    }

    private static void updateWidgets(Context context) {
        QuotaWidget.renderAll(context);
        context.sendBroadcast(new Intent(QuotaRepository.CHANGED).setPackage(context.getPackageName()));
    }

    private QuotaAlerts() {
    }
}
