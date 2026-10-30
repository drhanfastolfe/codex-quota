package es.codexquota.mobile.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class QuotaPresentationTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void configuredThresholdControlsWarningColor() {
        assertEquals(QuotaPresentation.NORMAL, QuotaPresentation.colorLevel(55.1, 55));
        assertEquals(QuotaPresentation.WARNING, QuotaPresentation.colorLevel(55, 55));
        assertEquals(QuotaPresentation.WARNING, QuotaPresentation.colorLevel(25, 55));
        assertEquals(QuotaPresentation.NORMAL, QuotaPresentation.colorLevel(25, 20));
        assertEquals(QuotaPresentation.CRITICAL, QuotaPresentation.colorLevel(10, 55));
    }

    @Test public void criticalColorNeverExceedsVeryLowConfiguredThreshold() {
        assertEquals(QuotaPresentation.NORMAL, QuotaPresentation.colorLevel(5, 1));
        assertEquals(QuotaPresentation.CRITICAL, QuotaPresentation.colorLevel(1, 1));
        assertEquals(QuotaPresentation.WARNING, QuotaPresentation.colorLevel(100, 100));
        assertEquals(QuotaPresentation.NORMAL, QuotaPresentation.colorLevel(Double.NaN, 55));
    }

    @Test public void freshnessUsesChosenFrequencyAndBoundary() {
        assertFalse(QuotaPresentation.isStale(NOW - 29 * 60_000L, NOW, 30));
        assertTrue(QuotaPresentation.isStale(NOW - 30 * 60_000L, NOW, 30));
        assertFalse(QuotaPresentation.isStale(NOW - 30 * 60_000L, NOW, 60));
        assertTrue(QuotaPresentation.isStale(NOW + 1, NOW, 30));
        assertTrue(QuotaPresentation.isStale(0, NOW, 30));
    }

    @Test public void confirmationContextIncludesBothRenewalWindows() {
        QuotaSnapshot snapshot = new QuotaSnapshot("plus", Arrays.asList(
                new QuotaWindow("5 horas", "primary", 75, NOW + 5 * 60_000L, 18_000L),
                new QuotaWindow("Semanal", "secondary", 20, NOW + 2 * 3_600_000L, 604_800L)
        ), null, NOW - 60_000L);
        String context = QuotaPresentation.renewalContext(snapshot, NOW, 30);
        assertTrue(context.contains("Según la última consulta"));
        assertTrue(context.contains("5 horas: en 5 min"));
        assertTrue(context.contains("Semanal: en 2 h"));
        assertFalse(context.contains("las fechas pueden haber cambiado"));
    }

    @Test public void oldOrElapsedDatesNeverClaimConfirmedRecovery() {
        QuotaSnapshot snapshot = new QuotaSnapshot("plus", Arrays.asList(
                new QuotaWindow("5 horas", "primary", 100, NOW - 1, 18_000L),
                new QuotaWindow("Semanal", "secondary", 40, null, 604_800L)
        ), null, NOW - 60 * 60_000L);
        String context = QuotaPresentation.renewalContext(snapshot, NOW, 30);
        assertTrue(context.contains("las fechas pueden haber cambiado"));
        assertTrue(context.contains("fecha alcanzada; pendiente de comprobar"));
        assertTrue(context.contains("Semanal: fecha no disponible"));
        assertFalse(context.contains("puedes volver"));
    }

    @Test public void missingQuotaDoesNotInventRenewals() {
        assertEquals("", QuotaPresentation.renewalContext(null, NOW, 30));
        assertEquals("", QuotaPresentation.renewalContext(
                new QuotaSnapshot("plus", Collections.emptyList(), null, NOW), NOW, 30));
    }
}
