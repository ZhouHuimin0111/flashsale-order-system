package io.github.zhouhuimin.flashsale.api;

import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ApiError(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
