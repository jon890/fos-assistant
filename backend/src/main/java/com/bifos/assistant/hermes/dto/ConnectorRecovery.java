package com.bifos.assistant.hermes.dto;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 커넥터가 오류 코드마다 고르는 복구 어휘다(ADR-092). 커넥터는 글을 쓰지 않고 이 어휘만 고른다.
 *
 * <p>대시보드 plugin 의 {@code ERROR_RECOVERIES} 와 같다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ConnectorRecovery {
    /** 승인 뒤 대상이 바뀌었다. 지금 값을 알리고 다시 조회할지 묻는다. */
    RECHECK("recheck"),
    /** 연결의 권한이나 값이 모자라다. 사용자가 연결을 다시 등록해야 한다. */
    RECONNECT("reconnect"),
    /** 인자가 맞지 않았다. 인자를 고쳐 새로 승인을 받는다. */
    FIX_INPUT("fix_input"),
    /** 서비스가 잠시 응답하지 않았다. 나중에 다시 시도할지 묻는다. */
    RETRY_LATER("retry_later");

    private final String word;

    public static Optional<ConnectorRecovery> fromWord(String word) {
        return Arrays.stream(values()).filter(value -> value.word.equals(word)).findFirst();
    }
}
