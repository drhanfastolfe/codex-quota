package es.codexquota.mobile;

import es.codexquota.mobile.core.QuotaWindow;
import es.codexquota.mobile.core.RelativeTimes;
import es.codexquota.mobile.core.ResetCredit;
import java.text.DateFormat;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

final class Formatters {
    Formatters() {
    }

    static String date(long j) {
        return DateFormat.getDateTimeInstance(3, 3, Locale.getDefault()).format(new Date(j));
    }

    static String remaining(QuotaWindow quotaWindow) {
        return String.format(Locale.getDefault(), "%.0f %%", Double.valueOf(Math.max(0.0d, Math.min(100.0d, 100.0d - quotaWindow.usedPercent))));
    }

    static String windowTitle(QuotaWindow quotaWindow) {
        return quotaWindow.label + " · " + remaining(quotaWindow) + " libre";
    }

    static String creditDate(ResetCredit resetCredit) {
        if (resetCredit.expiresAtMillis == null) {
            return "Sin caducidad indicada";
        }
        return RelativeTimes.expiry(System.currentTimeMillis(), resetCredit.expiresAtMillis.longValue(), ZoneId.systemDefault()) + " · " + date(resetCredit.expiresAtMillis.longValue());
    }

    static String creditTitle(ResetCredit resetCredit) {
        return resetCredit.title == null ? "Reset de Codex" : resetCredit.title;
    }

    static String compactRenewal(QuotaWindow quotaWindow) {
        if (quotaWindow == null || quotaWindow.resetAtMillis == null) {
            return "Fecha no disponible";
        }
        long jCurrentTimeMillis = System.currentTimeMillis();
        if (quotaWindow.resetAtMillis.longValue() <= jCurrentTimeMillis) {
            return "Pendiente · actualiza";
        }
        return "Renueva " + RelativeTimes.until(jCurrentTimeMillis, quotaWindow.resetAtMillis.longValue());
    }

    static String renewal(QuotaWindow quotaWindow) {
        if (quotaWindow.resetAtMillis == null) {
            return "Renovación sin fecha disponible";
        }
        return compactRenewal(quotaWindow) + "\n" + date(quotaWindow.resetAtMillis.longValue());
    }

    static String updated(long j) {
        return RelativeTimes.updated(System.currentTimeMillis(), j);
    }

    static String compactAge(long j) {
        String strUpdated = updated(j);
        return strUpdated.startsWith("Consultado ") ? strUpdated.substring(11) : "sin consulta";
    }
}
