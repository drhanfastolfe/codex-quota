package es.codexquota.mobile.core;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConfirmedConsumptionTest {
    private final ResetCredit consumed = new ResetCredit("consumed", "codex_rate_limits", "available", "First", null, 1L, 3_000L);
    private final ResetCredit remaining = new ResetCredit("remaining", "codex_rate_limits", "available", "Later", null, 1L, 5_000L);

    @Test public void removesConfirmedCreditAndKeepsNextKnownCredit() {
        ResetCredits current = new ResetCredits(2, Arrays.asList(remaining, consumed));
        ResetCredits updated = current.afterConfirmedConsumption("consumed", 1_000L);
        assertNotNull(updated);
        assertEquals(1L, updated.availableCount);
        assertEquals(1, updated.credits.size());
        assertEquals("remaining", updated.earliestForRedemption(1_000L).id);
        assertEquals(2L, current.availableCount);
    }

    @Test public void alreadyOmittedCreditDoesNotDecrementTwice() {
        ResetCredits current = new ResetCredits(1, Collections.singletonList(remaining));
        ResetCredits updated = current.afterConfirmedConsumption("consumed", 1_000L);
        assertEquals(1L, updated.availableCount);
        assertEquals("remaining", updated.credits.get(0).id);
    }

    @Test public void unknownCreditOrIncompleteDetailsRequireANewQuery() {
        ResetCredits current = new ResetCredits(2, Arrays.asList(consumed, remaining));
        assertNull(current.afterConfirmedConsumption(null, 1_000L));
        assertNull(new ResetCredits(2, null).afterConfirmedConsumption("consumed", 1_000L));
        assertNull(new ResetCredits(3, Arrays.asList(consumed, remaining)).afterConfirmedConsumption("consumed", 1_000L));
        assertNull(new ResetCredits(2, Arrays.asList(consumed, remaining), "partial").afterConfirmedConsumption("consumed", 1_000L));
    }

    @Test public void accountingUsesOriginalSnapshotTimeNotResponseArrival() {
        ResetCredits current = new ResetCredits(2, Arrays.asList(consumed, remaining));
        // The server can confirm after the chosen credit's expiry. Its original
        // available count must still be adjusted exactly once.
        ResetCredits updated = current.afterConfirmedConsumption("consumed", 1_000L);
        assertEquals(1L, updated.availableCount);
        assertEquals(1L, updated.displayAvailableCount(4_000L, 1_000L));
    }
}
