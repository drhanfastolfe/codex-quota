package es.codexquota.mobile.core;

public final class QuotaWindow {
    public final String label;
    public final String limitId;
    public final Long resetAtMillis;
    public final double usedPercent;
    public final Long windowSeconds;

    public QuotaWindow(String str, String str2, double d, Long l, Long l2) {
        this.label = str;
        this.limitId = str2;
        this.usedPercent = Double.isFinite(d) ? Math.max(0.0d, d) : 0.0d;
        this.resetAtMillis = l;
        this.windowSeconds = l2;
    }

    public double remainingPercent() {
        return Math.max(0.0d, 100.0d - this.usedPercent);
    }
}
