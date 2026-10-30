package es.codexquota.mobile.core;

import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public final class QuotaModelsTest {
    @Test public void preservesBothQuotaWindowsAndServerPermission() throws Exception {
        String json = "{\"plan_type\":\"plus\",\"rate_limit\":{\"allowed\":false,"
                + "\"primary_window\":{\"used_percent\":75,\"limit_window_seconds\":18000,\"reset_after_seconds\":60},"
                + "\"secondary_window\":{\"used_percent\":10,\"limit_window_seconds\":604800,\"reset_at\":2000}}}";
        QuotaSnapshot snapshot = QuotaSnapshot.fromUsageJson(json, 1000L);
        assertEquals(2, snapshot.windows.size());
        assertEquals("5 horas", snapshot.windows.get(0).label);
        assertEquals("Semanal", snapshot.windows.get(1).label);
        assertEquals(25.0, snapshot.windows.get(0).remainingPercent(), 0.001);
        assertEquals(Long.valueOf(61_000L), snapshot.windows.get(0).resetAtMillis);
        assertEquals(Long.valueOf(2_000_000L), snapshot.windows.get(1).resetAtMillis);
        assertEquals(Boolean.FALSE, snapshot.ordinaryUsageAllowed);
        QuotaSnapshot restored = QuotaSnapshot.fromJson(snapshot.toJson());
        assertEquals(Boolean.FALSE, restored.ordinaryUsageAllowed);
        assertEquals(2, restored.windows.size());
    }

    @Test public void absentPermissionDoesNotInventRecovery() throws Exception {
        QuotaSnapshot snapshot = QuotaSnapshot.fromUsageJson("{\"rate_limit\":{\"primary_window\":{\"used_percent\":0}}}", 1000L);
        assertNull(snapshot.ordinaryUsageAllowed);
        assertEquals(1, snapshot.windows.size());
        assertNull(snapshot.windows.get(0).resetAtMillis);
    }

    @Test public void creditsUseExpiryOrderAndUnknownDetailsCannotBeRedeemed() {
        ResetCredit early = new ResetCredit("early", "codex_rate_limits", "available", "Early", null, 1L, 3000L);
        ResetCredit late = new ResetCredit("late", "codex_rate_limits", "available", "Late", null, 1L, 5000L);
        ResetCredit expired = new ResetCredit("expired", "codex_rate_limits", "available", "Expired", null, 1L, 999L);
        ResetCredits complete = new ResetCredits(2, Arrays.asList(late, expired, early));
        assertEquals("early", complete.earliestForRedemption(1000L).id);
        assertEquals(1L, complete.displayAvailableCount(4000L, 1000L));
        assertNull(new ResetCredits(3, Arrays.asList(late, early)).earliestForRedemption(1000L));
        assertNull(new ResetCredits(2, Arrays.asList(late, early), "details unavailable").earliestForRedemption(1000L));
    }

    @Test public void creditDatesPreserveTimezoneAndRoundTrip() throws Exception {
        ResetCredits credits = QuotaSnapshot.parseResetCredits("{\"available_count\":1,\"credits\":[{"
                + "\"id\":\"credit\",\"status\":\"available\",\"expires_at\":\"2026-10-30T20:00:00+02:00\"}]}" );
        assertEquals(Long.valueOf(java.time.Instant.parse("2026-10-30T18:00:00Z").toEpochMilli()), credits.credits.get(0).expiresAtMillis);
        ResetCredits incomplete = QuotaSnapshot.parseResetCredits("{\"available_count\":1,\"credits\":[{\"id\":\"credit\",\"expires_at\":\"not-a-date\"}]}" );
        assertNull(incomplete.credits.get(0).expiresAtMillis);
    }
}
