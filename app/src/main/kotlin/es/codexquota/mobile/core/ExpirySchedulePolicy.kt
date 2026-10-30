package es.codexquota.mobile.core

/** Select the next reminder from known, unnotified credits; no network or redemption. */
object ExpirySchedulePolicy {
    @JvmStatic
    fun nextTriggerAt(credits: List<ResetCredit>, hours: Int, now: Long,
                      retryNotBefore: Long): Long? {
        val lead = AlertPolicy.clampExpiryHours(hours).toLong() * 3_600_000L
        return credits.asSequence()
            .filter { it.isAvailable(now) && !it.id.isNullOrBlank() && it.expiresAtMillis != null }
            .mapNotNull { credit ->
                val expires = credit.expiresAtMillis!!
                val scheduled = maxOf(expires - lead, now + 1_000L, retryNotBefore)
                scheduled.takeIf { it < expires }
            }.minOrNull()
    }
}
