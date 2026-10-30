package es.codexquota.mobile.core;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class QuotaSnapshot {
    public final long fetchedAtMillis;
    public final Boolean ordinaryUsageAllowed;
    public final String plan;
    public final ResetCredits resetCredits;
    public final List<QuotaWindow> windows;

    public QuotaSnapshot(String str, List<QuotaWindow> list, ResetCredits resetCredits, long j) {
        this(str, list, resetCredits, j, null);
    }

    public QuotaSnapshot(String str, List<QuotaWindow> list, ResetCredits resetCredits, long j, Boolean bool) {
        this.plan = str;
        this.windows = Collections.unmodifiableList(new ArrayList(list));
        this.resetCredits = resetCredits;
        this.fetchedAtMillis = j;
        this.ordinaryUsageAllowed = bool;
    }

    public static QuotaSnapshot fromUsageJson(String str, long j) throws JSONException {
        JSONObject jSONObject = new JSONObject(str);
        ArrayList arrayList = new ArrayList();
        JSONObject jSONObjectOptJSONObject = jSONObject.optJSONObject("rate_limit");
        addWindows(arrayList, jSONObjectOptJSONObject, "codex", null, j);
        JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("additional_rate_limits");
        if (jSONArrayOptJSONArray != null) {
            for (int i = 0; i < jSONArrayOptJSONArray.length(); i++) {
                JSONObject jSONObjectOptJSONObject2 = jSONArrayOptJSONArray.optJSONObject(i);
                if (jSONObjectOptJSONObject2 != null) {
                    addWindows(arrayList, jSONObjectOptJSONObject2.optJSONObject("rate_limit"), stringOrNull(jSONObjectOptJSONObject2, "metered_feature"), stringOrNull(jSONObjectOptJSONObject2, "limit_name"), j);
                }
            }
        }
        addWindows(arrayList, jSONObject.optJSONObject("code_review_rate_limit"), "code_review", "Revisión de código", j);
        JSONObject jSONObjectOptJSONObject3 = jSONObject.optJSONObject("rate_limit_reset_credits");
        return new QuotaSnapshot(stringOrNull(jSONObject, "plan_type"), arrayList, (jSONObjectOptJSONObject3 == null || !jSONObjectOptJSONObject3.has("available_count") || jSONObjectOptJSONObject3.isNull("available_count")) ? null : new ResetCredits(jSONObjectOptJSONObject3.optLong("available_count", 0L), null), j, (jSONObjectOptJSONObject == null || !jSONObjectOptJSONObject.has("allowed") || jSONObjectOptJSONObject.isNull("allowed")) ? null : Boolean.valueOf(jSONObjectOptJSONObject.optBoolean("allowed")));
    }

    public QuotaSnapshot withResetCredits(ResetCredits resetCredits) {
        return new QuotaSnapshot(this.plan, this.windows, resetCredits, this.fetchedAtMillis, this.ordinaryUsageAllowed);
    }

    public String toJson() {
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("schema_version", 1);
            jSONObject.put("plan", this.plan);
            jSONObject.put("fetched_at", this.fetchedAtMillis);
            jSONObject.put("ordinary_usage_allowed", this.ordinaryUsageAllowed);
            JSONArray jSONArray = new JSONArray();
            for (QuotaWindow quotaWindow : this.windows) {
                JSONObject jSONObject2 = new JSONObject();
                jSONObject2.put("label", quotaWindow.label);
                jSONObject2.put("limit_id", quotaWindow.limitId);
                jSONObject2.put("used_percent", quotaWindow.usedPercent);
                jSONObject2.put("reset_at", quotaWindow.resetAtMillis);
                jSONObject2.put("window_seconds", quotaWindow.windowSeconds);
                jSONArray.put(jSONObject2);
            }
            jSONObject.put("windows", jSONArray);
            if (this.resetCredits != null) {
                JSONObject jSONObject3 = new JSONObject();
                jSONObject3.put("available_count", this.resetCredits.availableCount);
                jSONObject3.put("details_error", this.resetCredits.detailsError);
                if (this.resetCredits.credits != null) {
                    JSONArray jSONArray2 = new JSONArray();
                    for (ResetCredit resetCredit : this.resetCredits.credits) {
                        JSONObject jSONObject4 = new JSONObject();
                        jSONObject4.put("id", resetCredit.id);
                        jSONObject4.put("reset_type", resetCredit.resetType);
                        jSONObject4.put("status", resetCredit.status);
                        jSONObject4.put("title", resetCredit.title);
                        jSONObject4.put("description", resetCredit.description);
                        jSONObject4.put("granted_at_ms", resetCredit.grantedAtMillis);
                        jSONObject4.put("expires_at_ms", resetCredit.expiresAtMillis);
                        jSONArray2.put(jSONObject4);
                    }
                    jSONObject3.put("credits", jSONArray2);
                }
                jSONObject.put("reset_credits", jSONObject3);
            }
            return jSONObject.toString();
        } catch (JSONException unused) {
            throw new IllegalStateException("No se pudo serializar la cuota.");
        }
    }

    public static QuotaSnapshot fromJson(String str) throws JSONException {
        ResetCredits resetCredits;
        ArrayList arrayList;
        JSONObject jSONObject = new JSONObject(str);
        if (jSONObject.optInt("schema_version", 0) != 1) {
            throw new JSONException("Unsupported cache version");
        }
        ArrayList arrayList2 = new ArrayList();
        JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("windows");
        if (jSONArrayOptJSONArray != null) {
            for (int i = 0; i < jSONArrayOptJSONArray.length(); i++) {
                JSONObject jSONObjectOptJSONObject = jSONArrayOptJSONArray.optJSONObject(i);
                if (jSONObjectOptJSONObject != null && jSONObjectOptJSONObject.has("used_percent")) {
                    arrayList2.add(new QuotaWindow(jSONObjectOptJSONObject.optString("label", "Cuota"), stringOrNull(jSONObjectOptJSONObject, "limit_id"), jSONObjectOptJSONObject.optDouble("used_percent", 0.0d), nullableLong(jSONObjectOptJSONObject, "reset_at"), nullableLong(jSONObjectOptJSONObject, "window_seconds")));
                }
            }
        }
        JSONObject jSONObjectOptJSONObject2 = jSONObject.optJSONObject("reset_credits");
        if (jSONObjectOptJSONObject2 != null) {
            JSONArray jSONArrayOptJSONArray2 = jSONObjectOptJSONObject2.optJSONArray("credits");
            if (jSONArrayOptJSONArray2 != null) {
                arrayList = new ArrayList();
                for (int i2 = 0; i2 < jSONArrayOptJSONArray2.length(); i2++) {
                    JSONObject jSONObjectOptJSONObject3 = jSONArrayOptJSONArray2.optJSONObject(i2);
                    if (jSONObjectOptJSONObject3 != null) {
                        arrayList.add(new ResetCredit(stringOrNull(jSONObjectOptJSONObject3, "id"), stringOrNull(jSONObjectOptJSONObject3, "reset_type"), stringOrNull(jSONObjectOptJSONObject3, "status"), stringOrNull(jSONObjectOptJSONObject3, "title"), stringOrNull(jSONObjectOptJSONObject3, "description"), nullableLong(jSONObjectOptJSONObject3, "granted_at_ms"), nullableLong(jSONObjectOptJSONObject3, "expires_at_ms")));
                    }
                }
            } else {
                arrayList = null;
            }
            resetCredits = new ResetCredits(jSONObjectOptJSONObject2.optLong("available_count", 0L), arrayList, stringOrNull(jSONObjectOptJSONObject2, "details_error"));
        } else {
            resetCredits = null;
        }
        return new QuotaSnapshot(stringOrNull(jSONObject, "plan"), arrayList2, resetCredits, jSONObject.optLong("fetched_at", 0L), (!jSONObject.has("ordinary_usage_allowed") || jSONObject.isNull("ordinary_usage_allowed")) ? null : Boolean.valueOf(jSONObject.optBoolean("ordinary_usage_allowed")));
    }

    public static ResetCredits parseResetCredits(String str) throws JSONException {
        ArrayList arrayList;
        JSONObject jSONObject = new JSONObject(str);
        if (!jSONObject.has("available_count") || jSONObject.isNull("available_count")) {
            throw new JSONException("Missing count");
        }
        JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("credits");
        if (jSONArrayOptJSONArray != null) {
            arrayList = new ArrayList();
            for (int i = 0; i < jSONArrayOptJSONArray.length(); i++) {
                JSONObject jSONObjectOptJSONObject = jSONArrayOptJSONArray.optJSONObject(i);
                if (jSONObjectOptJSONObject != null) {
                    arrayList.add(new ResetCredit(stringOrNull(jSONObjectOptJSONObject, "id"), stringOrNull(jSONObjectOptJSONObject, "reset_type"), stringOrNull(jSONObjectOptJSONObject, "status"), stringOrNull(jSONObjectOptJSONObject, "title"), stringOrNull(jSONObjectOptJSONObject, "description"), parseDate(stringOrNull(jSONObjectOptJSONObject, "granted_at")), parseDate(stringOrNull(jSONObjectOptJSONObject, "expires_at"))));
                }
            }
        } else {
            arrayList = null;
        }
        return new ResetCredits(jSONObject.optLong("available_count", 0L), arrayList);
    }

    private static void addWindows(List<QuotaWindow> list, JSONObject jSONObject, String str, String str2, long j) {
        if (jSONObject == null) {
            return;
        }
        addWindow(list, jSONObject.optJSONObject("primary_window"), str, str2, "Ventana principal", j);
        addWindow(list, jSONObject.optJSONObject("secondary_window"), str, str2, "Ventana secundaria", j);
    }

    private static void addWindow(List<QuotaWindow> list, JSONObject jSONObject, String str, String str2, String str3, long j) {
        Long lNullableLong;
        if (jSONObject == null || !jSONObject.has("used_percent") || jSONObject.isNull("used_percent")) {
            return;
        }
        double dOptDouble = jSONObject.optDouble("used_percent", Double.NaN);
        if (!Double.isFinite(dOptDouble) || dOptDouble < 0.0d) {
            return;
        }
        Long lNullableLong2 = nullableLong(jSONObject, "limit_window_seconds");
        if (lNullableLong2 != null && lNullableLong2.longValue() <= 0) {
            lNullableLong2 = null;
        }
        Long l = lNullableLong2;
        Long lNullableLong3 = nullableLong(jSONObject, "reset_at");
        if (lNullableLong3 != null) {
            lNullableLong3 = safeMillis(lNullableLong3.longValue());
        }
        Long lValueOf = (lNullableLong3 != null || (lNullableLong = nullableLong(jSONObject, "reset_after_seconds")) == null || lNullableLong.longValue() < 0 || lNullableLong.longValue() >= 9223372036854775L - (j / 1000)) ? lNullableLong3 : Long.valueOf(j + (lNullableLong.longValue() * 1000));
        String strWindowLabel = windowLabel(l, str3);
        if (str2 != null && !str2.isEmpty()) {
            strWindowLabel = str2 + " · " + strWindowLabel;
        }
        list.add(new QuotaWindow(strWindowLabel, str, dOptDouble, lValueOf, l));
    }

    private static String windowLabel(Long l, String str) {
        if (l == null) {
            return str;
        }
        if (l.longValue() == 604800) {
            return "Semanal";
        }
        if (l.longValue() == 18000) {
            return "5 horas";
        }
        if (l.longValue() % 86400 == 0) {
            return (l.longValue() / 86400) + " días";
        }
        if (l.longValue() % 3600 == 0) {
            return (l.longValue() / 3600) + " horas";
        }
        return Math.max(1L, (l.longValue() + 59) / 60) + " minutos";
    }

    static Long nullableLong(JSONObject jSONObject, String str) {
        if (jSONObject.has(str) && !jSONObject.isNull(str)) {
            Object objOpt = jSONObject.opt(str);
            if (objOpt instanceof Number) {
                return Long.valueOf(((Number) objOpt).longValue());
            }
            try {
                return Long.valueOf(Long.parseLong(String.valueOf(objOpt)));
            } catch (NumberFormatException unused) {
            }
        }
        return null;
    }

    static String stringOrNull(JSONObject jSONObject, String str) {
        Object objOpt = jSONObject.opt(str);
        if (objOpt instanceof String) {
            String str2 = (String) objOpt;
            if (!str2.isEmpty()) {
                return str2;
            }
        }
        return null;
    }

    private static Long safeMillis(long j) {
        if (j < 0 || j > 9223372036854775L) {
            return null;
        }
        return Long.valueOf(j * 1000);
    }

    private static Long parseDate(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli();
            } catch (RuntimeException invalidDate) {
                return null;
            }
        }
    }
}
