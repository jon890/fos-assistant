package com.bifos.assistant.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 사람이 보낸 대화 turn 의 실행과 그 질문 메시지다(ADR-093).
 *
 * <p>새 질문과 다시 생성의 실행에만 남긴다. 예약 작업, 먼저 살펴보기, 맡긴 일의 결과를 전하는 turn, 맡겨서 도는 실행은 줄이
 * 없다. {@code memory_remember} 가 바로 저장할 수 있는 실행인지를 이 줄로 판정한다.
 */
@Entity
@Table(name = "execution_question")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionQuestion {

    @Id
    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ExecutionQuestion of(Long executionId, Long messageId, Instant now) {
        ExecutionQuestion question = new ExecutionQuestion();
        question.executionId = executionId;
        question.messageId = messageId;
        question.createdAt = now;
        return question;
    }
}
