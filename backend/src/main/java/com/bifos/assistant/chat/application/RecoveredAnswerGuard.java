package com.bifos.assistant.chat.application;

import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.util.Optional;

/**
 * 기동 정리가 다시 붙어 끝낸 대화 turn 의 답을 그대로 대화에 남겨도 되는지 묻는 port 다.
 *
 * <p>{@code chat} 은 구현을 모른다. 먼저 살펴보기 turn 의 답은 Control Plane 이 검사해 그리기 전의 글이라 그대로 남기면 안 된다
 * (ADR-081). {@code proactive} 가 이 인터페이스를 구현한다. 구현이 하나도 없으면 모든 답을 그대로 남긴다.
 */
public interface RecoveredAnswerGuard {

    /**
     * 그 루트 실행의 답 대신 남길 알림 줄의 글이다. 답을 그대로 남겨도 되면 빈 값이다.
     *
     * <p>글이 있으면 답이 비었어도 그 알림 줄 하나를 남긴다. 빈 글이면 답도 알림 줄도 남기지 않는다. 사용자에게 바로 알리지 않는 turn 이다.
     *
     * @param rootExecutionId 기동 정리가 끝낸 대화 turn 의 실행 줄
     * @param ended 그 실행이 끝난 상태. {@code SUCCEEDED} 나 {@code CANCELLED} 다
     */
    Optional<String> noticeInsteadOfAnswer(Long rootExecutionId, ExecutionStatus ended);
}
