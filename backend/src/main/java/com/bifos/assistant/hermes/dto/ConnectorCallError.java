package com.bifos.assistant.hermes.dto;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 커넥터 도구가 실패했을 때 대시보드가 돌려주는 공통 어휘다. */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ConnectorCallError {
    CREDENTIAL_REJECTED("credential_rejected"),
    FORBIDDEN("forbidden"),
    INVALID_INPUT("invalid_input"),
    UNAVAILABLE("unavailable");

    private final String word;

    public static Optional<ConnectorCallError> fromWord(String word) {
        return Arrays.stream(values()).filter(value -> value.word.equals(word)).findFirst();
    }
}
