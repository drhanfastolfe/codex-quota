package es.codexquota.mobile.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** Behavioral policy tests require neither Android nor a live ChatGPT account. */
public class AlertPoliciesTest {
    private static final long NOW = 30_000_000L;
    private static final long HOUR = 3_600_000L;

    private ResetCredit credit(String id, long expires) {
        return new ResetCredit(id, "codex_rate_limits", "available", "Reset", null, NOW - HOUR, expires);
    }
    private QuotaSnapshot quota(long fetched, double remaining, Boolean allowed, ResetCredit... credits) {
        return new QuotaSnapshot("plus", Collections.singletonList(
                new QuotaWindow("5 horas", "codex", 100 - remaining, NOW + HOUR, 18_000L)),
                new ResetCredits(credits.length, Arrays.asList(credits)), fetched, allowed);
    }
    private AlertPolicy.Config settings(int threshold, int hours) {
        return new AlertPolicy.Config(true, threshold, true, hours);
    }

    @Test public void userThresholdFiftyFiveTriggersAtFiftyFour() {
        AlertPolicy.Evaluation result = AlertPolicy.evaluate(quota(NOW, 54, true), settings(55, 1), null, NOW);
        assertEquals(1, result.events.size());
        assertEquals(AlertPolicy.LOW_QUOTA, result.events.get(0).kind);
    }
    @Test public void thresholdIsInclusive() {
        assertEquals(1, AlertPolicy.evaluate(quota(NOW, 55, true), settings(55, 1), null, NOW).events.size());
        assertEquals(0, AlertPolicy.evaluate(quota(NOW, 55.1, true), settings(55, 1), null, NOW).events.size());
    }
    @Test public void unsuccessfulDeliveryDoesNotConsumeLowEligibility() {
        AlertPolicy.Evaluation first = AlertPolicy.evaluate(quota(NOW, 30, true), settings(55, 1), null, NOW);
        AlertPolicy.Evaluation next = AlertPolicy.evaluate(quota(NOW + 1, 30, true), settings(55, 1), first.state, NOW + 1);
        assertEquals(1, next.events.size());
    }
    @Test public void deliveredLowIsDeduplicatedUntilRecovery() {
        AlertPolicy.Evaluation first = AlertPolicy.evaluate(quota(NOW, 30, true), settings(55, 1), null, NOW);
        first.markDelivered(1);
        AlertPolicy.Evaluation again = AlertPolicy.evaluate(quota(NOW + 1, 30, true), settings(55, 1), first.state, NOW + 1);
        assertTrue(again.events.isEmpty());
        AlertPolicy.Evaluation recovered = AlertPolicy.evaluate(quota(NOW + 2, 60, true), settings(55, 1), again.state, NOW + 2);
        AlertPolicy.Evaluation lowAgain = AlertPolicy.evaluate(quota(NOW + 3, 54, true), settings(55, 1), recovered.state, NOW + 3);
        assertEquals(1, lowAgain.events.size());
    }
    @Test public void changedThresholdRearmsItsOwnCondition() {
        AlertPolicy.Evaluation first = AlertPolicy.evaluate(quota(NOW, 30, true), settings(55, 1), null, NOW);
        first.markDelivered(1);
        assertEquals(1, AlertPolicy.evaluate(quota(NOW + 1, 30, true), settings(40, 1), first.state, NOW + 1).events.size());
    }
    @Test public void staleQuotaDoesNotCreateUsageNotification() {
        AlertPolicy.Evaluation result = AlertPolicy.evaluate(quota(NOW - HOUR, 10, false), settings(55, 1), null, NOW);
        assertFalse(result.fresh);
        assertTrue(result.events.isEmpty());
    }
    @Test public void knownExpiryCanNotifyBetweenUsageQueries() {
        QuotaSnapshot old = quota(NOW - 5 * HOUR, 10, false, credit("a", NOW + HOUR / 2));
        AlertPolicy.Evaluation result = AlertPolicy.evaluateKnownExpiry(old, settings(55, 1), null, NOW);
        assertEquals(1, result.events.size());
        assertEquals(AlertPolicy.EXPIRING_RESET, result.events.get(0).kind);
        assertEquals(0, result.state.lastEvaluatedAt);
    }
    @Test public void knownAndFreshExpiryShareDeliveryDeduplication() {
        ResetCredit credit = credit("a", NOW + HOUR / 2);
        AlertPolicy.Evaluation local = AlertPolicy.evaluateKnownExpiry(quota(NOW - HOUR, 90, true, credit), settings(55, 1), null, NOW);
        local.markDelivered(2);
        assertTrue(local.state.expiryDelivered(credit, 1));
        AlertPolicy.Evaluation fresh = AlertPolicy.evaluate(quota(NOW + 1, 90, true, credit), settings(55, 1), local.state, NOW + 1);
        assertTrue(fresh.events.isEmpty());
        assertTrue(AlertPolicy.evaluateKnownExpiry(quota(NOW, 90, true, credit), settings(55, 1), fresh.state, NOW + 2).events.isEmpty());
    }
    @Test public void failedKnownDeliveryIsEligibleAgain() {
        QuotaSnapshot old = quota(NOW - HOUR, 90, true, credit("a", NOW + HOUR / 2));
        AlertPolicy.Evaluation first = AlertPolicy.evaluateKnownExpiry(old, settings(55, 1), null, NOW);
        assertEquals(1, AlertPolicy.evaluateKnownExpiry(old, settings(55, 1), first.state, NOW + 1).events.size());
    }
    @Test public void disabledExpiryAndExpiredCreditsNeverNotifyLocally() {
        QuotaSnapshot snapshot = quota(NOW, 10, false, credit("a", NOW + HOUR / 2));
        assertTrue(AlertPolicy.evaluateKnownExpiry(snapshot, new AlertPolicy.Config(true, 55, false, 1), null, NOW).events.isEmpty());
        assertTrue(AlertPolicy.evaluateKnownExpiry(quota(NOW, 10, false, credit("b", NOW)), settings(55, 1), null, NOW).events.isEmpty());
    }
    @Test public void differentCreditsAtSameDateRemainDistinct() {
        AlertPolicy.Evaluation result = AlertPolicy.evaluateKnownExpiry(
                quota(NOW, 90, true, credit("a", NOW + 1000), credit("b", NOW + 1000)), settings(55, 1), null, NOW);
        assertEquals(2, result.events.size());
    }

    @Test public void expiryReminderFallsBeforeSixHourUsageInterval() {
        Long next = ExpirySchedulePolicy.nextTriggerAt(Collections.singletonList(credit("a", NOW + 4 * HOUR)), 1, NOW, 0);
        assertEquals(Long.valueOf(NOW + 3 * HOUR), next);
    }
    @Test public void alreadyDueReminderSchedulesOnceInFuture() {
        assertEquals(Long.valueOf(NOW + 1000), ExpirySchedulePolicy.nextTriggerAt(
                Collections.singletonList(credit("a", NOW + HOUR / 2)), 1, NOW, 0));
    }
    @Test public void earliestEligibleCreditControlsNextReminder() {
        assertEquals(Long.valueOf(NOW + HOUR), ExpirySchedulePolicy.nextTriggerAt(
                Arrays.asList(credit("late", NOW + 5 * HOUR), credit("early", NOW + 2 * HOUR)), 1, NOW, 0));
    }
    @Test public void expiryScheduleRejectsExpiredOrUnidentifiedCredits() {
        assertNull(ExpirySchedulePolicy.nextTriggerAt(Arrays.asList(credit("a", NOW), credit("", NOW + HOUR)), 1, NOW, 0));
    }
    @Test public void deliveryRetryIsBoundedByCreditExpiry() {
        assertNull(ExpirySchedulePolicy.nextTriggerAt(Collections.singletonList(credit("a", NOW + 1000)), 1, NOW, NOW + HOUR));
    }
    @Test public void deliveryRetryNotBeforeIsRespected() {
        assertEquals(Long.valueOf(NOW + 60_000), ExpirySchedulePolicy.nextTriggerAt(
                Collections.singletonList(credit("a", NOW + HOUR / 2)), 1, NOW, NOW + 60_000));
    }

    @Test public void recoveryDoesNotNotifyOnFirstAllowedObservation() {
        RecoveryPolicy.Result result = RecoveryPolicy.evaluate(new RecoveryPolicy.State(0, false), true, quota(NOW, 90, true), NOW);
        assertFalse(result.shouldNotify);
    }
    @Test public void recoveryRequiresConfirmedBlockedThenAllowed() {
        RecoveryPolicy.Result blocked = RecoveryPolicy.evaluate(new RecoveryPolicy.State(0, false), true, quota(NOW, 0, false), NOW);
        assertFalse(blocked.shouldNotify);
        assertTrue(blocked.state.blocked);
        RecoveryPolicy.Result recovered = RecoveryPolicy.evaluate(blocked.state, true, quota(NOW + 1, 5, true), NOW + 1);
        assertTrue(recovered.shouldNotify);
    }
    @Test public void percentageAloneCannotConfirmRecovery() {
        RecoveryPolicy.State blocked = new RecoveryPolicy.State(NOW - 1, true);
        assertFalse(RecoveryPolicy.evaluate(blocked, true, quota(NOW, 100, null), NOW).shouldNotify);
        assertFalse(RecoveryPolicy.evaluate(blocked, true, quota(NOW, 100, false), NOW).shouldNotify);
    }
    @Test public void staleOrRepeatedSnapshotCannotConfirmRecovery() {
        RecoveryPolicy.State blocked = new RecoveryPolicy.State(NOW - HOUR, true);
        assertFalse(RecoveryPolicy.evaluate(blocked, true, quota(NOW - HOUR + 1, 100, true), NOW).shouldNotify);
        assertFalse(RecoveryPolicy.evaluate(new RecoveryPolicy.State(NOW, true), true, quota(NOW, 100, true), NOW).shouldNotify);
        assertFalse(RecoveryPolicy.evaluate(blocked, true, quota(NOW + 60_000, 100, true), NOW).shouldNotify);
    }
    @Test public void recoveryRetriesUntilActuallyDelivered() {
        RecoveryPolicy.Result first = RecoveryPolicy.evaluate(new RecoveryPolicy.State(NOW - 1, true), true, quota(NOW, 100, true), NOW);
        assertTrue(first.shouldNotify);
        RecoveryPolicy.Result retry = RecoveryPolicy.evaluate(first.state, true, quota(NOW + 1, 100, true), NOW + 1);
        assertTrue(retry.shouldNotify);
        RecoveryPolicy.Result delivered = RecoveryPolicy.evaluate(RecoveryPolicy.delivered(retry.state), true, quota(NOW + 2, 100, true), NOW + 2);
        assertFalse(delivered.shouldNotify);
    }
    @Test public void disabledRecoveryClearsItsArmedTransition() {
        RecoveryPolicy.Result disabled = RecoveryPolicy.evaluate(new RecoveryPolicy.State(NOW - 1, true), false, quota(NOW, 0, false), NOW);
        assertFalse(disabled.shouldNotify);
        assertFalse(disabled.state.blocked);
        assertFalse(RecoveryPolicy.evaluate(disabled.state, true, quota(NOW + 1, 100, true), NOW + 1).shouldNotify);
    }
}
