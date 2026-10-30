package es.codexquota.mobile.core;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

public final class QuotaClient {
    private static final Object REFRESH_LOCK = new Object();
    private final TokenStore store;
    private final HttpTransport transport;
    private volatile ApiException lastReadFailure;

    public QuotaClient(Context context) {
        this(new TokenStore(context));
    }

    public QuotaClient(TokenStore tokenStore) {
        this(tokenStore, new HttpTransport());
    }

    // Package-private transport seam for offline protocol tests.
    QuotaClient(TokenStore tokenStore, HttpTransport transport) {
        this.transport = transport;
        this.store = tokenStore;
    }

    /** A partial reset-detail failure still allows the fresh usage data to be shown. */
    public ApiException lastReadFailure() {
        return lastReadFailure;
    }

    public QuotaSnapshot fetchSnapshot() throws ApiException {
        lastReadFailure = null;
        long jGeneration = this.store.generation();
        try {
            try {
                QuotaSnapshot quotaSnapshotFromUsageJson = QuotaSnapshot.fromUsageJson(authenticated("GET", "/usage", null).body, System.currentTimeMillis());
                try {
                    quotaSnapshotFromUsageJson = quotaSnapshotFromUsageJson.withResetCredits(QuotaSnapshot.parseResetCredits(authenticated("GET", "/rate-limit-reset-credits", null).body));
                } catch (ApiException e) {
                    lastReadFailure = e;
                    if (e.authenticationRequired) {
                        throw e;
                    }
                    if (quotaSnapshotFromUsageJson.resetCredits != null) {
                        quotaSnapshotFromUsageJson = quotaSnapshotFromUsageJson.withResetCredits(new ResetCredits(quotaSnapshotFromUsageJson.resetCredits.availableCount, null, e.userMessage));
                    }
                } catch (JSONException unused) {
                    if (quotaSnapshotFromUsageJson.resetCredits != null) {
                        quotaSnapshotFromUsageJson = quotaSnapshotFromUsageJson.withResetCredits(new ResetCredits(quotaSnapshotFromUsageJson.resetCredits.availableCount, null, "No se pudieron leer los detalles de los restablecimientos."));
                    }
                }
                if (jGeneration == this.store.generation()) {
                    return quotaSnapshotFromUsageJson;
                }
                throw ApiException.signedOut();
            } catch (JSONException unused2) {
                throw ApiException.invalidResponse();
            }
        } catch (ApiException e2) {
            throw e2.atStage("Consultar cuota");
        }
    }

    public ConsumeResult consumeReset(String str, String str2) throws ApiException {
        if (str == null || str.trim().isEmpty() || str.length() > 128) {
            throw new ApiException("No se pudo preparar la solicitud de restablecimiento.", false, 0);
        }
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("redeem_request_id", str);
            if (str2 != null && !str2.isEmpty()) {
                jSONObject.put("credit_id", str2);
            }
            JSONObject jSONObjectJson = authenticated("POST", "/rate-limit-reset-credits/consume", jSONObject.toString()).json();
            String strStringOrNull = QuotaSnapshot.stringOrNull(jSONObjectJson, "code");
            if (strStringOrNull == null) {
                throw ApiException.invalidResponse();
            }
            return new ConsumeResult(strStringOrNull, Math.max(0L, jSONObjectJson.optLong("windows_reset", 0L)));
        } catch (JSONException unused) {
            throw ApiException.invalidResponse();
        }
    }

    private HttpTransport.Response authenticated(String str, String str2, String str3) throws ApiException {
        long jGeneration = this.store.generation();
        TokenBundle tokenBundleLoad = this.store.load();
        if (tokenBundleLoad == null) {
            throw ApiException.signedOut();
        }
        if (tokenBundleLoad.expiresAtMillis > 0 && tokenBundleLoad.expiresAtMillis <= System.currentTimeMillis() + 60000) {
            tokenBundleLoad = refresh(tokenBundleLoad);
        }
        if (jGeneration != this.store.generation()) {
            throw ApiException.signedOut();
        }
        HttpTransport.Response responseSend = this.transport.send(str, "https://chatgpt.com/backend-api/wham" + str2, str3, "application/json", tokenBundleLoad);
        if (responseSend.status == 401) {
            TokenBundle tokenBundleRefresh = refresh(tokenBundleLoad);
            if (jGeneration != this.store.generation()) {
                throw ApiException.signedOut();
            }
            if (!"GET".equals(str)) {
                // Authentication repair is safe here; credit consumption is not replayed.
                // The persisted request stays pending until a new user confirmation.
                throw new ApiException("La sesión se ha renovado. Confirma de nuevo el reset pendiente.", false, 401);
            }
            responseSend = this.transport.send(str, "https://chatgpt.com/backend-api/wham" + str2, str3, "application/json", tokenBundleRefresh);
        }
        if (jGeneration != this.store.generation()) {
            throw ApiException.signedOut();
        }
        if (responseSend.successful()) {
            return responseSend;
        }
        throw HttpTransport.failure(responseSend);
    }

    private TokenBundle refresh(TokenBundle tokenBundle) throws ApiException {
        synchronized (REFRESH_LOCK) {
            TokenBundle tokenBundleLoad = this.store.load();
            if (tokenBundleLoad == null) {
                throw ApiException.signedOut();
            }
            if (!tokenBundle.sameCredentials(tokenBundleLoad)) {
                if (tokenBundle.sameSession(tokenBundleLoad)) {
                    return tokenBundleLoad;
                }
                throw ApiException.signedOut();
            }
            JSONObject jSONObject = new JSONObject();
            try {
                jSONObject.put("grant_type", "refresh_token");
                jSONObject.put("client_id", "app_EMoamEEZ73f0CkXaXp7hrann");
                jSONObject.put("refresh_token", tokenBundle.refreshToken);
                HttpTransport.Response responseSend = this.transport.send("POST", "https://auth.openai.com/oauth/token", jSONObject.toString(), "application/json", null);
                if (!responseSend.successful()) {
                    String strOauthError = responseSend.oauthError();
                    if (responseSend.status != 401 && ((responseSend.status != 400 || !"invalid_grant".equals(strOauthError)) && !"refresh_token_expired".equals(strOauthError) && !"refresh_token_reused".equals(strOauthError) && !"refresh_token_invalidated".equals(strOauthError))) {
                        throw HttpTransport.failure(responseSend);
                    }
                    this.store.clearIfUnchanged(tokenBundle);
                    throw new ApiException("Tu sesión ya no se puede renovar. Inicia sesión otra vez.", true, responseSend.status);
                }
                TokenBundle tokenBundleFromResponse = TokenBundle.fromResponse(responseSend.json(), tokenBundle);
                if (this.store.replaceIfUnchanged(tokenBundle, tokenBundleFromResponse)) {
                    return tokenBundleFromResponse;
                }
                throw ApiException.signedOut();
            } catch (JSONException unused) {
                throw ApiException.invalidResponse();
            }
        }
    }
}
