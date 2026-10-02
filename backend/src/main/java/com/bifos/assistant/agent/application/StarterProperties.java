package com.bifos.assistant.agent.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 새 대화 화면의 추천 질문을 언제 어떻게 만들지 정한다.
 *
 * <p>값이 없으면 아래 기본값을 쓴다. 근거는 ADR-036 에 있다.
 *
 * @param enabled 추천을 만들지. 거짓이면 늘 추천이 없다고 답한다. 기본 참
 * @param refreshAfter 대화를 마쳤을 때 이보다 오래된 추천만 다시 만든다. 기본 24시간
 * @param historyConversations 추천을 만들 때 읽는 최근 대화 수. 기본 20
 * @param retryAfterFailure 만들기가 실패한 뒤 같은 사용자와 에이전트의 추천을 다시 만들지 않는 시간. Hermes 가
 *     막혔을 때 화면이 다시 읽을 때마다 실패한 실행이 쌓이지 않게 한다. 기본 10분
 */
@Validated
@ConfigurationProperties(prefix = "assistant.starters")
public record StarterProperties(
        Boolean enabled, Duration refreshAfter, Integer historyConversations, Duration retryAfterFailure) {

    private static final Duration DEFAULT_REFRESH_AFTER = Duration.ofHours(24);
    private static final int DEFAULT_HISTORY_CONVERSATIONS = 20;
    private static final Duration DEFAULT_RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    public StarterProperties {
        enabled = enabled == null || enabled;
        refreshAfter = refreshAfter == null ? DEFAULT_REFRESH_AFTER : refreshAfter;
        historyConversations = historyConversations == null || historyConversations <= 0
                ? DEFAULT_HISTORY_CONVERSATIONS
                : historyConversations;
        retryAfterFailure = retryAfterFailure == null ? DEFAULT_RETRY_AFTER_FAILURE : retryAfterFailure;
    }
}
