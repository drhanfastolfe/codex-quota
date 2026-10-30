package es.codexquota.mobile;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import es.codexquota.mobile.core.ApiException;
import es.codexquota.mobile.core.ConsumeResult;
import es.codexquota.mobile.core.DeviceLogin;
import es.codexquota.mobile.core.QuotaClient;
import es.codexquota.mobile.core.QuotaSnapshot;
import es.codexquota.mobile.core.QuotaWindow;
import es.codexquota.mobile.core.ResetCredit;
import es.codexquota.mobile.core.ResetCredits;
import es.codexquota.mobile.core.TokenBundle;
import es.codexquota.mobile.core.TokenStore;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

/** Real repository/UI with offline session and server substitutes. No account is used. */
public abstract class OfflineIntegrationFixture {
    protected Context context;
    protected QuotaRepository repository;
    protected long now;

    static final class Server {
        static volatile boolean signedIn;
        static volatile String identity;
        static volatile QuotaSnapshot snapshot;
        static volatile boolean failConsume;
        static volatile boolean failFetch;
        static volatile int fetches;
        static final List<String> requestIds = new ArrayList<>();
        static final List<String> creditIds = new ArrayList<>();
        static void reset() {
            signedIn = true;
            identity = "offline-test-user|offline-test-account";
            failConsume = false;
            failFetch = false;
            fetches = 0;
            requestIds.clear();
            creditIds.clear();
        }
    }

    @Implements(value = TokenStore.class, isInAndroidSdk = false)
    public static class OfflineTokens {
        @Implementation protected void __constructor__(Context ignored) {}
        @Implementation protected boolean isSignedIn() { return Server.signedIn; }
        @Implementation protected String sessionIdentity() { return Server.signedIn ? Server.identity : null; }
        @Implementation protected String accountId() { return "offline-test-account"; }
        @Implementation protected long generation() { return 0L; }
        @Implementation protected DeviceLogin.Challenge loadPendingChallenge() { return null; }
        @Implementation protected void clearPendingChallenge() {}
        @Implementation protected void clear() { Server.signedIn = false; }
        @Implementation protected TokenBundle load() {
            throw new AssertionError("An integration test must never request real credentials");
        }
    }

    @Implements(value = QuotaClient.class, isInAndroidSdk = false)
    public static class OfflineQuotaClient {
        @Implementation protected void __constructor__(TokenStore ignored) {}
        @Implementation protected void __constructor__(Context ignored) {}
        @Implementation protected QuotaSnapshot fetchSnapshot() throws ApiException {
            Server.fetches++;
            if (Server.failFetch) throw new ApiException("Consulta de prueba no disponible", false, 503);
            return Server.snapshot;
        }
        @Implementation protected ConsumeResult consumeReset(String requestId, String creditId) throws ApiException {
            synchronized (Server.requestIds) {
                Server.requestIds.add(requestId);
                Server.creditIds.add(creditId);
            }
            if (Server.failConsume) throw new ApiException("Resultado incierto de prueba", false, 503);
            return new ConsumeResult("reset", 1);
        }
    }

    @Before public void prepareOfflineSession() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        for (String name : Arrays.asList("quota_cache", "pending_redemptions", "refresh_settings",
                "quota_alert_settings", "quota_alert_state", "quota_alert_diagnostics", "widget_themes")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
        Server.reset();
        now = System.currentTimeMillis();
        Server.snapshot = snapshot(now, "earliest", now + TimeUnit.HOURS.toMillis(3));
        ReflectionHelpers.setStaticField(QuotaRepository.class, "instance", null);
        repository = QuotaRepository.get(context);
        assertTrue("Fake signed-in session must intercept TokenStore", repository.signedIn());
        ReflectionHelpers.setField(repository, "snapshot", Server.snapshot);
    }

    protected static QuotaSnapshot snapshot(long fetchedAt, String firstCreditId, long firstExpiry) {
        return new QuotaSnapshot("plus", Arrays.asList(
                new QuotaWindow("5 horas", "codex:primary", 65, fetchedAt + TimeUnit.MINUTES.toMillis(25), 18000L),
                new QuotaWindow("Semanal", "codex:secondary", 30, fetchedAt + TimeUnit.DAYS.toMillis(2), 604800L)),
                new ResetCredits(2, Arrays.asList(
                        new ResetCredit("later", "codex_rate_limits", "available", "Crédito posterior", null,
                                fetchedAt - 1000, firstExpiry + TimeUnit.DAYS.toMillis(1)),
                        new ResetCredit(firstCreditId, "codex_rate_limits", "available", "Crédito próximo", null,
                                fetchedAt - 2000, firstExpiry))), fetchedAt, Boolean.TRUE);
    }

    protected String consume(QuotaRepository.ResetApproval approval) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        String[] message = {null};
        repository.consumeReset(approval, result -> { message[0] = result; completed.countDown(); });
        assertTrue("Offline reset callback completed", completed.await(5, TimeUnit.SECONDS));
        return message[0];
    }

    protected void finishIo() throws Exception {
        ScheduledExecutorService executor = ReflectionHelpers.getField(repository, "io");
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @After public void stopOfflineSession() throws Exception {
        if (repository != null) {
            ScheduledExecutorService executor = ReflectionHelpers.getField(repository, "io");
            executor.shutdownNow();
            executor.awaitTermination(3, TimeUnit.SECONDS);
        }
        ReflectionHelpers.setStaticField(QuotaRepository.class, "instance", null);
    }
}
