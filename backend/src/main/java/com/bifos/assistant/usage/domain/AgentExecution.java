package com.bifos.assistant.usage.domain;

import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.hermes.dto.TokenUsage;
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

/**
 * 사용량과 비용 보고를 위해 남기는 에이전트 turn 하나다.
 *
 * <p>토큰 수는 실행마다 남긴다. 비용은 가격표가 그 모델을 알 때 공개된 API 가격으로 환산해 적고,
 * 모르면 비워 둔다. 구독형 바인딩도 같은 환산값을 받는다. 구성원이 그것을 구독료와 견주기 위해서다.
 * 금액은 데이터베이스에서 반올림이 일어나지 않도록 마이크로 단위 정수로 둔다.
 */
@Entity
@Table(name = "agent_execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "parent_execution_id")
    private Long parentExecutionId;

    @Column(name = "root_execution_id")
    private Long rootExecutionId;

    @Column(name = "profile_name", nullable = false, length = 64)
    private String profileName;

    @Column(name = "hermes_run_id", length = 128)
    private String hermesRunId;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_mode", nullable = false, length = 20)
    private CostMode costMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "cached_input_tokens")
    private Long cachedInputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "context_chars")
    private Long contextChars;

    /**
     * 자리가 없어 이 실행의 문맥에서 빠진 Memory 항목 수.
     *
     * <p>0 보다 크면 요청자가 볼 수 있는 Memory 를 전부 싣지 못한 실행이다. 이 칸이 생기기 전의
     * 기록과 문맥을 조립하지 않은 실행은 비어 있다.
     */
    @Column(name = "context_omitted_items")
    private Integer contextOmittedItems;

    /**
     * 실행 당시 Hermes 의 고정 프롬프트 구성을 가리키는 지문.
     *
     * <p>그 값을 주는 HTTP 경로가 아직 없어 지금은 항상 비어 있다. 값을 얻게 되는 날 칸을 다시 만들지
     * 않도록 미리 둔다.
     */
    @Column(name = "runtime_fingerprint", length = 64)
    private String runtimeFingerprint;

    /**
     * 이 실행에 넣은 {@code instructions} 의 SHA-256 앞 16바이트를 16진수로 적은 값.
     *
     * <p>{@code context_chars} 가 길이를 말하고 이 칸이 내용이 같은지를 말한다. 본문에 개인 Memory 가
     * 들어 있어 본문 자체는 어디에도 저장하지 않는다. 넣은 문맥이 없으면 비운다.
     */
    @Column(name = "instructions_hash", length = 64)
    private String instructionsHash;

    /** 통화 단위의 100만분의 1로 적은 환산 금액. 가격을 찾지 못했으면 null 이다. */
    @Column(name = "estimated_cost_micros")
    private Long estimatedCostMicros;

    @Column(name = "actual_cost_micros")
    private Long actualCostMicros;

    @Column(name = "cost_currency", length = 3, columnDefinition = "CHAR(3)")
    private String costCurrency;

    /** 계산에 쓴 가격표를 적는다. 나중에 가격이 바뀌어도 지난 기록이 다시 쓰이지 않게 한다. */
    @Column(name = "pricing_version", length = 32)
    private String pricingVersion;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    private AgentExecution(Builder builder) {
        this.userId = builder.userId;
        this.conversationId = builder.conversationId;
        this.agentId = builder.agentId;
        this.parentExecutionId = builder.parentExecutionId;
        this.rootExecutionId = builder.rootExecutionId;
        this.profileName = builder.profileName;
        this.hermesRunId = builder.hermesRunId;
        this.provider = builder.provider;
        this.model = builder.model;
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
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public Long agentId() { return agentId; }

    public Long parentExecutionId() { return parentExecutionId; }

    public Long rootExecutionId() { return rootExecutionId; }

    public String profileName() {
        return profileName;
    }

    public String hermesRunId() {
        return hermesRunId;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public CostMode costMode() {
        return costMode;
    }

    public ExecutionStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public Long inputTokens() {
        return inputTokens;
    }

    public Long cachedInputTokens() {
        return cachedInputTokens;
    }

    public Long outputTokens() {
        return outputTokens;
    }

    public Long totalTokens() {
        return totalTokens;
    }

    public Long latencyMs() {
        return latencyMs;
    }

    public Long contextChars() { return contextChars; }

    public Integer contextOmittedItems() { return contextOmittedItems; }

    public String runtimeFingerprint() { return runtimeFingerprint; }

    public String instructionsHash() { return instructionsHash; }

    public Long estimatedCostMicros() {
        return estimatedCostMicros;
    }

    public Long actualCostMicros() { return actualCostMicros; }

    public String costCurrency() {
        return costCurrency;
    }

    public String pricingVersion() {
        return pricingVersion;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    /** 실행을 제출한 직후 Hermes 가 준 run 번호를 적는다. */
    public void attachRunId(String hermesRunId) {
        this.hermesRunId = hermesRunId;
    }

    /** 끝난 시각과 토큰과 금액을 채우고 SUCCEEDED 로 옮긴다. */
    public void markSucceeded(
            String provider, String model, TokenUsage usage, EstimatedCost cost, Instant finishedAt) {
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
        this.status = ExecutionStatus.SUCCEEDED;
    }

    /** 끝난 시각과 토큰과 환산액과 실제 청구액을 채우고 SUCCEEDED 로 옮긴다. */
    public void markSucceeded(
            String provider, String model, TokenUsage usage, ExecutionCost cost, Instant finishedAt) {
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
        this.status = ExecutionStatus.SUCCEEDED;
    }

    /** 끝난 시각과 오류 코드를 채우고 FAILED 로 옮긴다. */
    public void markFailed(String errorCode, Instant finishedAt) {
        this.errorCode = errorCode;
        this.finishedAt = finishedAt;
        this.latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        this.status = ExecutionStatus.FAILED;
    }

    public static final class Builder {
        private Long userId;
        private Long conversationId;
        private Long agentId;
        private Long parentExecutionId;
        private Long rootExecutionId;
        private String profileName;
        private String hermesRunId;
        private String provider;
        private String model;
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

        public Builder profileName(String profileName) {
            this.profileName = profileName;
            return this;
        }

        public Builder hermesRunId(String hermesRunId) {
            this.hermesRunId = hermesRunId;
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
