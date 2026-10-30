package es.codexquota.mobile.core

/** A recovery is a server-confirmed blocked -> allowed transition, never a clock event. */
object RecoveryPolicy {
    class State(@JvmField val lastObservedAt: Long, @JvmField val blocked: Boolean)
    class Result(@JvmField val state: State, @JvmField val shouldNotify: Boolean)

    @JvmStatic
    fun evaluate(state: State, enabled: Boolean, snapshot: QuotaSnapshot?, now: Long): Result {
        if (snapshot == null || snapshot.fetchedAtMillis <= state.lastObservedAt ||
            snapshot.fetchedAtMillis <= 0 || snapshot.fetchedAtMillis > now + 30_000L ||
            now - snapshot.fetchedAtMillis > AlertPolicy.MAX_SNAPSHOT_AGE_MILLIS) {
            return Result(state, false)
        }
        if (!enabled) return Result(State(snapshot.fetchedAtMillis, false), false)
        val allowed = snapshot.ordinaryUsageAllowed
        val blocked = when (allowed) {
            false -> true
            else -> state.blocked
        }
        return Result(State(snapshot.fetchedAtMillis, blocked), allowed == true && blocked)
    }

    @JvmStatic
    fun delivered(state: State): State = State(state.lastObservedAt, false)
}
