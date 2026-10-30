package es.codexquota.mobile.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONException;
import org.json.JSONObject;

public final class DeviceLogin {
    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private final AtomicBoolean cancelled;
    private final LongSupplier clock;
    private final AtomicBoolean pollInProgress;
    private Runnable progressListener;
    private final Object stateLock;
    private volatile String status;
    private final LoginPersistence store;
    private final HttpTransport transport;

    static void lambda$new$0() {
    }

    public DeviceLogin(TokenStore tokenStore) {
        this(tokenStore, new HttpTransport(), new LongSupplier() {
            @Override // java.util.function.LongSupplier
            public final long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    DeviceLogin(LoginPersistence loginPersistence, HttpTransport httpTransport, LongSupplier longSupplier) {
        this.cancelled = new AtomicBoolean();
        this.pollInProgress = new AtomicBoolean();
        this.stateLock = new Object();
        this.status = "Esperando tu autorización…";
        this.progressListener = new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                DeviceLogin.lambda$new$0();
            }
        };
        this.store = loginPersistence;
        this.transport = httpTransport;
        this.clock = longSupplier;
    }

    public String statusText() {
        return this.status;
    }

    public void setProgressListener(Runnable runnable) {
        this.progressListener = runnable;
    }

    private void status(String str) {
        this.status = str;
        this.progressListener.run();
    }

    public Challenge begin() throws ApiException {
        checkCancelled();
        long jGeneration = this.store.generation();
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("client_id", CLIENT_ID);
            HttpTransport.Response responseSend = this.transport.send("POST", "https://auth.openai.com/api/accounts/deviceauth/usercode", jSONObject.toString(), "application/json", null);
            if (!responseSend.successful()) {
                if (responseSend.status == 404) {
                    throw new ApiException("El inicio de sesión por código no está habilitado. Comprueba los ajustes de seguridad de tu cuenta de ChatGPT.", false, 404);
                }
                throw HttpTransport.failure(responseSend);
            }
            JSONObject jSONObjectJson = responseSend.json();
            String strStringOrNull = QuotaSnapshot.stringOrNull(jSONObjectJson, "user_code");
            if (strStringOrNull == null) {
                strStringOrNull = QuotaSnapshot.stringOrNull(jSONObjectJson, "usercode");
            }
            String str = strStringOrNull;
            String strStringOrNull2 = QuotaSnapshot.stringOrNull(jSONObjectJson, "device_auth_id");
            if (str == null || strStringOrNull2 == null) {
                throw ApiException.invalidResponse();
            }
            Long lNullableLong = QuotaSnapshot.nullableLong(jSONObjectJson, "interval");
            Challenge challenge = new Challenge(str, strStringOrNull2, lNullableLong == null ? 5L : Math.max(1L, Math.min(lNullableLong.longValue(), 900L)), 900000 + this.clock.getAsLong(), jGeneration);
            synchronized (this.stateLock) {
                checkCancelled();
                if (jGeneration != this.store.generation()) {
                    throw cancelledError();
                }
                this.store.savePendingChallenge(challenge);
            }
            return challenge;
        } catch (JSONException unused) {
            throw ApiException.invalidResponse();
        }
    }

    public Challenge resume() {
        if (this.cancelled.get()) {
            return null;
        }
        return this.store.loadPendingChallenge();
    }

    public boolean poll(Challenge challenge) throws ApiException {
        checkCancelled();
        if (this.clock.getAsLong() >= challenge.expiresAtMillis) {
            throw new ApiException("El código ha caducado. Inicia el acceso de nuevo.", false, 0);
        }
        if (this.store.generation() != challenge.storeGeneration) throw cancelledError();
        if (!this.pollInProgress.compareAndSet(false, true)) return false;
        try {
            long now = this.clock.getAsLong();
            if (now < challenge.nextPollAtMillis) return false;
            challenge.nextPollAtMillis = now + challenge.pollIntervalSeconds * 1000;
            if (challenge.authorizationCode == null) {
                status("Comprobando la autorización de OpenAI…");
                JSONObject request = new JSONObject();
                try {
                    request.put("device_auth_id", challenge.deviceAuthId);
                    request.put("user_code", challenge.userCode);
                } catch (JSONException invalidRequest) {
                    throw ApiException.invalidResponse();
                }
                HttpTransport.Response response;
                try {
                    response = this.transport.send("POST", HttpTransport.AUTH + "/api/accounts/deviceauth/token",
                            request.toString(), "application/json", null);
                } catch (ApiException error) {
                    throw error.atStage("Comprobar autorización");
                }
                checkCancelled();
                if (response.status == 403 || response.status == 404) {
                    status("Esperando tu autorización en el navegador…");
                    return false;
                }
                if (response.status == 429) {
                    challenge.nextPollAtMillis = this.clock.getAsLong() + Math.max(30_000L,
                            response.retryAfterMillis == null ? 30_000L : response.retryAfterMillis);
                    status("OpenAI pide esperar antes de volver a comprobar el acceso…");
                    return false;
                }
                if (!response.successful()) throw HttpTransport.failure(response).atStage("Comprobar autorización");
                JSONObject responseJson = response.json();
                String authorizationCode = QuotaSnapshot.stringOrNull(responseJson, "authorization_code");
                String codeVerifier = QuotaSnapshot.stringOrNull(responseJson, "code_verifier");
                if (authorizationCode == null || codeVerifier == null) {
                    throw ApiException.invalidResponse().atStage("Comprobar autorización");
                }
                synchronized (this.stateLock) {
                    checkCancelled();
                    if (this.store.generation() != challenge.storeGeneration) throw cancelledError();
                    challenge.authorizationCode = authorizationCode;
                    challenge.codeVerifier = codeVerifier;
                    this.store.savePendingChallenge(challenge);
                }
            }
            checkCancelled();
            if (!this.store.markExchangeStarted(challenge)) throw cancelledError();
            status("Autorización recibida. Completando el acceso…");
            try {
                String form = "grant_type=authorization_code&client_id=" + encode(CLIENT_ID)
                        + "&code=" + encode(challenge.authorizationCode)
                        + "&redirect_uri=" + encode(HttpTransport.AUTH + "/deviceauth/callback")
                        + "&code_verifier=" + encode(challenge.codeVerifier);
                HttpTransport.Response exchange = this.transport.send("POST", HttpTransport.AUTH + "/oauth/token",
                        form, "application/x-www-form-urlencoded", null, 120_000);
                if (!exchange.successful()) {
                    this.store.clearPendingChallenge();
                    String message = "invalid_grant".equals(exchange.oauthError())
                            ? "Completar acceso: OpenAI rechazó el código de autorización. Genera un código nuevo."
                            : "Completar acceso: OpenAI rechazó la solicitud de sesión (HTTP " + exchange.status
                                    + "). Genera un código nuevo.";
                    throw HttpTransport.failure(exchange).stopRetrying(message);
                }
                TokenBundle tokens;
                try {
                    tokens = TokenBundle.fromResponse(exchange.json(), null);
                } catch (ApiException error) {
                    this.store.clearPendingChallenge();
                    throw error.atStage("Leer sesión de OpenAI");
                }
                synchronized (this.stateLock) {
                    checkCancelled();
                    try {
                        if (!this.store.completeLogin(tokens, challenge)) throw cancelledError();
                    } catch (ApiException error) {
                        throw error.atStage("Guardar sesión en el móvil");
                    }
                }
                status("Cuenta conectada.");
                return true;
            } catch (ApiException error) {
                // An authorization-code POST can be replayed only when transport confirms
                // it never left the device. An uncertain response consumes no new code.
                if (error.retryable && !error.requestMayHaveReachedServer) {
                    synchronized (this.stateLock) {
                        checkCancelled();
                        if (this.store.resetUnsentExchange(challenge)) throw error.atStage("Completar acceso");
                        throw cancelledError();
                    }
                }
                this.store.clearPendingChallenge();
                throw error.stopRetrying("Completar acceso: " + error.userMessage
                        + " No se puede confirmar la sesión. Genera un código nuevo.");
            }
        } finally {
            this.pollInProgress.set(false);
        }
    }

    public void cancel() {
        synchronized (this.stateLock) {
            this.cancelled.set(true);
            this.store.clearPendingChallenge();
        }
    }

    private void checkCancelled() throws ApiException {
        if (this.cancelled.get()) {
            throw cancelledError();
        }
    }

    private static ApiException cancelledError() {
        return new ApiException("El inicio de sesión se canceló. Puedes iniciarlo de nuevo.", false, 0);
    }

    private static String encode(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8");
        } catch (UnsupportedEncodingException unused) {
            throw new AssertionError();
        }
    }

    public static final class Challenge {
        String authorizationCode;
        String codeVerifier;
        final String deviceAuthId;
        volatile boolean exchangeStarted;
        public final long expiresAtMillis;
        volatile long nextPollAtMillis;
        public final long pollIntervalSeconds;
        final long storeGeneration;
        public final String userCode;
        public final String verificationUrl = "https://auth.openai.com/codex/device";

        public long earliestNextPollMillis() {
            return this.nextPollAtMillis;
        }

        Challenge(String str, String str2, long j, long j2, long j3) {
            this.userCode = str;
            this.deviceAuthId = str2;
            this.pollIntervalSeconds = j;
            this.expiresAtMillis = j2;
            this.storeGeneration = j3;
        }

        JSONObject toJson() throws JSONException {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("user_code", this.userCode);
            jSONObject.put("device_auth_id", this.deviceAuthId);
            jSONObject.put("interval", this.pollIntervalSeconds);
            jSONObject.put("expires_at", this.expiresAtMillis);
            jSONObject.put("generation", this.storeGeneration);
            jSONObject.put("exchange_started", this.exchangeStarted);
            jSONObject.put("authorization_code", this.authorizationCode);
            jSONObject.put("code_verifier", this.codeVerifier);
            return jSONObject;
        }

        static Challenge fromJson(JSONObject jSONObject) throws JSONException {
            Challenge challenge = new Challenge(jSONObject.getString("user_code"), jSONObject.getString("device_auth_id"), Math.max(1L, Math.min(900L, jSONObject.getLong("interval"))), jSONObject.getLong("expires_at"), jSONObject.getLong("generation"));
            challenge.exchangeStarted = jSONObject.optBoolean("exchange_started", false);
            challenge.authorizationCode = QuotaSnapshot.stringOrNull(jSONObject, "authorization_code");
            String strStringOrNull = QuotaSnapshot.stringOrNull(jSONObject, "code_verifier");
            challenge.codeVerifier = strStringOrNull;
            if ((challenge.authorizationCode == null) == (strStringOrNull == null)) {
                return challenge;
            }
            throw new JSONException("Incomplete authorization");
        }
    }
}
