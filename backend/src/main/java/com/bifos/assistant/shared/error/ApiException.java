package com.bifos.assistant.shared.error;

import java.util.List;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<String> missingToolsets;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
        this.missingToolsets = null;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.missingToolsets = null;
    }

    public ApiException(ErrorCode code, String message, List<String> missingToolsets) {
        super(message);
        this.code = code;
        this.missingToolsets = List.copyOf(missingToolsets);
    }

    public ErrorCode code() {
        return code;
    }

    public List<String> missingToolsets() {
        return missingToolsets;
    }
}
