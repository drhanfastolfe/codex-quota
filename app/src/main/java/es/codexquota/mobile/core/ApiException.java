package es.codexquota.mobile.core;

public final class ApiException extends Exception {
    public final boolean authenticationRequired;
    public final String diagnosticCode;
    public final int httpStatus;
    public final boolean requestMayHaveReachedServer;
    public final Long retryAfterMillis;
    public final boolean retryable;
    public final String userMessage;

    public ApiException(String str, boolean z, int i) {
        this(str, z, i, null);
    }

    public ApiException(String message, boolean authenticationRequired, int httpStatus, Long retryAfterMillis) {
        this(message, authenticationRequired, httpStatus, retryAfterMillis, false, true,
                httpStatus > 0 ? "HTTP_" + httpStatus : "LOCAL");
    }

    ApiException(String str, boolean z, int i, Long l, boolean z2, boolean z3, String str2) {
        super(str);
        this.userMessage = str;
        this.authenticationRequired = z;
        this.httpStatus = i;
        this.retryAfterMillis = l;
        this.retryable = z2;
        this.requestMayHaveReachedServer = z3;
        this.diagnosticCode = str2;
    }

    ApiException atStage(String str) {
        return new ApiException(str + ": " + this.userMessage + " [" + this.diagnosticCode + "]", this.authenticationRequired, this.httpStatus, this.retryAfterMillis, this.retryable, this.requestMayHaveReachedServer, this.diagnosticCode);
    }

    ApiException stopRetrying(String str) {
        return new ApiException(str + " [" + this.diagnosticCode + "]", this.authenticationRequired, this.httpStatus, this.retryAfterMillis, false, this.requestMayHaveReachedServer, this.diagnosticCode);
    }

    static ApiException invalidResponse() {
        return new ApiException("El servidor devolvió datos que esta versión no reconoce.", false, 0, null, false, true, "RESPONSE_FORMAT");
    }

    static ApiException signedOut() {
        return new ApiException("Inicia sesión para consultar tu cuota de Codex.", true, 401);
    }
}
