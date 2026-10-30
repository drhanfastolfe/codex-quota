package es.codexquota.mobile.core

/** Retry only read operations; never reuse this policy for a credit redemption. */
object QueryRetryPolicy {
    const val MAX_RETRIES = 3
    private const val FIRST_DELAY_MILLIS = 30_000L

    /** failedAttempts starts at one. -1 means stop until a new normal/manual query. */
    @JvmStatic
    fun delayMillis(error: Exception, failedAttempts: Int): Long {
        if (failedAttempts !in 1..MAX_RETRIES || error !is ApiException) return -1
        if (error.authenticationRequired || !error.retryable) return -1
        val status = error.httpStatus
        if (status != 0 && status != 429 && status !in 500..599) return -1
        val backoff = FIRST_DELAY_MILLIS shl (failedAttempts - 1)
        return maxOf(backoff, error.retryAfterMillis?.coerceAtLeast(0L) ?: 0L)
    }

    @JvmStatic
    fun dueAt(nowMillis: Long, delayMillis: Long): Long =
        if (delayMillis > Long.MAX_VALUE - nowMillis.coerceAtLeast(0L)) Long.MAX_VALUE
        else nowMillis.coerceAtLeast(0L) + delayMillis.coerceAtLeast(0L)
}
