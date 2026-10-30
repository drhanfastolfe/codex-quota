package es.codexquota.mobile.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class QueryRetryPolicyTest {
    @Test public void retriesAreBoundedAndExponential() {
        ApiException transientFailure = new ApiException("temporary", false, 503, null, true, true, "HTTP_503");
        assertEquals(30_000L, QueryRetryPolicy.delayMillis(transientFailure, 1));
        assertEquals(60_000L, QueryRetryPolicy.delayMillis(transientFailure, 2));
        assertEquals(120_000L, QueryRetryPolicy.delayMillis(transientFailure, 3));
        assertEquals(-1L, QueryRetryPolicy.delayMillis(transientFailure, 4));
    }

    @Test public void respectsRetryAfterEvenWhenLongerThanUsualInterval() {
        ApiException limited = new ApiException("wait", false, 429, 7_200_000L, true, true, "HTTP_429");
        assertEquals(7_200_000L, QueryRetryPolicy.delayMillis(limited, 1));
    }

    @Test public void doesNotRetryAuthenticationConfigurationOrUnknownFailures() {
        assertEquals(-1L, QueryRetryPolicy.delayMillis(new ApiException("sign in", true, 401), 1));
        assertEquals(-1L, QueryRetryPolicy.delayMillis(new ApiException("forbidden", false, 403), 1));
        assertEquals(-1L, QueryRetryPolicy.delayMillis(new ApiException("format", false, 0), 1));
        assertEquals(-1L, QueryRetryPolicy.delayMillis(new IllegalStateException(), 1));
        assertEquals(-1L, QueryRetryPolicy.delayMillis(new ApiException("unsafe", true, 503, null, true, true, "X"), 1));
    }

    @Test public void networkFailuresMayRetryAndArithmeticNeverWraps() {
        ApiException network = new ApiException("offline", false, 0, null, true, false, "NETWORK_DNS");
        assertEquals(30_000L, QueryRetryPolicy.delayMillis(network, 1));
        assertEquals(-1L, QueryRetryPolicy.delayMillis(network, 0));
        assertEquals(Long.MAX_VALUE, QueryRetryPolicy.dueAt(10L, Long.MAX_VALUE));
        assertEquals(30_010L, QueryRetryPolicy.dueAt(10L, 30_000L));
    }
}
