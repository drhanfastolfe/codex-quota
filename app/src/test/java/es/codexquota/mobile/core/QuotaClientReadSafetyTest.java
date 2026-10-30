package es.codexquota.mobile.core;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, shadows = QuotaClientReadSafetyTest.MemoryTokens.class)
public final class QuotaClientReadSafetyTest {
    private FakeTransport transport;
    private QuotaClient client;

    @Before public void setUp() {
        MemoryTokens.tokens = new TokenBundle("access-old", "refresh-old", "account", "user", "plus", false, Long.MAX_VALUE);
        transport = new FakeTransport();
        client = new QuotaClient(new TokenStore(ApplicationProvider.getApplicationContext()), transport);
    }

    @Test public void reset401RepairsSessionButNeverReplaysCreditPost() throws Exception {
        transport.reply(401, "{}");
        transport.reply(200, "{\"access_token\":\"access-new\",\"refresh_token\":\"refresh-new\"}");
        try {
            client.consumeReset("same-request", "same-credit");
            fail("A new confirmation must be required");
        } catch (ApiException failure) {
            assertTrue(failure.userMessage, failure.userMessage.contains("Confirma de nuevo"));
            assertFalse(failure.retryable);
        }
        assertEquals(1, transport.redemptionRequests());
        assertEquals("access-new", MemoryTokens.tokens.accessToken);
        assertTrue(transport.responses.isEmpty());
        // Only a separate caller action (the confirmed repository path) can consume again.
        transport.reply(200, "{\"code\":\"reset\",\"windows_reset\":1}");
        assertEquals("reset", client.consumeReset("same-request", "same-credit").code);
        assertEquals(2, transport.redemptionRequests());
        assertEquals(transport.bodies.get(0), transport.bodies.get(2));
    }

    @Test public void read401MayRefreshCredentialsAndRetryTheRead() throws Exception {
        transport.reply(401, "{}");
        transport.reply(200, "{\"access_token\":\"access-new\",\"refresh_token\":\"refresh-new\"}");
        transport.reply(200, "{\"rate_limit\":{\"allowed\":true,\"primary_window\":{\"used_percent\":20,\"limit_window_seconds\":18000}},\"rate_limit_reset_credits\":{\"available_count\":0}}");
        transport.reply(200, "{\"available_count\":0,\"credits\":[]}");
        QuotaSnapshot result = client.fetchSnapshot();
        assertEquals(Boolean.TRUE, result.ordinaryUsageAllowed);
        assertEquals(1, result.windows.size());
        assertEquals(0, transport.redemptionRequests());
        assertEquals(4, transport.urls.size());
        assertTrue(transport.urls.get(0).endsWith("/usage"));
        assertTrue(transport.urls.get(1).endsWith("/oauth/token"));
        assertTrue(transport.urls.get(2).endsWith("/usage"));
        assertEquals("GET", transport.methods.get(2));
    }

    @Test public void failedSessionRefreshStillCannotReplayReset() throws Exception {
        transport.reply(401, "{}");
        transport.reply(500, "{}");
        try { client.consumeReset("request", "credit"); fail(); }
        catch (ApiException expected) { }
        assertEquals(1, transport.redemptionRequests());
        assertEquals(2, transport.urls.size());
    }

    @Implements(TokenStore.class)
    public static class MemoryTokens {
        static TokenBundle tokens;
        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected TokenBundle load() { return tokens; }
        @Implementation protected long generation() { return 0L; }
        @Implementation protected boolean replaceIfUnchanged(TokenBundle previous, TokenBundle updated) {
            if (!previous.sameCredentials(tokens)) return false;
            tokens = updated;
            return true;
        }
    }

    private static final class FakeTransport extends HttpTransport {
        final ArrayDeque<Response> responses = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        void reply(int status, String json) { responses.add(new Response(status, json, null)); }
        int redemptionRequests() {
            int count = 0;
            for (String url : urls) if (url.endsWith("/rate-limit-reset-credits/consume")) count++;
            return count;
        }
        @Override Response send(String method, String url, String body, String contentType, TokenBundle token, int timeout) {
            urls.add(url); methods.add(method); bodies.add(body);
            return responses.remove();
        }
    }
}
