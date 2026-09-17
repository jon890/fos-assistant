package com.bifos.assistant.shared.error;

import org.springframework.http.HttpStatus;

/** Stable error codes returned to the web client. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND),
    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    AGENT_DISABLED(HttpStatus.CONFLICT),
    /** The caller has no Hermes profile bound. We never borrow another user's profile. */
    HERMES_BINDING_MISSING(HttpStatus.CONFLICT),
    /** A binding exists but its API key is not provisioned on this host. */
    HERMES_PROFILE_KEY_MISSING(HttpStatus.CONFLICT),
    HERMES_BINDING_DISABLED(HttpStatus.CONFLICT),
    HERMES_RUN_FAILED(HttpStatus.BAD_GATEWAY),
    HERMES_RUN_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    HERMES_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
