package es.codexquota.mobile.core;

import java.util.Objects;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

public final class PendingReset {
    public final String creditId;
    public final Long expiresAtMillis;
    public final String requestId;
    public final String title;

    private PendingReset(String str, String str2, String str3, Long l) {
        UUID.fromString(str);
        this.requestId = str;
        this.creditId = str2;
        this.title = str3;
        this.expiresAtMillis = l;
    }

    public static PendingReset forCredit(ResetCredit resetCredit) {
        if (resetCredit == null || resetCredit.id == null || resetCredit.id.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing credit");
        }
        return new PendingReset(UUID.randomUUID().toString(), resetCredit.id, resetCredit.title, resetCredit.expiresAtMillis);
    }

    public String toStorage() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("request_id", this.requestId);
        jSONObject.put("credit_id", this.creditId);
        jSONObject.put("title", this.title);
        jSONObject.put("expires_at", this.expiresAtMillis);
        return jSONObject.toString();
    }

    public static PendingReset fromStorage(String str) throws JSONException {
        if (str == null) {
            return null;
        }
        if (!str.startsWith("{")) {
            return new PendingReset(str, null, null, null);
        }
        JSONObject jSONObject = new JSONObject(str);
        return new PendingReset(jSONObject.getString("request_id"), QuotaSnapshot.stringOrNull(jSONObject, "credit_id"), QuotaSnapshot.stringOrNull(jSONObject, "title"), QuotaSnapshot.nullableLong(jSONObject, "expires_at"));
    }

    public boolean sameRequest(PendingReset pendingReset) {
        return pendingReset != null && this.requestId.equals(pendingReset.requestId) && Objects.equals(this.creditId, pendingReset.creditId);
    }
}
