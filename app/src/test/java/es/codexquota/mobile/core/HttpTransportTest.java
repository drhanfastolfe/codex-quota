package es.codexquota.mobile.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HttpTransportTest {
    @Test public void rejectsUnknownOriginsBeforeOpeningConnection() throws Exception {
        FakeTransport transport = new FakeTransport();
        for (String url : new String[] {"https://auth.openai.com.evil.test/token",
                "http://auth.openai.com/token", "https://chatgpt.com/other"}) {
            try { transport.send("GET", url, null, "application/json", null); fail(); }
            catch (ApiException error) { assertFalse(error.retryable); }
        }
        assertEquals(0, transport.opened);
    }

    @Test public void dnsFailureIsKnownUnsent() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.connection.connectError = new UnknownHostException();
        try { transport.send("POST", HttpTransport.AUTH + "/oauth/token", "body", "type", null); fail(); }
        catch (ApiException error) {
            assertTrue(error.retryable);
            assertFalse(error.requestMayHaveReachedServer);
            assertEquals("NETWORK_DNS", error.diagnosticCode);
        }
        assertTrue(transport.connection.disconnected);
    }

    @Test public void outputAndResponseFailuresAreNotAssumedUnsent() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.connection.outputError = new SocketTimeoutException();
        try { transport.send("POST", HttpTransport.AUTH + "/oauth/token", "body", "type", null); fail(); }
        catch (ApiException error) { assertTrue(error.requestMayHaveReachedServer); }
        assertTrue(transport.connection.disconnected);

        transport = new FakeTransport();
        transport.connection.responseError = new SocketTimeoutException();
        try { transport.send("GET", HttpTransport.BACKEND + "/usage", null, "type", null); fail(); }
        catch (ApiException error) { assertTrue(error.requestMayHaveReachedServer); }
        assertTrue(transport.connection.disconnected);
    }

    @Test public void doesNotFollowRedirectsAndPreservesRetryAfter() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.connection.status = 302;
        transport.connection.retryAfter = "90";
        HttpTransport.Response result = transport.send("GET", HttpTransport.BACKEND + "/usage", null, "type", null);
        assertEquals(302, result.status);
        assertEquals(Long.valueOf(90_000L), result.retryAfterMillis);
        assertFalse(result.successful());
        assertFalse(transport.connection.getInstanceFollowRedirects());
        assertTrue(transport.connection.disconnected);
    }

    @Test public void responseSizeIsBounded() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.connection.response = new byte[1024 * 1024 + 1];
        try { transport.send("GET", HttpTransport.BACKEND + "/usage", null, "type", null); fail(); }
        catch (ApiException expected) { }
        assertTrue(transport.connection.disconnected);
    }

    @Test public void malformedRetryAfterDoesNotCreateInvalidDelays() {
        assertNull(HttpTransport.retryAfter("-1"));
        assertNull(HttpTransport.retryAfter("9223372036854776"));
        assertNull(HttpTransport.retryAfter("nonsense"));
        assertEquals(Long.valueOf(0L), HttpTransport.retryAfter("0"));
        assertEquals(Long.valueOf(30_000L), HttpTransport.retryAfter(" 30 "));
    }

    private static final class FakeTransport extends HttpTransport {
        final FakeConnection connection;
        int opened;
        FakeTransport() throws Exception { connection = new FakeConnection(); }
        @Override HttpsURLConnection open(String url) {
            opened++;
            return connection;
        }
    }

    private static final class FakeConnection extends HttpsURLConnection {
        IOException connectError;
        IOException outputError;
        IOException responseError;
        byte[] response = new byte[0];
        int status = 200;
        String retryAfter;
        boolean disconnected;
        FakeConnection() throws Exception { super(new URL("https://auth.openai.com/test")); }
        @Override public void connect() throws IOException { if (connectError != null) throw connectError; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() throws IOException {
            if (outputError != null) throw outputError;
            return new ByteArrayOutputStream();
        }
        @Override public int getResponseCode() throws IOException {
            if (responseError != null) throw responseError;
            return status;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public String getHeaderField(String name) { return "Retry-After".equals(name) ? retryAfter : null; }
        @Override public String getCipherSuite() { return "test"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException { return null; }
    }
}
