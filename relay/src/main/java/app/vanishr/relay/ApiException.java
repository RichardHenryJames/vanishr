package app.vanishr.relay;

import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    public final HttpStatus status;

    public ApiException(HttpStatus status, String code) {
        super(code, null, false, false);
        this.status = status;
    }
}