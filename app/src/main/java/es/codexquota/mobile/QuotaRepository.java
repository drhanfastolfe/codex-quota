package es.codexquota.mobile;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;
import android.os.PersistableBundle;
import es.codexquota.mobile.core.QueryRetryPolicy;
import es.codexquota.mobile.core.ApiException;
import es.codexquota.mobile.core.DeviceLogin;
import es.codexquota.mobile.core.LoginRetryPolicy;
import es.codexquota.mobile.core.PendingReset;
import es.codexquota.mobile.core.QuotaClient;
import es.codexquota.mobile.core.QuotaSnapshot;
import es.codexquota.mobile.core.RefreshPolicy;
import es.codexquota.mobile.core.ResetCredit;
import es.codexquota.mobile.core.TokenStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class QuotaRepository {
    public static final String CHANGED = "es.codexquota.mobile.CHANGED";
    private static final int MANUAL_JOB = 202;
    private static final int PERIODIC_JOB = 201;
    static final int RETRY_JOB = 203;
    static final String RETRY_GENERATION = "session_generation";
    private static final String REFRESH_INTERVAL_KEY = "interval_minutes";
    private static QuotaRepository instance;
    private volatile int accountGeneration;
    private volatile boolean busy;
    private final SharedPreferences cache;
    private volatile DeviceLogin.Challenge challenge;
    private final QuotaClient client;
    private volatile boolean consuming;
    private final Context context;
    private volatile String error;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean loggingOut;
    private volatile DeviceLogin login;
    private int loginFailures;
    private int loginGeneration;
    private ScheduledFuture<?> pollTask;
    private final SharedPreferences redemptions;
    private final SharedPreferences refreshSettings;
    private volatile boolean signingIn;
    private volatile QuotaSnapshot snapshot;
    private final TokenStore tokens;

    public interface Completion {
        void done(String str);
    }

    public static synchronized QuotaRepository get(Context context) {
        if (instance == null) {
            instance = new QuotaRepository(context.getApplicationContext());
        }
        return instance;
    }

    private QuotaRepository(Context context) {
        this.error = "";
        this.context = context;
        context.deleteSharedPreferences("quota_history");
        SharedPreferences sharedPreferences = context.getSharedPreferences("quota_cache", 0);
        this.cache = sharedPreferences;
        this.redemptions = context.getSharedPreferences("pending_redemptions", 0);
        this.refreshSettings = context.getSharedPreferences("refresh_settings", 0);
        TokenStore tokenStore = new TokenStore(context);
        this.tokens = tokenStore;
        this.login = new DeviceLogin(tokenStore);
        this.client = new QuotaClient(tokenStore);
        this.error = sharedPreferences.getString("error", "");
        try {
            this.snapshot = QuotaSnapshot.fromJson(sharedPreferences.getString("snapshot", ""));
        } catch (Exception unused) {
            this.snapshot = null;
        }
        try {
            String strSessionIdentity = this.tokens.sessionIdentity();
            if (strSessionIdentity == null || !strSessionIdentity.equals(this.cache.getString("snapshot_account", null))) {
                this.snapshot = null;
            }
        } catch (Exception unused2) {
            this.snapshot = null;
        }
        try {
            this.challenge = this.login.resume();
            if (this.challenge != null) {
                this.signingIn = true;
                int i = this.loginGeneration + 1;
                this.loginGeneration = i;
                queuePoll(i, this.login);
            }
        } catch (Exception unused3) {
            this.error = "El acceso pendiente ha caducado. Genera un código nuevo.";
        }
        this.refreshSettings.edit().putInt(REFRESH_INTERVAL_KEY, refreshIntervalMinutes()).apply();
        schedulePeriodic();
    }

    public boolean signedIn() {
        return !this.loggingOut && this.tokens.isSignedIn();
    }

    public String sessionIdentity() {
        try {
            return signedIn() ? this.tokens.sessionIdentity() : null;
        } catch (ApiException unreadableSession) {
            return null;
        }
    }

    public QuotaSnapshot snapshot() {
        return this.snapshot;
    }

    public String error() {
        return this.error;
    }

    public boolean busy() {
        return this.busy;
    }

    public boolean signingIn() {
        return this.signingIn;
    }

    public boolean consuming() {
        return this.consuming;
    }

    public DeviceLogin.Challenge challenge() {
        return this.challenge;
    }

    public String loginStatus() {
        return this.login.statusText();
    }

    public int refreshIntervalMinutes() {
        try {
            return RefreshPolicy.normalizeMinutes(this.refreshSettings.getInt(REFRESH_INTERVAL_KEY, 30));
        } catch (ClassCastException unused) {
            return 30;
        }
    }

    public synchronized boolean setRefreshIntervalMinutes(int i) {
        if (!this.refreshSettings.edit().putInt(REFRESH_INTERVAL_KEY, RefreshPolicy.normalizeMinutes(i)).commit()) {
            return false;
        }
        schedulePeriodic();
        return true;
    }

    public synchronized void refreshIfStale() {
        if (signedIn() && !this.busy && !this.consuming && !this.signingIn) {
            if (cacheNeedsRefresh()) {
                JobScheduler jobScheduler = (JobScheduler) this.context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if (jobScheduler != null && jobScheduler.getPendingJob(MANUAL_JOB) != null) {
                    jobScheduler.cancel(MANUAL_JOB);
                }
                refresh(null);
            }
        }
    }

    public synchronized void scheduleManualIfStale() {
        if (signedIn() && !this.busy && !this.consuming && !this.signingIn && cacheNeedsRefresh()) {
            scheduleManual();
        }
    }

    private boolean cacheNeedsRefresh() {
        QuotaSnapshot quotaSnapshot = this.snapshot;
        return quotaSnapshot == null || RefreshPolicy.isStale(quotaSnapshot.fetchedAtMillis, System.currentTimeMillis(), refreshIntervalMinutes());
    }

    private void changed() {
        this.context.sendBroadcast(new Intent(CHANGED).setPackage(this.context.getPackageName()));
        QuotaWidget.renderAll(this.context);
    }

    private synchronized void saveSnapshot(QuotaSnapshot quotaSnapshot, int i, String str) throws Exception {
        if (i != this.accountGeneration || !signedIn() || str == null || !str.equals(this.tokens.sessionIdentity())) {
            throw ApiExceptionForUi.signedOut();
        }
        this.snapshot = quotaSnapshot;
        this.cache.edit().putString("snapshot", quotaSnapshot.toJson()).putString("snapshot_account", str).remove("error").apply();
        this.error = "";
        try {
            QuotaAlerts.onSnapshot(this.context, str, quotaSnapshot);
        } catch (Exception unused) {
        }
    }

    private void setError(Exception exc) {
        this.error = exc instanceof ApiException ? exc.getMessage() : "No se pudo actualizar. Inténtalo de nuevo.";
        this.cache.edit().putString("error", this.error).apply();
    }

    public synchronized void refresh(final Completion completion) {
        startRefresh(completion, false);
    }

    /** Called by Android jobs; retry work belongs to the account that scheduled it. */
    synchronized void refreshFromJob(int jobId, long generation, Completion completion) {
        if (jobId == RETRY_JOB && generation != this.tokens.generation()) {
            if (completion != null) completion.done("La sesión ha cambiado.");
            return;
        }
        startRefresh(completion, jobId == RETRY_JOB);
    }

    private synchronized void startRefresh(final Completion completion, boolean retry) {
        if (this.busy || this.consuming || this.signingIn || this.loggingOut) {
            if (completion != null) completion.done("Espera a que termine la operación actual.");
            return;
        }
        long notBefore = this.cache.getLong("read_not_before", 0L);
        if (notBefore > System.currentTimeMillis()) {
            // Respect Retry-After even if a manual or regular job arrives meanwhile.
            if (completion != null) completion.done("El servidor pide esperar antes de consultar de nuevo.");
            return;
        }
        if (!retry) {
            this.cache.edit().remove("read_retry_attempts").apply();
            cancelReadRetry();
        }
        final int generation = this.accountGeneration;
        this.busy = true;
        changed();
        this.io.execute(() -> runRefresh(generation, completion));
    }

    private void runRefresh(int generation, Completion completion) {
        String message = "";
        try {
            if (signedIn() && generation == this.accountGeneration) {
                saveSnapshot(this.client.fetchSnapshot(), generation, this.tokens.sessionIdentity());
                ApiException partialFailure = this.client.lastReadFailure();
                if (partialFailure == null) {
                    clearReadRetryState();
                } else {
                    scheduleReadRetry(partialFailure, generation);
                }
                schedulePeriodic();
            } else {
                message = "Inicia sesión para consultar tu cuota.";
            }
        } catch (Exception failure) {
            synchronized (this) {
                if (generation != this.accountGeneration || this.loggingOut) {
                    message = "La sesión ha cambiado.";
                } else {
                    setError(failure);
                    if (scheduleReadRetry(failure, generation)) {
                        this.error += " Reintentaremos la consulta automáticamente.";
                        this.cache.edit().putString("error", this.error).apply();
                    }
                    message = this.error;
                }
            }
        } finally {
            this.busy = false;
            changed();
            if (completion != null) completion.done(message);
        }
    }

    private synchronized boolean scheduleReadRetry(Exception failure, int generation) {
        if (generation != this.accountGeneration || !signedIn()) return false;
        int attempt = this.cache.getInt("read_retry_attempts", 0) + 1;
        this.cache.edit().putInt("read_retry_attempts", attempt).apply();
        long delayMillis = QueryRetryPolicy.delayMillis(failure, attempt);
        if (failure instanceof ApiException) {
            Long retryAfter = ((ApiException) failure).retryAfterMillis;
            if (retryAfter != null && retryAfter > 0) {
                this.cache.edit().putLong("read_not_before",
                        QueryRetryPolicy.dueAt(System.currentTimeMillis(), retryAfter)).apply();
            }
        }
        if (delayMillis < 0) {
            cancelReadRetry();
            return false;
        }
        JobScheduler scheduler = (JobScheduler) this.context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        PersistableBundle extras = new PersistableBundle();
        extras.putLong(RETRY_GENERATION, this.tokens.generation());
        JobInfo retry = new JobInfo.Builder(RETRY_JOB, new ComponentName(this.context, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setMinimumLatency(delayMillis)
                .setExtras(extras)
                .build();
        return scheduler.schedule(retry) == JobScheduler.RESULT_SUCCESS;
    }

    private synchronized void cancelReadRetry() {
        JobScheduler scheduler = (JobScheduler) this.context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(RETRY_JOB);
    }

    private synchronized void clearReadRetryState() {
        cancelReadRetry();
        this.cache.edit().remove("read_retry_attempts").remove("read_not_before").apply();
    }

    public synchronized void beginLogin() {
        if (!this.signingIn && !this.busy && !this.consuming && !this.loggingOut) {
            this.signingIn = true;
            this.error = "";
            this.challenge = null;
            this.loginFailures = 0;
            this.accountGeneration++;
            clearReadRetryState();
            QuotaAlerts.clear(this.context);
            this.cache.edit().remove("error").apply();
            final DeviceLogin deviceLogin = new DeviceLogin(this.tokens);
            this.login = deviceLogin;
            final int i = this.loginGeneration + 1;
            this.loginGeneration = i;
            watchProgress(i, deviceLogin);
            changed();
            this.io.execute(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    QuotaRepository.this.startLogin(deviceLogin, i);
                }
            });
        }
    }

    void startLogin(DeviceLogin deviceLogin, int i) {
        try {
            DeviceLogin.Challenge challengeBegin = deviceLogin.begin();
            synchronized (this) {
                if (i != this.loginGeneration) {
                    return;
                }
                this.challenge = challengeBegin;
                changed();
                queuePoll(i, deviceLogin);
            }
        } catch (Exception e) {
            synchronized (this) {
                if (i != this.loginGeneration) {
                    return;
                }
                this.signingIn = false;
                setError(e);
                changed();
            }
        }
    }

    private void watchProgress(final int i, DeviceLogin deviceLogin) {
        deviceLogin.setProgressListener(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                QuotaRepository.this.onLoginProgress(i);
            }
        });
    }

    void onLoginProgress(int i) {
        synchronized (this) {
            if (i == this.loginGeneration && this.signingIn) {
                changed();
            }
        }
    }

    public synchronized void resumeLogin() {
        if (!this.loggingOut && !this.consuming && !signedIn()) {
            if (this.signingIn && this.challenge != null) {
                queuePoll(this.loginGeneration, this.login, 0L);
                return;
            }
            if (this.signingIn) {
                return;
            }
            DeviceLogin deviceLogin = new DeviceLogin(this.tokens);
            DeviceLogin.Challenge challengeResume = deviceLogin.resume();
            if (challengeResume == null) {
                return;
            }
            this.login = deviceLogin;
            this.challenge = challengeResume;
            this.signingIn = true;
            this.loginFailures = 0;
            int i = this.loginGeneration + 1;
            this.loginGeneration = i;
            watchProgress(i, deviceLogin);
            queuePoll(i, deviceLogin, 0L);
            changed();
        }
    }

    private void queuePoll(int i, DeviceLogin deviceLogin) {
        queuePoll(i, deviceLogin, 0L);
    }

    private synchronized void queuePoll(final int i, final DeviceLogin deviceLogin, long j) {
        final DeviceLogin.Challenge challenge = this.challenge;
        if (challenge != null && i == this.loginGeneration && this.signingIn) {
            ScheduledFuture<?> scheduledFuture = this.pollTask;
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
            this.pollTask = this.io.schedule(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    QuotaRepository.this.pollLogin(i, deviceLogin, challenge);
                }
            }, Math.max(j, Math.max(0L, challenge.earliestNextPollMillis() - System.currentTimeMillis())), TimeUnit.MILLISECONDS);
        }
    }

    private void pollLogin(int generation, DeviceLogin attempt, DeviceLogin.Challenge pending) {
        long storeGeneration;
        synchronized (this) {
            if (generation != this.loginGeneration || !this.signingIn) return;
            storeGeneration = this.tokens.generation();
        }
        // Do not hold the repository lock while waiting for HTTPS. Resume/cancel and
        // rendering must remain responsive while the authorization-code exchange runs.
        try {
            boolean completed = attempt.poll(pending);
            synchronized (this) {
                if (generation != this.loginGeneration) {
                    if (completed) this.tokens.clearIfGeneration(storeGeneration + 1);
                    return;
                }
                if (completed) {
                    this.signingIn = false;
                    this.challenge = null;
                    this.loginFailures = 0;
                    this.error = "";
                    this.cache.edit().remove("error").remove("snapshot").remove("snapshot_account").apply();
                    this.snapshot = null;
                    changed();
                    refresh(null);
                    return;
                }
                this.loginFailures = 0;
                if (!this.error.isEmpty()) {
                    this.error = "";
                    this.cache.edit().remove("error").apply();
                }
                changed();
                queuePoll(generation, attempt);
            }
        } catch (Exception failure) {
            synchronized (this) {
                if (generation != this.loginGeneration) return;
                if (failure instanceof ApiException && LoginRetryPolicy.canRetry(
                        (ApiException) failure, pending.expiresAtMillis, System.currentTimeMillis())) {
                    long delay = LoginRetryPolicy.delayMillis((ApiException) failure, ++this.loginFailures);
                    this.error = failure.getMessage() + " Conservamos el código y volveremos a intentarlo.";
                    this.cache.edit().putString("error", this.error).apply();
                    changed();
                    queuePoll(generation, attempt, delay);
                } else {
                    this.signingIn = false;
                    this.challenge = null;
                    setError(failure);
                    changed();
                }
            }
        }
    }

    public synchronized void cancelLogin() {
        ScheduledFuture<?> scheduledFuture = this.pollTask;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        this.loginGeneration++;
        this.signingIn = false;
        this.challenge = null;
        this.login.cancel();
        changed();
    }

    public synchronized void logout() {
        this.loggingOut = true;
        this.accountGeneration++;
        clearReadRetryState();
        cancelLogin();
        this.tokens.clear();
        QuotaAlerts.clear(this.context);
        this.io.execute(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                QuotaRepository.this.finishLogout();
            }
        });
    }

    void finishLogout() {
        this.snapshot = null;
        this.error = "";
        this.cache.edit().clear().commit();
        this.loggingOut = false;
        JobScheduler jobScheduler = (JobScheduler) this.context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler != null) {
            jobScheduler.cancel(PERIODIC_JOB);
            jobScheduler.cancel(MANUAL_JOB);
            jobScheduler.cancel(RETRY_JOB);
        }
        changed();
    }

    public static final class ResetApproval {
        private final int generation;
        public final PendingReset request;
        public final boolean retry;

        ResetApproval(PendingReset pendingReset, boolean z, int i) {
            this.request = pendingReset;
            this.retry = z;
            this.generation = i;
        }
    }

    public boolean hasPendingReset() {
        try {
            String strSessionIdentity = this.tokens.sessionIdentity();
            return strSessionIdentity != null && this.redemptions.contains(redemptionKey(strSessionIdentity));
        } catch (Exception unused) {
            return false;
        }
    }

    public synchronized ResetApproval prepareReset() throws Exception {
        if (!signedIn()) {
            throw ApiExceptionForUi.signedOut();
        }
        String strSessionIdentity = this.tokens.sessionIdentity();
        if (strSessionIdentity == null) {
            throw ApiExceptionForUi.signedOut();
        }
        ResetCredit resetCreditEarliestForRedemption = null;
        PendingReset pendingResetFromStorage = PendingReset.fromStorage(this.redemptions.getString(redemptionKey(strSessionIdentity), null));
        if (pendingResetFromStorage != null) {
            return new ResetApproval(pendingResetFromStorage, true, this.accountGeneration);
        }
        if (this.snapshot != null && this.snapshot.resetCredits != null && this.snapshot.resetCredits.availableCount == 0) {
            throw new ApiException("No hay resets disponibles.", false, 0);
        }
        if (this.snapshot != null && this.snapshot.resetCredits != null) {
            resetCreditEarliestForRedemption = this.snapshot.resetCredits.earliestForRedemption(System.currentTimeMillis());
        }
        if (resetCreditEarliestForRedemption == null) {
            throw new ApiException("Actualiza para obtener todas las fechas y elegir el reset que caduca antes.", false, 0);
        }
        return new ResetApproval(PendingReset.forCredit(resetCreditEarliestForRedemption), false, this.accountGeneration);
    }

    public synchronized void consumeReset(final ResetApproval resetApproval, final Completion completion) {
        if (resetApproval == null) {
            if (completion != null) completion.done("Confirma el reset antes de continuar.");
            return;
        }
        if (!this.consuming && !this.busy && !this.signingIn && signedIn()) {
            final int i = resetApproval.generation;
            this.consuming = true;
            changed();
            this.io.execute(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    QuotaRepository.this.runConsumeReset(i, resetApproval, completion);
                }
            });
            return;
        }
        if (completion != null) {
            completion.done("Espera a que termine la operación actual y confirma el reset de nuevo.");
        }
    }

    void runConsumeReset(int i, ResetApproval resetApproval, Completion completion) {
        String strConcat;
        try {
            if (i != this.accountGeneration || !signedIn()) {
                throw ApiExceptionForUi.signedOut();
            }
            String strSessionIdentity = this.tokens.sessionIdentity();
            if (strSessionIdentity == null || strSessionIdentity.isEmpty()) {
                throw new Exception("Account identity unavailable");
            }
            String strRedemptionKey = redemptionKey(strSessionIdentity);
            ResetCredit resetCreditEarliestForRedemption = null;
            PendingReset pendingResetFromStorage = PendingReset.fromStorage(this.redemptions.getString(strRedemptionKey, null));
            if (pendingResetFromStorage == null) {
                if (resetApproval.retry) {
                    throw new ApiException("La solicitud pendiente ya ha terminado. Actualiza la cuota.", false, 0);
                }
                if (this.snapshot != null && this.snapshot.resetCredits != null) {
                    resetCreditEarliestForRedemption = this.snapshot.resetCredits.earliestForRedemption(System.currentTimeMillis());
                }
                if (resetCreditEarliestForRedemption == null || !resetCreditEarliestForRedemption.id.equals(resetApproval.request.creditId)) {
                    throw new ApiException("Los resets disponibles han cambiado. Actualiza y confirma de nuevo.", false, 0);
                }
                pendingResetFromStorage = resetApproval.request;
                if (!this.redemptions.edit().putString(strRedemptionKey, pendingResetFromStorage.toStorage()).commit()) {
                    throw new Exception("Idempotency key was not persisted");
                }
            }
            if (!pendingResetFromStorage.sameRequest(resetApproval.request)) {
                throw new ApiException("La solicitud ha cambiado. Confirma de nuevo el reset pendiente.", false, 0);
            }
            if (i != this.accountGeneration || !signedIn()) {
                throw ApiExceptionForUi.signedOut();
            }
            String str = this.client.consumeReset(pendingResetFromStorage.requestId, pendingResetFromStorage.creditId).code;
            if ("reset".equals(str)) {
                strConcat = "Restablecimiento aplicado.";
            } else if ("already_redeemed".equals(str)) {
                strConcat = "Esta solicitud ya se había canjeado. No se ha gastado otro crédito.";
            } else if ("nothing_to_reset".equals(str)) {
                strConcat = "No hay límites que se puedan restablecer ahora.";
            } else {
                if (!"no_credit".equals(str)) {
                    throw new Exception("Unknown outcome");
                }
                strConcat = "No quedan créditos de restablecimiento.";
            }
            this.redemptions.edit().remove(strRedemptionKey).commit();
            if ("reset".equals(str) || "already_redeemed".equals(str)) {
                invalidateConsumedCredit(pendingResetFromStorage.creditId, i, strSessionIdentity);
                QuotaAlerts.onCreditResolved(this.context, strSessionIdentity, pendingResetFromStorage.creditId);
            }
            try {
                saveSnapshot(this.client.fetchSnapshot(), i, strSessionIdentity);
            } catch (Exception e) {
                setError(e);
                strConcat = strConcat.concat(" Actualiza para comprobar la cuota.");
            }
        } catch (Exception e2) {
            setError(e2);
            strConcat = this.error + " Si lo reintentas, se reutilizará la misma solicitud para evitar gastar dos créditos.";
        }
        this.consuming = false;
        changed();
        if (completion != null) {
            completion.done(strConcat);
        }
    }

    /** Update only knowledge established by the successful credit response, not quota freshness. */
    private synchronized void invalidateConsumedCredit(String creditId, int generation, String identity) {
        if (generation != this.accountGeneration || !signedIn() || identity == null
                || !identity.equals(sessionIdentity()) || this.snapshot == null) return;
        QuotaSnapshot previous = this.snapshot;
        this.snapshot = previous.withResetCredits(previous.resetCredits == null ? null
                : previous.resetCredits.afterConfirmedConsumption(creditId, previous.fetchedAtMillis));
        // Keep fetchedAt and usage windows unchanged. onSnapshot must not treat this
        // local edit as a new server query or announce a quota recovery from it.
        this.cache.edit().putString("snapshot", this.snapshot.toJson()).putString("snapshot_account", identity).commit();
    }

    public synchronized void schedulePeriodic() {
        JobScheduler jobScheduler = (JobScheduler) this.context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) {
            return;
        }
        if (!signedIn()) {
            jobScheduler.cancel(PERIODIC_JOB);
            return;
        }
        int iRefreshIntervalMinutes = refreshIntervalMinutes();
        long jIntervalMillis = RefreshPolicy.intervalMillis(iRefreshIntervalMinutes);
        long jFlexMillis = RefreshPolicy.flexMillis(iRefreshIntervalMinutes);
        JobInfo pendingJob = jobScheduler.getPendingJob(PERIODIC_JOB);
        if (pendingJob != null && pendingJob.isPeriodic() && pendingJob.getIntervalMillis() == jIntervalMillis && pendingJob.getFlexMillis() == jFlexMillis) {
            return;
        }
        jobScheduler.schedule(new JobInfo.Builder(PERIODIC_JOB, new ComponentName(this.context, (Class<?>) RefreshJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(jIntervalMillis, jFlexMillis).build());
    }

    public synchronized void scheduleManual() {
        if (signedIn() && !this.busy && !this.consuming && !this.signingIn) {
            JobScheduler jobScheduler = (JobScheduler) this.context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobScheduler != null && jobScheduler.getPendingJob(MANUAL_JOB) == null) {
                jobScheduler.schedule(new JobInfo.Builder(MANUAL_JOB, new ComponentName(this.context, (Class<?>) RefreshJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0L).build());
            }
        }
    }

    private static final class ApiExceptionForUi {
        private ApiExceptionForUi() {
        }

        static ApiException signedOut() {
            return new ApiException("La sesión ha cambiado. Vuelve a conectar tu cuenta.", true, 401);
        }
    }

    private static String redemptionKey(String str) throws Exception {
        return "attempt_" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(str.getBytes(StandardCharsets.UTF_8)), 11);
    }
}
