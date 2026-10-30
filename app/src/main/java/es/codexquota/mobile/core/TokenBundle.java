package es.codexquota.mobile.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

public final class TokenBundle {
    final String accessToken;
    final String accountId;
    final long expiresAtMillis;
    final boolean fedramp;
    final String plan;
    final String refreshToken;
    final String userId;

    TokenBundle(String str, String str2, String str3, String str4, String str5, boolean z, long j) {
        this.accessToken = str;
        this.refreshToken = str2;
        this.accountId = str3;
        this.userId = str4;
        this.plan = str5;
        this.fedramp = z;
        this.expiresAtMillis = j;
    }

    static TokenBundle fromResponse(JSONObject jSONObject, TokenBundle tokenBundle) throws ApiException {
        boolean zOptBoolean;
        String str;
        long jCurrentTimeMillis;
        String str2;
        String str3;
        String strStringOrNull = QuotaSnapshot.stringOrNull(jSONObject, "access_token");
        String strStringOrNull2 = QuotaSnapshot.stringOrNull(jSONObject, "refresh_token");
        String strStringOrNull3 = QuotaSnapshot.stringOrNull(jSONObject, "id_token");
        if (strStringOrNull == null && tokenBundle != null) {
            strStringOrNull = tokenBundle.accessToken;
        }
        String str4 = strStringOrNull;
        if (strStringOrNull2 == null && tokenBundle != null) {
            strStringOrNull2 = tokenBundle.refreshToken;
        }
        String str5 = strStringOrNull2;
        if (str4 == null || str5 == null) {
            throw ApiException.invalidResponse();
        }
        String str6 = tokenBundle == null ? null : tokenBundle.accountId;
        String str7 = tokenBundle == null ? null : tokenBundle.userId;
        String str8 = tokenBundle == null ? null : tokenBundle.plan;
        boolean z = tokenBundle != null && tokenBundle.fedramp;
        if (strStringOrNull3 == null) {
            strStringOrNull3 = str4;
        }
        JSONObject jSONObjectClaims = claims(strStringOrNull3);
        JSONObject jSONObjectOptJSONObject = jSONObjectClaims == null ? null : jSONObjectClaims.optJSONObject("https://api.openai.com/auth");
        if (jSONObjectOptJSONObject == null) {
            zOptBoolean = z;
            str = str8;
        } else {
            String strStringOrNull4 = QuotaSnapshot.stringOrNull(jSONObjectOptJSONObject, "chatgpt_account_id");
            if (strStringOrNull4 != null) {
                if (tokenBundle != null && (str3 = tokenBundle.accountId) != null && !str3.equals(strStringOrNull4)) {
                    throw new ApiException("La cuenta de la sesión ha cambiado. Vuelve a iniciar sesión.", true, 401);
                }
                str6 = strStringOrNull4;
            }
            String strStringOrNull5 = QuotaSnapshot.stringOrNull(jSONObjectOptJSONObject, "chatgpt_user_id");
            if (strStringOrNull5 == null) {
                strStringOrNull5 = QuotaSnapshot.stringOrNull(jSONObjectOptJSONObject, "user_id");
            }
            if (strStringOrNull5 != null) {
                if (tokenBundle != null && (str2 = tokenBundle.userId) != null && !str2.equals(strStringOrNull5)) {
                    throw new ApiException("El usuario de la sesión ha cambiado. Vuelve a iniciar sesión.", true, 401);
                }
                str7 = strStringOrNull5;
            }
            String strStringOrNull6 = QuotaSnapshot.stringOrNull(jSONObjectOptJSONObject, "chatgpt_plan_type");
            if (strStringOrNull6 != null) {
                str8 = strStringOrNull6;
            }
            if (jSONObjectOptJSONObject.has("chatgpt_account_is_fedramp")) {
                str = str8;
                zOptBoolean = jSONObjectOptJSONObject.optBoolean("chatgpt_account_is_fedramp");
            } else {
                zOptBoolean = z;
                str = str8;
            }
        }
        String str9 = str6;
        String strStringOrNull7 = (str7 != null || jSONObjectClaims == null) ? str7 : QuotaSnapshot.stringOrNull(jSONObjectClaims, "sub");
        JSONObject jSONObjectClaims2 = claims(str4);
        Long lNullableLong = jSONObjectClaims2 != null ? QuotaSnapshot.nullableLong(jSONObjectClaims2, "exp") : null;
        long jLongValue = (lNullableLong == null || lNullableLong.longValue() <= 0 || lNullableLong.longValue() > 9223372036854775L) ? 0L : lNullableLong.longValue() * 1000;
        if (jLongValue != 0) {
            jCurrentTimeMillis = jLongValue;
        } else {
            Long lNullableLong2 = QuotaSnapshot.nullableLong(jSONObject, "expires_in");
            if (lNullableLong2 != null && lNullableLong2.longValue() > 0 && lNullableLong2.longValue() < 315360000) {
                jCurrentTimeMillis = System.currentTimeMillis() + (lNullableLong2.longValue() * 1000);
            } else if (tokenBundle == null || !str4.equals(tokenBundle.accessToken)) {
                jCurrentTimeMillis = jLongValue;
            } else {
                jCurrentTimeMillis = tokenBundle.expiresAtMillis;
            }
        }
        return new TokenBundle(str4, str5, str9, strStringOrNull7, str, zOptBoolean, jCurrentTimeMillis);
    }

    JSONObject toJson() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("access_token", this.accessToken);
        jSONObject.put("refresh_token", this.refreshToken);
        jSONObject.put("account_id", this.accountId);
        jSONObject.put("user_id", this.userId);
        jSONObject.put("plan", this.plan);
        jSONObject.put("fedramp", this.fedramp);
        jSONObject.put("expires_at", this.expiresAtMillis);
        return jSONObject;
    }

    static TokenBundle fromJson(JSONObject jSONObject) throws JSONException {
        return new TokenBundle(jSONObject.getString("access_token"), jSONObject.getString("refresh_token"), QuotaSnapshot.stringOrNull(jSONObject, "account_id"), QuotaSnapshot.stringOrNull(jSONObject, "user_id"), QuotaSnapshot.stringOrNull(jSONObject, "plan"), jSONObject.optBoolean("fedramp", false), jSONObject.optLong("expires_at", 0L));
    }

    boolean sameCredentials(TokenBundle tokenBundle) {
        return tokenBundle != null && this.accessToken.equals(tokenBundle.accessToken) && this.refreshToken.equals(tokenBundle.refreshToken);
    }

    boolean sameSession(TokenBundle tokenBundle) {
        return tokenBundle != null && Objects.equals(this.userId, tokenBundle.userId) && Objects.equals(this.accountId, tokenBundle.accountId);
    }

    private static JSONObject claims(String str) {
        if (str == null) {
            return null;
        }
        String[] strArrSplit = str.split("\\.");
        if (strArrSplit.length != 3) {
            return null;
        }
        try {
            return new JSONObject(new String(Base64.getUrlDecoder().decode(strArrSplit[1]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JSONException unused) {
            return null;
        }
    }
}
