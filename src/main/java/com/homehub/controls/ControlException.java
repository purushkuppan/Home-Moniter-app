package com.homehub.controls;

import org.springframework.http.HttpStatus;

/** A control request that cannot be carried out; the message is safe to show to the user. */
public class ControlException extends RuntimeException {

    private final HttpStatus status;

    public ControlException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
