package com.bifos.assistant.usage.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/** 실행 기록의 다음 쪽이 시작할 루트 실행 자리다. */
record ExecutionCursor(Instant startedAt, long id) {

    private static final char SEPARATOR = '_';

    static ExecutionCursor of(AgentExecution execution) {
        return new ExecutionCursor(execution.startedAt(), execution.id());
    }

    String encode() {
        String raw = startedAt + String.valueOf(SEPARATOR) + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 읽을 수 없거나 번호가 양수가 아닌 값은 {@code VALIDATION_FAILED} 다. */
    static ExecutionCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int at = raw.lastIndexOf(SEPARATOR);
            Instant startedAt = Instant.parse(raw.substring(0, at));
            long id = Long.parseLong(raw.substring(at + 1));
            if (id <= 0) throw invalid();
            return new ExecutionCursor(startedAt, id);
        } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "cursor is not valid");
    }
}
