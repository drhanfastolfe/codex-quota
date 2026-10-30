package es.codexquota.mobile.core;

public final class ResetCredit {
    public final String description;
    public final Long expiresAtMillis;
    public final Long grantedAtMillis;
    public final String id;
    public final String resetType;
    public final String status;
    public final String title;

    public ResetCredit(String str, String str2, String str3, String str4, String str5, Long l, Long l2) {
        this.id = str;
        this.resetType = str2;
        this.status = str3;
        this.title = str4;
        this.description = str5;
        this.grantedAtMillis = l;
        this.expiresAtMillis = l2;
    }

    public boolean isAvailable(long j) {
        Long l;
        return "available".equals(this.status) && "codex_rate_limits".equals(this.resetType) && ((l = this.expiresAtMillis) == null || l.longValue() > j);
    }
}
