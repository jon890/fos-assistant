package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.CheckInvalidReason;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 먼저 살펴보기 한 번이다(ADR-080). 시작할 때 만들고 끝날 때 갱신한다.
 *
 * <p>토큰과 금액은 {@code agent_execution} 이 갖고 여기 다시 적지 않는다. 살펴보기 한 번의 비용은 {@code rootExecutionId} 로
 * 그 트리의 실행 줄을 합쳐 얻는다.
 *
 * <p>도구 호출 수와 위임 수는 실행 중에 이 엔티티에서 세지 않는다. 스트림 스레드와 작업 스레드가 같은 엔티티를 고치지 않게, 실행 중의 셈은
 * 부르는 쪽이 들고 끝날 때 넘긴다.
 */
@Entity
@Table(name = "proactive_check")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class ProactiveCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 살펴보기를 연 사용자. 점검 대화의 주인이다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** 결과가 남는 점검 대화. */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 살펴보기 turn 의 실행 줄. 실행 줄을 만들기 전에 실패하면 비어 있다. 살펴보기 트리를 가리는 기준이다. */
    @Column(name = "root_execution_id", unique = true)
    private Long rootExecutionId;

    /** 이 살펴보기를 보낸 점검 대화의 루트 session. session 을 바꿀지 셀 때 쓴다. */
    @Column(name = "hermes_root_session_id", length = 128)
    private String hermesRootSessionId;

    /** {@code trigger} 는 MySQL 의 예약어라 칸 이름을 {@code trigger_type} 으로 둔다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, updatable = false, length = 16)
    private CheckTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CheckStatus status;

    /** {@code SUCCEEDED} 일 때만 채운다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16)
    private CheckOutcome outcome;

    /** 결과 블록을 읽지 못한 까닭. {@code outcome} 이 {@code INVALID_RESULT} 일 때만 채운다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "invalid_reason", length = 32)
    private CheckInvalidReason invalidReason;

    /** {@code FAILED} 와 {@code STOPPED} 의 까닭. 사용자가 멈추면 비어 있다. */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "tool_calls", nullable = false)
    private int toolCalls;

    @Column(name = "delegations", nullable = false)
    private int delegations;

    /**
     * 시작할 때 옮겨 적은 그 에이전트의 「먼저 살펴보기에 쓰기 도구 허용」 이다(ADR-082). 이 살펴보기의 경계는 에이전트 칸이 아니라
     * 이 값이 정한다. 도중에 에이전트 설정을 바꿔도 한 살펴보기 안에서 경계가 바뀌지 않게 하기 위해서다.
     */
    @Column(name = "writes_allowed", nullable = false, updatable = false)
    private boolean writesAllowed;

    @Column(name = "new_findings", nullable = false)
    private int newFindings;

    @Column(name = "reference_findings", nullable = false)
    private int referenceFindings;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    private ProactiveCheck(
            Long userId, Long agentId, Long conversationId, CheckTrigger trigger, boolean writesAllowed, Instant now) {
        this.userId = userId;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.trigger = trigger;
        this.writesAllowed = writesAllowed;
        this.status = CheckStatus.RUNNING;
        this.startedAt = now;
    }

    /** @param writesAllowed 시작하는 지금 그 에이전트의 「먼저 살펴보기에 쓰기 도구 허용」 값 */
    public static ProactiveCheck started(
            Long userId, Long agentId, Long conversationId, CheckTrigger trigger, boolean writesAllowed, Instant now) {
        return new ProactiveCheck(userId, agentId, conversationId, trigger, writesAllowed, now);
    }

    /** 살펴보기 turn 의 실행 줄과 그 turn 을 보낸 루트 session 을 적는다. */
    public void attachRoot(Long rootExecutionId, String hermesRootSessionId) {
        this.rootExecutionId = rootExecutionId;
        this.hermesRootSessionId = hermesRootSessionId;
    }

    public void succeed(
            CheckOutcome outcome, int newFindings, int referenceFindings, int toolCalls, int delegations, Instant now) {
        this.status = CheckStatus.SUCCEEDED;
        this.outcome = outcome;
        this.newFindings = newFindings;
        this.referenceFindings = referenceFindings;
        finish(null, toolCalls, delegations, now);
    }

    /** turn 은 끝났지만 결과 블록을 읽지 못했다. 읽지 못한 까닭을 함께 적는다. */
    public void succeedInvalid(CheckInvalidReason invalidReason, int toolCalls, int delegations, Instant now) {
        this.status = CheckStatus.SUCCEEDED;
        this.outcome = CheckOutcome.INVALID_RESULT;
        this.invalidReason = invalidReason;
        finish(null, toolCalls, delegations, now);
    }

    /** 상한에 닿았거나 사용자가 멈췄다. 사용자가 멈추면 {@code errorCode} 는 null 이다. */
    public void stop(String errorCode, int toolCalls, int delegations, Instant now) {
        this.status = CheckStatus.STOPPED;
        finish(errorCode, toolCalls, delegations, now);
    }

    public void fail(String errorCode, int toolCalls, int delegations, Instant now) {
        this.status = CheckStatus.FAILED;
        finish(errorCode, toolCalls, delegations, now);
    }

    private void finish(String errorCode, int toolCalls, int delegations, Instant now) {
        this.errorCode = errorCode;
        this.toolCalls = toolCalls;
        this.delegations = delegations;
        this.finishedAt = now;
    }
}
