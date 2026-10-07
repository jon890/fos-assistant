package com.bifos.assistant.usage.domain;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.usage.domain.type.EventObservation;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.domain.type.ReasoningEffortSource;
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
 * 사용량과 비용 보고를 위해 남기는 에이전트 turn 하나다.
 *
 * <p>토큰 수는 실행마다 남긴다. 비용은 가격표가 그 모델을 알 때 공개된 API 가격으로 환산해 적고,
 * 모르면 비워 둔다. 구독형 바인딩도 같은 환산값을 받는다. 사용자가 그것을 구독료와 견주기 위해서다.
 * 금액은 데이터베이스에서 반올림이 일어나지 않도록 마이크로 단위 정수로 둔다.
 */
@Entity
@Table(name = "agent_execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class AgentExecution {

    /** 과거 실행은 UNKNOWN 이다. 새 제출은 비수집을 먼저 표시하고 수집 경로가 OBSERVING 으로 바꾼다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_observation", nullable = false, length = 20)
    private EventObservation eventObservation = EventObservation.UNKNOWN;

    private void closeInterruptedObservation() {
        if (eventObservation == EventObservation.OBSERVING) {
            eventObservation = EventObservation.INCOMPLETE;
        }
    }

    public void beginEventObservation() {
        eventObservation = EventObservation.OBSERVING;
    }

    /** 실패 표시는 뒤늦은 정상 스트림 종료로 지우지 않는다. */
    public boolean finishEventObservation(boolean complete) {
        EventObservation next = complete && eventObservation == EventObservation.OBSERVING
                ? EventObservation.OBSERVED
                : EventObservation.INCOMPLETE;
        if (complete && eventObservation != EventObservation.OBSERVING || eventObservation == next) {
            return false;
        }
        eventObservation = next;
        return true;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "user_id", nullable = false)
    @Getter
    private Long userId;

    /** 이 실행이 속한 대화. 추천 질문을 만드는 실행처럼 대화 없이 돈 실행은 비어 있다. */
    @Column(name = "conversation_id")
    @Getter
    private Long conversationId;

    @Column(name = "agent_id")
    @Getter
    private Long agentId;

    @Column(name = "parent_execution_id")
    @Getter
    private Long parentExecutionId;

    @Column(name = "root_execution_id")
    @Getter
    private Long rootExecutionId;

    /**
     * 막혀서 다음 모델로 넘어갈 때 만든 실행이 가리키는 직전 실행.
     *
     * <p>자식이 아니라 같은 turn 을 다시 시도한 것이라 {@code parentExecutionId} 를 쓰지 않는다. 첫
     * 시도는 비어 있다.
     */
    @Column(name = "retry_of_execution_id")
    @Getter
    private Long retryOfExecutionId;

    @Column(name = "profile_name", nullable = false, length = 64)
    @Getter
    private String profileName;

    @Column(name = "hermes_run_id", length = 128)
    @Getter
    private String hermesRunId;

    /**
     * 이 실행이 속한 Hermes session. 제출하기 전에 적는다.
     *
     * <p>대화 turn 은 그 대화의 루트 session 을 적는다. 압축 교체로 Hermes 에 보낸 session 이 바뀌어도
     * MCP {@code agent_*} 호출이 들고 오는 서명한 루트 session 으로 이 실행을 찾게 하기 위해서다
     * (ADR-031). 루트가 없는 옛 대화는 보낸 session 을 적는다. 흐름의 하위 실행은 Control Plane 이 정한
     * {@code fos-<uuid>} 이고, Memory 제안은 비어 있다.
     */
    @Column(name = "hermes_session_id", length = 128)
    @Getter
    private String hermesSessionId;

    /** 다른 에이전트에게 맡겨 만든 실행만 채운다. 같은 호출이 다시 와도 실행을 하나만 만든다. */
    @Column(name = "delegation_key", unique = true, length = 64)
    @Getter
    private String delegationKey;

    /** 다른 에이전트에게 맡겨 만든 실행이 끝났을 때의 답. 대화 turn 의 답은 {@code chat_message} 가 갖는다. */
    @Column(name = "output_text", columnDefinition = "MEDIUMTEXT")
    @Getter
    private String outputText;

    @Column(name = "provider", length = 64)
    @Getter
    private String provider;

    @Column(name = "model", length = 128)
    @Getter
    private String model;

    /** 이 실행에 요청한 reasoning effort. 기본값으로 보냈으면 비어 있다. */
    @Column(name = "reasoning_effort", length = 16)
    @Getter
    private String reasoningEffort;

    @Enumerated(EnumType.STRING)
    @Column(name = "reasoning_effort_source", length = 20)
    @Getter
    private ReasoningEffortSource reasoningEffortSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_tier", length = 16)
    @Getter
    private ModelTier modelTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_mode", nullable = false, length = 20)
    @Getter
    private CostMode costMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Getter
    private ExecutionStatus status;

    @Column(name = "error_code", length = 64)
    @Getter
    private String errorCode;

    @Column(name = "input_tokens")
    @Getter
    private Long inputTokens;

    @Column(name = "cached_input_tokens")
    @Getter
    private Long cachedInputTokens;

    @Column(name = "output_tokens")
    @Getter
    private Long outputTokens;

    @Column(name = "total_tokens")
    @Getter
    private Long totalTokens;

    @Column(name = "latency_ms")
    @Getter
    private Long latencyMs;

    @Column(name = "context_chars")
    @Getter
    private Long contextChars;

    /**
     * 자리가 없어 이 실행의 문맥에서 빠진 Memory 항목 수.
     *
     * <p>0 보다 크면 요청자가 볼 수 있는 Memory 를 전부 싣지 못한 실행이다. 이 칸이 생기기 전의
     * 기록과 문맥을 조립하지 않은 실행은 비어 있다.
     */
    @Column(name = "context_omitted_items")
    @Getter
    private Integer contextOmittedItems;

    /**
     * 실행 당시 Hermes 의 고정 프롬프트 구성을 가리키는 지문.
     *
     * <p>그 값을 주는 HTTP 경로가 아직 없어 지금은 항상 비어 있다. 값을 얻게 되는 날 칸을 다시 만들지
     * 않도록 미리 둔다.
     */
    @Column(name = "runtime_fingerprint", length = 64)
    @Getter
    private String runtimeFingerprint;

    /**
     * 이 실행에 넣은 {@code instructions} 의 SHA-256 앞 16바이트를 16진수로 적은 값.
     *
     * <p>{@code context_chars} 가 길이를 말하고 이 칸이 내용이 같은지를 말한다. 본문에 개인 Memory 가
     * 들어 있어 본문 자체는 어디에도 저장하지 않는다. 넣은 문맥이 없으면 비운다.
     */
    @Column(name = "instructions_hash", length = 64)
    @Getter
    private String instructionsHash;

    /** 통화 단위의 100만분의 1로 적은 환산 금액. 가격을 찾지 못했으면 null 이다. */
    @Column(name = "estimated_cost_micros")
    @Getter
    private Long estimatedCostMicros;

    @Column(name = "actual_cost_micros")
    @Getter
    private Long actualCostMicros;

    @Column(name = "cost_currency", length = 3, columnDefinition = "CHAR(3)")
    @Getter
    private String costCurrency;

    /** 계산에 쓴 가격표를 적는다. 나중에 가격이 바뀌어도 지난 기록이 다시 쓰이지 않게 한다. */
    @Column(name = "pricing_version", length = 32)
    @Getter
    private String pricingVersion;

    @Column(name = "started_at", nullable = false)
    @Getter
    private Instant startedAt;

    @Column(name = "finished_at")
    @Getter
    private Instant finishedAt;

    @Column(name = "request_received_at")
    @Getter
    private Instant requestReceivedAt;

    @Column(name = "submitted_at")
    @Getter
    private Instant submittedAt;

    @Column(name = "first_delta_at")
    @Getter
    private Instant firstDeltaAt;

    /** profile 기본 모델 설정을 정상으로 읽어 이 실행의 기본 강도를 확인한 시각이다. */
    @Column(name = "reasoning_defaults_checked_at")
    @Getter
    private Instant reasoningDefaultsCheckedAt;

    /**
     * 이 실행의 끝난 결과를 부모 대화에 전한 시각이다. 전하지 않았으면 비어 있다.
     *
     * <p>부모가 {@code agent_status} 나 {@code agent_stop} 으로 결과를 직접 받았을 때도 적는다. 한 실행의 결과는
     * 한 번만 전하므로 {@code AgentExecutionRepository#markResultDelivered} 가 비어 있을 때만 채운다.
     */
    // 엔티티 저장이 이 칸을 메모리의 옛 값으로 덮지 않게 한다. 도는 위임 자식에 적은 표시가 그 자식이 끝날 때 지워지면 안 된다.
    @Column(name = "result_delivered_at", updatable = false)
    @Getter
    private Instant resultDeliveredAt;

    private AgentExecution(Builder builder) {
        this.userId = builder.userId;
        this.conversationId = builder.conversationId;
        this.agentId = builder.agentId;
        this.parentExecutionId = builder.parentExecutionId;
        this.rootExecutionId = builder.rootExecutionId;
        this.retryOfExecutionId = builder.retryOfExecutionId;
        this.profileName = builder.profileName;
        this.hermesRunId = builder.hermesRunId;
        this.hermesSessionId = builder.hermesSessionId;
        this.delegationKey = builder.delegationKey;
        this.provider = builder.provider;
        this.model = builder.model;
        this.reasoningEffort = builder.reasoningEffort;
        this.reasoningEffortSource = builder.reasoningEffortSource;
        this.modelTier = builder.modelTier;
        this.costMode = builder.costMode;
        this.status = builder.status;
        this.errorCode = builder.errorCode;
        this.inputTokens = builder.inputTokens;
        this.cachedInputTokens = builder.cachedInputTokens;
        this.outputTokens = builder.outputTokens;
        this.totalTokens = builder.totalTokens;
        this.latencyMs = builder.latencyMs;
        this.contextChars = builder.contextChars;
        this.contextOmittedItems = builder.contextOmittedItems;
        this.runtimeFingerprint = builder.runtimeFingerprint;
        this.instructionsHash = builder.instructionsHash;
        this.estimatedCostMicros = builder.estimatedCostMicros;
        this.actualCostMicros = builder.actualCostMicros;
        this.costCurrency = builder.costCurrency;
        this.pricingVersion = builder.pricingVersion;
        this.startedAt = builder.startedAt;
        this.finishedAt = builder.finishedAt;
        this.requestReceivedAt = builder.requestReceivedAt;
        this.submittedAt = builder.submittedAt;
        this.firstDeltaAt = builder.firstDeltaAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 이 실행이 속한 실행 트리의 루트 번호다.
     *
     * <p>루트 자신은 {@code rootExecutionId} 가 비어 있으므로 자기 번호를 쓴다. 자식을 열 때와 위임 실행을 물을 때
     * 같은 규칙으로 트리를 정한다.
     */
    public Long treeRootId() {
        return rootExecutionId == null ? id : rootExecutionId;
    }

    /** 실행을 제출한 직후 Hermes 가 준 run 번호를 적는다. */
    public void attachRunId(String hermesRunId) {
        this.hermesRunId = hermesRunId;
        if (hermesRunId != null && eventObservation == EventObservation.UNKNOWN) {
            eventObservation = EventObservation.INCOMPLETE;
        }
    }

    /** Hermes 에 제출하기 직전 시각은 한 번만 적는다. */
    public void markSubmitted(Instant at) {
        if (submittedAt == null) {
            submittedAt = at;
            eventObservation = EventObservation.INCOMPLETE;
        }
    }

    /** Flow 루트가 먼저 받은 원래 요청 시각을 보존하려고 더 이른 시각만 적는다. */
    public void markRequestReceived(Instant at) {
        if (at != null && (requestReceivedAt == null || at.isBefore(requestReceivedAt))) {
            requestReceivedAt = at;
        }
    }

    /** 첫 assistant delta를 받은 시각만 보존한다. 본문은 실행 기록에 두지 않는다. */
    public boolean markFirstDelta(Instant at) {
        if (firstDeltaAt == null) {
            firstDeltaAt = at;
            return true;
        }
        return false;
    }

    /** profile 설정에서 읽은 기본값으로 요청 effort의 출처를 보완한다. */
    public void recordProfileReasoningDefault(String effort) {
        if (reasoningEffortSource != ReasoningEffortSource.UNKNOWN || effort == null || effort.isBlank()) {
            return;
        }
        reasoningEffortSource = ReasoningEffortSource.PROFILE_DEFAULT;
        if (reasoningEffort == null) {
            reasoningEffort = effort;
        }
    }

    /** profile 기본 모델 설정의 정상 응답을 확인했음을 적어 빈 effort를 다시 조회하지 않는다. */
    public void markReasoningDefaultsChecked(Instant at) {
        if (reasoningDefaultsCheckedAt == null) {
            reasoningDefaultsCheckedAt = at;
        }
    }

    /** 끝난 답을 적는다. 다른 에이전트에게 맡겨 만든 실행만 쓴다. */
    public void recordOutput(String outputText) {
        this.outputText = outputText;
    }

    /** 끝난 시각과 토큰과 금액을 채우고 SUCCEEDED 로 옮긴다. */
    public void markSucceeded(String provider, String model, TokenUsage usage, EstimatedCost cost, Instant finishedAt) {
        this.provider = provider;
        this.model = model;
        this.inputTokens = usage.inputTokens();
        this.cachedInputTokens = usage.cachedInputTokens();
        this.outputTokens = usage.outputTokens();
        this.totalTokens = usage.totalTokens();
        this.estimatedCostMicros = cost.micros();
        this.costCurrency = cost.currency();
        this.pricingVersion = cost.pricingVersion();
        this.finishedAt = finishedAt;
        this.latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        closeInterruptedObservation();
        this.status = ExecutionStatus.SUCCEEDED;
    }

    /** 끝난 시각과 토큰과 환산액과 실제 청구액을 채우고 SUCCEEDED 로 옮긴다. */
    public void markSucceeded(String provider, String model, TokenUsage usage, ExecutionCost cost, Instant finishedAt) {
        this.provider = provider;
        this.model = model;
        this.inputTokens = usage.inputTokens();
        this.cachedInputTokens = usage.cachedInputTokens();
        this.outputTokens = usage.outputTokens();
        this.totalTokens = usage.totalTokens();
        this.estimatedCostMicros = cost.estimatedMicros();
        this.actualCostMicros = cost.actualMicros();
        this.costCurrency = cost.currency();
        this.pricingVersion = cost.pricingVersion();
        this.finishedAt = finishedAt;
        this.latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        closeInterruptedObservation();
        this.status = ExecutionStatus.SUCCEEDED;
    }

    /** 실패 응답에 포함된 모델과 토큰과 금액을 보존하고 FAILED 로 옮긴다. */
    public void markFailed(
            String provider, String model, TokenUsage usage, ExecutionCost cost, String errorCode, Instant finishedAt) {
        this.provider = provider;
        this.model = model;
        this.inputTokens = usage.inputTokens();
        this.cachedInputTokens = usage.cachedInputTokens();
        this.outputTokens = usage.outputTokens();
        this.totalTokens = usage.totalTokens();
        this.estimatedCostMicros = cost.estimatedMicros();
        this.actualCostMicros = cost.actualMicros();
        this.costCurrency = cost.currency();
        this.pricingVersion = cost.pricingVersion();
        markFailed(errorCode, finishedAt);
    }

    /** 끝난 시각과 오류 코드를 채우고 FAILED 로 옮긴다. */
    public void markFailed(String errorCode, Instant finishedAt) {
        this.errorCode = errorCode;
        this.finishedAt = finishedAt;
        this.latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        closeInterruptedObservation();
        this.status = ExecutionStatus.FAILED;
    }

    /** 끝난 시각과 토큰과 금액을 채우고 CANCELLED 로 옮긴다. */
    public void markCancelled(String provider, String model, TokenUsage usage, ExecutionCost cost, Instant finishedAt) {
        this.provider = provider;
        this.model = model;
        this.inputTokens = usage.inputTokens();
        this.cachedInputTokens = usage.cachedInputTokens();
        this.outputTokens = usage.outputTokens();
        this.totalTokens = usage.totalTokens();
        this.estimatedCostMicros = cost.estimatedMicros();
        this.actualCostMicros = cost.actualMicros();
        this.costCurrency = cost.currency();
        this.pricingVersion = cost.pricingVersion();
        markCancelled(finishedAt);
    }

    /** 이미 토큰을 적은 실행의 상태만 CANCELLED 로 옮긴다. */
    public void markCancelled(Instant finishedAt) {
        // Chief 가 끝난 뒤 자식 단계에서 취소될 수 있다. 그 경우 Chief 자신의 소요 시간과
        // 토큰·비용은 이미 확정됐으므로 상태만 바꾼다.
        if (this.finishedAt == null) {
            this.finishedAt = finishedAt;
            this.latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
        closeInterruptedObservation();
        this.status = ExecutionStatus.CANCELLED;
    }

    public static final class Builder {
        private Long userId;
        private Long conversationId;
        private Long agentId;
        private Long parentExecutionId;
        private Long rootExecutionId;
        private Long retryOfExecutionId;
        private String profileName;
        private String hermesRunId;
        private String hermesSessionId;
        private String delegationKey;
        private String provider;
        private String model;
        private String reasoningEffort;
        private ReasoningEffortSource reasoningEffortSource;
        private ModelTier modelTier;
        private CostMode costMode;
        private ExecutionStatus status;
        private String errorCode;
        private Long inputTokens;
        private Long cachedInputTokens;
        private Long outputTokens;
        private Long totalTokens;
        private Long latencyMs;
        private Long contextChars;
        private Integer contextOmittedItems;
        private String runtimeFingerprint;
        private String instructionsHash;
        private Long estimatedCostMicros;
        private Long actualCostMicros;
        private String costCurrency;
        private String pricingVersion;
        private Instant startedAt;
        private Instant finishedAt;
        private Instant requestReceivedAt;
        private Instant submittedAt;
        private Instant firstDeltaAt;

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder conversationId(Long conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder agentId(Long agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder parentExecutionId(Long parentExecutionId) {
            this.parentExecutionId = parentExecutionId;
            return this;
        }

        public Builder rootExecutionId(Long rootExecutionId) {
            this.rootExecutionId = rootExecutionId;
            return this;
        }

        public Builder retryOfExecutionId(Long retryOfExecutionId) {
            this.retryOfExecutionId = retryOfExecutionId;
            return this;
        }

        public Builder profileName(String profileName) {
            this.profileName = profileName;
            return this;
        }

        public Builder hermesRunId(String hermesRunId) {
            this.hermesRunId = hermesRunId;
            return this;
        }

        public Builder hermesSessionId(String hermesSessionId) {
            this.hermesSessionId = hermesSessionId;
            return this;
        }

        public Builder delegationKey(String delegationKey) {
            this.delegationKey = delegationKey;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        public Builder reasoningEffortSource(ReasoningEffortSource reasoningEffortSource) {
            this.reasoningEffortSource = reasoningEffortSource;
            return this;
        }

        public Builder modelTier(ModelTier modelTier) {
            this.modelTier = modelTier;
            return this;
        }

        public Builder requestReceivedAt(Instant requestReceivedAt) {
            this.requestReceivedAt = requestReceivedAt;
            return this;
        }

        public Builder costMode(CostMode costMode) {
            this.costMode = costMode;
            return this;
        }

        public Builder status(ExecutionStatus status) {
            this.status = status;
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder tokens(Long input, Long cachedInput, Long output, Long total) {
            this.inputTokens = input;
            this.cachedInputTokens = cachedInput;
            this.outputTokens = output;
            this.totalTokens = total;
            return this;
        }

        /** 비용을 모르면 금액 칸을 모두 null 로 둔다. 어느 것도 공짜로 읽히지 않게 하기 위해서다. */
        public Builder cost(EstimatedCost cost) {
            this.estimatedCostMicros = cost.micros();
            this.costCurrency = cost.currency();
            this.pricingVersion = cost.pricingVersion();
            return this;
        }

        /** 환산액과 실제 청구액을 함께 채운다. 구독 경로는 실제 청구액이 null 로 남는다. */
        public Builder cost(ExecutionCost cost) {
            this.estimatedCostMicros = cost.estimatedMicros();
            this.actualCostMicros = cost.actualMicros();
            this.costCurrency = cost.currency();
            this.pricingVersion = cost.pricingVersion();
            return this;
        }

        public Builder timing(Instant startedAt, Instant finishedAt) {
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
            return this;
        }

        public Builder contextChars(Long contextChars) {
            this.contextChars = contextChars;
            return this;
        }

        public Builder contextOmittedItems(Integer contextOmittedItems) {
            this.contextOmittedItems = contextOmittedItems;
            return this;
        }

        public Builder runtimeFingerprint(String runtimeFingerprint) {
            this.runtimeFingerprint = runtimeFingerprint;
            return this;
        }

        public Builder instructionsHash(String instructionsHash) {
            this.instructionsHash = instructionsHash;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public AgentExecution build() {
            return new AgentExecution(this);
        }
    }
}
