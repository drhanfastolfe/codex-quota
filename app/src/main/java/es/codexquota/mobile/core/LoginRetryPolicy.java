package es.codexquota.mobile.core;

public final class LoginRetryPolicy {
    private LoginRetryPolicy() {
    }

    public static boolean canRetry(ApiException apiException, long j, long j2) {
        return apiException.retryable && !apiException.authenticationRequired && j2 < j;
    }

    public static long delayMillis(ApiException apiException, int i) {
        long jMin = Math.min(30000L, (1 << Math.min(3, Math.max(0, i - 1))) * 5000);
        return apiException.retryAfterMillis == null ? jMin : Math.max(jMin, apiException.retryAfterMillis.longValue());
    }
}
