package es.codexquota.mobile.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public final class DeviceLoginTest {
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private MemoryLoginStore store;
    private FakeTransport transport;
    private DeviceLogin login;

    @Before public void setUp() {
        store = new MemoryLoginStore();
        transport = new FakeTransport();
        login = new DeviceLogin(store, transport, clock::get);
    }

    private DeviceLogin.Challenge begin() throws Exception {
        transport.reply(200, "{\"user_code\":\"ABCD-EFGH\",\"device_auth_id\":\"device\",\"interval\":5}");
        DeviceLogin.Challenge challenge = login.begin();
        assertSame(challenge, store.pending);
        assertEquals(clock.get() + 900_000L, challenge.expiresAtMillis);
        return challenge;
    }

    @Test public void authorizationPendingNeverExchangesTheCode() throws Exception {
        for (int status : new int[] {403, 404}) {
            setUp();
            DeviceLogin.Challenge challenge = begin();
            transport.reply(status, "{}");
            assertFalse(login.poll(challenge));
            assertEquals(0, store.exchanges);
            assertEquals(2, transport.urls.size());
            assertTrue(transport.urls.get(1).endsWith("/deviceauth/token"));
            assertSame(challenge, login.resume());
        }
    }

    @Test public void rateLimitDefersPollingWithoutAttemptingExchange() throws Exception {
        DeviceLogin.Challenge challenge = begin();
        transport.results.add(new HttpTransport.Response(429, "{}", 90_000L));
        assertFalse(login.poll(challenge));
        assertEquals(clock.get() + 90_000L, challenge.earliestNextPollMillis());
        assertFalse(login.poll(challenge));
        assertEquals(2, transport.urls.size());
        assertEquals(0, store.exchanges);
    }

    @Test public void authorizedCodeUsesPkceAndCommitsSessionOnce() throws Exception {
        DeviceLogin.Challenge challenge = begin();
        transport.reply(200, "{\"authorization_code\":\"code+value\",\"code_verifier\":\"verify/this=\"}");
        transport.reply(200, "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}");
        assertTrue(login.poll(challenge));
        assertEquals(1, store.exchanges);
        assertEquals(1, store.completions);
        assertNull(store.pending);
        String form = transport.bodies.get(2);
        assertTrue(form.contains("code=code%2Bvalue"));
        assertTrue(form.contains("code_verifier=verify%2Fthis%3D"));
        assertTrue(form.contains("redirect_uri=https%3A%2F%2Fauth.openai.com%2Fdeviceauth%2Fcallback"));
        assertEquals(120_000, transport.timeouts.get(2).intValue());
    }

    @Test public void uncertainExchangeIsNotAutomaticallyReplayed() throws Exception {
        DeviceLogin.Challenge challenge = begin();
        transport.reply(200, "{\"authorization_code\":\"code\",\"code_verifier\":\"verifier\"}");
        transport.results.add(new ApiException("timeout", false, 0, null, true, true, "NETWORK_TIMEOUT"));
        try {
            login.poll(challenge);
            fail("exchange must fail closed");
        } catch (ApiException failure) {
            assertFalse(failure.retryable);
        }
        assertNull(store.pending);
        clock.addAndGet(10_000);
        try { login.poll(challenge); fail("no pending exchange may be replayed"); }
        catch (ApiException expected) { }
        assertEquals(3, transport.urls.size());
        assertEquals(0, store.completions);
    }

    @Test public void confirmedUnsentExchangeKeepsCodeForControlledRetry() throws Exception {
        DeviceLogin.Challenge challenge = begin();
        transport.reply(200, "{\"authorization_code\":\"same-code\",\"code_verifier\":\"same-verifier\"}");
        transport.results.add(new ApiException("dns", false, 0, null, true, false, "NETWORK_DNS"));
        try { login.poll(challenge); fail("first attempt fails before send"); }
        catch (ApiException failure) { assertTrue(failure.retryable); }
        assertSame(challenge, store.pending);
        assertFalse(challenge.exchangeStarted);
        clock.addAndGet(10_000);
        transport.reply(200, "{\"access_token\":\"access\",\"refresh_token\":\"refresh\"}");
        assertTrue(login.poll(challenge));
        assertEquals(4, transport.urls.size());
        assertTrue(transport.urls.get(3).endsWith("/oauth/token"));
        assertEquals(transport.bodies.get(2), transport.bodies.get(3));
    }

    @Test public void cancelledOrExpiredChallengesNeverSendRequests() throws Exception {
        DeviceLogin.Challenge challenge = begin();
        login.cancel();
        try { login.poll(challenge); fail(); } catch (ApiException expected) { }
        assertEquals(1, transport.urls.size());
        setUp();
        challenge = begin();
        clock.set(challenge.expiresAtMillis);
        try { login.poll(challenge); fail(); } catch (ApiException expected) { }
        assertEquals(1, transport.urls.size());
    }

    @Test public void changedSessionCannotCompleteOldAuthorization() throws Exception {
        DeviceLogin.Challenge challenge = begin();
        store.generation++;
        try { login.poll(challenge); fail(); } catch (ApiException expected) { }
        assertEquals(1, transport.urls.size());
        assertEquals(0, store.completions);
    }

    @Test public void incompleteAuthorizationResponseNeverExchanges() throws Exception {
        DeviceLogin.Challenge challenge = begin();
        transport.reply(200, "{\"authorization_code\":\"code\"}");
        try { login.poll(challenge); fail(); } catch (ApiException expected) { }
        assertEquals(0, store.exchanges);
        assertEquals(2, transport.urls.size());
    }

    private static final class FakeTransport extends HttpTransport {
        final ArrayDeque<Object> results = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();
        void reply(int status, String json) { results.add(new Response(status, json, null)); }
        @Override Response send(String method, String url, String body, String contentType,
                                TokenBundle token, int timeout) throws ApiException {
            urls.add(url);
            bodies.add(body);
            timeouts.add(timeout);
            Object result = results.remove();
            if (result instanceof ApiException) throw (ApiException) result;
            return (Response) result;
        }
    }

    private static final class MemoryLoginStore implements LoginPersistence {
        long generation;
        int exchanges;
        int completions;
        DeviceLogin.Challenge pending;
        @Override public long generation() { return generation; }
        @Override public void savePendingChallenge(DeviceLogin.Challenge challenge) { pending = challenge; }
        @Override public DeviceLogin.Challenge loadPendingChallenge() { return pending; }
        @Override public void clearPendingChallenge() { pending = null; }
        @Override public boolean markExchangeStarted(DeviceLogin.Challenge challenge) {
            if (pending != challenge || generation != challenge.storeGeneration || challenge.exchangeStarted) return false;
            challenge.exchangeStarted = true;
            exchanges++;
            return true;
        }
        @Override public boolean resetUnsentExchange(DeviceLogin.Challenge challenge) {
            if (pending != challenge || generation != challenge.storeGeneration) return false;
            challenge.exchangeStarted = false;
            return true;
        }
        @Override public boolean completeLogin(TokenBundle tokens, DeviceLogin.Challenge challenge) {
            if (pending != challenge || generation != challenge.storeGeneration) return false;
            pending = null;
            generation++;
            completions++;
            return true;
        }
    }
}
