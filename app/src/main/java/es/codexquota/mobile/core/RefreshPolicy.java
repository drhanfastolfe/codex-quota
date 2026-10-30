package es.codexquota.mobile.core;

public final class RefreshPolicy {
    private static final long ANDROID_MIN_FLEX_MILLIS = 300000;
    public static final int DEFAULT_MINUTES = 30;
    public static final int MAX_MINUTES = 360;
    private static final long MINUTE_MILLIS = 60000;
    public static final int MIN_MINUTES = 15;
    private static final long PREFERRED_MAX_FLEX_MILLIS = 600000;
    public static final int STEP_MINUTES = 15;

    private RefreshPolicy() {
    }

    public static int normalizeMinutes(int i) {
        if (i <= 0) {
            return 30;
        }
        return ((Math.max(15, Math.min(MAX_MINUTES, i)) + 7) / 15) * 15;
    }

    public static long intervalMillis(int i) {
        return ((long) normalizeMinutes(i)) * MINUTE_MILLIS;
    }

    public static long flexMillis(int i) {
        long jIntervalMillis = intervalMillis(i);
        return Math.max(Math.max(300000L, jIntervalMillis / 20), Math.min(PREFERRED_MAX_FLEX_MILLIS, jIntervalMillis / 3));
    }

    public static boolean isStale(long j, long j2, int i) {
        return j <= 0 || j > j2 || j2 - j >= intervalMillis(i);
    }

    public static String label(int i) {
        String str;
        int iNormalizeMinutes = normalizeMinutes(i);
        if (iNormalizeMinutes < 60) {
            return iNormalizeMinutes + " min";
        }
        int i2 = iNormalizeMinutes / 60;
        int i3 = iNormalizeMinutes % 60;
        StringBuilder sb = new StringBuilder();
        sb.append(i2);
        sb.append(i2 == 1 ? " hora" : " horas");
        if (i3 == 0) {
            str = "";
        } else {
            str = " " + i3 + " min";
        }
        sb.append(str);
        return sb.toString();
    }
}
