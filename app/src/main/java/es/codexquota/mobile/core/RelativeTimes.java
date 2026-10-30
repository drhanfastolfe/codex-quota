package es.codexquota.mobile.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class RelativeTimes {
    private RelativeTimes() {
    }

    public static String until(long j, long j2) {
        long jSubtractExact;
        if (j2 <= j) {
            return "ahora";
        }
        try {
            jSubtractExact = Math.subtractExact(j2, j);
        } catch (ArithmeticException unused) {
            jSubtractExact = Long.MAX_VALUE;
        }
        long j3 = (jSubtractExact / 60000) + ((long) (jSubtractExact % 60000 == 0 ? 0 : 1));
        if (j3 < 60) {
            return "en " + j3 + " min";
        }
        long j4 = j3 / 60;
        long j5 = j3 % 60;
        String str = "";
        if (j4 < 24) {
            StringBuilder sb = new StringBuilder("en ");
            sb.append(j4);
            sb.append(" h");
            if (j5 > 0) {
                str = " " + j5 + " min";
            }
            sb.append(str);
            return sb.toString();
        }
        long j6 = j4 / 24;
        long j7 = j4 % 24;
        StringBuilder sb2 = new StringBuilder("en ");
        sb2.append(j6);
        sb2.append(j6 == 1 ? " día" : " días");
        if (j7 > 0) {
            str = " " + j7 + " h";
        }
        sb2.append(str);
        return sb2.toString();
    }

    public static String expiry(long j, long j2, ZoneId zoneId) {
        if (j2 <= j) {
            return "Caducó";
        }
        if (ChronoUnit.DAYS.between(Instant.ofEpochMilli(j).atZone(zoneId).toLocalDate(), Instant.ofEpochMilli(j2).atZone(zoneId).toLocalDate()) == 1 && j2 - j >= 86400000) {
            return "Caduca mañana";
        }
        return "Caduca " + until(j, j2);
    }

    public static String updated(long j, long j2) {
        if (j2 <= 0) {
            return "Sin consulta todavía";
        }
        if (j2 > j) {
            return "Hora de consulta por comprobar";
        }
        long j3 = (j - j2) / 60000;
        if (j3 == 0) {
            return "Consultado ahora";
        }
        if (j3 < 60) {
            return "Consultado hace " + j3 + " min";
        }
        long j4 = j3 / 60;
        if (j4 < 24) {
            return "Consultado hace " + j4 + " h";
        }
        long j5 = j4 / 24;
        StringBuilder sb = new StringBuilder("Consultado hace ");
        sb.append(j5);
        sb.append(j5 == 1 ? " día" : " días");
        return sb.toString();
    }
}
