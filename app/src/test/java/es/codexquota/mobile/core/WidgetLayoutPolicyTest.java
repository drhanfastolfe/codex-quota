package es.codexquota.mobile.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetLayoutPolicyTest {
    @Test public void smallMiniKeepsSpaceForBothQuotasInsteadOfTinyButtons() {
        assertFalse(WidgetLayoutPolicy.showMiniRefresh(140));
        assertFalse(WidgetLayoutPolicy.showMiniTheme(140));
    }

    @Test public void wideMiniAddsFullSizeActionsProgressively() {
        assertTrue(WidgetLayoutPolicy.showMiniRefresh(220));
        assertFalse(WidgetLayoutPolicy.showMiniTheme(220));
        assertTrue(WidgetLayoutPolicy.showMiniTheme(290));
    }

    @Test public void compactFullPreservesUsefulSpaceBeforeBrandHeading() {
        assertFalse(WidgetLayoutPolicy.showFullHeading(178));
        assertTrue(WidgetLayoutPolicy.showFullHeading(210));
    }
}
