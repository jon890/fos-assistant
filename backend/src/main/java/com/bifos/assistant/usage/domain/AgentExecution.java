package com.bifos.assistant.usage.domain;

import com.bifos.assistant.credential.domain.CostMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One agent turn, recorded for usage and cost reporting.
 *
 * <p>토큰 수는 실행마다 남긴다. 비용은 가격표가 그 모델을 알 때 공개된 API 가격으로 환산해 적고,
 * 모르면 비워 둔다. 구독형 바인딩도 같은 환산값을 받는다. 구성원이 그것을 구독료와 견주기 위해서다.
 * 금액은 데이터베이스에서 반올림이 일어나지 않도록 마이크로 단위 정수로 둔다.
 */
@Entity
@Table(name = "agent_execution")
public class AgentExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** Workspace the conversation ran in. Null when the conversation has none. */
    @Column(name = "workspace_id")
    private Long workspaceId;

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

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    /** 통화 단위의 100만분의 1로 적은 환산 금액. 가격을 찾지 못했으면 null 이다. */
    @Column(name = "estimated_cost_micros")
    private Long estimatedCostMicros;

    @Column(name = "cost_currency", length = 3)
    private String costCurrency;

    /** 계산에 쓴 가격표를 적는다. 나중에 가격이 바뀌어도 지난 기록이 다시 쓰이지 않게 한다. */
    @Column(name = "pricing_version", length = 32)
    private String pricingVersion;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    protected AgentExecution() {
    }

    private AgentExecution(Builder builder) {
        this.userId = builder.userId;
        this.conversationId = builder.conversationId;
        this.workspaceId = builder.workspaceId;
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
        this.estimatedCostMicros = builder.estimatedCostMicros;
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

    public Long workspaceId() {
        return workspaceId;
    }

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

    public long latencyMs() {
        return latencyMs;
    }

    public Long estimatedCostMicros() {
        return estimatedCostMicros;
    }

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

    public static final class Builder {
        private Long userId;
        private Long conversationId;
        private Long workspaceId;
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
        private long latencyMs;
        private Long estimatedCostMicros;
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

        public Builder workspaceId(Long workspaceId) {
            this.workspaceId = workspaceId;
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

        public Builder timing(Instant startedAt, Instant finishedAt) {
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
            return this;
        }

        public AgentExecution build() {
            return new AgentExecution(this);
        }
    }
}
