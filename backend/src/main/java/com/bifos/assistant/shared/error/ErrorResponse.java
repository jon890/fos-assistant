package com.bifos.assistant.shared.error;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record ErrorResponse(String code, String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> missingToolsets) {
    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
