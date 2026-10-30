package es.codexquota.mobile.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import org.json.JSONException;
import org.json.JSONObject;

/** HTTPS transport with bounded responses and conservative accounting of sent requests. */
class HttpTransport {
    static final String AUTH = "https://auth.openai.com";
    static final String BACKEND = "https://chatgpt.com/backend-api/wham";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    Response send(String method, String url, String body, String contentType, TokenBundle token)
            throws ApiException {
        return send(method, url, body, contentType, token, 30_000);
    }

    Response send(String method, String url, String body, String contentType, TokenBundle token,
                  int readTimeoutMillis) throws ApiException {
        HttpsURLConnection connection = null;
        boolean mayHaveReachedServer = false;
        try {
            if (!url.startsWith(AUTH + "/") && !url.startsWith(BACKEND + "/")) {
                throw new IllegalArgumentException("Unsupported origin");
            }
            connection = open(url);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestMethod(method);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("User-Agent", "CodexQuotaAndroid/0.2.0-beta.1");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token.accessToken);
                if (token.accountId != null) {
                    connection.setRequestProperty("ChatGPT-Account-Id", token.accountId);
                }
                if (token.fedramp) connection.setRequestProperty("X-OpenAI-Fedramp", "true");
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.connect();
                // Getting the output stream can send headers. From here onwards a failed
                // exchange must never be assumed safe to replay as an unsent POST.
                mayHaveReachedServer = true;
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            } else {
                connection.connect();
                mayHaveReachedServer = true;
            }
            int status = connection.getResponseCode();
            Long retryAfterMillis = retryAfter(connection.getHeaderField("Retry-After"));
            InputStream stream = status >= 300 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = "";
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > MAX_RESPONSE_BYTES) {
                            throw new IOException("Oversized response");
                        }
                        output.write(buffer, 0, count);
                    }
                    responseBody = new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return new Response(status, responseBody, retryAfterMillis);
        } catch (SocketTimeoutException error) {
            throw new ApiException("La conexión ha tardado demasiado.", false, 0, null,
                    true, mayHaveReachedServer, "NETWORK_TIMEOUT");
        } catch (UnknownHostException error) {
            throw new ApiException("No se pudo localizar el servidor. Comprueba la conexión.", false, 0,
                    null, true, mayHaveReachedServer, "NETWORK_DNS");
        } catch (SSLException error) {
            throw new ApiException("No se pudo establecer una conexión TLS segura.", false, 0, null,
                    false, mayHaveReachedServer, "NETWORK_TLS");
        } catch (IOException error) {
            throw new ApiException("Se ha interrumpido la conexión. Se puede volver a intentar.", false,
                    0, null, true, mayHaveReachedServer, "NETWORK_IO");
        } catch (IllegalArgumentException error) {
            throw new ApiException("No se pudo preparar la conexión.", false, 0, null,
                    false, mayHaveReachedServer, "REQUEST_CONFIG");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    HttpsURLConnection open(String url) throws IOException {
        return (HttpsURLConnection) new URL(url).openConnection();
    }

    static ApiException failure(Response response) {
        int status = response.status;
        if (status == 401) {
            return new ApiException("Tu sesión ha caducado. Inicia sesión otra vez.", true, 401);
        }
        if (status == 429) {
            return new ApiException("Demasiadas consultas. Espera antes de volver a intentar.", false,
                    429, response.retryAfterMillis, true, true, "HTTP_429");
        }
        if (status == 403) {
            return new ApiException("El servidor no permite esta operación para tu cuenta.", false, 403);
        }
        if (status == 404) {
            return new ApiException("Esta función no está disponible en el servidor para tu cuenta.", false, 404);
        }
        String message = status >= 500
                ? "El servicio no está disponible temporalmente. Inténtalo más tarde."
                : "El servidor rechazó la solicitud (" + status + ").";
        return new ApiException(message, false, status, response.retryAfterMillis,
                status >= 500, true, "HTTP_" + status);
    }

    static Long retryAfter(String value) {
        if (value == null) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0 || seconds > Long.MAX_VALUE / 1000) return null;
            return seconds * 1000;
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(0L, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis());
            } catch (RuntimeException invalidDate) {
                return null;
            }
        }
    }

    static final class Response {
        final String body;
        final Long retryAfterMillis;
        final int status;

        Response(int status, String body, Long retryAfterMillis) {
            this.status = status;
            this.body = body;
            this.retryAfterMillis = retryAfterMillis;
        }

        boolean successful() {
            return status >= 200 && status < 300;
        }

        JSONObject json() throws ApiException {
            try {
                return new JSONObject(body);
            } catch (JSONException error) {
                throw ApiException.invalidResponse();
            }
        }

        String oauthError() {
            try {
                JSONObject json = new JSONObject(body);
                Object error = json.opt("error");
                if (error instanceof String) return (String) error;
                if (error instanceof JSONObject) return ((JSONObject) error).optString("code", "");
                return json.optString("code", "");
            } catch (JSONException invalidBody) {
                return "";
            }
        }
    }
}
