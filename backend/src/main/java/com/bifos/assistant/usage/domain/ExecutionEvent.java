package com.bifos.assistant.usage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 실행 하나가 도는 동안 일어난 일을 우리 이름으로 옮겨 적은 한 줄이다.
 *
 * <p>Hermes 가 보낸 payload 를 통째로 넣지 않고 필요한 칸만 고른다. 도구가 읽어 온 문서 전체가 사건에
 * 실려 오는 것을 그대로 저장하지 않기 위해서다. 근거는 ADR-013 에 있다.
 *
 * <p>문자열 칸의 길이를 마이그레이션과 글자까지 맞춘다. 테스트는 이 엔티티로 스키마를 만들고 운영은
 * Flyway 가 만든 스키마를 검증하므로, 둘이 어긋나면 테스트는 통과하고 배포에서 기동이 실패한다.
 */
@Entity
@Table(name = "execution_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionEvent {

    /** 화면에 한 줄로 보일 만큼만 담는다. 넘으면 자른다. */
    public static final int DETAIL_LIMIT = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    /** 그 실행 안에서의 순서다. 1부터 센다. */
    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private ExecutionEventType eventType;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "subagent_name", length = 128)
    private String subagentName;

    /** 하위 에이전트가 따로 session 을 가지면 적는다. 그 경로가 없으면 비운다. */
    @Column(name = "hermes_session_id", length = 128)
    private String hermesSessionId;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "detail", length = DETAIL_LIMIT)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    private ExecutionEvent(Builder builder) {
        this.executionId = builder.executionId;
        this.sequence = builder.sequence;
        this.eventType = builder.eventType;
        this.toolName = builder.toolName;
        this.subagentName = builder.subagentName;
        this.hermesSessionId = builder.hermesSessionId;
        this.durationMs = builder.durationMs;
        this.detail = builder.detail;
        this.occurredAt = builder.occurredAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long id() {
        return id;
    }

    public Long executionId() {
        return executionId;
    }

    public int sequence() {
        return sequence;
    }

    public ExecutionEventType eventType() {
        return eventType;
    }

    public String toolName() {
        return toolName;
    }

    public String subagentName() {
        return subagentName;
    }

    public String hermesSessionId() {
        return hermesSessionId;
    }

    public Long durationMs() {
        return durationMs;
    }

    public String detail() {
        return detail;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public static final class Builder {
        private Long executionId;
        private int sequence;
        private ExecutionEventType eventType;
        private String toolName;
        private String subagentName;
        private String hermesSessionId;
        private Long durationMs;
        private String detail;
        private Instant occurredAt;

        public Builder executionId(Long executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder sequence(int sequence) {
            this.sequence = sequence;
            return this;
        }

        public Builder eventType(ExecutionEventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder subagentName(String subagentName) {
            this.subagentName = subagentName;
            return this;
        }

        public Builder hermesSessionId(String hermesSessionId) {
            this.hermesSessionId = hermesSessionId;
            return this;
        }

        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        /** 길이가 넘으면 자른다. 잘랐다는 표시는 남기지 않는다. */
        public Builder detail(String detail) {
            this.detail =
                    detail == null || detail.length() <= DETAIL_LIMIT
                            ? detail
                            : detail.substring(0, DETAIL_LIMIT);
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public ExecutionEvent build() {
            return new ExecutionEvent(this);
        }
    }
}
